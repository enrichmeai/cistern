package com.enrichmeai.cistern.webflux;

/**
 * How an OAuth 2.0 bearer token may be sent to this resource — RFC 9728 §2's
 * {@code bearer_methods_supported}, whose "defined values are {@code header}, {@code body},
 * and {@code query}, corresponding to Sections 2.1, 2.2, and 2.3 of [RFC6750]".
 *
 * <p>The header only. {@code BearerToken.from} reads {@code Authorization} and nothing else,
 * the MCP authorization specification forbids the query string ("Access tokens MUST NOT be
 * included in the URI query string"), and RFC 6750 §2.2's form-encoded body has no place on a
 * server whose request bodies are resource representations.
 */
enum BearerMethod {

    /** RFC 6750 §2.1: {@code Authorization: Bearer <token>}. */
    HEADER("header");

    private final String token;

    BearerMethod(String token) {
        this.token = token;
    }

    /** The value as it appears in the document. */
    String token() {
        return token;
    }
}
