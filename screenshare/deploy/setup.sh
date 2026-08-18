#!/usr/bin/env bash
# One-shot setup for the ScreenLink relay server, behind Caddy for automatic
# TLS. Run this on the machine (VPS, home server, etc.) that will host it.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

if ! command -v docker >/dev/null 2>&1; then
  echo "docker is required. Install Docker Engine first: https://docs.docker.com/engine/install/" >&2
  exit 1
fi
if ! docker compose version >/dev/null 2>&1; then
  echo "the 'docker compose' plugin is required (Docker Engine 20.10+)." >&2
  exit 1
fi

if [ ! -f .env ]; then
  cp .env.example .env
  echo "Created deploy/.env from the example."
  echo "Edit it now and set DOMAIN to a DNS name that already points at this host,"
  echo "then re-run this script."
  exit 0
fi

mkdir -p data
if [ ! -f data/devices.json ]; then
  echo '{"devices":[]}' > data/devices.json
  echo "Created empty deploy/data/devices.json (no devices enrolled yet)."
fi

echo "Building and starting the relay + Caddy (TLS) containers..."
docker compose up -d --build

echo
echo "Done. Once DNS for the DOMAIN in .env resolves to this host, the relay is"
echo "reachable at wss://<DOMAIN> (Caddy fetches a Let's Encrypt cert on first use)."
echo
echo "Enroll a device:"
echo "  docker compose exec relay node manage-devices.js add \"表示名\" <group>"
echo
echo "After enrolling/removing devices, reload without restarting:"
echo "  docker compose kill -s HUP relay"
