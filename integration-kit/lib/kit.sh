# Shared by seed.sh, capture-fixtures.sh, sample-app/run.sh and keycloak/build-realm.sh.
# Source it; do not run it. Everything here talks to 127.0.0.1 only.
#
#   KIT_DIR             absolute path of integration-kit/
#   KIT_MODE            today | after-88   (which grants are active; which story the app tells)
#   CISTERN_BASE        http://127.0.0.1:<CISTERN_HOST_PORT>   (default 3737)
#   KEYCLOAK_BASE       http://127.0.0.1:<KEYCLOAK_HOST_PORT>  (default 8080)
#   plus everything in cistern.env and identities.env
# shellcheck disable=SC2034   # a sourced library: its constants are used by the scripts that source it
set -euo pipefail

KIT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# ---- the closed set of modes ------------------------------------------------------------
readonly KIT_MODE_TODAY="today"
readonly KIT_MODE_AFTER_88="after-88"
KIT_MODE="${KIT_MODE:-$KIT_MODE_TODAY}"
case "$KIT_MODE" in
  "$KIT_MODE_TODAY"|"$KIT_MODE_AFTER_88") ;;
  *) echo "KIT_MODE must be '$KIT_MODE_TODAY' or '$KIT_MODE_AFTER_88' (got '$KIT_MODE')" >&2; exit 2 ;;
esac

# ---- environment: one source of truth, shared with docker-compose.yml -------------------
set -a
# shellcheck source=../cistern.env
. "$KIT_DIR/cistern.env"
# shellcheck source=../identities.env
. "$KIT_DIR/identities.env"
set +a

readonly CISTERN_HOST_PORT="${CISTERN_HOST_PORT:-3737}"
readonly KEYCLOAK_HOST_PORT="${KEYCLOAK_HOST_PORT:-8080}"
readonly CISTERN_BASE="${CISTERN_BASE:-http://127.0.0.1:${CISTERN_HOST_PORT}}"
readonly KEYCLOAK_BASE="${KEYCLOAK_BASE:-http://127.0.0.1:${KEYCLOAK_HOST_PORT}}"

# ---- pod layout (docs/INTEGRATION.md step 2) — the only place these paths are spelled ----
readonly POD_MATTER="/matters/2026-114/"
readonly POD_MATTER_INDEX="${POD_MATTER}index"
readonly POD_MATTER_CONTRACT="${POD_MATTER}contract.pdf"
readonly POD_MATTER_ACL="${POD_MATTER}.acl"
readonly POD_TAX="/tax/FY2025-26/"
readonly POD_TAX_RETURN="${POD_TAX}return"
readonly POD_TAX_ACL="${POD_TAX}.acl"

# ---- media types and headers -----------------------------------------------------------
readonly MEDIA_TURTLE="text/turtle"
readonly MEDIA_PDF="application/pdf"
readonly MEDIA_FORM="application/x-www-form-urlencoded"
readonly OWNER_AUTH_HEADER="Authorization: Bearer ${CISTERN_OWNER_TOKEN}"

# ---- Keycloak endpoints (relative to KEYCLOAK_BASE) -------------------------------------
readonly KC_REALM_PATH="/realms/${KEYCLOAK_REALM}"
readonly KC_DISCOVERY_PATH="${KC_REALM_PATH}/.well-known/openid-configuration"
readonly KC_TOKEN_PATH="${KC_REALM_PATH}/protocol/openid-connect/token"
readonly KC_JWKS_PATH="${KC_REALM_PATH}/protocol/openid-connect/certs"

# ---- grants: the ACL files seed.sh and the sample app both apply -----------------------
readonly GRANTS_DIR="${KIT_DIR}/grants"
readonly GRANT_MATTER_FILE="matters-2026-114.acl.ttl"
readonly GRANT_TAX_FILE="tax-FY2025-26.acl.ttl"
readonly GRANT_OWNER_PLACEHOLDER="OWNER-WEBID"      # as in docs/INTEGRATION.md §9

# ---- output helpers --------------------------------------------------------------------
say()  { printf '\n\033[1m%s\033[0m\n' "$*"; }
note() { printf '   %s\n' "$*"; }
die()  { printf 'error: %s\n' "$*" >&2; exit 1; }

# HTTP status only.
code() { curl -s -o /dev/null -w '%{http_code}' "$@"; }

# Print "   <label> -> <status>  <comment>" and fail unless the status is one of the accepted.
# usage: expect "<label>" "<accepted statuses, space separated>" "<comment>" curl-args...
expect() {
  local label="$1" accepted="$2" comment="$3"; shift 3
  local status; status="$(code "$@")"
  local ok=1; for s in $accepted; do [ "$s" = "$status" ] && ok=0; done
  printf '   %-42s -> %s  %s\n' "$label" "$status" "$comment"
  [ "$ok" = 0 ] || die "expected one of [$accepted] for $label, got $status"
}

# The ACL text for a grant file in the active mode, owner placeholder resolved.
grant_text() {
  local path="${GRANTS_DIR}/${KIT_MODE}/$1"
  [ -f "$path" ] || die "no grant file $path"
  sed "s|<${GRANT_OWNER_PLACEHOLDER}>|<${CISTERN_OWNER_WEBID}>|g" "$path"
}

# ---- readiness ---------------------------------------------------------------------------
readonly WAIT_ATTEMPTS=90
readonly WAIT_SLEEP_SECONDS=2

wait_until() {   # wait_until "<what>" <command...>  — polls until the command succeeds
  local what="$1"; shift
  local i
  for ((i = 1; i <= WAIT_ATTEMPTS; i++)); do
    if "$@" >/dev/null 2>&1; then printf '   %-42s ok\n' "$what"; return 0; fi
    sleep "$WAIT_SLEEP_SECONDS"
  done
  die "$what: not ready after $((WAIT_ATTEMPTS * WAIT_SLEEP_SECONDS))s"
}

keycloak_ready() { curl -sf "${KEYCLOAK_BASE}${KC_DISCOVERY_PATH}" | grep "\"issuer\"" >/dev/null; }   # not grep -q: with pipefail an early exit fails curl
# The owner reading the root: 200 means the server is up, enforcing, and knows this token.
cistern_ready()  { [ "$(code -H "$OWNER_AUTH_HEADER" "${CISTERN_BASE}/")" = "200" ]; }

wait_for_kit() {
  wait_until "keycloak ${KEYCLOAK_BASE} (realm ${KEYCLOAK_REALM})" keycloak_ready
  wait_until "cistern  ${CISTERN_BASE} (owner token)" cistern_ready
}

# ---- Keycloak tokens (client credentials / password) ------------------------------------
readonly GRANT_TYPE_CLIENT_CREDENTIALS="client_credentials"
readonly GRANT_TYPE_PASSWORD="password"
readonly JSON_ACCESS_TOKEN_FIELD="access_token"

# Prints the raw access token (a JWT is [A-Za-z0-9._-], so sed can lift it out of the JSON).
# Bash 3.2 (macOS) compatible: no arrays, no ${var,,}.
kc_token() {   # kc_token <client-id> <client-secret> [<username> <password>]
  local client_id="$1" client_secret="$2" user="${3:-}" pass="${4:-}"
  local response
  if [ -n "$user" ]; then
    response="$(curl -sf -X POST "${KEYCLOAK_BASE}${KC_TOKEN_PATH}" -H "Content-Type: ${MEDIA_FORM}" \
      -d "grant_type=${GRANT_TYPE_PASSWORD}" -d "client_id=${client_id}" -d "client_secret=${client_secret}" \
      -d "username=${user}" -d "password=${pass}")"
  else
    response="$(curl -sf -X POST "${KEYCLOAK_BASE}${KC_TOKEN_PATH}" -H "Content-Type: ${MEDIA_FORM}" \
      -d "grant_type=${GRANT_TYPE_CLIENT_CREDENTIALS}" -d "client_id=${client_id}" -d "client_secret=${client_secret}")"
  fi
  printf '%s' "$response" | sed -n "s/.*\"${JSON_ACCESS_TOKEN_FIELD}\":\"\([^\"]*\)\".*/\1/p"
}
