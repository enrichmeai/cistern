package com.enrichmeai.cistern.webflux;

import com.enrichmeai.cistern.core.ResourceIdentifier;
import com.enrichmeai.cistern.core.vocab.Pim;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.vocabulary.RDF;
import org.springframework.stereotype.Component;

import java.net.URI;

/**
 * The storage description resource of Solid Protocol §4.1 — where it lives, what it says, and
 * the {@code Link} that points at it.
 *
 * <h2>The three requirements this class holds together</h2>
 * §4.1 states them separately, but they are one fact seen from three sides, so they are derived
 * from one field here rather than written down three times:
 *
 * <ol>
 *   <li>"Servers MUST advertise the storage resource by including the HTTP {@code Link} header
 *       field with {@code rel="type"} targeting {@code http://www.w3.org/ns/pim/space#Storage}
 *       when responding to storage's request URI." — {@link ResourceKind#STORAGE_ROOT} carries
 *       {@link Pim#STORAGE} in its type links, because that response is per-resource and the
 *       table is where a resource's advertisement lives.</li>
 *   <li>"Servers MUST include the {@code Link} header field with
 *       {@code rel="http://www.w3.org/ns/solid/terms#storageDescription"} targeting the URI of
 *       the storage description resource in the response of HTTP {@code GET}, {@code HEAD} and
 *       {@code OPTIONS} requests targeting a resource in a storage." — {@link #linkValue()},
 *       emitted by the read and {@code OPTIONS} handlers on every resource. It is not in
 *       {@link ResourceKind} precisely because it does not vary by kind: it is a property of the
 *       storage, identical on a container, a document and a binary alike.</li>
 *   <li>"Servers MUST include statements about the storage as part of the storage description
 *       resource", where "Storage description statements include the properties: {@code rdf:type}
 *       — A class whose URI is {@code http://www.w3.org/ns/pim/space#Storage}." — {@link #graph()}.</li>
 * </ol>
 *
 * <p>Requirements 1 and 3 are the same assertion in two encodings: the root <em>is</em> a
 * {@code pim:Storage}, said once in a header and once in a graph. Both read {@link Pim#STORAGE},
 * so a client that discovers the storage by walking up the path hierarchy and a client that reads
 * the description document cannot be told different things.
 *
 * <h2>Where the description lives</h2>
 * The specification fixes the {@code Link} relation but deliberately not the URI — it says only
 * "the URI of the storage description resource", leaving the location to the server, which is
 * why the relation exists at all. Cistern serves it at {@value #WELL_KNOWN_PATH}, relative to
 * {@code cistern.base-url}, matching the Community Solid Server's observed wire behaviour so that
 * tooling written against the ecosystem's de facto location finds it without following the link.
 *
 * <p>Note that {@code solid} is <em>not</em> in IANA's well-known URI registry (RFC 8615 §3),
 * so this is convention rather than registration. Nothing depends on the spelling: every
 * response in the storage carries the link, so a client that follows it — the behaviour §4.1
 * actually specifies — is unaffected if the location ever moves.
 *
 * <h2>Why the base URL and not the request</h2>
 * A storage's identity must not depend on how a request happened to arrive, for exactly the
 * reason {@link CisternProperties#baseUrl()} exists: in Solid the identifier is the HTTP URI, so
 * a forged {@code Host} that could move the storage root would move every subject a client reads
 * back. The root and the description URI are therefore computed once, at startup, from
 * configuration.
 */
@Component
public class StorageDescription {

    /**
     * The path the storage description resource is served from, relative to
     * {@code cistern.base-url}. Also the route predicate in
     * {@code CisternWebFluxConfiguration} and the {@code Link} target in {@link #linkValue()} —
     * one constant, so the resource cannot be advertised at a URI it is not served from.
     */
    static final String WELL_KNOWN_PATH = "/.well-known/solid";

    /** The storage root: the base URL's own container (Solid Protocol §4.1). */
    private final ResourceIdentifier storageRoot;

    /** Where {@link #WELL_KNOWN_PATH} resolves to, absolute. */
    private final URI descriptionUri;

    /** The ready-to-emit {@code Link} field value; constant for the life of the server. */
    private final String linkValue;

    public StorageDescription(CisternProperties properties) {
        this.storageRoot = new ResourceIdentifier(URI.create(properties.baseUrl() + "/"));
        this.descriptionUri = URI.create(properties.baseUrl() + WELL_KNOWN_PATH);
        this.linkValue = HttpConstants.link(descriptionUri.toString(),
                LinkRelation.STORAGE_DESCRIPTION);
    }

    /**
     * The storage root container — the resource {@link #graph()} makes its statements about.
     * A {@link ResourceIdentifier}, so the invariant that it is a container URI ending in
     * {@code /} is enforced by the type rather than by this class remembering to append one.
     */
    ResourceIdentifier storageRoot() {
        return storageRoot;
    }

    /** The absolute URI of the storage description resource. */
    URI descriptionUri() {
        return descriptionUri;
    }

    /**
     * The {@code Link} field value every {@code GET}, {@code HEAD} and {@code OPTIONS} response
     * in this storage carries (Solid Protocol §4.1).
     *
     * <p>It is <em>added</em> to a response's {@code Link} field, never set: RFC 8288 §3 makes
     * {@code Link} a list field, and a resource already advertises its LDP interaction model
     * through {@code rel="type"} links from {@link ResourceKind}. Replacing them would trade one
     * mandatory advertisement for another.
     */
    String linkValue() {
        return linkValue;
    }

    /**
     * The description graph: {@code <storage root> a pim:Storage}.
     *
     * <p>That single statement is the whole of what §4.1 requires — the storage description
     * statements it enumerates consist of {@code rdf:type} alone — and Cistern asserts nothing
     * beyond it, because every further property a server might add (notification channels,
     * quota, owner) is defined by a specification Cistern does not yet implement. A description
     * that claimed them would be advertising capabilities that do not exist.
     *
     * <p>A fresh model per call rather than a shared constant: Jena models are mutable and not
     * thread-safe, and a serializer handed a shared one from many request threads would be a
     * data race for no gain — the graph is three terms.
     */
    Model graph() {
        Model model = ModelFactory.createDefaultModel();
        model.add(model.createResource(storageRoot.uri().toString()), RDF.type, Pim.STORAGE);
        return model;
    }
}
