#!/usr/bin/env bash
#
# Neocities API deploy for springcat.ragdollp.site
# -------------------------------------------------
# Uploads static pages to Neocities using the official API.
#   Docs: https://neocities.org/api
#
# Usage:
#   NEOCITIES_API_KEY=xxxxxxxx ./deploy-neocities.sh              # deploy the default page set
#   NEOCITIES_API_KEY=xxxxxxxx ./deploy-neocities.sh url/index.html   # deploy specific files
#
# The API key lives at:  Neocities → Settings → Manage Site Settings → API Key
# Never commit the key. Pass it as an environment variable (or use ~/.netrc).
#
set -euo pipefail

API_BASE="https://neocities.org/api"

if [[ -z "${NEOCITIES_API_KEY:-}" ]]; then
  echo "error: NEOCITIES_API_KEY is not set." >&2
  echo "       Get it from Neocities → Settings → Manage Site Settings → API Key," >&2
  echo "       then run:  NEOCITIES_API_KEY=xxxx $0" >&2
  exit 1
fi

# Files to upload. The multipart field name becomes the remote path,
# so "url/index.html" lands at https://<site>/url/index.html
DEFAULT_FILES=(
  "index.html"
  "url/index.html"
)

FILES=("$@")
if [[ ${#FILES[@]} -eq 0 ]]; then
  FILES=("${DEFAULT_FILES[@]}")
fi

form_args=()
for f in "${FILES[@]}"; do
  if [[ ! -f "$f" ]]; then
    echo "skip: $f (not found)" >&2
    continue
  fi
  form_args+=(-F "${f}=@${f}")
  echo "queued: $f"
done

if [[ ${#form_args[@]} -eq 0 ]]; then
  echo "error: nothing to upload." >&2
  exit 1
fi

echo "→ uploading ${#form_args[@]} file(s) to Neocities…"
http_code=$(
  curl -sS -w '%{http_code}' -o /tmp/neocities-deploy.out \
    -H "Authorization: Bearer ${NEOCITIES_API_KEY}" \
    "${form_args[@]}" \
    "${API_BASE}/upload"
)

echo "--- response ---"
cat /tmp/neocities-deploy.out
echo
echo "----------------"

if [[ "$http_code" == "200" ]]; then
  echo "✓ deploy succeeded"
else
  echo "✗ deploy failed (HTTP ${http_code})" >&2
  exit 1
fi
