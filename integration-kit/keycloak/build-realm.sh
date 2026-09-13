#!/usr/bin/env bash
# How realm-cistern.json came to be — and how to make it again.
#
# Boots a throwaway Keycloak (same image the kit runs), builds the realm through the admin
# API from identities.env (build-realm.py), stops the server, and runs Keycloak's own
# `kc.sh export` against its database. The committed realm-cistern.json is therefore a real
# export — realm keys included, so the JWKS in fixtures/ stays valid across `compose down -v`.
# A rebuild keeps the previous export's keys too (build-realm.py reads them), so regenerating
# the realm never invalidates fixtures/. The stand-in identity provider (T7.16) is a second
# realm and gets its own export, realm-<KEYCLOAK_BROKER_REALM>.json, imported alongside.
#
#   ./keycloak/build-realm.sh            # rewrites keycloak/realm-*.json
#
# Needs docker, curl, python3. Uses host port 18080 (KEYCLOAK_BUILD_HOST_PORT overrides) and a
# throwaway volume; touches nothing the compose stack owns. Afterwards: docker compose down -v && docker compose up -d && ./seed.sh
set -euo pipefail
# shellcheck source=../lib/kit.sh
. "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/lib/kit.sh"

readonly BUILD_IMAGE="quay.io/keycloak/keycloak:latest"
readonly BUILD_HOST_PORT="${KEYCLOAK_BUILD_HOST_PORT:-18080}"
# Named after the port: two builds on one machine (two worktrees, say) each own their throwaway;
# with one fixed name, either one's cleanup would remove the other's container mid-build.
readonly BUILD_CONTAINER="cistern-kit-realm-build-${BUILD_HOST_PORT}"
readonly BUILD_VOLUME="cistern-kit-realm-build-${BUILD_HOST_PORT}"
readonly BUILD_BASE="http://localhost:${BUILD_HOST_PORT}"
readonly KC_DATA_DIR="/opt/keycloak/data"    # mount the volume here, not at data/h2: only this dir exists in the image with keycloak ownership
readonly EXPORT_MOUNT="/export"
readonly REALM_FILE_PREFIX="realm-"          # realm-<name>.json; build-realm.py reads the previous one by the same name
readonly REALM_FILE_SUFFIX=".json"
readonly EXPORT_IN_PROGRESS_SUFFIX=".exporting"    # never the committed name until the export succeeded
readonly MASTER_DISCOVERY_PATH="/realms/master/.well-known/openid-configuration"
readonly LOOPBACK="127.0.0.1"
# The issuer's host (`keycloak`, from KEYCLOAK_ISSUER) as the throwaway must see it. Keycloak
# checks an identity provider's URLs at creation: under the realm's default sslRequired
# (external) it accepts plain http only for a host that resolves to a local address, and in
# this container — outside the compose network — `keycloak` would resolve to nothing.
readonly ISSUER_HOST_AND_PORT="${KEYCLOAK_ISSUER#*://}"
readonly ISSUER_HOST="${ISSUER_HOST_AND_PORT%%[:/]*}"

realm_file() { printf '%s/keycloak/%s%s%s' "$KIT_DIR" "$REALM_FILE_PREFIX" "$1" "$REALM_FILE_SUFFIX"; }

cleanup() {
  docker rm -f "$BUILD_CONTAINER" >/dev/null 2>&1 || true
  docker volume rm "$BUILD_VOLUME" >/dev/null 2>&1 || true
}
trap cleanup EXIT
cleanup

say "1. Throwaway Keycloak on ${BUILD_BASE} (image ${BUILD_IMAGE})"
docker run -d --name "$BUILD_CONTAINER" \
  -p "${LOOPBACK}:${BUILD_HOST_PORT}:8080" \
  --add-host "${ISSUER_HOST}:${LOOPBACK}" \
  -e KC_BOOTSTRAP_ADMIN_USERNAME="$KEYCLOAK_ADMIN_USER" \
  -e KC_BOOTSTRAP_ADMIN_PASSWORD="$KEYCLOAK_ADMIN_PASSWORD" \
  -v "${BUILD_VOLUME}:${KC_DATA_DIR}" \
  "$BUILD_IMAGE" start-dev >/dev/null
master_ready() { curl -sf "${BUILD_BASE}${MASTER_DISCOVERY_PATH}" | grep "\"issuer\"" >/dev/null; }
wait_until "keycloak ${BUILD_BASE} (master realm)" master_ready

say "2. Build realm '${KEYCLOAK_REALM}' from identities.env"
KEYCLOAK_BUILD_BASE="$BUILD_BASE" python3 "${KIT_DIR}/keycloak/build-realm.py"

say "3. Stop the server, export each realm with kc.sh (a real export, keys and secrets included)"
docker stop "$BUILD_CONTAINER" >/dev/null
# Each realm is exported to a temporary name and moved over the committed file only when the
# export process itself succeeded and wrote something: a killed export (the Docker VM under
# memory pressure, say) must not leave the previous file in place looking like today's.
for realm in "$KEYCLOAK_REALM" ${KEYCLOAK_BROKER_REALM:+"$KEYCLOAK_BROKER_REALM"}; do
  realm_path="$(realm_file "$realm")"
  export_name="$(basename "$realm_path")${EXPORT_IN_PROGRESS_SUFFIX}"
  rm -f "${KIT_DIR}/keycloak/${export_name}"
  export_status=0
  docker run --rm \
    -v "${BUILD_VOLUME}:${KC_DATA_DIR}" \
    -v "${KIT_DIR}/keycloak:${EXPORT_MOUNT}" \
    "$BUILD_IMAGE" export --file "${EXPORT_MOUNT}/${export_name}" --realm "$realm" 2>&1 \
    | { grep -E 'Export|export|ERROR|Killed' || true; } || export_status=$?    # pipefail: the export's own status
  [ "$export_status" = 0 ] || die "export of realm '${realm}' failed (exit ${export_status}); ${realm_path} left as it was"
  [ -s "${KIT_DIR}/keycloak/${export_name}" ] || die "export of realm '${realm}' wrote nothing; ${realm_path} left as it was"
  mv "${KIT_DIR}/keycloak/${export_name}" "$realm_path"
  note "$(wc -c < "$realm_path" | tr -d ' ') bytes -> ${realm_path}"
done
docker image inspect "$BUILD_IMAGE" --format '   keycloak version: {{index .Config.Labels "version"}}'

say "4. Done. Fresh start:  docker compose down -v && docker compose up -d && ./seed.sh"
