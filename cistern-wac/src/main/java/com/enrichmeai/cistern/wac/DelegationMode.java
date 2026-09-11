package com.enrichmeai.cistern.wac;

/**
 * Whether {@link WacEngine} reads and evaluates Cistern's delegation terms
 * ({@code cistern:client} today; {@code cistern:validUntil} under T5.8) — the value of
 * {@code cistern.wac.delegation.enabled} (ADR 0004, AD-DEL-6 seam 5; AD-16).
 *
 * <p>One switch for every delegation term rather than one per term: for the conformance
 * harness's purposes they are one feature, and a per-term flag would let half the extension
 * reach the harness. {@link #DISABLED} is the default, and under it the engine is byte-for-byte
 * the engine that existed before delegation: {@link WacEngine#parse} does not read the terms, so
 * no authorization is ever constrained, no decision is ever narrowed, and no receipt carries a
 * narrowing term.
 *
 * <p>An enum rather than a {@code boolean} parameter (ground rule 7), so that a constructor
 * call reads as what it configures.
 */
public enum DelegationMode {

    /** Delegation terms are ignored: plain Web Access Control, as the harness expects. */
    DISABLED,

    /** Delegation terms are read, and narrow what the portable terms grant (AD-15). */
    ENABLED;

    /** The mode a configuration flag selects. */
    public static DelegationMode of(boolean enabled) {
        return enabled ? ENABLED : DISABLED;
    }

    /** Whether delegation terms are read and evaluated. */
    public boolean isEnabled() {
        return this == ENABLED;
    }
}
