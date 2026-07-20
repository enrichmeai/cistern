package com.enrichmeai.cistern.core;

/**
 * cistern-core's message catalogue (ground rule 7): every exception and log message this
 * module produces is a constant here, never text inlined at a throw site. Keeping them in
 * one place makes the wording reviewable against the spec sentences it paraphrases, keeps
 * the same condition worded the same way everywhere, and leaves one file to touch for
 * localization or for a rewording.
 *
 * <p>Plain Java: cistern-core takes no Spring dependency.
 *
 * <p>Templates are {@link String#format} patterns. A literal percent sign must be doubled
 * ({@code %%}) — several of these messages quote percent-encoded URI text.
 */
public enum CoreMessage {

    /** Read of a resource that does not exist (→ 404 via the global error mapper). */
    RESOURCE_NOT_FOUND("No such resource: <%s>"),

    /** {@code getContainer} called with a document identifier — a caller programming error. */
    NOT_A_CONTAINER_IDENTIFIER(
            "getContainer() requires a container identifier (trailing slash): <%s>"),

    /** Solid Protocol §5.3: containment triples are server-managed. */
    CONTAINMENT_SERVER_MANAGED(
            "Containment triples are server-managed (Solid Protocol §5.3): the request body"
                    + " must not assert ldp:contains for <%s>"),

    /** Solid Protocol §5.4: DELETE targeting the storage's root container is refused (405). */
    STORAGE_ROOT_NOT_DELETABLE(
            "The storage's root container cannot be deleted (Solid Protocol §5.4): <%s>"),

    /**
     * A container's representation is an RDF source (Solid Protocol §4.2), so a write that
     * offers it any other media type conflicts with what the target resource is. RFC 9110
     * §9.3.4 names the two acceptable refusals for a representation "inconsistent with the
     * target resource" — 409 or 415 — and Solid's slash semantics make this the same
     * container/document conflict as a kind flip, so it is a Conflict.
     */
    CONTAINER_REQUIRES_RDF_BODY(
            "<%s> is a container and its representation is an RDF source (Solid Protocol §4.2):"
                    + " it cannot be written as \"%s\"; use %s or %s"),

    /** Stored bytes that will not parse: server-side corruption, never a client fault. */
    STORED_REPRESENTATION_CORRUPT("Stored representation for <%s> is corrupt: %s"),

    // ---------------------------------------------------------------- POST (T2.3)

    /** Solid Protocol §5.3: a POST to a URI with no representation is a 404, not a create. */
    POST_TARGET_NOT_FOUND(
            "POST targets a resource without an existing representation (Solid Protocol §5.3):"
                    + " <%s>"),

    /**
     * Solid Protocol §5.3 requires creation by POST only "to a URI path ending with /". A
     * document is not a container and mints no children, which §5.2's {@code Allow} already
     * advertises — so the refusal is RFC 9110 §15.5.6's 405.
     */
    POST_TARGET_NOT_A_CONTAINER(
            "POST creates resources in containers, and <%s> is not one (Solid Protocol §5.3:"
                    + " creation by POST is to a URI path ending with \"/\")"),

    /**
     * Every candidate name drawn for a new child was already taken — see LdpService.
     *
     * <p>Every placeholder in this catalogue is {@code %s}, including the ones that carry a
     * number: {@code CoreMessageTest} formats each template with string arguments to prove that
     * building a 4xx message can never throw and turn a client error into a 500, and a
     * {@code %d} would fail that guard.
     */
    CHILD_NAME_UNAVAILABLE(
            "Could not mint an unused name for a new child of <%s> after %s attempts"),

    /** RFC 5023 §9.7.1 admits printable ASCII and tab; a control character is a malformed header. */
    SLUG_MALFORMED(
            "Slug header contains a control character (0x%s); RFC 5023 §9.7.1 admits only"
                    + " printable characters and horizontal tab"),

    /** RFC 5023 §9.7.1: the field value is percent-encoded, so a broken escape is malformed. */
    SLUG_ESCAPE_MALFORMED("Malformed percent-escape in the Slug header: \"%s\""),

    /** A caller programming error: a Slug instance may only ever hold a sanitized name. */
    SLUG_NOT_A_NAME(
            "A Slug value must be a single non-empty path segment of unreserved characters"
                    + " (RFC 3986 §2.3) that neither starts nor ends with \".\" or \"-\": \"%s\"");

    private final String template;

    CoreMessage(String template) {
        this.template = template;
    }

    /** The message text with {@code args} substituted into this entry's template. */
    public String format(Object... args) {
        return String.format(template, args);
    }
}
