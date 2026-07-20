package com.enrichmeai.cistern.webflux.error;

import com.enrichmeai.cistern.core.CisternException;
import java.util.Set;
import java.util.function.Function;
import org.springframework.http.HttpMethod;
import org.springframework.web.server.MethodNotAllowedException;

/**
 * The closed set of probe endpoints, one per row of {@link ProblemMapper}'s table: path,
 * the error it raises, and the detail that error carries. Routes and assertions are both
 * driven from here, so a test can never assert a detail the route did not actually throw.
 */
enum Probe {

    BAD_INPUT("bad-input", "not valid Turtle", CisternException.BadInput::new),
    UNPROCESSABLE_ENTITY("unprocessable-entity", "patch deletes a triple it does not bind",
            CisternException.UnprocessableEntity::new),
    NOT_FOUND("not-found", "no such resource", CisternException.NotFound::new),
    CONFLICT("conflict", "cannot write containment triples", CisternException.Conflict::new),
    PRECONDITION_FAILED("precondition-failed", "If-Match did not match the current ETag",
            CisternException.PreconditionFailed::new),
    ACCESS_DENIED("access-denied", "Write mode required", CisternException.AccessDenied::new),
    ILLEGAL_ARGUMENT("illegal-argument", "/doc is not a container", IllegalArgumentException::new),

    /**
     * Text that must never reach a client: it stands for the kind of internal detail a real
     * {@code IllegalStateException} would carry — paths, corrupt sidecar contents.
     */
    ILLEGAL_STATE("illegal-state", Text.SECRET, IllegalStateException::new),
    UNEXPECTED("boom", Text.SECRET, RuntimeException::new),

    /**
     * T2.4 will raise this for a DELETE of the storage root, so mapping it is not theoretical.
     * There is no {@code CisternException} for 405 — Spring's own exception carries the
     * {@code Allow} header RFC 9110 §15.5.6 mandates, so the pass-through branch handles it.
     */
    METHOD_NOT_ALLOWED("method-not-allowed", "",
            detail -> new MethodNotAllowedException(HttpMethod.DELETE, Set.of(HttpMethod.GET, HttpMethod.HEAD))),

    /** Reached by no route at all: exercises the framework's own unrouted-path error. */
    UNROUTED("no-such-route", "", detail -> new IllegalStateException(detail)),

    /** Decodes its body, so the codecs raise the genuine 415 / 400 rather than a hand-built one. */
    ECHO("echo", "", detail -> new IllegalStateException(detail));

    /**
     * Enum constants are initialised before the enum's own static fields, so text they
     * reference has to live in a separate class.
     */
    private static final class Text {
        /** Shared by the two 500 probes; asserted absent from every 500 response body. */
        static final String SECRET = "corrupt sidecar at /var/data/pod/secret-file.meta";
    }

    /** Internal text that must never appear in a response body. */
    static String secret() {
        return Text.SECRET;
    }

    private static final String PATH_PREFIX = "/probe/";

    private final String path;
    private final String detail;
    private final Function<String, Throwable> error;

    Probe(String slug, String detail, Function<String, Throwable> error) {
        this.path = PATH_PREFIX + slug;
        this.detail = detail;
        this.error = error;
    }

    String path() {
        return path;
    }

    /** The RFC 9457 {@code detail} the mapped response is expected to carry. */
    String detail() {
        return detail;
    }

    Throwable error() {
        return error.apply(detail);
    }
}
