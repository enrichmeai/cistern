package com.enrichmeai.cistern.cli;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Objects;

/**
 * A resource's path on the server, as typed on the command line: {@code /trips/} (a container)
 * or {@code /trips/lisbon} (a document).
 *
 * <p>The rules are the server's own for a request-target ({@code RequestPaths} in
 * cistern-webflux), applied before anything is sent, so the tool refuses what the server would
 * refuse with 400 and — more importantly — never mints an identifier the server would spell
 * differently: absolute ({@code /…}); no {@code .} or {@code ..} segments; no empty segment
 * ({@code //}); no fragment or query. Percent-encoding is left exactly as typed, since the
 * server keys resources by raw path.
 *
 * @param value the raw path, beginning with {@code /}
 */
record PodPath(String value) {

    private static final String SEPARATOR = "/";
    private static final String EMPTY_SEGMENT = SEPARATOR + SEPARATOR;
    private static final String CURRENT_SEGMENT = ".";
    private static final String PARENT_SEGMENT = "..";
    private static final String FRAGMENT_MARKER = "#";
    private static final String QUERY_MARKER = "?";
    private static final int KEEP_TRAILING_EMPTY = -1;

    PodPath {
        Objects.requireNonNull(value, "value");
        if (!value.startsWith(SEPARATOR) || value.contains(EMPTY_SEGMENT)
                || value.contains(FRAGMENT_MARKER) || value.contains(QUERY_MARKER)) {
            throw new IllegalArgumentException(CliMessage.INVALID_PATH.format(value));
        }
        for (String segment : value.substring(SEPARATOR.length()).split(SEPARATOR, KEEP_TRAILING_EMPTY)) {
            if (segment.equals(CURRENT_SEGMENT) || segment.equals(PARENT_SEGMENT)) {
                throw new IllegalArgumentException(CliMessage.INVALID_PATH.format(value));
            }
        }
        try {
            new URI(value);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(CliMessage.INVALID_PATH.format(value), e);
        }
    }

    /** Solid Protocol §3.1: a container's path ends in {@code /}. */
    boolean isContainer() {
        return value.endsWith(SEPARATOR);
    }
}
