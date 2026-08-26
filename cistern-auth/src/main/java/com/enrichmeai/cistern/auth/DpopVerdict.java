package com.enrichmeai.cistern.auth;

import java.util.Objects;

/**
 * The outcome of checking a DPoP proof (T4.2, RFC 9449 §4.3).
 *
 * <p>Sealed: twelve checks, one outcome, and adding a thirteenth should force every caller to
 * say what it does about it.
 */
public sealed interface DpopVerdict permits DpopVerdict.Accepted, DpopVerdict.Rejected {

    /**
     * The proof is well-formed, signed by the key it carries, for this method and target,
     * inside the acceptance window, not replayed, and bound to the presented access token.
     *
     * @param thumbprint the RFC 7638 thumbprint of the proof key — the same value the access
     *                   token carries in {@code cnf.jkt}, confirmed equal
     */
    record Accepted(String thumbprint) implements DpopVerdict {

        public Accepted {
            Objects.requireNonNull(thumbprint, "thumbprint");
        }
    }

    /** The proof binds nothing. */
    record Rejected(DpopRejectionReason reason, String detail) implements DpopVerdict {

        public Rejected {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(detail, "detail");
        }

        static Rejected of(DpopRejectionReason reason, Object... args) {
            return new Rejected(reason, reason.describe(args));
        }
    }
}
