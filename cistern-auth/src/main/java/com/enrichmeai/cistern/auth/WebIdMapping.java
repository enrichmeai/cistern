package com.enrichmeai.cistern.auth;

import com.nimbusds.jwt.JWTClaimsSet;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How the WebID is found in a verified token's claims (T4.0, #88).
 *
 * <p>The one thing an issuer cannot tell us. A Solid-aware issuer puts it in the
 * {@code webid} claim (Solid-OIDC §5) — that is {@link Claim}. An ordinary OIDC issuer such
 * as Keycloak has users but no WebIDs, and then the pod operator decides how a user becomes
 * one — that is {@link Template}, e.g. {@code {iss}/users/{sub}#me}, or a claim the operator
 * has mapped into the token themselves (the fixture realm maps a {@code webid} user attribute).
 *
 * <p>Sealed: the two shapes are the whole set the configuration can express
 * ({@code webid-claim} xor {@code webid-template}), and adding a third means deciding what its
 * configuration key is.
 */
public sealed interface WebIdMapping permits WebIdMapping.Claim, WebIdMapping.Template {

    /** The WebID the claims identify, or the reason they identify nobody. */
    Result webIdOf(JWTClaimsSet claims);

    /** What a mapping yields. */
    sealed interface Result permits Result.WebId, Result.Unmapped {

        /** A WebID was found. */
        record WebId(URI uri) implements Result {

            public WebId {
                Objects.requireNonNull(uri, "uri");
            }
        }

        /**
         * No WebID. {@code reason} is one of the two WebID reasons; {@code detail} is the
         * argument its {@link JwtRejectionReason#describe} takes — which claim was missing, or
         * what the non-URI value was.
         */
        record Unmapped(JwtRejectionReason reason, String detail) implements Result {

            public Unmapped {
                Objects.requireNonNull(reason, "reason");
                Objects.requireNonNull(detail, "detail");
            }
        }
    }

    /**
     * The WebID is the string value of one claim.
     *
     * @param claimName the claim, e.g. {@code webid}
     */
    record Claim(String claimName) implements WebIdMapping {

        public Claim {
            Objects.requireNonNull(claimName, "claimName");
            if (claimName.isBlank()) {
                throw new IllegalArgumentException(AuthMessage.WEBID_CLAIM_NAME_BLANK.format());
            }
        }

        @Override
        public Result webIdOf(JWTClaimsSet claims) {
            String value = stringClaim(claims, claimName);
            if (value == null) {
                return new Result.Unmapped(JwtRejectionReason.WEBID_MISSING,
                        AuthMessage.WEBID_CLAIM_ABSENT.format(claimName));
            }
            return asWebId(value);
        }
    }

    /**
     * The WebID is built from claims: every {@code {name}} in the template is replaced by the
     * string value of claim {@code name}. {@code iss} and {@code sub} are the usual ones —
     * {@code {iss}/users/{sub}#me} — but any string claim works.
     *
     * @param template the template; must contain at least one placeholder, or every token would
     *                 map to the same WebID
     */
    record Template(String template) implements WebIdMapping {

        /** {@code {claim-name}}: letters, digits and the punctuation claim names use. */
        private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z0-9_.:-]+)}");

        public Template {
            Objects.requireNonNull(template, "template");
            if (!PLACEHOLDER.matcher(template).find()) {
                throw new IllegalArgumentException(
                        AuthMessage.WEBID_TEMPLATE_HAS_NO_PLACEHOLDER.format(template));
            }
        }

        @Override
        public Result webIdOf(JWTClaimsSet claims) {
            Matcher matcher = PLACEHOLDER.matcher(template);
            StringBuilder out = new StringBuilder();
            while (matcher.find()) {
                String name = matcher.group(1);
                String value = stringClaim(claims, name);
                if (value == null) {
                    return new Result.Unmapped(JwtRejectionReason.WEBID_MISSING,
                            AuthMessage.WEBID_TEMPLATE_PLACEHOLDER_UNRESOLVED.format(name));
                }
                matcher.appendReplacement(out, Matcher.quoteReplacement(value));
            }
            matcher.appendTail(out);
            return asWebId(out.toString());
        }
    }

    /** The claim's value if it is a string; null when absent or of another type. */
    private static String stringClaim(JWTClaimsSet claims, String name) {
        Object value = claims.getClaim(name);
        return value instanceof String string ? string : null;
    }

    /** A WebID is an absolute URI, or it is not a WebID. */
    private static Result asWebId(String candidate) {
        try {
            URI uri = new URI(candidate);
            if (!uri.isAbsolute()) {
                return new Result.Unmapped(JwtRejectionReason.WEBID_INVALID, candidate);
            }
            return new Result.WebId(uri);
        } catch (URISyntaxException e) {
            return new Result.Unmapped(JwtRejectionReason.WEBID_INVALID, candidate);
        }
    }
}
