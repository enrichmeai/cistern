package com.enrichmeai.cistern.webflux;

/**
 * The query parameters of a receipts request (T5.9) — a closed set, so an enum (ground rule
 * 7), and one shared by the filter that recognises the request and the handler that answers
 * it, so they cannot spell a parameter differently.
 *
 * <p>{@code GET <resource>?receipts[&from=<instant>][&to=<instant>][&agent=<webid>]}
 */
enum ReceiptsParameter {

    /**
     * The marker. Its presence, with or without a value, turns a {@code GET} into a receipts
     * query, and changes what the request requires: Control on the resource, not Read.
     */
    RECEIPTS("receipts"),

    /** Inclusive lower bound of the interval, ISO 8601 instant; the beginning of time if absent. */
    FROM("from"),

    /** Exclusive upper bound of the interval, ISO 8601 instant; the end of time if absent. */
    TO("to"),

    /**
     * A WebID to restrict to. On the pod root, that agent's receipts across the pod (Control on
     * the root — the owner's question); on any other resource, that agent's receipts for that
     * resource alone.
     */
    AGENT("agent");

    private final String name;

    ReceiptsParameter(String name) {
        this.name = name;
    }

    /** The parameter name as it appears in the query string. */
    String parameterName() {
        return name;
    }
}
