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
| `identities.env` | every identity in the kit: owner, `alice`, `bob`, `valuedocs-legal`, `valuedocs-tax`, their WebIDs, secrets, the audience and issuer; the Google Workspace values (blank by default) and the stand-in provider with `carol` (T7.16) |
| `seed.sh` | the owner's actions, as plain HTTP: `layout` (PUT `/matters/2026-114/index`, `contract.pdf`, `/tax/FY2025-26/return`), `grant` (PUT the `.acl`s for the active mode), `revoke`, or all |
| `seed/` | the three documents seed.sh uploads — two Turtle files and a 682-byte real PDF |
| `grants/today/` | the grant that works **today**: `foaf:Agent` Read on `/matters/2026-114/` (the server names no per-app principal yet) |
| `grants/after-88/` | the grants for **after #88**: `acl:agent <https://valuedocs.example/apps/legal#id>` on the matter, `<…/tax#id>` on the tax year |
| `sample-app/` | TypeScript, Node 20+, global `fetch`, no framework, no SDK. `run.sh` compiles and runs it. Tells the story above; exits non-zero on any status that is not the designed one |
| `keycloak/realm-cistern.json` | the realm — a genuine `kc.sh export` (keys and secrets included), imported on first boot |
| `keycloak/realm-external-idp.json` | the second realm that stands in for an external identity provider (`carol`, and the `cistern` realm as its client), imported alongside |
| `keycloak/build-realm.sh` + `.py` | how those exports were produced (throwaway Keycloak → admin API from `identities.env` → `kc.sh export`); run it to regenerate — it keeps the previous export's signing keys, so `fixtures/` stays valid |
| `capture-fixtures.sh` → `fixtures/` | discovery document, JWKS, one valid token per service principal, one human token, one **expired** token, one **brokered** token (carol, who exists only behind the stand-in provider), all from the running realm — for #88's tests (real-first) |
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
| `carol` | human, exists **only** in realm `external-idp` (the stand-in for Google) | password `carol-password` **there**; the `cistern` realm brokers to it | none in the token — Cistern builds it: `http://keycloak:8080/realms/cistern/users/<sub>#me` (see [Sign in with Google](#sign-in-with-google-t716)) |

Every Keycloak access token carries `aud: cistern` and `iss: http://keycloak:8080/realms/cistern`;
every seeded identity's token also carries a `webid` claim (one user-attribute mapper on the
`cistern` client scope serves humans and service accounts alike). A brokered person's token has
no such claim — that is the case the template below exists for. Keycloak admin console:
<http://127.0.0.1:8080/> — `admin` / `admin`.

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

## Sign in with Google (T7.16)

A small company's staff sign in with the account they already have — a Google Workspace
account — not with a password in a realm someone seeded. Keycloak does the brokering with its
built-in `google` provider; **Cistern does not change**: it validates the Keycloak-issued token
exactly as before (`cistern.auth.oidc.*`) and takes the WebID from the token's claims. The
Solid-OIDC specification places the WebID in a `webid` claim; an issuer that has users but no
WebIDs leaves the pod operator to say how a user becomes one, and `webid-template` is Cistern's
answer for that case (`docs/INTEGRATION.md` §6.1).

### What to create in Google Cloud

1. **OAuth consent screen** (APIs & Services → OAuth consent screen): user type **Internal** —
   available to a Workspace organisation, and the first fence: only that organisation's
   accounts can sign in at all. App name and support email; no scopes beyond the defaults
   (`openid`, `email`, `profile` are what Keycloak asks for).
2. **Credentials → Create credentials → OAuth client ID**, application type **Web application**.
   One **authorised redirect URI**: `<keycloak frontend>/realms/cistern/broker/google/endpoint`.
   The frontend is the URL the person's browser reaches Keycloak at. Google accepts an `https`
   URL on a real domain, or a loopback `http` one (`http://127.0.0.1:<port>` /
   `http://localhost:<port>`) — Google's [redirect URI validation rules](https://developers.google.com/identity/protocols/oauth2/web-server#uri-validation):
   HTTPS except for localhost URIs including localhost IP addresses, no raw IP hosts except
   those, host TLDs on the public suffix list. The kit's fixed frontend `http://keycloak:8080`
   fails on two of the three, so the capture runbook below runs the kit with a loopback
   frontend for the session.
3. Copy the client ID and secret.

### The three values

In `identities.env`, blank by default:

| Value | What it is |
|---|---|
| `GOOGLE_CLIENT_ID` | the OAuth client's ID |
| `GOOGLE_CLIENT_SECRET` | its secret — never committed; a regenerated export made with it set is not committed either |
| `GOOGLE_HOSTED_DOMAIN` | the Workspace domain (`acme-law.example`). Sent to Google as `hd` and checked on the identity Google returns, so only that domain's accounts get in. Keycloak accepts a comma-separated list, and `*` for "any Workspace domain" |

All three blank: the provider is not created and the kit is exactly as it was. All three set:
`./keycloak/build-realm.sh` creates the provider — trust email (the Workspace has verified it;
no verification mail), the first-broker-login flow below, hosted domain as given. A partial
set is refused by `build-realm.py` rather than turned into a provider missing its secret or
open to every Google account.

### First login

The person clicks *Sign in with Google* on the Keycloak page (or the application sends them
straight there with `kc_idp_hint=google`), signs in at Google, and comes back. Keycloak's
first-broker-login flow then creates the local user from Google's claims — name, email,
username — and **asks nothing**: the kit uses a copy of Keycloak's built-in flow with the
*Review Profile* step disabled (`first broker login without review`, made by
`build-realm.py`; the built-in flow is left as it ships). A Workspace account always carries
a name and an email, which is what the user profile requires. Later logins find the user by
the federated link and go straight through. If a local account already has the same email
address — alice's `alice@acme-law.example`, say, for a Workspace on that domain — the flow's
*Handle Existing Account* branch, kept from the built-in, asks the person to confirm the link
before it proceeds; a company whose staff exist only in Google never sees it.

### The WebID of a brokered person — the decision

**Cistern's `webid-template` over Keycloak's stable `sub`.** With the brokered block in
`docker-compose.yml` uncommented:

```
cistern.auth.oidc.webid-template = {iss}/users/{sub}#me
```

a token for a brokered person resolves to

```
http://keycloak:8080/realms/cistern/users/<sub>#me
```

where `<sub>` is the id Keycloak assigns the local user it creates at that first login —
carol's, in the committed capture, is `7818c658-34dc-4140-81f4-61200e5ea454`
(`fixtures/token-carol-via-broker.claims.json`). It is stable for as long as that user exists:
later logins find the same user through the federated identity link, and a changed name, email
or username leaves it alone. Its lifetime is the realm database's — `docker compose down -v`
starts a realm in which carol has never signed in, and her next first login mints a new id —
which is why the proof below reads the WebID from the fresh capture rather than from this page,
and why a real deployment backs up the realm. It is not Google's own `sub`, which lives on the
federated link inside Keycloak; the identifier is minted by the realm the firm runs. An
identifier that outlives the realm — Google's `sub` imported by a provider-level *attribute
importer* mapper (configured once per provider, never per person) and emitted as a claim for
the template — is the same shape one step further, and is not built here.

*Why:* there is no per-person step. The alternative — a `webid` attribute on each user, which
is what alice and bob have and what the `webid` claim mapper serves — is an administrator
setting a value for every member of staff, which is the thing a small company cannot do. The
template asks the issuer for nothing it does not already produce.

*What it costs:* `webid-claim` and `webid-template` are one or the other. With the template
set, every token from this issuer maps through it — alice's, bob's and the applications'
included — and the `webid` claims the seeded identities carry are not consulted. A company
where everyone is brokered runs the template alone; the `after-88` grants in this kit, which
name the applications by their `webid` claim values, belong to the claim posture. The WebID
does not dereference, as none of the kit's WebIDs do: for Web Access Control it is an
identifier, and `cistern grant` takes it as one.

### Proving it without Google

Real-first (ground rule 6) without anyone's Google account: a **second realm in the same
Keycloak**, `external-idp`, stands in for the external provider. It holds one person, `carol`,
and one client, `cistern-realm` — the `cistern` realm itself, whose only redirect URI is the
`cistern` realm's broker endpoint. The `cistern` realm has an `oidc` identity provider pointing
at that realm's endpoints, the same shape the Google provider has built in. Everything is in
`realm-cistern.json` and `realm-external-idp.json`; nothing to switch on.

```bash
# 1. Cistern with the template: uncomment the three lines of the brokered block in
#    docker-compose.yml (they replace the after-88 lines), then
docker compose up -d
# 2. carol's token: the browser flow, driven by curl — Keycloak's direct grant does not cross a
#    broker, so the capture is the authorization-code flow itself, hop by hop
./capture-fixtures.sh              # → fixtures/token-carol-via-broker.jwt (+ .claims.json)
# 3. her WebID, from the claims — then grant it something and try the rest
CAROL="http://keycloak:8080/realms/cistern/users/$(python3 -c "import json;print(json.load(open('fixtures/token-carol-via-broker.claims.json'))['claims']['sub'])")#me"
KIT_MODE=after-88 ./seed.sh grant  # no grant names carol yet
../bin/cistern grant "$CAROL" --read /matters/2026-114/ --base http://127.0.0.1:3737 --token kit-owner-token-3f9c1e0d7a4b2c6e
TOKEN="$(cat fixtures/token-carol-via-broker.jwt)"
curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $TOKEN" http://127.0.0.1:3737/matters/2026-114/index   # 200
curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $TOKEN" http://127.0.0.1:3737/tax/FY2025-26/return     # 403
```

`capture-fixtures.sh` walks the flow the way a browser would: authorization request to the
`cistern` realm with `kc_idp_hint=external-idp`, the stand-in's login form, the redirect back
to `/realms/cistern/broker/external-idp/endpoint`, the first-broker-login flow, the redirect to
the application's callback (`http://127.0.0.1/valuedocs-legal/callback`, where nothing listens
— the code is read from the redirect, as the application would), and the code exchange at the
`cistern` realm's token endpoint. Every URL in that walk is on the issuer's origin,
`keycloak:8080`, which this host cannot resolve; the script maps it to the published port with
`curl --connect-to`, leaving URLs, cookies and redirect checks exactly as a browser sees them.
Tokens are five-minute Keycloak defaults: capture, then curl, within that.

**Microsoft 365** is the same shape — Keycloak's `microsoft` provider, one client in Entra ID,
the same first-login flow and the same WebID — and is not built here.

### Capturing the Google-brokered token — runbook

For whoever holds the Google Cloud project. The deliverable is
`fixtures/token-<name>-via-google.claims.json`; the secret and the regenerated export are not
committed.

1. Google Cloud, as above. The redirect URI is
   `http://127.0.0.1:8080/realms/cistern/broker/google/endpoint` (with `KEYCLOAK_HOST_PORT`
   overridden, use that port here and in step 3; if Google's console refuses the IP form, use
   `http://localhost:8080/…` and the same host in step 3).
2. `identities.env`: set the three values. `./keycloak/build-realm.sh` — the log shows
   `identity provider google: created, hosted domain …`.
3. The kit with a loopback frontend, so that the redirect URI Keycloak sends Google is the one
   registered: `KEYCLOAK_HOSTNAME=http://127.0.0.1:8080 docker compose down -v` then
   `KEYCLOAK_HOSTNAME=http://127.0.0.1:8080 docker compose up -d`. For this session `iss` is
   `http://127.0.0.1:8080/realms/cistern`; the stand-in flow, which expects the fixed
   frontend, is not run in this session.
4. In a browser, as a Workspace user of the hosted domain:
   `http://127.0.0.1:8080/realms/cistern/protocol/openid-connect/auth?client_id=valuedocs-legal&redirect_uri=http://127.0.0.1/valuedocs-legal/callback&response_type=code&scope=openid&kc_idp_hint=google`
   — sign in at Google. The browser ends on `http://127.0.0.1/valuedocs-legal/callback?…&code=…`
   (an error page: nothing listens there). Copy `code=` from the address bar; it is single-use
   and short-lived, so do step 5 straight away.
5. ```bash
   curl -s -X POST http://127.0.0.1:8080/realms/cistern/protocol/openid-connect/token \
     -d grant_type=authorization_code -d client_id=valuedocs-legal \
     -d client_secret=valuedocs-legal-secret-9d2c4f6a8b1e -d code=<code> \
     --data-urlencode redirect_uri=http://127.0.0.1/valuedocs-legal/callback \
     | python3 -c "import json,sys; print(json.load(sys.stdin)['access_token'])" > /tmp/google.jwt
   python3 lib/jwt-decode.py /tmp/google.jwt > fixtures/token-<name>-via-google.claims.json
   ```
   Expect `aud` containing `cistern`, no `webid` claim, and a `sub` — the WebID's stable part.
6. Put back what the session changed: `git checkout identities.env keycloak/realm-cistern.json`
   (`git status` shows only the new claims file), `docker compose down -v`, and the kit is as
   it was. Commit the claims file.

## Reset, rebuild, troubleshoot

- **Start over** (pod contents, realm state): `docker compose down -v && docker compose up -d && ./seed.sh`
- **Back to "no grant"** without losing documents: `./seed.sh revoke`; back: `./seed.sh grant`
- **Rebuilt Cistern** (code change): `docker compose up -d --build`
- **Changing a port** after first boot needs `down -v`: identifiers already stored carry the
  old origin (`cistern.base-url` mints every URI the pod hands out).
- **Regenerate the realms** after editing `identities.env`: `./keycloak/build-realm.sh` (both exports; the signing keys are kept, so `fixtures/` stays valid), then `down -v`. Host port 18080 taken? `KEYCLOAK_BUILD_HOST_PORT=18090 ./keycloak/build-realm.sh`.
- **`seed.sh` waits forever on Cistern**: `docker compose logs cistern` — the owner must be
  seeded (`Seeded root ACL … granting full access to owner …`); if instead you see
  `NO_OWNER_CONFIGURED`, `cistern.env` was not read.
- **Keycloak unhealthy**: `docker compose logs keycloak` — the healthcheck asks for the realm's
  discovery document, so "healthy" means the import succeeded.
- **Keycloak refuses to start with `The url [authorization_url] requires secure connections`**:
  the stand-in provider's endpoints are plain `http` on `keycloak:8080`, and Keycloak accepts
  a plain-http provider URL — at creation and at import — only where that host resolves to a
  local address. It does inside the compose network; it does not in a bare `docker run`,
  which is why `build-realm.sh` gives its throwaway `--add-host keycloak:127.0.0.1`. Importing
  `realm-cistern.json` anywhere else needs the same, or `https` endpoints.

Then read `docs/INTEGRATION.md`: this kit is its steps 0–6, executed.
