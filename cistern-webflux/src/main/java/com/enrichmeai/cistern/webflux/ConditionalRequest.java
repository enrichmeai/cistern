package com.enrichmeai.cistern.webflux;

import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.server.ServerRequest;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Every conditional request header field of RFC 9110 §13.1, parsed off one request — the typed
 * form of "what did this client make its request conditional on?", so that no handler ever
 * reads a conditional header name or compares an entity-tag string itself.
 *
 * <p>The four components are in §13.2.2's evaluation order, matching {@link ConditionalHeader};
 * {@link PreconditionEvaluator} walks them in that order and nothing else decides precedence.
 *
 * <h2>Date fields are ignored rather than rejected when unusable</h2>
 * Both date conditionals carry an explicit instruction for a value that will not serve, and it
 * is the same one in each case: "A recipient MUST ignore the If-Modified-Since header field if
 * the received field value is not a valid HTTP-date, the field value has more than one member,
 * or if the request method is neither GET nor HEAD" (§13.1.3), and "A recipient MUST ignore the
 * If-Unmodified-Since header field if the received field value is not a valid HTTP-date
 * (including when the field value appears to be a list of dates)" (§13.1.4). Both therefore
 * arrive here as an empty {@link Optional} and the step that would have used them is skipped,
 * exactly as if the client had not sent the field. The multi-member rule is applied to both,
 * since a list of dates cannot be a valid HTTP-date either way.
 *
 * <p>Parsing goes through Spring's {@code HttpHeaders} accessors, which accept all three
 * formats RFC 9110 §5.6.7 obliges a recipient to parse (IMF-fixdate, the obsolete RFC 850
 * form, and asctime) and answer {@code -1} for anything else — precisely the "ignore it"
 * semantics above.
 *
 * @param ifMatch           §13.1.1, evaluated first
 * @param ifUnmodifiedSince §13.1.4, evaluated only when {@code ifMatch} is absent
 * @param ifNoneMatch       §13.1.2, the only field that can produce a 304
 * @param ifModifiedSince   §13.1.3, evaluated only for GET/HEAD and only when
 *                          {@code ifNoneMatch} is absent
 */
record ConditionalRequest(EntityTagCondition ifMatch,
                          Optional<Instant> ifUnmodifiedSince,
                          EntityTagCondition ifNoneMatch,
                          Optional<Instant> ifModifiedSince) {

    /** Spring's answer for an absent or unparseable HTTP-date field. */
    private static final long NO_DATE = -1L;

    /** Parses the conditional fields of {@code request}; components are never null. */
    static ConditionalRequest of(ServerRequest request) {
        ServerRequest.Headers headers = request.headers();
        HttpHeaders httpHeaders = headers.asHttpHeaders();
        return new ConditionalRequest(
                EntityTagCondition.parse(headers.header(ConditionalHeader.IF_MATCH.fieldName())),
                dateOf(headers, ConditionalHeader.IF_UNMODIFIED_SINCE,
                        httpHeaders.getIfUnmodifiedSince()),
                EntityTagCondition.parse(headers.header(ConditionalHeader.IF_NONE_MATCH.fieldName())),
                dateOf(headers, ConditionalHeader.IF_MODIFIED_SINCE,
                        httpHeaders.getIfModifiedSince()));
    }

    /**
     * Whether the request carried any precondition at all. When it did not, no state lookup
     * happens and the request takes exactly the path it took before this ticket — an
     * unconditional {@code PUT} still costs one store round-trip, not two.
     */
    boolean isEmpty() {
        return !ifMatch.isPresent() && !ifNoneMatch.isPresent()
                && ifUnmodifiedSince.isEmpty() && ifModifiedSince.isEmpty();
    }

    /** The condition the given field carried, for the steps of §13.2.2 that name one. */
    EntityTagCondition conditionOf(ConditionalHeader header) {
        return switch (header) {
            case IF_MATCH -> ifMatch;
            case IF_NONE_MATCH -> ifNoneMatch;
            case IF_UNMODIFIED_SINCE, IF_MODIFIED_SINCE -> new EntityTagCondition.NotPresent();
        };
    }

    private static Optional<Instant> dateOf(ServerRequest.Headers headers,
                                            ConditionalHeader header, long parsedMillis) {
        if (headers.header(header.fieldName()).size() != 1 || parsedMillis == NO_DATE) {
            return Optional.empty();
        }
        return Optional.of(Instant.ofEpochMilli(parsedMillis));
    }
}
