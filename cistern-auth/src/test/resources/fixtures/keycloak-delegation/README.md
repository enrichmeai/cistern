# Delegation fixtures — captured from Keycloak 26.7.1

Every file here was produced by a real identity provider (ground rule 6): **Keycloak 26.7.1**,
image `quay.io/keycloak/keycloak:26.7.1`
(`sha256:f1f1f01e472c8a78df40d8f2a49a925274eda4d3d80d5f6edbb5c880ee3c01c6` — the same image
as [`../keycloak`](../keycloak/README.md)), started as

```bash
docker run -d --name cistern-t65-kc -p 127.0.0.1:18092:8080 -v cistern-t65-kc-data:/opt/keycloak/data \
  -e KC_BOOTSTRAP_ADMIN_USERNAME=admin -e KC_BOOTSTRAP_ADMIN_PASSWORD=admin \
  quay.io/keycloak/keycloak:26.7.1 start-dev
```

and driven by [`capture.sh`](capture.sh) on **2026-09-10 (05:30 UTC)**. The script is the
capture procedure; re-running it against a fresh Keycloak regenerates the whole set (with a
fresh signing key and fresh `sub` UUIDs — the tests assert on structure and on the claims the
script controls, never on a specific `kid` or `sub`). No JWT here was assembled by hand.

## Why a second realm

The (person, client) principal of T6.5 (#119) needs a token whose client identifier is an
**absolute URI** — a Solid Client Identifier Document, or an application registered under its
own WebID — because `Agent.client()` is an `Optional<URI>`: an opaque client id names no
client a policy could be written against, so it reads as no client at all. Every client in the
T4.0 realm (`valuedocs-legal`, `unrelated-app`, …) has an opaque Keycloak client id, so none
of those tokens can exercise the client half. Registering URI-named clients is a realm change,
and that realm's fixtures belong to another ticket; this realm is separate so neither capture
disturbs the other.

## The realm

`cistern-delegation`, exported with `kc.sh export --realm cistern-delegation --users same_file`
into [`realm-export.json`](realm-export.json). Access-token and session lifespans are **ten
years** so that the fixtures do not rot between builds.

| Client (its `clientId` **is** the URI) | Kind | Audience mapper (`aud: cistern`) | `webid` claim mapper |
|---|---|---|---|
| `https://agents.example/claude#id` | confidential, password grant **and** service account | yes | yes |
| `https://agents.example/other#id` | confidential, password grant | yes | yes |

User `alice` (password `alice-password`) carries a `webid` attribute, declared in the realm's
user profile and mapped into tokens as the `webid` claim. The service-account user of the
claude client carries a `webid` attribute too, equal to its client id, so its
client-credentials token names the application's own WebID — the shape #89 ruled for v1.

| Principal | `webid` |
|---|---|
| alice | `https://alice.example/profile/card#me` |
| service-account for `https://agents.example/claude#id` | `https://agents.example/claude#id` |

**One redaction, stated:** in `realm-export.json` the `privateKey` values of the RSA key
providers and the `secret` values of the HMAC/AES providers are replaced with a `REDACTED`
marker, exactly as in `../keycloak`. The public half is `jwks.json`; the private halves are the
one thing that would let anyone mint new "fixtures" by hand. Everything else — clients,
secrets, mappers, users, lifespans, user profile — is verbatim.

## Documents

| File | Captured how |
|---|---|
| `openid-configuration.json` | `GET http://localhost:18092/realms/cistern-delegation/.well-known/openid-configuration` (piped through `jq .`) |
| `jwks.json` | `GET <jwks_uri>`: one RS256 signing key (`kid VSN7p9v6…`) and Keycloak's RSA-OAEP encryption key |

## Tokens (`tokens/`)

All are RS256 access tokens with `iss = http://localhost:18092/realms/cistern-delegation`,
`aud = ["cistern","account"]` and `exp = 2036-09-07T05:30:19Z`. `capture.sh` prints the
summary below at the end of a run.

| File | Grant | `azp` | `client_id` | `webid` |
|---|---|---|---|---|
| `alice-via-claude.jwt` | password, via the claude client | `https://agents.example/claude#id` | **absent** | alice |
| `alice-via-other.jwt` | password, via the other client | `https://agents.example/other#id` | **absent** | alice |
| `claude-self.jwt` | client credentials | `https://agents.example/claude#id` | `https://agents.example/claude#id` | `https://agents.example/claude#id` |

**The finding these fixtures record.** A Keycloak-issued *user* access token names its client
in `azp` only; `client_id` is absent. Only a client-credentials token carries `client_id`
(alongside `azp`, with the same value). The Solid identity provider captured in
[`../css`](../css/README.md) does the opposite — `client_id`, no `azp`. So a resolver that
read `client_id` alone would populate `Agent.client()` for a Solid-OIDC token and for an
application acting as itself, but never for a person acting through an application on a
plain-OIDC issuer — which is the one case a client-scoped grant exists for. `ClientIdentifier`
reads `client_id` first and `azp` second, and this directory is the evidence for the second.

## Regenerating

```bash
docker run -d --name cistern-t65-kc -p 127.0.0.1:18092:8080 -v cistern-t65-kc-data:/opt/keycloak/data \
  -e KC_BOOTSTRAP_ADMIN_USERNAME=admin -e KC_BOOTSTRAP_ADMIN_PASSWORD=admin \
  quay.io/keycloak/keycloak:26.7.1 start-dev
./capture.sh                                    # realm, user, clients, key, tokens
docker stop cistern-t65-kc                      # the dev-file H2 store cannot be exported while serving
docker run --rm -v cistern-t65-kc-data:/data alpine sh -c 'mkdir -p /data/export && chmod 777 /data/export'
docker run --rm -v cistern-t65-kc-data:/opt/keycloak/data quay.io/keycloak/keycloak:26.7.1 \
  export --realm cistern-delegation --users same_file --file /opt/keycloak/data/export/realm-export.json
docker run --rm -v cistern-t65-kc-data:/data alpine cat /data/export/realm-export.json \
  | jq '(.components["org.keycloak.keys.KeyProvider"][].config | select(has("privateKey")) | .privateKey) = ["REDACTED"]
      | (.components["org.keycloak.keys.KeyProvider"][].config | select(has("secret")) | .secret) = ["REDACTED"]' \
  > realm-export.json
```
