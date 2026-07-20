package com.enrichmeai.cistern.webflux;

/**
 * cistern-webflux's message catalogue (ground rule 7): every exception message this module
 * produces is a constant here, never text inlined at a throw site.
 *
 * <p>Templates are {@link String#format} patterns. A literal percent sign must be doubled
 * ({@code %%}) — the request-target messages quote percent-encoded text, so this matters
 * here more than most places.
 */
enum WebfluxMessage {

    /** Request-target that is not an absolute path — nothing to resolve against the base. */
    TARGET_NOT_ABSOLUTE("Request target must be an absolute path: %s"),

    /** {@code //} or {@code /a//b}: the storage backend cannot name an empty segment. */
    TARGET_EMPTY_SEGMENT("Empty path segment in request target: %s"),

    /** {@code .} or {@code ..}: identifiers must arrive normalized (RFC 3986 §5.2.4). */
    TARGET_DOT_SEGMENT("Dot segments are not addressable; send a normalized path: %s"),

    /** {@code %%2F} inside a segment — raw and decoded slash structure would disagree. */
    TARGET_ENCODED_SLASH("Encoded slash (%%2F) in a path segment is not addressable: %s"),

    /** Percent-escape or character that makes the request-target unparseable as a URI. */
    TARGET_MALFORMED("Malformed request target %s: %s"),

    /** {@code Accept} that will not parse — the client's error, not the server's. */
    ACCEPT_MALFORMED("Malformed Accept header: %s"),

    /** Solid Protocol §5.5 fixes what an RDF source can be serialized as. */
    RDF_SOURCE_NOT_ACCEPTABLE(
            "No acceptable representation: this resource is an RDF source and can only be served"
                    + " as %s or %s (Solid Protocol §5.5); Accept requested %s"),

    /** A non-RDF source has exactly one representation and is never transcoded. */
    NON_RDF_SOURCE_NOT_ACCEPTABLE(
            "No acceptable representation: this resource is a non-RDF source, served verbatim"
                    + " as %s; Accept requested %s"),

    /** Stored media type that will not parse: server-side corruption, never a client fault. */
    STORED_CONTENT_TYPE_INVALID(
            "Stored content type for <%s> is not a valid media type: %s"),

    /** {@code cistern.base-url} must be usable as the base of every resource identifier. */
    BASE_URL_INVALID("cistern.base-url must be an absolute URI without a fragment: %s");

    private final String template;

    WebfluxMessage(String template) {
        this.template = template;
    }

    /** The message text with {@code args} substituted into this entry's template. */
    String format(Object... args) {
        return String.format(template, args);
    }
}
