# Stride web

Map of photos (MapLibre GL, OpenFreeMap tiles), photo details, sign-in and
upload with EXIF/XMP metadata. Vite + TypeScript, no framework.

```bash
npm ci
npm run dev        # uses VITE_PB_URL from .env.development (local PocketBase on :8099)
npm run build      # dist/
```

API URL, first match wins: `localStorage["stride.api"]`, `VITE_PB_URL` at
build time, then the Pi on the LAN (`http://192.168.178.66:8091`).

GitHub Pages is served over HTTPS, so the deployed site needs the HTTPS
tunnel URL: set the repository variable `STRIDE_API_URL`
(Settings → Secrets and variables → Actions → Variables).
