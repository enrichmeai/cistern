package com.enrichmeai.cistern.cli;

/**
 * An {@code ETag} field value exactly as the server sent it, to be echoed back verbatim in
 * {@code If-Match} (RFC 9110 §13.1.1 compares entity-tags, quotes and all).
 *
 * <p>Opaque on purpose: the CLI has no business parsing the validator, and a type — rather than
 * a {@code String} — keeps a header value from being confused with any other string on its way
 * from the {@code GET} to the {@code PUT}.
 *
 * @param value the field value, non-blank
 */
record EntityTagHeader(String value) {

    EntityTagHeader {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    CliMessage.BLANK_HEADER_VALUE.format(HttpHeaderName.ETAG.fieldName()));
        }
    }
}
