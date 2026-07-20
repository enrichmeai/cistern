# Cistern strategy — the why

Written 2026-07-20. `ARCHITECTURE.md` is the *what*; `BACKLOG.md` is the *when*; this is
the *why*, and specifically why Cistern is not the thing it superficially looks like.

Companion: `docs/ideas/agent-scoped-delegation.md` is the technical form of the central
argument here. The market claims below come from a prior-art survey done 2026-07-20; where
a claim is judgment rather than verified fact it is marked as such.

## The question this answers

> The AI era broke some assumptions in how we build servers. Spring and Quarkus are the
> incumbent JVM frameworks. Can we do better — or should we build something different?

Short answer: **do not build a framework.** The vacancy is not in the application-framework
layer. It is one layer down, in data and authority, and that is where Cistern already sits.

## Why not a framework

Frameworks win on ecosystem and elapsed time, not on design quality. Spring's moat is two
decades of starters, documentation, Stack Overflow answers, and the fact that every
enterprise Java hire already knows it. A technically superior framework with no ecosystem
loses to a mediocre one with a large ecosystem, reliably, and the losing takes years.

Cistern's realistic budget is 15 to 20 focused hours a week, with Valuedocs as priority
one. That budget cannot out-ecosystem Spring. It is, however, entirely sufficient to own a
narrow primitive that no framework provides.

Note also that Cistern is *built on* Spring Boot. This is not a competitive position. It is
a layer above one.

## What actually changed, and who already filled it

Six real shifts in what a server must do once the caller is a model rather than a person:

| Shift | Status |
|---|---|
| Calling an LLM is an ordinary dependency | **Filled.** Spring AI, LangChain4j, Quarkus LangChain4j. Commoditised. |
| Agent loops are long-running and resumable, not request/response | **Filled.** Temporal, Restate. Durable execution is an established category. |
| The unit of API is a tool, not an endpoint | **Filled.** MCP, and it won. |
| Per-call authorization decisions | **Filling.** OpenID AuthZEN's COAZ profile reached WG draft June 2026 — it standardises the wire format between enforcement point and decision point. |
| Tokens and cost as a runtime resource with backpressure | Thin, but this is a library, not a framework, and not a defensible position. |
| **Identity is now (user, agent), not user** | **Vacant for the consumer, owner-authored case.** |

Five of six are occupied by funded incumbents. Entering as a framework means competing on
all five simultaneously. Entering at the sixth means competing with almost nobody.

## The vacancy, precisely

Spring Security models a user. So does the entire OAuth family. There is no principal that
means *"this agent, acting for this person, with strictly less authority than that person
holds."* Verified in the July 2026 survey:

- **MCP considered per-tool scopes and rejected them.** SEP-1880 was closed as not planned.
  The authorization spec's own Scope Selection Strategy tells clients, absent a challenge,
  to request *every* scope in `scopes_supported`, on the reasoning that MCP clients lack
  the domain knowledge to choose. Scope maximisation is the documented default.
- **Its flagship extension goes the other way.** Enterprise-Managed Authorization (stable
  June 2026; Anthropic, Microsoft, Okta) is admin-authored, and its advertised benefit is
  issuing tokens "without additional per-server authorization prompts." The document
  concedes the user-driven model "is ideal" for consumers, then builds for enterprises.
- **Every vendor product is developer- or admin-authored**, enforced at an authorization
  server, revoked by token expiry: Entra Agent ID, Okta, Auth0, Descope, Stytch, WorkOS.
  Microsoft's is the strongest — agent access is capped by the user's own Graph permissions
  — but the scopes are Microsoft-defined (`Mail.Read`). The owner cannot say "only emails
  from my lawyer, from the last 30 days."
- **The one stack that models the client as a first-class principal is Solid ACP**, whose
  `acp:client` matcher does exactly this and is genuinely enforced in Community Solid
  Server's source. It is off by default, has not moved since its 2022 Editor's Draft, is
  not a W3C Recommendation, and has no productised authoring surface.

So: the mechanism exists and is unowned. The primitives are individually mature —
attenuable capability tokens (macaroons, Biscuit), ACP's matcher algebra, Solid-OIDC Client
ID Documents, AuthZEN for per-call decisions, ReBAC engines for datastore-side enforcement.
**Nobody has assembled them behind an owner-facing surface.** The OpenID Foundation's own
2025 whitepaper lists delegated authority as an open problem.

## The reframe

Cistern is not a Solid server that happens to speak MCP.

> **Cistern is the authority layer for agents over user-owned data.** It uses Solid because
> Solid already solved the data model, and MCP because MCP won the tool interface.

This reframing is not cosmetic. It changes four things:

1. **It ends the wrong competitions.** Not against Spring (we use it). Not against Solid
   purists (we do not care about Solid for its own sake — it is the best available
   substrate, and the conformance discipline is how we prove we use it honestly rather
   than a goal in itself).
2. **It promotes `docs/ideas/agent-scoped-delegation.md` from parked idea to product
   thesis.** Owner-authored, strictly-narrowing, instantly revocable agent authority is the
   differentiator. Conformant LDP storage is table stakes underneath it.
3. **It explains ARCHITECTURE decision 6.** MCP is a peer front-end with no privileged
   internal path *because the authority check must be on the data path*. If agents could
   reach storage another way, there would be no product.
4. **It makes conformance-first commercially legible.** "The open one, and provably
   correct" is a defensible slot. "Another agent framework" is not. The CTH pass-count is
   marketing, not just engineering hygiene.

## Positioning, in the order a stranger needs it

1. Agents today act *as you* — they hold your token, so they hold all your authority.
2. The blast radius of a prompt injection is therefore the blast radius of the person.
3. You cannot revoke one agent without revoking yourself, and afterwards there is no record
   of which agent did what.
4. Cistern is a place to keep your data where *you* write the rule — this agent, this
   subset, this long — enforced at the store, revocable instantly, fully audited.
5. Open, self-hostable, any agent. Apache 2.0.

Against Inrupt: **Charlie is the product; Cistern is the infrastructure.** Retain this — it
was right before and the reframe sharpens rather than replaces it.

## Competitive picture

**Inrupt is pitching this exact thesis.** Their Solid-plus-MCP post argues that the current
MCP model "treats AI agents like they're you — full access to everything," and pitches
natural-language, time-bounded grants ("share my driver's license with the rental agency
for the next 48 hours"). That is our argument, nearly verbatim.

Read it two ways, both true. It **validates the thesis** — the incumbent independently
reached the same conclusion. And it is **the clock** — as of the survey it is a prototype
seeking release partners, with no dates, no repos and no GA, exactly the pattern Charlie
followed. Being right does not matter if they ship first and closed.

Broader timing: AuthZEN COAZ reached WG draft in June 2026 and the IETF is scheduled to
convene an `agentproto` working-group-forming BOF on 23 July 2026. The space is officially
pre-charter. That is the cheap moment to be early, and it is closing.

## Honest risks

- **Consent UX is the actual product, and it is not a server problem.** Getting a human to
  author a correct, coherent delegation is where this class of system historically dies —
  it is precisely why UMA 2.0 has had the right model for a decade and near-zero adoption.
  A Turtle file is not an answer for anyone but us. v1 punts to a config file and a
  developer audience; we should be clear-eyed that this leaves the hardest part unsolved,
  and that solving it is a different project with a different skill set.
- **The substrate is stalled.** ACP has not moved since 2022, nothing in the Solid authz
  stack is a W3C Recommendation, and the Linked Web Storage WG's charter runs only to
  September 2026. We inherit standards alignment, not momentum.
- **Owning an unowned niche and owning a *valuable* niche are different claims.** The
  survey establishes the first. The second is a bet, not a finding — user-owned data has
  been "about to matter" for a decade.
- **Execution risk is the dominant risk.** Cistern is at Phase 1 of 7. The strategy above
  is worth nothing until Milestone 3 exists.

## What this does not change

- Valuedocs remains priority one. This document is not permission to expand scope.
- The phase order stands. Nothing here justifies pulling ACP forward wholesale; the only
  near-term ask is the cheap principal-shape decision in
  `docs/ideas/agent-scoped-delegation.md`, taken before T4.3.
- Conformance remains the fitness function. A better story is not a reason to ship a worse
  server, and the CTH pass-count still only moves forward.
