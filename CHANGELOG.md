# Changelog

All notable changes to Cistern are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow
[Semantic Versioning](https://semver.org/) — pre-1.0, minor versions may break.

A release is a `v<version>` tag on `main`. Pushing it runs `.github/workflows/release.yml`,
which publishes `ghcr.io/enrichmeai/cistern:<version>` (linux/amd64 + linux/arm64) and a
GitHub Release carrying `cistern-app-<version>.jar`, the CLI (`cistern-cli-<version>.jar`
plus its `cistern` wrapper script), the MCP bridge (`cistern-mcp-<version>-bridge.jar`)
and `SHA256SUMS` over all four, with this file's matching section as the release body.
**A tag without a section here fails the release** before anything is built, deliberately.
The procedure around the tag — gate, rehearsal, stranger test — is [RELEASE.md](RELEASE.md).

## [Unreleased]

## [0.3.0] - 2026-10-15

Solid-OIDC. A pod now accepts a person or an agent signed in at **any** Solid identity provider, not only
the owner's token, a service credential or one configured OIDC issuer: the access token is verified, the
DPoP proof shows the caller holds the key the token is bound to, and the WebID document must name the
token's issuer. All three, or the request is anonymous. It is **opt-in** (`cistern.auth.solid.enabled=true`);
with it off, authentication is exactly as in 0.2.0. Also: delegation where the client narrows a person's
grant instead of being granted, `cistern sync`, and the fixes the Solid conformance harness found.

### Added

**Solid-OIDC with DPoP (`cistern-auth`, T4.1–T4.4, #131 #140 #141 #143)** — opt-in with
`cistern.auth.solid.enabled=true`.
- Access-token verification against the issuer's discovery document and JWKS, written against tokens
  captured from Community Solid Server 7.2.0 (the Solid-OIDC spec leaves the resource-server side
  undefined, so the shape comes from a real implementation).
- DPoP proof validation step by step against RFC 9449 §4.3, with a bounded `jti` replay cache and a 60 s
  `iat` window. Not done: server-issued `DPoP-Nonce` challenges (step 10).
- WebID issuer verification: the WebID document is fetched and must carry `solid:oidcIssuer` naming the
  token's `iss`. Fail closed: a WebID that cannot be fetched authenticates nobody: the request is
  anonymous (a 401 where a grant is needed, never a 500).
- The three checks compose in one place (`SolidOidcPrincipalResolver`); `htu` is checked against
  `cistern.base-url` plus the request path, never the socket or `X-Forwarded-*`.
- WebID fetches refuse non-public origins (SSRF guard). `cistern.auth.solid.webid.trusted-origins`
  allows named origins, empty by default and logged at WARN when set (#148).
- The agent carries the client it acted through (#89), for the delegation rules below.

**Delegation: the client is a constraint, never a grantee (`cistern-wac`, T6.5, ADR 0004, #203)** — a
delegated request carries the person and the client that acted for them; a rule grants the person and
the `cistern:client` term narrows when that grant applies, so a delegation exported to another Solid
server still means what it meant here. Opt-in with `cistern.wac.delegation.enabled=true` (default off:
off is plain WAC, the term is not read). Write one with `cistern grant <webid> --read --client <client-id>
<path>`; the receipt names `narrowedBy: CLIENT` when the constraint refused. On the plain-OIDC path the
client is read from `client_id`, else `azp`; only an absolute URI counts.

**WebID document seeding (#152)** — `cistern.pods.seed[n].oidc-issuer` writes the owner's WebID document
with its `solid:oidcIssuer` triple when a pod is seeded, so an owner whose identity lives in their own
pod can sign in. Unset, nothing is written, as before.

**`cistern sync` (`cistern-cli`, T7.17, #200, PR #202)** — mirror a local folder into a pod container.
Sub-folders become containers; files are sent under a closed extension-to-media-type table
(`.md`, `.pdf`, `.csv`, `.ttl`, …; anything else `application/octet-stream`), every write
conditional: `If-None-Match: *` for a file never sent, `If-Match` for one sent before, and a
412 is exit 3 with the pod's copy left standing. `<local-dir>/.cistern-sync.json` remembers
what was sent, so a second run with nothing changed sends nothing. `--dry-run` prints the plan;
`--delete` (off by default) removes what the folder no longer holds. The CLI parses no RDF.

**MCP over Streamable HTTP (`cistern-mcp`, T6.7, #151)** — a transport for MCP clients that are not
local processes, behind `cistern.mcp.enabled=true` plus `cistern.mcp.http.enabled=true`. **It does not work in the assembled app in this
release** (see *Known limitations*); the stdio door from 0.2.0 is unchanged.

**Brokered sign-in kit (`integration-kit`, T7.16, #204)** — Google Workspace through Keycloak: a person
with no `webid` claim gets a WebID built from the token by `cistern.auth.oidc.webid-template`. Kit and docs
only.

**Demo (T6.3, #157, #184)** — one command stands up a pod and Penstock together; the agent holds its own
credential, so the refusal before a grant is the demonstration.

### Fixed

- **Security (#186):** a bare `OPTIONS` carrying `Access-Control-Request-Method` but no `Origin` skipped
  WAC entirely: an anonymous probe of whether a protected resource exists and what it allows, with no
  receipt. It is now authorised like any other request; only a real preflight (`Origin` plus
  `Access-Control-Request-Method`) is exempt. Upgrade if the pod is reachable by untrusted callers.
- **`cistern grant` could lock the owner out of a container (#227).** If the CLI's `--base` and the
  server's `cistern.base-url` named the same server differently (`127.0.0.1` and `localhost`, as the
  0.2.0 README's quickstart and the CLI's default did), a grant on a container that inherits wrote an
  ACL that matched nothing the server reads. It re-stated nobody's Control, yet reported success. Now:
  - The CLI refuses an ACL whose rules name the pod under another origin than `--base`, before
    writing anything, and says which `--base` to use (exit 1).
  - `GrantService` refuses to write an ACL for an inheriting target when it finds no Control-holder
    to re-state.
  - The README quickstart uses `127.0.0.1` on both sides.
- WAC: every resource response advertises its own ACL with `Link: rel="acl"` (ACL discovery MUST, #159);
  the unused `aclResourceFor`, which pointed at the inherited ACL #159 ruled out, is deleted (#196).
- CORS (T3.3, #186): CORS fields on refusals, one `Vary` line, preflight grants echo the request.
- `OPTIONS *` reaches its handler instead of answering 400 on every enforced deployment (#161, #163).
- `trusted-origins` bound two origins as one unusable entry (#153).
- Auth review findings: DNS lookups off the event loop, caches bounded, `jwks_uri` vetted (#144).

### Changed

- `cistern-cli`: exit code 3 now also covers a synced copy that changed on the pod; the refusal
  message for a resource (not an ACL) names the mode the method needed — Read, Write, or Write
  on it and its container for a delete.
- CORS never decides an actual request (#186): an origin outside `cistern.cors.allowed-origins` gets no
  CORS headers, so the browser withholds the response, while the pod still authorises the request and
  records the receipt.

### Known limitations (read before deploying)

- **MCP over HTTP is not reachable in the assembled app (#220).** `POST /mcp` is answered by the Solid
  resource handler (404 for the owner, 403 for an agent), so a remote MCP client cannot reach the door.
  Use the stdio bridge (`cistern-mcp-0.3.0-bridge.jar`), as in 0.2.0.
- **`cistern sync` from a second machine** (no state file) exits 3 on files it already sent instead of
  reconciling (#219); a state file for a pod that no longer holds the files makes sync send nothing
  (#221); `.yaml`, `.yml` and `.sh` go as `application/octet-stream` (#218).
- Solid-OIDC is off unless enabled, and no server-issued `DPoP-Nonce` challenge is sent.
- A DPoP proof without `ath` is refused (RFC 9449 §4.3). The conformance harness's client omits it, so
  the official number is still 0 of 41 executed (`cth/BASELINE.md`); the upstream fix is
  solid-contrib/conformance-test-harness#789.

### Upgrade notes

- Authentication is unchanged until you set `cistern.auth.solid.enabled=true`, and delegation until
  `cistern.wac.delegation.enabled=true`.
- HTTP behaviour does change without either flag: every resource response carries `Link: rel="acl"`,
  `OPTIONS *` answers instead of returning 400, and CORS no longer refuses a disallowed origin's actual
  request (it gets no CORS headers instead; see *Changed*).
- With Solid-OIDC on behind a proxy, set `cistern.base-url` to the public URL clients call: DPoP's `htu`
  is checked against it.
- An identity provider on a private network or loopback needs its origin in
  `cistern.auth.solid.webid.trusted-origins`.

## [0.2.0] - 2026-08-20

The MCP front door. Any MCP client — Claude Desktop first — now connects to a pod and gets
exactly what its bound identity was granted: reads inside the grant, structured refusals
outside it, revocation that lands on the very next tool call, and receipts for all of it.
The four-beat flagship demo (works · refusal · live revocation · the receipt) runs end to
end against a real MCP client. Every MCP call crosses the same enforcement filter as every
HTTP caller — no privileged path, by architecture decision.

### Added

**MCP front door (`cistern-mcp`, T6.1 + T6.2, #37/#38)**

- The MCP server: seven tools — `read-resource`, `list-container`, `write-resource` (ETag
  preconditions honoured), `delete-resource`, `grant`/`revoke` (the same `.acl` writes the
  CLI performs; Control enforced by the server), `receipts` (Control enforced) — built on
  the official MCP Java SDK. Deliberately no search tool, and no MCP resources in v1:
  nothing the door serves may outlive a revocation.
- **No privileged internal path** (ARCHITECTURE decision 6): every tool call is a real HTTP
  request to the running server — the standalone bridge over the network, the embedded
  shape over its own loopback port — crossing `AuthorizationFilter`, decided by WAC afresh,
  and leaving a receipt like any other client's request.
- **Static identity binding** (T6.2, per the #89 ruling): one connection, one bearer
  credential (`cistern.mcp.credential` / `CISTERN_MCP_CREDENTIAL`), resolved through the
  ordinary resolver chain. An enabled door without a credential is refused at bind time.
- Refusals are honest and structured: a 401/403 becomes an `isError` result whose text
  begins `REFUSED`, naming the resource and the required mode (computed by the server's own
  `RequiredAccess` table) — never an empty success, never a retry with other credentials.
- Two launch shapes: the shaded **bridge jar** (`cistern-mcp-<version>-bridge.jar`) a
  desktop client launches against a running pod, and `cistern.mcp.enabled=true`, which makes
  cistern-app serve MCP on its own stdio (with the `mcp-stdio` profile keeping stdout
  protocol-clean). Claude Desktop configuration and the four-beat walkthrough:
  `docs/demo/claude-desktop.md`.

## [0.1.0] - 2026-08-19

First tagged release: everything on `main` from the scaffold (2026-07-17) to the production
posture (T7.7, #122). It is a **usable multi-pod server with real authentication**: humans
authenticate through your own OIDC issuer and applications as service principals with hashed
credentials (or the owner uses a local bearer token on a private network), Web Access Control
is enforced on every request, grants are authored with one command and revoked on the very
next request, and every allow and deny leaves a receipt. It is *not yet* a Solid-OIDC/DPoP
server — interop with Solid identity providers is Phase 4 proper — and although this release
ships the production tooling ADR 0002 requires, the private-network posture remains the
default (see *Known limitations*).

### Added

**Core (`cistern-core`)**

- Resource model and the storage SPI (`ResourceStore`), with the shared
  `ResourceStoreContractTest` kit every backend must pass and an in-memory reference
  implementation (#50).
- RDF I/O: Turtle and JSON-LD parse/serialize with base-URI resolution, over Jena (#51).
- Containment: `ldp:contains` is derived from storage, never stored; client attempts to write
  server-managed triples are rejected (#52).
- N3 Patch engine — `solid:inserts` / `solid:deletes` / `solid:where` (#56); resource kind
  (container vs document) is a core-carried fact rather than inferred from the path (#70).
- Ground-rule-7 retrofit: per-module message catalogues, an RDF media-type enum, a sealed
  `CisternException` hierarchy, one interface-metadata writer (#74).

**Storage (`cistern-storage-file`)**

- File-per-resource backend with metadata sidecars and crash-safe writes
  (temp file + `ATOMIC_MOVE`); passes the contract kit (#53).

**HTTP (`cistern-webflux`, Spring WebFlux, fully reactive)**

- `GET`/`HEAD` with content negotiation and LDP headers; `HEAD` = `GET` minus body (#62).
- `PUT` create/replace, intermediate containers created on demand, trailing-slash
  container semantics (#66).
- `POST` to a container: sanitised `Slug`, non-overwriting name collisions, `Link`
  interaction model (#68).
- `DELETE`: `204`; `409` on a non-empty container; `405` for the storage root (#63).
- Conditional requests: `If-Match` / `If-None-Match`, `412` decided before any write, `304`
  on reads (#69).
- `PATCH` with N3 Patch over HTTP (#70).
- `OPTIONS` and CORS: `Allow` / `Accept-*` from one table; open CORS with the request origin
  echoed (Solid Protocol §8.1), narrowable with `cistern.cors.*` (#72).
- Discovery: a storage description resource, `pim:Storage` on the root,
  `solid:storageDescription` link on every resource (#73).
- One RFC 9457 error mapper — every error is `application/problem+json`; handlers never
  speak status codes (#61).
- `cistern.base-url` mints every identifier (configured, never taken from `Host`), so
  identifiers are stable behind a proxy and cannot be poisoned by a forged header.

**Access control (`cistern-wac`, enforced in `cistern-webflux`)**

- WAC engine evaluating `acl:Authorization` graphs, deny by default; `acl:Append` is a
  subset of `acl:Write`; `acl:Control` implies nothing else (#84).
- ACL discovery: the resource's own ACL, else the nearest ancestor's `acl:default`, stopping
  at the storage root — and **failing closed**: an ACL that exists but does not parse denies,
  an empty ACL still terminates the walk (#85).
- Enforcement as a `WebFilter` ahead of every handler, so deny-by-default is structural:
  `401` + `WWW-Authenticate: Bearer realm="cistern"` when no identity was proved, `403` when
  an authenticated agent lacks the mode, `WAC-Allow` on `GET`/`HEAD`. `DELETE` requires
  `Write` on the parent container as well; `POST`/`PATCH` require `Append` (#86).
- Owner seeding: setting `cistern.owner.web-id` turns enforcement on and seeds the root ACL
  (`acl:accessTo` and `acl:default`, never overwritten) granting that WebID full access.
  `cistern.owner.token` adds the local bearer way in (`Authorization: Bearer <token>`) — one
  of the three credential shapes below, and unset in production (#86, refined by #122).

**Authentication (T4.0, #88, PR #110 — `cistern-auth`)**

- One resolver seam, three credential shapes: `ChainedPrincipalResolver` (first authenticated
  resolver wins) chains the OIDC/JWT resolver, the service-principal registry and the owner's
  local token, with anonymous as the fallthrough. The enforcement path is untouched — WAC
  does not care how a WebID was proved.
- `OidcJwtPrincipalResolver` (Nimbus): bring your own issuer. `iss` is compared verbatim to
  `cistern.auth.oidc.issuer`, `aud` must contain a configured audience, keys come from
  `.well-known/openid-configuration` (or a pinned `jwks-uri`), `exp`/`nbf` honour a
  configurable clock skew, and the WebID is read from a claim (`webid-claim`) or minted from
  a template (`webid-template`). Fixtures captured from a real IdP, never invented.
- `ServicePrincipalRegistry`: an application is its own principal —
  `cistern.auth.service-principals[n].web-id` + `.credential-hash` (`sha256:<hex>` at rest),
  no issuer required for machine clients.

**Provisioning, grants and the `cistern` CLI (T5.6, #90, PR #113 · T5.7, #91, PR #111)**

- Multi-pod provisioning, idempotent on every restart: `cistern.pods.seed[n]` provisions pods
  at boot (never overwrites an existing ACL; a malformed or duplicate root is refused at bind
  time), `PodProvisioner` does the same for embedders, and `cistern pod create --root
  </firms/acme/> --owner <webid>` does it over HTTP under the caller's credential — the
  server enforces Write and Control where the root goes.
- Grant authoring that cannot lock the owner out: `GrantService` and `cistern grant
  <webid|public> --read|--write|--append|--control <path>` write `<path>.acl` re-stating
  whoever holds Control there today; `cistern revoke` takes back everything a grantee was
  given and refuses to remove an authorization that grants Control. Exit codes: 0 ok,
  1 failure, 2 refused (the server decides, not the tool), 3 conflict (the ACL changed
  underneath; nothing written).
- The CLI is a shaded executable jar (`cistern-cli-<version>.jar`, picocli, no Spring) with a
  `bin/cistern` wrapper.

**Decision log and receipts (T5.9, #93, PR #114)**

- Every authorization decision — allow and deny, every method — leaves one `DecisionRecord`
  naming the agent, the target, the mode required, the outcome and, when allowed, the ACL
  that granted it; the record is written before the response is sent. `AccessDecision`
  carries `decidedBy` and the matched authorization IRIs. JSON Lines, one file per UTC day
  under `<storage root>/.cistern/decisions/`, written through the storage SPI but outside
  the pod's URI space (never listed, no HTTP path reaches it). `GET <resource>?receipts[&from&to]`
  returns them as `application/x-ndjson` to a holder of Control on the resource;
  `GET /?receipts&agent=<webid>` is the owner's per-agent query. `X-Request-Id` is honoured
  or minted and echoed on every response. `cistern.audit.required` (default `false`) makes
  an unrecordable decision fail closed with 503 (`CisternException.ServiceUnavailable`); by
  default the outcome stands and the failure is logged. `k8s/demo.sh`'s sixth beat is the
  receipt.

**Packaging & operations**

- Multi-stage `Dockerfile` (non-root uid 10001, `/data` volume, TCP healthcheck) and a
  `docker-compose.yml` bound to `127.0.0.1:3737` (#78, #80).
- Kubernetes manifests for a local cluster (`k8s/`): `restricted` Pod Security Standard,
  RWO PVC, single replica with `Recreate`, `ClusterIP` only, deny-all NetworkPolicy; the
  owner credential from a Secret; `k8s/demo.sh` shows a scoped grant and live revocation
  (#79, #86, #87).
- Integration kit (`integration-kit/`, T7.10, #102, PR #108): Cistern + Keycloak — a real
  OIDC issuer with a ready realm (two humans, two service principals, audience `cistern`) —
  plus a seeded pod and a sample app performing the allow/refuse sequence, in one
  `docker compose up`; loopback only.
- CI: build + full test suite on every PR; the Solid conformance harness as a report-only job
  with honest numbers (#77); the Dockerfile built on every PR; k8s manifests and Terraform
  validated without credentials.
- Release pipeline (T7.14, #107, PR #109): a `v*` tag runs the full suite, builds the image
  natively per architecture, and publishes the multi-arch image to GHCR and a GitHub Release
  whose body is this section — carrying `cistern-app-<version>.jar`, the `cistern` CLI
  (`cistern-cli-<version>.jar` and the `bin/cistern` wrapper, smoke-run before either can
  become an asset) and `SHA256SUMS` over all three. `RELEASE.md` is the runbook: the gate,
  the `workflow_dispatch` rehearsal (publishes nothing), the tag commands, the post-release
  stranger test.

**Production posture (T7.7, #94, PR #122)**

- **Enforcement guard.** A credential source — `cistern.auth.oidc.issuer` or
  `cistern.auth.service-principals[]` — configured without `cistern.owner.web-id` refuses
  to start at bind time (`ENFORCEMENT_REQUIRES_OWNER`, naming the fix): enforcement is keyed
  on the owner, so the credentials would never have been asked for and the pod would have
  been open while its configuration read as locked. The owner's WebID and token do different
  jobs: the WebID alone turns enforcement on and seeds the root ACL (`Owner.isNamed()`); the
  token only adds the local bearer resolver (`Owner.hasLocalCredential()`) — the production
  shape is WebID set, token unset, owner authenticating via OIDC or a hashed service
  credential. Nothing configured still starts and warns (`NO_OWNER_CONFIGURED`: enforcement
  is off); an owner named with no way to authenticate starts enforced and warns
  `ENFORCEMENT_WITHOUT_CREDENTIAL`.
- **ADR 0002 supersedes ADR 0001:** an instance may face the internet under eight conditions
  (TLS in front; `cistern.owner.web-id` set; `cistern.owner.token` unset; per-tenant
  isolation; backups drilled; `X-Request-Id` across the edge; rate limiting at the edge;
  `cistern.audit.required=true`). `infra/terraform` becomes a deployable GCP path: a COS
  instance with no external IP and Cloud NAT egress, a global HTTPS load balancer with a
  Google-managed certificate on 443 only, a firewall admitting only the load balancer's and
  IAP's ranges, optional Cloud Armor per-IP rate limiting, the image from GHCR by pinned tag
  (`:latest` refused), the authentication environment from Secret Manager (never in tfvars
  or state; `CISTERN_OWNER_TOKEN` refused by the startup script), a daily disk-snapshot
  policy, and `outputs.tf` with the base URL. `k8s/` becomes `base` + `overlays/local` (the
  former manifests, object-for-object) + `overlays/production` (Ingress with TLS,
  ingress-only NetworkPolicy, resource limits, `cistern-auth` Secret with no owner token,
  one replica); `.github/workflows/k8s.yml` validates both and holds each to its posture.
  `docs/deploy.md` rewritten for the production topology, backups (the whole storage root
  including `.cistern/`; the base URL is part of the data) and **`infra/restore-drill.sh`**
  — snapshot → new volume → boot → smoke (anonymous 401, owner 200, root ACL byte-identical,
  receipts carried over and live), run locally and transcribed. `docs/INTEGRATION.md` step 8
  and the Dockerfile header describe the server as built.

**Documentation**

- Architecture, backlog mirrored to GitHub issues, ADR 0001 (local-only until the authority
  plane exists; superseded in this release by ADR 0002, above), deployment notes, the
  flagship demo drafted before it was built (#83).
- Integration architecture and application playbook — `docs/INTEGRATION.md`: how an
  application integrates, the derived-data rule, configuration reference, status-code
  contract (#97); who holds the data and where, and the honest integration-effort table
  (§2a/§2b, #105).

**Build**

- Java 25 baseline; Temurin 25 images (#98). Spring Boot 4.1.0, Spring Framework 7,
  Jena 6.1.0 (#59). Dependency audit against Maven Central (#47).

### Fixed

- `ResourceIdentifier.parent()` no longer throws on percent-encoded paths (#55).
- WAC: an inherited `acl:default` authorization is matched against the container the ACL is
  attached to, not the requested child — the previous code silently denied every inherited
  grant (#85).
- Test generator initialises Jena before touching `TypeMapper` (#99).
- **WAC: an ACL resource requires `acl:Control` on the resource it governs, for every method
  (#112, PR #115).** `RequiredAccess` mapped `.acl` resources by HTTP method like any other
  resource, so after a public (or per-agent) Read grant on a container, `GET <container>.acl`
  returned 200 to anyone the grant covered — disclosing who holds access — and Write on the
  container was enough to replace or delete its ACL. Now any request addressed to `<x>.acl`
  requires Control on `<x>` (anonymous 401, authenticated without Control 403), judged by
  `<x>`'s effective ACL, which is what WAC's "Control on the resource" means. The receipt for
  an ACL access is recorded on the governed resource with `required: CONTROL`.
- **Stale "no authentication, no access control" compose comments (#123).** `docker-compose.yml`
  and `integration-kit/docker-compose.yml` now state the ADR 0002 posture: WAC is enforced the
  moment an owner is configured; local/private-network only until the production posture is
  applied. The kit's commented service-principal examples now use the Spring relaxed-binding
  canonical env names (`CISTERN_AUTH_SERVICEPRINCIPALS_0_WEBID` / `_CREDENTIALHASH` — the old
  `SERVICE_PRINCIPALS_…_WEB_ID` spelling silently fails to bind), and `docs/INTEGRATION.md` §7
  states the env form for list properties.

### Known limitations (read before deploying)

- **Not yet Solid-OIDC / DPoP.** T4.0 brings real authentication — JWTs from your own OIDC
  issuer, hashed service principals — but not the Solid-OIDC profile (T4.1–T4.4): no DPoP
  sender-constraining, no interop with Solid identity providers yet. That is also why
  **conformance stands at 0 passed / 0 failed / 41 untested** (`cth/BASELINE.md`): the
  harness registers its test users against a Solid-OIDC provider before it exercises any
  feature. The numbers only move forward from here.
- **No time-limited grants.** WAC has no expiry; `cistern:validUntil` (fail-closed) is #92.
  Until then revocation is the tool — it takes effect on the very next request.
- **No MCP front-end yet.** The agent door is Phase 6; `cistern-mcp` is a module shell in
  this release, and `cistern-spring-boot-starter` is likewise scaffold.
- **File backend only.** Keep `cistern.storage.root` on a real POSIX filesystem: the
  crash-safety guarantee is atomic rename, which gcsfuse-style mounts do not provide. The
  backend is single-writer — one replica. An object-native backend is #95.
- **Production tooling is shipped and validated, not yet operated.** The ADR 0002 path —
  the TLS Terraform topology, the k8s production overlay, backups and the restore drill —
  ships in this release and was validated in #122, but EnrichMeAI has not operated an
  internet-facing instance: the first production deployment is the consumer's, following
  `docs/deploy.md` and ADR 0002's eight conditions. The private-network posture (loopback
  compose, local overlay) remains the default.

### Upgrade notes

There is nothing to upgrade *from*; this section records what a deployment of 0.1.0
depends on so that later releases can state precisely what changed.

**Configuration keys as of 0.1.0.** Spring relaxed binding: `cistern.owner.web-id` is
`CISTERN_OWNER_WEBID` in the environment; list properties put the index inline —
`cistern.auth.service-principals[0].web-id` is `CISTERN_AUTH_SERVICEPRINCIPALS_0_WEBID`,
never `CISTERN_AUTH_SERVICE_PRINCIPALS_0_WEB_ID` (which silently does not bind).

| Property | Environment | Default | Meaning |
|---|---|---|---|
| `server.port` | `SERVER_PORT` | `3000` | HTTP port inside the process/container; compose and k8s publish it as 3737 |
| `cistern.base-url` | `CISTERN_BASE_URL` | `http://localhost:3000` | the origin every resource identifier is minted under — must be the URL clients actually call |
| `cistern.storage.root` | `CISTERN_STORAGE_ROOT` | `./data` (`/data` in the image) | file backend root; back this directory up, including `.cistern/` |
| `cistern.owner.web-id` | `CISTERN_OWNER_WEBID` | unset | the pod owner's WebID; **setting it turns enforcement on** and seeds the root ACL |
| `cistern.owner.token` | `CISTERN_OWNER_TOKEN` | unset | the owner's local bearer secret — optional, private network only, **unset in production** (ADR 0002) |
| `cistern.auth.oidc.issuer` | `CISTERN_AUTH_OIDC_ISSUER` | unset | trusted OIDC issuer, compared verbatim to `iss`; **setting it enables the JWT resolver** |
| `cistern.auth.oidc.audiences` | `CISTERN_AUTH_OIDC_AUDIENCES` | — (required with issuer) | `aud` must contain one of these |
| `cistern.auth.oidc.webid-claim` / `.webid-template` | `CISTERN_AUTH_OIDC_WEBIDCLAIM` / `_WEBIDTEMPLATE` | `webid` / — | how a token names a WebID; one or the other |
| `cistern.auth.oidc.clock-skew` | `CISTERN_AUTH_OIDC_CLOCKSKEW` | `60s` | tolerance on `exp`/`nbf` |
| `cistern.auth.oidc.jwks-uri` | `CISTERN_AUTH_OIDC_JWKSURI` | discovered | where the keys are, if not via `.well-known/openid-configuration` |
| `cistern.auth.service-principals[n].web-id` / `.credential-hash` | `CISTERN_AUTH_SERVICEPRINCIPALS_n_WEBID` / `_CREDENTIALHASH` | unset | an application as its own principal; hash is `sha256:<hex>` |
| `cistern.pods.seed[n].root` / `.owner-web-id` | `CISTERN_PODS_SEED_n_ROOT` / `_OWNERWEBID` | unset | a pod provisioned at boot; idempotent, never overwrites; malformed or duplicate roots refused at bind time |
| `cistern.audit.required` | `CISTERN_AUDIT_REQUIRED` | `false` | `true` ⇒ a decision the log cannot record is not acted on: 503, retry later |
| `cistern.audit.root` | `CISTERN_AUDIT_ROOT` | `<cistern.storage.root>/.cistern` | directory of the JSON Lines decision log (`decisions/YYYY-MM-DD.jsonl`); never pod content |
| `cistern.cors.allowed-origins` | `CISTERN_CORS_ALLOWEDORIGINS` | `*` | origins allowed to read the pod from a browser (patterns; the matching origin is echoed) |
| `cistern.cors.max-age` | `CISTERN_CORS_MAXAGE` | `1h` | preflight cache lifetime |

- **Unset owner = no authorization layer.** A server started without `cistern.owner.web-id`
  serves everything to everyone and says so at `WARN` on boot. Every quickstart in the
  README sets both owner keys.
- **The enforcement guard refuses half-locked configurations** (#122): any `cistern.auth.*`
  credential source configured without `cistern.owner.web-id` is a start-up refusal
  (`ENFORCEMENT_REQUIRES_OWNER`). The production shape is WebID set, `cistern.owner.token`
  unset — the owner authenticates through the issuer or a hashed service-principal entry
  carrying the owner's WebID (ADR 0002).
- **Data layout is the file backend's** — one file per resource plus a metadata sidecar under
  `cistern.storage.root`, and the decision log under `.cistern/`. Later releases that change
  the layout will ship a migration note in this section; until then a backup is a copy of
  that directory, `.cistern/` included (the receipts are the audit trail).
- **Image contract:** runs as uid 10001, listens on 3000, `/data` is the volume; a *named*
  volume inherits the right ownership, a bind mount keeps the host's. Pin `0.1.0` (or the
  digest); `latest` moves with every non-prerelease tag.
- **Jars:** `cistern-app-0.1.0.jar` is a Spring Boot fat jar — `java -jar` with the same
  environment variables. `cistern-cli-0.1.0.jar` is the CLI — `java -jar` directly, or via
  the `cistern` wrapper script from the same Release. Java 25 required for both.

[Unreleased]: https://github.com/enrichmeai/cistern/compare/v0.3.0...HEAD
[0.3.0]: https://github.com/enrichmeai/cistern/releases/tag/v0.3.0
[0.2.0]: https://github.com/enrichmeai/cistern/releases/tag/v0.2.0
[0.1.0]: https://github.com/enrichmeai/cistern/releases/tag/v0.1.0
