# Fixtures — captured from a real Keycloak, not written by hand

Produced by `integration-kit/capture-fixtures.sh` on 2026-09-10T05:46:32Z against
Keycloak 26.7.1 (`quay.io/keycloak/keycloak:latest`) running `integration-kit/keycloak/realm-cistern.json`.
Regenerate with `docker compose up -d && ./capture-fixtures.sh`; the signing keys travel in the realm
export, so `jwks.json` verifies tokens from every fresh import — only timestamps move.

| File | What |
|---|---|
| `openid-configuration.json` | `/realms/cistern/.well-known/openid-configuration` — issuer `http://keycloak:8080/realms/cistern` |
| `jwks.json` | `/realms/cistern/protocol/openid-connect/certs` — public keys (RS256 signing key + RSA-OAEP enc key) |
| `token-valuedocs-legal.jwt` | client credentials, `valuedocs-legal` → `webid` https://valuedocs.example/apps/legal#id |
| `token-valuedocs-tax.jwt` | client credentials, `valuedocs-tax` → `webid` https://valuedocs.example/apps/tax#id |
| `token-alice-via-valuedocs-legal.jwt` | password grant, alice through `valuedocs-legal` → `webid` https://acme-law.example/people/alice#me, `azp` valuedocs-legal |
| `token-expired.jwt` | client credentials, `fixture-short-lived` (1s lifespan) — `exp` is in the past |
| `token-carol-via-broker.jwt` | authorization code, carol through `valuedocs-legal`, brokered: she exists only in realm `external-idp` and signed in there (captured by `broker_login` in `capture-fixtures.sh`: the browser flow, driven by curl). **No `webid` claim**; `sub` 7818c658-34dc-4140-81f4-61200e5ea454 is what `webid-template` `{iss}/users/{sub}#me` uses → http://keycloak:8080/realms/cistern/users/7818c658-34dc-4140-81f4-61200e5ea454#me |
| `*.claims.json` | the same tokens decoded (`lib/jwt-decode.py`), for reading and for asserting |

Every access token: `iss` `http://keycloak:8080/realms/cistern`, `aud` contains `cistern`, `alg` RS256, a `webid` claim — except `token-carol-via-broker.jwt`, which has none (its WebID is built from `iss` and `sub`).
Nothing here is secret: public keys and bearer tokens for a loopback-only realm whose secrets live in
`identities.env`. Tests that verify these tokens must fix their clock (`iat`/`exp` are the capture instant).
