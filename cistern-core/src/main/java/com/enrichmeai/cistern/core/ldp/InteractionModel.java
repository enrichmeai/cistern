package com.enrichmeai.cistern.core.ldp;

import com.enrichmeai.cistern.core.CisternException;
import com.enrichmeai.cistern.core.CoreMessage;
import org.apache.jena.rdf.model.Resource;

import java.util.Collection;
import java.util.Optional;

/**
 * Which kind of resource a {@code POST} asks the server to create — LDP's "requested interaction
 * model" (LDP 1.0 §5.2.3.4).
 *
 * <p>Two constants, because the spec names exactly two interaction models and describes each by
 * how the server must behave afterwards: "If the request header specifies a <b>LDPR</b>
 * interaction model, then the server MUST handle subsequent requests to the newly created
 * resource's URI as if it is a LDPR ... If the request header specifies a <b>LDPC</b> interaction
 * model, then the server MUST handle subsequent requests to the newly created resource's URI as
 * if it is a LDPC." A closed set, so it is an enum rather than a {@code boolean asContainer} at
 * four call sites (ground rule 7); the model decides the shape of the minted URI, since Solid
 * Protocol §3.1 makes "paths ending with a slash denote a container resource".
 *
 * <h2>Honouring the request, or failing it</h2>
 * §5.2.3.4 opens with a MUST that has teeth: "LDP servers that successfully create a resource
 * from a RDF representation in the request entity body MUST honor the client's requested
 * interaction model(s). <b>If any requested interaction model cannot be honored, the server MUST
 * fail the request.</b>" So a type this server cannot serve may not be quietly downgraded to one
 * it can — {@link #forTypeIris} refuses instead (architect ruling, PR #68).
 *
 * <p><b>The MUST is narrower than "any unrecognised Link", and deliberately so.</b> The same
 * paragraph ends: "This specification does not constrain the server's behavior in other cases."
 * The constrained cases are the LDP interaction models; a {@code rel="type"} link naming anything
 * outside the LDP namespace — a domain vocabulary class a client attaches to its own data — is
 * one of the other cases, and creating a document for it is permitted rather than an oversight.
 * The asymmetry between the two halves of {@link #forTypeIris} is exactly that sentence.
 *
 * <h2>The table</h2>
 * <table border="1">
 *   <caption>{@code Link: <IRI>; rel="type"} on a POST</caption>
 *   <tr><th>Requested type</th><th>Result</th><th>Why</th></tr>
 *   <tr><td>{@code ldp:BasicContainer}</td><td>container</td>
 *       <td>Solid Protocol §4.2 — Solid containers correspond to LDP Basic Container</td></tr>
 *   <tr><td>{@code ldp:Container}</td><td>container</td>
 *       <td>superclass; Solid has no other container kind, so a basic container honours it</td></tr>
 *   <tr><td>{@code ldp:Resource}</td><td>document</td><td>the LDPR interaction model</td></tr>
 *   <tr><td>{@code ldp:RDFSource}</td><td>document</td><td>an LDPR whose state is triples</td></tr>
 *   <tr><td>{@code ldp:NonRDFSource}</td><td>document</td><td>see below</td></tr>
 *   <tr><td>{@code ldp:DirectContainer}</td><td><b>refused, 400</b></td>
 *       <td>membership semantics Cistern does not implement</td></tr>
 *   <tr><td>{@code ldp:IndirectContainer}</td><td><b>refused, 400</b></td><td>likewise</td></tr>
 *   <tr><td>any non-LDP IRI</td><td>document</td>
 *       <td>"does not constrain the server's behavior in other cases"</td></tr>
 *   <tr><td>absent</td><td>document</td><td>nothing was requested</td></tr>
 * </table>
 *
 * <h2>Why {@code ldp:NonRDFSource} is created rather than refused</h2>
 * Because it is an LDPR, and Cistern honours the LDPR interaction model for it in full. §5.2.3.4
 * constrains the LDPR and LDPC models only, and an LDP-NR <em>is</em> an LDPR — creating a
 * document means subsequent requests to it are handled as an LDPR, which is precisely what the
 * MUST asks for. What the resource's state actually is comes from the media type, not from this
 * header: §5.2.3.6, "LDP servers SHOULD use the Content-Type request header to determine the
 * request representation's format", and Cistern stores and serves a non-RDF body verbatim
 * (§5.2.3.3 permits exactly that). A client that sends a binary body under this link therefore
 * gets the LDP-NR it asked for, and refusing the link would fail a request the server honours.
 *
 * <h2>Why {@code ldp:DirectContainer} and {@code ldp:IndirectContainer} are refused</h2>
 * Because the honouring clause cannot be met. Both carry membership semantics —
 * {@code ldp:membershipResource}, {@code ldp:hasMemberRelation},
 * {@code ldp:insertedContentRelation} — under which a POST must add <em>membership</em> triples to
 * a resource that need not even be the container. Solid Protocol §4.2 requires Basic Containers
 * and Cistern implements no membership machinery, so a container created here would not behave as
 * the client asked on any subsequent request. Creating a basic container and calling it done is
 * the silent downgrade §5.2.3.4 forbids.
 *
 * <h2>Why the refusal is a 400</h2>
 * <ul>
 *   <li><b>4xx, not 5xx.</b> LDP §4.2.1.6 frames exactly this situation as client-side: servers
 *       must publish creation constraints "to all responses to requests that fail due to violation
 *       of those constraints. For example, a server that refuses resource creation requests via
 *       HTTP PUT, POST, or PATCH would return this Link header on its <b>4xx</b> responses".</li>
 *   <li><b>Not 501.</b> RFC 9110 §15.6.2 scopes it to method support — "the appropriate response
 *       when the server does not recognize the request method and is not capable of supporting it
 *       for any resource" — and it is a 5xx, which asserts a server fault. Cistern recognises
 *       {@code POST} and serves it; what it will not do is one thing this request asked for.</li>
 *   <li><b>Not 422.</b> RFC 4918 §11.2 is about a request <em>entity</em> the server understood but
 *       could not process. The body here is fine; the unmeetable request is in a header.</li>
 *   <li><b>Not 409.</b> Nothing about the resource's state conflicts — the request would fail
 *       identically against an empty pod.</li>
 *   <li><b>400.</b> RFC 9110 §15.5.1: "the server cannot or will not process the request due to
 *       something that is perceived to be a client error". That is this case exactly, and it
 *       needs no new {@link CisternException} subtype, so the error mapper's coverage test stands
 *       unmodified.</li>
 * </ul>
 */
public enum InteractionModel {

    /**
     * LDP's LDPR interaction model — a document. The default: §5.2.3.4 constrains only models the
     * client actually requested, so a {@code POST} with no {@code Link} creates a document, whose
     * URI has no trailing slash and therefore is not a container under Solid Protocol §3.1.
     */
    RESOURCE(false),

    /**
     * LDP's LDPC interaction model, as Solid narrows it — an LDP Basic Container (Solid Protocol
     * §4.2). Its minted URI ends with a slash.
     */
    BASIC_CONTAINER(true);

    /**
     * The LDP interaction models Cistern cannot serve, so that each can be refused by name. A
     * closed set, hence an enum, and the IRIs come from the {@link Ldp} vocabulary class rather
     * than from literals (ground rule 7) — which also makes a typo here a compile error.
     */
    private enum Unhonourable {

        DIRECT_CONTAINER(Ldp.DIRECT_CONTAINER),
        INDIRECT_CONTAINER(Ldp.INDIRECT_CONTAINER);

        private final String iri;

        Unhonourable(Resource type) {
            this.iri = type.getURI();
        }

        /** Whether the requested type is one this server must fail the request over. */
        static boolean names(String typeIri) {
            for (Unhonourable model : values()) {
                if (model.iri.equals(typeIri)) {
                    return true;
                }
            }
            return false;
        }
    }

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
     * <p><b>Refusal is checked across every requested type before one is chosen</b>, because
     * §5.2.3.4 fails the request if <em>any</em> requested model cannot be honoured — so
     * {@code Link: <ldp#BasicContainer>; rel="type", <ldp#DirectContainer>; rel="type"} is refused
     * rather than half-honoured.
     *
     * <p><b>Otherwise the most specific request wins.</b> A client that sends both
     * {@code <ldp#Resource>; rel="type"} and {@code <ldp#BasicContainer>; rel="type"} — a common
     * pairing, since every container is also a resource — is asking for a container, and a
     * document would not honour that. So any IRI naming a container decides the answer, and
     * {@link #RESOURCE} is what is left when none does.
     *
     * @param typeIris the target IRIs of the request's {@code rel="type"} links, in any order
     * @return the requested model; {@link #RESOURCE} when the collection is empty or names only
     *         types this server does not create as containers
     * @throws CisternException.BadInput if any requested type is an LDP interaction model Cistern
     *                                   cannot honour (→ 400 via the single error mapper)
     */
    public static InteractionModel forTypeIris(Collection<String> typeIris) {
        for (String typeIri : typeIris) {
            if (Unhonourable.names(typeIri)) {
                throw new CisternException.BadInput(
                        CoreMessage.INTERACTION_MODEL_UNHONOURABLE.format(typeIri));
            }
        }
        return typeIris.stream()
                .map(InteractionModel::forTypeIri)
                .flatMap(Optional::stream)
                .filter(InteractionModel::container)
                .findFirst()
                .orElse(RESOURCE);
    }

    /**
     * One type IRI's model, or empty for an IRI that names no interaction model this server
     * distinguishes — including every IRI outside the LDP namespace, which §5.2.3.4 leaves
     * unconstrained.
     *
     * <p>{@code ldp:Container} maps to {@link #BASIC_CONTAINER} alongside
     * {@code ldp:BasicContainer}: it is the latter's superclass, Solid has no other container kind
     * (§4.2), and creating a basic container is the only way to honour a request for the container
     * interaction model. {@code ldp:Resource}, {@code ldp:RDFSource} and {@code ldp:NonRDFSource}
     * all map to {@link #RESOURCE} — they are the LDPR interaction model in the class hierarchy's
     * three spellings. The two unhonourable container types never reach this method as a decision:
     * {@link #forTypeIris} has already refused them.
     */
    public static Optional<InteractionModel> forTypeIri(String typeIri) {
        if (Ldp.BASIC_CONTAINER.getURI().equals(typeIri) || Ldp.CONTAINER.getURI().equals(typeIri)) {
            return Optional.of(BASIC_CONTAINER);
        }
        if (Ldp.RESOURCE.getURI().equals(typeIri)
                || Ldp.RDF_SOURCE.getURI().equals(typeIri)
                || Ldp.NON_RDF_SOURCE.getURI().equals(typeIri)) {
            return Optional.of(RESOURCE);
        }
        return Optional.empty();
    }
}
