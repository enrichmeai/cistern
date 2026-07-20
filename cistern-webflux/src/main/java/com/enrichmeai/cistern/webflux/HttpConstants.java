package com.enrichmeai.cistern.webflux;

import com.enrichmeai.cistern.core.rdf.N3Patch;
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

    /**
     * N3 Patch documents (Solid Protocol §5.3.1), served by T2.7's {@code PATCH}. Taken from
     * core's patch engine rather than spelled again here: the media type this server advertises
     * in {@code Accept-Patch} and the one {@link N3Patch#parse} will actually accept are one
     * fact, and a second literal is a second thing to get wrong.
     */
    static final String TEXT_N3 = N3Patch.MEDIA_TYPE;

    /**
     * RFC 5023 §9.7 — the client's name hint on a {@code POST}, adopted by LDP §5.2.3.10. Not a
     * field {@link HttpHeaders} names, and never spelled at a call site; see
     * {@code com.enrichmeai.cistern.core.ldp.Slug} for what is done with the value.
     */
    static final String SLUG = "Slug";

    /**
     * WAC §5 — the access modes the current agent holds on the target resource. Emitted by
     * cistern-wac from T3.x; named here already because T2.8 must list it under
     * {@code Access-Control-Expose-Headers}, and a browser app that cannot read it cannot tell
     * a user what they may do. Declared once so the CORS list and the eventual emitter cannot
     * spell it differently.
     */
    static final String WAC_ALLOW = "WAC-Allow";

    /**
     * RFC 9449 §7.1 — the DPoP proof JWT a Solid-OIDC client sends alongside its bound access
     * token. Read by cistern-auth from T4.x; named here for the same reason as
     * {@link #WAC_ALLOW}: T2.8 must allow it through CORS preflight or no browser-based Solid
     * client can authenticate at all.
     */
    static final String DPOP = "DPoP";

    /**
     * One {@code Link} field value (RFC 8288 §3): {@code <target>; rel="relation"}. The target
     * always comes from a vocabulary constant class or a resolved resource URI, never from a
     * literal, and the relation from {@link LinkRelation} — the same constants the request-side
     * parser matches against, so what Cistern emits and what it reads cannot drift.
     *
     * <p>The relation is quoted even where it is a bare token, which RFC 8288's ABNF permits
     * either way ({@code link-param = token BWS [ "=" BWS ( token / quoted-string ) ]}). An
     * extension relation type is a URI containing {@code :} and {@code /}, neither of which is a
     * {@code tchar}, so it <em>must</em> be quoted; quoting uniformly means the one form is
     * always correct rather than depending on which row is being rendered.
     */
    static final String LINK_TEMPLATE = "<%s>; rel=\"%s\"";

    /** {@code Link: <target>; rel="relation"} — the general form. */
    static String link(String target, LinkRelation relation) {
        return LINK_TEMPLATE.formatted(target, relation.value());
    }

    /** {@code Link: <IRI>; rel="type"} for one vocabulary IRI. */
    static String linkType(String iri) {
        return link(iri, LinkRelation.TYPE);
    }

    /** An {@code Allow} field value (RFC 9110 §10.2.1) from typed methods, in the given order. */
    static String allow(List<HttpMethod> methods) {
        return methods.stream()
                .map(HttpMethod::name)
                .collect(Collectors.joining(LIST_SEPARATOR));
    }

    private HttpConstants() {
        // constants only
    }
}
