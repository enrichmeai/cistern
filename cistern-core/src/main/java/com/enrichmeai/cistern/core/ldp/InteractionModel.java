package com.enrichmeai.cistern.core.ldp;

import java.util.Collection;
import java.util.Optional;

/**
 * Which kind of resource a {@code POST} asks the server to create — LDP's "requested
 * interaction model" (LDP 1.0 §5.2.3.4: "LDP servers that successfully create a resource from a
 * RDF representation in the request entity body MUST honor the client's requested interaction
 * model(s) ... If the request header specifies a LDPC interaction model, then the server MUST
 * handle subsequent requests to the newly created resource's URI as if it is a LDPC").
 *
 * <p>A closed set, so it is an enum rather than a boolean or a type IRI carried around as a
 * string: the model decides the shape of the minted URI (Solid Protocol §3.1 — "paths ending
 * with a slash denote a container resource"), and a two-valued domain concept spelled as
 * {@code boolean asContainer} at four call sites is exactly what ground rule 7 forbids.
 *
 * <p>The client states the model with a {@code Link} header typing the resource-to-be
 * ({@code Link: <http://www.w3.org/ns/ldp#BasicContainer>; rel="type"}); parsing that header is
 * the HTTP layer's job, and mapping the IRIs it yields onto a model is this enum's.
 */
public enum InteractionModel {

    /**
     * A plain LDP resource — a document. The default: LDP §5.2.3.4 only constrains models the
     * client actually requested, so a {@code POST} with no {@code Link} (or one naming a type
     * this server does not treat as a container) creates a document, whose URI has no trailing
     * slash and therefore is not a container under Solid Protocol §3.1.
     */
    RESOURCE(false),

    /**
     * An LDP Basic Container — the only container kind Solid has (Solid Protocol §4.2: "The
     * representation and behaviour of containers in Solid corresponds to LDP Basic Container").
     * Its minted URI ends with a slash.
     */
    BASIC_CONTAINER(true);

    private final boolean container;

    InteractionModel(boolean container) {
        this.container = container;
    }

    /** True iff a resource created under this model is a container (a URI ending in {@code /}). */
    public boolean container() {
        return container;
    }

    /**
     * The model requested by a set of {@code Link rel="type"} target IRIs, defaulting to
     * {@link #RESOURCE}.
     *
     * <p><b>The most specific request wins.</b> A client that sends both
     * {@code <ldp#Resource>; rel="type"} and {@code <ldp#BasicContainer>; rel="type"} — a
     * common pairing, since every container is also a resource — is asking for a container, and
     * a document would not honour that. So any IRI naming a container decides the answer, and
     * {@link #RESOURCE} is what is left when none does.
     *
     * @param typeIris the target IRIs of the request's {@code rel="type"} links, in any order
     * @return the requested model; {@link #RESOURCE} when the collection is empty or names only
     *         types this server does not create as containers
     */
    public static InteractionModel forTypeIris(Collection<String> typeIris) {
        return typeIris.stream()
                .map(InteractionModel::forTypeIri)
                .flatMap(Optional::stream)
                .filter(InteractionModel::container)
                .findFirst()
                .orElse(RESOURCE);
    }

    /**
     * One type IRI's model, or empty for an IRI this server does not recognise as naming an
     * interaction model at all.
     *
     * <p>{@code ldp:Container} maps to {@link #BASIC_CONTAINER} alongside
     * {@code ldp:BasicContainer}: it is the latter's superclass, Solid has no other container
     * kind (§4.2), and creating a basic container is the only way to honour a request for the
     * container interaction model. {@code ldp:Resource} maps to {@link #RESOURCE}; everything
     * else — {@code ldp:RDFSource}, {@code ldp:NonRDFSource}, a domain type a client happened to
     * link — is not an interaction model this server distinguishes, and so does not change what
     * gets created.
     */
    public static Optional<InteractionModel> forTypeIri(String typeIri) {
        if (Ldp.BASIC_CONTAINER.getURI().equals(typeIri) || Ldp.CONTAINER.getURI().equals(typeIri)) {
            return Optional.of(BASIC_CONTAINER);
        }
        if (Ldp.RESOURCE.getURI().equals(typeIri)) {
            return Optional.of(RESOURCE);
        }
        return Optional.empty();
    }
}
