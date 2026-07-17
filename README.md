# Cistern

**An open, self-hostable Solid pod server for the AI era.** JVM-native (Spring Boot 3 /
WebFlux), conformance-first, and MCP-fronted — so any AI agent (Claude, ChatGPT, your
in-house bot) can read and write user-owned data *with the user's consent model enforced
by the server, not promised by the vendor*.

> Your agent's memory. Your pod. Your Cistern.

## Why

AI assistants are accumulating the deepest personal profiles ever built — locked inside
each vendor. [Solid](https://solidproject.org) solved the hard parts (identity, storage,
consent) years ago; what it lacked was demand. The AI era supplies the demand, and
[MCP](https://modelcontextprotocol.io) supplies the protocol agents actually speak.
Cistern joins the two: a spec-conformant Solid server whose flagship interface is an MCP
front-end with Web Access Control enforced underneath.

Commercial personal-AI vaults are arriving closed and top-down. Cistern is the open
infrastructure version: Apache 2.0, self-hostable, bring-your-own agent and identity
provider.

## Status

Pre-0.1, built in the open. Conformance against the official
[Solid test harness](https://github.com/solid-contrib/conformance-test-harness) is the
project's public health metric — numbers only move forward (see `cth/BASELINE.md`).
Roadmap: [docs/BACKLOG.md](docs/BACKLOG.md) · Architecture:
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)

## Quickstart (dev)

```bash
mvn -q verify
mvn -q -pl cistern-app spring-boot:run     # pod server on http://localhost:3000
./cth/run-cth.sh                           # run the conformance harness (Docker)
```

## Modules

`cistern-core` (LDP semantics, storage SPI) · `cistern-storage-file` · `cistern-webflux`
(HTTP) · `cistern-auth` (Solid-OIDC + DPoP validation) · `cistern-wac` (Web Access
Control) · `cistern-mcp` (the agent front-end) · `cistern-spring-boot-starter` ·
`cistern-app`

## Licence & governance

Apache License 2.0. Copyright © Good Shepherd Software Consultancy Ltd (Company
No. 09702990), trading as **EnrichMeAI**. Contributions require DCO sign-off — see
[CONTRIBUTING.md](CONTRIBUTING.md). Enterprise support: [COMMERCIAL.md](COMMERCIAL.md).
