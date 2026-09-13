package com.enrichmeai.cistern.wac;

/**
 * The members of a serialized {@link DecisionRecord} — the column names of the decision log
 * (T5.9). An enum rather than string literals at the write and read sites (ground rule 7), and
 * one enum for both, so a field cannot be written under one name and looked up under another.
 *
 * <p>Every {@linkplain #isAlwaysPresent() always-present} field is on every line, {@code null}
 * where the value is absent ({@link #AGENT} for an anonymous request, {@link #DECIDED_BY} for a
 * denial), so a consumer can rely on the shape without checking for missing keys.
 * {@link #NARROWED_BY} is the one field that is not: it is written only when a delegation term
 * narrowed the decision (ADR 0004 §6, AD-DEL-5), so that a pod with no delegation writes lines
 * byte-identical to those it wrote before the field existed, and a log written before it parses
 * unchanged. One enum-valued field rather than a boolean per delegation dimension (AD-DEL-6
 * seam 3): a later term adds a {@link DelegationTerm} constant, never a column here.
 */
public enum DecisionField {

    /** {@link DecisionRecord#at()}, ISO 8601 instant. */
    AT("at", true),

    /** {@link DecisionRecord#agent()}'s WebID, or {@code null} for anonymous. */
    AGENT("agent", true),

    /** {@link DecisionRecord#target()}, the request's target URI. */
    TARGET("target", true),

    /** {@link DecisionRecord#required()}, the {@link AccessMode} name. */
    REQUIRED("required", true),

    /** {@link DecisionRecord#outcome()}, the {@link Outcome} name. */
    OUTCOME("outcome", true),

    /** {@link DecisionRecord#decidedBy()}, the ACL resource URI, or {@code null} on a denial. */
    DECIDED_BY("decidedBy", true),

    /** {@link DecisionRecord#requestId()}. */
    REQUEST_ID("requestId", true),

    /**
     * {@link DecisionRecord#narrowedBy()}, the {@link DelegationTerm} name. Present only when a
     * delegation term bound; a line without it is a decision no delegation touched.
     */
    NARROWED_BY("narrowedBy", false);

    private final String key;
    private final boolean alwaysPresent;

    DecisionField(String key, boolean alwaysPresent) {
        this.key = key;
        this.alwaysPresent = alwaysPresent;
    }

    /** The JSON member name. */
    public String key() {
        return key;
    }

    /** Whether every line carries this member ({@code null} when the value is absent). */
    public boolean isAlwaysPresent() {
        return alwaysPresent;
    }
}
