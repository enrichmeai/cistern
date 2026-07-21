# The flagship demo, written before the code

Draft for **T6.3**, written 2026-07-21 while Phases 4–6 are still empty directories. This
is deliberate: the demo is the product claim, and a claim is far cheaper to reject in prose
than in Java. If the story below does not land, no amount of server correctness rescues it.

Nothing here is built. Read it as a specification for what must be true, and as a test of
whether the thing is worth building at all.

## What has to be proved

Not *"an AI agent can read your files."* Every filesystem MCP server does that, in one line
of config, today. Demonstrating it proves nothing and invites the obvious question: why a
Solid pod, then?

The claim that is actually ours is a **negative**:

> An agent tried to do something, and was stopped — by a rule the data's owner wrote, held
> in the owner's own storage, enforced at the store rather than at the agent, revoked
> instantly, with a record of what was touched.

Nobody ships that for the consumer, owner-authored case. MCP rejected per-tool scopes
(SEP-1880, closed as not planned) and documents scope *maximisation* as the default.
Enterprise-Managed Authorization is admin-authored and exists to remove consent prompts.
Every vendor agent-identity product is developer- or admin-authored, with revocation by
token expiry. ACP models the client as a principal but is off by default, has no expiry, no
cap, and no authoring surface.

So the demo must show a refusal. A demo without a refusal is a file browser.

## The four beats

Short enough to perform live in about three minutes, and each beat exists to kill one
specific objection.

### Beat 1 — it works at all

Claude Desktop is connected to a pod over MCP. Ask it something ordinary:

> **"Summarise my notes from this week."**

It does. Beat 1 buys nothing on its own; it exists so that the refusal in beat 2 cannot be
dismissed as a broken connection.

### Beat 2 — the refusal

Same session, same agent, same token:

> **"Now read my private folder and tell me what's in it."**

It cannot. The agent reports that it was denied — not that the folder is empty, not that it
could not find it. **Denied.**

The objection this kills: *"the agent just wasn't given that path."* Show the rule. It is a
file in the pod, authored by the owner, that names this client and grants read on `/notes/`
and nothing else. The agent was not asked politely to stay out; it was refused at the store.

### Beat 3 — revocation, live

Delete the rule mid-session. Do not restart Claude. Do not reissue a token. Ask beat 1's
question again:

> **"Summarise my notes from this week."**

Now it fails too.

This is the beat that separates Cistern from every token-scoping product on the market.
Their revocation waits for expiry, because the authority is baked into a token that was
issued hours ago. Here the policy is read at enforcement time, so the *next call* is denied.
The gap between "I revoked it" and "it is revoked" is one request.

### Beat 4 — the receipt

Show the audit record: which client, which resource, which rule permitted or refused it,
when. *"Which agent read my medical notes, under what grant, and when"* is answerable.

The objection this kills: *"fine, but you cannot prove what it did while it had access."*

## The one-sentence version

> Alice's agent can read her notes and nothing else, for as long as she says, and she can
> take it back in the time it takes to delete a line.

## What must exist for this to run

Beat by beat, against the current backlog:

| Beat | Needs |
|---|---|
| 1 — it works | T6.1 MCP server, T6.2 identity binding |
| 2 — the refusal | **T5.1–T5.3** (ACL discovery, engine, enforcement) + client-aware principal |
| 3 — revocation | policy evaluated per request — falls out of T5.3, provided nothing caches decisions into a token |
| 4 — the receipt | decision logging that names the deciding policy resource — *not currently a ticket* |

Two things this surfaces that the backlog does not currently carry:

1. **Beat 4 has no ticket.** Audit is asserted as a property in `STRATEGY.md` and
   `agent-scoped-delegation.md` ("inherent audit"), but nothing schedules it. It is cheap
   *if* the decision point is built to name its deciding policy, and expensive to retrofit
   afterwards. It should be a DoD line on T5.3, not a later ticket.
2. **Beat 3 is a constraint on T5.3's design, not a feature.** The moment any decision is
   cached into a session or token, instant revocation is gone and beat 3 dies quietly. Worth
   stating in T5.3's DoD: *no authorization decision outlives the request that produced it.*

The intersection cap (`effective = user ∩ agent`) does **not** appear in this demo, and
that is honest: for a pod the owner fully controls, the cap is vacuous. It earns its place
as a standing invariant against a mis-authored rule, not as a demo beat. Claiming otherwise
on stage would be overselling.

## The part that is not a server problem

Beat 2 requires the owner to have authored a correct rule. In this draft that is a Turtle
file, which is an answer for us and for nobody else.

This is the honest weak point, and `STRATEGY.md` already names it: consent UX is where
systems of this class die — UMA has had the right model for a decade and near-zero
adoption. A demo that opens a text editor and writes RDF proves the enforcement works and
simultaneously demonstrates that the product is not usable by its intended user.

Three options, in increasing cost:

- **v1, developer audience.** Author the rule in Turtle, on camera, and be explicit that
  the authoring surface is future work. Honest, cheap, and limits the audience to people
  who find a triple readable.
- **A single-purpose CLI.** `cistern grant claude --read /notes/ --for 24h`, which writes
  the same file. Small, and turns beat 2 into something a non-RDF audience can follow.
- **Natural-language authoring**, which is what Inrupt is pitching. Not v1, and worth
  noting the recursion: an agent authoring the constraints on agents needs its own trust
  story.

**Recommendation: build the CLI.** It is a day's work against a file format that has to
exist anyway, and it is the difference between a demo that reads as *research* and one that
reads as a *product*. It also forces the vocabulary to be legible before the demo hardens.

## Corpus

The demo needs data the audience believes someone would care about. Invented notes make
beats 2 and 3 abstract — refusing access to a lorem-ipsum file is not frightening.

**Assumption to confirm:** the owner's other product, Valuedocs, handles legal documents.
If so it is an unusually strong fit — legal work is where *"this agent may read this
matter's file, for the next 48 hours"* is a real professional requirement rather than a
contrivance, and where "the agent holds all my authority" is obviously unacceptable.
Nothing in the note below depends on it; substitute any corpus the owner would be annoyed
to lose. **This is the open question that most shapes the demo, and it is not a technical
one.**

**Hard constraint:** real documents go into a pod only *after* the authority plane exists.
Today an anonymous request deletes anything (ADR 0001).

## How this gets falsified

Worth writing down while it is cheap to change course. This demo fails if:

- **The refusal is unimpressive.** If an audience shrugs at beat 2 — "so it has
  permissions, like every database" — then the differentiator is not delegation but
  *owner-authored* delegation, and the demo must foreground the authoring, not the denial.
- **Beat 3 is invisible.** Instant revocation is only striking if the audience knows the
  alternative is waiting for a token to expire. It needs one sentence of framing, or it
  looks like an ordinary permission change.
- **Nobody asks for beat 4.** If audiences never ask "what did it do while it had access",
  audit is a compliance feature rather than a product one, and should be scheduled
  accordingly rather than sold as central.

## What to do with this note

Read it as a go/no-go on the thesis, not as a ticket. If beats 2 and 3 read as compelling,
the resequencing argued in `docs/ideas/first-user-path.md` follows directly: Phase 5 and
Phase 6 produce this demo, Phase 4 and Phase 3 do not. If they do not read as compelling,
that is worth knowing now — before the authorization engine is built.
