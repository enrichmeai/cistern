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
