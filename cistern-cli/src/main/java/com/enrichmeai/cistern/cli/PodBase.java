package com.enrichmeai.cistern.cli;

import com.enrichmeai.cistern.core.ResourceIdentifier;

import java.net.URI;
import java.util.Objects;

/**
 * Where the server is: the origin (and optional path prefix) that {@code cistern.base-url}
 * publishes the pod under. Resource identifiers are minted by appending a {@link PodPath}, so
 * they match what the server mints for the same request path — in Solid the identifier
 * <em>is</em> the URI, and a mismatch here would edit the wrong resource's ACL.
 *
 * <p>A trailing slash is insignificant and stripped, as {@code CisternProperties} strips it.
 *
 * @param uri absolute http(s) URI, no fragment, no query, no trailing slash
 */
record PodBase(URI uri) {

    /** What the CLI talks to when nothing else is said: the loopback port compose and k8s expose. */
    static final String DEFAULT = "http://127.0.0.1:3737";

    private static final String TRAILING_SEPARATOR = "/";
    private static final String HTTP = "http";
    private static final String HTTPS = "https";

    PodBase {
        Objects.requireNonNull(uri, "uri");
        String text = uri.toString();
        while (text.endsWith(TRAILING_SEPARATOR)) {
            text = text.substring(0, text.length() - TRAILING_SEPARATOR.length());
        }
        uri = URI.create(text);
        boolean web = HTTP.equalsIgnoreCase(uri.getScheme()) || HTTPS.equalsIgnoreCase(uri.getScheme());
        if (!uri.isAbsolute() || !web || uri.getRawFragment() != null || uri.getRawQuery() != null
                || uri.getRawAuthority() == null) {
            throw new IllegalArgumentException(CliMessage.INVALID_BASE.format(text));
        }
    }

    /** {@code path} as the resource it names on this server. */
    ResourceIdentifier resolve(PodPath path) {
        return new ResourceIdentifier(URI.create(uri.toString() + path.value()));
    }

    /**
     * {@code resource} as the reader typed it — its path on this server — or its full URI if it
     * lives elsewhere. For output only; identity stays the URI.
     */
    String display(ResourceIdentifier resource) {
        String text = resource.uri().toString();
        String prefix = uri.toString();
        return text.startsWith(prefix) ? text.substring(prefix.length()) : text;
    }
}
