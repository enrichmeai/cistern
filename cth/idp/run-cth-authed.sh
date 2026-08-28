#!/usr/bin/env bash
# The authenticated conformance run: read the credentials provision.sh wrote,
# verify the topology actually loaded, then DELEGATE to ../run-cth.sh — one
# harness invocation in the repository, extended by environment rather than
# copied, so the readiness probe, exit-code report and every hard-won docker
# flag live in one place. Runbook and traps: README.md.
set -euo pipefail
cd "$(dirname "$0")"

# --coverage touches neither server nor IdP; hand it straight through.
for arg in "$@"; do
  [ "$arg" = "--coverage" ] && exec ../run-cth.sh "$@"
done

# The kit pins 3737: the seeded WebIDs, owner, trusted-origins and every
# provisioned credential bake that origin in, so a different port would break
# the byte-identical-origins invariant silently (see README traps).
if [ "${CISTERN_HOST_PORT:-3737}" != "3737" ]; then
  echo "error: the cth/idp kit pins CISTERN_HOST_PORT=3737 — the origin is baked" >&2
  echo "into cth-application.yaml and the provisioned credentials. Unset it, or" >&2
  echo "adapt the kit's config and re-provision for your port." >&2
  exit 2
fi

USERS_FILE="${CTH_USERS_FILE:-users.json}"
[ -f "$USERS_FILE" ] || { echo "error: $USERS_FILE not found — run ./provision.sh first" >&2; exit 2; }

# A real JSON parse, not a grep window; every value is required or the run is
# refused with the cause named — an empty secret would otherwise surface as an
# opaque auth failure deep inside the harness.
creds=$(python3 - "$USERS_FILE" <<'PY'
import json, sys
users = json.load(open(sys.argv[1]))
for who in ("alice", "bob"):
    u = users.get(who) or {}
    for key in ("webId", "clientId", "clientSecret"):
        value = u.get(key)
        if not value:
            sys.exit(f"error: {sys.argv[1]} has no {key} for {who} — re-run ./provision.sh")
        print(f"{who}\t{key}\t{value}")
PY
) || { echo "$creds" >&2; exit 2; }

ALICE_WEBID=""; ALICE_ID=""; ALICE_SECRET=""; BOB_WEBID=""; BOB_ID=""; BOB_SECRET=""
while IFS=$'\t' read -r who key value; do
  case "$who/$key" in
    alice/webId)        ALICE_WEBID=$value ;;
    alice/clientId)     ALICE_ID=$value ;;
    alice/clientSecret) ALICE_SECRET=$value ;;
    bob/webId)          BOB_WEBID=$value ;;
    bob/clientId)       BOB_ID=$value ;;
    bob/clientSecret)   BOB_SECRET=$value ;;
  esac
done <<< "$creds"

# Config-actually-loaded preflight: one request that fails for every silent
# miss at once (mount landed wrong, auth not enabled, pods not seeded, wrong
# port). ../run-cth.sh's own probe only asks whether ANYTHING answers; a
# missing profile here means the harness would die at REGISTER CLIENTS in a
# way indistinguishable from a real conformance failure.
profile=$(curl -s --max-time 5 --resolve host.docker.internal:3737:127.0.0.1 \
    "${ALICE_WEBID%%#*}" 2>/dev/null) || profile=""
if ! printf '%s' "$profile" | grep -q "oidcIssuer"; then
  echo "error: ${ALICE_WEBID%%#*} is not serving a WebID profile — the stack is" >&2
  echo "down, or cth-application.yaml was not loaded. Start with:" >&2
  echo "  docker compose -f docker-compose.yml -f cth/idp/compose-cth-idp.yml up --build -d" >&2
  exit 2
fi

IDP="${SOLID_IDENTITY_PROVIDER:-http://host.docker.internal:3939/}"
export SOLID_IDENTITY_PROVIDER="$IDP"
export USERS_ALICE_WEBID="$ALICE_WEBID" USERS_ALICE_CLIENTID="$ALICE_ID" \
       USERS_ALICE_CLIENTSECRET="$ALICE_SECRET" USERS_ALICE_IDP="$IDP"
export USERS_BOB_WEBID="$BOB_WEBID" USERS_BOB_CLIENTID="$BOB_ID" \
       USERS_BOB_CLIENTSECRET="$BOB_SECRET" USERS_BOB_IDP="$IDP"

# CTH_IMAGE passes through ../run-cth.sh: official by default (the only image
# that may move cth/BASELINE.md's official row); a build of upstream PR #789
# yields the PROVISIONAL numbers, recorded as provisional or not at all.
exec ../run-cth.sh "$@"
