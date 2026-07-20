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
    STORED_REPRESENTATION_CORRUPT("Stored representation for <%s> is corrupt: %s");

    private final String template;

    CoreMessage(String template) {
        this.template = template;
    }

    /** The message text with {@code args} substituted into this entry's template. */
    public String format(Object... args) {
        return String.format(template, args);
    }
}
