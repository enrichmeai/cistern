package com.enrichmeai.cistern.webflux;

import com.enrichmeai.cistern.core.ResourceIdentifier;
import com.enrichmeai.cistern.core.ldp.Ldp;
import com.enrichmeai.cistern.core.ldp.ResourceView;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.springframework.http.MediaType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * A strong entity-tag for one <em>representation</em> of a resource (RFC 9110 §8.8.3).
 *
 * <h2>Why this is not {@code StoredResource.etag()}</h2>
 * The store's validator hashes the stored bytes, which is the right validator for the stored
 * state but the wrong one for what a {@code GET} actually returns. RFC 9110 §8.8.1 requires a
 * strong validator to change whenever the representation changes, and the served
 * representation differs from the stored bytes in two ways:
 *
 * <ul>
 *   <li><b>A container's representation includes derived containment.</b>
 *       {@code ldp:contains} triples are computed from the live children (Solid Protocol
 *       §4.2) and are in no stored byte, so adding or deleting a child changes what is served
 *       while the stored etag stands still — a shared cache would keep serving a stale
 *       listing, and a client could later send {@code If-Match} on a tag that no longer
 *       describes what it holds.</li>
 *   <li><b>One resource has two RDF representations.</b> The Turtle and JSON-LD renderings of
 *       one graph are different byte sequences, so they must not share a validator.</li>
 * </ul>
 *
 * <h2>Hashed over canonical inputs, never over serializer output</h2>
 * The digest covers the store's validator, the media type of the representation, and — for a
 * container — the sorted set of contained URIs. It deliberately does <b>not</b> hash the
 * serialized bytes. That is not a theoretical concern: serializing one graph (with blank
 * nodes) 25 times through {@code RdfIo} shows Jena's <b>JSON-LD writer producing different
 * bytes on repeat calls within a single JVM</b>, and a different digest again in a second JVM;
 * Turtle happened to be stable in the same experiment, but nothing in Jena's contract promises
 * it. Hashing output would therefore emit a fresh validator on almost every JSON-LD request —
 * strictly worse than a stale one, since it defeats caching outright and makes conditional
 * requests fail spuriously. Hashing the logical inputs is deterministic by construction and
 * changes exactly when the representation changes.
 *
 * <p>Computed in one place so {@code GET}, {@code HEAD} and T2.5's conditional requests
 * cannot disagree about a resource's validator.
 *
 * @param value the opaque validator, without the surrounding quotes
 */
record EntityTag(String value) {

    private static final String DIGEST_ALGORITHM = "SHA-256";

    /** Separates hashed fields so concatenations cannot collide; illegal in URIs and media types. */
    private static final byte FIELD_SEPARATOR = 0x00;

    /** RFC 9110 §8.8.3 — a strong entity-tag is the opaque value in double quotes. */
    private static final String STRONG_TAG_TEMPLATE = "\"%s\"";

    EntityTag {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("An entity-tag needs an opaque value");
        }
    }

    /**
     * The validator for the representation of {@code view} that would be served as
     * {@code mediaType}.
     *
     * @param view      the resource as core resolved it
     * @param mediaType the media type of the representation being described — the negotiated
     *                  one for an RDF source, the stored one for a non-RDF source
     */
    static EntityTag forRepresentation(ResourceView view, MediaType mediaType) {
        MessageDigest digest = newDigest();
        update(digest, view.etag());
        update(digest, mediaType.toString());
        if (view instanceof ResourceView.Rdf rdf && rdf.container()) {
            for (String child : containedUris(rdf.graph(), rdf.identifier())) {
                update(digest, child);
            }
        }
        return new EntityTag(HexFormat.of().formatHex(digest.digest()));
    }

    /** The {@code ETag} field value: quoted, and strong (no {@code W/} prefix). */
    String headerValue() {
        return STRONG_TAG_TEMPLATE.formatted(value);
    }

    /**
     * The container's contained URIs, sorted so the digest does not depend on the order the
     * backend happened to list its directory in. Read back from the merged graph rather than
     * from a second {@code children()} call: the graph is the representation being described,
     * so nothing can drift between what is hashed and what is served.
     */
    private static List<String> containedUris(Model graph, ResourceIdentifier container) {
        Resource subject = graph.createResource(container.uri().toString());
        return graph.listObjectsOfProperty(subject, Ldp.CONTAINS).toList().stream()
                .filter(RDFNode::isURIResource)
                .map(node -> node.asResource().getURI())
                .sorted()
                .toList();
    }

    private static void update(MessageDigest digest, String field) {
        digest.update(field.getBytes(StandardCharsets.UTF_8));
        digest.update(FIELD_SEPARATOR);
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance(DIGEST_ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM without " + DIGEST_ALGORITHM, e);
        }
    }
}
