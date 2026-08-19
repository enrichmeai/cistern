#!/usr/bin/env bash
# Captures real-first fixtures from the running Keycloak into fixtures/ (ground rule 6):
# the realm's discovery document and JWKS, a valid access token per service principal, one
# human token, and one token that is genuinely expired. #88's tests copy from here; nothing
# in cistern-auth/ is written by this script.
#
#   docker compose up -d && ./capture-fixtures.sh
#
# The realm export carries the realm's signing keys, so the JWKS captured here stays valid
# for every fresh `docker compose down -v && up`; only the tokens' timestamps move.
set -euo pipefail
# shellcheck source=lib/kit.sh
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/kit.sh"

readonly FIXTURES_DIR="${KIT_DIR}/fixtures"
readonly FIXTURE_DISCOVERY="openid-configuration.json"
readonly FIXTURE_JWKS="jwks.json"
readonly FIXTURE_TOKEN_LEGAL="token-valuedocs-legal.jwt"
readonly FIXTURE_TOKEN_TAX="token-valuedocs-tax.jwt"
readonly FIXTURE_TOKEN_ALICE="token-alice-via-valuedocs-legal.jwt"
readonly FIXTURE_TOKEN_EXPIRED="token-expired.jwt"
readonly FIXTURE_CLAIMS_SUFFIX=".claims.json"
readonly FIXTURE_README="README.md"
readonly JWT_DECODE="${KIT_DIR}/lib/jwt-decode.py"
readonly EXPIRY_MARGIN_SECONDS=2
readonly KEYCLOAK_IMAGE="quay.io/keycloak/keycloak:latest"

mkdir -p "$FIXTURES_DIR"

save_token() {   # save_token <file> <client-id> <client-secret> [<user> <password>]  -> writes token + decoded claims
  local file="$1"; shift
  local token; token="$(kc_token "$@")"
  [ -n "$token" ] || die "no access_token for client $1"
  printf '%s' "$token" > "${FIXTURES_DIR}/${file}"
  python3 "$JWT_DECODE" "${FIXTURES_DIR}/${file}" > "${FIXTURES_DIR}/${file%.jwt}${FIXTURE_CLAIMS_SUFFIX}"
  note "${file}  (+ ${file%.jwt}${FIXTURE_CLAIMS_SUFFIX})"
}

say "Waiting for Keycloak"
wait_until "keycloak ${KEYCLOAK_BASE} (realm ${KEYCLOAK_REALM})" keycloak_ready

say "Issuer metadata and keys"
curl -sf "${KEYCLOAK_BASE}${KC_DISCOVERY_PATH}" | python3 -m json.tool > "${FIXTURES_DIR}/${FIXTURE_DISCOVERY}"
note "${FIXTURE_DISCOVERY}"
curl -sf "${KEYCLOAK_BASE}${KC_JWKS_PATH}" | python3 -m json.tool > "${FIXTURES_DIR}/${FIXTURE_JWKS}"
note "${FIXTURE_JWKS}"

say "Tokens (client credentials; alice by password grant through the legal app)"
save_token "$FIXTURE_TOKEN_LEGAL" "$KEYCLOAK_CLIENT_LEGAL_ID" "$KEYCLOAK_CLIENT_LEGAL_SECRET"
save_token "$FIXTURE_TOKEN_TAX"   "$KEYCLOAK_CLIENT_TAX_ID"   "$KEYCLOAK_CLIENT_TAX_SECRET"
save_token "$FIXTURE_TOKEN_ALICE" "$KEYCLOAK_CLIENT_LEGAL_ID" "$KEYCLOAK_CLIENT_LEGAL_SECRET" \
                                  "$KEYCLOAK_USER_ALICE" "$KEYCLOAK_USER_ALICE_PASSWORD"

say "An expired token: ${KEYCLOAK_CLIENT_FIXTURE_ID} mints ${KEYCLOAK_CLIENT_FIXTURE_TOKEN_LIFESPAN_SECONDS}s tokens; wait it out"
save_token "$FIXTURE_TOKEN_EXPIRED" "$KEYCLOAK_CLIENT_FIXTURE_ID" "$KEYCLOAK_CLIENT_FIXTURE_SECRET"
sleep $((KEYCLOAK_CLIENT_FIXTURE_TOKEN_LIFESPAN_SECONDS + EXPIRY_MARGIN_SECONDS))
note "expired since $(python3 -c "import json,sys,time; c=json.load(open(sys.argv[1]))['claims']; print(time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime(c['exp'])))" "${FIXTURES_DIR}/${FIXTURE_TOKEN_EXPIRED%.jwt}${FIXTURE_CLAIMS_SUFFIX}")"

say "Provenance"
keycloak_version="$(docker image inspect "$KEYCLOAK_IMAGE" --format '{{index .Config.Labels "version"}}' 2>/dev/null || echo unknown)"
cat > "${FIXTURES_DIR}/${FIXTURE_README}" <<EOF
# Fixtures — captured from a real Keycloak, not written by hand

Produced by \`integration-kit/capture-fixtures.sh\` on $(date -u +%Y-%m-%dT%H:%M:%SZ) against
Keycloak ${keycloak_version} (\`${KEYCLOAK_IMAGE}\`) running \`integration-kit/keycloak/realm-cistern.json\`.
Regenerate with \`docker compose up -d && ./capture-fixtures.sh\`; the signing keys travel in the realm
export, so \`${FIXTURE_JWKS}\` verifies tokens from every fresh import — only timestamps move.

| File | What |
|---|---|
| \`${FIXTURE_DISCOVERY}\` | \`${KC_DISCOVERY_PATH}\` — issuer \`${KEYCLOAK_ISSUER}\` |
| \`${FIXTURE_JWKS}\` | \`${KC_JWKS_PATH}\` — public keys (RS256 signing key + RSA-OAEP enc key) |
| \`${FIXTURE_TOKEN_LEGAL}\` | client credentials, \`${KEYCLOAK_CLIENT_LEGAL_ID}\` → \`webid\` ${KEYCLOAK_CLIENT_LEGAL_WEBID} |
| \`${FIXTURE_TOKEN_TAX}\` | client credentials, \`${KEYCLOAK_CLIENT_TAX_ID}\` → \`webid\` ${KEYCLOAK_CLIENT_TAX_WEBID} |
| \`${FIXTURE_TOKEN_ALICE}\` | password grant, ${KEYCLOAK_USER_ALICE} through \`${KEYCLOAK_CLIENT_LEGAL_ID}\` → \`webid\` ${KEYCLOAK_USER_ALICE_WEBID}, \`azp\` ${KEYCLOAK_CLIENT_LEGAL_ID} |
| \`${FIXTURE_TOKEN_EXPIRED}\` | client credentials, \`${KEYCLOAK_CLIENT_FIXTURE_ID}\` (${KEYCLOAK_CLIENT_FIXTURE_TOKEN_LIFESPAN_SECONDS}s lifespan) — \`exp\` is in the past |
| \`*${FIXTURE_CLAIMS_SUFFIX}\` | the same tokens decoded (\`lib/jwt-decode.py\`), for reading and for asserting |

Every access token: \`iss\` \`${KEYCLOAK_ISSUER}\`, \`aud\` contains \`${KEYCLOAK_AUDIENCE}\`, \`alg\` RS256, a \`webid\` claim.
Nothing here is secret: public keys and bearer tokens for a loopback-only realm whose secrets live in
\`identities.env\`. Tests that verify these tokens must fix their clock (\`iat\`/\`exp\` are the capture instant).
EOF
note "${FIXTURE_README}"

say "Done: $(find "$FIXTURES_DIR" -type f | wc -l | tr -d ' ') files in ${FIXTURES_DIR}"
