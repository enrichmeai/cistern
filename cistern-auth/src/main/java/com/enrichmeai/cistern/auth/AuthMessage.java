package com.enrichmeai.cistern.auth;

/**
 * cistern-auth's message catalogue (ground rule 7): every piece of human-readable text this
 * module produces — exception messages, log lines, the detail of a token verdict — is a
 * constant here, never text inlined at a throw or log site.
 *
 * <p>Templates are {@link String#format} patterns; every placeholder is {@code %s}, so a
 * template can be exercised with any arguments (see {@code AuthMessageTest}).
 */
public enum AuthMessage {

    // ---------------------------------------------------------------- verdict details

    // One entry per JwtRejectionReason: the detail that accompanies the reason in a log line.

    REASON_MALFORMED("not a signed JWT: %s"),
    REASON_ALGORITHM_NOT_ACCEPTED("JWS algorithm %s is not accepted; accepted: %s"),
    REASON_KEY_UNKNOWN(
            "no published signing key matches the token's header (kid %s), even after"
                    + " refreshing the key set"),
    REASON_KEYS_UNAVAILABLE("the issuer's signing keys could not be obtained: %s"),
    REASON_SIGNATURE_INVALID("the signature does not verify against the published key(s) for kid %s"),
    REASON_ISSUER_MISMATCH("iss is %s; this pod trusts %s"),
    REASON_AUDIENCE_MISMATCH("aud is %s; this pod accepts %s"),
    REASON_EXPIRY_MISSING("the token has no exp claim"),
    REASON_EXPIRED("expired at %s (now %s, allowing %s of skew)"),
    REASON_NOT_YET_VALID("not valid before %s (now %s, allowing %s of skew)"),
    REASON_WEBID_MISSING("no WebID could be derived: %s"),
    REASON_WEBID_INVALID("the derived WebID is not an absolute URI: %s"),

    /** RFC 9449 §6: without a confirmation claim the token is bearer-equivalent. */
    REASON_CONFIRMATION_MISSING("no cnf.jkt: the token is not bound to any DPoP key"),

    // ---- DPoP proof validation, RFC 9449 §4.3, one per numbered step ----

    /** §4.3 step 1. */
    DPOP_HEADER_REPEATED("the request carried %s DPoP header fields; exactly one is allowed"),
    /** §4.3 step 2. */
    DPOP_MALFORMED("the DPoP header is not a well-formed JWT: %s"),
    /** §4.3 step 3. */
    DPOP_CLAIM_MISSING("the DPoP proof omits the required claim %s"),
    /** §4.3 step 4. */
    DPOP_TYP_UNEXPECTED("the DPoP proof declares typ %s, not dpop+jwt"),
    /** §4.3 step 5. */
    DPOP_ALG_NOT_ACCEPTED("the DPoP proof is signed with %s; accepted: %s"),
    /** §4.3 step 6. */
    DPOP_SIGNATURE_INVALID("the DPoP proof does not verify against the key it carries"),
    /** §4.3 step 7 — a proof carrying a private key is a client that has leaked its own secret. */
    DPOP_JWK_HAS_PRIVATE_KEY("the DPoP proof's jwk carries private key material"),
    /** §4.3 step 8. */
    DPOP_HTM_MISMATCH("the DPoP proof is for method %s; this request is %s"),
    /** §4.3 step 9. */
    DPOP_HTU_MISMATCH("the DPoP proof is for %s; this request is for %s"),
    /** §4.3 step 11. */
    DPOP_IAT_OUTSIDE_WINDOW("the DPoP proof was created at %s; now %s, accepting %s either way"),
    /** §4.3 step 11, replay half: a jti seen before inside the acceptance window. */
    DPOP_JTI_REPLAYED("the DPoP proof jti %s has already been used"),
    /** The replay cache is full, so uniqueness cannot be guaranteed; refuse rather than guess. */
    DPOP_REPLAY_CACHE_FULL("the DPoP replay cache is at its bound of %s entries"),
    /** §4.3 step 12, first half. */
    DPOP_ATH_MISMATCH("the DPoP proof's ath does not hash to the presented access token"),
    /** §4.3 step 12, second half — the proof key is not the key the token is bound to. */
    DPOP_THUMBPRINT_MISMATCH("the DPoP proof key is %s; the access token is bound to %s"),
    /** The proof carries no ath, but an access token was presented alongside it. */
    DPOP_ATH_MISSING("an access token was presented but the DPoP proof carries no ath"),

    /** Construction-time invariants of the replay cache. */
    DPOP_RETENTION_INVALID("jti retention must be positive, was %s"),
    DPOP_BOUND_INVALID("the jti cache bound must be positive, was %s"),
    DPOP_METHOD_BLANK("the request method cannot be blank"),
    DPOP_TARGET_NOT_ABSOLUTE("the request target must be an absolute URI, was %s"),

    // ---- WebID dereferencing, T4.3 ----
    REASON_WEBID_SCHEME_REFUSED("a WebID is only dereferenced over https, not %s"),
    REASON_WEBID_ADDRESS_REFUSED("the WebID host %s resolves to a non-public address"),
    REASON_WEBID_UNREACHABLE("the WebID document could not be fetched: %s"),
    REASON_WEBID_UNPARSEABLE("the WebID document is not parseable RDF: %s"),
    REASON_WEBID_ISSUER_NOT_NAMED("the WebID does not name %s as a solid:oidcIssuer (names: %s)"),
    WEBID_TIMEOUT_INVALID("the WebID fetch timeout must be positive, was %s"),
    WEBID_REDIRECTS_INVALID("the WebID redirect cap cannot be negative, was %s"),
    WEBID_BODY_CAP_INVALID("the WebID body cap must be positive, was %s"),

    /** A DPoP proof that did not bind the request, at the resolver boundary. */
    DPOP_REJECTED("DPoP proof rejected: %s — %s"),
    SOLID_RESOLVER_WIRED("Solid-OIDC resolver wired: base %s, proof window %s, WebID cache %s"),
    ISSUER_BOUND_INVALID("the discovered-issuer bound must be positive, was %s"),

    /** The token names an issuer, but not one this pod will fetch keys from. */
    REASON_ISSUER_UNTRUSTED("the token names issuer %s, which this pod does not trust"),

    /** {@code iss} is absent, or present and not an absolute URI. */
    REASON_ISSUER_INVALID("iss is not an absolute URI: %s"),
    REASON_VERIFICATION_ERROR("verification failed unexpectedly: %s"),

    // ---------------------------------------------------------------- WebID mapping

    /** {@code webid-claim}: the named claim is absent, or is not a string. */
    WEBID_CLAIM_ABSENT("claim '%s' is absent or not a string"),

    /** {@code webid-template}: a {@code {placeholder}} names a claim the token lacks. */
    WEBID_TEMPLATE_PLACEHOLDER_UNRESOLVED("template placeholder {%s} names no string claim"),

    // ---------------------------------------------------------------- key sets

    /** A key set was fetched. Logged at DEBUG. */
    JWKS_LOADED("Loaded %s key(s) from <%s>"),

    /** The key set could not be fetched or parsed. The exception message; logged by the resolver. */
    JWKS_FETCH_FAILED("Could not load the signing keys from <%s>: %s"),

    /** The discovery document could not be fetched or parsed. */
    DISCOVERY_FAILED("Could not read the OpenID discovery document at <%s>: %s"),

    /** The discovery document parsed but names no key set. */
    DISCOVERY_MISSING_JWKS_URI("The OpenID discovery document at <%s> has no jwks_uri member"),

    /**
     * A refresh was asked for too soon after the last fetch. Logged at DEBUG. This is the
     * defence against a stream of tokens with made-up kids turning every request into a
     * round trip to the issuer.
     */
    JWKS_REFRESH_RATE_LIMITED(
            "Not refreshing the key set: last fetched %s ago, minimum interval %s"),

    // ---------------------------------------------------------------- configuration

    /** {@link OidcIssuer} restates what {@code CisternProperties.Oidc} already enforces at bind time. */
    OIDC_ISSUER_INVALID("An OIDC issuer must be an absolute URI: %s"),
    OIDC_AUDIENCES_REQUIRED("An OIDC issuer needs at least one accepted audience"),
    OIDC_CLOCK_SKEW_NEGATIVE("Clock skew must not be negative: %s"),

    /** {@code webid-template} with no {@code {placeholder}} would give every token one WebID. */
    WEBID_TEMPLATE_HAS_NO_PLACEHOLDER(
            "A WebID template must contain at least one {claim} placeholder: %s"),

    /** {@code webid-claim} must name something. */
    WEBID_CLAIM_NAME_BLANK("A WebID claim name must not be blank"),

    // ---------------------------------------------------------------- resolver log lines

    /** A bearer JWT authenticated nobody. Level depends on the reason. */
    TOKEN_REJECTED("Bearer JWT rejected (%s): %s"),

    /** A bearer JWT authenticated a WebID. Logged at DEBUG. */
    TOKEN_ACCEPTED("Bearer JWT accepted as <%s> (iss %s, sub %s)"),

    /** Startup: what the OIDC resolver was configured with. Logged at INFO. */
    OIDC_RESOLVER_WIRED(
            "OIDC JWT resolver: issuer <%s>, audiences %s, WebID via %s, clock skew %s");

    private final String template;

    AuthMessage(String template) {
        this.template = template;
    }

    /** This message with {@code args} substituted. */
    public String format(Object... args) {
        return String.format(template, args);
    }
}
