#!/usr/bin/env bash
# How realm-cistern.json came to be — and how to make it again.
#
# Boots a throwaway Keycloak (same image the kit runs), builds the realm through the admin
# API from identities.env (build-realm.py), stops the server, and runs Keycloak's own
# `kc.sh export` against its database. The committed realm-cistern.json is therefore a real
# export — realm keys included, so the JWKS in fixtures/ stays valid across `compose down -v`.
#
#   ./keycloak/build-realm.sh            # rewrites keycloak/realm-cistern.json
#
# Needs docker, curl, python3. Uses host port 18080 and a throwaway volume; touches nothing the
# compose stack owns. Afterwards: docker compose down -v && docker compose up -d && ./seed.sh
set -euo pipefail
# shellcheck source=../lib/kit.sh
. "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/lib/kit.sh"

readonly BUILD_IMAGE="quay.io/keycloak/keycloak:latest"
readonly BUILD_CONTAINER="cistern-kit-realm-build"
readonly BUILD_VOLUME="cistern-kit-realm-build"
readonly BUILD_HOST_PORT="${KEYCLOAK_BUILD_HOST_PORT:-18080}"
readonly BUILD_BASE="http://localhost:${BUILD_HOST_PORT}"
readonly KC_DATA_DIR="/opt/keycloak/data"    # mount the volume here, not at data/h2: only this dir exists in the image with keycloak ownership
readonly EXPORT_MOUNT="/export"
readonly REALM_FILE="realm-cistern.json"
readonly REALM_PATH="${KIT_DIR}/keycloak/${REALM_FILE}"
readonly MASTER_DISCOVERY_PATH="/realms/master/.well-known/openid-configuration"

cleanup() {
  docker rm -f "$BUILD_CONTAINER" >/dev/null 2>&1 || true
  docker volume rm "$BUILD_VOLUME" >/dev/null 2>&1 || true
}
trap cleanup EXIT
cleanup

say "1. Throwaway Keycloak on ${BUILD_BASE} (image ${BUILD_IMAGE})"
docker run -d --name "$BUILD_CONTAINER" \
  -p "127.0.0.1:${BUILD_HOST_PORT}:8080" \
  -e KC_BOOTSTRAP_ADMIN_USERNAME="$KEYCLOAK_ADMIN_USER" \
  -e KC_BOOTSTRAP_ADMIN_PASSWORD="$KEYCLOAK_ADMIN_PASSWORD" \
  -v "${BUILD_VOLUME}:${KC_DATA_DIR}" \
  "$BUILD_IMAGE" start-dev >/dev/null
master_ready() { curl -sf "${BUILD_BASE}${MASTER_DISCOVERY_PATH}" | grep "\"issuer\"" >/dev/null; }
wait_until "keycloak ${BUILD_BASE} (master realm)" master_ready

say "2. Build realm '${KEYCLOAK_REALM}' from identities.env"
KEYCLOAK_BUILD_BASE="$BUILD_BASE" python3 "${KIT_DIR}/keycloak/build-realm.py"

say "3. Stop the server, export the realm with kc.sh (a real export, keys and secrets included)"
docker stop "$BUILD_CONTAINER" >/dev/null
docker run --rm \
  -v "${BUILD_VOLUME}:${KC_DATA_DIR}" \
  -v "${KIT_DIR}/keycloak:${EXPORT_MOUNT}" \
  "$BUILD_IMAGE" export --file "${EXPORT_MOUNT}/${REALM_FILE}" --realm "$KEYCLOAK_REALM" 2>&1 \
  | grep -E 'Export|export|ERROR' || true
[ -s "$REALM_PATH" ] || die "export produced no ${REALM_PATH}"
docker image inspect "$BUILD_IMAGE" --format '   keycloak version: {{index .Config.Labels "version"}}'
note "$(wc -c < "$REALM_PATH" | tr -d ' ') bytes -> ${REALM_PATH}"

say "4. Done. Fresh start:  docker compose down -v && docker compose up -d && ./seed.sh"
