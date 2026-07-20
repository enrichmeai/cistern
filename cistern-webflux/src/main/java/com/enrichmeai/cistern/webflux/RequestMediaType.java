package com.enrichmeai.cistern.webflux;

import com.enrichmeai.cistern.core.CisternException;
import com.enrichmeai.cistern.core.Representation;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.ServerRequest;

import java.util.Objects;

/**
 * The media type a write request declares, reduced to the canonical form Cistern stores.
 *
 * <h2>Why canonicalization is load-bearing and not cosmetic</h2>
 * {@link Representation#isRdf()} decides whether a resource is an RDF source, and it does so by
 * exact string equality against {@code text/turtle} and {@code application/ld+json}. A body
 * sent as {@code text/turtle;charset=utf-8} would therefore be stored as an <em>opaque
 * binary</em> resource: it would never be parsed on read, never be serializable as JSON-LD, and
 * so never satisfy Solid Protocol §5.5 ("the server MUST satisfy GET requests ... in
 * text/turtle or application/ld+json") — for the lifetime of that resource, because nothing
 * downstream can recover the intent afterwards. Charset parameters on Turtle are entirely
 * ordinary, so this is the common case, not an edge case.
 *
 * <p>The canonical form is the bare type, lower-cased, with all parameters dropped, and the
 * record's invariant enforces that no non-canonical instance can exist. Whether a type is RDF
 * is answered by {@link RdfSerialization} — the enum T2.1 already uses to decide the same
 * question on the read side — so there is exactly one notion of "which media types are RDF" in
 * the module.
 *
 * <h2>Known consequence: parameters are dropped for non-RDF types too</h2>
 * {@code text/plain;charset=iso-8859-1} is stored as {@code text/plain}, so a later {@code GET}
 * serves the bytes back without the charset the client declared. The bytes are unchanged and no
 * RDF behaviour is affected, but the fidelity loss is real and deliberate: the rule is uniform,
 * which keeps one code path and makes stored types predictable. Preserving parameters for
 * non-RDF types alone is a live question for the architect and belongs with issue #60, which
 * already tracks giving {@code Representation} a proper media-type value type instead of string
 * equality.
 *
 * @param mediaType the canonical type: no parameters, lower-cased, never a wildcard
 */
record RequestMediaType(MediaType mediaType) {

    RequestMediaType {
        Objects.requireNonNull(mediaType, "mediaType");
        if (!mediaType.getParameters().isEmpty()) {
            throw new IllegalArgumentException(
                    "A canonical request media type carries no parameters: " + mediaType);
        }
    }

    /**
     * The declared media type of a write request.
     *
     * <p>Solid Protocol §2.1: "Server MUST reject {@code PUT}, {@code POST}, and {@code PATCH}
     * requests that contain content but lack the {@code Content-Type} header field, with a
     * status code of {@code 400}." Cistern rejects an absent {@code Content-Type} on a write
     * whether or not content accompanies it: with no declared type there is no basis on which
     * to choose what a body — even an empty one — would be stored as, and guessing would decide
     * RDF-source-ness by accident. A malformed one is refused for the same reason.
     *
     * @param request the write request
     * @return the canonical media type to store the body under
     * @throws CisternException.BadInput if {@code Content-Type} is absent, unparseable, or not
     *                                   a concrete media type (→ 400 via the single error mapper)
     */
    static RequestMediaType required(ServerRequest request) {
        MediaType declared;
        try {
            declared = request.headers().contentType().orElse(null);
        } catch (InvalidMediaTypeException e) {
            throw new CisternException.BadInput(
                    WebfluxMessage.CONTENT_TYPE_MALFORMED.format(e.getMessage()));
        }
        if (declared == null) {
            throw new CisternException.BadInput(WebfluxMessage.CONTENT_TYPE_REQUIRED.format());
        }
        return of(declared);
    }

    /**
     * Package-visible seam so the canonicalization rules can be unit-tested without an HTTP
     * stack (the same arrangement {@link RequestPaths} uses).
     *
     * @param declared the media type as the client wrote it
     * @throws CisternException.BadInput if it is a range rather than a concrete type
     */
    static RequestMediaType of(MediaType declared) {
        if (declared.isWildcardType() || declared.isWildcardSubtype()) {
            // RFC 9110 §8.3: Content-Type states the media type of the enclosed
            // representation. A range such as */* describes what is acceptable, not what
            // something IS, so it cannot name what to store the body as.
            throw new CisternException.BadInput(
                    WebfluxMessage.CONTENT_TYPE_NOT_CONCRETE.format(declared));
        }
        return new RequestMediaType(RdfSerialization.forMediaType(declared)
                .map(RdfSerialization::mediaType)
                .orElseGet(() -> new MediaType(declared.getType(), declared.getSubtype())));
    }

    /**
     * The spelling the storage layer keeps, which is exactly what {@link Representation#isRdf()}
     * compares against for an RDF type.
     */
    String contentType() {
        return mediaType.toString();
    }

    /** The body as core takes it: canonical media type plus the client's bytes, untouched. */
    Representation representationOf(byte[] body) {
        return new Representation(contentType(), body);
    }
}
