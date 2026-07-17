package com.enrichmeai.cistern.core;

import java.time.Instant;

/**
 * A resource as returned by a {@link ResourceStore}: representation plus the metadata
 * the HTTP layer needs for conditional requests (Solid Protocol §5 / RFC 9110 §13).
 *
 * @param identifier     the resource's URI
 * @param representation stored bytes + media type
 * @param etag           strong validator; MUST change whenever the representation changes.
 *                       Backends choose the scheme (content hash recommended) but the
 *                       contract-test kit verifies change-on-write behaviour.
 * @param lastModified   last modification instant, second precision (HTTP date resolution)
 */
public record StoredResource(
        ResourceIdentifier identifier,
        Representation representation,
        String etag,
        Instant lastModified) {
}
