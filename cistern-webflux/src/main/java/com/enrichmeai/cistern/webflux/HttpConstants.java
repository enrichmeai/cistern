package com.enrichmeai.cistern.webflux;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Header field names, values and value templates that Spring's {@link HttpHeaders} does not
 * already name. Everything Cistern emits is named here or taken from {@code HttpHeaders} —
 * no header name or structured header value is spelled out at a call site.
 */
final class HttpConstants {

    /** Separator for the comma-delimited list values of RFC 9110 §5.6.1 ("#rule"). */
    static final String LIST_SEPARATOR = ", ";

    /** LDP 1.0 §7.1.2 — media types acceptable in a {@code POST} to this container. */
    static final String ACCEPT_POST = "Accept-Post";

    /** Solid Protocol §5.2 — media types acceptable in a {@code PUT} to this resource. */
    static final String ACCEPT_PUT = "Accept-Put";

    /** N3 Patch documents (Solid Protocol §5.3.2), wired to {@code PATCH} by T2.7. */
    static final String TEXT_N3 = "text/n3";

    /**
     * A {@code Link} field value typing the resource (RFC 8288 §3): {@code <IRI>; rel="type"}.
     * The IRI always comes from a vocabulary constant class, never from a literal.
     */
    static final String LINK_TYPE_TEMPLATE = "<%s>; rel=\"type\"";

    /** {@code Link: <IRI>; rel="type"} for one vocabulary IRI. */
    static String linkType(String iri) {
        return LINK_TYPE_TEMPLATE.formatted(iri);
    }

    /** An {@code Allow} field value (RFC 9110 §10.2.1) from typed methods, in the given order. */
    static String allow(HttpMethod... methods) {
        return List.of(methods).stream()
                .map(HttpMethod::name)
                .collect(Collectors.joining(LIST_SEPARATOR));
    }

    private HttpConstants() {
        // constants only
    }
}
