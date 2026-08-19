package com.enrichmeai.cistern.auth;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/**
 * The one identity provider whose tokens this pod trusts, and on what terms (T4.0, #88).
 *
 * <p>Built from {@code cistern.auth.oidc.*}; the validation of those keys happens where they
 * are bound ({@code CisternProperties.Oidc}), so this record only restates the invariants it
 * relies on rather than duplicating the messages.
 *
 * @param issuer    the issuer identifier, compared <em>verbatim</em> against {@code iss} — OIDC
 *                  Core §3.1.3.7 rule 2: "MUST exactly match"; no normalisation
 * @param audiences a token's {@code aud} must contain at least one of these
 * @param clockSkew tolerance applied to {@code exp} and {@code nbf}
 */
public record OidcIssuer(URI issuer, Set<String> audiences, Duration clockSkew) {

    public OidcIssuer {
        Objects.requireNonNull(issuer, "issuer");
        Objects.requireNonNull(audiences, "audiences");
        Objects.requireNonNull(clockSkew, "clockSkew");
        if (!issuer.isAbsolute()) {
            throw new IllegalArgumentException(AuthMessage.OIDC_ISSUER_INVALID.format(issuer));
        }
        if (audiences.isEmpty()) {
            throw new IllegalArgumentException(AuthMessage.OIDC_AUDIENCES_REQUIRED.format());
        }
        if (clockSkew.isNegative()) {
            throw new IllegalArgumentException(AuthMessage.OIDC_CLOCK_SKEW_NEGATIVE.format(clockSkew));
        }
        audiences = Set.copyOf(audiences);
    }

    /** OIDC Discovery §4.1: the discovery document lives at {@code {issuer}/.well-known/openid-configuration}. */
    public URI discoveryDocument() {
        String base = issuer.toString();
        while (base.endsWith(PATH_SEPARATOR)) {
            base = base.substring(0, base.length() - PATH_SEPARATOR.length());
        }
        return URI.create(base + WELL_KNOWN_OPENID_CONFIGURATION);
    }

    /** OIDC Discovery §4: the well-known path suffix. */
    static final String WELL_KNOWN_OPENID_CONFIGURATION = "/.well-known/openid-configuration";

    private static final String PATH_SEPARATOR = "/";
}
