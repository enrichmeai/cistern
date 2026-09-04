# Reviewer lens — the good-spine checklist

Verdict: **passes on breadth and on brownfield fidelity; two gaps, one of them shared with
the adversarial lens.**

| Criterion | Verdict |
|---|---|
| Fixes the real divergence points for the level below | Yes — the three live ones (client duplication, backend edge, inert `client`) all land as ADs rather than prose. |
| Every Rule enforceable, and prevents its stated divergence | Yes except **AD-15**, whose rule as written makes AD-16(1) unsatisfiable (see adversarial finding 1), and **AD-9**, which leaves the embedded shape open (finding 2). |
| Nothing under Deferred could let two units diverge | Mostly. "ACP evaluator scheduling" is safe because AD-14/15/16 fix its rules first. But `cistern-client-java`'s own dependency shape is neither decided nor deferred — the dependency diagram asserts an edge to `cistern-core` that no ticket or code decides. See below. |
| Named tech verified-current | Sourced from the POM; currency caveat belongs in the table (versions lens). |
| Ratifies rather than contradicts the brownfield codebase | Yes, and corrects the doc where the code is stronger: ARCHITECTURE.md decision 6 ("calls the same LdpService") is replaced by the HTTP-only rule the code actually enforces. `WacEnforcer` — a name in the docs and in no source file — is dropped. |
| Covers the driving inputs' scope | Yes: the seven load-bearing decisions, the non-goals list, ADR 0002's eight conditions, CLAUDE.md's ground rules and code-quality bar, and the delegation note's three decisions. |
| Every dimension the altitude owns is decided, deferred, or open | Yes. The operational/environmental envelope — the dimension this checklist singles out — is AD-19 plus the deployment diagram, not silence. |

## Gap 1 — an edge asserted, not decided

The dependency diagram draws `cistern-client-java --> cistern-core`. Nothing decides this.
T7.9 (#101) says only "thin Java, no Spring". Two builders diverge immediately:

- one depends on `cistern-core` to reuse `ResourceIdentifier`, `EntityTag` and the
  `CisternException` taxonomy — and inherits Jena, which is not thin;
- one builds standalone so the Java and TypeScript clients stay mirror images.

Either is defensible; asserting one in a diagram without an AD is the weakest option.

## Gap 2 — testing strategy is distributed rather than stated

`StepVerifier`/`WebTestClient` (AD-12), the contract kit (AD-3), through-the-chain and
binding tests (AD-18), and real-captured fixtures (conventions) are each present, but a
reader looking for "how is this project tested" has to assemble it from four places. Not a
divergence risk — no two units can build incompatibly because of it — so this is a note,
not a finding to fix.
