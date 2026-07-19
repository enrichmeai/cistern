package com.enrichmeai.cistern.core.rdf;

import com.enrichmeai.cistern.core.CisternException;
import com.enrichmeai.cistern.core.Representation;
import com.enrichmeai.cistern.core.ResourceIdentifier;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFFormat;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.riot.RDFWriter;
import org.apache.jena.riot.system.ErrorHandlerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Locale;

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
            throw new CisternException.BadInput("Cannot parse RDF: no representation given");
        }
        if (representation.data() == null) {
            throw new CisternException.BadInput("Cannot parse RDF: representation has no data");
        }
        if (resource == null) {
            throw new CisternException.BadInput("Cannot parse RDF: no resource identifier given as base");
        }
        Lang lang = langFor(representation.contentType());
        Model model = ModelFactory.createDefaultModel();
        try {
            RDFParser.create()
                    .source(new ByteArrayInputStream(representation.data()))
                    .forceLang(lang)
                    .base(resource.uri().toString())
                    .errorHandler(ErrorHandlerFactory.errorHandlerStrictNoLogging)
                    .parse(model);
        } catch (RuntimeException e) {
            throw new CisternException.BadInput(
                    "Malformed " + lang.getContentType().getContentTypeStr() + " document for <"
                            + resource.uri() + ">: " + safeMessage(e));
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
            throw new CisternException.BadInput("Cannot serialize RDF: no model given");
        }
        String canonical = canonicalMediaType(contentType);
        RDFFormat format = Representation.TURTLE.equals(canonical) ? RDFFormat.TURTLE : RDFFormat.JSONLD;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            RDFWriter.source(model).format(format).output(out);
        } catch (RuntimeException e) {
            throw new CisternException.BadInput(
                    "Cannot serialize RDF as " + canonical + ": " + safeMessage(e));
        }
        return new Representation(canonical, out.toByteArray());
    }

    private static Lang langFor(String contentType) {
        String canonical = canonicalMediaType(contentType);
        return Representation.TURTLE.equals(canonical) ? Lang.TURTLE : Lang.JSONLD;
    }

    /**
     * Reduces a media type to its canonical bare form (parameters stripped, lower-cased)
     * and rejects anything other than the two supported RDF types.
     */
    private static String canonicalMediaType(String contentType) {
        if (contentType == null) {
            throw new CisternException.BadInput(
                    "No content type given; supported RDF content types: "
                            + Representation.TURTLE + ", " + Representation.JSON_LD);
        }
        int semicolon = contentType.indexOf(';');
        String bare = (semicolon >= 0 ? contentType.substring(0, semicolon) : contentType)
                .trim()
                .toLowerCase(Locale.ROOT);
        if (!Representation.TURTLE.equals(bare) && !Representation.JSON_LD.equals(bare)) {
            throw new CisternException.BadInput(
                    "Unsupported RDF content type \"" + contentType + "\"; supported: "
                            + Representation.TURTLE + ", " + Representation.JSON_LD);
        }
        return bare;
    }

    private static String safeMessage(Throwable t) {
        return t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
    }
}
