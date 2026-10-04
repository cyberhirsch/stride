"""Stride alignment worker: pull a job, align the place, post the result, repeat.

Environment:
  STRIDE_URL        server, e.g. https://stride-api.example.com
  STRIDE_EMAIL      a Stride account used by this worker
  STRIDE_PASSWORD
  STRIDE_WORKDIR    scratch space (default /tmp/stride-worker)
  STRIDE_THREADS    CPU threads for COLMAP (default: all)
  STRIDE_ONCE=1     process at most one job, then exit
"""
from __future__ import annotations

import json
import logging
import os
import shutil
import sys
import threading
import time
import traceback
from pathlib import Path

import requests

from .align import align

log = logging.getLogger("stride-worker")
IDLE_SLEEP = 60
HEARTBEAT = 300


class Client:
    def __init__(self, url, email, password):
        self.url = url.rstrip("/")
        self.email, self.password = email, password
        self.s = requests.Session()
        self.s.headers["User-Agent"] = "stride-worker/0.1"
        self.login()

    def login(self):
        r = self.s.post(f"{self.url}/api/collections/users/auth-with-password",
                        json={"identity": self.email, "password": self.password}, timeout=30)
        r.raise_for_status()
        self.s.headers["Authorization"] = r.json()["token"]

    def post(self, path, **kw):
        r = self.s.post(self.url + path, timeout=kw.pop("timeout", 120), **kw)
        if r.status_code == 401:
            self.login()
            r = self.s.post(self.url + path, timeout=120, **kw)
        if r.status_code >= 400:
            raise RuntimeError(f"{path}: {r.status_code} {r.text[:300]}")
        return r

    def claim(self):
        r = self.post("/api/stride/jobs/claim")
        return None if r.status_code == 204 else r.json()

    def download(self, photo, dest: Path):
        # 1600 px thumbnails: enough for matching, a fraction of the bandwidth
        r = self.s.get(f"{self.url}{photo['image']}", params={"thumb": "1600x0"}, timeout=120)
        r.raise_for_status()
        dest.write_bytes(r.content)


def heartbeat_loop(client, job_id, stop: threading.Event):
    while not stop.wait(HEARTBEAT):
        try:
            client.post(f"/api/stride/jobs/{job_id}/heartbeat")
        except Exception as e:  # noqa: BLE001 - keep working, the lease is long
            log.warning("heartbeat failed: %s", e)


def run_job(client, job, workdir: Path, threads: int):
    from PIL import Image

    jobdir = workdir / job["job"]
    images = jobdir / "images"
    shutil.rmtree(jobdir, ignore_errors=True)
    images.mkdir(parents=True)
    photos = []
    for p in job["photos"]:
        name = f"{p['id']}.jpg"
        client.download(p, images / name)
        with Image.open(images / name) as im:
            w, h = im.size  # thumbnails have their own size; intrinsics priors scale via fov
        photos.append({**p, "file": name, "width": w, "height": h})
    log.info("job %s: %d photos downloaded", job["job"], len(photos))

    result = align(photos, images, jobdir / "work", log=log.info, num_threads=threads)
    points = result.pop("points")
    client.post(f"/api/stride/jobs/{job['job']}/result",
                data={"result": json.dumps(result)},
                files={"points": ("points.bin", points, "application/octet-stream")},
                timeout=600)
    shutil.rmtree(jobdir, ignore_errors=True)
    return result["stats"]


def main():
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(message)s")
    try:
        client = Client(os.environ["STRIDE_URL"], os.environ["STRIDE_EMAIL"], os.environ["STRIDE_PASSWORD"])
    except KeyError as e:
        sys.exit(f"missing environment variable {e}")
    workdir = Path(os.environ.get("STRIDE_WORKDIR", "/tmp/stride-worker"))
    threads = int(os.environ.get("STRIDE_THREADS", "-1"))
    once = os.environ.get("STRIDE_ONCE") == "1"
    log.info("worker ready at %s", client.url)
    while True:
        job = client.claim()
        if not job:
            if once:
                return
            time.sleep(IDLE_SLEEP)
            continue
        stop = threading.Event()
        threading.Thread(target=heartbeat_loop, args=(client, job["job"], stop), daemon=True).start()
        try:
            stats = run_job(client, job, workdir, threads)
            log.info("job %s done: %s", job["job"], stats)
        except Exception as e:  # noqa: BLE001 - report any failure back to the queue
            log.error("job %s failed: %s", job["job"], e)
            try:
                client.post(f"/api/stride/jobs/{job['job']}/fail",
                            json={"error": "".join(traceback.format_exception_only(e)).strip()})
            except Exception as e2:  # noqa: BLE001
                log.error("could not report failure: %s", e2)
        finally:
            stop.set()
        if once:
            return


if __name__ == "__main__":
    main()
