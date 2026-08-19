# Cistern integration kit

The first thing an integrator runs. One directory, three commands, and the whole loop from
`docs/INTEGRATION.md` is running on your machine: a **Cistern** pod with enforcement on, a
**real OIDC issuer** (Keycloak, realm pre-seeded with two humans and two application
principals), a **provisioned matter and tax year**, an **owner-written grant**, and a
**sample application** that reads under the grant and is refused outside it.

```bash
cd integration-kit
docker compose up -d --build     # Cistern (127.0.0.1:3737) + Keycloak (127.0.0.1:8080)
./seed.sh                        # owner provisions the layout and writes the grant
./sample-app/run.sh              # the app: refused, granted, allowed inside, refused outside, revoked
```

What you should see from the sample app (mode `today`, the default; every status is the one
`docs/INTEGRATION.md` §8 promises):

```
  1  owner      DELETE  /matters/2026-114/.acl         -> 204  ok  reset: the story starts with no grant on the matter …
  2  legal-app  GET     /tax/FY2025-26/return          -> 401  ok  no credential and no rule grants the public: refused; 401 = "authenticate and it may work"
  3  legal-app  GET     /matters/2026-114/index        -> 401  ok  no credential and no rule grants the public: refused …
  4  owner      PUT     /matters/2026-114/.acl         -> 201  ok  the owner writes the rule, a file in the pod: Read on the matter (accessTo + default), owner re-stated
  5  legal-app  GET     /matters/2026-114/             -> 200  ok  inside the grant: the container listing (ldp:contains)   [WAC-Allow: user="read",public="read"; text/turtle, 391 bytes]
  6  legal-app  GET     /matters/2026-114/index        -> 200  ok  inside the grant: WAC-Allow says exactly what the app holds   [WAC-Allow: user="read",public="read"; …]
  7  legal-app  GET     /matters/2026-114/contract.pdf -> 200  ok  inside the grant: a non-RDF document, served verbatim   [WAC-Allow: user="read",public="read"; application/pdf, 682 bytes]
  8  legal-app  DELETE  /matters/2026-114/index        -> 401  ok  Read is not Write
  9  legal-app  GET     /tax/FY2025-26/return          -> 401  ok  outside the grant, no credential: refused
 10  owner      DELETE  /matters/2026-114/.acl         -> 204  ok  the owner revokes: delete the file. No restart, no token reissued, no cache to purge
 11  legal-app  GET     /matters/2026-114/index        -> 401  ok  the very next request
 12  owner      PUT     /matters/2026-114/.acl         -> 201  ok  restore the grant seed.sh wrote, so the pod is as you left it

all 12 steps matched the design (docs/INTEGRATION.md §8).
```

The climax is a refusal, on purpose: an application tried something and was stopped by a rule
the owner wrote, held in the owner's own storage, enforced at the store, revoked in one request.

## Prerequisites

- Docker with Compose v2 (`docker compose`), able to build the repo image (Java 25 Maven
  build inside Docker; the first build takes a few minutes, later ones are cached).
- `bash` (3.2 is enough — macOS's is fine) and `curl`.
- **Node 20+** with `npm` for the sample app (TypeScript compiler is its only, dev-time,
  dependency; installed on first run). `python3` only for `capture-fixtures.sh` and
  `keycloak/build-realm.sh`.

## What is where

| Path | What it is |
|---|---|
| `docker-compose.yml` | `cistern` (built from the repo `Dockerfile`, owner from `cistern.env`) + `keycloak` (`quay.io/keycloak/keycloak:latest`, dev mode, imports the realm). Named volumes, healthchecks, ports on `127.0.0.1` only. The **after-#88 switch** is a commented block here. |
| `cistern.env` | `CISTERN_OWNER_WEBID` + `CISTERN_OWNER_TOKEN` — the container's environment; setting the owner turns enforcement on and seeds `/.acl` |
| `identities.env` | every identity in the kit: owner, `alice`, `bob`, `valuedocs-legal`, `valuedocs-tax`, their WebIDs, secrets, the audience and issuer |
| `seed.sh` | the owner's actions, as plain HTTP: `layout` (PUT `/matters/2026-114/index`, `contract.pdf`, `/tax/FY2025-26/return`), `grant` (PUT the `.acl`s for the active mode), `revoke`, or all |
| `seed/` | the three documents seed.sh uploads — two Turtle files and a 682-byte real PDF |
| `grants/today/` | the grant that works **today**: `foaf:Agent` Read on `/matters/2026-114/` (the server names no per-app principal yet) |
| `grants/after-88/` | the grants for **after #88**: `acl:agent <https://valuedocs.example/apps/legal#id>` on the matter, `<…/tax#id>` on the tax year |
| `sample-app/` | TypeScript, Node 20+, global `fetch`, no framework, no SDK. `run.sh` compiles and runs it. Tells the story above; exits non-zero on any status that is not the designed one |
| `keycloak/realm-cistern.json` | the realm — a genuine `kc.sh export` (keys and secrets included), imported on first boot |
| `keycloak/build-realm.sh` + `.py` | how that export was produced (throwaway Keycloak → admin API from `identities.env` → `kc.sh export`); run it to regenerate |
| `capture-fixtures.sh` → `fixtures/` | discovery document, JWKS, one valid token per service principal, one human token, one **expired** token, all from the running realm — for #88's tests (real-first) |
| `lib/kit.sh` | shared by every script: env loading, pod paths, endpoints, readiness, token helper |
| `lib/jwt-decode.py` | prints a token's header and claims (no verification) |

## The identities

| Who | Kind | Authenticates with | WebID (the `webid` claim / the `acl:agent`) |
|---|---|---|---|
| the firm (pod owner) | owner | `CISTERN_OWNER_TOKEN` (today's only server-side principal) | `https://acme-law.example/profile#firm` |
| `alice` | human, lawyer | password `alice-password` through `valuedocs-legal` (Keycloak) | `https://acme-law.example/people/alice#me` |
| `bob` | human, client | password `bob-password` | `https://acme-law.example/people/bob#me` |
| `valuedocs-legal` | service principal (confidential client, service account) | client credentials | `https://valuedocs.example/apps/legal#id` |
| `valuedocs-tax` | service principal | client credentials | `https://valuedocs.example/apps/tax#id` |
| `fixture-short-lived` | service principal, 1-second tokens | client credentials | only to mint `fixtures/token-expired.jwt` |

Every Keycloak access token carries `aud: cistern`, `iss: http://keycloak:8080/realms/cistern`,
and a `webid` claim (one user-attribute mapper on the `cistern` client scope serves humans and
service accounts alike). Keycloak admin console: <http://127.0.0.1:8080/> — `admin` / `admin`.

## Today, and after #88 — the switch is configuration

**Today** Cistern has one resolver, the owner token. The application therefore holds *no*
credential; the grant is class-based (`foaf:Agent`); and refusal outside the grant is **401**
("authenticate and it may work"). This is the default: `KIT_MODE=today`.

**After #88** (T4.0, the OIDC/JWT resolver) the application authenticates to Keycloak as
itself, Cistern maps the token's `webid` claim to an `Agent`, the grant names that WebID, and
refusal outside the grant becomes **403** ("do not retry"). Nothing in the kit is rewritten:

1. In `docker-compose.yml`, uncomment the three `CISTERN_AUTH_OIDC_*` lines (they are
   `cistern.auth.oidc.issuer` / `.audiences` / `.webid-claim` from `docs/INTEGRATION.md` §6.1
   in environment form) and `docker compose up -d`.
2. `KIT_MODE=after-88 ./seed.sh` — writes `grants/after-88/*` instead.
3. `KIT_MODE=after-88 ./sample-app/run.sh` — obtains a token for `valuedocs-legal` (then
   `valuedocs-tax`) by client credentials and expects 200 inside, **403** outside.

Run step 3 today and it prints the same story with `!!` on every line where the server still
answers 401 — an honest picture of what #88 changes, not a fake pass.

The issuer string is fixed by `KC_HOSTNAME=http://keycloak:8080` so it is the same whoever asks:
the sample app on the host (which reaches Keycloak at `127.0.0.1:8080`), Cistern inside the
compose network (where `keycloak` resolves and the JWKS is fetched), and the fixtures.

## Loopback only (ADR 0001)

Both host ports bind to `127.0.0.1`; nothing is reachable from another machine:

```
$ docker compose ps
NAME                     …  PORTS
cistern-kit-cistern-1    …  127.0.0.1:3737->3000/tcp
cistern-kit-keycloak-1   …  8443/tcp, 9000/tcp, 127.0.0.1:8080->8080/tcp
```

Ports taken? `CISTERN_HOST_PORT=3838 KEYCLOAK_HOST_PORT=8180 docker compose up -d` — export
the same two variables when running the scripts. Every script and the pod's own base URL say
**`127.0.0.1`, not `localhost`**: on a Mac `localhost` resolves to `::1` first, Docker binds
IPv4 only, and any other process on the port (a stray `java -jar cistern.jar`) would answer
instead — with a 401 that looks exactly like this server misbehaving.

The owner token, the Keycloak admin password and the client secrets are demo values committed
on purpose. They protect nothing off this machine, and moving the kit onto a network without
#94 (TLS, real secrets) is exactly what ADR 0001 forbids.

## Fixtures for #88

```bash
./capture-fixtures.sh      # → fixtures/: openid-configuration.json, jwks.json, token-*.jwt (+ decoded *.claims.json), README.md
```

The realm export carries its signing keys, so `fixtures/jwks.json` verifies tokens from every
fresh import; tokens are five-minute Keycloak defaults (`token-expired.jwt` was minted with a
one-second lifespan and is already past `exp`). Tests using them fix their clock.

## Reset, rebuild, troubleshoot

- **Start over** (pod contents, realm state): `docker compose down -v && docker compose up -d && ./seed.sh`
- **Back to "no grant"** without losing documents: `./seed.sh revoke`; back: `./seed.sh grant`
- **Rebuilt Cistern** (code change): `docker compose up -d --build`
- **Changing a port** after first boot needs `down -v`: identifiers already stored carry the
  old origin (`cistern.base-url` mints every URI the pod hands out).
- **Regenerate the realm** after editing `identities.env`: `./keycloak/build-realm.sh`, then `down -v`.
- **`seed.sh` waits forever on Cistern**: `docker compose logs cistern` — the owner must be
  seeded (`Seeded root ACL … granting full access to owner …`); if instead you see
  `NO_OWNER_CONFIGURED`, `cistern.env` was not read.
- **Keycloak unhealthy**: `docker compose logs keycloak` — the healthcheck asks for the realm's
  discovery document, so "healthy" means the import succeeded.

Then read `docs/INTEGRATION.md`: this kit is its steps 0–6, executed.
