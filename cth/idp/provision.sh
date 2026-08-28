#!/usr/bin/env bash
# Provision alice+bob at the CSS test IdP and write cth/idp/users.json.
# Runbook and traps: README.md. Safe to re-run; a failed run never destroys a
# previously good users.json (temp-file-then-move, never a shell redirect that
# truncates before node starts).
set -euo pipefail
cd "$(dirname "$0")"

CSS_ORIGIN="http://host.docker.internal:3939"
CISTERN_ORIGIN="http://host.docker.internal:${CISTERN_HOST_PORT:-3737}"

# CSS binds its identifier space to host.docker.internal, so the readiness probe
# must present that name — from the host, --resolve pins it to loopback.
echo "waiting for CSS at ${CSS_ORIGIN} ..."
for i in $(seq 1 60); do
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 \
      --resolve host.docker.internal:3939:127.0.0.1 \
      "${CSS_ORIGIN}/.account/" 2>/dev/null) || code=000
  [ "$code" = "200" ] && break
  sleep 2
done
if [ "${code}" != "200" ]; then
  echo "error: CSS not answering on ${CSS_ORIGIN} — start the stack first:" >&2
  echo "  docker compose -f docker-compose.yml -f cth/idp/compose-cth-idp.yml up --build -d" >&2
  exit 2
fi

# The CSS image itself runs the script: node is already on its PATH, the image
# is already pulled by the stack, and --add-host makes host.docker.internal
# resolve on plain Linux Docker, not only Docker Desktop.
tmp=$(mktemp)
if docker run --rm \
    --add-host=host.docker.internal:host-gateway \
    --entrypoint node \
    -v "$(pwd)/provision-css.mjs:/provision.mjs:ro" \
    solidproject/community-server:7.2.0 \
    /provision.mjs "$CSS_ORIGIN" "$CISTERN_ORIGIN" > "$tmp"; then
  mv "$tmp" users.json
  echo "wrote $(pwd)/users.json — untracked, keep it that way"
else
  rm -f "$tmp"
  echo "error: provisioning failed; users.json untouched" >&2
  exit 1
fi
