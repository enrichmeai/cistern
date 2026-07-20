package com.enrichmeai.cistern.core.rdf;

import com.enrichmeai.cistern.core.CisternException;
import com.enrichmeai.cistern.core.CoreMessage;
import com.enrichmeai.cistern.core.Representation;
import com.enrichmeai.cistern.core.ResourceIdentifier;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.riot.RDFWriter;
import org.apache.jena.riot.system.ErrorHandlerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/**
 * Parse and serialize RDF resource representations.
 *
 * <p>Solid Protocol §5.5 (Resource Representations) requires a server that creates an RDF
 * source on {@code PUT}, {@code POST} or {@code PATCH} to satisfy {@code GET} requests in
 * {@code text/turtle} and {@code application/ld+json}; those two media types are exactly
 * what this class supports. Relative references in an incoming document are resolved
 * against the URI of the resource the representation belongs to (Solid Protocol §1.4.1 —
 * relative referencing of identifiers per the URI standards, i.e. RFC 3986 §5.1.3: base is
 * the retrieval URI).
 *
 * <p>Deliberately synchronous: parsing is CPU-bound, so callers lift invocations into
 * their reactive chains (e.g. {@code Mono.fromCallable}) rather than this class faking
 * asynchrony. It never returns reactive types.
 *
 * <p>Every failure mode — malformed syntax, an unsupported or unknown content type, null
 * input — surfaces as {@link CisternException.BadInput}. No Jena exception type
 * ({@code RiotException} etc.) escapes this class.
 */
public final class RdfIo {

    private RdfIo() {
        // static utility
    }

    /**
     * Parses an RDF representation into a Jena model, resolving relative URIs in the
     * document against the resource URI as base.
     *
     * @param representation the bytes and media type to parse; media type must be
     *                       {@link Representation#TURTLE} or {@link Representation#JSON_LD}
     *                       (parameters such as {@code ;charset=utf-8} are tolerated)
     * @param resource       the resource the representation belongs to; its URI is the
     *                       base for relative reference resolution
     * @return the parsed graph, with all URIs absolute
     * @throws CisternException.BadInput on malformed input, unsupported content type, or null arguments
     */
    public static Model parse(Representation representation, ResourceIdentifier resource) {
        if (representation == null) {
            throw new CisternException.BadInput(CoreMessage.RDF_NO_REPRESENTATION.format());
        }
        if (representation.data() == null) {
            throw new CisternException.BadInput(CoreMessage.RDF_NO_DATA.format());
        }
        if (resource == null) {
            throw new CisternException.BadInput(CoreMessage.RDF_NO_BASE.format());
        }
        RdfMediaType type = RdfMediaType.require(representation.contentType());
        Model model = ModelFactory.createDefaultModel();
        try {
            RDFParser.create()
                    .source(new ByteArrayInputStream(representation.data()))
                    .forceLang(type.lang())
                    .base(resource.uri().toString())
                    .errorHandler(ErrorHandlerFactory.errorHandlerStrictNoLogging)
                    .parse(model);
        } catch (RuntimeException e) {
            throw new CisternException.BadInput(CoreMessage.RDF_DOCUMENT_MALFORMED.format(
                    type.contentType(), resource.uri(), safeMessage(e)));
        }
        return model;
    }

    /**
     * Serializes a Jena model into the requested RDF media type. URIs are written in
     * absolute form (no relativization).
     *
     * @param model       the graph to serialize
     * @param contentType {@link Representation#TURTLE} or {@link Representation#JSON_LD}
     *                    (parameters such as {@code ;charset=utf-8} are tolerated)
     * @return a representation whose content type is the canonical bare media type
     * @throws CisternException.BadInput on an unsupported content type or null arguments
     */
    public static Representation serialize(Model model, String contentType) {
        if (model == null) {
            throw new CisternException.BadInput(CoreMessage.RDF_NO_MODEL.format());
        }
        RdfMediaType type = RdfMediaType.require(contentType);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            RDFWriter.source(model).format(type.format()).output(out);
        } catch (RuntimeException e) {
            throw new CisternException.BadInput(CoreMessage.RDF_SERIALIZATION_FAILED.format(
                    type.contentType(), safeMessage(e)));
        }
        return new Representation(type.contentType(), out.toByteArray());
    }

    private static String safeMessage(Throwable t) {
        return t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
    }
}
