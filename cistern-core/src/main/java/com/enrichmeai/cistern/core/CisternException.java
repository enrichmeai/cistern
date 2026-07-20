package com.enrichmeai.cistern.core;

/**
 * Root of Cistern's domain error hierarchy. The HTTP layer maps subtypes to status
 * codes in exactly one place (T2.6, GlobalErrorHandler) — services and stores signal
 * these through the reactive chain and never speak HTTP.
 */
public abstract class CisternException extends RuntimeException {

    protected CisternException(String message) {
        super(message);
    }

    /** Container/document kind mismatch, non-empty container delete, etc. → 409. */
    public static final class Conflict extends CisternException {
        public Conflict(String message) { super(message); }
    }

    /**
     * Operation targets a resource that does not exist where existence is required —
     * e.g. {@code ResourceStore.delete} of a missing resource → 404 (mapped in T2.6,
     * GlobalErrorHandler). Absence on read stays an empty Mono; this subtype exists
     * because {@code Mono<Void>} cannot distinguish absence from success by emptiness.
     */
    public static final class NotFound extends CisternException {
        public NotFound(String message) { super(message); }
    }

    /** Malformed RDF body, invalid slug, bad N3 Patch document → 400. */
    public static final class BadInput extends CisternException {
        public BadInput(String message) { super(message); }
    }

    /**
     * Well-formed request entity that violates a spec constraint → 422 (RFC 4918); e.g. an
     * N3 Patch document breaching the §n3-patch constraints. Distinct from {@link BadInput}
     * (400), which signals an entity the server could not parse at all: the split is
     * load-bearing because the HTTP layer cannot reconstruct it after the fact.
     */
    public static final class UnprocessableEntity extends CisternException {
        public UnprocessableEntity(String message) { super(message); }
    }

    /**
     * No representation of the target resource matches the request's {@code Accept} header
     * → 406 (RFC 9110 §15.5.7). Signalled by the read path when proactive negotiation
     * cannot satisfy the client: an RDF source can only ever be served as
     * {@code text/turtle} or {@code application/ld+json} (Solid Protocol §5.5), and a
     * non-RDF source only as its stored media type.
     *
     * <p>Lives here rather than in the HTTP layer for the same reason
     * {@link PreconditionFailed} does: handlers must not name status codes (ground rule 4),
     * so the condition has to reach the single error mapper as a domain signal.
     */
    public static final class NotAcceptable extends CisternException {
        public NotAcceptable(String message) { super(message); }
    }

    /** Precondition (If-Match / If-None-Match) failed → 412. */
    public static final class PreconditionFailed extends CisternException {
        public PreconditionFailed(String message) { super(message); }
    }

    /**
     * The target resource does not support the request method → 405 (RFC 9110 §15.5.6),
     * whose response MUST carry an {@code Allow} header listing the methods it does support.
     *
     * <p>Raised today by {@code LdpService.delete} for the storage root container: Solid
     * Protocol §5.4 — "When a {@code DELETE} request targets storage's root container or its
     * associated ACL resource, the server MUST respond with the {@code 405} status code."
     * The refusal is a domain rule about which resource is being addressed, not a routing
     * fact, so it has to reach the single error mapper as a domain signal (ground rule 4)
     * rather than being decided in a handler.
     */
    public static final class MethodNotAllowed extends CisternException {
        public MethodNotAllowed(String message) { super(message); }
    }

    /** Authenticated agent lacks the required access mode → 403 (or 401 if unauthenticated). */
    public static final class AccessDenied extends CisternException {
        public AccessDenied(String message) { super(message); }
    }
}
