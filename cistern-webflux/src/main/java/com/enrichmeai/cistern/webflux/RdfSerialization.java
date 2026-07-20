package com.enrichmeai.cistern.webflux;

import com.enrichmeai.cistern.core.Representation;
import com.enrichmeai.cistern.core.rdf.RdfIo;
import org.apache.jena.rdf.model.Model;
import org.springframework.http.MediaType;

import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The serializations Cistern can produce for an RDF source — a closed set, so an enum.
 *
 * <p>Solid Protocol §5.5 fixes exactly which: "the server MUST satisfy {@code GET} requests
 * on this resource when the field value of the {@code Accept} header field requests a
 * representation in {@code text/turtle} or {@code application/ld+json}". {@code RdfIo}
 * supports the same pair, so the HTTP layer and the RDF layer cannot disagree about what is
 * serviceable.
 *
 * <p>Declaration order is server preference order: Turtle first, which makes it both the
 * tie-break winner and the answer when the client states no preference (LDP 1.0 §4.3.2.2 —
 * servers "SHOULD respond with a {@code text/turtle} representation ... whenever the
 * {@code Accept} request header is absent").
 *
 * <p>Each constant owns the mapping to its two spellings — the Spring {@link MediaType} used
 * for negotiation and response headers, and the bare media-type string {@code RdfIo} expects
 * — so no code elsewhere compares or concatenates media-type strings.
 */
enum RdfSerialization {

    /** {@code text/turtle} — the pod's preferred form and the no-preference default. */
    TURTLE(Representation.TURTLE),

    /** {@code application/ld+json}. */
    JSON_LD(Representation.JSON_LD);

    private final String contentType;
    private final MediaType mediaType;

    RdfSerialization(String contentType) {
        this.contentType = contentType;
        this.mediaType = MediaType.parseMediaType(contentType);
    }

    /** The serialization used when the client expresses no preference, and on a tie. */
    static RdfSerialization preferred() {
        return TURTLE;
    }

    /**
     * The serialization a client declared, if it named one — the write-side counterpart of
     * negotiation, and the single answer in this module to "is this media type RDF?".
     *
     * <p>Matched on type and subtype only, so parameters are ignored:
     * {@code text/turtle;charset=utf-8} is Turtle. That is what lets {@link RequestMediaType}
     * canonicalize a declared type to the bare spelling {@code Representation.isRdf()} compares
     * against, without a second table of RDF media types existing anywhere.
     *
     * @param declared the media type as the client wrote it
     * @return the matching serialization, or empty if the type is not an RDF one
     */
    static Optional<RdfSerialization> forMediaType(MediaType declared) {
        return Stream.of(values())
                .filter(serialization -> serialization.mediaType.equalsTypeAndSubtype(declared))
                .findFirst();
    }

    /** {@code "text/turtle, application/ld+json"} — for {@code Accept-Put} on containers. */
    static String mediaTypeList() {
        return Stream.of(values())
                .map(RdfSerialization::contentType)
                .collect(Collectors.joining(HttpConstants.LIST_SEPARATOR));
    }

    MediaType mediaType() {
        return mediaType;
    }

    String contentType() {
        return contentType;
    }

    /** Serializes a graph in this form. The only place a serialization choice reaches Jena. */
    Representation serialize(Model graph) {
        return RdfIo.serialize(graph, contentType);
    }
}
