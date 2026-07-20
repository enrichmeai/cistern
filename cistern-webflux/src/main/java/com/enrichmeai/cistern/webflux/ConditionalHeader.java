package com.enrichmeai.cistern.webflux;

import org.springframework.http.HttpHeaders;

/**
 * The conditional request header fields Cistern evaluates — a closed set, so an enum
 * (ground rule 7), and the only place their field names are spelled.
 *
 * <p>Declaration order is RFC 9110 §13.2.2's evaluation order, which is not an accident of
 * listing: "lost update" preconditions have more strict requirements than cache validation,
 * and entity tags are presumed to be more accurate than date validators. Reading this enum
 * top to bottom is reading the precedence {@link PreconditionEvaluator} implements.
 *
 * <h2>What is deliberately absent</h2>
 * {@code If-Range} (§13.1.5) is step 5 of §13.2.2 and is <b>not</b> represented here, because
 * step 5 only applies "when the method is GET and both Range and If-Range are present" and
 * Cistern does not implement range requests at all — §14 makes them optional ("Range requests
 * are an OPTIONAL feature of HTTP, designed so that recipients not implementing this feature
 * ... can respond as if it is a normal GET request without impacting interoperability"). With
 * no {@code Range} support there is no partial response for {@code If-Range} to guard, and
 * §13.2.2 step 5's "otherwise, ignore the Range header field and respond 200 (OK)" is exactly
 * what Cistern already does.
 */
enum ConditionalHeader {

    /** RFC 9110 §13.1.1 — strong comparison; step 1 of the §13.2.2 order. */
    IF_MATCH(HttpHeaders.IF_MATCH),

    /** RFC 9110 §13.1.4 — the date-validator stand-in for {@code If-Match}; step 2. */
    IF_UNMODIFIED_SINCE(HttpHeaders.IF_UNMODIFIED_SINCE),

    /** RFC 9110 §13.1.2 — weak comparison; step 3, and the only step that can yield 304. */
    IF_NONE_MATCH(HttpHeaders.IF_NONE_MATCH),

    /** RFC 9110 §13.1.3 — the date-validator stand-in for {@code If-None-Match}; step 4. */
    IF_MODIFIED_SINCE(HttpHeaders.IF_MODIFIED_SINCE);

    private final String fieldName;

    ConditionalHeader(String fieldName) {
        this.fieldName = fieldName;
    }

    /** The header field name as it appears on the wire. */
    String fieldName() {
        return fieldName;
    }
}
