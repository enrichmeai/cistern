# CLAUDE.md — Cistern

Open, self-hostable **Solid pod server** for the AI era: JVM-native, Spring Boot 4 / WebFlux,
MCP-fronted, conformance-first. Apache 2.0. © Good Shepherd Software Consultancy Ltd
(Company No. 09702990), trading as **EnrichMeAI**.

**Positioning in one line:** Inrupt's Charlie is the closed product; Cistern is the open
infrastructure — any agent (Claude, ChatGPT, in-house) gets consented access to user-owned
data over MCP, with Solid WAC enforcing the consent.

## Ground rules (non-negotiable)

1. **The conformance test harness (CTH) is the real API.** Specs are implemented against
   the Solid Protocol text + CTH assertions, never against guesses or blog posts. Before
   starting a ticket, read the spec sections it cites. If the CTH and your reading of the
   spec disagree, stop and raise it to the architect — do not code around the harness.
2. **Correct by construction.** Architect review confirms; it does not rescue. A ticket is
   not "done because tests pass" — it is done when the DoD checklist in the ticket is
   demonstrably met and you have verified it yourself (run the server, curl it, run the
   relevant CTH subset).
3. **Fully reactive.** No `.block()`, no `.toFuture().get()`, no blocking I/O outside
   `boundedElastic` in production code. `StepVerifier` for service/core tests,
   `WebTestClient` for HTTP tests.
4. **One error mapper.** Domain code signals `CisternException` subtypes through the
   reactive chain. Only the global error handler (cistern-webflux) speaks HTTP status
   codes. No `.onErrorResume` for error mapping in handlers.
5. **The storage SPI is the seam.** Backends implement `ResourceStore` and MUST extend the
   shared `ResourceStoreContractTest` kit. Backends never parse RDF; the core RDF layer
   never touches storage details. No Spring dependencies in `cistern-core`.
6. **Real-first testing.** Any fixture (JWKS, DPoP proofs, MCP frames) is captured from a
   real implementation, never invented. A mock built from a guess will happily confirm
   the guess.
7. **No stringly-typed code, no inline literals — build it properly the first time.**
   Half measures are rejected at review, not tidied up later.
   - **Closed sets are `enum`s**, never bare strings or `int`s: media types, resource
     kinds, access modes, patch operations, problem types, emitted header names.
   - **Domain concepts are records/value classes**, not `String`/`Map`/tuple pairs. If a
     value has rules (a slug, an etag, a WebID), it gets a type that enforces them.
   - **RDF vocabulary IRIs live in per-namespace constant classes** (`Ldp`, `Solid`,
     `Acl`, …) — never an inline IRI string.
   - **Message text is never inlined at a throw/log site.** Each module owns a message
     catalogue: an `enum` whose constants carry a `String.format` template plus a
     `format(Object...)` method — e.g. `CoreMessage.CONTAINMENT_SERVER_MANAGED.format(uri)`.
     Exceptions and logs reference catalogue entries; no `"text " + var + " text"` at the
     call site. Naming: `CoreMessage`, `WebfluxMessage`, `StorageFileMessage`, one per
     module (plain Java — `cistern-core` still takes no Spring dependency).
   - **Magic numbers and repeated literals become named constants.**

## Verification (learned the hard way, 2026-08-22 and 2026-08-28)

Every real defect found in a day of intensive work came from **checking a claim that had
already been asserted confidently** — several of them our own. None came from a passing test
suite. So:

1. **Verify your own assertions before they reach the user or a public page.** A number you
   grepped, a config key you remembered, an env var you inferred: check it against the source
   of truth. On one day this caught a wrong vulnerability count (9 vs the report's own 138), a
   config key that silently binds nothing, and a safety warning deleted during a rewrite.
2. **Correct behind a filter is not correct.** Two separate bugs shipped because a handler was
   right and `AuthorizationFilter` in front of it changed the answer — the missing
   `Link rel="acl"`, and `OPTIONS *` returning 400 before its own handler could run. Both had
   green handler-level tests. **Anything the filter guards needs a `WebTestClient` test through
   the chain**, not a call to the handler.
3. **Never publish a sentence that promises a future event.** "when Solid-OIDC lands", "no
   release yet" — both became false and neither announced it. Publish the state and its cause;
   a claim about the present stays true or is visibly wrong.
4. **A green build proves less than it looks.** A stray `git add -A` once reverted four merged
   PRs and still compiled and passed, because the tests that would have caught it were among
   the reverted files.
5. **Before recommending we build something, check whether it already exists or already
   works.** Twice in one session the answer was yes: a client SDK was nearly proposed while
   Inrupt's `@inrupt/solid-client` already drove Cistern 8/8 unmodified, and a worked
   integration example was nearly proposed while `integration-kit/` already existed,
   unlinked. Standing the real thing up settled both in under an hour. See
   `probe-running-pod`.
6. **Ticket, phase and version references are facts — check them.** `docs/BACKLOG.md` is
   the source of truth. "Solid-OIDC (Phase 5)" reached the README before being caught; it
   is Phase 4. The same applies to version pins: the README said "Pre-0.1" the day after
   0.2.0 shipped, with six stale `0.1.0` pins in the quickstart.
7. **The release gate runs on every release, not just the first.** "A stranger can deploy
   AND use it from published artifacts alone, no stale text in the box" was written for
   v0.1.0 and not re-run for 0.2.0, which is how the stale pins survived. Re-run it, and
   check the docs still point at published assets rather than `target/` build output.

## Configuration binding (silent-failure traps)

Spring's relaxed binding **removes hyphens** rather than converting them:
`cistern.auth.service-principals[0].web-id` is `CISTERN_AUTH_SERVICEPRINCIPALS_0_WEBID`. The
intuitive `SERVICE_PRINCIPALS_0_WEB_ID` binds *nothing*, silently, and the server then starts
missing the thing you configured.

`@Value` does not reliably split a comma-separated value into a collection. Bind a `String`
and split it, or use `@ConfigurationProperties`. A two-origin allow-list once arrived as one
string and canonicalised to a single unusable entry.

**Any property that changes security posture gets a binding test** — a context that loads it
and asserts the value reached the object. Both traps above were found that way and neither
was visible in review.

## Working alongside other sessions

Several agents may work these repos at once. **Never commit from the shared checkout** — use
`git worktree add ../cistern-<task> -b <branch> main`, and leave `~/projects/cistern` parked
for whoever is running something. Drive review by PR number rather than by checkout state.

Announce what you are touching, and say which files — and **correct it when it changes**.
Telling a peer "this session is read-only" and then editing a tracked file leaves them
merging blind. Two sessions steering one working tree produced the four-PR silent revert
above.

**Another session may have already corrected what you are about to assert.** Re-read the
relevant memory and docs before writing a positioning claim into an issue, a PR or the
site. A framing corrected by one session was about to be re-published by another in the
same hour; the contradiction would have shipped. When memory and your draft disagree,
reconcile before publishing, and fix whichever is wrong.

## Skills

`.claude/skills/` carries six, written from the tasks that recurred most and that let errors
through when rushed:

- **`verify-published-claim`** — check an artifact, number, link or upstream fact before it
  reaches the site, a README, a PR body or the user. Includes the GHCR visibility check
  (a 401 does not mean private) and why counting report rows with `grep` gives wrong numbers.
- **`ship-site-change`** — edit, validate and publish enrichmeai.com. The site is a separate
  clone and merging to `main` publishes; covers the four validations and the copy rules.
- **`land-pr`** — branch from a worktree through to verified merge, including the shared-tree
  hazard and what `--delete-branch` does to a stacked PR.
- **`write-dispatch-brief`** — the brief that sends a dev agent from ticket to merged PR:
  decisions already taken, verified file references, DoD, and the out-of-scope list that
  stops scope creep.
- **`file-backlog-issue`** — turn a finding into a well-formed issue: duplicate check
  first, house labels and milestones, evidence with file references.
- **`probe-running-pod`** — answer "does X actually work against Cistern?" by standing the
  real server up and driving it with a real client. Carries the base-URL, `localhost`
  vs `127.0.0.1`, relaxed-binding and Node-version traps.

## Build & run

```bash
mvn -q verify                              # full build + unit tests
mvn -q -pl cistern-core -am test           # one module
docker compose up --build                  # server on :3737 (CISTERN_HOST_PORT overrides)
./cth/run-cth.sh                           # conformance harness against localhost:3737 (Docker)
```

Java 25 (SDKMAN), Maven 3.9. No Gradle. No Lombok — use records.

## Module map

| Module | Responsibility | Key rule |
|---|---|---|
| `cistern-core` | Resource model, storage SPI, RDF io (Jena), containment, N3 Patch engine | no Spring imports |
| `cistern-storage-file` | File-per-resource backend + metadata sidecars | passes contract kit |
| `cistern-webflux` | HTTP handlers, content negotiation, conditional requests, error mapping | thin; no business logic |
| `cistern-auth` | Solid-OIDC token validation, DPoP, WebID deref (Nimbus) | validate only — we are NOT an IdP in v1 |
| `cistern-wac` | Web Access Control engine + enforcement | Control ≠ Write; deny by default |
| `cistern-mcp` | MCP server exposing pod resources/tools to AI agents | every access goes through WAC |
| `cistern-spring-boot-starter` | Auto-configuration for embedding | |
| `cistern-app` | Runnable server; CTH target | config only, no logic |

## Workflow

- Work = tickets in `docs/BACKLOG.md` (`T<phase>.<n>`). One ticket per branch:
  `feature/t2.3-post-slug`. PR into `main`; the architect merges — dev agents never self-merge.
- Every commit is DCO signed-off (`git commit -s`). Conventional commit messages
  (`feat(webflux): ...`, `fix(wac): ...`).
- AI-assisted commits keep the `Co-Authored-By: Claude ...` trailer — decided 2026-07-17.
  Transparency about the agent-first build is part of the project's story; consistency
  matters (never a mix of tagged and untagged AI work).
- Post a DoD-checklist comment on the ticket/PR when returning work.
- Update `docs/BACKLOG.md` ticket status (`[ ]` → `[x]`) in the same PR that completes it.
- CTH conformance numbers only move forward. If your PR regresses a previously passing
  CTH assertion, that is a blocking failure regardless of what it adds.

## Writing about the specs

We implement other people's designs. Solid, WAC and Solid-OIDC are years of public agreement,
published precisely enough to build against and backed by a harness that tells us when we are
wrong — none of which we had to do. Cistern is a different implementation of that design, never
an improvement on it, and nothing we publish says or implies otherwise.

Where a spec genuinely leaves something open — and some do, which is normal for a spec that has
to be implementable by more than one party — say **what it defines** and **what we did about
it**, never what it "fails to" or "leaves undefined". Two examples of the right shape:

- *"Solid-OIDC specifies the authorization server, and leaves the resource server to follow, so
  the behaviour here was confirmed against a running implementation."* Not "the spec doesn't
  say".
- *"The specification defines the mapping algorithm over variables; matching blank nodes falls
  outside it, so this server declines rather than inventing a behaviour a patch could not rely
  on."* Not "leaves blank-node matching undefined".

This is accuracy as much as manners: an open point is usually a division of labour or a
deliberate non-goal, not an oversight. It also protects the thing that makes our claims
checkable — the conformance number only means something while we are plainly implementing the
same specification everyone else is.

Applies to code comments, error messages clients see, `docs/`, the README, PR text and the
site. Extensions of our own stay **additive and ignorable**: a pod carrying them still passes
the harness, and a plain Solid client still works against it.

## Spec sources (read these, not summaries)

- Solid Protocol: https://solidproject.org/TR/protocol
- Solid-OIDC: https://solidproject.org/TR/oidc  · DPoP: RFC 9449
- WAC: https://solidproject.org/TR/wac
- CTH + tests: https://github.com/solid-contrib/conformance-test-harness ·
  https://github.com/solid-contrib/specification-tests
- MCP: https://modelcontextprotocol.io/specification · Java SDK: https://github.com/modelcontextprotocol/java-sdk
- Reference implementation for behaviour comparison (never copy code — AGPL-adjacent
  licences and we are Apache 2.0; observe wire behaviour only): CommunitySolidServer
