# Idea: the architecture, and the shortest honest path to a first user

Design note, 2026-07-20. Nothing here is built. It proposes a **resequencing** of the
backlog, so it needs an owner decision before it changes anything. Companion to
`docs/STRATEGY.md` (the why) and `docs/ideas/agent-scoped-delegation.md` (the mechanism).

## The architecture, in two planes

Cistern is one server with two independent concerns. Naming them separately is what makes
the rest of this note possible.

```
                        ┌──────────────────────────────────────────┐
  Claude / any agent    │            AUTHORITY PLANE               │
        │  MCP          │                                          │
        ▼               │   principal ──► policy ──► decision      │
   cistern-mcp ─────────┤       ▲            ▲           │         │
                        │       │            │           │         │
   HTTP (Solid) ────────┤   resolver     owner-authored  │         │
        ▲               │   (pluggable)  rules in the    │         │
        │               │                pod itself      │         │
   credentials          └───────────────────────┬────────┴─────────┘
                                                │ allow / deny
                        ┌───────────────────────▼──────────────────┐
                        │              DATA PLANE  (built)         │
                        │  LdpService ──► ResourceStore ──► disk   │
                        └──────────────────────────────────────────┘
```

The **data plane** is done and works: LDP semantics, RDF io, containment, N3 Patch,
conditional requests, content negotiation. The **authority plane** is entirely absent —
and per `STRATEGY.md` it is not a missing feature, it is the product.

Two properties of this shape are load-bearing, and both are already committed to:

- **One enforcement point.** ARCHITECTURE decision 6: MCP is a peer front-end with no
  privileged internal path. Every caller, human or agent, crosses the authority plane.
- **Storage is behind an SPI.** Ground rule 5. The backend never parses RDF and the core
  never touches storage details, which is why the GCS question below is a backend choice
  rather than a rewrite.

## The insight: authentication and authorization are not the same phase

Phase 4 is Solid-OIDC + DPoP + WebID dereferencing. That machinery exists for exactly two
reasons: **interoperating with the wider Solid world**, and **passing the conformance
harness**. Both matter. Neither is needed for you to use your own pod on your own machine.

Phase 5 is the authorization engine — ACL discovery, evaluation, enforcement, and the
intersection cap. *That* is the part with product value, and it does not depend on how the
caller proved who they are.

So split the seam explicitly:

```java
interface PrincipalResolver {          // authentication: how identity is proven
    Mono<Agent> resolve(ServerRequest request);
}
```

with two implementations feeding **the same** engine:

| Resolver | Proves identity via | For |
|---|---|---|
| `LocalCredentialResolver` | a configured credential mapped to a WebID + client id | self-hosted, single owner, MCP on your own machine |
| `SolidOidcResolver` | Solid-OIDC access token + DPoP proof | interop with other Solid apps, and the CTH |

This is deliberately **not** an authorization bypass. Both resolvers produce the same
`Agent` principal and every request is evaluated by the same WAC engine against the same
owner-authored rules. Only the proof of identity differs. A local credential that skipped
enforcement would be a back door and would violate decision 6; a local credential that
*populates a principal* is just a second front door with the same lock behind it.

The payoff: **you become a real user after the authorization engine, not after the OIDC
stack.** Solid-OIDC then lands for the reasons it actually exists — interop and
conformance — rather than as a gate in front of your own dogfooding.

## What that makes the shortest path

Ordered by what unblocks the next thing, not by phase number:

1. **The principal type and the resolver seam.** Small. This is exactly where the open
   `Agent(webId, Optional<URI> client)` decision lands — see the delegation note. It has to
   be settled here regardless of ordering.
2. **`LocalCredentialResolver`.** Small. Config-driven, one owner, one or more agent
   clients.
3. **WAC engine — T5.1/T5.2/T5.3.** The real work: effective-ACL discovery, evaluating
   Authorization triples, mapping method+state to a required mode, deny by default.
4. **The intersection cap.** Cheap *once the engine exists* — a second evaluation and an
   intersection — and it is the differentiator: a delegation can only ever narrow, even if
   the rule was authored wrong.
5. **MCP front-end with client identity — T6.1/T6.2.** Per decision 6 this is close to
   free, because it runs the same evaluation as HTTP.

Phases 3 and 4 (the conformance grind and Solid-OIDC) come *after* this, and lose nothing
by it: T3.3 is already blocked behind T5.4 provisioning, so the conformance numbers cannot
move before Phase 5 lands under any ordering. This is not "skip conformance" — it is
"stop waiting on a gate that is already closed for other reasons."

## How you become the first user

The test for "first user" is not that it runs. It is: **you would be annoyed if it went
down.** That requires a corpus you care about and an agent doing something you would
otherwise do by hand.

The loop, once steps 1–5 above exist:

1. Cistern holds a corpus that is actually yours.
2. Claude Desktop connects over MCP as a *named client*, not as you.
3. You author one rule: this client may read `/notes/`, not `/private/`, until Friday.
4. You watch it work, then delete the rule and watch the next tool call fail.

Step 4 is the whole product demonstrated in one gesture, and it is also `T6.3`'s
walkthrough. **Write that walkthrough now, in prose, before building any of it** — it costs
an evening and it forces the consent-authoring question into the open, which `STRATEGY.md`
names as the risk that kills projects in this category. If the story does not read well in
ten lines, no amount of server correctness rescues it.

### The corpus question, which is really the beachhead question

Neither `STRATEGY.md` nor the delegation note answers "who runs this first and why," and
the strategy doc concedes user-owned data has been *about to matter* for a decade. The
cheapest available answer is your own other product: the working gcloud project is
`value-docs-legal`, and if Valuedocs handles legal documents then it is an unusually good
fit — legal work is where *"this agent may read the file for this matter, for the next 48
hours"* is a genuine requirement rather than a demo contrivance, and where "the agent holds
all your authority" is obviously unacceptable to a professional.

That would give, in one move: a first user (you), a real corpus, a use case shaped like a
customer's, and a forcing function that no synthetic demo provides. It may be a bad fit for
reasons not visible from here — but it is worth answering deliberately rather than by
default, because the alternative is a conformant pod with nobody in it.

**Sequencing constraint, and it is absolute:** real documents go in *after* the authority
plane exists, never before. Today an anonymous request can delete anything in the pod.

## Where this deploys

See `docs/deploy.md` for the detail. In short: `docker compose up` locally is the whole
answer until Phase 5, and a GCE VM with a persistent disk is the fit after that. Not Cloud
Run over a gcsfuse bucket — the file backend depends on atomic rename, which gcsfuse does
not provide. The long-term GCP answer is an object-native storage backend written against
the existing SPI, which is a clean ticket rather than a workaround.

## Decision requested

1. **Adopt the resolver seam**, so authentication mechanism and authorization engine are
   separable. Cheap, and independently good design. **Recommend yes.**
2. **Resequence to 5 → 6 → 4 → 3** (authorization and MCP before Solid-OIDC and the
   conformance grind). This contradicts "the phase order stands" in `STRATEGY.md`, which is
   why it is a request rather than a plan. **Recommend yes**, on the grounds that the
   conformance numbers are blocked until T5.4 under any ordering.
3. **Name the first corpus.** Valuedocs or otherwise. **Recommend deciding it explicitly
   before Phase 5 starts**, because it determines what "done" looks like for the demo.
