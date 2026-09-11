#!/usr/bin/env bash
# Captures real-first fixtures from the running Keycloak into fixtures/ (ground rule 6):
# the realm's discovery document and JWKS, a valid access token per service principal, one
# human token, one token that is genuinely expired, and — when the stand-in identity
# provider is configured (T7.16) — one token for a person who exists only behind the broker.
# #88's tests copy from here; nothing in cistern-auth/ is written by this script.
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
readonly FIXTURE_TOKEN_CAROL="token-carol-via-broker.jwt"
readonly FIXTURE_CLAIMS_SUFFIX=".claims.json"
readonly FIXTURE_README="README.md"
readonly JWT_DECODE="${KIT_DIR}/lib/jwt-decode.py"
readonly EXPIRY_MARGIN_SECONDS=2
readonly KEYCLOAK_IMAGE="quay.io/keycloak/keycloak:latest"

# ---- the brokered sign-in, as a browser drives it (T7.16) --------------------------------
readonly OIDC_AUTHORIZATION_ENDPOINT="/protocol/openid-connect/auth"
readonly RESPONSE_TYPE_CODE="code"
readonly SCOPE_OPENID="openid"
readonly GRANT_TYPE_AUTHORIZATION_CODE="authorization_code"
readonly BROKER_STATE="kit"                  # opaque to Keycloak; a real app checks it on return
readonly LOGIN_FORM_ACTION_PATTERN='action="[^"]*"'
readonly HTML_AMPERSAND='&amp;'
readonly WEBID_TEMPLATE='{iss}/users/{sub}#me'   # the template docker-compose.yml's brokered block sets
# Every URL Keycloak hands the browser during a brokered login is on the issuer's origin
# (KC_HOSTNAME, `keycloak:8080`), which this host cannot resolve; curl's --connect-to sends
# those connections to the published port instead, leaving the URLs — and so the cookies and
# the redirect-URI checks — exactly as a browser would see them.
readonly ISSUER_HOST_AND_PORT="${KEYCLOAK_ISSUER#*://}"
readonly CONNECT_TO="${ISSUER_HOST_AND_PORT%%/*}:127.0.0.1:${KEYCLOAK_HOST_PORT}"
BROKER_JAR="$(mktemp)"; readonly BROKER_JAR
BROKER_BODY="$(mktemp)"; readonly BROKER_BODY
trap 'rm -f "$BROKER_JAR" "$BROKER_BODY"' EXIT

# One step of the browser's journey: keeps the cookies, saves the page, prints the redirect if any.
hop() { curl -s -o "$BROKER_BODY" -w '%{redirect_url}' -c "$BROKER_JAR" -b "$BROKER_JAR" --connect-to "$CONNECT_TO" "$@"; }
# What the last page said, for a failure message: its title and Keycloak's error line if there is one.
page_error() {
  tr -d '\n' < "$BROKER_BODY" | { grep -o '<title>[^<]*\|kc-error-message[^<]*<p[^>]*>[^<]*' || true; } | sed 's/.*>//' | tr '\n' ' '
}

# Follows redirects from <location> until a page comes back (prints nothing) or a redirect to
# the app's callback does (prints it) — the one redirect a browser would deliver to the app.
follow() {   # follow <location> <redirect-uri>
  local location="$1" redirect_uri="$2"
  while [ -n "$location" ] && [ "${location#"$redirect_uri"}" = "$location" ]; do
    location="$(hop "$location")"
  done
  printf '%s' "$location"
}

# A brokered sign-in, driven the way a browser drives it. No grant crosses a broker —
# Keycloak's direct grant authenticates the realm's own users only — so the capture is the
# authorization-code flow itself, which is exactly what Google's brokering will use:
#   1. the app sends the person to the cistern realm's authorization endpoint; kc_idp_hint
#      names the provider, so the realm sends them on to it (via its own /broker/<alias>/login)
#   2. the provider (here: the stand-in realm) shows its login form
#   3. the person signs in there; the provider redirects back to the cistern realm's broker
#      endpoint with the provider's code
#   4. the cistern realm redeems that code with the provider, runs the first-broker-login
#      flow (creates the user; asks nothing) and redirects to the app's callback with its own
#      code — which nobody follows: the code is read from the redirect, as the app would
#   5. the app redeems the code at the cistern realm's token endpoint: an ordinary
#      authorization-code grant, and the access token is the cistern realm's
# Prints the raw access token.
broker_login() {   # broker_login <client-id> <client-secret> <redirect-uri> <idp-alias> <username> <password>
  local client_id="$1" client_secret="$2" redirect_uri="$3" idp="$4" user="$5" pass="$6"
  local location form_action code
  location="$(hop -G "${KEYCLOAK_ISSUER}${OIDC_AUTHORIZATION_ENDPOINT}" \
    --data-urlencode "client_id=${client_id}" --data-urlencode "redirect_uri=${redirect_uri}" \
    --data-urlencode "response_type=${RESPONSE_TYPE_CODE}" --data-urlencode "scope=${SCOPE_OPENID}" \
    --data-urlencode "kc_idp_hint=${idp}" --data-urlencode "state=${BROKER_STATE}")"
  [ -n "$location" ] || die "realm ${KEYCLOAK_REALM} did not send ${user} on to provider ${idp}: $(page_error)"
  location="$(follow "$location" "$redirect_uri")"
  if [ -z "$location" ]; then   # a page: the provider's login form (a session already signed in would have skipped it)
    form_action="$(tr -d '\n' < "$BROKER_BODY" | { grep -o "$LOGIN_FORM_ACTION_PATTERN" || true; } | head -n 1 \
      | sed "s/^action=\"//; s/\"\$//; s/${HTML_AMPERSAND}/\&/g")"
    [ -n "$form_action" ] || die "provider ${idp} showed no login form: $(page_error)"
    location="$(hop --data-urlencode "username=${user}" --data-urlencode "password=${pass}" "$form_action")"
    location="$(follow "$location" "$redirect_uri")"
  fi
  [ -n "$location" ] || die "brokered sign-in for ${user} did not reach ${redirect_uri}: $(page_error)"
  code="$(printf '%s' "$location" | sed -n 's/.*[?&]code=\([^&]*\).*/\1/p')"
  [ -n "$code" ] || die "no code in the callback redirect: ${location}"
  curl -sf -X POST "${KEYCLOAK_BASE}${KC_TOKEN_PATH}" -H "Content-Type: ${MEDIA_FORM}" \
    -d "grant_type=${GRANT_TYPE_AUTHORIZATION_CODE}" -d "client_id=${client_id}" -d "client_secret=${client_secret}" \
    -d "code=${code}" --data-urlencode "redirect_uri=${redirect_uri}" \
    | sed -n "s/.*\"${JSON_ACCESS_TOKEN_FIELD}\":\"\([^\"]*\)\".*/\1/p"
}

mkdir -p "$FIXTURES_DIR"

write_token() {   # write_token <file> <token>  -> writes token + decoded claims
  local file="$1" token="$2"
  [ -n "$token" ] || die "no access_token for ${file}"
  printf '%s' "$token" > "${FIXTURES_DIR}/${file}"
  python3 "$JWT_DECODE" "${FIXTURES_DIR}/${file}" > "${FIXTURES_DIR}/${file%.jwt}${FIXTURE_CLAIMS_SUFFIX}"
  note "${file}  (+ ${file%.jwt}${FIXTURE_CLAIMS_SUFFIX})"
}
save_token() {   # save_token <file> <client-id> <client-secret> [<user> <password>]
  local file="$1"; shift
  write_token "$file" "$(kc_token "$@")"
}
claim() {   # claim <claims-file> <name>  -> the claim's value
  python3 -c "import json,sys; print(json.load(open(sys.argv[1]))['claims'][sys.argv[2]])" "$1" "$2"
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

carol_row=""
carol_note=""
if [ -n "${KEYCLOAK_BROKER_REALM:-}" ]; then
  say "A brokered token: ${KEYCLOAK_BROKER_USER_CAROL} exists only in realm ${KEYCLOAK_BROKER_REALM}; the legal app signs her in through realm ${KEYCLOAK_REALM}"
  write_token "$FIXTURE_TOKEN_CAROL" "$(broker_login "$KEYCLOAK_CLIENT_LEGAL_ID" "$KEYCLOAK_CLIENT_LEGAL_SECRET" \
    "$KEYCLOAK_CLIENT_LEGAL_REDIRECT_URI" "$KEYCLOAK_BROKER_REALM" \
    "$KEYCLOAK_BROKER_USER_CAROL" "$KEYCLOAK_BROKER_USER_CAROL_PASSWORD")"
  carol_claims="${FIXTURES_DIR}/${FIXTURE_TOKEN_CAROL%.jwt}${FIXTURE_CLAIMS_SUFFIX}"
  carol_sub="$(claim "$carol_claims" sub)"
  carol_webid="${KEYCLOAK_ISSUER}/users/${carol_sub}#me"
  note "iss $(claim "$carol_claims" iss), sub ${carol_sub}, no webid claim -> ${WEBID_TEMPLATE} gives ${carol_webid}"
  carol_row="| \`${FIXTURE_TOKEN_CAROL}\` | authorization code, ${KEYCLOAK_BROKER_USER_CAROL} through \`${KEYCLOAK_CLIENT_LEGAL_ID}\`, brokered: she exists only in realm \`${KEYCLOAK_BROKER_REALM}\` and signed in there (captured by \`broker_login\` in \`capture-fixtures.sh\`: the browser flow, driven by curl). **No \`webid\` claim**; \`sub\` ${carol_sub} is what \`webid-template\` \`${WEBID_TEMPLATE}\` uses → ${carol_webid} |"
  carol_note=" — except \`${FIXTURE_TOKEN_CAROL}\`, which has none (its WebID is built from \`iss\` and \`sub\`)"
fi

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
${carol_row:+${carol_row}
}| \`*${FIXTURE_CLAIMS_SUFFIX}\` | the same tokens decoded (\`lib/jwt-decode.py\`), for reading and for asserting |

Every access token: \`iss\` \`${KEYCLOAK_ISSUER}\`, \`aud\` contains \`${KEYCLOAK_AUDIENCE}\`, \`alg\` RS256, a \`webid\` claim${carol_note}.
Nothing here is secret: public keys and bearer tokens for a loopback-only realm whose secrets live in
\`identities.env\`. Tests that verify these tokens must fix their clock (\`iat\`/\`exp\` are the capture instant).
EOF
note "${FIXTURE_README}"

say "Done: $(find "$FIXTURES_DIR" -type f | wc -l | tr -d ' ') files in ${FIXTURES_DIR}"
