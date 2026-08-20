package com.enrichmeai.cistern.mcp;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * The {@code ?receipts} query string (T5.9's surface), spelled once. The parameter names
 * mirror cistern-webflux's {@code ReceiptsParameter} and cannot be imported from it: that
 * enum lives in a module the standalone bridge deliberately does not carry.
 */
final class ReceiptsQuery {

    /** The parameter that makes a GET a receipts query. */
    static final String RECEIPTS = "receipts";
    static final String FROM = "from";
    static final String TO = "to";

    private static final String QUERY_START = "?";
    private static final String PARAMETER_SEPARATOR = "&";
    private static final String KEY_VALUE_SEPARATOR = "=";

    private ReceiptsQuery() {
        // static assembly only
    }

    /** {@code requestUri} with {@code ?receipts} and the optional interval appended. */
    static URI appendTo(URI requestUri, Optional<String> from, Optional<String> to) {
        StringBuilder query = new StringBuilder(requestUri.toString())
                .append(QUERY_START).append(RECEIPTS);
        from.ifPresent(value -> append(query, FROM, value));
        to.ifPresent(value -> append(query, TO, value));
        return URI.create(query.toString());
    }

    private static void append(StringBuilder query, String name, String value) {
        query.append(PARAMETER_SEPARATOR).append(name).append(KEY_VALUE_SEPARATOR)
                .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
    }
}
