package com.enrichmeai.cistern.wac;

import com.enrichmeai.cistern.core.Agent;

/**
 * How an authorization decision ended, as the audit trail records it (T5.9). A closed set of
 * three, so an enum (ground rule 7).
 *
 * <p>The two denials are kept apart for the same reason the HTTP layer answers them with
 * different status codes: "nobody was refused" and "somebody was refused" are different
 * facts about the pod. A receipt full of {@link #DENIED_UNAUTHENTICATED} is a scanner or a
 * misconfigured client; a receipt with {@link #DENIED_FORBIDDEN} names an agent who tried
 * something outside their grant, which is the line the owner actually wants to see.
 */
public enum Outcome {

    /** Every requirement was granted; the request proceeded. */
    ALLOWED,

    /** Refused, and the request proved no identity. */
    DENIED_UNAUTHENTICATED,

    /** Refused, and the request proved an identity that was not permitted. */
    DENIED_FORBIDDEN;

    /**
     * The outcome of {@code verdict} for {@code agent}: allowed, or the denial that fits how the
     * request identified itself. The one place the split is decided, so the record and the HTTP
     * status (401 vs 403) cannot disagree.
     */
    public static Outcome of(AccessVerdict verdict, Agent agent) {
        if (verdict.allowed()) {
            return ALLOWED;
        }
        return agent.isAuthenticated() ? DENIED_FORBIDDEN : DENIED_UNAUTHENTICATED;
    }

    /** Whether this outcome let the request proceed. */
    public boolean isAllowed() {
        return this == ALLOWED;
    }
}
