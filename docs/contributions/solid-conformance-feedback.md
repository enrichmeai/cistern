# Feedback for the Solid conformance harness and specification tests

Draft, not yet filed. Written from building a second, independent Solid server
implementation (Cistern) against the published specifications and the official harness.

Everything below is offered in the spirit the harness exists for. A specification only gets
tested when a second implementation reads it independently, and every item here is something
the Solid community's own work made it possible for us to find.

## 1. A new implementation gets no conformance signal until Solid-OIDC is complete

**Observed:** the harness registers authenticated `alice`/`bob` clients during
`registerClients` → `prepareServer`, before executing any feature. There is no skip-auth or
unauthenticated-subset mode. A server without Solid-OIDC therefore stops at REGISTER CLIENTS
and emits no results report — not a partial score, but nothing at all.

**Reproduced:** Cistern, with a complete HTTP layer and a passing WAC engine, scored
0 passed / 0 failed / **41 untested** for six weeks. The run failed on a 404 for a WebID
document, before the first assertion. Recorded in our `cth/BASELINE.md` with dates and image
digests.

**Why it is worth changing:** authentication is the *last* thing an implementation builds and
among the hardest. Under the current design an implementer builds the entire protocol and
access-control surface with no external signal, then turns on authentication and discovers
what they got wrong months earlier. Conformance is most valuable as a gradient, not a gate.

**Suggestion:** a documented subset that runs without registered clients — even a handful of
protocol assertions against public resources would let an implementation track progress from
its first week. If that subset already exists and we missed it, that is a documentation gap
worth closing, and we would happily send the doc change.

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
