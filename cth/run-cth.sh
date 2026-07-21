#!/usr/bin/env bash
# Run the Solid conformance test harness against a locally running Cistern (:3000).
# T0.4 made this work end-to-end; keep failures honest — the baseline is the
# deliverable, not a green lie.
#
# Usage:
#   ./run-cth.sh               # full test run (needs the server on :3000)
#   ./run-cth.sh --coverage    # coverage report only (does not touch the server)
# Extra args are passed straight to the harness (see USAGE.md in
# https://github.com/solid-contrib/conformance-test-harness).
#
# Layout of the docker image (do NOT mount over /data — it holds the tests):
#   /data        specification-tests checkout (manifests + features)
#   /app/config  default application.yaml (sources + mappings)
#   /reports     where reports are written (mounted to ./reports)
set -euo pipefail
cd "$(dirname "$0")"

mkdir -p reports

# The harness image runs as uid 185 and writes its reports into this bind mount.
# On Linux the mount preserves host ownership, so a default 0755 directory owned by
# the invoking user is unwritable to that uid: the harness logs
# "AccessDeniedException: /reports/coverage.html" and STILL EXITS 0, so the run looks
# clean while producing no report at all. macOS Docker Desktop maps ownership to the
# host user and hides this entirely — it only ever bites in CI.
chmod 777 reports

# Which host port the server under test is listening on. Must match however it was
# started — docker-compose publishes ${CISTERN_HOST_PORT:-3737}, so exporting the same
# variable keeps the two in step. CI runs the jar directly and sets it explicitly.
PORT="${CISTERN_HOST_PORT:-3737}"

# The harness runs inside a container: it must reach the host's port via
# host.docker.internal (--add-host makes that name work on Linux; Docker
# Desktop on macOS/Windows provides it natively).
SERVER_ROOT="http://host.docker.internal:${PORT}"

# Coverage mode never contacts the server; every other mode does.
coverage_only=false
for arg in "$@"; do
  [ "$arg" = "--coverage" ] && coverage_only=true
done

if [ "$coverage_only" = false ]; then
  # Boot the server with CISTERN_BASE_URL=${SERVER_ROOT}. The harness addresses the
  # host by that name from inside its container, and cistern.base-url is what mints
  # Location headers and the storage description — leave it at the localhost default
  # and every URI handed to the harness names an origin the harness never called.
  #
  # Readiness: any HTTP response means up. Deliberately NOT `curl -f`; the storage
  # root honestly 404s until T5.4 provisions it, and -f would report a live server
  # as dead.
  # On a connection failure curl BOTH prints 000 and exits non-zero, so piping a
  # fallback through `|| echo 000` yields "000000" and the guard below never matches.
  # Let the assignment carry curl's status instead.
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "http://localhost:${PORT}/" 2>/dev/null) || code=000
  if [ "$code" = "000" ]; then
    echo "error: nothing answering on http://localhost:${PORT}/ — start the server first:" >&2
    echo "  CISTERN_HOST_PORT=${PORT} docker compose up -d" >&2
    echo "or, running it directly:" >&2
    echo "  SERVER_PORT=${PORT} CISTERN_BASE_URL=${SERVER_ROOT} mvn -q -pl cistern-app spring-boot:run" >&2
    echo "(set CISTERN_HOST_PORT to match if the server is on another port)" >&2
    exit 2
  fi
fi

# The harness hard-requires alice/bob WebIDs at startup (SmallRye config).
# Cistern provisions no accounts or pods yet (T5.4): these WebIDs point at
# where the test pods WILL live, so today each deref honestly 404s and a test
# run stops at "REGISTER CLIENTS" — that is the recorded T0.4 baseline.
# When T5.4 lands, add credentials here (SOLID_IDENTITY_PROVIDER plus
# USERS_*_USERNAME/PASSWORD or client credentials — see CTH USAGE.md).
set +e
docker run --rm \
  --add-host=host.docker.internal:host-gateway \
  -v "$(pwd)/subject-cistern.ttl:/subjects/subject-cistern.ttl:ro" \
  -v "$(pwd)/reports:/reports" \
  -e USERS_ALICE_WEBID="${SERVER_ROOT}/alice/profile/card#me" \
  -e USERS_BOB_WEBID="${SERVER_ROOT}/bob/profile/card#me" \
  -e RESOURCE_SERVER_ROOT="${SERVER_ROOT}" \
  -e TEST_CONTAINER="/alice/" \
  solidproject/conformance-test-harness \
  --output=/reports \
  --subjects=/subjects/subject-cistern.ttl \
  --target=https://github.com/enrichmeai/cistern \
  "$@"
status=$?
set -e

echo "Harness exit code: ${status}"
echo "Reports (if any) in cth/reports/ — update cth/BASELINE.md if the pass-count rose."
exit "${status}"
