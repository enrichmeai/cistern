# Cistern architecture

> This document is the *what*. `docs/STRATEGY.md` is the *why* — why Cistern is an
> authority layer for agents over user-owned data rather than a Solid server that speaks
> MCP, and why we are not building a framework. `docs/BACKLOG.md` is the *when*.

## The shape

```
                    AI agents (Claude, ChatGPT, in-house bots)
                                   │  MCP (stdio / streamable HTTP)
                                   ▼
   Browsers / Solid apps      cistern-mcp ──┐
            │ HTTP                          │ same internal API, same WAC
            ▼                               ▼
   cistern-webflux  ──►  auth filter (cistern-auth: Solid-OIDC + DPoP validation)
            │                               │
            ▼                               ▼
       LdpService (core)  ◄──  WacEnforcer (cistern-wac)
            │
            ▼
       ResourceStore SPI  ◄──  cistern-storage-file | (later) r2dbc | in-memory (tests)
```

## Load-bearing decisions

1. **Resource server only (v1).** Cistern validates Solid-OIDC tokens from any IdP; it
   does not issue them. Users bring a WebID. An IdP module is a v2 decision.
2. **The storage SPI deals in representations (bytes + media type), not RDF graphs.**
   Backends stay dumb and swappable; Jena lives only in core's RDF layer. Containment
   triples are derived at read time from `children()`, never stored.
3. **Trailing slash is semantic** (Solid Protocol): `/foo/` container, `/foo` document,
   distinct resources. `ResourceIdentifier.isContainer()` is the single predicate — no
   ad-hoc string checks anywhere else.
4. **ETags are strong and change-on-write**; conditional requests (`If-Match`,
   `If-None-Match: *`) are enforced in the HTTP layer before the store is touched.
5. **Deny by default.** No ACL found on the resource → walk up to the nearest
   `acl:default`. No effective ACL grants the mode → 403 (401 if unauthenticated).
   `acl:Control` is never implied by `acl:Write`.
6. **MCP is a peer front-end, not a bolt-on.** `cistern-mcp` maps an MCP client identity
   to a WebID (static mapping first, token exchange later) and calls the same `LdpService`
   behind the same `WacEnforcer`. There is no privileged internal path.
7. **Conformance is the fitness function.** The CTH runs in CI on every PR; its report is
   published as a build artifact and the pass-count is the project's public health metric.
   Numbers only move forward.

## Deliberate non-goals (v1)

- Solid-OIDC **provider** (issuing tokens) — v2.
- ACP (Access Control Policy) — WAC first; ACP module later (`cistern-acp`).
- Notifications protocol — after milestone 3 (`cistern-notifications`).
- Postgres/R2DBC backend — after the contract kit is stable (`cistern-storage-r2dbc`).
- Multi-pod / multi-tenant management — commercial-track candidate, separate repo.

## Ideas parked

- `docs/ideas/privacy-fuzzing.md` — pluggable "controlled distortion" policy at the pod
  boundary (the trick Inrupt's Charlie markets), as an open, auditable filter.
- `docs/ideas/agent-scoped-delegation.md` — letting a pod owner give an agent *less*
  access than they have themselves, via ACP's `acp:client` plus an intersection cap and
  expiry. **Needs a decision before T4.3 freezes the authenticated principal**, and if
  accepted it changes the reasoning behind parking ACP as a v1 non-goal above.
