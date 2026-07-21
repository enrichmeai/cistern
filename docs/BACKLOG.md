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
- [x] **T2.5 Conditional requests.** Honor `If-Match` (etag), `If-None-Match: *` (create-
  only PUT); mismatches → 412 before any store mutation; GET with `If-None-Match` matching
  → 304. DoD: tests prove the store is untouched on 412 (spy store). Delivered the full
  RFC 9110 §13.2.2 order (`If-Match`, `If-Unmodified-Since`, `If-None-Match`,
  `If-Modified-Since`); `If-Range` is out of scope while range requests are unimplemented
  (§14 makes them optional).
- [x] **T2.6 Global error handler.** Single WebFlux error mapper: BadInput→400,
  AccessDenied→401/403 (401 iff unauthenticated), missing→404, Conflict→409,
  PreconditionFailed→412; RFC 9457 problem+json bodies. Remove any per-handler error
  logic that crept in. DoD: each mapping tested; no `.onErrorResume` in handlers.
- [x] **T2.7 PATCH (N3).** Wire T1.5 behind `PATCH` with `Content-Type: text/n3`;
  patching a non-existent resource creates it (per spec, requires Append/Write); wrong
  content type → 415. DoD: spec examples pass over HTTP.
- [x] **T2.8 OPTIONS + CORS.** OPTIONS with correct Allow/Accept-* per resource kind; CORS
  wide-open by default (Solid apps are cross-origin by nature) with
  `Access-Control-Expose-Headers` covering ETag/Link/Location/WAC-Allow. DoD: preflight
  tests from a fake origin.
- [x] **T2.9 Discovery surface.** `/.well-known/solid` storage description; `Link:
  rel="http://www.w3.org/ns/solid/terms#storageDescription"` from every resource to it
  (GET/HEAD/OPTIONS); `Link: <http://www.w3.org/ns/pim/space#Storage>; rel="type"` on the
  root container. DoD: CTH discovery assertions targeted; curl transcript.
  <br>*Ticket text corrected on completion: the sketch said `rel="http://www.w3.org/ns/pim/space#storage"`
  (lower case) as the relation from a resource to the storage root. Solid Protocol §4.1
  defines no such link relation. Lower-case `pim:storage` is an RDF **predicate** for a WebID
  profile (T5.4); the storage root is advertised with the registered `type` relation targeting
  the upper-case **class** `pim:Storage`, and the resource→storage traversal §4.1 actually
  specifies is `solid:storageDescription`. Implemented per the spec text.*

## Phase 3 — Conformance ratchet (CTH)

- [x] **T3.1 CTH in CI.** GitHub Actions job: boot cistern-app (test profile, tmp storage),
  run the CTH docker image with `cth/subject-cistern.ttl`, upload the report artifact,
  write pass/fail counts to the job summary. Must not fail the build yet (report-only).
  DoD: artifact + summary visible on a PR.
  *Two things the ticket did not anticipate. (1) A full run emits **no results report at
  all** pre-T5.4 — it dies in setup — so the summary is built from the harness log, and
  `cth/summarize.sh` reports counts it cannot parse as unknown rather than as 0; a
  green-looking "0 failed" from a run that tested nothing would be the exact dishonesty
  ground rule 2 forbids. Coverage mode is run as well, since it is the only mode that
  yields a real artifact today. (2) The server must be booted with
  `CISTERN_BASE_URL=http://host.docker.internal:3000` — the origin the harness calls —
  or `Location` headers and the storage description name an origin it never contacted.*
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
  *See `docs/ideas/agent-scoped-delegation.md`: while capturing, record whether the
  **access** token carries a `client_id`/`azp` claim. Solid-OIDC only mandates `azp` on the
  ID token, yet CSS reads client identity off the access token — confirm what a real IdP
  actually emits before anything depends on it.*
- [ ] **T4.2 DPoP proofs.** Validate the `DPoP` header JWT: htm/htu match, iat window, jti
  replay cache, `cnf.jkt` thumbprint binding to the access token. DoD: matrix incl.
  replayed jti and mismatched thumbprint; fixtures real-captured.
- [ ] **T4.3 WebID verification.** Dereference the WebID document (WebClient, timeout+
  cache); confirm `solid:oidcIssuer` lists the token's issuer; result = authenticated
  `Agent(webId)` in Reactor context — single population point, downstream reads context
  only. DoD: issuer-mismatch → 401; WebID fetch failure → 401 not 500.
  **Decision required before starting** — see `docs/ideas/agent-scoped-delegation.md`:
  should the principal be `Agent(webId, Optional<URI> client)` from day one? One extra
  field now; a cross-cutting refactor against a frozen CTH baseline later. This ticket is
  the last cheap moment to choose.
- [ ] **T4.4 Security wiring.** WebFilter chain: anonymous requests proceed as
  `Agent.ANONYMOUS` (WAC decides), invalid credentials → 401 + `WWW-Authenticate`. No
  Spring Security session state; stateless only. DoD: WebTestClient auth matrix; no filter
  emits null signals; no `switchIfEmpty` hung off `chain.filter()`.

## Phase 5 — Authorization (cistern-wac)

- [x] **T5.1 ACL discovery.** Effective-ACL algorithm: resource's own `.acl` else walk
  ancestors for `acl:default`; advertise via `Link: rel="acl"`. DoD: unit tests for deep
  inheritance chains and root fallback.
  *`AclDiscovery` (reactive walk, no blocking I/O), `AclResource` (the `.acl` naming
  convention in one place, matching CSS so a pod moves between implementations),
  `EffectiveAcl` (graph + scope + source). The `Link: rel="acl"` header itself is emitted
  in T5.3 — `EffectiveAcl.aclResource()` supplies the URI.*
  *Two ways the obvious implementation is more permissive than the spec, both now tested.
  **An ACL that exists but does not parse denies rather than falling through** — continuing
  the walk would substitute an ancestor's `acl:default` (usually the more generous rule)
  for the one the owner wrote. **An empty ACL terminates the walk**, because the spec's
  condition is "has an associated aclResource with a representation", not a useful one; an
  empty ACL deliberately denies everyone and must not let a parent's defaults leak back.*
  ***Bug this caught in T5.2***: `acl:default` names the **container**, so an inherited
  authorization must be matched against the container the ACL is attached to, never against
  the child being requested. Matching the child silently denied every inherited grant. Fixed
  by making `EffectiveAcl.source()` the value to match and adding
  `WacEngine.decide(EffectiveAcl, Agent)` so callers cannot pass the two inconsistently.
  The unit test that should have caught it was passing the container while claiming to test
  a child — found only by the end-to-end test.*
- [x] **T5.2 WAC engine.** Evaluate Authorization triples: modes (Read/Write/Append/
  Control), subjects (`acl:agent`, `acl:agentClass foaf:Agent|acl:AuthenticatedAgent`,
  ~~`acl:origin`~~), targets (`acl:accessTo`, `acl:default`). Append ⊂ Write. Control never
  implied. Deny by default. DoD: table-driven tests covering the WAC spec examples.
  *`cistern-wac`: `WacEngine` + `AccessMode`/`AgentClass`/`AclScope` enums,
  `Authorization`/`AccessDecision` records, `WacMessage` catalogue. 30 tests, no Spring,
  no HTTP, no I/O — pure evaluation over a graph, so an authorization bug is findable
  without a server.*
  ***`acl:origin` deliberately not implemented*** *— it is absent from the matchers the
  current WAC spec defines, the CTH has no assertion for it, and
  `docs/ideas/agent-scoped-delegation.md` records why it is a dead letter (conjunctive
  rather than a subject in its own right; applies only to requests carrying an `Origin`
  header, so every non-browser client including every AI agent bypasses it; CSS ignores
  it outright). The ticket text predates that research. Raised rather than silently
  skipped, per ground rule 1.*
  *Two decisions worth carrying into T5.3. (a) The engine returns the **granted mode set**,
  not a boolean, because one evaluation must answer both "may this proceed?" and "what
  should `WAC-Allow` advertise?" — evaluating twice invites the two to disagree. (b)
  `AclScope` is a required argument: the same ACL yields different permissions depending on
  whether it was found on the resource (`acl:accessTo`) or inherited from an ancestor
  (`acl:default`), and conflating them leaks a container-only rule down the whole tree.*
  *Confirmed from the CTH for T5.3: authenticated-but-denied → **403**, unauthenticated →
  **401**.*
- [ ] **T5.3 Enforcement.** Map HTTP method+state → required mode (GET=Read, PUT/DELETE=
  Write, POST=Append on container, PATCH=per-patch-op, ACL editing=Control); enforce
  before handlers; emit `WAC-Allow` header on GET/HEAD. DoD: WebTestClient matrix
  authenticated/anonymous × allowed/denied → 200/401/403.
  *Two additions from `docs/demo/walkthrough.md`, both cheap here and expensive to
  retrofit. (a) **No authorization decision may outlive the request that produced it** —
  the moment a decision is cached into a session or token, instant revocation dies
  quietly and the demo's third beat with it. Add to the DoD as a test: delete a policy
  mid-session, next request is denied. (b) **The decision must name the policy resource
  that permitted or refused it**, so "which agent read what, under which grant" is
  answerable. Audit is asserted as a property in STRATEGY.md but scheduled nowhere; it is
  nearly free if the decision point carries it from the start.*
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
  *Script drafted ahead of the code in `docs/demo/walkthrough.md` (2026-07-21) — the demo
  is the product claim, and a claim is cheaper to reject in prose than in Java. Four
  beats: it works, **the refusal**, **live revocation**, the receipt. A demo without a
  refusal is a file browser, and beat 3 is what no token-scoping product can match.
  Two open questions it raises are not technical: which corpus (a demo over invented
  notes makes the refusal abstract), and how the owner authors the rule — a Turtle file
  proves enforcement works while demonstrating the product is unusable by its intended
  user. Recommendation there is a single-purpose CLI (`cistern grant claude --read
  /notes/ --for 24h`), roughly a day against a file format that must exist anyway.*

## Phase 7 — Packaging & announcement

- [ ] **T7.1 Starter.** cistern-spring-boot-starter: `@AutoConfiguration` wiring core+
  file-storage+webflux+auth+wac from `cistern.*` properties; a consumer app with only the
  starter dep + 3 lines of yaml serves a pod. DoD: sample in `docs/embedding.md` verified.
- [x] **T7.2 Docker.** Multi-stage Dockerfile + `docker-compose.yml` (server + volume);
  `ghcr.io/enrichmeai/cistern` build in CI (publish gated on tag). DoD: `docker compose up`
  → CTH runs against the container.
- [ ] **T7.3 README + site.** Conformance badge (real numbers from cth/BASELINE.md),
  quickstart (docker + starter), the Charlie-positioning paragraph, architecture diagram.
  DoD: a stranger can go zero→running pod in 5 minutes.
- [ ] **T7.4 Launch checklist.** Solid forum + Matrix post, Show-HN-style writeup,
  enrichmeai.com product page, Medium article ("building a Solid server agent-first,
  with the conformance harness as fitness function"). Gate: Milestones 1–3 all met,
  `local` demo T6.3 re-verified same-day. Owner: Joseph, not agents.
- [x] **T7.5 Infrastructure as code.** `infra/terraform/` — COS instance + persistent
  disk + IAP-only firewall for a GCP test pod, and `.github/workflows/terraform.yml`
  splitting `validate` (no credentials, every PR) from `plan`/`apply` (Workload Identity
  Federation, manual dispatch). Added outside the original plan at the owner's request
  2026-07-20; mirrored as issue #81, outside the T0.1=#1 … T7.4=#44 numbering. DoD: `terraform validate` green in
  CI with no cloud setup; the `0.0.0.0/0` guard demonstrated in both directions.
  *Authored, not applied — **apply is blocked by ADR 0001** until Phase 5 gives the pod
  an authorization layer. The one-time WIF/state-bucket bootstrap is deliberately
  manual and documented in `infra/terraform/README.md`.*
- [x] **T7.6 Kubernetes manifests (local).** `k8s/` kustomization — namespace with the
  `restricted` Pod Security Standard, RWO PVC, single-replica Deployment, ClusterIP
  Service, deny-all NetworkPolicy — plus `.github/workflows/k8s.yml` (kubeconform schema
  validation + a guard refusing NodePort/LoadBalancer/Ingress). Owner request 2026-07-21; mirrored as
  issue #82, outside the original numbering as T7.5 is. DoD: applied to a real cluster,
  data survives pod deletion, guard demonstrated in both directions.
  *Verified on Docker Desktop k8s v1.25.4: PUT 201 / GET 200, uid 10001, data survived
  the pod being destroyed. Two findings recorded in `k8s/README.md` — the `restricted`
  PSS **is** enforced (a non-compliant pod was refused), the NetworkPolicy is **not**
  (Docker Desktop's CNI ignores it; a second pod reached the service), so `ClusterIP`
  is the control that actually holds locally. Load-bearing choices: `strategy: Recreate`
  (the file backend is single-writer; RollingUpdate over an RWO volume risks two
  writers), TCP probes (the root legitimately 404s until T5.4, so httpGet would
  crash-loop a healthy server), `fsGroup: 10001` and an `emptyDir` at `/tmp`.*

## Parked (post-milestone-3 candidates — do not start without architect approval)

- `cistern-notifications` (WebSocketChannel2023), `cistern-acp`, `cistern-storage-r2dbc`
  (Postgres), Solid-OIDC **provider** module, privacy-fuzzing policy
  (docs/ideas/privacy-fuzzing.md), multi-tenant console (commercial track, separate repo).
