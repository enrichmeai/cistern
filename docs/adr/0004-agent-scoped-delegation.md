# ADR 0004 — Where client-aware authorization is evaluated, and what a delegation may say

- **Status:** accepted — amends AD-14 in [ARCHITECTURE.md](../ARCHITECTURE.md); accepted by
  the merge of the PR that carries it
- **Date:** 2026-09-05
- **Deciders:** project owner (architect); proposed in the PR that carries this ADR
- **Governed by:** [`docs/architecture/delegation/ARCHITECTURE-SPINE.md`](../architecture/delegation/ARCHITECTURE-SPINE.md),
  the epic spine whose AD-DEL-1..6 this ADR records
- **Scope:** T6.5 / #119 (`(user, client)` principal) and T5.8 / #92 (time-boxed grants)

## Context

`docs/STRATEGY.md` names owner-authored, strictly-narrowing, instantly revocable agent
authority as the product thesis. `docs/ideas/agent-scoped-delegation.md` is its technical
form. The principal half shipped at T4.3: `Agent` carries
`Optional<URI> webId, Optional<URI> client`, and a real access token from CSS 7.2.0 carries
`client_id`, so the client is knowable at authentication with no extra round trip.

AD-14 as originally written confined client-aware evaluation to a future `cistern-acp`
module and required `WacEngine` to match on the WebID alone. It was written to prevent a
specific accident: the first consumer of `Agent.client()` setting the precedent by treating a
client match as *another way to be allowed in*, which is a privilege escalation wearing the
clothes of a feature.

That confinement had a cost the decision did not price. `cistern-acp` is deferred, and
ARCHITECTURE.md's own Deferred section calls it "a phase in its own right — estimating it as a
fold-in is how the Phase 5 estimate gets wrecked." So AD-14 gated the differentiator behind a
module with no schedule, while the same capability ships in commercial products today.

A second question was open and unaddressed: WAC and Solid define no term for client-scoped
authorization or grant expiry, so both need a vocabulary decision.

## Decision

### 1. `WacEngine` evaluates the client dimension; AD-14 is amended

The matcher lives in `cistern-wac` now, not in a future `cistern-acp`.

The accident AD-14 guarded against is already excluded by two rules that existed when it was
written. **AD-15** admits the client dimension *only* as an intersection —
`effective = accessFor(user) ∩ accessFor(client)` — so a client match cannot widen anything,
structurally rather than by review. **AD-16** keeps the whole feature behind a config flag,
default off until the WAC suite is green. The guard was doing work the guarded-against
outcome could no longer reach.

`cistern-acp` remains deferred for ACP proper — the matcher algebra and
`acp:AccessControlResource`. It loses client scoping as its reason to exist; it does not gain
a schedule.

### 2. Primary authority is portable WAC; Cistern terms may only reduce

This is the invariant the original design did not carry, and the reason the amendment is safe
to make.

AD-16 secures invisibility to the conformance harness. It does not secure **portability on
export**, and the two are different. A narrowing constraint expressed in a predicate another
server does not understand fails *open*: move a pod to another Solid implementation and the
agent silently gains the owner's full authority — the exact escalation the feature exists to
prevent, produced by the exit route the project advertises.

So: every authorization's distinguishing term is portable WAC — `acl:agent` naming the
agent's own WebID, or `acl:agentClass`. `cistern:client` and `cistern:validUntil` may only
reduce what that portable term already allows. **An authorization whose sole distinguishing
term is a Cistern extension is refused at authoring time.** Strip every Cistern term and what
remains must already be a grant the owner would accept as permanent.

This is continuous with decisions already taken: #89 ruled applications hold their own WebIDs
for v1; #106's acceptance requires `valuedocs-legal` to authenticate as its own principal
rather than on the owner's token; and the flagship demo's sixth beat is the agent's own
account.

### 3. The client is a constraint on an authorization, never a grantee

`Grantee` stays sealed at `WebId | Public`. Adding a `Client` variant would make a client
match a way to *be granted*, which is the widening AD-15 forbids.

`Authorization` gains `Set<URI> clients` and `Optional<Instant> validUntil`. An empty
`clients` set means **unconstrained, never denied** — AD-15's identity element, without which
every service-principal request an application makes would be refused.

### 4. Vocabulary: `cistern:client` and `cistern:validUntil`, profiled on ACP

Declared as constants in `core.vocab.Cistern`, alongside `Ldp`, `Solid`, `Acl`, `Foaf` and
`Pim`. `cistern:client` is documented as the ACP `acp:client` matcher restricted to depth 1,
so a later `cistern-acp` adopts the semantics rather than renegotiating them.

Two alternatives were rejected. **`acl:origin`** is a browser concept and is not an OAuth
`client_id`; servers that implement it for browser trust would collide with a second meaning.
**Bare `acp:client`** inside a WAC `acl:Authorization` is neither ACP nor WAC — a server
running ACP will not read the document and one running WAC ignores the term — which buys the
appearance of alignment with none of the interoperability.

### 5. Expiry is a ceiling, not a revocation

Both dimensions evaluate inside `WacEngine.decide` against an injected `Clock`, never
`Instant.now()` at the call site. A `validUntil` that is present and unparseable, or present
and past, makes that authorization contribute nothing — fail closed — with a named
`WacMessage` reason.

Revoking means deleting the authorization. No CLI text, error message or document may present
expiry as the way to revoke. Stripped on export, a `validUntil` becomes permanent; decision 2
is what keeps that bounded in scope rather than in time.

### 6. The receipt names the term that bound

When the client dimension or an expiry narrows a decision, the `DecisionRecord` names *which*,
as an enum-valued field rather than free text. Without it a receipt cannot distinguish "the
user never had this access" from "the delegation capped it" — which is the audit value the
feature exists to produce, and what #106's sixth acceptance point has to show. A decision
unaffected by either dimension records neither, so a pod with no delegation emits a record
byte-identical to today's.

### 7. Five shared seams, settled before either story lands

T5.8 and T6.5 are independent — expiry never reads `Agent.client()` — but both edit
`Authorization`, `WacEngine`, `DecisionField` and the grant CLI. Each could obey every decision
above and still build incompatibly, so the seams are fixed here rather than by whoever arrives
first:

1. `Clock` is a `WacEngine` **constructor collaborator**, not a `decide` parameter, and never
   `Instant.now()` at a call site. Both existing `decide` overloads keep their signatures.
2. Both overloads funnel into **one private narrowing method**. Neither implements narrowing
   itself.
3. **One `DecisionField`, enum-valued** — not a boolean field per dimension. A third dimension
   later adds a constant, never a column.
4. Absence idioms are typed, not wrapped: `Set<URI> clients` where empty means unconstrained;
   `Optional<Instant> validUntil` where absent means no ceiling. Never `Optional<Set<URI>>`.
5. **One flag gates both dimensions** — `cistern.wac.delegation.enabled`. For AD-16's purpose
   they are one feature, and a per-dimension flag would let half the extension reach the
   harness.

## Consequences

**What this buys.** The product thesis becomes implementable in Phase 5/6 rather than behind
an unscheduled module. A pod exported to another Solid server degrades to a grant that is
narrow by construction instead of one that silently widens. A receipt can distinguish "the
user never had this access" from "the delegation capped it", which is what #106's sixth
acceptance point has to show.

**What it costs.** `cistern-wac` carries a second evaluation per request once a delegation
exists — accepted and designed for, per AD-15. Every delegated grant now requires the agent to
hold its own WebID, so provisioning an agent is a step that cannot be skipped. And AD-14, an
`[ADOPTED]` decision, was amended the day after the spine carrying it was finalized; the
amendment is recorded in place with its ID stable rather than renumbered.

**What stays open.** The owner-facing authoring surface is out of v1 on purpose — a Turtle
file is an answer for us and not for a pod owner, and this is the part that historically kills
systems of this shape. Sub-delegation stays at depth 1; if chains are ever wanted, adopt
macaroons or Biscuit rather than invent a third attenuation scheme. Whether a pod should
advertise in its storage description that its ACLs carry terms another server will not honour
is a real question, and a mitigation of decision 2 rather than a replacement for it.

## References

- `docs/ARCHITECTURE.md` — AD-14 (amended here), AD-15, AD-16
- `docs/ideas/agent-scoped-delegation.md` — the technical argument
- `docs/STRATEGY.md` — why this is the thesis rather than a feature
- Backlog: T6.5 (#119), T5.8 (#92); closed principal-shape ruling #89
- ACP `acp:client`, Editor's Draft — the semantics `cistern:client` profiles
