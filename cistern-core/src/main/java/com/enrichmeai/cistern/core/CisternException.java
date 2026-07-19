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

    /** Precondition (If-Match / If-None-Match) failed → 412. */
    public static final class PreconditionFailed extends CisternException {
        public PreconditionFailed(String message) { super(message); }
    }

    /** Authenticated agent lacks the required access mode → 403 (or 401 if unauthenticated). */
    public static final class AccessDenied extends CisternException {
        public AccessDenied(String message) { super(message); }
    }
}
