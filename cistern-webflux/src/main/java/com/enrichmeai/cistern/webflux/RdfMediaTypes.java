package com.enrichmeai.cistern.webflux;

import com.enrichmeai.cistern.core.Representation;
import org.springframework.http.MediaType;

import java.util.List;

/**
 * The RDF serializations Cistern can produce, in server preference order.
 *
 * <p>Solid Protocol §5.5 fixes the set: "the server MUST satisfy {@code GET} requests on
 * this resource when the field value of the {@code Accept} header field requests a
 * representation in {@code text/turtle} or {@code application/ld+json}". Those two, no
 * more — {@code RdfIo} supports exactly the same pair, so the HTTP layer and the RDF layer
 * cannot disagree about what is serviceable.
 *
 * <p>Turtle is first, which makes it both the tie-break winner and the answer when the
 * client expresses no preference (LDP 1.0 §4.3.2.2: servers "SHOULD respond with a
 * {@code text/turtle} representation ... whenever the {@code Accept} request header is
 * absent").
 */
final class RdfMediaTypes {

    static final MediaType TURTLE = MediaType.parseMediaType(Representation.TURTLE);
    static final MediaType JSON_LD = MediaType.parseMediaType(Representation.JSON_LD);

    /** Server preference order; the first entry is the default and the tie-break winner. */
    static final List<MediaType> PREFERRED = List.of(TURTLE, JSON_LD);

    /** The bare media type string {@code RdfIo} expects for serialization. */
    static String canonical(MediaType type) {
        return JSON_LD.equalsTypeAndSubtype(type) ? Representation.JSON_LD : Representation.TURTLE;
    }

    private RdfMediaTypes() {
        // constants only
    }
}
