# CTH baseline

Best-known conformance pass-count. CI (T3.2) fails any PR that drops below this;
raise it in the same PR that improves it. Honest numbers only.

| Date | Suite | Passed | Failed | Untested | Notes |
|------|-------|--------|--------|----------|-------|
| 2026-07-19 | Solid Protocol + WAC (specification-tests 0.0.19, 41 test cases discovered) | 0 | 0 | 41 | Harness wired end-to-end (T0.4). Test phase unreachable: the CTH mandates authenticated alice/bob clients before running any feature (`registerClients` → `prepareServer`, no skip-auth mode exists), and Cistern has no HTTP layer or Solid-OIDC yet — the run stops at REGISTER CLIENTS with an honest 404 on the alice/bob WebID documents, so no results report is emitted. `--coverage` mode runs to completion (exit 0) and writes `reports/coverage.html`. First real pass/fail numbers arrive once resource routes exist and T5.4 provisions test accounts. |

Re-verified 2026-07-20 under T3.1, against the full HTTP layer (Phase 2 complete) with
`CISTERN_BASE_URL=http://host.docker.internal:3000`: unchanged at 0 / 0 / 41. The blocker
is unchanged and is not about the HTTP layer — the harness still stops in REGISTER CLIENTS
on a 404 for a WebID document, before executing any feature. (Whichever of alice/bob it
checks first, so the name in the error varies between runs; it is not significant.) CI now
runs this on every PR (report-only) and publishes the same numbers to the job summary,
confirmed on run 29757151369: `host.docker.internal` resolves from the harness container
on the Linux runner via `--add-host=host-gateway`, so the 404 is a real answer from
Cistern rather than a connection failure.

Re-verified 2026-08-27 against main `4121b27` (Phase 4 complete: #140 DPoP, #141 WebID
verification, #143 security wiring, #144 review fixes), harness 1.2.2, suite 0.0.19,
server built and run via `docker compose` with `CISTERN_BASE_URL=http://host.docker.internal:3737`
as `run-cth.sh` instructs: **unchanged at 0 / 0 / 41 — no regression.** The run still
stops in REGISTER CLIENTS on a real 404 for an alice/bob WebID document (exit code 1,
no results report emitted). The wall is no longer identity validation — Phase 4 can
verify a Solid-OIDC token end to end — it is that those documents and pods do not
exist: T5.4 provisions only the single-owner case, and the harness needs alice/bob
accounts plus an IdP to authenticate them against. A second gate sits behind that one:
`WebIdFetchPolicy` refuses http/non-public origins by design, so a local test IdP is
unusable until the trusted-origins allow-list (PR #148) merges. Both are named so the
next number move is deliberate, not accidental: provision alice/bob (T5.4 follow-on),
point them at a real IdP (CSS 7.2.0 works — the T4.1/T4.2 fixture capture automates
account + pod + client credentials in `cistern-auth/.../fixtures/css/capture.mjs`),
and list that IdP's origin in `cistern.auth.solid.webid.trusted-origins`.

Baseline run details (2026-07-19, `solidproject/conformance-test-harness:latest`,
digest `sha256:4a38077d…`, test suite version 0.0.19 2024-03-21):

- `./run-cth.sh` — discovery succeeds ("TEST CASES FOUND: 41", 26 protocol + 15 WAC),
  subject `https://github.com/enrichmeai/cistern` loaded, skip tags `[]`, then
  `TestHarnessInitializationException: Failed to read WebID Document for
  [http://host.docker.internal:3000/…/profile/card#me] … Error response=404`.
  Exit code 1. 0 passed / 0 failed — all 41 untested.
- `./run-cth.sh --coverage` — full run, exit code 0, `reports/coverage.html` generated.
