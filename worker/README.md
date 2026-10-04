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

Measured on COLMAP's South Building set (128 photos at 1600 px, with
synthetic GPS σ 4 m, compass σ 8°, tilt σ 2°) on a Threadripper 1950X,
24 threads: 128/128 registered in one model, position error vs ground truth
median 0.9 m (max 1.1 m), heading error max 1.3°, 13 min (6 min matching,
6 min mapping). Feeding the GPS priors into bundle adjustment made it worse
(median 2.9 m), so GPS is used only for pair selection and the final fit.
