package com.enrichmeai.cistern.webflux;

import com.enrichmeai.cistern.core.ResourceIdentifier;
import com.enrichmeai.cistern.core.ldp.Ldp;
import com.enrichmeai.cistern.core.ldp.LdpKind;
import com.enrichmeai.cistern.core.ldp.ResourceView;
import org.apache.jena.rdf.model.Resource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
 * drifts.
 *
 * <h2>One row per {@link LdpKind} (T2.7)</h2>
 * This enum is the HTTP <em>interface</em> of a resource kind; {@link LdpKind} in cistern-core
 * is the kind itself. Every row names the core constant it describes, and {@link #forKind}
 * is the only way a kind becomes an interface — so the HTTP layer never decides what a
 * resource is, it only looks up what that resource advertises.
 *
 * <p>Before T2.7 the two could be conflated, because the container/document split is visible in
 * the URI (Solid Protocol §3.1). {@code PATCH} ended that: {@link #RDF_DOCUMENT} and
 * {@link #NON_RDF_DOCUMENT} differ by {@code PATCH} and {@code Accept-Patch} alone, and no
 * amount of looking at a path can tell them apart — only the stored media type can, which is
 * core's fact. Hence the kind travels here from core, on the {@code ResourceView} for a
 * successful response and on {@code CisternException.MethodNotAllowed} for a 405.
 *
 * <p>T2.4 resolved the nuance T2.1 parked here — the storage root refuses {@code DELETE} — by
 * making it a kind of its own ({@link #STORAGE_ROOT}) rather than a special case at one call
 * site. Solid Protocol §5.4 requires both halves of that, and requiring them of every response
 * is exactly what this table is for: "Server MUST exclude the {@code DELETE} method in the
 * field value of the {@code Allow} header field, in response to requests to these resources."
 */
public enum ResourceKind {

    /** An LDP Basic Container (Solid Protocol §3.1: a path ending in a slash). */
    CONTAINER(
            LdpKind.CONTAINER,
            List.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS,
                    HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE),
            RdfSerialization.mediaTypeList(),
            MediaType.ALL_VALUE,
            HttpConstants.TEXT_N3,
            List.of(Ldp.RESOURCE, Ldp.BASIC_CONTAINER)),

    /**
     * The storage's root container: a {@link #CONTAINER} in every respect except that it
     * cannot be removed. Solid Protocol §5.4 — "When a {@code DELETE} request targets
     * storage's root container or its associated ACL resource, the server MUST respond with
     * the {@code 405} status code. Server MUST exclude the {@code DELETE} method in the field
     * value of the {@code Allow} header field, in response to requests to these resources."
     *
     * <p>A row rather than a flag, so the 405's {@code Allow} (RFC 9110 §15.5.6 makes it
     * mandatory) and the {@code Allow} on a successful {@code GET} of the root are one value
     * and cannot contradict each other — which is the very inconsistency §5.4's second
     * sentence exists to forbid.
     */
    STORAGE_ROOT(
            LdpKind.STORAGE_ROOT,
            List.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS,
                    HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH),
            RdfSerialization.mediaTypeList(),
            MediaType.ALL_VALUE,
            HttpConstants.TEXT_N3,
            List.of(Ldp.RESOURCE, Ldp.BASIC_CONTAINER)),

    /** A document stored as one of the RDF media types: patchable, not postable to. */
    RDF_DOCUMENT(
            LdpKind.RDF_DOCUMENT,
            List.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS,
                    HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE),
            MediaType.ALL_VALUE,
            null,
            HttpConstants.TEXT_N3,
            List.of(Ldp.RESOURCE)),

    /** A binary document: replaceable and deletable, but there is no graph to patch. */
    NON_RDF_DOCUMENT(
            LdpKind.NON_RDF_DOCUMENT,
            List.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS,
                    HttpMethod.PUT, HttpMethod.DELETE),
            MediaType.ALL_VALUE,
            null,
            null,
            List.of(Ldp.RESOURCE));

    /** One row per {@link LdpKind}, so {@link #forKind} is total and needs no default. */
    private static final Map<LdpKind, ResourceKind> BY_LDP_KIND =
            Arrays.stream(values()).collect(Collectors.toUnmodifiableMap(
                    ResourceKind::ldpKind, kind -> kind));

    private final LdpKind ldpKind;
    private final List<HttpMethod> methods;
    private final String allow;
    private final String acceptPut;
    private final String acceptPost;
    private final String acceptPatch;
    private final List<String> linkTypeValues;

    ResourceKind(LdpKind ldpKind, List<HttpMethod> methods, String acceptPut, String acceptPost,
                 String acceptPatch, List<Resource> types) {
        this.ldpKind = ldpKind;
        this.methods = List.copyOf(methods);
        this.allow = HttpConstants.allow(this.methods);
        this.acceptPut = acceptPut;
        this.acceptPost = acceptPost;
        this.acceptPatch = acceptPatch;
        this.linkTypeValues = types.stream()
                .map(type -> HttpConstants.linkType(type.getURI()))
                .toList();
    }

    /**
     * The interface metadata for a resource whose kind core has already decided — the seam
     * T2.7 introduced so the HTTP layer never re-derives the kind from a URI.
     *
     * <p>Total by construction: {@link #BY_LDP_KIND} is built from the rows themselves, and
     * {@code ResourceKindTest} asserts one row per {@link LdpKind}, so adding a kind in core
     * without giving it an interface here fails the build rather than a request.
     */
    static ResourceKind forKind(LdpKind kind) {
        return BY_LDP_KIND.get(kind);
    }

    static ResourceKind of(ResourceView view) {
        return forKind(LdpKind.of(view));
    }

    /**
     * The kind of a container named by identifier alone — what a {@code POST} response needs,
     * since its target resource is the container while the resource it creates is a different
     * one, so {@link #of(ResourceView)}'s view is the wrong resource to ask.
     *
     * <p>The root is a container whose method set is one method short (Solid Protocol §5.4), so
     * the distinction is drawn here rather than at each header-emitting site.
     */
    static ResourceKind ofContainer(ResourceIdentifier container) {
        return forKind(LdpKind.ofContainer(container));
    }

    /** The core-side fact this row describes the HTTP interface of. */
    LdpKind ldpKind() {
        return ldpKind;
    }

    /**
     * The {@code Allow} field value (RFC 9110 §10.2.1) this kind advertises — derived from
     * {@link #permits}, so what a resource says it supports and what it actually accepts are
     * one fact and cannot contradict each other.
     */
    public String allow() {
        return allow;
    }

    /**
     * Whether this kind supports {@code method} at all — the same set {@link #allow()} is
     * rendered from.
     *
     * <p>Used by T2.5 to implement RFC 9110 §13.2.1: "A server MUST ignore all received
     * preconditions if its response to the same request without those conditions ... would have
     * been a status code other than a 2xx (Successful) or 412 (Precondition Failed)." A method
     * this kind does not support answers 405 (RFC 9110 §15.5.6) whatever the request's
     * {@code If-Match} says, so its preconditions are never evaluated.
     *
     * <p>Driven off the table rather than off a test like "is this the storage root", so the
     * rule is general: it already covers Solid Protocol §5.4's other 405 (the root's ACL
     * resource, #64) and any future kind whose method set is narrower, with no further work and
     * no second place for the two to disagree.
     */
    boolean permits(HttpMethod method) {
        return methods.contains(method);
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
