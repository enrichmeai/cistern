# Idea: agent-scoped delegation

Design note, not a ticket. Nothing here is built. It needs a decision before **T4.3**
(Phase 4), which freezes the shape of the authenticated principal.

**Definitions used below.** *Delegator* = the human who owns the pod. *Delegate* (or
*agent*) = the software acting on their behalf: Claude, ChatGPT, an in-house bot.
*Owner-authored* = the constraint is written by the pod owner, in their own pod, as
opposed to *developer-authored*, where an application declares the permissions it wants
and the user only says yes or no to that menu. The distinction is the whole point of
this note.

## The problem

Solid answers "may Alice read this resource?". It cannot express the question the AI era
actually asks:

> Alice controls her whole pod. She wants Claude, acting for her, to read `/notes/` and
> nothing else, read-only, for the next 24 hours — even though *she* can read and write
> everything.

Today the only expressible answer is "the agent holds Alice's token, therefore the agent
is Alice". Every MCP server works this way. The consequences:

- The blast radius of a prompt injection equals the blast radius of the *person*.
- You cannot revoke one agent without revoking the human.
- Afterwards there is no record of which agent did what, under whose authority.

This is not specific to Solid. But a pod server is the natural place to fix it, because
the pod already holds both the data and the owner's intent.

## MCP will not fix this, and has said so

Worth stating plainly, because it is the strongest argument for doing anything here at
all. In the current MCP authorization spec (draft; 2026-07-28 revision in RC):

- The **Scope Selection Strategy** advises clients, absent a challenge, to request *all*
  scopes in `scopes_supported`, on the reasoning that MCP clients "typically lack
  domain-specific knowledge to make informed decisions about individual scope
  selection." Scope *maximization* is the documented default path.
- **SEP-1880**, which would have let a tool declare its own required scopes, was
  **closed as not planned**. Per-tool scope was proposed and rejected.
- The flagship extension, **Enterprise-Managed Authorization** (stable June 2026;
  Anthropic, Microsoft, Okta), is admin-authored, and its advertised benefit is issuing
  tokens "without additional per-server authorization prompts." It removes user consent
  by design. The document concedes the user-driven model "is ideal" for consumers, then
  builds for enterprises anyway.

So the consumer, owner-authored case is explicitly vacant in the protocol Cistern
fronts. That is the opening.

## Prior art: the mechanism is not the gap

Researched July 2026. The honest picture is that this space is loudly crowded, but
crowded in one specific corner, and the corner Cistern would occupy is nearly empty for
reasons worth understanding.

**Already solved, do not rebuild:**

| Thing | What it gives | Why it is not the answer here |
|---|---|---|
| **Solid ACP** | `acp:client` is a first-class matcher beside `acp:agent`, `acp:issuer`, `acp:vc`, composable with `allOf`/`anyOf`/`noneOf`. "Only Alice, only via app A" is directly expressible. | **This is the substrate, and it already exists.** It gives the client-as-principal half. It does not give the cap or expiry. See below. |
| OAuth Token Exchange (RFC 8693) | `act` actor chains | RFC 8693 §4.1: consumers must consider only the current actor. Prior actors are an audit trail, not access control. Carries identity, not reduced permission. |
| Rich Authorization Requests (RFC 9396) | structured `authorization_details` | Developer-authored schema; the user picks within a developer-built menu. No implementation found where a user free-form authors it. |
| UMA 2.0 | genuinely owner-authored policy | Right model. Kantara spec stagnant since ~2024, implementations registry last updated 2020, zero agent uptake. |
| Macaroons / Biscuit | offline attenuation; narrowing is native and provable | Right primitive for *chains*. But no general-purpose data store verifies them, and caveat vocabularies are still service-defined. Biscuit's last release was Dec 2024. |
| AuthZEN COAZ | per-tool-call authorize decisions (WG draft, June 2026) | Standardizes the wire format between enforcement point and decision point. Says nothing about who authors the policy. A possible complement, not a competitor. |
| Entra Agent ID, Anthropic Agent Identity, Auth0, Okta, Descope, Stytch, WorkOS | agent identity, per-tool scopes, instant revocation, enforced at the store in Microsoft's case | All developer- or admin-authored. Scopes are vendor-defined (`Mail.Read`); the owner cannot say "only emails from my lawyer, last 30 days". |

**The correction that matters most.** An earlier version of this note proposed inventing
a `cst:Delegation` vocabulary and building on WAC's `acl:origin`. Both were wrong:

- `acl:origin` is a dead letter. It is *conjunctive* (access needs an agent match **and**
  an origin match, so the app is never a subject in its own right), it applies only when
  the request carries an `Origin` header — so non-browser clients, **including every AI
  agent**, bypass it entirely — and an Origin is not an app identity. Community Solid
  Server ignores it outright: the access-checker directory has `AgentAccessChecker`,
  `AgentClassAccessChecker`, `AgentGroupAccessChecker` and **no** `OriginAccessChecker`;
  `acl:origin` does not appear in `WebAclReader.ts`.
- **ACP already does the thing properly, and CSS already enforces it.** Verified in
  source: `DPoPWebIdExtractor.ts` pulls `client_id` off the access token, `Credentials.ts`
  keeps `agent` / `client` / `issuer` as separate fields, and `AcpReader.ts` passes all
  three into `allowAccessModes()`, whose result is the permission set. Owner-authored,
  server-enforced, per-resource, client-distinct authorization, working today, in open
  source.

So the client-as-principal mechanism is not missing, and building a `cst:Delegation`
vocabulary would have been reinventing it badly. What is missing is everything around it:
ACP is off by default in CSS, has not moved since its 2022 Editor's Draft, is not a W3C
Recommendation, and sits under a Working Group (Linked Web Storage) chartered only through
September 2026. Nobody has put an owner-facing authoring surface on it, nobody has wired it
to MCP, it has no time-bound grants, and it has no cap — a mis-authored rule over-grants
silently.

**Competitive note.** Inrupt is pitching precisely this — their Solid + MCP post argues
"the current MCP authentication model treats AI agents like they're you," and pitches
natural-language time-bounded grants ("share my driver's license with the rental agency
for the next 48 hours"). It is a prototype seeking release partners: no dates, no repos,
no GA. Same pattern as Charlie. They have spotted the gap; they have not shipped it.

**Timing.** The gap is thin rather than empty, and closing. AuthZEN COAZ landed as a WG
draft in June 2026, and the IETF is scheduled to hold an `agentproto` working-group-forming
BOF on 23 July 2026 (three days from this note; BOFs slip, and forming a WG is not the same
as producing a spec). The space is officially pre-charter, which is exactly when it is
cheap to be early and expensive to be late.

## What this actually becomes

Keeping the two halves apart matters, because an earlier draft of this note ran them
together and thereby claimed a safety guarantee it had not paid for.

**Substrate we adopt (exists — do not rebuild):**

- **ACP with `acp:client`.** The standard mechanism for treating the application as a
  principal distinct from the user. Implemented and enforced in CSS today. No Cistern
  dialect, no new vocabulary.

**Semantics we add (this is the actual novelty, and it is real work):**

1. **The intersection cap.** See below. This is a genuine extension beyond stock ACP.
2. **Expiry.** ACP defines no temporal attribute — verified against the v0.9.0 context
   model, whose attributes are exactly `mode`, `agent`, `creator`, `owner`, `client`,
   `issuer`, `vc`. But the context model is **explicitly extensible through
   sub-properties of `acp:attribute`**, so a temporal attribute is an extension point the
   spec provides, not a fork of it. A grant that dies on its own is most of the practical
   value of a delegation and this is the cheapest way to get it.
3. **The MCP binding.** Every tool call resolves the agent to a client identity and runs
   the same evaluation. Per ARCHITECTURE decision 6 there is no privileged internal path,
   so this is close to free.
4. **The owner-facing authoring surface.** Named for completeness; explicitly out of v1.
   See risks — this is the part that kills projects like this.

### The intersection cap, stated honestly

Stock ACP grants a matched client exactly what its rules say. There is no second operand.
If the owner authors a generous rule, the agent gets generous access — the narrowing comes
entirely from the owner having chosen to write a narrow rule. That is a *policy-authoring*
guarantee, which is to say no guarantee at all against a mistake.

The extension is to evaluate the delegator's access and the delegate's access separately
and cap one by the other:

```
effective(user, agent, resource) = accessFor(user, resource) INTERSECT accessFor(agent, resource)
```

**Intersection only, never union.** A delegation can then only narrow: it cannot grant
Alice's agent something Alice lacks, because an intersection is always a subset of both
operands. This holds *even if the rule was written wrong*, which is the entire point and
the difference between this and plain ACP. It excludes the privilege-escalation bug class
structurally rather than by careful review.

Two caveats worth stating rather than discovering later. First, for the common case the
cap is vacuous — the pod owner already has full rights on their own pod, so intersecting
against them changes nothing. It earns its keep for delegations against resources the
delegator only partly controls, and as a standing invariant that cannot be eroded by a
future careless ACR. Second, it means two evaluations per request instead of one, which is
a real cost to design for, not a footnote. (WorkOS publishes the same intersection rule as
guidance for agent systems — convergent, not novel, which is mildly reassuring.)

Two further properties fall out for free, and they are the ones the vendor products charge
for:

- **Instant revocation.** The policy is read at enforcement time, not baked into a token
  issued hours ago. Alice deletes the rule; the next tool call is denied.
- **Inherent audit.** Every decision names the policy resource that permitted it. "Which
  agent read my medical notes, under what grant, when" is answerable.

## Why Cistern is unusually well placed

1. **One enforcement point, by architectural decree.** ARCHITECTURE decision 6: MCP is a
   peer front-end, "there is no privileged internal path." The hardest part of retrofitting
   delegated authorization is finding every place that must enforce it. Already solved on
   paper, before the code exists.
2. **The identity is already on the wire.** A Solid-OIDC token carries a client identifier
   distinct from the `webid` claim, and Client ID Documents make it a dereferenceable URI
   rather than a bare Origin — which is exactly what fixes `acl:origin`'s fatal flaw.
3. **The pod can hold the policy.** No token surgery, no external policy store.
4. **We have not written the authorization layer yet.** Phases 4, 5 and 6 are empty
   directories today. This is the only moment where the choice is free.

## Cost, and the window

Moderate, and larger than the first draft of this note claimed once the cap is counted
honestly: a client-aware principal, an ACP evaluator (a phase in its own right — ACP is
currently a declared v1 non-goal), the intersection cap with its second evaluation per
request, an expiry attribute, and the MCP binding passing the client through. The pieces
that fold into T4.3 / T5.2 / T6.2 are small; the ACP evaluator is not, and pretending
otherwise is how the Phase-5 estimate gets wrecked.

The reason it needs deciding now is that **T4.3's DoD fixes the authenticated principal as
`Agent(webId)`**. Widening that today is one extra field on a record and a nullable term
in an evaluation. After Phase 5 ships it touches the Reactor context population point, the
WAC engine's input type, every ACL fixture, the MCP identity binding, and the CTH baseline
— which by our own ground rules may only move forward.

**Minimum commitment, even if everything else here is parked:** make the T4.3 principal
carry an optional client identity from day one.

```java
record Agent(URI webId, Optional<URI> client) { ... }
```

That single signature choice keeps the window open at a cost of approximately nothing, is
reversible, and is independently justified — CSS extracts exactly these fields for the
same reason.

**Implementation landmine for T4.1.** Solid-OIDC normatively places `azp` on the **ID
token** and says nothing about access-token claims — yet CSS reads `client_id` off the
**access token**. The mechanism this entire idea depends on is convention, not contract.
Capture real tokens from a live IdP (the T4.1 DoD already requires this) and record what
is actually present before designing against it.

## Scope discipline for a v1

Every axis of "reduced scope" multiplies both the enforcement surface and the consent
problem. v1 gets three:

| In | Out (v1) |
|---|---|
| target resources (ACP matchers) | RDF predicate- or triple-level scoping |
| access modes (Read / Append / Write) | purpose limitation ("only for scheduling") |
| expiry | call-count budgets, rate limits |
| | sub-delegation chains |

**Sub-delegation is deliberately out. Depth 1: a delegate may not re-delegate.** The moment
chains are allowed, attenuation must be provably monotonic across the chain — precisely the
problem macaroons and Biscuit exist to solve. If chains are ever wanted, adopt one of those
rather than invent a third. Note the research finding that recursive delegation
accountability is listed in the 2026 literature as an *open research problem*, not an
engineering task.

## Conformance risk

Extensions are not Solid Protocol, and the CTH is our fitness function. Non-negotiable:

- With no delegation policy present, behaviour must be identical to plain WAC.
- Delegations may only narrow, so no assertion passing today can begin failing unless a
  test deliberately creates a delegation.
- Config-flagged, default off, until the WAC suite is green (T5.5).

If it cannot be made invisible to the harness, it does not ship.

## Honest risks

- **Consent UX is the actual product, and it is not a server problem.** Getting a human to
  author a correct delegation is where systems of this kind historically die — it is why
  UMA is stagnant despite having the right model. A Turtle file is not an answer for
  anyone but us. v1 punts to a config file and a developer audience, and we should be
  clear-eyed that this leaves the hardest part unsolved.
- **ACP is a stalled spec.** Implementing it buys standards alignment and CSS
  interoperability, but not momentum. Its Working Group's charter expires September 2026.
- **Opportunity cost.** Cistern is at Phase 1 of 7 and Valuedocs is priority one. The
  `Agent(webId, client)` minimum costs nothing. Building the feature before Milestone 3
  would be a scope breach.
- **ACP is currently a declared v1 non-goal** (ARCHITECTURE, "Deliberate non-goals"). If
  this note is accepted, that entry needs revisiting — not to pull ACP forward wholesale,
  but because the reasoning behind deferring it changes if `acp:client` is the
  differentiator rather than a completeness item.

## Decision requested

1. Accept the `Agent(webId, Optional<client>)` principal shape into the T4.3 DoD. Cheap,
   reversible, independently justified. **Recommend yes.**
2. Add the access-token `client_id` capture to the T4.1 fixture DoD. **Recommend yes.**
3. Whether ACP moves from parked non-goal to a Milestone-3-adjacent phase. **Not yet** —
   revisit at the Phase 4 boundary, when the auth layer is real and the CTH baseline is
   known.
