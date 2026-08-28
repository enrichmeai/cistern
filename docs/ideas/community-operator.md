# Idea: the community operator

Design note, not a ticket. Nothing here is built. It concerns who *runs* a pod server, and
what that answer asks of the software.

**Definitions.** *Operator* = whoever the server belongs to and who is accountable for it
running. *Owner* = the person whose data is in a pod on it. Today those are assumed to be
the same person; this note is about the case where they are not.

## The problem

Cistern currently offers two ways to have a pod, and most people are in neither.

| Shape | Who operates | Who it actually serves |
|---|---|---|
| Self-host | the pod owner | people who can run a server and want to |
| Managed | a company | people who will accept a company in the position |

Self-hosting assumes a competence and an appetite that almost nobody has. Managed hosting
works, but it reintroduces the thing the project exists to remove: an organisation standing
between a person and their data. It is a better landlord, not the absence of one.

The gap is people who want neither to administer a machine nor to depend on a distant
company. That is most people.

## The third shape

An institution people already trust runs one server for its members: a church, a library, a
school, a credit union, a housing co-operative, a GP practice, a village association. The
pod owner's data sits with a body they can walk into and whose officers they know by name.

Two properties make this different in kind rather than degree:

1. **The trust is pre-existing and social, not contractual.** It was not established by a
   privacy policy and cannot be transferred by an acquisition. It is also legible: people
   understand what a church is in a way they do not understand what a data processor is.
2. **It distributes the operator role rather than relocating it.** A thousand small
   operators is a structurally different thing from one alternative provider, however
   well-intentioned that provider is. Nobody acquires a thousand parish councils.

This is the mutual model — credit unions, building societies, friendly societies — applied
to storage. It is not novel, which is a point in its favour.

## Small by design

A personal pod is small. A person's documents, notes, records and correspondence are
megabytes to a few gigabytes; a congregation's worth is not a data-centre workload. Personal
data lives in hyperscale infrastructure because of what aggregating it is worth, not because
storing it is hard.

So the hardware envelope for a community server is a cheap box: a mini PC in a cupboard, a
small VPS, a Raspberry Pi class machine. Cistern is already close to this and should stay
there deliberately:

- file-per-resource storage with metadata sidecars — no database cluster;
- `docker compose up` as the whole deployment;
- no mandatory external infrastructure beyond an identity provider;
- a JVM footprint that fits a machine with a few gigabytes of RAM.

**This is a constraint to protect, not merely a present fact.** Any future dependency that
assumes a cluster — a message broker, a distributed cache, an external database as a hard
requirement — forecloses this model silently. Adding one should be a decision taken with
this note open, not a side effect of an implementation.

## What it asks of Cistern

Nothing here is built. In rough order of how load-bearing it is:

- **Multi-tenancy as the main line, not an extra.** Many pods per deployment, isolated from
  each other, provisioned as an operator action rather than by editing config and
  restarting. T5.6 is the existing ticket; this note raises its priority and its bar.
- **Provisioning and de-provisioning as first-class operations**, including a member
  leaving with their data intact.
- **Backup and restore an operator can actually perform**, and verify. Untested backups are
  the single most common way community-run infrastructure loses people's data.
- **Upgrades that cannot lose data**, applied by someone who is not watching release notes.
- **An identity answer.** Cistern validates tokens and is deliberately not an identity
  provider (see CLAUDE.md). A community operator cannot stand up and run one either. Who
  issues a village's logins is therefore an open question this model forces, and it is not
  answered here.
- **Per-pod isolation that survives operator error**, since the operator is not a
  professional administrator.

## Failure modes, stated plainly

This model has a decade of evidence against naive versions of itself. Federated email,
Mastodon and Matrix all show the same pattern: small instances start enthusiastically, and
then administrators burn out, certificates expire, backups turn out never to have worked,
and users consolidate onto whichever large instance is still standing. Any design that
requires sustained volunteer attention will follow them.

- **Administrator burnout.** The operator's ongoing burden must approach zero. If running
  it needs monthly attention from a competent volunteer, it will fail on the month that
  volunteer moves away.
- **Legal position.** An institution holding members' personal data takes on real duties
  under data protection law. This is a genuine obligation and needs a real answer, not a
  disclaimer. It is a reason the operator's software should make the right thing automatic.
- **Power asymmetry.** "Community" does not mean "safe". Whoever holds root can read
  everything unless the design prevents it, and local power dynamics can be worse for an
  individual than a distant company that has no idea who they are. A model that only works
  when the operator is benign is not a security model.
- **Continuity.** Institutions close. A pod must be portable out of a dying server without
  the operator's cooperation, or the model has recreated lock-in at parish scale.

## The design principle that makes it survivable

**The community governs; the operation is invisible.** A credit union is locally accountable
and locally trusted while running core banking software it neither wrote nor operates. The
equivalent here: the institution decides who may join and what the rules are, holds the
relationship with its members, and puts its name on it — while nobody there ever opens a
terminal.

That framing keeps the trust local and the burden professional, and it is the only version
of this idea that survives the failure modes above.

## What this note does not decide

- Who issues identity for a community's members.
- How operation is provided or funded.
- Whether this becomes a supported deployment shape or stays an idea.

Those are the owner's calls. What this note argues is narrower: that the third operator
shape is real, that the hardware envelope which makes it possible is a property Cistern
currently has and could lose by accident, and that multi-tenancy is the piece of work
everything else in it depends on.
