# Changelog

All notable changes to Cistern are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow
[Semantic Versioning](https://semver.org/) — pre-1.0, minor versions may break.

A release is a `v<version>` tag on `main`. Pushing it runs `.github/workflows/release.yml`,
which publishes `ghcr.io/enrichmeai/cistern:<version>` (linux/amd64 + linux/arm64) and a
GitHub Release carrying `cistern-app-<version>.jar`, the CLI (`cistern-cli-<version>.jar`
plus its `cistern` wrapper script) and `SHA256SUMS` over all three, with this file's
matching section as the release body. **A tag without a section here fails the release**
before anything is built, deliberately. The procedure around the tag — gate, rehearsal,
stranger test — is [RELEASE.md](RELEASE.md).

## [Unreleased]

<!-- Nothing yet. New entries land here and are folded into the version section when the
     tag is cut — the tag is cut from main, so everything on main belongs to the version
     (RELEASE.md §1). -->

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

[Unreleased]: https://github.com/enrichmeai/cistern/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/enrichmeai/cistern/releases/tag/v0.1.0
