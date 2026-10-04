"""Align one place: SIFT features, prior-guided matching, incremental SfM, georeferencing.

Input photos are dicts with at least `id`, `file`, `lat`, `lon`, `width`,
`height`, and optionally `altitude`, `gps_accuracy`, `heading`/`has_heading`,
`pitch`/`has_tilt`, `fov_h`. Output poses live in a local east-north-up frame
(metres) around `origin`; rotations map camera to world with the COLMAP camera
convention (x right, y down, z forward).
"""
from __future__ import annotations

import math
import shutil
import struct
import time
from pathlib import Path

import numpy as np
import pycolmap

from . import geo

MAX_POINTS = 60000


def prior_focal_px(p):
    fov = p.get("fov_h") or 0
    if 10 < fov < 150:
        return (p["width"] / 2) / math.tan(math.radians(fov) / 2)
    return 1.2 * max(p["width"], p["height"])  # COLMAP's default guess


def candidate_pairs(photos, enu, max_dist=80.0, max_neighbors=40):
    """Image pairs worth matching, from GPS distance and compass overlap (PRD A1)."""
    n = len(photos)
    if n <= 30:
        return [(i, j) for i in range(n) for j in range(i + 1, n)]
    scored = {i: [] for i in range(n)}
    for i in range(n):
        for j in range(i + 1, n):
            d = float(np.linalg.norm(enu[i][:2] - enu[j][:2]))
            acc = (photos[i].get("gps_accuracy") or 10) + (photos[j].get("gps_accuracy") or 10)
            if d > max(max_dist, 3 * acc):
                continue
            diff = 0.0
            if photos[i].get("has_heading") and photos[j].get("has_heading"):
                diff = abs((photos[i]["heading"] - photos[j]["heading"] + 180) % 360 - 180)
                if diff > 110 and d > 10:
                    continue  # looking away from each other: little shared content
            score = d + 0.2 * diff
            scored[i].append((score, j))
            scored[j].append((score, i))
    pairs = set()
    for i, lst in scored.items():
        for _, j in sorted(lst)[:max_neighbors]:
            pairs.add((min(i, j), max(i, j)))
    return sorted(pairs)


def align(photos, image_dir: Path, work: Path, log=print, num_threads=-1, use_priors=False):
    t0 = time.time()
    work.mkdir(parents=True, exist_ok=True)
    db_path = work / "database.db"
    if db_path.exists():
        db_path.unlink()

    lat0 = float(np.mean([p["lat"] for p in photos]))
    lon0 = float(np.mean([p["lon"] for p in photos]))
    alts = [p["altitude"] for p in photos if p.get("altitude")]
    alt0 = float(np.median(alts)) if alts else 0.0
    enu = [geo.to_enu(p["lat"], p["lon"], p.get("altitude") or alt0, lat0, lon0, alt0) for p in photos]
    by_name = {p["file"]: (p, e) for p, e in zip(photos, enu)}

    # --- features
    extraction = pycolmap.FeatureExtractionOptions()
    extraction.max_image_size = 1600
    extraction.num_threads = num_threads
    extraction.sift.max_num_features = 8192
    reader = pycolmap.ImageReaderOptions()
    reader.camera_model = "SIMPLE_RADIAL"
    pycolmap.extract_features(db_path, image_dir, image_names=[p["file"] for p in photos],
                              camera_mode=pycolmap.CameraMode.PER_IMAGE,
                              reader_options=reader, extraction_options=extraction)
    log(f"features: {time.time() - t0:.0f}s")

    # --- camera and position priors (PRD A3)
    db = pycolmap.Database.open(db_path)
    name_to_id = {}
    for image in db.read_all_images():
        p, e = by_name[image.name]
        name_to_id[image.name] = image.image_id
        cam = db.read_camera(image.camera_id)
        f = prior_focal_px(p)
        sx, sy = cam.width / p["width"], cam.height / p["height"]
        cam.params = [f * sx, cam.width / 2, cam.height / 2, 0.0]
        cam.has_prior_focal_length = bool(p.get("fov_h"))
        db.update_camera(cam)
        sigma_h = max(p.get("gps_accuracy") or 10.0, 2.0)
        prior = pycolmap.PosePrior(
            position=e,
            position_covariance=np.diag([sigma_h ** 2, sigma_h ** 2, (2 * sigma_h) ** 2]),
            coordinate_system=pycolmap.PosePriorCoordinateSystem.CARTESIAN,
            corr_data_id=image.data_id,
        )
        db.write_pose_prior(prior)
    db.close()

    # --- matching on candidate pairs only
    pairs = candidate_pairs(photos, enu)
    pairs_file = work / "pairs.txt"
    pairs_file.write_text("\n".join(f"{photos[i]['file']} {photos[j]['file']}" for i, j in pairs))
    matching = pycolmap.FeatureMatchingOptions()
    matching.num_threads = num_threads
    pycolmap.match_image_pairs(db_path, matching_options=matching,
                               pairing_options=pycolmap.ImportedPairingOptions(match_list_path=pairs_file))
    log(f"matching {len(pairs)} pairs: {time.time() - t0:.0f}s")

    # --- incremental mapping
    options = pycolmap.IncrementalPipelineOptions()
    options.num_threads = num_threads
    options.use_prior_position = use_priors
    options.min_model_size = 3
    out = work / "sparse"
    shutil.rmtree(out, ignore_errors=True)
    out.mkdir()
    models = pycolmap.incremental_mapping(db_path, image_dir, out, options)
    if not models:
        raise RuntimeError("no model: too few matches between photos")
    rec = max(models.values(), key=lambda r: r.num_reg_images())
    log(f"mapping: {rec.num_reg_images()}/{len(photos)} registered, "
        f"{rec.num_points3D()} points, {time.time() - t0:.0f}s")

    result = georeference(rec, by_name, (lat0, lon0, alt0), log)
    result["stats"].update(seconds=round(time.time() - t0, 1), pairs=len(pairs),
                           models=len(models), photos=len(photos))
    return result


def georeference(rec, by_name, origin, log=print):
    """Rotate the model upright (gravity), then fit it to GPS in the horizontal plane (PRD A5)."""
    images = [rec.image(i) for i in rec.reg_image_ids()]
    R_wc = [im.cam_from_world().rotation.matrix().T for im in images]  # camera -> model
    centers = np.array([im.projection_center() for im in images])
    axes = [r[:, 2] for r in R_wc]
    rights = [r[:, 0] for r in R_wc]
    metas = [by_name[im.name][0] for im in images]
    gps = np.array([by_name[im.name][1] for im in images])
    pitches = [m.get("pitch") if m.get("has_tilt") else None for m in metas]

    up = geo.estimate_up(rights, axes, pitches)
    # sign: cameras' image-up (-y) should mostly point up
    if np.mean([np.dot(-r[:, 1], up) for r in R_wc]) < 0:
        up = -up
    Rg = geo.rotation_between(up, np.array([0.0, 0, 1]))
    cbar = centers.mean(0)
    level = (Rg @ (centers - cbar).T).T

    acc = np.median([m.get("gps_accuracy") or 10.0 for m in metas])
    spread = np.linalg.norm(gps[:, :2] - gps[:, :2].mean(0), axis=1).max()
    if spread > 2 * acc and len(images) >= 3:
        s, R2, t2, inliers = geo.ransac_similarity_2d(level[:, :2], gps[:, :2], threshold=max(3 * acc, 8.0))
        method = "gps"
    else:
        # all photos from about one spot: GPS fixes position only, compass fixes yaw
        model_heading = [geo.heading_of(Rg @ a) for a in axes]
        diffs = [m["heading"] - h for m, h in zip(metas, model_heading) if m.get("has_heading")]
        yaw = math.radians(geo.circular_mean_deg(diffs)) if diffs else 0.0
        R2 = np.array([[math.cos(yaw), math.sin(yaw)], [-math.sin(yaw), math.cos(yaw)]])
        s, inliers = 1.0, np.ones(len(images), bool)
        t2 = gps[:, :2].mean(0) - R2 @ level[:, :2].mean(0)
        method = "compass"
    M = np.eye(3)
    M[:2, :2] = R2
    M = M @ Rg  # model -> ENU rotation

    enu_c = s * level.copy()
    enu_c[:, :2] = (s * (R2 @ level[:, :2].T)).T + t2
    has_alt = np.array([bool(m.get("altitude")) for m in metas])
    tz = float(np.median(gps[has_alt, 2] - enu_c[has_alt, 2])) if any(has_alt) else -float(np.median(enu_c[:, 2]))
    enu_c[:, 2] += tz
    shift = np.array([*t2, tz])

    def point_to_enu(p):
        return s * (M @ (np.asarray(p) - cbar)) + shift

    # per-image observations, depth and error
    poses = []
    for k, im in enumerate(images):
        meta = metas[k]
        cam = im.camera
        cfw = im.cam_from_world()
        depths, errors = [], []
        for p2d in im.points2D:
            if p2d.has_point3D():
                pt = rec.point3D(p2d.point3D_id)
                depths.append((cfw * pt.xyz)[2])
                errors.append(pt.error)
        R_enu = M @ R_wc[k]
        q = pycolmap.Rotation3d(R_enu).quat  # xyzw
        f = cam.params[0]
        gps_res = float(np.linalg.norm(enu_c[k][:2] - gps[k][:2]))
        heading = geo.heading_of(R_enu[:, 2])
        head_res = abs((heading - meta["heading"] + 180) % 360 - 180) if meta.get("has_heading") else None
        n_pts = len(depths)
        err = float(np.mean(errors)) if errors else None
        ok = bool(n_pts >= 40 and (err or 9) < 3.0 and inliers[k])
        poses.append(dict(
            photo=meta["id"], ok=ok,
            x=float(enu_c[k][0]), y=float(enu_c[k][1]), z=float(enu_c[k][2]),
            qx=float(q[0]), qy=float(q[1]), qz=float(q[2]), qw=float(q[3]),
            fx=f / cam.width, fy=f / cam.width, cx=cam.params[1] / cam.width, cy=cam.params[2] / cam.height,
            k1=float(cam.params[3]) if len(cam.params) > 3 else 0.0,
            num_points=n_pts, error_px=err,
            median_depth=float(np.median(depths)) * s if depths else None,
            gps_residual_m=gps_res, heading=heading, heading_residual_deg=head_res,
        ))

    pts = [(point_to_enu(p.xyz), p.color) for p in rec.points3D.values()
           if p.track.length() >= 3 and p.error < 2.0]
    if len(pts) > MAX_POINTS:
        idx = np.random.default_rng(0).choice(len(pts), MAX_POINTS, replace=False)
        pts = [pts[i] for i in idx]
    points = b"".join(struct.pack("<fffBBB", *xyz.astype(np.float32), *map(int, rgb)) for xyz, rgb in pts)

    ok_n = sum(p["ok"] for p in poses)
    res = [p["gps_residual_m"] for p in poses if p["ok"]]
    log(f"georeference ({method}): scale {s:.3f}, {ok_n} ok, median GPS residual "
        f"{np.median(res) if res else float('nan'):.1f} m")
    lat0, lon0, alt0 = origin
    return dict(
        origin=dict(lat=lat0, lon=lon0, alt=alt0),
        method=method,
        poses=poses,
        points=points,
        stats=dict(registered=len(images), ok=ok_n, points=len(pts), scale=s,
                   median_gps_residual_m=float(np.median(res)) if res else None),
    )
