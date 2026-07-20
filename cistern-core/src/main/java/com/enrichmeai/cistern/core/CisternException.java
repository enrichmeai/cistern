package com.enrichmeai.cistern.core;

import com.enrichmeai.cistern.core.ldp.LdpKind;

import java.util.Objects;

/**
 * Root of Cistern's domain error hierarchy. The HTTP layer maps subtypes to status
 * codes in exactly one place (T2.6, GlobalErrorHandler) — services and stores signal
 * these through the reactive chain and never speak HTTP.
 *
 * <h2>Why it is sealed (#60)</h2>
 * The hierarchy is a closed set: every member is declared here, and the single error mapper
 * must have an answer for each. Sealing states that, and turns "the mapper handles every
 * subtype" from something a test checked at runtime into something the compiler checks.
 *
 * <p>{@code ProblemMapper} switches exhaustively over this {@code permits} list, so it needs
 * no {@code default} branch and no fallback to 500 for an unrecognized subtype — that branch
 * is now unreachable by construction rather than by inspection. Adding a subtype below
 * without giving it a status stops the build in cistern-webflux, which is where the decision
 * has to be made anyway; previously it compiled cleanly and failed a reflection-based test,
 * and if that test had ever been skipped it would have reached production as a 500.
 *
 * <p>The list is explicit rather than relying on the implicit permission Java grants
 * same-file subclasses: it is the enumeration the mapper is checked against, so it is worth
 * being able to read it in one place.
 */
public abstract sealed class CisternException extends RuntimeException
        permits CisternException.AccessDenied,
                CisternException.BadInput,
                CisternException.Conflict,
                CisternException.MethodNotAllowed,
                CisternException.NotAcceptable,
                CisternException.NotFound,
                CisternException.PreconditionFailed,
                CisternException.UnprocessableEntity,
                CisternException.UnsupportedMediaType {

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
     * <p>Raised by {@code LdpService.delete} for the storage root container (Solid Protocol
     * §5.4 — "When a {@code DELETE} request targets storage's root container or its associated
     * ACL resource, the server MUST respond with the {@code 405} status code"), by
     * {@code LdpService.createIn} for a {@code POST} to a document (§5.3 confines creation by
     * {@code POST} to paths ending in {@code /}), and by {@code LdpService.patch} for a
     * {@code PATCH} of a non-RDF source (§5.3.1 scopes N3 Patch to RDF documents). Each is a
     * domain rule about which resource is being addressed, not a routing fact, so it reaches
     * the single error mapper as a domain signal (ground rule 4) rather than being decided in
     * a handler.
     *
     * <h2>Why it carries the kind (T2.7)</h2>
     * RFC 9110 §15.5.6 makes {@code Allow} mandatory on a 405, and the value must be true of
     * the resource that refused. Until {@code PATCH} existed the HTTP layer could infer that
     * from the request path, because every distinction it needed was the container/document
     * split Solid Protocol §3.1 puts in the trailing slash. It no longer can: an RDF document
     * allows {@code PATCH} and a binary document does not, and the URI does not say which a
     * path names — only the stored media type does, which is core's fact and not the edge's.
     *
     * <p>So the exception carries {@link LdpKind}, the semantic kind, rather than a list of
     * methods: methods are HTTP, and a {@code CisternException} never speaks HTTP. The mapper
     * turns one kind into one {@code Allow} through cistern-webflux's {@code ResourceKind}
     * table, so a refusal and a successful read of the same resource advertise the same
     * interface by construction — which is what Solid Protocol §5.2 requires.
     */
    public static final class MethodNotAllowed extends CisternException {

        private final transient LdpKind kind;

        /**
         * @param message the refusal, for the problem document's detail
         * @param kind    what the refusing resource is, so the mapper can render an
         *                {@code Allow} that is exactly true of it
         */
        public MethodNotAllowed(String message, LdpKind kind) {
            super(message);
            this.kind = Objects.requireNonNull(kind, "kind");
        }

        /** The kind of the resource that refused — the input to its {@code Allow}. */
        public LdpKind kind() {
            return kind;
        }
    }

    /**
     * The request content is in a media type the target resource does not accept for this
     * method → 415 (RFC 9110 §15.5.16).
     *
     * <p>Raised for a {@code PATCH} whose body is not {@code text/n3} (T2.7). Solid Protocol
     * §5.3.1 identifies an N3 Patch by that media type and by no other, and RFC 5789 §2.2 names
     * the status: "Unsupported patch document: Can be specified using a 415 (Unsupported Media
     * Type) response when the client sends a patch document format that the server does not
     * support for the resource identified by the Request-URI."
     *
     * <p>A domain signal rather than Spring's {@code UnsupportedMediaTypeStatusException}
     * (which the mapper would also render, as an {@code ErrorResponse}) for one substantive
     * reason: RFC 5789 §2.2 continues "Such a response SHOULD include an {@code Accept-Patch}
     * response header ... to notify the client what patch document media types are supported",
     * and Spring's exception carries {@code Accept} instead — the wrong field, and one RFC 9110
     * does not define as a response header. Going through the domain path lets the single error
     * mapper attach the field the PATCH specification actually asks for, and gives the refusal
     * a Cistern problem type instead of {@code about:blank}.
     */
    public static final class UnsupportedMediaType extends CisternException {
        public UnsupportedMediaType(String message) { super(message); }
    }

    /** Authenticated agent lacks the required access mode → 403 (or 401 if unauthenticated). */
    public static final class AccessDenied extends CisternException {
        public AccessDenied(String message) { super(message); }
    }
}
