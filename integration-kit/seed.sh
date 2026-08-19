#!/usr/bin/env bash
# The owner's script: waits for Keycloak and Cistern, provisions the demo layout, writes the
# grants. Everything it does is a plain HTTP request with the owner token — the same requests
# docs/INTEGRATION.md steps 2 and 3 show by hand. Idempotent; run it as often as you like.
#
#   ./seed.sh            layout + grants for KIT_MODE (default: today)
#   ./seed.sh layout     documents only
#   ./seed.sh grant      grants only (grants/<KIT_MODE>/*.acl.ttl)
#   ./seed.sh revoke     delete the grants, keep the documents (the "before" state)
#
#   KIT_MODE=today      /matters/2026-114/.acl grants foaf:Agent Read   (server names no apps yet)
#   KIT_MODE=after-88   /matters/2026-114/.acl names the legal app, /tax/FY2025-26/.acl the tax app
#
# After #90 the layout comes from `cistern pod create`; after #91 the grants from `cistern grant`.
set -euo pipefail
# shellcheck source=lib/kit.sh
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/kit.sh"

readonly SEED_DIR="${KIT_DIR}/seed"
readonly SEED_MATTER_INDEX="${SEED_DIR}/matters-2026-114-index.ttl"
readonly SEED_MATTER_CONTRACT="${SEED_DIR}/contract.pdf"
readonly SEED_TAX_RETURN="${SEED_DIR}/tax-FY2025-26-return.ttl"

readonly CMD_ALL="all" CMD_LAYOUT="layout" CMD_GRANT="grant" CMD_REVOKE="revoke"
readonly STATUS_CREATED_OR_REPLACED="201 204"
readonly STATUS_DELETED_OR_ABSENT="204 404"

owner_put_file() {   # owner_put_file <pod path> <media type> <file>  -> 201 (new) or 204 (replaced)
  expect "owner PUT $1" "$STATUS_CREATED_OR_REPLACED" "" \
    -X PUT -H "$OWNER_AUTH_HEADER" -H "Content-Type: $2" --data-binary @"$3" "${CISTERN_BASE}$1"
}

owner_put_grant() {  # owner_put_grant <acl pod path> <grant file name>  -> the .acl from grants/<mode>/
  grant_text "$2" | expect "owner PUT $1" "$STATUS_CREATED_OR_REPLACED" "(grants/${KIT_MODE}/$2)" \
    -X PUT -H "$OWNER_AUTH_HEADER" -H "Content-Type: ${MEDIA_TURTLE}" --data-binary @- "${CISTERN_BASE}$1"
}

owner_delete() {     # owner_delete <pod path>  -> 204, or 404 when already gone
  expect "owner DELETE $1" "$STATUS_DELETED_OR_ABSENT" "$2" -X DELETE -H "$OWNER_AUTH_HEADER" "${CISTERN_BASE}$1"
}

layout() {
  say "Layout (docs/INTEGRATION.md step 2): one matter, one tax year — intermediate containers are created for you"
  owner_put_file "$POD_MATTER_INDEX"    "$MEDIA_TURTLE" "$SEED_MATTER_INDEX"
  owner_put_file "$POD_MATTER_CONTRACT" "$MEDIA_PDF"    "$SEED_MATTER_CONTRACT"
  owner_put_file "$POD_TAX_RETURN"      "$MEDIA_TURTLE" "$SEED_TAX_RETURN"
}

grant() {
  case "$KIT_MODE" in
    "$KIT_MODE_TODAY")
      say "Grants (step 3), mode=today: foaf:Agent may Read ${POD_MATTER} — the server names no per-app principal until #88"
      owner_put_grant "$POD_MATTER_ACL" "$GRANT_MATTER_FILE"
      note "${POD_TAX} keeps the root ACL: owner only."
      ;;
    "$KIT_MODE_AFTER_88")
      say "Grants (step 3), mode=after-88: ${KEYCLOAK_CLIENT_LEGAL_WEBID} may Read ${POD_MATTER}; ${KEYCLOAK_CLIENT_TAX_WEBID} may Read ${POD_TAX}"
      owner_put_grant "$POD_MATTER_ACL" "$GRANT_MATTER_FILE"
      owner_put_grant "$POD_TAX_ACL"    "$GRANT_TAX_FILE"
      ;;
  esac
}

revoke() {
  say "Revoke: delete the grant files. No restart, no token reissue — the next request is refused."
  owner_delete "$POD_MATTER_ACL" ""
  owner_delete "$POD_TAX_ACL"    ""
}

command="${1:-$CMD_ALL}"
say "Waiting for the kit (mode=${KIT_MODE})"
wait_for_kit
case "$command" in
  "$CMD_ALL")    layout; grant ;;
  "$CMD_LAYOUT") layout ;;
  "$CMD_GRANT")  grant ;;
  "$CMD_REVOKE") revoke ;;
  *) die "usage: $0 [$CMD_ALL|$CMD_LAYOUT|$CMD_GRANT|$CMD_REVOKE]" ;;
esac
say "Seeded. Try:  curl -i ${CISTERN_BASE}${POD_MATTER_INDEX}      (and ./sample-app/run.sh for the whole story)"
