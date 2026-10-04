#!/usr/bin/env python3
"""Copy selected photos into the Stride seed folder and write manifest.json.

Input: a CSV with columns id,originalPath,latitude,longitude,dateTimeOriginal,score
(as exported from Immich). Paths starting with /data/ are mapped to LIBRARY.
Only JPEGs are taken; position and time come from the CSV, the rest from EXIF.

  python3 prepare_seed.py stride_landscapes.csv --min-score 0.05
"""
import argparse, csv, json, math, os, shutil, sys
from datetime import datetime, timezone
from PIL import Image, ExifTags

LIBRARY = "/media/cyberhirsch/SSD/Media/Photos/"
SEED = "/media/cyberhirsch/SSD/Data/Stride/seed"

GPS = {v: k for k, v in ExifTags.GPSTAGS.items()}
TAG = {v: k for k, v in ExifTags.TAGS.items()}


def fov_from_35mm(f35, w, h):
    """Field of view from the 35 mm equivalent focal length (43.27 mm diagonal)."""
    if not f35 or not w or not h:
        return None, None
    half = math.atan(43.27 / (2 * f35))
    d = math.hypot(w, h)
    return (math.degrees(2 * math.atan(math.tan(half) * w / d)),
            math.degrees(2 * math.atan(math.tan(half) * h / d)))


def num(v):
    try:
        f = float(v)
        return f if math.isfinite(f) else None
    except (TypeError, ValueError, ZeroDivisionError):
        return None


def read_exif(path):
    out = {}
    with Image.open(path) as im:
        w, h = im.size
        exif = im.getexif()
        if exif.get(TAG["Orientation"]) in (5, 6, 7, 8):
            w, h = h, w
        ifd = exif.get_ifd(0x8769)
        gps = exif.get_ifd(0x8825)
    out["width"], out["height"] = w, h
    make, model = (exif.get(TAG["Make"]) or "").strip(), (exif.get(TAG["Model"]) or "").strip()
    out["device"] = (model if model.lower().startswith(make.lower()) else f"{make} {model}").strip()
    out["focal_length_mm"] = num(ifd.get(TAG["FocalLength"]))
    out["focal_length_35mm"] = num(ifd.get(TAG["FocalLengthIn35mmFilm"]))
    out["fov_h"], out["fov_v"] = fov_from_35mm(out["focal_length_35mm"], w, h)
    direction = num(gps.get(GPS["GPSImgDirection"]))
    if direction is not None:
        out["heading"] = direction % 360
        out["has_heading"] = True
        out["heading_ref"] = gps.get(GPS["GPSImgDirectionRef"], "T")
    alt = num(gps.get(GPS["GPSAltitude"]))
    if alt is not None:
        out["altitude"] = -alt if gps.get(GPS["GPSAltitudeRef"]) in (1, b"\x01") else alt
    acc = num(gps.get(31))  # GPSHPositioningError
    if acc is not None:
        out["gps_accuracy"] = acc
    return {k: v for k, v in out.items() if v not in (None, "")}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("csv")
    ap.add_argument("--min-score", type=float, default=0.05)
    args = ap.parse_args()

    os.makedirs(f"{SEED}/photos", exist_ok=True)
    manifest, skipped = [], 0
    with open(args.csv) as f:
        for row in csv.DictReader(f):
            src = row["originalPath"]
            if float(row["score"]) <= args.min_score or not src.lower().endswith((".jpg", ".jpeg")):
                continue
            if src.startswith("/data/"):
                src = LIBRARY + src[len("/data/"):]
            dst = f"{SEED}/photos/{row['id']}.jpg"
            try:
                if not os.path.exists(dst):
                    shutil.copy2(src, dst)
                meta = read_exif(dst)
            except Exception as e:
                print(f"skip {src}: {e}", file=sys.stderr)
                skipped += 1
                continue
            taken = datetime.fromisoformat(row["dateTimeOriginal"].replace("+00", "+00:00")).astimezone(timezone.utc)
            manifest.append({
                "file": f"photos/{row['id']}.jpg",
                "source_id": row["id"],
                "lat": float(row["latitude"]),
                "lon": float(row["longitude"]),
                "captured_at": taken.strftime("%Y-%m-%d %H:%M:%S.000Z"),
                **meta,
            })
    with open(f"{SEED}/manifest.json", "w") as f:
        json.dump(manifest, f, indent=1)
    print(f"{len(manifest)} photos in manifest, {skipped} skipped, "
          f"{sum(1 for m in manifest if m.get('has_heading'))} with heading")


if __name__ == "__main__":
    main()
