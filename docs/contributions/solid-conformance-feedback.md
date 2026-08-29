# Feedback for the Solid conformance harness and specification tests

Draft, not yet filed. Written from building a second, independent Solid server
implementation (Cistern) against the published specifications and the official harness.

Everything below is offered in the spirit the harness exists for. A specification only gets
tested when a second implementation reads it independently, and every item here is something
the Solid community's own work made it possible for us to find.

## 1. The precondition is provisioned accounts, not authentication

**Observed:** the harness registers authenticated `alice`/`bob` clients during
`registerClients` → `prepareServer`, before executing any feature. There is no skip-auth or
unauthenticated-subset mode. A server without Solid-OIDC therefore stops at REGISTER CLIENTS
and emits no results report — not a partial score, but nothing at all.

**Reproduced twice, and the second run is the interesting one.**

Cistern scored 0 passed / 0 failed / **41 untested** for six weeks with a complete HTTP layer
and a passing WAC engine — the run stopping on a 404 for a WebID document, before the first
assertion.

We then built the whole Solid-OIDC stack: access-token validation, DPoP proof checking to
RFC 9449 §4.3, WebID issuer verification, and the filter chain composing them. Re-ran the
harness (1.2.2, suite 0.0.19, 41 cases discovered — 26 protocol + 15 WAC) against that server.

**The score was identical: 0 / 0 / 41, stopping in REGISTER CLIENTS on a 404 for bob's WebID
document.** Both runs are dated in our `cth/BASELINE.md` with image digests.

That is the finding, and it is sharper than "no signal until Solid-OIDC is done": **completing
Solid-OIDC is not sufficient.** The precondition is that test accounts *exist and are
registerable*, which is a provisioning capability, not an authentication one. A server can have
a complete and correct token-validation stack and still learn nothing at all about its
conformance.

**Why it is worth changing:** an implementer builds the entire protocol and access-control
surface with no external signal, completes the hardest part of authentication, and still gets
zero — then discovers the actual gate was multi-user provisioning. Conformance is most valuable
as a gradient, not a gate, and the gate here is not the one the failure mode suggests.

**Suggestion:** a documented subset that runs without registered clients — the 26 protocol
cases include unauthenticated behaviour a server can be judged on long before it has accounts — even a handful of
protocol assertions against public resources would let an implementation track progress from
its first week. If that subset already exists and we missed it, that is a documentation gap
worth closing, and we would happily send the doc change.

## 1b. The harness's own client omits a claim RFC 9449 makes required, and we can measure what it costs

**Observed:** `Client.generateDpopToken` (`src/main/java/org/solid/testharness/http/Client.java`)
builds DPoP proofs with `jti`, `htm`, `htu` and `iat`. It does not set `ath`. RFC 9449 §4.3
step 12 requires `ath` on any proof accompanying an access token, and requires the resource
server to check that it hashes to that token.

A server implementing §4.3 as written therefore rejects every authenticated request the
harness makes. Ours does: the proof fails on the missing `ath`, the request falls through to
anonymous, and the run stops in PREPARE SERVER unable to find an ACL link.

**Already reported, twice.** Issues #767 (27 May 2026) and #786 (30 July 2026), both open,
neither with a comment. We are not the first to hit this; we may be the first able to put a
number on it.

**And that is the useful part — the cost is measurable.** We ran the suite twice against the
same server build, changing only one guarded line in the harness client to add `ath`:

| Harness | MustFeatures | MustScenarios |
|---|---|---|
| Official, unmodified | **0 passed / 0 failed / 41 untested** | run halts in PREPARE SERVER |
| Same harness and build, `ath` added | **24 passed / 11 failed** | **613 passed / 28 failed** |

Both rows are the same server build on 28 August 2026, so the gap between them is not a
property of the server. It is the cost of one absent claim in the test client — the difference
between a specification-conformant implementation receiving **no signal at all** and receiving
613 passing scenarios plus an itemised list of 28 real defects to fix.

Those 28 are being worked through, and the point of the table is the gap rather than either
number: as of 29 August the patched run reads 27 features and 629 scenarios passing. The
official row has not moved, and will not until an unmodified harness produces it.

**The uncomfortable implication, stated plainly because it is the point:** the stricter a
server is about §4.3, the worse it scores — and a server that ignores `ath` entirely does
better on the official harness than one that implements the RFC. That is an incentive
pointing the wrong way, and it is fixable in about a line.

**We are contributing the fix rather than filing a third issue.** The change is small — set
`ath` on proofs that accompany an access token — and it is in a pull request against this
repository. The measurement above is included there, because a report of the cost seemed more
useful to a maintainer than another report of the cause.

## 1c. The suite and the specification point different ways on `Accept` in CORS

**The specification, verbatim** (Solid Protocol, CORS section, as published 29 August 2026 —
if that text has since been revised this item may be stale and is worth re-reading before use):

> Servers SHOULD explicitly list `Accept` under `Access-Control-Allow-Headers`, because header
> field values longer than 128 characters (not uncommon for RDF-based Solid apps) would
> otherwise be blocked, despite shorter `Accept` header field values being allowed without
> explicit mention.

**The suite's `accept-acah` feature** asserts that `Access-Control-Allow-Headers` must **not**
contain `Accept` when the preflight did not request it.

Under echo semantics — reflect the headers that were asked for — those cannot both be
satisfied. Always listing `Accept` fails the suite. Echoing only what was requested departs
from the SHOULD.

**Why this is worth raising rather than shrugging at.** The specification does not merely state
a preference; it states a reason, and the reason describes a real browser behaviour. `Accept`
is CORS-safelisted only up to a value-length limit, and RDF content negotiation produces long
`Accept` values routinely. So a server that satisfies the suite's reading will block exactly
the long-`Accept` RDF requests the SHOULD exists to protect — in a browser, not in a test.
Following the suite has a cost the specification predicted.

**What we did.** We echo the request's own header list rather than enumerating a fixed one,
because §8.1 asks servers to accept "any request and combination of request headers" and the
suite holds both directions — a preflight requesting `X-CUSTOM` must see it echoed, and one not
requesting `Accept` must not see `Accept`. No fixed list satisfies both; echoing does. The
`Accept` SHOULD is the conscious trade that rides along, recorded in a comment where the echo
is configured rather than resolved locally — a disagreement between suite and specification is
raised, not decided by the implementer. We are offering this as a datapoint, not a complaint: both documents come from the
same community's work, and the disagreement is only visible from outside because we
implemented against both at once.

**What would help.** Whichever way it resolves, an implementer would benefit from the two
documents agreeing — either the feature relaxed to permit an always-listed `Accept`, or the
SHOULD amended to describe what conforming servers actually do.

## 2. Resource-server validation is left to follow from the authorization server

**Observed:** Solid-OIDC §9.2/§9.3/§8.1.1 specify the authorization server. The resource
server's obligations — which claims to require on an access token, how to bind it to a DPoP
proof, how to reach the issuer's keys — follow from that but are not enumerated in one place.

We think this is a reasonable division of labour: the authorization server is the half where a
mistake issues bad tokens to everyone. But it means a resource-server implementer has to
derive behaviour from a running implementation. We did that against CSS 7.2.0 and it worked;
a short non-normative "what a resource server must check" note would save the next
implementation the same detective work.

## 3. Three observations from a real token, for whatever they are worth

Captured from CSS 7.2.0, stock config, DPoP-bound `client_credentials` grant. Fixtures and the
capture script are public in our repository.

| Observation | Detail |
|---|---|
| `client_id` on the **access** token | present. `azp` is not — it is mandated on the ID token. A resource server wanting the client identity can read it here without a second round trip, though under client-credentials it is an opaque identifier rather than a Client Identifier Document URI. |
| `aud` | the literal string `"solid"`, not the resource server's origin. A pod validating `aud` against its own URL rejects every valid token. This surprised us and cost time. |
| `cnf.jkt` | present and correct — it matched the RFC 7638 thumbprint of the proof key, verified independently. |

If any of these would be useful as test cases or as a note in the test documentation, we are
glad to write them up in KarateDSL and submit them with the requirement references and review
status the repository asks for.

## What we can offer

Cistern is an independently written JVM implementation (Apache 2.0, public). It is now running
the suite, and the 28 failing scenarios in that run are being worked through as our own defects
— the first of them, a missing `Link rel="acl"` on responses where WAC requires it, was found
by this harness and is already fixed.

That is the exchange we would like to keep up: the suite tells us where we are wrong, and where
we think the suite is wrong we bring a patch and the measurement rather than an opinion. Where
a test turns out to encode reference-implementation behaviour rather than specification text,
we will report it that way, with a reproduction.

With thanks for the specifications, the harness, and CSS. None of this work would have been
possible to do carefully without all three.
