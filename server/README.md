# Stride server

A dedicated PocketBase in Docker on the Pi, separate from every other
database there. Schema in `pb_migrations/`, hooks in `pb_hooks/`, API in
[../docs/API.md](../docs/API.md).

| | |
|---|---|
| Pi path | `/media/cyberhirsch/SSD/Data/Stride` (data in `pb_data/`) |
| container | `stride-pb`, compose project `stride` |
| LAN | `http://192.168.178.66:8091`, dashboard `/_/` |
| public | `stride-tunnel` (Cloudflare), compose profile `tunnel` |

```bash
server/deploy.sh            # sync + rebuild, LAN only
server/deploy.sh --tunnel   # also start the Cloudflare tunnel
```

First superuser (on the Pi):

```bash
docker exec -it stride-pb /pb/pocketbase superuser upsert you@example.com 'a-strong-password' --dir=/pb_data
```

Tunnel: Cloudflare Zero Trust → Networks → Tunnels → create `stride`, add
a public hostname pointing at `http://stride-pb:8090`, put the token in
`.env` on the Pi (`TUNNEL_TOKEN=…`), then `deploy.sh --tunnel`.

Moderation: three reports set `hidden` on a photo; review in the dashboard
(`reports` collection), then clear `hidden` or delete the photo.

## Seeding from Immich

`tools/prepare_seed.py` copies selected photos (CSV export from Immich) into
`seed/photos/` and writes `seed/manifest.json`; `import-seed` creates the
records, owned by one Stride user. Default licence is `arr`.

```bash
python3 tools/prepare_seed.py /tmp/stride_landscapes.csv --min-score 0.05
docker exec stride-pb /pb/pocketbase import-seed you@example.com arr \
  --dir=/pb_data --hooksDir=/pb/pb_hooks --migrationsDir=/pb/pb_migrations
```
