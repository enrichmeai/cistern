# Changelog

All notable changes to Cistern are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow
[Semantic Versioning](https://semver.org/) — pre-1.0, minor versions may break.

A release is a `v<version>` tag on `main`. Pushing it runs `.github/workflows/release.yml`,
which publishes `ghcr.io/enrichmeai/cistern:<version>` (linux/amd64 + linux/arm64) and a
GitHub Release carrying `cistern-app-<version>.jar` and `SHA256SUMS`, with this file's
matching section as the release body. **A tag without a section here fails the release**
before anything is built, deliberately.

## [Unreleased]

### Added

- **Decision log and receipts (T5.9, #93).** Every authorization decision — allow and deny,
  every method — leaves one `DecisionRecord` naming the agent, the target, the mode required,
  the outcome and, when allowed, the ACL that granted it; the record is written before the
  response is sent. `AccessDecision` now carries `decidedBy` and the matched authorization
  IRIs. JSON Lines, one file per UTC day under `<storage root>/.cistern/decisions/`, written
  through the storage SPI but outside the pod's URI space (never listed, no HTTP path reaches
  it). `GET <resource>?receipts[&from&to]` returns them as `application/x-ndjson` to a holder
  of Control on the resource; `GET /?receipts&agent=<webid>` is the owner's per-agent query.
  `X-Request-Id` is honoured or minted and echoed on every response. `cistern.audit.required`
  (default `false`) makes an unrecordable decision fail closed with 503 (new
  `CisternException.ServiceUnavailable`); by default the outcome stands and the failure is
  logged. `k8s/demo.sh` gains a sixth beat: the receipt.

### Fixed

- **WAC: an ACL resource requires `acl:Control` on the resource it governs, for every method
  (#112).** `RequiredAccess` mapped `.acl` resources by HTTP method like any other resource, so
  after a public (or per-agent) Read grant on a container, `GET <container>.acl` returned 200
  to anyone the grant covered — disclosing who holds access — and Write on the container was
  enough to replace or delete its ACL. Now any request addressed to `<x>.acl` requires Control
  on `<x>` (anonymous 401, authenticated without Control 403), judged by `<x>`'s effective ACL,
  which is what WAC's "Control on the resource" means. The receipt for an ACL access is recorded
  on the governed resource with `required: CONTROL`.

## [0.1.0] - 2026-08-19

First tagged release: everything built between the scaffold (2026-07-17) and the Java 25
baseline (#98). It is a **usable single-owner pod server on a private network** — the owner
authenticates with a bearer secret, Web Access Control is enforced on every request, and
anyone without a grant gets `401`/`403`. It is *not yet* a Solid-OIDC server and must not be
put on a public address (see *Known limitations* and ADR 0001).

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
- Owner seeding: setting `cistern.owner.web-id` + `cistern.owner.token` turns enforcement on
  and seeds the root ACL (`acl:accessTo` and `acl:default`, never overwritten) granting that
  WebID full access; the owner authenticates with `Authorization: Bearer <token>` (#86).

**Packaging & operations**

- Multi-stage `Dockerfile` (non-root uid 10001, `/data` volume, TCP healthcheck) and a
  `docker-compose.yml` bound to `127.0.0.1:3737` (#78, #80).
- Kubernetes manifests for a local cluster (`k8s/`): `restricted` Pod Security Standard,
  RWO PVC, single replica with `Recreate`, `ClusterIP` only, deny-all NetworkPolicy; the
  owner credential from a Secret; `k8s/demo.sh` shows a scoped grant and live revocation
  (#79, #86, #87).
- Terraform for a GCP test pod — authored, deliberately **not applied** (ADR 0001) (#78).
- CI: build + full test suite on every PR; the Solid conformance harness as a report-only job
  with honest numbers (#77); the Dockerfile built on every PR; k8s manifests and Terraform
  validated without credentials.
- Release pipeline: a `v*` tag publishes the multi-arch image to GHCR and a GitHub Release
  with the jar and checksums (#107).

**Documentation**

- Architecture, backlog mirrored to GitHub issues, ADR 0001 (local-only until the authority
  plane exists), deployment notes, the flagship demo drafted before it was built (#83).
- Integration architecture and application playbook — `docs/INTEGRATION.md`: how an
  application integrates, the derived-data rule, configuration reference, status-code
  contract (#97).

**Build**

- Java 25 baseline; Temurin 25 images (#98). Spring Boot 4.1.0, Spring Framework 7,
  Jena 6.1.0 (#59). Dependency audit against Maven Central (#47).

### Fixed

- `ResourceIdentifier.parent()` no longer throws on percent-encoded paths (#55).
- WAC: an inherited `acl:default` authorization is matched against the container the ACL is
  attached to, not the requested child — the previous code silently denied every inherited
  grant (#85).
- Test generator initialises Jena before touching `TypeMapper` (#99).

### Known limitations (read before deploying)

- **No Solid-OIDC / DPoP yet** — the only credential is the owner's shared bearer secret.
  Run it on loopback or a private network only (ADR 0001); TLS and real authentication are
  T4.0 (#88) and T7.7 (#94). `cistern-auth`, `cistern-mcp` and `cistern-spring-boot-starter`
  are module shells with no code in this release.
- **One owner, one storage root.** Multi-pod provisioning (#90), grant authoring (#91) and
  the decision log (#93) are not built.
- **File backend only.** Keep `cistern.storage.root` on a real POSIX filesystem: the
  crash-safety guarantee is atomic rename, which gcsfuse-style mounts do not provide.
- **Conformance: 0 passed / 0 failed / 41 untested** (`cth/BASELINE.md`). The harness
  registers two authenticated test users before running any feature, which needs Phase 4
  authentication (#88) and multi-pod provisioning (#90); the numbers only move forward from
  there.

### Upgrade notes

There is nothing to upgrade *from*; this section records what a deployment of 0.1.0
depends on so that later releases can state precisely what changed.

**Configuration keys as of 0.1.0** (Spring relaxed binding: `cistern.owner.web-id` is also
`CISTERN_OWNER_WEBID` in the environment):

| Property | Environment | Default | Meaning |
|---|---|---|---|
| `server.port` | `SERVER_PORT` | `3000` | HTTP port inside the process/container; compose and k8s publish it as 3737 |
| `cistern.base-url` | `CISTERN_BASE_URL` | `http://localhost:3000` | the origin every resource identifier is minted under — must be the URL clients actually call |
| `cistern.storage.root` | `CISTERN_STORAGE_ROOT` | `./data` (`/data` in the image) | file backend root; back this directory up |
| `cistern.owner.web-id` | `CISTERN_OWNER_WEBID` | unset | the pod owner's WebID; **setting it turns enforcement on** and seeds the root ACL |
| `cistern.owner.token` | `CISTERN_OWNER_TOKEN` | unset | the owner's bearer secret; both halves are required for the owner to authenticate |
| `cistern.cors.allowed-origins` | `CISTERN_CORS_ALLOWEDORIGINS` | `*` | origins allowed to read the pod from a browser (patterns; the matching origin is echoed) |
| `cistern.cors.max-age` | `CISTERN_CORS_MAXAGE` | `1h` | preflight cache lifetime |

- **Unset owner = no authorization layer.** A server started without `cistern.owner.web-id`
  serves everything to everyone and says so at `WARN` on boot. Every quickstart in the
  README sets both owner keys.
- **`cistern.auth.*` does not exist yet.** It arrives with T4.0 (#88) — a pluggable
  `PrincipalResolver` chain, OIDC/JWT validation and service principals — and will be listed
  here with its defaults when it does. Nothing in 0.1.0 needs to change to prepare for it;
  the owner secret remains valid alongside it for local use.
- **Data layout is the file backend's** — one file per resource plus a metadata sidecar under
  `cistern.storage.root`. Later releases that change the layout will ship a migration note in
  this section; until then a backup is a copy of that directory.
- **Image contract:** runs as uid 10001, listens on 3000, `/data` is the volume; a *named*
  volume inherits the right ownership, a bind mount keeps the host's. Pin `0.1.0` (or the
  digest); `latest` moves with every non-prerelease tag.
- **Jar:** `cistern-app-0.1.0.jar` is a Spring Boot fat jar; `java -jar` with the same
  environment variables. Java 25 required.

[Unreleased]: https://github.com/enrichmeai/cistern/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/enrichmeai/cistern/releases/tag/v0.1.0
