package com.enrichmeai.cistern.core.rdf;

import com.enrichmeai.cistern.core.CisternException;
import com.enrichmeai.cistern.core.CoreMessage;

import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFFormat;

import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The RDF serializations cistern-core can read and write — a closed set, so an enum
 * (ground rule 7), each constant carrying the media type together with the Jena
 * {@link Lang} it parses as and the {@link RDFFormat} it writes as.
 *
 * <p>Solid Protocol §5.5 fixes the membership: a server that creates an RDF source must
 * satisfy {@code GET} requests for {@code text/turtle} and {@code application/ld+json}.
 * Those two, and no others.
 *
 * <h2>Why core has this and cistern-webflux has {@code RdfSerialization}</h2>
 * They are two different facts about the same closed set, and neither module can hold both.
 * This enum answers "what does Jena call this, and how do I parse or write it?" —
 * {@link Lang} and {@link RDFFormat} are RDF-layer concerns that must not leak into the HTTP
 * edge. {@code RdfSerialization} answers "what does Spring call this, and how do I negotiate
 * it?" — its {@code MediaType} is a Spring type, and cistern-core takes no Spring dependency
 * (ground rule 5), so it cannot live here and this cannot move there.
 *
 * <p>They are not a third notion, and they cannot drift: {@code RdfSerialization}'s constants
 * are constructed from {@code Representation}'s media-type strings, which are in turn this
 * enum's {@link #contentType()}. One list of media types, spelled once, projected into each
 * layer's vocabulary.
 *
 * <p>Declaration order is preference order: Turtle first (LDP 1.0 §4.3.2.2 makes it the
 * no-preference default), which is also the order {@link #contentTypeList()} renders.
 */
public enum RdfMediaType {

    /** {@code text/turtle} — the pod's preferred RDF form. */
    TURTLE("text/turtle", Lang.TURTLE, RDFFormat.TURTLE),

    /** {@code application/ld+json}. */
    JSON_LD("application/ld+json", Lang.JSONLD, RDFFormat.JSONLD);

    private final String contentType;
    private final transient Lang lang;
    private final transient RDFFormat format;

    RdfMediaType(String contentType, Lang lang, RDFFormat format) {
        this.contentType = contentType;
        this.lang = lang;
        this.format = format;
    }

    /** The canonical bare media type, as stored and as written to {@code Content-Type}. */
    public String contentType() {
        return contentType;
    }

    /** The Jena language this is parsed as — the only place a media type becomes a {@link Lang}. */
    Lang lang() {
        return lang;
    }

    /** The Jena format this is written as — the only place a media type becomes an {@link RDFFormat}. */
    RDFFormat format() {
        return format;
    }

    /**
     * The serialization named by {@code contentType}, or empty if it names none.
     *
     * <p>Parameters are tolerated and stripped ({@code text/turtle;charset=utf-8} is Turtle),
     * and matching is case-insensitive, as RFC 9110 §8.3.1 requires of type and subtype.
     */
    public static Optional<RdfMediaType> of(String contentType) {
        if (contentType == null) {
            return Optional.empty();
        }
        String bare = canonicalize(contentType);
        return Stream.of(values())
                .filter(type -> type.contentType.equals(bare))
                .findFirst();
    }

    /**
     * The serialization named by {@code contentType}, or {@link CisternException.BadInput} if
     * it names none — the form the RDF layer uses, where an unsupported type is a client fault
     * rather than a question to answer.
     */
    static RdfMediaType require(String contentType) {
        if (contentType == null) {
            throw new CisternException.BadInput(
                    CoreMessage.RDF_CONTENT_TYPE_MISSING.format(contentTypeList()));
        }
        return of(contentType).orElseThrow(() -> new CisternException.BadInput(
                CoreMessage.RDF_CONTENT_TYPE_UNSUPPORTED.format(contentType, contentTypeList())));
    }

    /** Whether {@code contentType} names an RDF serialization Cistern supports. */
    public static boolean isRdf(String contentType) {
        return of(contentType).isPresent();
    }

    /** {@code "text/turtle, application/ld+json"} — the supported set, for a message or a header. */
    public static String contentTypeList() {
        return Stream.of(values())
                .map(RdfMediaType::contentType)
                .collect(Collectors.joining(", "));
    }

    /** Media type reduced to its bare lower-case form: parameters stripped, whitespace trimmed. */
    private static String canonicalize(String contentType) {
        int semicolon = contentType.indexOf(';');
        return (semicolon >= 0 ? contentType.substring(0, semicolon) : contentType)
                .trim()
                .toLowerCase(Locale.ROOT);
    }
}
