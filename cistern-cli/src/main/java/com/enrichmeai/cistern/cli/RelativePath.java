package com.enrichmeai.cistern.cli;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.StringJoiner;

/**
 * Where an entry sits under the folder being mirrored, as the pod will know it: segments joined
 * by {@code /} whatever the local separator, a container form ending in {@code /} (Solid
 * Protocol §3.1), and never an empty, {@code .} or {@code ..} segment. It is the key of the
 * state file and the name a plan line prints.
 *
 * <p>Separate from {@link PodPath} because it is relative and unencoded — it is a name on disk.
 * The two meet exactly once, in {@link #under}, where each segment is percent-encoded so that
 * the identifier the server keys the resource by is one the server would mint itself
 * ({@code RequestPaths} keys by raw path, and a space or a {@code #} in a file name has to be
 * encoded to travel at all). Encoding only there keeps the state file as readable as the folder
 * is, and spells the pod path once.
 *
 * <p>Ordered lexicographically, which puts every container before its members — a parent's
 * value is a prefix of its children's — so a plan sorted on it creates parents first and one
 * sorted in reverse deletes children first; the same folder always yields the same transcript.
 *
 * @param value the {@code /}-joined relative path, ending in {@code /} for a container
 */
record RelativePath(String value) implements Comparable<RelativePath> {

    static final String SEPARATOR = "/";
    private static final String EMPTY_SEGMENT = SEPARATOR + SEPARATOR;
    private static final String CURRENT_SEGMENT = ".";
    private static final String PARENT_SEGMENT = "..";
    private static final int KEEP_TRAILING_EMPTY = -1;

    RelativePath {
        Objects.requireNonNull(value, "value");
        if (value.isBlank() || value.startsWith(SEPARATOR) || value.contains(EMPTY_SEGMENT)) {
            throw new IllegalArgumentException(CliMessage.INVALID_RELATIVE_PATH.format(value));
        }
        for (String segment : value.split(SEPARATOR, KEEP_TRAILING_EMPTY)) {
            if (segment.equals(CURRENT_SEGMENT) || segment.equals(PARENT_SEGMENT)) {
                throw new IllegalArgumentException(CliMessage.INVALID_RELATIVE_PATH.format(value));
            }
        }
    }

    /** {@code entry} as it sits under {@code root}; {@code container} adds the trailing slash. */
    static RelativePath of(Path root, Path entry, boolean container) {
        StringJoiner joined = new StringJoiner(SEPARATOR);
        for (Path segment : root.relativize(entry)) {
            joined.add(segment.toString());
        }
        return new RelativePath(container ? joined + SEPARATOR : joined.toString());
    }

    /** Solid Protocol §3.1: a container's path ends in {@code /}. */
    boolean isContainer() {
        return value.endsWith(SEPARATOR);
    }

    /** The container this sits in, or empty at the top of the folder. */
    Optional<RelativePath> parent() {
        String trimmed = isContainer() ? value.substring(0, value.length() - SEPARATOR.length()) : value;
        int slash = trimmed.lastIndexOf(SEPARATOR);
        return slash < 0
                ? Optional.empty()
                : Optional.of(new RelativePath(trimmed.substring(0, slash + SEPARATOR.length())));
    }

    /**
     * This path below {@code container} on the server, each segment percent-encoded as RFC 3986
     * §3.3 requires of a path segment.
     */
    PodPath under(PodPath container) {
        if (!container.isContainer()) {
            throw new IllegalArgumentException(CliMessage.INVALID_TARGET_CONTAINER.format(container.value()));
        }
        return new PodPath(container.value() + encoded());
    }

    /**
     * The multi-argument {@link URI} constructor quotes every octet a path may not carry raw —
     * space, {@code #}, {@code ?}, {@code %} among them — and {@link URI#toASCIIString()}
     * encodes the non-ASCII rest; the separators are left alone, which is exactly the split
     * the server's {@code RequestPaths} keeps.
     */
    private String encoded() {
        try {
            return new URI(null, null, SEPARATOR + value, null).toASCIIString().substring(SEPARATOR.length());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(CliMessage.INVALID_RELATIVE_PATH.format(value), e);
        }
    }

    @Override
    public int compareTo(RelativePath other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
