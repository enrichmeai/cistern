package com.enrichmeai.cistern.auth;

import java.util.Objects;

/**
 * Whether a WebID authorises the issuer that minted a token for it (T4.3, Solid-OIDC §5).
 *
 * <p>The check the whole trust model rests on: the token asserts a WebID, and only the WebID's
 * own document says which issuers may assert it. Sealed, so a caller cannot forget the refused
 * case — and refusing is the direction this fails in, per the owner's ruling: an unreachable
 * WebID authenticates nobody.
 */
public sealed interface WebIdVerdict permits WebIdVerdict.Verified, WebIdVerdict.Refused {

    /** The WebID document names this issuer in a {@code solid:oidcIssuer} triple. */
    record Verified() implements WebIdVerdict {

        private static final Verified INSTANCE = new Verified();

        public static Verified instance() {
            return INSTANCE;
        }
    }

    /** It does not, or could not be checked. Either way the request authenticates nobody. */
    record Refused(JwtRejectionReason reason, String detail) implements WebIdVerdict {

        public Refused {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(detail, "detail");
        }

        static Refused of(JwtRejectionReason reason, Object... args) {
            return new Refused(reason, reason.describe(args));
        }
    }
}
