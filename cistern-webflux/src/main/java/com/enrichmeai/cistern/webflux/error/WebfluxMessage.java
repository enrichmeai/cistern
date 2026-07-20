package com.enrichmeai.cistern.webflux.error;

/**
 * Message catalogue for {@code cistern-webflux} (CLAUDE.md ground rule 7): every piece of
 * human-readable text this module emits — problem titles, problem details, log lines — is a
 * constant here, never a literal at the throw or log site.
 *
 * <p>Titles are the RFC 9457 {@code title} member and so must not vary between occurrences of
 * the same problem type (§3.1.3): they carry no format arguments. Occurrence-specific text
 * belongs in {@code detail}, which comes from the domain exception.
 */
public enum WebfluxMessage {

    // --- RFC 9457 titles, one per Cistern problem type (see ProblemType) ---

    TITLE_BAD_INPUT("Malformed request entity"),
    TITLE_UNPROCESSABLE_ENTITY("Request entity violates a protocol constraint"),
    TITLE_NOT_FOUND("Resource not found"),
    TITLE_CONFLICT("Request conflicts with the state of the resource"),
    TITLE_PRECONDITION_FAILED("Precondition failed"),
    TITLE_AUTHENTICATION_REQUIRED("Authentication required"),
    TITLE_ACCESS_DENIED("Access denied"),

    // --- Details ---

    /**
     * The only detail any 5xx response ever carries. RFC 9457 §5 warns that problem details
     * must not leak information about the system; the real cause goes to the log instead.
     * Wording follows RFC 9110 §15.6.1's definition of 500 rather than describing the fault.
     */
    DETAIL_INTERNAL_ERROR(
            "The server encountered an unexpected condition that prevented it from fulfilling the request."),

    // --- Log lines ---

    LOG_SERVER_ERROR("%s %s failed with %s"),
    LOG_CLIENT_ERROR("%s %s rejected with %s: %s"),
    /** Fires when a {@code CisternException} subtype exists that the registry has no row for. */
    LOG_UNMAPPED_DOMAIN_ERROR(
            "No problem type registered for %s — falling back to 500. Add a row to ProblemMapper.DOMAIN_PROBLEMS."),

    // --- Programming errors ---

    PROBLEM_MEMBER_REQUIRED("RFC 9457 member '%s' must not be null");

    private final String template;

    WebfluxMessage(String template) {
        this.template = template;
    }

    /** Renders the message, substituting {@code String.format} arguments. */
    public String format(Object... args) {
        return args.length == 0 ? template : String.format(template, args);
    }
}
