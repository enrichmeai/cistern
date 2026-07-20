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

## Build & run

```bash
mvn -q verify                              # full build + unit tests
mvn -q -pl cistern-core -am test           # one module
mvn -q -pl cistern-app spring-boot:run     # server on :3000 (storage in ./data)
./cth/run-cth.sh                           # conformance harness against localhost:3000 (Docker)
```

Java 21 (SDKMAN), Maven 3.9. No Gradle. No Lombok — use records.

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

## Spec sources (read these, not summaries)

- Solid Protocol: https://solidproject.org/TR/protocol
- Solid-OIDC: https://solidproject.org/TR/oidc  · DPoP: RFC 9449
- WAC: https://solidproject.org/TR/wac
- CTH + tests: https://github.com/solid-contrib/conformance-test-harness ·
  https://github.com/solid-contrib/specification-tests
- MCP: https://modelcontextprotocol.io/specification · Java SDK: https://github.com/modelcontextprotocol/java-sdk
- Reference implementation for behaviour comparison (never copy code — AGPL-adjacent
  licences and we are Apache 2.0; observe wire behaviour only): CommunitySolidServer
