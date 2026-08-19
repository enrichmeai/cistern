#!/usr/bin/env bash
# Captures cistern-auth's OIDC test fixtures from a REAL identity provider (ground rule 6).
#
# Nothing in ../fixtures/keycloak is hand-made: every JWKS document and every token here was
# minted by the Keycloak this script drives, and this script is the record of exactly how.
# Re-running it against a fresh Keycloak regenerates the whole set (with fresh keys and
# fresh sub UUIDs — the tests assert on structure and on the claims this script controls,
# never on a specific kid or sub).
#
# Prerequisites
#   docker run -d --name cistern-keycloak -p 8080:8080 -v cistern-kc-data:/opt/keycloak/data \
#     -e KC_BOOTSTRAP_ADMIN_USERNAME=admin -e KC_BOOTSTRAP_ADMIN_PASSWORD=admin \
#     quay.io/keycloak/keycloak:26.7.1 start-dev
#   curl and jq on the PATH.
#
# Usage
#   ./capture.sh            # writes into the directory this script lives in
#
# What it builds (all in realm "cistern")
#   - realm token lifespans of ten years, so the VALID fixtures do not rot: a captured token
#     with Keycloak's default five-minute lifespan would be an "expired" fixture by the time
#     the next build ran. The EXPIRED fixture comes from a client with a 60-second override.
#   - clients (all confidential):
#       valuedocs-legal   service accounts + password grant; aud "cistern"; webid claim
#       valuedocs-tax     same
#       unrelated-app     password grant; NO audience mapper (tokens carry aud "account" only)
#       short-lived-app   password grant; aud "cistern"; access.token.lifespan = 60 s
#   - users alice and bob, each with a "webid" attribute mapped to a "webid" claim
#   - the service-account users of valuedocs-legal / valuedocs-tax get webid attributes too
#   - a second RSA signing key added afterwards ("rotation"), to capture a token whose kid is
#     absent from the first JWKS document
set -euo pipefail

KC="${KC:-http://localhost:8080}"
REALM=cistern
OUT="$(cd "$(dirname "$0")" && pwd)"
TEN_YEARS=315360000

# Fixed secrets so the exported realm and this script agree; test-only values.
LEGAL_SECRET=8cd4ed3186c923e2fdeedd032cb9d5a0c42c357b720bc0f3
TAX_SECRET=273d573b2640b4f1b4350c0db64fefcf51ee08f655bf83be
UNRELATED_SECRET=4d0a669d84aba5436d9bf609a8ac4b796e355afcec6d7455
SHORT_SECRET=3da226f334524798611dbeb2feeb77df992974e0a0cf2e84
ALICE_PASSWORD=alice-password
BOB_PASSWORD=bob-password

say() { printf '\n\033[1m%s\033[0m\n' "$*"; }

say "admin token"
ADMIN_TOKEN=$(curl -sf -X POST "$KC/realms/master/protocol/openid-connect/token" \
  -d client_id=admin-cli -d grant_type=password -d username=admin -d password=admin | jq -r .access_token)
admin() { curl -sf -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' "$@"; }

say "realm $REALM"
admin -X POST "$KC/admin/realms" -d @- <<EOF
{
  "realm": "$REALM", "enabled": true,
  "accessTokenLifespan": $TEN_YEARS,
  "accessTokenLifespanForImplicitFlow": $TEN_YEARS,
  "ssoSessionIdleTimeout": $TEN_YEARS, "ssoSessionMaxLifespan": $TEN_YEARS,
  "clientSessionIdleTimeout": $TEN_YEARS, "clientSessionMaxLifespan": $TEN_YEARS
}
EOF

say "user profile: declare the webid attribute (Keycloak 24+ drops undeclared attributes)"
PROFILE=$(admin "$KC/admin/realms/$REALM/users/profile")
echo "$PROFILE" | jq '.attributes += [{
  "name": "webid", "displayName": "WebID", "multivalued": false,
  "permissions": {"view": ["admin","user"], "edit": ["admin"]}
}]' | admin -X PUT "$KC/admin/realms/$REALM/users/profile" -d @-

# Protocol mappers, as Keycloak's admin API represents them.
AUDIENCE_MAPPER='{
  "name": "cistern-audience", "protocol": "openid-connect", "protocolMapper": "oidc-audience-mapper",
  "config": {"included.custom.audience": "cistern", "access.token.claim": "true", "id.token.claim": "false"}
}'
WEBID_MAPPER='{
  "name": "webid", "protocol": "openid-connect", "protocolMapper": "oidc-usermodel-attribute-mapper",
  "config": {"user.attribute": "webid", "claim.name": "webid", "jsonType.label": "String",
             "access.token.claim": "true", "id.token.claim": "true", "userinfo.token.claim": "true"}
}'

create_client() { # id secret service-accounts mappers-json [attributes-json]
  local id=$1 secret=$2 sa=$3 mappers=$4 attrs=${5:-'{}'}
  admin -X POST "$KC/admin/realms/$REALM/clients" -d @- <<EOF
{
  "clientId": "$id", "enabled": true, "publicClient": false, "secret": "$secret",
  "protocol": "openid-connect", "standardFlowEnabled": false, "implicitFlowEnabled": false,
  "directAccessGrantsEnabled": true, "serviceAccountsEnabled": $sa,
  "protocolMappers": $mappers, "attributes": $attrs
}
EOF
}

say "clients"
create_client valuedocs-legal "$LEGAL_SECRET" true "[$AUDIENCE_MAPPER, $WEBID_MAPPER]"
create_client valuedocs-tax   "$TAX_SECRET"   true "[$AUDIENCE_MAPPER, $WEBID_MAPPER]"
create_client unrelated-app   "$UNRELATED_SECRET" false "[$WEBID_MAPPER]"
create_client short-lived-app "$SHORT_SECRET" false "[$AUDIENCE_MAPPER, $WEBID_MAPPER]" '{"access.token.lifespan": "60"}'

create_user() { # username password webid
  admin -X POST "$KC/admin/realms/$REALM/users" -d @- <<EOF
{
  "username": "$1", "enabled": true, "email": "$1@example.test", "emailVerified": true,
  "firstName": "$1", "lastName": "Example",
  "credentials": [{"type": "password", "value": "$2", "temporary": false}],
  "attributes": {"webid": ["$3"]}
}
EOF
}

say "users"
create_user alice "$ALICE_PASSWORD" "https://alice.example/profile/card#me"
create_user bob   "$BOB_PASSWORD"   "https://bob.example/profile/card#me"

set_service_account_webid() { # client-id webid
  local cid uid
  cid=$(admin "$KC/admin/realms/$REALM/clients?clientId=$1" | jq -r '.[0].id')
  uid=$(admin "$KC/admin/realms/$REALM/clients/$cid/service-account-user" | jq -r .id)
  admin "$KC/admin/realms/$REALM/users/$uid" \
    | jq --arg w "$2" '.attributes = ((.attributes // {}) + {"webid": [$w]})' \
    | admin -X PUT "$KC/admin/realms/$REALM/users/$uid" -d @-
}
say "service-account WebIDs"
set_service_account_webid valuedocs-legal "https://valuedocs.co.in/apps/legal#id"
set_service_account_webid valuedocs-tax   "https://valuedocs.co.in/apps/tax#id"

TOKEN_URL="$KC/realms/$REALM/protocol/openid-connect/token"
password_token() { # client secret user password
  curl -sf -X POST "$TOKEN_URL" -d grant_type=password -d client_id="$1" -d client_secret="$2" \
    -d username="$3" -d password="$4" | jq -r .access_token
}
client_token() { # client secret
  curl -sf -X POST "$TOKEN_URL" -d grant_type=client_credentials -d client_id="$1" -d client_secret="$2" \
    | jq -r .access_token
}

say "discovery document and JWKS (key set 1)"
curl -sf "$KC/realms/$REALM/.well-known/openid-configuration" | jq . > "$OUT/openid-configuration.json"
JWKS_URI=$(jq -r .jwks_uri "$OUT/openid-configuration.json")
curl -sf "$JWKS_URI" | jq . > "$OUT/jwks.json"

say "tokens against key set 1"
T="$OUT/tokens"
password_token valuedocs-legal "$LEGAL_SECRET" alice "$ALICE_PASSWORD"     > "$T/alice-valid.jwt"
password_token valuedocs-legal "$LEGAL_SECRET" bob   "$BOB_PASSWORD"       > "$T/bob-valid.jwt"
client_token   valuedocs-legal "$LEGAL_SECRET"                              > "$T/valuedocs-legal-valid.jwt"
client_token   valuedocs-tax   "$TAX_SECRET"                                > "$T/valuedocs-tax-valid.jwt"
password_token unrelated-app   "$UNRELATED_SECRET" alice "$ALICE_PASSWORD" > "$T/alice-wrong-audience.jwt"
password_token short-lived-app "$SHORT_SECRET"     alice "$ALICE_PASSWORD" > "$T/alice-expired.jwt"

say "bad signature: alice-valid with the last character of its signature segment changed"
# The header and payload are the real ones Keycloak issued; only the signature is corrupted,
# which is exactly what a tampered token looks like on the wire. Keycloak cannot mint a token
# whose signature is wrong for a key it publishes, so this one is derived and says so here.
VALID=$(cat "$T/alice-valid.jwt")
LAST=${VALID: -1}
if [ "$LAST" = "A" ]; then REPLACEMENT=B; else REPLACEMENT=A; fi
printf '%s%s' "${VALID%?}" "$REPLACEMENT" > "$T/alice-bad-signature.jwt"

say "key rotation: add a second RSA key at higher priority, capture key set 2 and a token under it"
REALM_ID=$(admin "$KC/admin/realms/$REALM" | jq -r .id)
admin -X POST "$KC/admin/realms/$REALM/components" -d @- <<EOF
{
  "name": "rsa-generated-2", "providerId": "rsa-generated", "providerType": "org.keycloak.keys.KeyProvider",
  "parentId": "$REALM_ID",
  "config": {"priority": ["200"], "enabled": ["true"], "active": ["true"], "algorithm": ["RS256"], "keySize": ["2048"]}
}
EOF
curl -sf "$JWKS_URI" | jq . > "$OUT/jwks-rotated.json"
password_token valuedocs-legal "$LEGAL_SECRET" alice "$ALICE_PASSWORD" > "$T/alice-rotated-key.jwt"

say "summary (header.kid, iss, aud, exp, sub, webid) — for the README"
b64url_decode() { tr '_-' '/+' | awk '{p=length($0)%4; if(p) $0=$0 substr("===",1,4-p); print}' | base64 -d; }
for f in "$T"/*.jwt; do
  hdr=$(cut -d. -f1 "$f" | b64url_decode)
  pl=$(cut -d. -f2 "$f" | b64url_decode)
  printf '%-28s kid=%s alg=%s\n' "$(basename "$f")" "$(echo "$hdr" | jq -r .kid)" "$(echo "$hdr" | jq -r .alg)"
  echo "$pl" | jq -c '{iss, aud, exp, sub, azp, webid}'
done
echo
echo "Now export the realm (server must be stopped for the dev-file H2 store):"
echo "  docker stop cistern-keycloak"
echo "  docker run --rm -v cistern-kc-data:/opt/keycloak/data quay.io/keycloak/keycloak:26.7.1 \\"
echo "    export --realm $REALM --users realm_file --file /opt/keycloak/data/export/realm-export.json"
echo "  docker run --rm -v cistern-kc-data:/data alpine cat /data/export/realm-export.json > $OUT/realm-export.json"
