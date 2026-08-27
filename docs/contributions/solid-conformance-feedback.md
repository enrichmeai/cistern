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
| Same harness, `ath` added | **24 passed / 11 failed** | **613 passed / 28 failed** |

The gap between those two rows is not a property of the server. It is the cost of one absent
claim in the test client, and it is the difference between a specification-conformant
implementation receiving no signal at all and receiving 613 passing scenarios plus an
itemised list of 28 real defects to fix.

**The uncomfortable implication, stated plainly because it is the point:** the stricter a
server is about §4.3, the worse it scores — and a server that ignores `ath` entirely does
better on the official harness than one that implements the RFC. That is an incentive
pointing the wrong way, and it is fixable in about a line.

**We would like to contribute the fix**, and we have it. Say the word and we will open a PR
rather than a third issue.

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

Cistern is an independently written JVM implementation (Apache 2.0, public). Once it is running
the harness we expect to surface tests that encode reference-implementation behaviour rather
than specification text — the most useful thing a second implementation produces. We will
report those as we find them, with reproductions.

With thanks for the specifications, the harness, and CSS. None of this work would have been
possible to do carefully without all three.
