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

- [ ] **T1.6 Object-storage backend (GCS/S3).** New `cistern-storage-gcs` (or `-s3`, by first
  deployment target) implementing `ResourceStore` — objects keyed by resource path, media type
  and ETag in object metadata, `children()` via prefix listing; never a mounted bucket (gcsfuse
  breaks atomic rename). DoD: `ResourceStoreContractTest` green against a real bucket (integration
  profile) and an emulator in CI; no RDF parsing; wired via `cistern.storage.backend`. Issue #95.
  Post-Milestone-3 unless a first deployment (T7.7) needs it. Owner request 2026-08-18.

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

- [x] **T4.0 Resolver seam — pluggable `PrincipalResolver` chain, OIDC/JWT resolver, service
  principals.** Phase 4-lite for a first application (ValueDocs legal/tax), per
  `docs/ideas/first-user-path.md`: `ChainedPrincipalResolver` (first authenticated wins) keeping
  `LocalCredentialResolver`/`AnonymousResolver`; `OidcJwtPrincipalResolver` in `cistern-auth`
  (Nimbus JWKS, iss/aud/exp, claim → WebID); `ServicePrincipalRegistry` so each application is
  its own WebID with its own credential. Enforcement path untouched. **Does not replace
  T4.1–T4.4**, which remain the conformance/interop path. DoD: WebTestClient matrix (valid /
  expired / wrong-aud / bad-sig JWT; service credential; owner token); fixtures captured from a
  real IdP; `docs/INTEGRATION.md` §Identity updated; no CTH regression. Issue #88.
  *Depends on the architect's ruling on the resolver-seam resequencing and on the
  principal-shape decision (issue #89, the T4.3 `Agent(webId, client)` question).*

- [x] **T4.1 Solid-OIDC validation.** Accept `Authorization: DPoP <token>`: resolve issuer
  discovery doc + JWKS (cached, TTL), verify signature/exp/aud per Solid-OIDC, extract
  `webid` claim. Fixtures captured from a REAL IdP (run CSS locally once, record its
  tokens/JWKS into `src/test/resources/fixtures/` — real-first rule). DoD: valid/expired/
  wrong-key/wrong-issuer matrix against captured fixtures.
  *See `docs/ideas/agent-scoped-delegation.md`: while capturing, record whether the
  **access** token carries a `client_id`/`azp` claim. Solid-OIDC only mandates `azp` on the
  ID token, yet CSS reads client identity off the access token — confirm what a real IdP
  actually emits before anything depends on it.*
- [x] **T4.2 DPoP proofs.** Validate the `DPoP` header JWT: htm/htu match, iat window, jti
  replay cache, `cnf.jkt` thumbprint binding to the access token. DoD: matrix incl.
  replayed jti and mismatched thumbprint; fixtures real-captured.
- [x] **T4.3 WebID verification.** Dereference the WebID document (WebClient, timeout+
  cache); confirm `solid:oidcIssuer` lists the token's issuer; result = authenticated
  `Agent(webId)` in Reactor context — single population point, downstream reads context
  only. DoD: issuer-mismatch → 401; WebID fetch failure → 401 not 500.
  **Decision required before starting** — see `docs/ideas/agent-scoped-delegation.md`:
  should the principal be `Agent(webId, Optional<URI> client)` from day one? One extra
  field now; a cross-cutting refactor against a frozen CTH baseline later. This ticket is
  the last cheap moment to choose.
- [x] **T4.4 Security wiring.** WebFilter chain: anonymous requests proceed as
  `Agent.ANONYMOUS` (WAC decides), invalid credentials → 401 + `WWW-Authenticate`. No
  Spring Security session state; stateless only. DoD: WebTestClient auth matrix; no filter
  emits null signals; no `switchIfEmpty` hung off `chain.filter()`.
  *Code and test matrix landed in #143, hardened in #144. The end-to-end leg ("no CTH
  regression") verified 2026-08-27: full harness run against main `4121b27`, unchanged
  at 0 / 0 / 41 — details and the two named walls in `cth/BASELINE.md`.*

## Phase 5 — Authorization (cistern-wac)

- [x] **T5.1 ACL discovery.** Effective-ACL algorithm: resource's own `.acl` else walk
  ancestors for `acl:default`; advertise via `Link: rel="acl"`. DoD: unit tests for deep
  inheritance chains and root fallback.
  *`AclDiscovery` (reactive walk, no blocking I/O), `AclResource` (the `.acl` naming
  convention in one place, matching CSS so a pod moves between implementations),
  `EffectiveAcl` (graph + scope + source). The `Link: rel="acl"` header fell through this
  note's hand-off to T5.3 (whose DoD listed only `WAC-Allow`) and was landed by the first
  full CTH run's failure (PR #159): a `beforeCommit` hook in `AuthorizationFilter`, and the
  advertised URI is `AclResource.aclFor(target)` — the target's own ACL, never the effective
  source, which is the server's business to walk.*
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
- [x] **T5.3 Enforcement.** Map HTTP method+state → required mode (GET=Read, PUT/DELETE=
  Write, POST=Append on container, PATCH=per-patch-op, ACL editing=Control); enforce
  before handlers; emit `WAC-Allow` header on GET/HEAD. DoD: WebTestClient matrix
  authenticated/anonymous × allowed/denied → 200/401/403.
  ***Anonymous DELETE now returns 401, not 204*** *— the defect behind ADR 0001 is fixed.
  `AuthorizationFilter` (a WebFilter, so no handler can be added that forgets to ask),
  `RequiredAccess` (the method→mode table), `AccessControl` (discovery + engine),
  `PrincipalResolver`/`LocalCredentialResolver`/`AnonymousResolver`, `OwnerPodSeeder`.
  11 HTTP tests; full build 1,195 green.*
  ***DELETE requires Write on the parent container as well as on the resource*** *— taken
  from the harness, which returns 403 for `container=no, resource=W`. Removing a resource
  edits its parent's containment triples, so checking only the resource side would let an
  agent with Write on one document delete it out of a container it has no rights over.*
  ***Enforcement is registered only when `cistern.owner.web-id` is set.*** *WAC needs a
  principal and a root ACL to be useful; with neither, the only reachable decision is
  "deny", so the pod would be inert rather than secure and there would be no way in to
  write the ACL that fixes it. A deployment that forgets the owner is unprotected exactly
  as before — made loud (`NO_OWNER_CONFIGURED` at WARN every boot) rather than silent, and
  ADR 0001 keeps such a pod on loopback anyway. Turning it on brick-walled 211 existing
  tests, which is what surfaced the question.*
  *PATCH requires only Append, deliberately: reading the body to tell inserts from deletes
  would mean consuming it before the handler. Append is the weaker check and the N3 engine
  already refuses to delete absent triples; tighten to Write once the parsed patch reaches
  the enforcement point.*
- [x] **T5.4 Pod provisioning.** `cistern.pods.seed` config: create pod root + owner ACL
  (owner WebID gets all modes incl. Control) on first boot. DoD: fresh boot creates seeded
  pods; restart is idempotent.
  *Done as `OwnerPodSeeder` for the single-owner case: seeds the root ACL with
  `acl:accessTo` **and** `acl:default` (granting only the first leaves every child
  unreachable), never overwrites an existing one — a restart is not a request to reset
  permissions — and grants nothing to `foaf:Agent`, so a new pod is private until its owner
  says otherwise. **The CTH's alice/bob multi-pod seeding is NOT done** and still needs
  Phase 4; this unblocks the owner, not the harness.*
  *Two additions from `docs/demo/walkthrough.md`, both cheap here and expensive to
  retrofit. (a) **No authorization decision may outlive the request that produced it** —
  the moment a decision is cached into a session or token, instant revocation dies
  quietly and the demo's third beat with it. Add to the DoD as a test: delete a policy
  mid-session, next request is denied. (b) **The decision must name the policy resource
  that permitted or refused it**, so "which agent read what, under which grant" is
  answerable. Audit is asserted as a property in STRATEGY.md but scheduled nowhere; it is
  nearly free if the decision point carries it from the start.*

- [x] **T5.8b WebID profile seeding — the document the harness 404s on.** A seeded pod got a
  container and an owner ACL and no WebID document, so an owner whose identity lives in their
  own pod could not authenticate anywhere: Solid-OIDC resolves the WebID and looks for
  `solid:oidcIssuer`, and no document means no triple. That 404 is what stops the conformance
  harness in REGISTER CLIENTS. `cistern.pods.seed[n].oidc-issuer` turns it on per pod;
  unset means no document is written, which is right when the owner's WebID lives at their own
  provider. The profile is granted public Read — that is what a WebID *is*, since a resource
  server checking a token has not authenticated anyone yet and must read it anonymously — on
  the document alone, with no `acl:default`, and the owner keeps every mode. Idempotent: an
  existing profile is left alone rather than reverted on boot. DoD: 7 tests on the graph and
  its ACL; full build green.

- [ ] **T5.5 WAC grind.** Same ratchet loop as T3.3 against the CTH WAC suite. DoD: WAC
  suite green ⇒ Milestone 3 gate 1.

- [x] **T5.6 Pod & matter provisioning API/CLI (multi-owner).** The un-built half of T5.4
  (#34): `PodProvisioner` in `cistern-wac` creating a root/matter container + owner ACL
  (`accessTo` **and** `default`, no `foaf:Agent`), idempotent, never overwriting an existing
  ACL; exposed as `cistern pod create` (new `cistern-cli` module) and as a Control-protected
  admin call; `cistern.pods.seed[]` for boot-time seeding of several pods. DoD: N seeded pods
  on fresh boot, restart idempotent; CLI second run is a no-op; contract test on the ACL shape;
  CTH alice/bob seeding expressible by config. Issue #90.
- [x] **T5.7 Grant authoring — `GrantService` + `cistern grant` CLI.** Add/remove an
  `Authorization` for a WebID or `foaf:Agent` on a target, always preserving the owner's
  authorization and always writing `accessTo` + `default` on containers (the two silent-deny
  traps); performed as a normal `PUT <target>.acl` under the caller's credential so Control is
  enforced. `--until` requires T5.8. DoD: property test — owner keeps Control after any
  grant/revoke sequence; CLI transcript reproduces beats 3 and 5 of `k8s/demo.sh` without
  hand-written Turtle. Issue #91.
- [ ] **T5.8 Time-boxed grants.** `cistern:validUntil` (xsd:dateTime) on an `acl:Authorization`
  (new `Cistern` vocab class in core); `Authorization` gains `Optional<Instant> validUntil`;
  `WacEngine` treats expired or malformed values as absent (fail closed); `Clock` injected; no
  decision caching (T5.3 invariant). Foreign ACLs without the term behave as today. DoD: engine
  tests active/expired/malformed/boundary; HTTP test — grant with past `--until` denied on the
  next request, future allowed, clock advance denies without restart; no CTH regression.
  Issue #92.
- [x] **T5.9 Decision log & receipts.** `AccessDecision` carries `decidedBy` (the effective ACL
  resource, already known to `EffectiveAcl`) and matched authorization IRIs; `DecisionRecord` +
  `DecisionSink` (append-only JSON Lines under `<root>/.cistern/decisions/` via `ResourceStore`;
  in-memory for tests); one record per decision from `AuthorizationFilter`, allow and deny,
  never blocking the request (`cistern.audit.required` flips that); `DecisionQuery` per resource
  (requires Control) and per agent (owner). DoD: the five demo beats yield a receipt naming
  `/trips/.acl`; receipts endpoint denies without Control; log failure does not change the
  outcome unless required. Issue #93. *This is beat 4 of `docs/demo/walkthrough.md`.*

## Phase 6 — MCP front-end (cistern-mcp)

- [x] **T6.1 MCP server.** Using the official MCP Java SDK (Spring integration): expose
  tools `read_resource(uri)`, `write_resource(uri, content, contentType)`,
  `list_container(uri)`, `delete_resource(uri)` and MCP resources for pod browsing.
  Transport: stdio; Streamable HTTP added in T6.7. DoD: MCP Inspector session
  transcript in the PR showing all four tools.
- [x] **T6.2 Identity binding.** MCP connection config carries either a static WebID
  mapping (dev) or a Solid-OIDC token (prod path); every tool call goes through
  `WacEnforcer` as that agent — verify by test that a WAC-denied resource is denied over
  MCP with a clean MCP error, not a stack trace. DoD: allowed/denied matrix over MCP.
- [ ] **T6.4 OAuth resource-server metadata.** Cistern serves OAuth 2.0 Protected Resource
  Metadata (RFC 9728) at `/.well-known/oauth-protected-resource` and carries `resource_metadata`
  in `WWW-Authenticate` on 401 (MCP authorization spec). An external authorization server
  exposes AS metadata (RFC 8414), dynamic client registration (RFC 7591, with its
  anonymous-registration policy recorded), PKCE, and resource indicators (RFC 8707) so tokens
  carry Cistern's audience and `cistern-auth` rejects the rest. **No IdP code in Cistern** — we
  validate, we do not issue. Depends on T6.7, which supplies the transport a remote MCP client
  needs before any of this is reachable. DoD: fixtures of the real client OAuth flows captured
  (ground rule 6); `claude mcp add --transport http` and a desktop custom connector both
  complete OAuth and list the tools, transcript in the PR; WebTestClient for the
  401 / wrong-audience / valid cases; `INTEGRATION.md` "MCP clients" step. Issue #118.

- [ ] **T6.5 (user, client) principal — the deferred half of #89.** `Agent(webId, client)`
  already carries the field (ruled 2026-08-23, #131); this is the half that *uses* it. Client
  from the token's `client_id` — T4.1's capture confirmed a real access token carries
  `client_id` and not `azp`. `WacEngine` matcher for a client-scoped authorization and the
  intersection cap `effective = modes(user) ∩ modes(client)`; `cistern grant` can target
  `(webId, client)`. DoD: allowed/denied matrix over (user-only, client-only, both, neither)
  with the cap proven; ARCHITECTURE load-bearing decision recorded and #89 closed; no CTH
  regression. Issue #119.

- [ ] **T6.6 Distribution pack.** Client plugin manifest (`plugin.json` + `.mcp.json` with a
  configure-later `url`; skills: save to pod / recall / grant-revoke an agent), tool annotations
  on every MCP tool (`title`, `readOnlyHint`/`destructiveHint`), connector-directory submission
  pack (privacy policy URL, docs, support contact, reviewer test account), MCP registry
  `server.json`. **Nothing in the pack claims "first" or "the only open one"** (#100) — a commercial
  Solid server shipped MCP support in May 2026, and the claim would be false. DoD: plugin installs on a clean machine and connects to
  a deployed URL with the three skills working, transcript in the PR; pre-submission checklist
  passes with evidence. Where that deployment is hosted, and anything to do with signup, is not
  this ticket and not this repository. Issue #120.

- [x] **T6.7 Streamable HTTP transport.** Written rather than imported: the MCP Java SDK
  2.0.0 ships Servlet transports only, and `io.modelcontextprotocol.sdk:mcp-spring-webflux`
  stops at 0.18.4 — never carried to the 2.x line — so a Netty/WebFlux server on SDK 2.0 has
  no first-party HTTP transport. `WebFluxStreamableTransport` implements
  `McpStreamableServerTransportProvider`: POST (initialize → JSON + `Mcp-Session-Id`; request
  → SSE; notification/response → 202), GET (the server-initiated stream), DELETE (end the
  session). Never blocks — the Servlet version `.block()`s on the initialize result and on
  notification handling, which an event loop cannot afford. `McpFrontDoor` gained an overload
  so both transports are built from one definition of the tools; a client over HTTP gets the
  same surface and the same refusals as one over stdio. Off unless `cistern.mcp.http.enabled`.
  **Stated non-goal: resumability.** `Last-Event-ID` is refused with 501 rather than accepted
  and ignored — a client told its stream resumed and then silently missing messages is worse
  off than one told it cannot. Every message is stamped with an id so adding an event store
  later changes the store, not the wire. DoD: 9 protocol tests over real HTTP via
  `WebTestClient.bindToRouterFunction`; full build green. *Renumbered from T6.4 — #121
  reserved T6.4–T6.6 first.*

- [x] **T6.3 Flagship demo.** `docs/demo/README.md` + `docs/demo/one-command.yml`: one
  compose file brings up a pod and Penstock together, the agent holding its own service
  credential so Cistern resolves it to the agent's own WebID. Five beats — put a document in,
  ask and be **refused**, grant one document read-only, ask and be answered, revoke and be
  refused **on the next request** — then read the receipt from the pod. Runs entirely offline
  with Ollama, both ports bound to loopback. Honest limits stated in the doc: Penstock v0.1.0
  has unpatched findings in its own published scan so it is a local demo, the WebIDs are local
  rather than resolvable, and conformance is whatever `cth/BASELINE.md` currently says.
  *The Claude Desktop walkthrough the ticket originally described is superseded: the
  interesting demonstration turned out to be an agent that authenticates as itself and gets
  refused, which stdio MCP with a statically bound identity cannot show.*
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

- [ ] **T7.7 Production deployment posture.** Supersede ADR 0001 with ADR 0002 once T4.0 lands
  (conditions: T4.0 merged, TLS terminated, no owner secret in production): TLS + domain,
  apply `infra/terraform` to the GCP test project (COS + persistent disk, never Cloud Run +
  gcsfuse), backup schedule + restore drill, per-firm isolation (separate pods/roots), edge
  rate limiting, request-id propagation for T5.9. DoD: a ValueDocs test instance over TLS with
  T4.0 auth and the owner secret unset; `k8s/demo.sh` passes remotely with a JWT/service
  credential; restore drill documented in `docs/deploy.md`. Issue #94.
- [ ] **T7.8 Integration architecture & application playbook.** `docs/INTEGRATION.md`:
  architecture as built, integration model, step-by-step playbook (ValueDocs worked example)
  with curl verified against the jar, derived-data rule, first-cut interfaces and module
  placement for T4.0/T5.6–T5.9/T7.7/T1.6, config reference, status-code contract. DoD: every
  claim verified or marked planned with its ticket; linked from README and ARCHITECTURE.
  Issue #96.

- [ ] **T7.9 Client SDKs.** Thin Java (`cistern-client-java`, no Spring) and TypeScript
  (`@enrichmeai/cistern-client`) clients over the observed HTTP contract
  (`docs/INTEGRATION.md` §8): auth header, ETag preconditions, typed 401/403/412/409,
  `WAC-Allow` parsing, container listing, non-RDF uploads; `grant`/`revoke` after T5.7,
  `receipts` after T5.9. Contract tests against a real server in CI (ground rule 6). DoD:
  both clients reproduce `k8s/demo.sh` end to end; ≤15-line README snippet; INTEGRATION.md
  gains "with the client" variants. Issue #101.
- [x] **T7.10 Integration kit.** `integration-kit/`: docker compose booting Cistern +
  Keycloak (real OIDC issuer, seeded humans + `app-legal`/`app-tax` service principals) + a
  sample app that authenticates, lists a matter, reads a document, is refused on a sibling
  folder and shows its `WAC-Allow`; seed script provisions pod + grants (T5.6/T5.7 when
  merged, template until then); fixtures reused by T4.0 tests. DoD: `docker compose up` →
  allow/refuse sequence within 2 minutes; loopback only. Issue #102.
- [ ] **T7.11 Hosted offering — design note (ADR 0003).** Decide tenancy model, BYO-IdP vs
  managed, the open-here / commercial-repo boundary, pricing *shape* (per vault / seat /
  GB / receipt volume; the business is billed, never the end person in v1), SG + IN
  residency, and whole-vault export as standard Solid data. Note + decision only; build
  lands in the commercial repo. DoD: ADR merged; INTEGRATION.md step 8 and COMMERCIAL.md
  link to it. Issue #103.

- [x] **T7.14 First tagged release.** `release.yml` on `v*` tags: version from tag, full tests,
  native amd64+arm64 images pushed by digest to `ghcr.io/enrichmeai/cistern:<version>` (+ `latest`
  for non-prerelease), GitHub Release with jar + `SHA256SUMS` and the CHANGELOG section; OCI
  labels; `CHANGELOG.md` with upgrade notes; README "pull it" path; k8s pinned to the tag. Issue
  #107, PR #109. *First tag `v0.1.0` to be pushed by the owner; GHCR package must be made public
  once.*
- [x] **T5.3 follow-up — ACL resources require Control for every method** (#112, PR #115):
  `RequiredAccess` maps any `.acl` target to Control on the governed resource; anonymous GET of
  an ACL after a public Read grant went 200 → 401, authenticated non-Control → 403.

## Parked (post-milestone-3 candidates — do not start without architect approval)

- `cistern-notifications` (WebSocketChannel2023), `cistern-acp`, `cistern-storage-r2dbc`
  (Postgres), Solid-OIDC **provider** module, privacy-fuzzing policy
  (docs/ideas/privacy-fuzzing.md), multi-tenant console (commercial track, separate repo).
