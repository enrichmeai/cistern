# Cistern backlog

> **Live status lives in [GitHub issues](https://github.com/enrichmeai/cistern/issues)** —
> every ticket below is mirrored as an issue (T0.1 = #1 … T7.4 = #44, epics #5/#11/#21/
> #25/#30/#36/#40/#45, milestones M1/M2/M3/Launch). This file remains the canonical DoD
> text; close the issue when the DoD is met, and keep the two in sync if scope changes.

Tickets are `T<phase>.<n>`. One ticket = one branch = one PR. Do them in order within a
phase; phases 1–2 may interleave once T1.2 is merged. Every ticket's DoD implicitly
includes: unit tests, no `.block()`, DCO sign-off, BACKLOG status updated, and no CTH
regression.

**Milestone 1** = end of phase 2 (server survives CTH bring-up, some assertions green).
**Milestone 2** = end of phase 3 (unauthenticated read/write conformant).
**Milestone 3** = end of phase 6 (WAC + auth conformant, MCP demo works) → public announcement.

---

## Phase 0 — Bootstrap

- [x] **T0.1 Build green.** `mvn -q verify` passes from a clean checkout. Fix anything the
  scaffold got wrong (missing deps, plugin versions). DoD: CI badge green on main.
- [x] **T0.2 Dependency audit.** Verify/bump every version in the parent POM to latest
  stable (Spring Boot 3.5.x line, Jena 5.x, Nimbus, Titanium, MCP SDK). Record chosen
  versions + date in the PR description. DoD: `mvn -q verify` green on the bumped set.
- [x] **T0.3 CI.** `.github/workflows/ci.yml` runs build + tests on PR and main pushes.
  DoD: a deliberately failing test on a branch fails the check.
- [x] **T0.4 CTH baseline.** Get `./cth/run-cth.sh` to execute the dockerized harness
  against a locally running cistern-app. Failing assertions are EXPECTED — the deliverable
  is the harness running end-to-end and `cth/reports/` capturing the report. DoD: report
  generated; baseline pass-count recorded in `cth/BASELINE.md`.

## Phase 1 — Core semantics (cistern-core)

- [x] **T1.1 RDF io.** `RdfIo` class: parse/serialize Turtle and JSON-LD via Jena; resolve
  relative URIs against the resource URI as base; reject malformed input with
  `CisternException.BadInput`. DoD: round-trip property tests Turtle⇄JSON-LD; malformed
  docs produce BadInput, not Jena exceptions leaking through.
- [x] **T1.2 Storage contract kit.** `ResourceStoreContractTest` (abstract, JUnit 5) in
  cistern-core test-jar, encoding every rule in the `ResourceStore` javadoc: empty-Mono
  on missing, intermediate-container creation on put, kind-flip rejection, non-empty
  container delete → Conflict, children of non-container → error, etag change-on-write,
  lastModified monotonicity. DoD: kit published as test-jar; in-memory reference impl
  (`InMemoryResourceStore`, test scope) passes it.
- [x] **T1.3 File backend.** cistern-storage-file: file-per-resource under
  `cistern.storage.root`; sidecar metadata files (`.meta.json`: contentType, etag) — never
  guess type from extension; atomic writes (tmp + move); containers = directories. DoD:
  extends and passes the contract kit; survives kill-mid-write (tmp files ignored on read).
- [x] **T1.4 Containment layer.** `LdpService.getContainer`: merge stored container triples
  with derived `ldp:contains` from `children()`; add `rdf:type ldp:BasicContainer|Resource`.
  Reject client attempts to PUT/PATCH containment triples directly (Solid Protocol server-
  managed triples) with Conflict (409 per Solid Protocol §5.3 — architect ruling on PR #52,
  spec text wins over the original BadInput wording). DoD: StepVerifier tests; a container
  GET shows exactly the live children.
- [x] **T1.5 N3 Patch engine.** Parse `text/n3` patch documents (the single
  `solid:InsertDeletePatch` resource with `solid:where` / `solid:inserts` / `solid:deletes`
  formulae — the spec vocabulary; the earlier `solid:InsertionPatch` / `solid:DeletionPatch`
  sketch was from memory and is not in the Solid Protocol); apply to a graph; no where
  mapping / multiple where mappings / deletion of absent triples → 409 semantics (Conflict).
  DoD: the patch example from the Solid Protocol spec text passes; fuzz malformed patches →
  BadInput. Done in `cistern-core` (`N3Patch`, `N3PatchParser`). Three status codes are kept
  distinct at the core boundary, because the HTTP layer cannot reconstruct them afterwards
  (architect ruling, PR #56):
  (a) **422** (`CisternException.UnprocessableEntity`, new) — well-formed N3 that violates the
  spec's patch constraints: not exactly one patch resource, missing/duplicate
  `solid:InsertDeletePatch` type, more than one of each formula, a non-formula formula object,
  blank nodes in `inserts`/`deletes`, `inserts`/`deletes` variables not occurring in `where`,
  **and** recognized N3 content inside a formula that is not a plain triple/triple pattern
  (nested formulae, collections, implications, declarations/quantifiers, blank-node property
  lists, terms in RDF-invalid positions) — the formulae must consist "only of triples and/or
  triple patterns".
  (b) **400** (`BadInput`) — unparseable entity: wrong/missing content type, non-UTF-8 bytes,
  null args, and malformed N3. Out-of-subset constructs at *document* level stay 400: the
  formula-content constraint does not reach them.
  (c) **409** (`Conflict`) — three conditions, all from the spec's processing rules: no `where`
  mapping, multiple `where` mappings, and `deletes` of triples absent from the graph.
  Deliberate limitation: blank nodes in `solid:where` are refused as **422** (spec-well-formed,
  but the mapping algorithm is defined over variables only) — revisit with CTH evidence, #57.

## Phase 2 — HTTP layer (cistern-webflux)

- [x] **T2.1 GET/HEAD.** Functional endpoints (RouterFunction, not annotated controllers):
  content negotiation between Turtle and JSON-LD driven by Accept (default Turtle);
  `Link: <...ldp#Resource>; rel="type"` (+BasicContainer for containers); ETag,
  Last-Modified, Allow, Accept-Put/Post/Patch headers; HEAD = GET minus body. Non-RDF
  resources served verbatim. DoD: WebTestClient tests per header; curl transcript in PR.
- [x] **T2.2 PUT.** Create/replace with intermediate containers; enforce slash semantics
  (PUT to `/foo/` with non-container body or kind-flip → 409 via Conflict); created → 201,
  replaced → 204/200. DoD: WebTestClient matrix create/replace/kind-flip/nested.
  Write orchestration is `LdpService.put` → `WriteOutcome(WriteEffect, ResourceView)`;
  replaced → **204** and created → **201 without `Location`** (RFC 9110 §9.3.4, §15.3.2).
  **No `ETag`/`Last-Modified` on RDF writes** (documents and containers alike) — RFC 9110
  §9.3.4 forbids a validator unless the served representation is the content received, and
  the read path re-serializes every RDF source from a parsed graph; both are sent on non-RDF
  writes, where bytes are served verbatim. Clients get RDF validators from `GET`/`HEAD`.
  Media types are canonicalized **only when RDF** (`text/turtle;charset=utf-8` →
  `text/turtle`); non-RDF types keep their parameters, so `text/plain;charset=utf-16`
  round-trips intact. Known: the create-vs-replace `exists`-then-`put` check is not atomic —
  the fix belongs in the storage SPI and is tracked separately.
- [x] **T2.3 POST to container.** Slug header honored (sanitized), collision → server picks
  a fresh name (never overwrite); generated name is a UUID-ish short id; `Location` header
  on 201; POST with `Link: ...BasicContainer; rel="type"` creates a child container. POST
  to a non-container → 404/405 per spec. DoD: WebTestClient matrix incl. slug collision.
  Create orchestration is `LdpService.createIn(container, Optional<Slug>, InteractionModel,
  Representation)` → `ResourceView` (a POST never replaces, so there is no `WriteEffect` to
  report); it reuses `put` to store the body, so RDF validation, the container-needs-RDF rule
  and §5.3's containment guard are inherited rather than restated. **`Slug` is a value type**
  (RFC 5023 §9.7, LDP §5.2.3.10): decoded once, allowlisted to RFC 3986 unreserved characters,
  runs collapsed, edges trimmed, capped at 128 — so `../`, `%2F` and dot segments cannot
  survive. A slug that sanitizes to **nothing is an ignored hint** (server generates instead);
  a control character or a broken escape is a **400**. **Collisions fall back to a generated
  name, never a numeric suffix** — numbering would disclose that a resource exists, which
  becomes a WAC leak in Phase 4 — and both spellings of a name (`/c/n`, `/c/n/`) count as
  taken (§3.1). Generated names are 22 lower-case alphanumerics (~114 bits). **Refusals:**
  target with no representation → **404** (§5.3, explicit); target that exists but is not a
  container → **405** (§5.3 confines POST creation to paths ending `/`; §5.2 + RFC 9110
  §15.5.6) — existence is checked first. **Interaction models** (LDP §5.2.3.4, architect ruling
  on PR #68): `ldp:BasicContainer`/`ldp:Container` → container; `ldp:Resource`/`ldp:RDFSource`/
  `ldp:NonRDFSource` → document; `ldp:DirectContainer`/`ldp:IndirectContainer` → **400, the
  request fails** rather than being silently downgraded ("If any requested interaction model
  cannot be honored, the server MUST fail the request" — we have no membership machinery and
  Solid §4.2 mandates Basic). A `rel="type"` IRI **outside the LDP namespace** still creates a
  document: the same paragraph ends "This specification does not constrain the server's behavior
  in other cases". 400 (not 501/422/409) because LDP §4.2.1.6 frames creation-constraint
  violations as "4xx responses" and RFC 9110 §15.5.1 covers a request the server will not process
  due to client error; no new `CisternException` subtype was needed. **Validators ARE permitted on POST**: RFC 9110
  §9.3.4's prohibition binds PUT only, and §15.3.2 says so ("Note that the PUT method ... has
  additional requirements"); they are sent for non-RDF creates and withheld for RDF ones,
  where the tag would be per-serialization and the 201 names no serialization. `Link` is
  parsed as the RFC 8288 structured field it is (`LinkHeader`), not substring-matched.
- [x] **T2.4 DELETE.** Document delete → 204 + parent containment updated; non-empty
  container → 409; storage root → 405. DoD: tests incl. root protection.
- [ ] **T2.5 Conditional requests.** Honor `If-Match` (etag), `If-None-Match: *` (create-
  only PUT); mismatches → 412 before any store mutation; GET with `If-None-Match` matching
  → 304. DoD: tests prove the store is untouched on 412 (spy store).
- [x] **T2.6 Global error handler.** Single WebFlux error mapper: BadInput→400,
  AccessDenied→401/403 (401 iff unauthenticated), missing→404, Conflict→409,
  PreconditionFailed→412; RFC 9457 problem+json bodies. Remove any per-handler error
  logic that crept in. DoD: each mapping tested; no `.onErrorResume` in handlers.
- [ ] **T2.7 PATCH (N3).** Wire T1.5 behind `PATCH` with `Content-Type: text/n3`;
  patching a non-existent resource creates it (per spec, requires Append/Write); wrong
  content type → 415. DoD: spec examples pass over HTTP.
- [ ] **T2.8 OPTIONS + CORS.** OPTIONS with correct Allow/Accept-* per resource kind; CORS
  wide-open by default (Solid apps are cross-origin by nature) with
  `Access-Control-Expose-Headers` covering ETag/Link/Location/WAC-Allow. DoD: preflight
  tests from a fake origin.
- [ ] **T2.9 Discovery surface.** `/.well-known/solid` storage description; `Link:
  rel="http://www.w3.org/ns/pim/space#storage"` from resources to the storage root;
  advertise root container. DoD: CTH discovery assertions targeted; curl transcript.

## Phase 3 — Conformance ratchet (CTH)

- [ ] **T3.1 CTH in CI.** GitHub Actions job: boot cistern-app (test profile, tmp storage),
  run the CTH docker image with `cth/subject-cistern.ttl`, upload the report artifact,
  write pass/fail counts to the job summary. Must not fail the build yet (report-only).
  DoD: artifact + summary visible on a PR.
- [ ] **T3.2 Ratchet gate.** Persist the best-known pass-count in `cth/BASELINE.md`; CI
  fails a PR whose pass-count drops below baseline; merging a PR that raises it updates
  the file (same PR). DoD: demonstrated both directions on a test branch.
- [ ] **T3.3 Protocol grind.** Iterate: run CTH → pick failing protocol-suite assertions →
  fix → repeat, one PR per coherent cluster (headers, slash semantics, status codes...).
  Open one `T3.3.x` sub-ticket per cluster as discovered. DoD (phase exit = Milestone 2):
  all unauthenticated read/write assertions of the protocol suite green.

## Phase 4 — Authentication (cistern-auth)

- [ ] **T4.1 Solid-OIDC validation.** Accept `Authorization: DPoP <token>`: resolve issuer
  discovery doc + JWKS (cached, TTL), verify signature/exp/aud per Solid-OIDC, extract
  `webid` claim. Fixtures captured from a REAL IdP (run CSS locally once, record its
  tokens/JWKS into `src/test/resources/fixtures/` — real-first rule). DoD: valid/expired/
  wrong-key/wrong-issuer matrix against captured fixtures.
- [ ] **T4.2 DPoP proofs.** Validate the `DPoP` header JWT: htm/htu match, iat window, jti
  replay cache, `cnf.jkt` thumbprint binding to the access token. DoD: matrix incl.
  replayed jti and mismatched thumbprint; fixtures real-captured.
- [ ] **T4.3 WebID verification.** Dereference the WebID document (WebClient, timeout+
  cache); confirm `solid:oidcIssuer` lists the token's issuer; result = authenticated
  `Agent(webId)` in Reactor context — single population point, downstream reads context
  only. DoD: issuer-mismatch → 401; WebID fetch failure → 401 not 500.
- [ ] **T4.4 Security wiring.** WebFilter chain: anonymous requests proceed as
  `Agent.ANONYMOUS` (WAC decides), invalid credentials → 401 + `WWW-Authenticate`. No
  Spring Security session state; stateless only. DoD: WebTestClient auth matrix; no filter
  emits null signals; no `switchIfEmpty` hung off `chain.filter()`.

## Phase 5 — Authorization (cistern-wac)

- [ ] **T5.1 ACL discovery.** Effective-ACL algorithm: resource's own `.acl` else walk
  ancestors for `acl:default`; advertise via `Link: rel="acl"`. DoD: unit tests for deep
  inheritance chains and root fallback.
- [ ] **T5.2 WAC engine.** Evaluate Authorization triples: modes (Read/Write/Append/
  Control), subjects (`acl:agent`, `acl:agentClass foaf:Agent|acl:AuthenticatedAgent`,
  `acl:origin`), targets (`acl:accessTo`, `acl:default`). Append ⊂ Write. Control never
  implied. Deny by default. DoD: table-driven tests covering the WAC spec examples.
- [ ] **T5.3 Enforcement.** Map HTTP method+state → required mode (GET=Read, PUT/DELETE=
  Write, POST=Append on container, PATCH=per-patch-op, ACL editing=Control); enforce
  before handlers; emit `WAC-Allow` header on GET/HEAD. DoD: WebTestClient matrix
  authenticated/anonymous × allowed/denied → 200/401/403.
- [ ] **T5.4 Pod provisioning.** `cistern.pods.seed` config: create pod root + owner ACL
  (owner WebID gets all modes incl. Control) on first boot; this is how CTH's alice/bob
  get pods. DoD: fresh boot creates seeded pods; restart is idempotent.
- [ ] **T5.5 WAC grind.** Same ratchet loop as T3.3 against the CTH WAC suite. DoD: WAC
  suite green ⇒ Milestone 3 gate 1.

## Phase 6 — MCP front-end (cistern-mcp)

- [ ] **T6.1 MCP server.** Using the official MCP Java SDK (Spring integration): expose
  tools `read_resource(uri)`, `write_resource(uri, content, contentType)`,
  `list_container(uri)`, `delete_resource(uri)` and MCP resources for pod browsing.
  Transport: stdio (for desktop clients) + streamable HTTP. DoD: MCP Inspector session
  transcript in the PR showing all four tools.
- [ ] **T6.2 Identity binding.** MCP connection config carries either a static WebID
  mapping (dev) or a Solid-OIDC token (prod path); every tool call goes through
  `WacEnforcer` as that agent — verify by test that a WAC-denied resource is denied over
  MCP with a clean MCP error, not a stack trace. DoD: allowed/denied matrix over MCP.
- [ ] **T6.3 Flagship demo.** `docs/demo/claude-desktop.md`: config + walkthrough — Claude
  reads and writes a note in a pod, is denied on another user's private container. Record
  the transcript; this is the launch asset. DoD: reproducible from the doc on a clean
  machine.

## Phase 7 — Packaging & announcement

- [ ] **T7.1 Starter.** cistern-spring-boot-starter: `@AutoConfiguration` wiring core+
  file-storage+webflux+auth+wac from `cistern.*` properties; a consumer app with only the
  starter dep + 3 lines of yaml serves a pod. DoD: sample in `docs/embedding.md` verified.
- [ ] **T7.2 Docker.** Multi-stage Dockerfile + `docker-compose.yml` (server + volume);
  `ghcr.io/enrichmeai/cistern` build in CI (publish gated on tag). DoD: `docker compose up`
  → CTH runs against the container.
- [ ] **T7.3 README + site.** Conformance badge (real numbers from cth/BASELINE.md),
  quickstart (docker + starter), the Charlie-positioning paragraph, architecture diagram.
  DoD: a stranger can go zero→running pod in 5 minutes.
- [ ] **T7.4 Launch checklist.** Solid forum + Matrix post, Show-HN-style writeup,
  enrichmeai.com product page, Medium article ("building a Solid server agent-first,
  with the conformance harness as fitness function"). Gate: Milestones 1–3 all met,
  `local` demo T6.3 re-verified same-day. Owner: Joseph, not agents.

## Parked (post-milestone-3 candidates — do not start without architect approval)

- `cistern-notifications` (WebSocketChannel2023), `cistern-acp`, `cistern-storage-r2dbc`
  (Postgres), Solid-OIDC **provider** module, privacy-fuzzing policy
  (docs/ideas/privacy-fuzzing.md), multi-tenant console (commercial track, separate repo).
