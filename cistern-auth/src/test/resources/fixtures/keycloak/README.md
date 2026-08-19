# OIDC fixtures — captured from Keycloak 26.7.1

Every file here was produced by a real identity provider (ground rule 6): **Keycloak 26.7.1**,
image `quay.io/keycloak/keycloak:26.7.1`
(`sha256:f1f1f01e472c8a78df40d8f2a49a925274eda4d3d80d5f6edbb5c880ee3c01c6`), started as

```bash
docker run -d --name cistern-keycloak -p 8080:8080 -v cistern-kc-data:/opt/keycloak/data \
  -e KC_BOOTSTRAP_ADMIN_USERNAME=admin -e KC_BOOTSTRAP_ADMIN_PASSWORD=admin \
  quay.io/keycloak/keycloak:26.7.1 start-dev
```

and driven by [`capture.sh`](capture.sh) on **2026-08-19 (00:04 UTC)**. The script is the
capture procedure; re-running it against a fresh Keycloak regenerates the whole set (with fresh
signing keys and fresh `sub` UUIDs — the tests assert on structure and on the claims the script
controls, never on a specific `kid` or `sub`). No JWT here was assembled by hand.

## The realm

`cistern`, exported with `kc.sh export --realm cistern --users same_file` into
[`realm-export.json`](realm-export.json). Access-token and session lifespans are **ten years**
so that the *valid* fixtures do not rot between builds (Keycloak's five-minute default would
turn every valid token into an expired one by the next run).

| Client | Kind | Audience mapper (`aud: cistern`) | `webid` claim mapper | Notes |
|---|---|---|---|---|
| `valuedocs-legal` | confidential, service account, password grant | yes | yes | secret in the export |
| `valuedocs-tax` | confidential, service account, password grant | yes | yes | secret in the export |
| `unrelated-app` | confidential, password grant | **no** → tokens carry `aud: "account"` only | yes | source of the wrong-audience token |
| `short-lived-app` | confidential, password grant | yes | yes | `access.token.lifespan = 60` → source of the expired token |

Users `alice` and `bob` (passwords `alice-password`, `bob-password`) each carry a `webid`
attribute, declared in the realm's user profile and mapped into tokens as the `webid` claim by
the `oidc-usermodel-attribute-mapper`. The service-account users of `valuedocs-legal` and
`valuedocs-tax` carry `webid` attributes too, so their client-credentials tokens name the
application's own WebID:

| Principal | `webid` |
|---|---|
| alice | `https://alice.example/profile/card#me` |
| bob | `https://bob.example/profile/card#me` |
| service-account-valuedocs-legal | `https://valuedocs.co.in/apps/legal#id` |
| service-account-valuedocs-tax | `https://valuedocs.co.in/apps/tax#id` |

**One redaction, stated:** in `realm-export.json` the `privateKey` values of the RSA key
providers and the `secret` values of the HMAC/AES providers are replaced with a `REDACTED`
marker. The public halves are what the tests need and are captured separately below; the private
halves are the one thing in this directory that would let anyone mint new "fixtures" by hand,
which is exactly what ground rule 6 forbids. Everything else in the export — clients, secrets,
mappers, users, lifespans, user profile — is verbatim.

## Documents

| File | Captured how |
|---|---|
| `openid-configuration.json` | `GET http://localhost:8080/realms/cistern/.well-known/openid-configuration` (piped through `jq .`) |
| `jwks.json` | `GET <jwks_uri>` before any key was added: one RS256 signing key (`kid YXVwKlTA…`) and Keycloak's RSA-OAEP encryption key |
| `jwks-rotated.json` | the same, after `capture.sh` added a second `rsa-generated` provider at priority 200: both signing keys |

## Tokens (`tokens/`)

All are RS256 access tokens with `iss = http://localhost:8080/realms/cistern`. `capture.sh`
prints the summary below at the end of a run.

| File | Grant | Signed with | `aud` | `exp` | Purpose |
|---|---|---|---|---|---|
| `alice-valid.jwt` | password, via `valuedocs-legal` | key 1 | `["cistern","account"]` | 2036-08-19 | the valid token; `webid` → alice |
| `bob-valid.jwt` | password, via `valuedocs-legal` | key 1 | `["cistern","account"]` | 2036-08-19 | valid, but no grant in the tests → 403 |
| `valuedocs-legal-valid.jwt` | client credentials | key 1 | `["cistern","account"]` | 2036-08-19 | the legal application as itself |
| `valuedocs-tax-valid.jwt` | client credentials | key 1 | `["cistern","account"]` | 2036-08-19 | the tax application as itself |
| `alice-expired.jwt` | password, via `short-lived-app` | key 1 | `["cistern","account"]` | 2026-08-19T00:05:39Z (60 s) | expired |
| `alice-wrong-audience.jwt` | password, via `unrelated-app` | key 1 | `"account"` | 2036-08-19 | wrong audience (note: a string, not an array) |
| `alice-rotated-key.jwt` | password, via `valuedocs-legal`, after rotation | **key 2** (`kid zWHezfKk…`) | `["cistern","account"]` | 2036-08-19 | its `kid` is absent from `jwks.json`, present in `jwks-rotated.json` |
| `alice-bad-signature.jwt` | derived from `alice-valid.jwt` | key 1's `kid`, signature corrupted | as alice-valid | as alice-valid | bad signature with a *known* kid |

**On `alice-bad-signature.jwt`:** Keycloak cannot mint a token whose signature is wrong for a
key it publishes, so this one is `alice-valid.jwt` with the last character of its signature
segment changed (`capture.sh`, step "bad signature"). Header and payload are the ones Keycloak
issued, byte for byte; only the third segment differs. That is what a tampered token looks like
on the wire, and it is the only file here that is not exactly as the identity provider produced
it.

## Regenerating

```bash
docker run -d --name cistern-keycloak -p 8080:8080 -v cistern-kc-data:/opt/keycloak/data \
  -e KC_BOOTSTRAP_ADMIN_USERNAME=admin -e KC_BOOTSTRAP_ADMIN_PASSWORD=admin \
  quay.io/keycloak/keycloak:26.7.1 start-dev
./capture.sh                                    # realm, users, clients, keys, tokens
docker stop cistern-keycloak                    # the dev-file H2 store cannot be exported while serving
docker run --rm -v cistern-kc-data:/data alpine sh -c 'mkdir -p /data/export && chmod 777 /data/export'
docker run --rm -v cistern-kc-data:/opt/keycloak/data quay.io/keycloak/keycloak:26.7.1 \
  export --realm cistern --users same_file --file /opt/keycloak/data/export/realm-export.json
docker run --rm -v cistern-kc-data:/data alpine cat /data/export/realm-export.json \
  | jq '(.components["org.keycloak.keys.KeyProvider"][].config | select(has("privateKey")) | .privateKey) = ["REDACTED"]
      | (.components["org.keycloak.keys.KeyProvider"][].config | select(has("secret")) | .secret) = ["REDACTED"]' \
  > realm-export.json
```

`alice-expired.jwt` becomes usable as an expired fixture 60 s (plus the configured skew) after
capture; the tests use the system clock, so run them after that.
