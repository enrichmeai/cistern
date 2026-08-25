package com.enrichmeai.cistern.auth;

import java.util.Objects;

/**
 * The outcome of verifying a Solid-OIDC access token (T4.1): either it names an identity that
 * may be carried forward, or it does not and here is why.
 *
 * <p>Sealed for the same reason {@link JwtVerdict} is: handling it is an exhaustive switch, so
 * a new outcome cannot appear without every caller deciding what to do with it.
 */
public sealed interface SolidOidcVerdict
        permits SolidOidcVerdict.Accepted, SolidOidcVerdict.Rejected {

    /**
     * The token verified and carries everything Solid-OIDC requires of it.
     *
     * <p>Not yet an authenticated request — see {@link SolidOidcIdentity}.
     */
    record Accepted(SolidOidcIdentity identity) implements SolidOidcVerdict {

        public Accepted {
            Objects.requireNonNull(identity, "identity");
        }
    }

    /** The token authenticates nobody. */
    record Rejected(JwtRejectionReason reason, String detail) implements SolidOidcVerdict {

        public Rejected {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(detail, "detail");
        }

        static Rejected of(JwtRejectionReason reason, Object... args) {
            return new Rejected(reason, reason.describe(args));
        }

        /** The same rejection a {@link JwtVerdict.Rejected} already carries. */
        static Rejected of(JwtVerdict.Rejected rejected) {
            return new Rejected(rejected.reason(), rejected.detail());
        }
    }
}
