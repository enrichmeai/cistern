package com.enrichmeai.cistern.wac;

/**
 * The members of a serialized {@link DecisionRecord} — the column names of the decision log
 * (T5.9). An enum rather than string literals at the write and read sites (ground rule 7), and
 * one enum for both, so a field cannot be written under one name and looked up under another.
 *
 * <p>Every field is present on every line, {@code null} where the value is absent
 * ({@link #AGENT} for an anonymous request, {@link #DECIDED_BY} for a denial), so a consumer
 * can rely on the shape without checking for missing keys.
 */
public enum DecisionField {

    /** {@link DecisionRecord#at()}, ISO 8601 instant. */
    AT("at"),

    /** {@link DecisionRecord#agent()}'s WebID, or {@code null} for anonymous. */
    AGENT("agent"),

    /** {@link DecisionRecord#target()}, the request's target URI. */
    TARGET("target"),

    /** {@link DecisionRecord#required()}, the {@link AccessMode} name. */
    REQUIRED("required"),

    /** {@link DecisionRecord#outcome()}, the {@link Outcome} name. */
    OUTCOME("outcome"),

    /** {@link DecisionRecord#decidedBy()}, the ACL resource URI, or {@code null} on a denial. */
    DECIDED_BY("decidedBy"),

    /** {@link DecisionRecord#requestId()}. */
    REQUEST_ID("requestId");

    private final String key;

    DecisionField(String key) {
        this.key = key;
    }

    /** The JSON member name. */
    public String key() {
        return key;
    }
}
