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

# The harness runs inside a container: it must reach the host's :3000 via
# host.docker.internal (--add-host makes that name work on Linux; Docker
# Desktop on macOS/Windows provides it natively).
SERVER_ROOT="http://host.docker.internal:3000"

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
