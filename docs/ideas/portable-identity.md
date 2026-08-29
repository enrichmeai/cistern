# Idea: what "you can leave" actually requires

Design note, not a ticket. Nothing here is built. Companion to `community-operator.md`, which
argues for operators other than the pod owner; this note states what has to be true before
moving between them is real rather than promised.

## The claim we make, and the gap in it

Cistern's guarantee is exit: Apache 2.0, self-hostable, an open specification, so a pod owner
can leave for another implementation or another operator. Two of those hold today — the code
forks, the server runs anywhere. The third does not yet, and it is worth stating precisely
rather than assuming portability follows from open formats.

Moving a pod from operator A to operator B breaks two things. Both have the same root cause:
**a name that is also an address.**

## Problem 1: the WebID is hosted

Solid identifies an agent by a dereferenceable HTTP URI, and that is a deliberate and useful
design — it is what lets any server verify an identity by fetching it, with no central
registry and no issuer of record. The consequence is that the identifier resolves at some
host.

If Alice's WebID is `https://church.example/alice/profile/card#me` and she leaves that
operator:

- every `acl:agent` triple naming that URI, **in other people's pods**, still names it;
- those ACLs are not ours to rewrite, and often not ours to see;
- if the operator closes — the case the community-operator model most needs to survive — the
  URI stops resolving, and a WebID that cannot be dereferenced cannot be verified.

Her data moved. Her name did not. This is email addresses again: portable mailbox contents,
non-portable address, and lock-in reappears one layer up.

### Options, with their real costs

| Approach | How it works | Cost |
|---|---|---|
| Own the domain | WebID at `alice.example`, DNS repointed on a move | Works today with no spec change; requires a person to own and renew a domain, which most will not |
| Redirect from the old host | `301` from old WebID to new | Requires the departed operator to cooperate indefinitely — fails in exactly the closure case that matters |
| Aliasing in the profile | Profile asserts an equivalence between old and new URIs | Every access-control evaluation would have to resolve equivalences, which is not what WAC evaluates; unilateral adoption would be a dialect |
| Indirection layer | The stable identifier resolves to a current host through a directory | Genuine portability; needs agreement beyond one implementation |

**Prior art worth studying rather than re-deriving.** The AT Protocol faced this exact problem
— accounts move between hosts while remaining the same account — and separated the stable
identifier from the location, with a human-readable handle resolving to it. Whatever one
thinks of the specifics, it is a working answer at scale to the question this note asks, and
the failure modes are documented in public.

## Problem 2: resource URIs move too

Resources are named by where they live: `https://church.example/alice/notes/2026-01.ttl`.
After a move, every link into that pod from outside — a shared document, an ACL naming a
resource in another pod, a bookmark, an agent's stored reference — points at the old host.

This is less severe than the identity problem, because most references are the owner's own and
travel with them, but it is the same shape and has the same fix: a name that is not an address.

## What Cistern can and cannot do here

**Cannot, unilaterally:** invent an identifier scheme. An identity that only Cistern honours is
a dialect, and a dialect is lock-in with better intentions — the exact thing this project
exists to refuse. Any answer has to be adopted by more than one implementation to mean
anything.

**Can, and should:**

- **Make the question concrete upstream**, with the worked failure case above. It is a
  genuine finding from building an independent implementation, which is what
  `docs/contributions/solid-conformance-feedback.md` is for. It is also not a criticism of a
  design decision: dereferenceable identity bought decentralised verification, and this is
  the bill for it.
- **Support the option that works today.** A pod owner with their own domain is already
  portable. Cistern should make that path documented and ordinary rather than expert-only —
  hosting a WebID profile at a domain the owner controls, pointing at a pod hosted elsewhere.
- **Build export and re-import** so that when identity is settled, the data half is ready and
  the operator-to-operator move is a supported operation rather than a rescue.

## Why this matters more than it looks

`community-operator.md` argues that a thousand small operators is structurally different from
one alternative provider. That argument depends entirely on being able to leave one operator
for another. Without portable identity, a church-hosted pod is not an escape from lock-in; it
is lock-in with a smaller landlord and worse uptime.

Stating that plainly is better than discovering it after someone's parish server closes.

## What this note does not decide

- Which identifier approach to back.
- Whether to raise it with the Solid CG now or after the current conformance feedback lands.
- Anything about who operates, funds or hosts what.
