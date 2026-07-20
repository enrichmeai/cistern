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

    /**
     * LDP §5.2.3.4: "If any requested interaction model cannot be honored, the server MUST fail
     * the request." Raised for the LDP container types Cistern does not implement.
     */
    INTERACTION_MODEL_UNHONOURABLE(
            "Cistern cannot honour the requested interaction model <%s> (LDP 1.0 §5.2.3.4 requires"
                    + " the request to fail rather than be downgraded): Solid containers are LDP"
                    + " Basic Containers (Solid Protocol §4.2), and membership-based containers are"
                    + " not implemented"),

    // ---------------------------------------------------------------- PATCH (T2.7)

    /**
     * Solid Protocol §5.3.1 scopes the {@code PATCH} requirement to RDF documents ("Servers
     * MUST accept a {@code PATCH} request with an N3 Patch body when the target of the request
     * is an <em>RDF document</em>"), and there is no graph in a byte stream to apply a patch to.
     * The refusal is RFC 9110 §15.5.6's 405 — the method is known but not supported by this
     * target — which is also what §5.2's {@code Allow} for a binary resource already advertises.
     */
    PATCH_TARGET_NOT_AN_RDF_SOURCE(
            "<%s> is a non-RDF source stored as \"%s\": an N3 Patch applies to the graph of an RDF"
                    + " document (Solid Protocol §5.3.1), and this resource has none"),

    /**
     * A caller programming error: {@link com.enrichmeai.cistern.core.ldp.LdpKind#ofContainer}
     * needs a container identifier.
     */
    KIND_REQUIRES_CONTAINER_IDENTIFIER(
            "The kind of a document cannot be known from its URI alone; ofContainer() requires a"
                    + " container identifier (trailing slash): <%s>"),

    // ---------------------------------------------------------------- RDF io

    /** {@code RdfIo.parse} called with no representation — a caller programming error. */
    RDF_NO_REPRESENTATION("Cannot parse RDF: no representation given"),

    /** A representation carrying a null byte array. */
    RDF_NO_DATA("Cannot parse RDF: representation has no data"),

    /** Relative-reference resolution needs a base (Solid Protocol §1.4.1, RFC 3986 §5.1.3). */
    RDF_NO_BASE("Cannot parse RDF: no resource identifier given as base"),

    /** Syntax Jena rejected. The trailing {@code %s} is the parser's own complaint. */
    RDF_DOCUMENT_MALFORMED("Malformed %s document for <%s>: %s"),

    /** {@code RdfIo.serialize} called with no model — a caller programming error. */
    RDF_NO_MODEL("Cannot serialize RDF: no model given"),

    /** A write that Jena failed, which is a server fault rather than a syntax one. */
    RDF_SERIALIZATION_FAILED("Cannot serialize RDF as %s: %s"),

    /**
     * No media type offered where one is required. The supported set is rendered by
     * {@code RdfMediaType.contentTypeList()}, so the message cannot list types the RDF layer
     * does not actually accept.
     */
    RDF_CONTENT_TYPE_MISSING("No content type given; supported RDF content types: %s"),

    /** Solid Protocol §5.5 fixes the pair of RDF serializations; anything else is a client fault. */
    RDF_CONTENT_TYPE_UNSUPPORTED("Unsupported RDF content type \"%s\"; supported: %s"),

    // ---------------------------------------------------------------- N3 Patch (T1.5)

    // Parse and constraint failures from N3Patch/N3PatchParser. The three wrapper templates
    // below (N3_PARSE_ERROR, N3_CONSTRAINT_VIOLATION, N3_FORMULA_OUT_OF_SUBSET) each take a
    // nested message from one of the entries that follow, so the position prefix and the
    // spec citation are written once rather than at every one of the parser's throw sites.

    N3_DIRECTIVE_OUT_OF_SUBSET("unsupported N3 construct: @%s is outside the N3 Patch subset"),
    N3_DIRECTIVE_UNKNOWN("unknown directive @%s"),
    N3_PREFIX_IRI_EXPECTED("expected an IRI after the prefix label in a prefix declaration"),
    N3_BASE_IRI_EXPECTED("expected an IRI in a base declaration"),
    N3_VARIABLE_OUTSIDE_FORMULA("variables are only allowed inside a formula"),
    N3_FORMULA_MISPLACED(
            "a formula may only appear as the object of solid:deletes, solid:inserts "
                    + "or solid:where"),
    N3_COLLECTION_OUT_OF_SUBSET(
            "unsupported N3 construct: collections are outside the N3 Patch subset"),
    N3_LITERAL_SUBJECT("a literal cannot be the subject of a statement"),
    N3_STATEMENT_START_UNEXPECTED("unexpected character at the start of a statement"),
    N3_FORMULA_NESTED("nested formulae are not allowed in an N3 Patch"),
    N3_FORMULA_PREDICATE("a formula cannot be used as a predicate"),
    N3_BLANK_NODE_PREDICATE("a blank node cannot be used as a predicate"),
    N3_LITERAL_PREDICATE("a literal cannot be used as a predicate"),
    N3_PREDICATE_EXPECTED("expected a predicate (IRI, prefixed name or 'a')"),
    N3_FORMULA_UNTERMINATED("unterminated formula: missing '}'"),
    N3_DIRECTIVE_IN_FORMULA("the N3 directive @%s is not allowed inside a formula"),
    N3_DIRECTIVES_IN_FORMULA("directives are not allowed inside a formula"),
    N3_FORMULA_STATEMENT_END_EXPECTED("expected '.' or '}' after a statement in a formula"),
    N3_LITERAL_SUBJECT_IN_PATTERN("a literal cannot be the subject of a triple pattern"),
    N3_TERM_EXPECTED("expected an IRI, prefixed name or blank node %s"),
    N3_IRI_UNTERMINATED("unterminated IRI: missing '>'"),
    N3_IRI_ESCAPE_ILLEGAL(
            "illegal escape sequence in IRI (only \\uXXXX and \\UXXXXXXXX are "
                    + "allowed)"),
    N3_IRI_CHARACTER_ILLEGAL("illegal character in IRI: '%s'"),
    N3_IRI_UNRESOLVABLE("cannot resolve relative IRI <%s> against base <%s>"),
    N3_IRI_INVALID("invalid IRI <%s>"),
    N3_PREFIX_UNDECLARED("undeclared prefix \"%s:\""),
    N3_LOCAL_NAME_PERCENT("'%%' in a local name must start a %%XX percent-encoded triplet"),
    N3_LOCAL_NAME_ESCAPE_ILLEGAL("illegal escape sequence in local name"),
    N3_BLANK_NODE_LABEL_EXPECTED("expected a blank node label after '_:'"),
    N3_VARIABLE_NAME_EXPECTED("expected a variable name after '?'"),
    N3_VARIABLE_NAME_LEADING_DIGIT("a variable name must not start with a digit: ?%s"),
    N3_STRING_UNTERMINATED("unterminated string literal"),
    N3_STRING_LINE_BREAK("unescaped line break in string literal"),
    N3_LONG_STRING_UNTERMINATED("unterminated long string literal"),
    N3_STRING_ESCAPE_ILLEGAL("illegal escape sequence in string literal"),
    N3_LANGUAGE_TAG_EXPECTED("expected a language tag after '@'"),
    N3_LANGUAGE_TAG_MALFORMED("malformed language tag"),
    N3_NUMERIC_MALFORMED("malformed numeric literal"),
    N3_NUMERIC_EXPONENT_MALFORMED("malformed exponent in numeric literal"),
    N3_UNICODE_ESCAPE_TRUNCATED("truncated \\u escape sequence"),
    N3_UNICODE_ESCAPE_MALFORMED("malformed \\u escape sequence"),
    N3_UNICODE_ESCAPE_INVALID_CODE_POINT("\\u escape sequence is not a valid code point"),
    N3_WORD_EXPECTED("expected a word"),
    N3_NO_PATCH_RESOURCE(
            "it contains no patch resource (a triple '?patch a "
                    + "solid:InsertDeletePatch' is required)"),
    N3_PATCH_TYPE_INVALID(
            "the type of a patch resource must be solid:Patch or "
                    + "solid:InsertDeletePatch"),
    N3_PREDICATE_UNEXPECTED(
            "unexpected predicate <%s>: a patch resource may only use rdf:type, "
                    + "solid:deletes, solid:inserts and solid:where"),
    N3_PATCH_RESOURCE_NOT_UNIQUE(
            "the patch document must contain exactly one patch resource, but %s "
                    + "subjects were found"),
    N3_PATCH_RESOURCE_TYPE_MISSING(
            "the patch resource must contain a triple '?patch a "
                    + "solid:InsertDeletePatch'"),
    N3_PREDICATE_REPEATED("a patch resource must contain at most one %s triple"),
    N3_PREDICATE_OBJECT_NOT_FORMULA("the object of %s must be a formula { ... }"),
    N3_WHERE_BLANK_NODE(
            "blank nodes in the solid:where formula are not supported: the "
                    + "specification defines the mapping algorithm over variables only and "
                    + "leaves blank-node matching undefined"),
    N3_FORMULA_BLANK_NODE("the %s formula must not contain blank nodes"),
    N3_VARIABLE_NOT_IN_WHERE(
            "variable ?%s in the %s formula does not occur in the solid:where formula"),
    N3_IMPLICATION_OUT_OF_SUBSET(
            "unsupported N3 construct: '=' / '=>' are outside the N3 Patch subset"),
    N3_REVERSE_IMPLICATION_OUT_OF_SUBSET(
            "unsupported N3 construct: '<=' is outside the N3 Patch subset"),
    N3_BLANK_NODE_PROPERTY_LIST(
            "blank node property lists are not supported in an N3 Patch document"),
    N3_OBJECT_EXPECTED_IN_FORMULA("expected an object (IRI, prefixed name, literal, variable)"),
    N3_OBJECT_EXPECTED("expected an object (IRI, prefixed name, literal, formula)"),
    N3_TOKEN_EXPECTED("expected '%s'"),
    N3_TOKEN_EXPECTED_AT_EOF("expected '%s' but reached the end of the document"),
    N3_CONSTRAINT_VIOLATION(
            "Invalid N3 Patch document: %s (Solid Protocol, Modifying Resources Using "
                    + "N3 Patches)"),
    N3_PARSE_ERROR("Invalid N3 Patch document %s: %s"),
    N3_FORMULA_OUT_OF_SUBSET(
            "Invalid N3 Patch document %s: %s — a formula must consist only of "
                    + "triples and/or triple patterns (Solid Protocol, Modifying Resources "
                    + "Using N3 Patches)"),
    N3_NO_REPRESENTATION("Cannot parse N3 Patch: no representation given"),
    N3_NO_DATA("Cannot parse N3 Patch: representation has no data"),
    N3_NO_BASE("Cannot parse N3 Patch: no resource identifier given as base"),
    N3_DOCUMENT_MALFORMED("Malformed %s patch document for <%s>: %s"),
    N3_APPLY_NO_MAPPING(
            "Cannot apply N3 Patch: no variable mapping exists for which all "
                    + "solid:where triples occur in the target graph"),
    N3_APPLY_AMBIGUOUS_MAPPING(
            "Cannot apply N3 Patch: multiple variable mappings satisfy the "
                    + "solid:where formula; the Solid Protocol requires exactly one"),
    N3_APPLY_DELETION_ABSENT(
            "Cannot apply N3 Patch: solid:deletes triple is not present in the target "
                    + "graph: %s"),
    N3_UNBOUND_VARIABLE("Unbound variable after mapping propagation: %s"),
    N3_CONTENT_TYPE_MISSING("No content type given; an N3 Patch document must be %s"),
    N3_CONTENT_TYPE_UNSUPPORTED(
            "Unsupported content type \"%s\" for an N3 Patch document; must be %s"),
    N3_NOT_UTF8("N3 Patch document is not valid UTF-8"),

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
