#!/usr/bin/env bash
# Sync the server folder to the Pi and rebuild the Stride container.
# Usage: server/deploy.sh [--tunnel]
set -euo pipefail
HOST="${STRIDE_PI:-cyberhirsch@192.168.178.66}"
DEST=/media/cyberhirsch/SSD/Data/Stride
cd "$(dirname "$0")"
ssh "$HOST" "mkdir -p $DEST/pb_data $DEST/seed"
tar czf - Dockerfile docker-compose.yml .env.example worker.env.example pb_migrations pb_hooks tools | ssh "$HOST" "tar xzf - -C $DEST"
PROFILE=""
[ "${1:-}" = "--tunnel" ] && PROFILE="--profile tunnel"
ssh "$HOST" "cd $DEST && docker compose $PROFILE up -d --build && docker compose ps"
