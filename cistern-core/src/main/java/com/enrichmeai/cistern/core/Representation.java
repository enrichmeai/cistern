package com.enrichmeai.cistern.core;

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

    public static final String TURTLE = "text/turtle";
    public static final String JSON_LD = "application/ld+json";

    public boolean isRdf() {
        return TURTLE.equals(contentType) || JSON_LD.equals(contentType);
    }
}
