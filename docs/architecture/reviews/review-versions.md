# Reviewer lens — was every committed decision reality-checked, or asserted from training data?

Verdict: **sourced, with one currency caveat that belongs in the spine rather than hidden.**

## Checked against the repository, not recall

| Claim | Source of truth | Result |
|---|---|---|
| Java 25, Boot 4.1.0, Jena 6.1.0, nimbus 10.9.1, titanium 1.7.0, MCP SDK 2.0.0, picocli 4.7.7 | `pom.xml` `<properties>` | matches exactly |
| CTH baseline 0 / 0 / 41, halt in PREPARE SERVER on missing DPoP `ath` | `cth/BASELINE.md` | matches |
| `Agent(Optional<URI> webId, Optional<URI> client)` | `cistern-core/.../Agent.java` | matches; ruled 2026-08-23 |
| Issue #89 closed | `gh issue view 89` | CLOSED 2026-08-25 |
| #95 object storage, #101 client SDKs, #132 streamable HTTP | `gh issue view` | all three OPEN, titles match |
| `cistern-auth` → `cistern-webflux` | both POMs | confirmed, direction as stated |
| `cistern-webflux` → `cistern-storage-file` | `cistern-webflux/pom.xml` + its own comment | confirmed, including that the comment concedes T7.1 |
| Seven duplicated classes across mcp and cli | `wc -l` on both trees | confirmed, already drifted |
| `cistern-core` Spring-free | `cistern-core/pom.xml` | confirmed: jena-arq + reactor-core only |

## Corrected during the run rather than carried forward

- `docs/BACKLOG.md` T0.2 still says "Spring Boot 3.5.x line"; the POM moved to 4.1.0 and
  records why (3.5 OSS support ended 2026-06-30 at 3.5.16). The stale text is in the
  backlog, not the spine.
- A stored memory saying two principal-shape decisions are open on #89 is stale.
- `WacEnforcer` appears in `docs/ARCHITECTURE.md` twice and `docs/BACKLOG.md:376` and
  exists in no source file. The spine names `WacEngine` + `AuthorizationFilter`.

## Caveat the spine must state rather than imply

The versions are **the repository's audited set, dated 2026-07-20 (#58)** — six weeks old at
authoring, and not re-audited against Maven Central during this run. For a brownfield Stack
table that is the correct source (it describes what is built, not what to adopt), but the
table should say so rather than read as a currency claim.
