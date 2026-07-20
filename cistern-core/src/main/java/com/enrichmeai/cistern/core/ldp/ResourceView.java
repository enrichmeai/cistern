package com.enrichmeai.cistern.core.ldp;

import com.enrichmeai.cistern.core.Representation;
import com.enrichmeai.cistern.core.ResourceIdentifier;
import com.enrichmeai.cistern.core.StoredResource;
import org.apache.jena.rdf.model.Model;

import java.time.Instant;

/**
 * What a read of a resource yields: the validator metadata every front-end needs, plus the
 * resource's content in the form that front-end must serve it in. Returned by
 * {@link LdpService#read(ResourceIdentifier)}.
 *
 * <h2>Why the split is in core and not in the HTTP layer</h2>
 * Solid has exactly two kinds of readable content and they are handled by disjoint rules:
 *
 * <ul>
 *   <li>An <b>RDF source</b> (every container, plus documents stored as {@code text/turtle}
 *       or {@code application/ld+json}) is a <em>graph</em>. Solid Protocol §5.5 requires the
 *       server to satisfy {@code GET} in both {@code text/turtle} and
 *       {@code application/ld+json}, so the serialization is chosen per request and the
 *       stored bytes are not the answer. A container's graph additionally contains derived
 *       {@code ldp:contains} triples that exist in no stored byte at all (§4.2), so for
 *       containers there is no "stored representation" to hand back.</li>
 *   <li>A <b>non-RDF source</b> (any other media type) is a byte stream. It is served
 *       verbatim with its stored media type: never parsed, never converted.</li>
 * </ul>
 *
 * Deciding which of the two applies is LDP/Solid semantics, so it belongs to
 * {@link LdpService}. The HTTP layer only chooses a serialization for {@link Rdf} and
 * copies bytes for {@link NonRdf}, which is what keeps cistern-webflux thin.
 *
 * <p>A sealed interface rather than one record with nullable fields: an exhaustive
 * {@code switch} in the front-end cannot forget a case, and no caller can ask a binary
 * resource for a graph.
 */
public sealed interface ResourceView {

    /** The resource read. */
    ResourceIdentifier identifier();

    /** Strong validator carried over from {@link StoredResource#etag()} (RFC 9110 §8.8.3). */
    String etag();

    /** Second-precision modification instant, ready for an HTTP-date (RFC 9110 §8.8.2). */
    Instant lastModified();

    /** True iff this resource is an LDP Basic Container (Solid Protocol §3.1, §4.2). */
    boolean container();

    /**
     * An RDF source: a container, or a document whose stored media type is one of the two
     * RDF types. The graph is the resource's live state — for a container that includes the
     * derived containment triples and the server-asserted LDP types, neither of which is
     * stored. Callers serialize it into the media type they negotiated ({@code RdfIo}).
     */
    record Rdf(
            ResourceIdentifier identifier,
            String etag,
            Instant lastModified,
            boolean container,
            Model graph) implements ResourceView {
    }

    /**
     * A non-RDF source: served verbatim with the stored media type. The representation is
     * exactly what the store returned — Cistern does not transcode binary resources, and no
     * content negotiation can turn one into RDF.
     *
     * <p>Never a container: a container's representation is always an RDF graph.
     */
    record NonRdf(
            ResourceIdentifier identifier,
            String etag,
            Instant lastModified,
            Representation representation) implements ResourceView {

        @Override
        public boolean container() {
            return false;
        }
    }
}
