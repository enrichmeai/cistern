package com.enrichmeai.cistern.mcp;

/**
 * Every argument any tool takes — a closed set of names, so an enum (ground rule 7): the
 * schema builder, the argument parser and the documentation all draw from these constants,
 * and a tool cannot spell an argument two ways.
 */
enum ToolArgument {

    URL("url",
            "The resource: a path within the pod (e.g. /notes/week, containers end with '/')"
                    + " or an absolute URL under the pod's base URL."),

    CONTENT("content",
            "The document body to write, as text (e.g. Turtle, JSON-LD, plain text)."),

    CONTENT_TYPE("content-type",
            "The media type of the content (e.g. text/turtle, application/ld+json, text/plain)."),

    IF_MATCH("if-match",
            "Replace only if the resource's current ETag matches this value, exactly as read"
                    + " (include the quotes). Omitted: the write is unconditional."),

    CREATE_ONLY("create-only",
            "Create only: fail with a precondition error if the resource already exists"
                    + " (If-None-Match: *)."),

    AGENT("agent",
            "Who: a WebID (an absolute URL identifying an agent), or the word 'public' for"
                    + " anyone."),

    MODES("modes",
            "The access modes to grant: read, write, append and/or control. Write implies"
                    + " append; control governs the ACL itself and implies nothing else."),

    FROM("from",
            "Start of the interval (inclusive), an ISO 8601 instant like"
                    + " 2026-08-20T00:00:00Z. Omitted: from the beginning of the log."),

    TO("to",
            "End of the interval (exclusive), an ISO 8601 instant. Omitted: up to now.");

    private final String jsonName;
    private final String description;

    ToolArgument(String jsonName, String description) {
        this.jsonName = jsonName;
        this.description = description;
    }

    /** The property name as it appears in the tool's input schema and arguments. */
    String jsonName() {
        return jsonName;
    }

    String description() {
        return description;
    }
}
