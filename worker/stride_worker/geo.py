"""Local east-north-up coordinates and the similarity fits used for georeferencing."""
from __future__ import annotations

import math

import numpy as np

EARTH_R = 6378137.0


def to_enu(lat, lon, alt, lat0, lon0, alt0):
    """Equirectangular local tangent plane; exact enough within a few km."""
    x = math.radians(lon - lon0) * EARTH_R * math.cos(math.radians(lat0))
    y = math.radians(lat - lat0) * EARTH_R
    return np.array([x, y, (alt - alt0) if alt is not None else 0.0])


def from_enu(e, lat0, lon0, alt0):
    lat = lat0 + math.degrees(e[1] / EARTH_R)
    lon = lon0 + math.degrees(e[0] / (EARTH_R * math.cos(math.radians(lat0))))
    return lat, lon, alt0 + e[2]


def rotation_between(a, b):
    """Smallest rotation taking unit vector a onto unit vector b."""
    a = a / np.linalg.norm(a)
    b = b / np.linalg.norm(b)
    v = np.cross(a, b)
    c = float(np.dot(a, b))
    if c < -0.999999:
        axis = np.cross(a, [1.0, 0, 0])
        if np.linalg.norm(axis) < 1e-6:
            axis = np.cross(a, [0, 1.0, 0])
        axis /= np.linalg.norm(axis)
        return 2 * np.outer(axis, axis) - np.eye(3)
    vx = np.array([[0, -v[2], v[1]], [v[2], 0, -v[0]], [-v[1], v[0], 0]])
    return np.eye(3) + vx + vx @ vx / (1 + c)


def estimate_up(right_vecs, axes, pitches):
    """World up direction in model coordinates.

    Cameras are held roughly level, so their right (x) axes are near
    horizontal: up is close to the direction least spanned by them. Where
    the phone measured pitch, the optical axis satisfies up·axis = sin(pitch),
    which also fixes the sign and removes the level-camera assumption's bias.
    `pitches` holds degrees or None per camera.
    """
    R = np.asarray(right_vecs)
    rows, rhs = [R * 0.5], [np.zeros(len(R))]
    measured = [(d, p) for d, p in zip(axes, pitches) if p is not None]
    if len(measured) >= 3:
        D = np.array([d for d, _ in measured])
        rows.append(D)
        rhs.append(np.array([math.sin(math.radians(p)) for _, p in measured]))
        g, *_ = np.linalg.lstsq(np.vstack(rows), np.concatenate(rhs), rcond=None)
        if np.linalg.norm(g) > 1e-6:
            return g / np.linalg.norm(g)
    w, v = np.linalg.eigh(R.T @ R)
    return v[:, 0]


def umeyama_2d(src, dst, with_scale=True):
    """Similarity (s, R 2x2, t) minimising |s R src + t - dst|."""
    mu_s, mu_d = src.mean(0), dst.mean(0)
    a, b = src - mu_s, dst - mu_d
    cov = b.T @ a / len(src)
    U, S, Vt = np.linalg.svd(cov)
    D = np.eye(2)
    if np.linalg.det(U @ Vt) < 0:
        D[1, 1] = -1
    R = U @ D @ Vt
    var = (a ** 2).sum() / len(src)
    s = float(np.trace(np.diag(S) @ D) / var) if with_scale and var > 1e-12 else 1.0
    return s, R, mu_d - s * R @ mu_s


def ransac_similarity_2d(src, dst, threshold, iters=500, seed=0):
    """Robust 2D similarity; returns (s, R, t, inlier mask)."""
    n = len(src)
    rng = np.random.default_rng(seed)
    best = np.zeros(n, bool)
    if n < 2:
        return 1.0, np.eye(2), (dst - src).mean(0) if n else np.zeros(2), np.ones(n, bool)
    for _ in range(iters):
        i, j = rng.choice(n, 2, replace=False)
        if np.linalg.norm(src[i] - src[j]) < 1e-9:
            continue
        s, R, t = umeyama_2d(src[[i, j]], dst[[i, j]])
        err = np.linalg.norm((s * (R @ src.T)).T + t - dst, axis=1)
        inl = err < threshold
        if inl.sum() > best.sum():
            best = inl
    if best.sum() < 2:
        best[:] = True
    s, R, t = umeyama_2d(src[best], dst[best])
    err = np.linalg.norm((s * (R @ src.T)).T + t - dst, axis=1)
    return s, R, t, err < threshold


def circular_mean_deg(values):
    rad = np.radians(values)
    return math.degrees(math.atan2(np.sin(rad).mean(), np.cos(rad).mean()))


def heading_of(axis_enu):
    return math.degrees(math.atan2(axis_enu[0], axis_enu[1])) % 360
