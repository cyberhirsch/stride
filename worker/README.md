# Stride worker

Volunteer alignment worker, SETI@home style: it pulls a place from the
Stride server, aligns its photos with COLMAP (SIFT, prior-guided pair
selection, incremental structure-from-motion), fits the model to GPS and
gravity, and posts the camera poses and a sparse point cloud back.
Protocol: [docs/API.md](../docs/API.md#worker-protocol).

```bash
docker run -d --name stride-worker --restart unless-stopped \
  -e STRIDE_URL=https://<stride api> -e STRIDE_EMAIL=you@example.com -e STRIDE_PASSWORD=… \
  -e STRIDE_THREADS=4 --cpus 4 \
  ghcr.io/cyberhirsch/stride-worker:latest
```

Any Stride account can run a worker. A superuser marks accounts as
`trusted_worker`; results from other accounts wait for review.

Local development (Python 3.12/3.13, pycolmap wheels exist for x86_64
Linux, Windows and macOS; ARM Linux uses the Docker image):

```bash
pip install -r requirements.txt pytest
pytest
STRIDE_URL=http://127.0.0.1:8099 STRIDE_EMAIL=… STRIDE_PASSWORD=… STRIDE_ONCE=1 python -m stride_worker.main
```

Measured on the South Building set (COLMAP's test data, 1600 px, GPS
noise σ 4 m, compass σ 8°) on the Threadripper: see the commit history and
`tests/` for the numbers this was tuned with.
