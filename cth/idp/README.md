# Authenticated CTH runs — the reproduction kit

Everything behind `cth/BASELINE.md`'s 2026-08-28 rows, so any checkout can reproduce
them. The topology is the interop Phase 4 exists for: **alice's identity at CSS,
alice's storage at Cistern** — an external IdP the pod trusts because the seeded
WebID document says so.

**Platform scope, stated honestly:** verified on Docker Desktop (macOS), where the
baseline rows were produced. On plain Linux Docker the loopback-published ports
(`127.0.0.1:3737` / `:3939`) are unreachable from `host-gateway`, so containers
cannot call back to the host by name; the CI shape (which can bind the runner's
interfaces deliberately) is tracked with the dual-lane CI work. The `extra_hosts`
entries are already in place for it.

## The three steps

```bash
# 1. Cistern (configured by cth-application.yaml) + CSS 7.2.0 as the test IdP
docker compose -f docker-compose.yml -f cth/idp/compose-cth-idp.yml up --build -d

# 2. Wait for CSS, provision alice+bob, write cth/idp/users.json (untracked;
#    written only on success, so a failed run never destroys good credentials)
./cth/idp/provision.sh

# 3. The run. Official image by default — the only one that may move
#    BASELINE.md's official row. CTH_IMAGE selects a build of upstream PR
#    solid-contrib/conformance-test-harness#789 for the PROVISIONAL numbers.
./cth/idp/run-cth-authed.sh
CTH_IMAGE=cth-patched:ath ./cth/idp/run-cth-authed.sh
```

## What each file is

| File | Role |
|---|---|
| `compose-cth-idp.yml` | Compose override: mounts the run config into Cistern, adds the CSS IdP (both with `extra_hosts`) |
| `cth-application.yaml` | The run's Cistern config: owner, `cistern.auth.solid.*` (enabled, trusted-origins), `cistern.pods.seed` for alice/bob |
| `css-config.json` | CSS `config/default.json` with the WebID ownership check swapped to `unsafe-no-check` — a test IdP only. Version pinned here and in `cistern-auth/src/test/resources/fixtures/css/README.md`; bump both together |
| `provision.sh` | Readiness wait, then runs `provision-css.mjs` inside the CSS image itself (node is on its PATH, `--add-host` set), temp-file-then-move to `users.json` |
| `provision-css.mjs` | Account + password + external-WebID link + client credentials per user, each response status-checked; verifies the seeded profile serves an `oidcIssuer` before linking |
| `run-cth-authed.sh` | Preflights (port pin, credentials complete, config actually loaded), exports the credential env, then delegates to `../run-cth.sh` — one harness invocation in the repo |

## The traps, so nobody rediscovers them

- **Issuer strings are byte-identical; origin entries are canonicalised — know
  which is which.** The issuer comparison (`WebIdIssuerVerifier.namesIssuer`) is
  near-verbatim, so the seeded `solid:oidcIssuer` and the harness's IdP carry the
  exact string CSS's discovery document states, trailing slash included:
  `http://host.docker.internal:3939/`. The `trusted-origins` entries are reduced to
  scheme://host:port (`WebIdFetchPolicy.canonicalOrigin`), so they are written
  slash-free. Keep each in its lane rather than trusting either normalisation to
  save a mismatch.
- **The kit uses its own storage volume** (`cistern-cth-data`) — pod seeding is
  never-overwrite, so an old volume keeps an old topology no matter what the config
  says. After any change to `cth-application.yaml`:
  `docker compose -f docker-compose.yml -f cth/idp/compose-cth-idp.yml down -v`
  before bringing the stack back up.
- **The kit pins `CISTERN_HOST_PORT=3737`.** The origin is baked into the seeded
  config and every provisioned credential; `run-cth-authed.sh` refuses other values
  rather than letting the drift surface as fake auth failures.
- **`trusted-origins` needs Cistern's own origin too**, not just CSS's: T4.3
  dereferences the Cistern-hosted WebID through the same fetch policy, and
  `host.docker.internal` resolves to a private address.
- **`users.json` goes stale if `css-idp` restarts** — CSS stores accounts in
  memory, deliberately (an IdP that restarted empty while credentials persisted
  would authenticate nobody, invisibly). Re-run `./cth/idp/provision.sh` after any
  CSS restart.
- **If a run dies at REGISTER CLIENTS**, grep the server log for
  `WEBID_ADDRESS_REFUSED` / `WEBID_SCHEME_REFUSED` before forming any auth theory —
  after checking the two guards above.
- **The official harness currently halts in PREPARE SERVER** on its missing `ath`
  claim (RFC 9449 §4.3) — upstream's, tracked at
  [#789](https://github.com/solid-contrib/conformance-test-harness/pull/789), and
  why the official row and the provisional table in `../BASELINE.md` differ.
