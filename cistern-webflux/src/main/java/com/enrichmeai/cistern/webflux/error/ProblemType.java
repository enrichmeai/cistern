package com.enrichmeai.cistern.webflux.error;

import com.enrichmeai.cistern.webflux.WebfluxMessage;
import java.net.URI;
import org.springframework.http.HttpStatus;

/**
 * The closed set of problem types Cistern defines for its own domain errors, each row
 * carrying its RFC 9457 {@code type} URI, its {@code title}, and the HTTP status it maps to.
 * This enum <em>is</em> the mapping table: {@link ProblemMapper} only chooses a row.
 *
 * <p>RFC 9457 §3.1.1 encourages resolvable type URIs, so these are {@code https:} URLs under
 * the project's domain and are expected to resolve to documentation of each problem type.
 * Errors that need no semantics beyond their status code — Spring's own exceptions, 500s —
 * use {@code about:blank} (§4.2.1) instead and are built by
 * {@link ProblemDocument#blank(org.springframework.http.HttpStatusCode, String, URI)}; they
 * are absent here precisely because their status is not drawn from a closed set.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9457">RFC 9457</a>
 */
public enum ProblemType {

    /** Entity the server could not parse at all: bad Turtle, invalid slug, unparseable N3. */
    BAD_INPUT("bad-input", HttpStatus.BAD_REQUEST, WebfluxMessage.TITLE_BAD_INPUT),

    /**
     * Well-formed entity that breaches a protocol constraint — RFC 4918 §11.2. Distinct from
     * {@link #BAD_INPUT} because the HTTP layer cannot reconstruct the difference after the
     * fact; the N3 Patch engine relies on the split.
     */
    UNPROCESSABLE_ENTITY("unprocessable-entity", HttpStatus.UNPROCESSABLE_CONTENT,
            WebfluxMessage.TITLE_UNPROCESSABLE_ENTITY),

    NOT_FOUND("not-found", HttpStatus.NOT_FOUND, WebfluxMessage.TITLE_NOT_FOUND),

    /**
     * RFC 9110 §15.5.7 — the resource has no representation the request's {@code Accept} will
     * take. Raised by {@code ContentNegotiator}: an RDF source can only be Turtle or JSON-LD
     * (Solid Protocol §5.5), and a non-RDF source is served verbatim or not at all.
     */
    NOT_ACCEPTABLE("not-acceptable", HttpStatus.NOT_ACCEPTABLE, WebfluxMessage.TITLE_NOT_ACCEPTABLE),

    /**
     * RFC 9110 §15.5.6 — the method is not supported on the target resource. The response MUST
     * carry an {@code Allow} header, which {@link ProblemMapper} attaches; the body only
     * explains the refusal. Raised by {@code DELETE} on the storage root (Solid Protocol §5.4).
     */
    METHOD_NOT_ALLOWED("method-not-allowed", HttpStatus.METHOD_NOT_ALLOWED,
            WebfluxMessage.TITLE_METHOD_NOT_ALLOWED),

    /** Solid Protocol §5.3 (containment-triple writes) and §5.4 (non-empty container delete). */
    CONFLICT("conflict", HttpStatus.CONFLICT, WebfluxMessage.TITLE_CONFLICT),

    PRECONDITION_FAILED("precondition-failed", HttpStatus.PRECONDITION_FAILED,
            WebfluxMessage.TITLE_PRECONDITION_FAILED),

    /** Solid Protocol §2.1: the request carried no credentials the server accepted. */
    AUTHENTICATION_REQUIRED("authentication-required", HttpStatus.UNAUTHORIZED,
            WebfluxMessage.TITLE_AUTHENTICATION_REQUIRED),

    /** An authenticated agent lacks the mode; re-authenticating would not help. */
    ACCESS_DENIED("access-denied", HttpStatus.FORBIDDEN, WebfluxMessage.TITLE_ACCESS_DENIED);

    /** Base for every Cistern problem type URI. */
    private static final String TYPE_BASE = "https://enrichmeai.com/cistern/problems/";

    private final URI uri;
    private final HttpStatus status;
    private final WebfluxMessage title;

    ProblemType(String slug, HttpStatus status, WebfluxMessage title) {
        this.uri = URI.create(TYPE_BASE + slug);
        this.status = status;
        this.title = title;
    }

    /** The RFC 9457 {@code type} member. */
    public URI uri() {
        return uri;
    }

    /** The status this problem type always maps to. */
    public HttpStatus status() {
        return status;
    }

    /** The RFC 9457 {@code title} member — stable across occurrences (§3.1.3). */
    public String title() {
        return title.format();
    }
}
