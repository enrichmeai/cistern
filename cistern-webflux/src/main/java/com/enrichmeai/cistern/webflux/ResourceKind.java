package com.enrichmeai.cistern.webflux;

import com.enrichmeai.cistern.core.ldp.Ldp;
import com.enrichmeai.cistern.core.ldp.ResourceView;
import org.apache.jena.rdf.model.Resource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

import java.util.List;

/**
 * The three kinds of resource Cistern serves, and the interface metadata each advertises.
 * One table, consulted by every method handler, so {@code Allow} and the {@code Accept-*}
 * headers cannot drift apart per endpoint — Solid Protocol §5.2 requires exactly that
 * consistency: the {@code Accept-Patch}, {@code Accept-Post} and {@code Accept-Put} fields
 * must "correspond to acceptable HTTP methods listed in {@code Allow} header field value".
 *
 * <h2>Where the values come from</h2>
 * <ul>
 *   <li><b>Allow</b> — Solid Protocol §5.2: "Servers MUST indicate the HTTP methods
 *       supported by the target resource by generating an {@code Allow} header field in
 *       successful responses", and "Servers MUST support the HTTP {@code GET}, {@code HEAD}
 *       and {@code OPTIONS} methods". The write methods differ by kind: only a container
 *       accepts {@code POST} (it is the only resource that can mint children), and only an
 *       RDF source accepts {@code PATCH} (N3 Patch operates on a graph, Solid Protocol
 *       §5.3.2 — there is nothing to patch in a JPEG).</li>
 *   <li><b>Link {@code rel="type"}</b> — LDP 1.0 §4.2.1.4: servers exposing LDPRs "MUST
 *       advertise their LDP support by exposing a HTTP {@code Link} header with a target URI
 *       of {@code http://www.w3.org/ns/ldp#Resource}, and a link relation type of
 *       {@code type}", and §5.2.1.4 adds the container's own type. Solid Protocol §4.2 fixes
 *       which container type that is: Solid containers correspond to LDP Basic Container.
 *       Advertised on every kind, binary resources included — an LDPR need not be an RDF
 *       source. The IRIs come from the {@link Ldp} vocabulary class, never from literals.</li>
 *   <li><b>Accept-Put</b> — a container's stored representation is a graph, so only the
 *       {@link RdfSerialization} media types of Solid Protocol §5.5 may replace it. A
 *       document may hold anything, so any media type is accepted.</li>
 *   <li><b>Accept-Post</b> — containers only, matching {@code POST} in {@code Allow}; a
 *       posted child may be of any media type.</li>
 *   <li><b>Accept-Patch</b> — {@code text/n3}, the N3 Patch content type of Solid Protocol
 *       §5.3.2, and only where {@code PATCH} is in {@code Allow}.</li>
 * </ul>
 *
 * <p>The values describe the resource kinds as Phase 2 defines them, so the write methods
 * appear before their own tickets land (T2.2 {@code PUT}, T2.3 {@code POST}, T2.4
 * {@code DELETE}, T2.7 {@code PATCH}, T2.8 {@code OPTIONS}). That is deliberate: {@code Allow}
 * describes the resource, and splitting the table across five tickets would guarantee it
 * drifts. Known nuance for T2.4: the storage root container refuses {@code DELETE} (405),
 * which this kind-level table cannot express.
 */
enum ResourceKind {

    /** An LDP Basic Container (Solid Protocol §3.1: a path ending in a slash). */
    CONTAINER(
            HttpConstants.allow(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS,
                    HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE),
            RdfSerialization.mediaTypeList(),
            MediaType.ALL_VALUE,
            HttpConstants.TEXT_N3,
            List.of(Ldp.RESOURCE, Ldp.BASIC_CONTAINER)),

    /** A document stored as one of the RDF media types: patchable, not postable to. */
    RDF_DOCUMENT(
            HttpConstants.allow(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS,
                    HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE),
            MediaType.ALL_VALUE,
            null,
            HttpConstants.TEXT_N3,
            List.of(Ldp.RESOURCE)),

    /** A binary document: replaceable and deletable, but there is no graph to patch. */
    NON_RDF_DOCUMENT(
            HttpConstants.allow(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS,
                    HttpMethod.PUT, HttpMethod.DELETE),
            MediaType.ALL_VALUE,
            null,
            null,
            List.of(Ldp.RESOURCE));

    private final String allow;
    private final String acceptPut;
    private final String acceptPost;
    private final String acceptPatch;
    private final List<String> linkTypeValues;

    ResourceKind(String allow, String acceptPut, String acceptPost, String acceptPatch,
                 List<Resource> types) {
        this.allow = allow;
        this.acceptPut = acceptPut;
        this.acceptPost = acceptPost;
        this.acceptPatch = acceptPatch;
        this.linkTypeValues = types.stream()
                .map(type -> HttpConstants.linkType(type.getURI()))
                .toList();
    }

    static ResourceKind of(ResourceView view) {
        if (view.container()) {
            return CONTAINER;
        }
        return view instanceof ResourceView.Rdf ? RDF_DOCUMENT : NON_RDF_DOCUMENT;
    }

    String allow() {
        return allow;
    }

    /** Null where the corresponding method is absent from {@link #allow()}. */
    String acceptPut() {
        return acceptPut;
    }

    String acceptPost() {
        return acceptPost;
    }

    String acceptPatch() {
        return acceptPatch;
    }

    /** Ready-to-emit {@code Link} field values typing this resource. */
    List<String> linkTypeValues() {
        return linkTypeValues;
    }
}
