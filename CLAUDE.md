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

## Verification (rules of mechanics — 2026-08-22, 2026-08-28)

Every real defect found in two days of intensive work came from **checking a claim that had
already been asserted confidently** — several of them our own. None came from a failing test.

1. **Verify your own assertions before they reach the user or a public page.** A number you
   grepped, a config key you remembered, an env var you inferred: check it against the source
   of truth. This caught a wrong vulnerability count (9 vs the report's own 138), a config key
   that silently binds nothing, and a safety warning deleted during a rewrite.
2. **The filter and the handlers each own half of one answer.** Two bugs shipped from that
   split, in opposite directions: the `Link rel="acl"` header was written by
   `AuthorizationFilter` and then **replaced** by any handler emitting its own `Link` values
   (per-key `putAll`), with a slice test pinning the wiped state as correct; `OPTIONS *` was
   answered correctly by a handler the filter **400'd before it could run**. Both had green
   handler-level tests. So: anything the filter touches needs a `WebTestClient` test **through
   the chain**, and when one is wrong, suspect either half.
3. **Never publish a sentence that promises a future event.** "when Solid-OIDC lands", "no
   release yet" — both went stale and neither announced it. State the present and its cause.
4. **A green build proves less than it looks.** A stray `git add -A` once reverted four merged
   PRs and still compiled and passed, because the tests that would have caught it were among
   the reverted files.
5. **Before recommending we build something, check whether it already exists or already
   works.**
6. **Ticket, phase and version references are facts** — check them against `docs/BACKLOG.md`.
7. **A fix version named by a scanner is not a published artifact.** Confirm it resolves on
   Maven Central before promising a bump.

## Configuration binding (silent-failure traps — mechanics)

Spring's relaxed binding **removes hyphens** rather than converting them:
`cistern.auth.service-principals[0].web-id` is `CISTERN_AUTH_SERVICEPRINCIPALS_0_WEBID`. The
intuitive `SERVICE_PRINCIPALS_0_WEB_ID` binds *nothing*, silently.

`@Value` does not reliably split a comma-separated value into a collection. Bind a `String` and
split it, or use `@ConfigurationProperties`. A two-origin allow-list once arrived as one string
and canonicalised to a single unusable entry.

**Any property that changes security posture gets a binding test.** Both traps were found that
way and neither was visible in review.

## Working alongside other sessions (2026-08-28)

Several agents work these repos at once. **Never commit from the shared checkout** — use
`git worktree add ../cistern-<task> -b <branch> main`. A `git add -A` in a stale shared tree
silently reverted four merged PRs and still built green.

Announce what you are touching, **and correct it when it changes**. Re-read memory before
publishing a positioning claim; another session may have already corrected it. Drive review by
PR number rather than by checkout state — two sessions can be on one branch at different
staleness and mistake that for a collision.

A stash labelled with someone else's branch is probably bookkeeping, not lost work. Ask before
dropping it.

> **Rules above are mechanics** — how git, Spring and this codebase behave. Rules carrying a
> date and "owner directive" are **policy** and can be reversed; check the date before obeying
> one. A skill teaching a reversed policy propagates it silently, which is how the attribution
> rule nearly outlived its own reversal.

## Skills

`.claude/skills/` — invoke rather than re-derive. Written from the tasks that recurred and that
let errors through when rushed:

- **`verify-published-claim`** — check an artifact, number, link or upstream fact before it
  reaches the site, a README, a PR body or the user.
- **`ship-site-change`** — edit, validate and publish enrichmeai.com; merging to `main`
  publishes.
- **`land-pr`** — worktree through to verified merge.
- **`write-dispatch-brief`**, **`file-backlog-issue`**, **`probe-running-pod`** — the
  architect-loop tasks.
- **`track-open-prs`** — survey what is already in flight before building it: fetch first,
  check whether an open or merged PR already does this, find PRs touching the same files, and
  catch instructions a later policy commit reversed.

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
- Commits are authored and signed off by the committer alone — **no AI co-author
  trailers** (owner directive 2026-08-28, reversing 2026-07-17; history before the
  cutover keeps its trailers and is not rewritten, so the trail up to this date remains
  self-describing). The agent-first build story lives in the docs and the public
  narrative, not in trailers.
- **Outbound contributions to other projects carry no AI attribution anywhere** —
  commit author, sign-off, and PR body are the committer's alone (first applied:
  solid-contrib/conformance-test-harness#789).
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
