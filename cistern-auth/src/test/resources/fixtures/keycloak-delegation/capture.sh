#!/usr/bin/env bash
# Captures the delegation fixtures — tokens whose client identifier is an absolute URI — from a
# REAL identity provider (ground rule 6), exactly as ../keycloak/capture.sh captures the T4.0 set.
#
# Why a second realm rather than more tokens in ../keycloak: the clients there are named
# "valuedocs-legal" and friends — opaque Keycloak client ids — and an opaque client id names
# no client a policy could match (Agent.client() is an Optional<URI> for that reason). The
# (person, client) principal of T6.5 needs a client whose identifier IS a URI, which is what a
# Solid Client Identifier Document is and what an application registered under its own WebID
# would use. Registering such clients is a realm change, and that realm is owned by another
# ticket's fixtures; this one is separate so neither capture disturbs the other.
#
# Nothing in this directory is hand-made: every JWKS document and every token here was minted
# by the Keycloak this script drives, and this script is the record of exactly how. Re-running
# it against a fresh Keycloak regenerates the whole set (with fresh keys and fresh sub UUIDs —
# the tests assert on structure and on the claims this script controls, never on a kid or sub).
#
# Prerequisites
#   docker run -d --name cistern-t65-kc -p 127.0.0.1:18092:8080 -v cistern-t65-kc-data:/opt/keycloak/data \
#     -e KC_BOOTSTRAP_ADMIN_USERNAME=admin -e KC_BOOTSTRAP_ADMIN_PASSWORD=admin \
#     quay.io/keycloak/keycloak:26.7.1 start-dev
#   curl and jq on the PATH.
#
# Usage
#   ./capture.sh            # writes into the directory this script lives in
#
# What it builds (all in realm "cistern-delegation")
#   - realm token lifespans of ten years, so the VALID fixtures do not rot between builds
#   - clients (both confidential, password grant, aud "cistern", webid claim), whose client
#     ids are absolute URIs:
#       https://agents.example/claude#id   also service accounts, so a client-credentials
#                                          token can be captured — that one carries client_id
#       https://agents.example/other#id    a second client, so "via claude" and "via other"
#                                          can be told apart through the whole filter chain
#   - user alice, with a "webid" attribute mapped to a "webid" claim
#   - the service-account user of the claude client gets a webid attribute too
set -euo pipefail

KC="${KC:-http://localhost:18092}"
REALM=cistern-delegation
OUT="$(cd "$(dirname "$0")" && pwd)"
TEN_YEARS=315360000

CLAUDE_CLIENT='https://agents.example/claude#id'
OTHER_CLIENT='https://agents.example/other#id'

# Fixed secrets so the exported realm and this script agree; test-only values.
CLAUDE_SECRET=5b1e8d0c7a9f4e2b6c3d1a0f8e7b6c5d4a3f2e1d0c9b8a7f
OTHER_SECRET=9f8e7d6c5b4a3f2e1d0c9b8a7f6e5d4c3b2a1f0e9d8c7b6a
ALICE_PASSWORD=alice-password

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

AUDIENCE_MAPPER='{
  "name": "cistern-audience", "protocol": "openid-connect", "protocolMapper": "oidc-audience-mapper",
  "config": {"included.custom.audience": "cistern", "access.token.claim": "true", "id.token.claim": "false"}
}'
WEBID_MAPPER='{
  "name": "webid", "protocol": "openid-connect", "protocolMapper": "oidc-usermodel-attribute-mapper",
  "config": {"user.attribute": "webid", "claim.name": "webid", "jsonType.label": "String",
             "access.token.claim": "true", "id.token.claim": "true", "userinfo.token.claim": "true"}
}'

create_client() { # id secret service-accounts
  local id=$1 secret=$2 sa=$3
  admin -X POST "$KC/admin/realms/$REALM/clients" -d @- <<EOF
{
  "clientId": "$id", "enabled": true, "publicClient": false, "secret": "$secret",
  "protocol": "openid-connect", "standardFlowEnabled": false, "implicitFlowEnabled": false,
  "directAccessGrantsEnabled": true, "serviceAccountsEnabled": $sa,
  "protocolMappers": [$AUDIENCE_MAPPER, $WEBID_MAPPER]
}
EOF
}

say "clients (client ids are absolute URIs)"
create_client "$CLAUDE_CLIENT" "$CLAUDE_SECRET" true
create_client "$OTHER_CLIENT"  "$OTHER_SECRET"  false

say "user alice"
admin -X POST "$KC/admin/realms/$REALM/users" -d @- <<EOF
{
  "username": "alice", "enabled": true, "email": "alice@example.test", "emailVerified": true,
  "firstName": "alice", "lastName": "Example",
  "credentials": [{"type": "password", "value": "$ALICE_PASSWORD", "temporary": false}],
  "attributes": {"webid": ["https://alice.example/profile/card#me"]}
}
EOF

say "service-account WebID for the claude client (its own WebID is its client id)"
CID=$(admin -G "$KC/admin/realms/$REALM/clients" --data-urlencode "clientId=$CLAUDE_CLIENT" | jq -r '.[0].id')
UID_=$(admin "$KC/admin/realms/$REALM/clients/$CID/service-account-user" | jq -r .id)
admin "$KC/admin/realms/$REALM/users/$UID_" \
  | jq --arg w "$CLAUDE_CLIENT" '.attributes = ((.attributes // {}) + {"webid": [$w]})' \
  | admin -X PUT "$KC/admin/realms/$REALM/users/$UID_" -d @-

TOKEN_URL="$KC/realms/$REALM/protocol/openid-connect/token"
password_token() { # client secret user password
  curl -sf -X POST "$TOKEN_URL" -d grant_type=password --data-urlencode "client_id=$1" \
    --data-urlencode "client_secret=$2" -d username="$3" -d password="$4" | jq -r .access_token
}
client_token() { # client secret
  curl -sf -X POST "$TOKEN_URL" -d grant_type=client_credentials --data-urlencode "client_id=$1" \
    --data-urlencode "client_secret=$2" | jq -r .access_token
}

say "discovery document and JWKS"
curl -sf "$KC/realms/$REALM/.well-known/openid-configuration" | jq . > "$OUT/openid-configuration.json"
JWKS_URI=$(jq -r .jwks_uri "$OUT/openid-configuration.json")
curl -sf "$JWKS_URI" | jq . > "$OUT/jwks.json"

say "tokens"
T="$OUT/tokens"
mkdir -p "$T"
password_token "$CLAUDE_CLIENT" "$CLAUDE_SECRET" alice "$ALICE_PASSWORD" > "$T/alice-via-claude.jwt"
password_token "$OTHER_CLIENT"  "$OTHER_SECRET"  alice "$ALICE_PASSWORD" > "$T/alice-via-other.jwt"
client_token   "$CLAUDE_CLIENT" "$CLAUDE_SECRET"                         > "$T/claude-self.jwt"

say "summary (header.kid, iss, aud, exp, sub, azp, client_id, webid) — for the README"
b64url_decode() { tr '_-' '/+' | awk '{p=length($0)%4; if(p) $0=$0 substr("===",1,4-p); print}' | base64 -d; }
for f in "$T"/*.jwt; do
  hdr=$(cut -d. -f1 "$f" | b64url_decode)
  pl=$(cut -d. -f2 "$f" | b64url_decode)
  printf '%-24s kid=%s alg=%s\n' "$(basename "$f")" "$(echo "$hdr" | jq -r .kid)" "$(echo "$hdr" | jq -r .alg)"
  echo "$pl" | jq -c '{iss, aud, exp, sub, azp, client_id, webid}'
done
echo
echo "Now export the realm (server must be stopped for the dev-file H2 store):"
echo "  docker stop cistern-t65-kc"
echo "  docker run --rm -v cistern-t65-kc-data:/data alpine sh -c 'mkdir -p /data/export && chmod 777 /data/export'"
echo "  docker run --rm -v cistern-t65-kc-data:/opt/keycloak/data quay.io/keycloak/keycloak:26.7.1 \\"
echo "    export --realm $REALM --users same_file --file /opt/keycloak/data/export/realm-export.json"
echo "  docker run --rm -v cistern-t65-kc-data:/data alpine cat /data/export/realm-export.json \\"
echo "    | jq '(.components[\"org.keycloak.keys.KeyProvider\"][].config | select(has(\"privateKey\")) | .privateKey) = [\"REDACTED\"]"
echo "        | (.components[\"org.keycloak.keys.KeyProvider\"][].config | select(has(\"secret\")) | .secret) = [\"REDACTED\"]' \\"
echo "    > $OUT/realm-export.json"
