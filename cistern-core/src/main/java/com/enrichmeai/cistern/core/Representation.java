package com.enrichmeai.cistern.core;

import com.enrichmeai.cistern.core.rdf.RdfMediaType;

/**
 * A concrete serialization of a resource: bytes plus their media type.
 *
 * <p>The storage SPI deals ONLY in representations — storage backends never parse RDF.
 * Format conversion (Turtle ⇄ JSON-LD) happens in cistern-core's RDF layer, above the SPI.
 * This keeps backends dumb, swappable, and testable with a shared contract-test kit.
 *
 * @param contentType IANA media type as stored (e.g. "text/turtle", "application/ld+json",
 *                    or any non-RDF type for binary resources)
 * @param data        the serialized bytes; never null, may be empty
 */
public record Representation(String contentType, byte[] data) {

    /**
     * The supported RDF media types, spelled once in {@link RdfMediaType} — which also carries
     * each one's Jena mapping — and exposed here as the strings storage and the HTTP layer
     * compare against (ground rule 7: one closed set, one enum, no second list).
     */
    public static final String TURTLE = RdfMediaType.TURTLE.contentType();

    public static final String JSON_LD = RdfMediaType.JSON_LD.contentType();

    /**
     * Whether these bytes are an RDF source — answered by {@link RdfMediaType}, so "is this
     * RDF?" has exactly one implementation.
     *
     * <p>Exact match on the stored type: a stored content type is already canonical (the RDF
     * layer writes back {@code RdfMediaType.contentType()}), so no parameter stripping is
     * wanted here.
     */
    public boolean isRdf() {
        return TURTLE.equals(contentType) || JSON_LD.equals(contentType);
    }
}
