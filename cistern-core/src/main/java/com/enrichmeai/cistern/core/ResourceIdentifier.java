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

    /**
     * True iff this identifier is the storage's root container — the one resource with no
     * parent container, i.e. the URI path {@code /} (or an empty path, which names the same
     * resource: RFC 3986 §6.2.3 makes {@code http://host} and {@code http://host/}
     * equivalent).
     *
     * <p>Defined as "has no parent" rather than by comparing against a configured value, so
     * the predicate cannot disagree with {@link #parent()} — the pair is what the containment
     * hierarchy is built from, and {@code FileResourceStore} maps exactly this identifier onto
     * its root directory. Solid Protocol §5.4 singles the root container out: {@code DELETE}
     * on it MUST be refused with 405, and {@code DELETE} MUST be excluded from its
     * {@code Allow}.
     */
    public boolean isStorageRoot() {
        return parent().isEmpty();
    }

    /** The parent container of this resource, or empty for the storage root. */
    public java.util.Optional<ResourceIdentifier> parent() {
        // Work on the RAW (percent-encoded) path. Feeding a decoded path back through
        // uri.resolve(String) re-parses it as a URI reference, so any octet that decodes
        // to a raw-illegal character (space, non-ASCII, ...) throws (issue #54). The '/'
        // separators are never percent-encoded, so raw-path segmentation is exact.
        String rawPath = uri.getRawPath();
        if (rawPath == null || rawPath.equals("/") || rawPath.isEmpty()) {
            return java.util.Optional.empty();
        }
        String trimmed = rawPath.endsWith("/") ? rawPath.substring(0, rawPath.length() - 1) : rawPath;
        int lastSlash = trimmed.lastIndexOf('/');
        String parentRawPath = trimmed.substring(0, lastSlash + 1);
        return java.util.Optional.of(new ResourceIdentifier(withRawPath(uri, parentRawPath)));
    }

    /**
     * Rebuild a URI from its RAW components, substituting only the raw path. Scheme,
     * authority and query are carried across verbatim (already-encoded) so nothing is
     * re-encoded or re-decoded; {@code URI.create} only re-parses an already-valid raw
     * form. The record invariants (absolute, no fragment) keep holding because the base
     * satisfied them and only the path changes.
     */
    private static URI withRawPath(URI uri, String newRawPath) {
        StringBuilder sb = new StringBuilder();
        sb.append(uri.getScheme()).append(':');
        String rawAuthority = uri.getRawAuthority();
        if (rawAuthority != null) {
            sb.append("//").append(rawAuthority);
        }
        sb.append(newRawPath);
        String rawQuery = uri.getRawQuery();
        if (rawQuery != null) {
            sb.append('?').append(rawQuery);
        }
        return URI.create(sb.toString());
    }
}
