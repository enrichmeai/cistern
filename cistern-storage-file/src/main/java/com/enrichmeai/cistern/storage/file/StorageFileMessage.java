package com.enrichmeai.cistern.storage.file;

/**
 * cistern-storage-file's message catalogue (ground rule 7): every exception message this
 * module produces is a constant here, never text inlined at a throw site.
 *
 * <p>The counterpart of {@code CoreMessage} for the file backend, and plain Java for the
 * same reason — this module has no Spring dependency either. Two kinds of message live
 * here and the distinction matters when reading them:
 *
 * <ul>
 *   <li><b>Domain refusals</b> ({@code CisternException} subtypes) — a client fault the
 *       error mapper turns into a 4xx, so the wording reaches the client in a problem
 *       document. These quote the Solid Protocol section they enforce.</li>
 *   <li><b>Programming errors and corruption</b> ({@code IllegalArgumentException},
 *       {@code IllegalStateException}) — never a client fault. The mapper replaces the
 *       detail of any 5xx before responding, so these words go to the log, not the wire;
 *       they exist to make an operator's diagnosis fast.</li>
 * </ul>
 *
 * <p>Templates are {@link String#format} patterns, so a literal percent sign must be
 * doubled ({@code %%}) — disk names are percent-escaped, and several of these messages
 * quote one.
 */
enum StorageFileMessage {

    // ---------------------------------------------------------------- write conflicts

    /**
     * Solid Protocol §3.1 makes the trailing slash the container/document distinction, so a
     * committed document on the path to a new resource cannot also be the container that
     * would hold it.
     */
    INTERMEDIATE_CONTAINER_BLOCKED(
            "Cannot create intermediate container: a document already occupies %s"
                    + " (Solid Protocol §3.1)"),

    /** A container write where a document of the same name is already committed. */
    CONTAINER_NAME_TAKEN_BY_DOCUMENT(
            "%s conflicts with the same-name document: one name cannot be both"
                    + " a container and a document (Solid Protocol §3.1)"),

    /** A document write where a container of the same name is already committed. */
    DOCUMENT_NAME_TAKEN_BY_CONTAINER(
            "%s conflicts with the same-name container: one name cannot be both"
                    + " a container and a document (Solid Protocol §3.1)"),

    // ---------------------------------------------------------------- delete

    /** Delete of a container with no committed self record. */
    CONTAINER_NOT_FOUND("No such container: %s"),

    /** Solid Protocol §5.4: a container with committed children cannot be deleted. */
    CONTAINER_NOT_EMPTY("Container is not empty: %s (Solid Protocol §5.4)"),

    /** Delete of a document with no committed sidecar. */
    RESOURCE_NOT_FOUND("No such resource: %s"),

    // ---------------------------------------------------------------- caller errors

    /** {@code children()} is meaningful only for a container identifier. */
    CHILDREN_REQUIRES_CONTAINER(
            "children() requires a container identifier (trailing slash): %s"),

    /** A resource URI whose raw path is absent or relative — never a valid pod identifier. */
    PATH_NOT_ABSOLUTE("Resource URI must have an absolute path: %s"),

    /** A container identifier whose raw path does not actually end in a slash. */
    CONTAINER_PATH_UNTERMINATED(
            "Container raw path must end with '/' (encoded slashes are unsupported): %s"),

    /** An empty segment ({@code //}) names nothing and has no disk name. */
    PATH_SEGMENT_EMPTY("Empty path segment: %s"),

    /**
     * A {@code %%2F} inside a segment would make the URI-to-path mapping ambiguous, so it is
     * refused rather than guessed at.
     */
    PATH_SEGMENT_ENCODED_SLASH("Encoded slash in path segment is unsupported: %s"),

    // ---------------------------------------------------------------- invariants

    /**
     * Structurally impossible given {@code DiskNames.encode} (which escapes {@code .} in
     * leading position and cannot emit {@code /}); kept as a belt-and-braces assertion
     * because the failure it guards against is a path traversal.
     */
    PATH_ESCAPED_ROOT("Path escaped the storage root: %s"),

    /** SHA-256 is mandatory in every conformant JVM; its absence is not a recoverable state. */
    DIGEST_ALGORITHM_MISSING("JVM without SHA-256"),

    // ---------------------------------------------------------------- disk names

    /** A {@code %%XX} escape cut short — the name on disk is corrupt. */
    DISK_NAME_ESCAPE_TRUNCATED("Truncated escape in disk name: %s"),

    /** A {@code %%XX} escape with a non-hex digit — the name on disk is corrupt. */
    DISK_NAME_HEX_DIGIT_BAD("Bad hex digit in disk name escape: %s"),

    // ---------------------------------------------------------------- sidecar JSON

    /** A sidecar object missing one of the three fields {@link Sidecar} requires. */
    SIDECAR_FIELD_MISSING("Sidecar missing '%s': %s"),

    /** A sidecar object whose members are not comma-separated. */
    SIDECAR_SEPARATOR_EXPECTED("Expected ',' or '}' in sidecar JSON: %s"),

    /** A sidecar file that ends mid-token. */
    SIDECAR_TRUNCATED("Truncated sidecar JSON: %s"),

    /** A structural character the sidecar grammar required, and what was found instead. */
    SIDECAR_CHARACTER_EXPECTED("Expected '%s' but found '%s' in sidecar JSON: %s"),

    /** A backslash escape outside the JSON string-escape set. */
    SIDECAR_ESCAPE_BAD("Bad escape '\\%s' in sidecar JSON: %s"),

    /** A {@code \\u} escape with a non-hex digit. */
    SIDECAR_UNICODE_ESCAPE_BAD("Bad \\u escape in sidecar JSON: %s");

    private final String template;

    StorageFileMessage(String template) {
        this.template = template;
    }

    /** The message text with {@code args} substituted into this entry's template. */
    String format(Object... args) {
        return String.format(template, args);
    }
}
