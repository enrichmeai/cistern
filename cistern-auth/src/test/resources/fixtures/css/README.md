# Solid-OIDC fixtures — captured from the Community Solid Server 7.2.0

Every token, key set and proof here was produced by a real Solid identity provider (ground
rule 6): **CSS 7.2.0**, started with the stock configuration as

```bash
npx @solid/community-server@7.2.0 -p 3939 -c @css:config/default.json -f ./data
```

and driven by [`capture.mjs`](capture.mjs) on **2026-08-23 (04:04 UTC)**. The script *is* the
capture procedure; run it against a **fresh** server (empty `-f` directory) and it regenerates
the whole set — it creates the account, the pod, the WebID and the client credential, then
performs a real DPoP-bound `client_credentials` token request. Re-running against a server that
already holds the `alice` pod fails at pod creation by design, rather than quietly capturing
something different.

Why CSS and not the Keycloak realm next door: the Keycloak set (T4.0) is a plain bearer JWT
from an application's own IdP. These are **Solid-OIDC** tokens — a different shape, a different
`aud`, DPoP-bound — and T4.1/T4.2 are the conformance path, so the fixtures have to come from
an implementation the Solid ecosystem actually uses.

## What a real Solid-OIDC access token turned out to contain

`access-token.decoded.json` is the decoded form of `access-token.jwt`, kept beside it so the
shape is reviewable without a JWT tool. Header `{"alg":"ES256","typ":"at+jwt","kid":…}`, and:

| Claim | Value in the capture | Note for the verifier |
|---|---|---|
| `webid` | `http://localhost:3939/alice/profile/card#me` | present on the **access** token — this is the principal |
| `client_id` | `cistern-capture_767ce9e3-…` | **present**; see the finding below |
| `azp` | *absent* | Solid-OIDC mandates `azp` on the **ID** token; the access token does not carry it |
| `iss` | `http://localhost:3939/` | note the trailing slash |
| `aud` | `"solid"` | a fixed literal string, **not** the resource server's URL |
| `cnf.jkt` | `jAFaFuyYHcJbN34RwAXGdmwEOnm9x4UB21prJ0ZzFmI` | matches the thumbprint of the DPoP key (verified at capture) |
| `exp` − `iat` | 600 s | see "On expiry" |

**`aud` is `"solid"`, not our base URL.** A verifier that checks `aud` against its own origin —
which is what the T4.0 Keycloak path does, where the realm has an audience mapper — rejects
every real Solid-OIDC token. The audience check for this path is `aud == "solid"`, and the
binding to *this* server comes from DPoP (`htu`, T4.2) and from the WebID naming the issuer
(T4.3), not from `aud`.

**Finding for issue #89 (principal shape).** T4.1's DoD asks whether the access token carries
`client_id`/`azp`, because `Agent(webId, Optional<URI> client)` depends on the client identity
being available without an extra round trip. It is: CSS puts **`client_id` on the access
token** and does not emit `azp` there. One caveat that matters for the type — in the
client-credentials flow the value is CSS's own opaque credential id
(`cistern-capture_<uuid>`), **not** a dereferenceable URI. A Solid-OIDC client using a Client
Identifier Document would put its document URI here. So the claim is reliably present, but
`Optional<URI>` is only the right type if a non-URI `client_id` is treated as absent rather
than as a parse failure.

## On expiry — why there is no separate "expired" fixture

CSS issues 600-second access tokens and its lifetime is not configurable from the CLI, so the
Keycloak trick (a ten-year realm lifespan) is not available. Nothing is faked to work around
it: `JwtVerifier` already takes a `Clock`, so the same genuinely-issued token is the **valid**
fixture judged at a clock just after `iat` and the **expired** fixture judged at a clock past
`exp`. One real token, two verdicts, no hand-assembly.

`iat` 2026-08-23T04:04:51Z · `exp` 2026-08-23T04:14:51Z.

## Documents

| File | Captured how |
|---|---|
| `openid-configuration.json` | `GET http://localhost:3939/.well-known/openid-configuration` |
| `jwks.json` | `GET <jwks_uri>` — CSS's single ES256 signing key |
| `access-token.jwt` | `client_credentials` grant at `/.oidc/token` with a DPoP proof; verbatim |
| `access-token.decoded.json` | the above, decoded (header + claims), for review |
| `dpop-proof-token-request.jwt` | the proof sent *with* the token request (`htm=POST`, `htu=/.oidc/token`) |
| `dpop-proof-resource-request.jwt` | a proof for a subsequent resource `GET`, carrying `ath` — what T4.2 validates |

## Derived negatives — stated, not disguised

No identity provider will mint a token that fails its own verification, so the four negatives
below are **derived**, exactly as `alice-bad-signature.jwt` is in the Keycloak set. Each is
listed with what was changed and what was left alone.

| File | Derived how |
|---|---|
| `access-token-wrong-key.jwt` | the captured claims, re-signed with a locally generated ES256 key whose `kid` (`foreign-key-not-in-css-jwks`) is absent from `jwks.json` |
| `access-token-wrong-issuer.jwt` | the same, with `iss` replaced by `https://evil.example/` |
| `access-token-unusable-issuer.jwt` | the same, with `iss` replaced by `not-a-uri` — an issuer that names nothing to fetch keys from, which fails differently from one this pod merely does not trust |
| `access-token-bad-signature.jwt` | `access-token.jwt` with one bit of the signature's **first byte** flipped — header and payload are byte-for-byte what CSS issued |
| `jwks-foreign.json` | the public half of that locally generated key, so a test can assert a token verifies under the wrong key set and not under CSS's |

## Regenerating

```bash
rm -rf ./data
npx @solid/community-server@7.2.0 -p 3939 -c @css:config/default.json -f ./data &
node capture.mjs <output-dir>          # needs `npm i jose@5` alongside it
```

The script prints the decoded claims and asserts `cnf.jkt` matches the DPoP key it generated;
if that line ever reads `false`, the capture is wrong and nothing downstream should use it.

**On tampering the signature:** flipping the *last* base64url character does not work here and
the script asserts against it. An ES256 signature is 64 raw bytes, so its final character
carries four bits that decoding discards — `A` and `B` decode to the same byte, the token still
verifies, and the fixture silently tests nothing. The derivation flips a bit of the first byte
instead and fails loudly if the decoded bytes come back unchanged.


## DPoP proofs (T4.2)

`capture.mjs` writes two proofs exactly as a real client produces them, signed with the
ES256 key the access token is bound to:

| File | Signed for | Carries |
|---|---|---|
| `dpop-proof-token-request.jwt` | `POST` to the token endpoint | `htu`, `htm`, `iat`, `jti` |
| `dpop-proof-resource-request.jwt` | `GET http://localhost:3939/alice/private/note.ttl` | the above plus `ath` |

Both bindings were verified against the captured access token rather than assumed:

- `ath` equals `base64url(SHA-256(ASCII(access token)))` — RFC 9449 §4.2.
- The proof key's RFC 7638 thumbprint equals the token's `cnf.jkt`.

### Derived negatives

No correct client emits a proof that fails §4.3, so the negatives are stated derivations,
produced by [`derive-dpop-negatives.mjs`](derive-dpop-negatives.mjs) from the captured proof's
own claims. They are signed with a locally generated ES256 key (`dpop-foreign-jwk.json`),
which is why `dpop-proof-foreign-key.jwt` fails the thumbprint check and nothing else.

| File | Fails | Step |
|---|---|---|
| `dpop-proof-wrong-typ.jwt` | `typ` is `JWT` | 4 |
| `dpop-proof-bad-signature.jwt` | first byte of the signature flipped | 6 |
| `dpop-proof-private-jwk.jwt` | `jwk` carries `d` | 7 |
| `dpop-proof-wrong-htm.jwt` | `htm` is `DELETE` | 8 |
| `dpop-proof-wrong-htu.jwt` | `htu` names another origin | 9 |
| `dpop-proof-no-ath.jwt` | no `ath`, with a token presented | 12 |
| `dpop-proof-wrong-ath.jwt` | `ath` hashes to nothing | 12 |
| `dpop-proof-foreign-key.jwt` | correct `htm`/`htu`/`ath`, foreign key | 12 |

**On `dpop-proof-bad-signature.jwt`:** the corruption is a flipped bit in the *first* byte of
the decoded signature. Flipping the last base64url character does not reliably change the
signature — a 64-byte ES256 signature ends on a character whose low bits decoding discards, so
the token still verifies. That mistake was made once in the T4.1 fixtures and the derivation
now asserts the bytes actually differ.

**On `dpop-proof-private-jwk.jwt`:** Nimbus refuses a non-public key in the `jwk` header during
`JWSHeader.parse`, so this fixture is rejected as malformed before the validator's own step-7
check runs. `DpopValidatorTest` asserts on the rejection detail, so the requirement stays
covered by a test rather than by a comment.
