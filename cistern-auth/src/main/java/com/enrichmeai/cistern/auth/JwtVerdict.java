package com.enrichmeai.cistern.auth;

import com.nimbusds.jwt.JWTClaimsSet;

import java.util.Objects;

/**
 * The outcome of verifying a bearer JWT (T4.0, #88): either its claims may be trusted, or
 * they may not and here is why.
 *
 * <p>Sealed, so the resolver's handling of it is an exhaustive switch and a new outcome
 * cannot be added without deciding what the resolver does with it.
 */
public sealed interface JwtVerdict permits JwtVerdict.Accepted, JwtVerdict.Rejected {

    /**
     * Signature verified against a published key of the trusted issuer, and every claim check
     * passed. The claims are trustworthy from here on.
     *
     * @param claims the verified claims
     */
    record Accepted(JWTClaimsSet claims) implements JwtVerdict {

        public Accepted {
            Objects.requireNonNull(claims, "claims");
        }
    }

    /**
     * The token authenticates nobody.
     *
     * @param reason which check failed
     * @param detail the reason's description with the specifics substituted, from
     *               {@link JwtRejectionReason#describe}
     */
    record Rejected(JwtRejectionReason reason, String detail) implements JwtVerdict {

        public Rejected {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(detail, "detail");
        }

        /** A rejection for {@code reason}, its detail formatted from {@code args}. */
        public static Rejected of(JwtRejectionReason reason, Object... args) {
            return new Rejected(reason, reason.describe(args));
        }
    }
}
