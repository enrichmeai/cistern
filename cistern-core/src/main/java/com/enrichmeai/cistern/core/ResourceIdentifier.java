package com.enrichmeai.cistern.core;

import java.net.URI;

/**
 * Identifies a resource in a pod. In Solid, the resource identifier IS the HTTP URI
 * (Solid Protocol §2.1 — URI persistence and trailing-slash semantics apply:
 * {@code /foo/} is a container, {@code /foo} is not, and they are distinct resources).
 *
 * @param uri absolute URI of the resource; must be normalized (no dot-segments, no fragment)
 */
public record ResourceIdentifier(URI uri) {

    public ResourceIdentifier {
        if (!uri.isAbsolute()) {
            throw new IllegalArgumentException("Resource identifier must be an absolute URI: " + uri);
        }
        if (uri.getRawFragment() != null) {
            throw new IllegalArgumentException("Resource identifier must not carry a fragment: " + uri);
        }
    }

    /** A container's URI always ends with '/'. This predicate is load-bearing across the codebase. */
    public boolean isContainer() {
        return uri.getPath() != null && uri.getPath().endsWith("/");
    }

    /** The parent container of this resource, or empty for the storage root. */
    public java.util.Optional<ResourceIdentifier> parent() {
        String path = uri.getPath();
        if (path == null || path.equals("/") || path.isEmpty()) {
            return java.util.Optional.empty();
        }
        String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        int lastSlash = trimmed.lastIndexOf('/');
        String parentPath = trimmed.substring(0, lastSlash + 1);
        return java.util.Optional.of(new ResourceIdentifier(uri.resolve(parentPath)));
    }
}
