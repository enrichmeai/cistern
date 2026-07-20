package com.enrichmeai.cistern.webflux.error;

import java.net.URI;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;

/**
 * An RFC 9457 problem details object: the five members of §3.1, typed, in the order the RFC
 * presents them. Serialises to {@code application/problem+json} by component name — the
 * record <em>is</em> the wire format, so there is no map-building or string-concatenation
 * step that could drift from the spec.
 *
 * <p>Every member is mandatory here even though the RFC makes most optional: a missing member
 * is indistinguishable from a bug, and the two factories below can always supply all five.
 *
 * @param type     §3.1.1 — URI identifying the problem type; {@link #BLANK_TYPE} when the
 *                 status code carries the whole meaning
 * @param title    §3.1.3 — short summary of the <em>type</em>, stable across occurrences
 * @param status   §3.1.2 — advisory copy of the HTTP status code, which the response must match
 * @param detail   §3.1.4 — explanation of <em>this</em> occurrence
 * @param instance §3.1.5 — URI reference identifying this occurrence; Cistern uses the
 *                 request path, an absolute-path reference as §3.1.5 recommends
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9457">RFC 9457</a>
 */
public record ProblemDocument(URI type, String title, int status, String detail, URI instance) {

    /** The media type every problem response is served as (RFC 9457 §3). */
    public static final MediaType MEDIA_TYPE = MediaType.APPLICATION_PROBLEM_JSON;

    /** RFC 9457 §4.2.1 — "the problem has no additional semantics beyond the status code". */
    public static final URI BLANK_TYPE = URI.create("about:blank");

    /** Title used when a status code has no reason phrase Spring can resolve. */
    private static final String UNKNOWN_STATUS_TITLE = "Error";

    public ProblemDocument {
        // Correct by construction: no 5xx can carry a caller-supplied detail, whatever the
        // call site passes. Scrubbing at each call site would work until someone adds a new
        // 5xx path and forgets — this cannot be forgotten (RFC 9457 §5). Done before the
        // null checks so a 500 is never itself derailed by a missing detail.
        if (HttpStatusCode.valueOf(status).is5xxServerError()) {
            detail = WebfluxMessage.DETAIL_INTERNAL_ERROR.format();
        }
        Objects.requireNonNull(type, () -> WebfluxMessage.PROBLEM_MEMBER_REQUIRED.format("type"));
        Objects.requireNonNull(title, () -> WebfluxMessage.PROBLEM_MEMBER_REQUIRED.format("title"));
        Objects.requireNonNull(detail, () -> WebfluxMessage.PROBLEM_MEMBER_REQUIRED.format("detail"));
        Objects.requireNonNull(instance, () -> WebfluxMessage.PROBLEM_MEMBER_REQUIRED.format("instance"));
    }

    /** A problem of one of Cistern's own {@linkplain ProblemType types}. */
    public static ProblemDocument of(ProblemType type, String detail, URI instance) {
        return new ProblemDocument(type.uri(), type.title(), type.status().value(), detail, instance);
    }

    /**
     * An {@code about:blank} problem: used where the status code alone says everything —
     * Spring's own exceptions, whose status is whatever the framework signalled rather than a
     * value from a closed set, and 500s. RFC 9457 §4.2.1 requires the title to be the status
     * code's reason phrase in this case.
     */
    public static ProblemDocument blank(HttpStatusCode status, String detail, URI instance) {
        HttpStatus resolved = HttpStatus.resolve(status.value());
        String title = resolved != null ? resolved.getReasonPhrase() : UNKNOWN_STATUS_TITLE;
        return new ProblemDocument(BLANK_TYPE, title, status.value(), detail != null ? detail : title, instance);
    }

    /** The status to send, which §3.1.2 requires to equal the {@code status} member. */
    public HttpStatusCode statusCode() {
        return HttpStatusCode.valueOf(status);
    }
}
