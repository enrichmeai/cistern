# ADR 0003 — The hosted offering: tenancy, boundary, price unit, residency

- **Status:** accepted — accepted by the merge of the PR that carries it
- **Date:** 2026-09-06
- **Deciders:** project owner; tenancy, region and price-unit rulings taken 2026-09-06
- **Scope:** T7.11 / #103. Governs the `enrichmeai/cistern-cloud` backlog C1–C9.
- **Supersedes nothing.** Extends [ADR 0002](0002-production-posture.md), which fixed when an
  instance may face the internet; this fixes what a *hosted* one is.

## Context

Cistern is Apache 2.0 and `COMMERCIAL.md` states publicly that this will not change. That
promise has a consequence which decides everything below: **Cistern is not bought as software** —
anyone who can adopt it can run the jar for nothing. What is bought is operations and
accountability.

`cistern-cloud` (private) holds the build as C1–C9. This ADR fixes the four decisions those
tickets depend on and were blocked without.

The distribution question is not separate from the commercial one. A free hosted tier and the
Claude Connectors Directory listing (#120) are one motion: a stranger clicks through from the
directory, gets a pod, and watches an agent be *refused*. That is the product demonstrating
itself with no install, no WebID setup and no Docker — and it is the only route by which most
people will ever experience a refusal, which is the thing being sold.

## Decision

### 1. Tenancy is a deployment topology, not a Cistern feature

The hosted product chooses a topology per tier. Cistern itself gains no tenancy concept.

| Topology | What it costs Cistern | Isolation rests on |
|---|---|---|
| **Instance-per-tenant** | **Nothing.** This is what the open server already is: one owner, one storage root, one origin | Process and filesystem |
| **Shared process, host-per-tenant** | A host → storage-root resolver at the composition root | Filesystem — different root per host |
| ~~Shared process, path-per-tenant~~ | **Rejected** | — |

**Path-prefix tenancy is rejected on the evidence in #65.** `ResourceIdentifier.isStorageRoot()`
defines the root as the resource with no parent. Under `https://host/tenant-a/`, nothing
recognises `/tenant-a/` as a root: DELETE protection on the root silently stops applying, the
ancestor walk treats `/` as a real parent, and PUT will create resources outside the pod. #65
records that fixing this properly is option (b) — prefix-aware identifiers across core, storage
and HTTP. A tenancy model is not a good reason to take on that change, and WAC's ancestor
walking would inherit the ambiguity.

**So a tenant is an origin.** That keeps #65's origin-only rule intact, and it makes isolation
*structural* rather than a property of the authority plane being correct — which matters here
more than usual, because the authority plane is the thing being sold. A WAC defect should be a
bug, never a tenant breach.

**For C1 and C3, start instance-per-tenant.** It needs no new code, so the closed beta tests the
product rather than the platform. Host-routed sharing is the documented growth path when
per-tenant cost stops being acceptable, and it is a composition-root change (AD-4), not a core
one.

### 2. The open/commercial boundary

Unchanged from #103's framing, restated here because it must not drift:

- **Open, always** — the server, the storage SPI and every backend, the WAC engine, delegation
  (`cistern:client`, `cistern:validUntil`), receipts, the CLI, the MCP front door, conformance.
- **Commercial** — multi-tenant provisioning and console, billing, backup and restore
  automation, monitoring and on-call, SLAs, the hosted domain and certificates.

The test: **if it changes what a request is allowed to do, it is open.** Operations are the
product; authority never is. A boundary that ever moves toward "the good access-control
features are paid" would cost the differentiator that survives against a shipped closed
competitor, which is the licence and the shape rather than the mechanism.

### 3. Price unit — per protected pod, per month

Value and cost both scale per data subject, and it is the unit a professional-services firm
already thinks in: per client, per matter. The free tier is naturally *one pod*, which is what
makes it a demo rather than a giveaway.

Rejected: per seat (decouples price from what the system does — five staff with five thousand
client pods would pay the same as five with five); per receipt volume (honest, but it penalises
exactly the agent activity the product exists to encourage, and a buyer cannot forecast it). A
pod fee with a receipt allowance remains available later as an enterprise lever; it is not worth
the metering work before C3 produces evidence.

**Payer: the business, never the end person in v1.** Carried unchanged from #103.

### 4. Residency — UK/EU first

#103 said SG and IN first. That was written when Singapore was the target market; it is now
deferred, so the assumption is corrected rather than inherited.

UK/EU is where the company is (Good Shepherd Software Consultancy Ltd, England & Wales), which
means the C8 legal pack — privacy policy, terms, DPA — gets written once for the jurisdiction
the merchant actually operates in, and UK GDPR is a regime already binding rather than one being
opted into. India remains the strongest *adjacency* because the reference customer is there and
DPDP is a genuine tailwind for a consent-and-receipts product; it becomes a second region when a
customer asks, not before.

### 5. Exit is part of the product, not a concession

C7 stands: a whole-vault export as standard Solid data, importable into a self-hosted Cistern.
Portability is load-bearing in the pitch, so it has to be true of the paid product or the pitch
is false. Note the known gap: `docs/ideas/portable-identity.md` records that data moves but the
WebID does not, because it is a name that is also an address. Export makes the data portable;
the identifier problem is unsolved and is not solved here.

## Consequences

**What this unblocks.** C2 has an isolation model. C4 has a price unit. C8 has a jurisdiction.
C1 can be costed. The sequence C1 → C2 → C3 → C4 → C5 becomes startable.

**What it depends on.** #118 (OAuth resource-server metadata and an authorization server with
dynamic client registration) is the hard prerequisite for all of it — without it no Claude
connector can authenticate to a hosted tenant, so there is no directory listing and no product.
It is unblocked and is the highest-leverage open ticket for this track.

**What it costs.** Instance-per-tenant means per-tenant infrastructure cost from the first free
account, which caps how large a free tier can be before host-routed sharing is needed. That is
accepted: a small free tier that demonstrates a refusal is worth more than a large one that
demonstrates storage.

**What stays open.** The **trademark** on "Cistern" is not registered. Apache 2.0 grants no mark
rights, so until it is, anyone may sell "managed Cistern" against this offering — which matters
precisely and only once there is a paid host. Flagged here because this ADR is what creates that
exposure; the decision is not taken in it.

## References

- #103 (T7.11), and `enrichmeai/cistern-cloud` C1–C9
- #65 — the path-prefix scope decision this ADR relies on
- #118 (T6.4), #120 (T6.6) — the authentication and listing prerequisites
- [ADR 0002](0002-production-posture.md) — when an instance may face the internet
- `COMMERCIAL.md`, `docs/ideas/portable-identity.md`
