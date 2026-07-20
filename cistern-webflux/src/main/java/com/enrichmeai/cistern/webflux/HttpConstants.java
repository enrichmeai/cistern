package com.enrichmeai.cistern.webflux;

/**
 * Header field names and media types Spring's {@code HttpHeaders} does not already name.
 *
 * <p>{@code Accept-Post} is defined by LDP 1.0 §7.1.2 and {@code Accept-Put} by the Solid
 * Protocol (§5.2, alongside {@code Accept-Patch} of RFC 5789 §3.1).
 */
final class HttpConstants {

    /** LDP 1.0 §7.1.2 — media types acceptable in a {@code POST} to this container. */
    static final String ACCEPT_POST = "Accept-Post";

    /** Solid Protocol §5.2 — media types acceptable in a {@code PUT} to this resource. */
    static final String ACCEPT_PUT = "Accept-Put";

    /** N3 Patch documents (Solid Protocol §5.3.2), wired to {@code PATCH} by T2.7. */
    static final String TEXT_N3 = "text/n3";

    private HttpConstants() {
        // constants only
    }
}
