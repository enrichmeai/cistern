package com.enrichmeai.cistern.webflux;

/**
 * cistern-webflux's message catalogue (ground rule 7): every piece of human-readable text this
 * module produces — exception messages, RFC 9457 problem titles and details, log lines — is a
 * constant here, never text inlined at a throw or log site. One catalogue per module, so the
 * {@code error} subpackage draws from this enum rather than keeping a second one.
 *
 * <p>Templates are {@link String#format} patterns. A literal percent sign must be doubled
 * ({@code %%}) — the request-target messages quote percent-encoded text, so this matters
 * here more than most places.
 */
public enum WebfluxMessage {

    // ---------------------------------------------------------------- request targets

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

    // ---------------------------------------------------------------- negotiation

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

    // ---------------------------------------------------------------- write requests

    /** Solid Protocol §2.1 mandates 400 for a content-bearing write with no Content-Type. */
    CONTENT_TYPE_REQUIRED(
            "A write request must declare the media type of its body in the Content-Type"
                    + " header field (Solid Protocol §2.1)"),

    /** {@code Content-Type} that will not parse — the client's error, not the server's. */
    CONTENT_TYPE_MALFORMED("Malformed Content-Type header: %s"),

    /** RFC 9110 §8.3: Content-Type names what the body IS; a range names what is acceptable. */
    CONTENT_TYPE_NOT_CONCRETE(
            "Content-Type must name a concrete media type, not a range: %s"),

    /**
     * Solid Protocol §5.3.1 identifies an N3 Patch by {@code text/n3} and by nothing else, so a
     * {@code PATCH} body in any other media type is RFC 5789 §2.2's "unsupported patch document"
     * — a 415, whose {@code Accept-Patch} tells the client what to retry with.
     */
    PATCH_MEDIA_TYPE_UNSUPPORTED(
            "A PATCH body must be an N3 Patch document declared as %s (Solid Protocol §5.3.1);"
                    + " this request declared %s"),

    // ---------------------------------------------------------------- conditional requests

    /**
     * A failed {@code If-Match} (RFC 9110 §13.1.1). Names the field, so a client that sent
     * several conditionals knows which one to fix, and quotes the tags back so it can see the
     * server read them as it meant them.
     */
    PRECONDITION_IF_MATCH_FAILED(
            "The %s precondition failed for <%s>: no current representation of the resource"
                    + " matches %s (RFC 9110 §13.1.1). Re-read the resource to obtain its"
                    + " current ETag before retrying."),

    /**
     * A failed {@code If-None-Match} (RFC 9110 §13.1.2) — the opposite sense to
     * {@link #PRECONDITION_IF_MATCH_FAILED}, and a separate entry precisely so it cannot be
     * described with the wrong one: this failure means the resource <em>does</em> match, which
     * for {@code *} means it already exists and the write would not have been a create.
     */
    PRECONDITION_IF_NONE_MATCH_FAILED(
            "The %s precondition failed for <%s>: the resource has a current representation"
                    + " matching %s (RFC 9110 §13.1.2), so this request would not have been"
                    + " applied to the state the client assumed."),

    /** A failed {@code If-Unmodified-Since} (RFC 9110 §13.1.4). */
    PRECONDITION_MODIFICATION_DATE_FAILED(
            "The %s precondition failed for <%s>: the resource was modified after the date"
                    + " given (RFC 9110 §13.1.4). Re-read the resource before retrying."),

    /**
     * Fires only if the evaluator and the write path disagree about which methods are reads:
     * RFC 9110 §13.2.2 step 3 reserves 304 for {@code GET} and {@code HEAD}.
     */
    NOT_MODIFIED_ON_UNSAFE_METHOD(
            "Precondition evaluation yielded 304 for %s <%s>, but RFC 9110 §13.2.2 step 3"
                    + " allows that outcome only for GET and HEAD"),

    // ---------------------------------------------------------------- configuration

    /** {@code cistern.base-url} must be usable as the base of every resource identifier. */
    BASE_URL_INVALID("cistern.base-url must be an absolute URI without a fragment: %s"),

    // ---------------------------------------- RFC 9457 titles, one per problem type

    // Titles are the RFC 9457 title member and so must not vary between occurrences of the
    // same problem type (§3.1.3): they carry no format arguments. Occurrence-specific text
    // belongs in detail, which comes from the domain exception. See ProblemType.

    TITLE_BAD_INPUT("Malformed request entity"),
    TITLE_UNPROCESSABLE_ENTITY("Request entity violates a protocol constraint"),
    TITLE_NOT_FOUND("Resource not found"),

    /** RFC 9110 §15.5.7 — nothing the server can produce matches the request's {@code Accept}. */
    TITLE_NOT_ACCEPTABLE("No acceptable representation"),

    /** RFC 9110 §15.5.6 — the method is not supported on this resource; see {@code Allow}. */
    TITLE_METHOD_NOT_ALLOWED("Method not allowed on this resource"),

    /** RFC 9110 §15.5.16 — the body's media type is not one this method takes for this resource. */
    TITLE_UNSUPPORTED_MEDIA_TYPE("Unsupported media type for this method"),

    TITLE_CONFLICT("Request conflicts with the state of the resource"),
    TITLE_PRECONDITION_FAILED("Precondition failed"),
    TITLE_AUTHENTICATION_REQUIRED("Authentication required"),
    TITLE_ACCESS_DENIED("Access denied"),

    // ---------------------------------------------------------------- problem details

    /**
     * The only detail any 5xx response ever carries. RFC 9457 §5 warns that problem details
     * must not leak information about the system; the real cause goes to the log instead.
     * Wording follows RFC 9110 §15.6.1's definition of 500 rather than describing the fault.
     */
    DETAIL_INTERNAL_ERROR(
            "The server encountered an unexpected condition that prevented it from fulfilling the request."),

    // ---------------------------------------------------------------- log lines

    LOG_SERVER_ERROR("%s %s failed with %s"),
    LOG_CLIENT_ERROR("%s %s rejected with %s: %s"),
    // LOG_UNMAPPED_DOMAIN_ERROR was here (#60). It announced a CisternException subtype the
    // mapper had no row for — a condition sealing made unreachable: the switch in ProblemMapper
    // is exhaustive over the permits list, so an unmapped subtype cannot compile, let alone log.
    // Removed rather than kept: a catalogue entry for a message that can never be emitted is
    // exactly the drift this catalogue exists to prevent.

    // ---------------------------------------------------------------- programming errors

    PROBLEM_MEMBER_REQUIRED("RFC 9457 member '%s' must not be null"),

    /** Startup: no owner configured, so nothing can authenticate. Logged, never thrown. */
    NO_OWNER_CONFIGURED(
            "cistern.owner.web-id / cistern.owner.token are not set: no credential"
                    + " authenticates anyone, so only what the ACLs grant the public is"
                    + " reachable. Set both to use this pod as its owner."),

    /** Startup: a fresh pod was given a root ACL granting its owner full access. */
    SEEDED_ROOT_ACL("Seeded root ACL <%s> granting full access to owner <%s>");

    private final String template;

    WebfluxMessage(String template) {
        this.template = template;
    }

    /**
     * The message text with {@code args} substituted into this entry's template. Always goes
     * through {@link String#format}, including when called with no arguments, so a doubled
     * {@code %%} resolves to one percent sign however the entry is used.
     */
    public String format(Object... args) {
        return String.format(template, args);
    }
}
