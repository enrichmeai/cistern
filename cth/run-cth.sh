#!/usr/bin/env bash
# Run the Solid conformance test harness against a locally running Cistern (:3000).
# T0.4 makes this actually work end-to-end; keep failures honest — the baseline is the
# deliverable, not a green lie.
set -euo pipefail
cd "$(dirname "$0")"

mkdir -p reports

docker run --rm \
  --add-host=host.docker.internal:host-gateway \
  -v "$(pwd)":/data \
  solidproject/conformance-test-harness \
  --output=/data/reports \
  --subjects=/data/subject-cistern.ttl \
  --target=https://github.com/enrichmeai/cistern \
  "$@"

echo "Report written to cth/reports/ — update cth/BASELINE.md if the pass-count rose."
