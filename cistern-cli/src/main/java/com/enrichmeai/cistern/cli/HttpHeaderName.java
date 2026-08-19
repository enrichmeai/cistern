package com.enrichmeai.cistern.cli;

/** The HTTP header fields the CLI sends or reads — a closed set, so an enum (ground rule 7). */
enum HttpHeaderName {

    AUTHORIZATION("Authorization"),
    ACCEPT("Accept"),
    CONTENT_TYPE("Content-Type"),
    IF_MATCH("If-Match"),
    IF_NONE_MATCH("If-None-Match"),
    ETAG("ETag");

    private final String fieldName;

    HttpHeaderName(String fieldName) {
        this.fieldName = fieldName;
    }

    /** The field name as it appears on the wire. */
    String fieldName() {
        return fieldName;
    }
}
