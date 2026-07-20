package com.enrichmeai.cistern.core.ldp;

import com.enrichmeai.cistern.core.CoreMessage;
import com.enrichmeai.cistern.core.ResourceIdentifier;

/**
 * What kind of resource a URI names, as a closed set (ground rule 7 — "resource kinds" are
 * enums, never bare strings). This is the <em>semantic</em> fact: it says what the resource
 * <em>is</em> in LDP/Solid terms, and says nothing about HTTP.
 *
 * <h2>Why this lives in core (T2.7)</h2>
 * Until {@code PATCH} existed, the HTTP layer could infer a resource's kind from the request
 * path, because the only distinction that mattered to a response was the container/document
 * split that Solid Protocol §3.1 encodes in the trailing slash. {@code PATCH} breaks that: an
 * RDF document is patchable and a binary document is not (§5.3.1 — "Servers MUST accept a
 * {@code PATCH} request with an N3 Patch body when the target of the request is an RDF
 * document"), and <em>nothing in the URI says which one a path names</em>. Only the stored
 * media type does, and that is core's fact.
 *
 * <p>So the kind travels out of core rather than being guessed at the edge:
 * <ul>
 *   <li>On the <b>success</b> path it rides on the {@link ResourceView} the front-end already
 *       holds — {@link #of(ResourceView)}.</li>
 *   <li>On the <b>405 path</b> there is no view to consult, so the domain signal carries it:
 *       {@link com.enrichmeai.cistern.core.CisternException.MethodNotAllowed#kind()}. That is
 *       what lets a refusal advertise an {@code Allow} that is exactly true of the resource
 *       refusing, which RFC 9110 §15.5.6 requires and Solid Protocol §5.2 requires to agree
 *       with the {@code Allow} on a successful read of the same resource.</li>
 * </ul>
 *
 * <p>The HTTP interface each kind advertises — its {@code Allow}, {@code Accept-Patch},
 * {@code Accept-Post}, {@code Accept-Put} and {@code Link rel="type"} values — is
 * cistern-webflux's {@code ResourceKind}, which holds exactly one row per constant here. The
 * split is the module boundary doing its job: core knows what a resource is, the HTTP layer
 * knows what that means on the wire, and neither restates the other.
 */
public enum LdpKind {

    /**
     * The storage's root container: a {@link #CONTAINER} in every respect except that Solid
     * Protocol §5.4 forbids deleting it. A kind of its own rather than a flag, so that the
     * one place which knows the difference is the one place that decides it.
     */
    STORAGE_ROOT,

    /** An LDP Basic Container that is not the storage root (Solid Protocol §3.1, §4.2). */
    CONTAINER,

    /**
     * A document whose stored representation is RDF ({@code text/turtle} or
     * {@code application/ld+json}). Patchable: §5.3.1's {@code PATCH} requirement is scoped to
     * exactly this kind and to containers.
     */
    RDF_DOCUMENT,

    /**
     * A binary document: any other stored media type. There is no graph to patch, so
     * {@code PATCH} is not offered on it and not accepted for it.
     */
    NON_RDF_DOCUMENT;

    /**
     * The kind of the resource a read resolved. Total over {@link ResourceView}: a container
     * splits on whether it is the storage root, and a document on whether the read path
     * classified it as an RDF source.
     *
     * <p>{@link ResourceView.Rdf} versus {@link ResourceView.NonRdf} is the test for a document
     * rather than a media-type comparison, because that classification <em>is</em> core's answer
     * to "is this a graph or a byte stream?" — re-deriving it here would create a second place
     * for the answer to be decided.
     */
    public static LdpKind of(ResourceView view) {
        if (view.container()) {
            return ofContainer(view.identifier());
        }
        return view instanceof ResourceView.Rdf ? RDF_DOCUMENT : NON_RDF_DOCUMENT;
    }

    /**
     * The kind of a container named by identifier alone — what a caller has when the resource
     * it must describe is not the one it read (a {@code POST} response describes the container,
     * not the child it created) or when no read happened at all (a {@code DELETE} of the
     * storage root is refused before the store is touched).
     *
     * @throws IllegalArgumentException if {@code container} is not a container identifier, since
     *                                  the kind of a document cannot be known from its URI
     */
    public static LdpKind ofContainer(ResourceIdentifier container) {
        if (!container.isContainer()) {
            throw new IllegalArgumentException(
                    CoreMessage.KIND_REQUIRES_CONTAINER_IDENTIFIER.format(container.uri()));
        }
        return container.isStorageRoot() ? STORAGE_ROOT : CONTAINER;
    }
}
