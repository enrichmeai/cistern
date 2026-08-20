package com.enrichmeai.cistern.mcp;

/**
 * An {@code ETag} field value exactly as the server sent it, echoed back verbatim in
 * {@code If-Match} (RFC 9110 §13.1.1 compares entity-tags, quotes and all). Opaque on purpose,
 * as in cistern-cli: the front door has no business parsing a validator.
 *
 * @param value the field value, non-blank
 */
record EntityTagHeader(String value) {

    EntityTagHeader {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(McpMessage.ARGUMENT_INVALID.format(
                    HttpHeaderName.ETAG.fieldName(), value, HttpHeaderName.ETAG.fieldName()));
        }
    }
}
