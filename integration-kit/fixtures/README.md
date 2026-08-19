# Fixtures — captured from a real Keycloak, not written by hand

Produced by `integration-kit/capture-fixtures.sh` on 2026-08-19T00:16:23Z against
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
| `*.claims.json` | the same tokens decoded (`lib/jwt-decode.py`), for reading and for asserting |

Every access token: `iss` `http://keycloak:8080/realms/cistern`, `aud` contains `cistern`, `alg` RS256, a `webid` claim.
Nothing here is secret: public keys and bearer tokens for a loopback-only realm whose secrets live in
`identities.env`. Tests that verify these tokens must fix their clock (`iat`/`exp` are the capture instant).
