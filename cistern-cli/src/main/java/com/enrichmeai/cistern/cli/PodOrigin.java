package com.enrichmeai.cistern.cli;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * The scheme, host and port of an http(s) URI, compared the way a web origin is (RFC 6454 §4):
 * scheme and host without regard to case, and a default port made explicit, so
 * {@code http://Pod.example} and {@code http://pod.example:80} are the same origin.
 *
 * <p>Two names for one machine are two origins here, on purpose: {@code localhost} and
 * {@code 127.0.0.1} reach the same server, but the server names its resources under exactly one
 * of them, and an ACL rule matches an IRI, not a machine.
 */
record PodOrigin(String scheme, String host, int port) {

    private static final String HTTP = "http";
    private static final String HTTPS = "https";
    private static final int HTTP_PORT = 80;
    private static final int HTTPS_PORT = 443;
    private static final int NO_PORT = -1;
    private static final String SCHEME_SEPARATOR = "://";
    private static final String PORT_SEPARATOR = ":";
    private static final String NO_PORT_TEXT = "";

    PodOrigin {
        Objects.requireNonNull(scheme, "scheme");
        Objects.requireNonNull(host, "host");
    }

    /** The origin of {@code uri}, or empty if it is not an absolute http(s) URI with a host. */
    static Optional<PodOrigin> of(URI uri) {
        Objects.requireNonNull(uri, "uri");
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null) {
            return Optional.empty();
        }
        scheme = scheme.toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        if (HTTP.equals(scheme)) {
            port = port == NO_PORT ? HTTP_PORT : port;
        } else if (HTTPS.equals(scheme)) {
            port = port == NO_PORT ? HTTPS_PORT : port;
        } else {
            return Optional.empty();
        }
        return Optional.of(new PodOrigin(scheme, host.toLowerCase(Locale.ROOT), port));
    }

    /** {@code scheme://host:port}, the port left out when it is the scheme's default. */
    @Override
    public String toString() {
        boolean defaultPort = (HTTP.equals(scheme) && port == HTTP_PORT) || (HTTPS.equals(scheme) && port == HTTPS_PORT);
        return scheme + SCHEME_SEPARATOR + host + (defaultPort ? NO_PORT_TEXT : PORT_SEPARATOR + port);
    }
}
