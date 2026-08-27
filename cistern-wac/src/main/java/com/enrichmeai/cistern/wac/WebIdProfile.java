package com.enrichmeai.cistern.wac;

import com.enrichmeai.cistern.wac.AccessMode;
import com.enrichmeai.cistern.core.Representation;
import com.enrichmeai.cistern.core.ResourceIdentifier;
import com.enrichmeai.cistern.core.rdf.RdfIo;
import com.enrichmeai.cistern.core.vocab.Acl;
import com.enrichmeai.cistern.core.vocab.Foaf;
import com.enrichmeai.cistern.core.vocab.Solid;

import java.net.URI;
import java.util.Objects;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;

/**
 * The WebID document for a pod owner whose identity lives in their own pod.
 *
 * <p>A pod without one is a pod nobody can authenticate into. Solid-OIDC's trust model runs
 * through this file: a resource server takes the {@code iss} from a token, dereferences the
 * WebID, and looks for a {@code solid:oidcIssuer} triple naming that issuer. No document, no
 * triple, no authentication — which is exactly why the conformance harness stops at
 * REGISTER CLIENTS with a 404 rather than a failed login.
 *
 * <p>Seeding it is opt-in per pod, by configuring the issuer. Without one there is no document
 * and nothing is published, because a profile naming no issuer would be a public file that
 * authenticates nobody.
 *
 * <h2>The one thing here that is world-readable, and why</h2>
 *
 * <p>The profile is granted {@code acl:Read} to {@code foaf:Agent} — the public. That is not a
 * relaxation, it is what a WebID <em>is</em>: an identifier anyone can resolve to find out
 * which issuers may speak for it. A resource server checking a token has not authenticated
 * anybody yet, so it necessarily reads this document anonymously; a private WebID document
 * cannot be used to log in anywhere, including into the pod that holds it.
 *
 * <p>It is still the only thing Cistern publishes by default, so: the grant is on the profile
 * document alone, never the container, and the owner keeps every mode on it.
 */
public final class WebIdProfile {

    /** The fragment a WebID conventionally uses for the person: {@code …/profile/card#me}. */
    public static final String WEBID_FRAGMENT = "#me";

    private static final String PUBLIC_AUTHORIZATION_FRAGMENT = "#public";
    private static final String OWNER_AUTHORIZATION_FRAGMENT = "#owner";

    private WebIdProfile() {
        // graph shapes only
    }

    /**
     * Whether {@code webId} is a document this pod is responsible for serving.
     *
     * <p>An owner whose WebID lives elsewhere — at their own identity provider, which is the
     * common case — needs nothing seeded here, and writing a document at a URI we do not own
     * would be inventing an identity for somebody else.
     */
    public static boolean livesIn(URI webId, ResourceIdentifier root) {
        Objects.requireNonNull(webId, "webId");
        Objects.requireNonNull(root, "root");
        return webId.toString().startsWith(root.uri().toString());
    }

    /** The document a WebID names, i.e. the WebID without its fragment. */
    public static ResourceIdentifier documentOf(URI webId) {
        Objects.requireNonNull(webId, "webId");
        String text = webId.toString();
        int hash = text.indexOf('#');
        return new ResourceIdentifier(URI.create(hash < 0 ? text : text.substring(0, hash)));
    }

    /** The profile graph: who this is, and which issuer may speak for them. */
    public static Model graph(URI webId, URI oidcIssuer) {
        Objects.requireNonNull(webId, "webId");
        Objects.requireNonNull(oidcIssuer, "oidcIssuer");
        Model model = ModelFactory.createDefaultModel();
        model.setNsPrefix("solid", Solid.NS);
        model.setNsPrefix(Foaf.PREFIX, Foaf.NS);

        Resource me = model.createResource(webId.toString());
        me.addProperty(RDF.type, Foaf.AGENT);
        // The triple the whole trust model turns on (Solid-OIDC §5).
        me.addProperty(Solid.OIDC_ISSUER, model.createResource(oidcIssuer.toString()));
        // Deliberately no storage triple: pim:Storage is a class, and the space:storage
        // property it would need is not in the vocabulary yet. The issuer is what
        // authentication turns on; storage discovery can follow when something needs it.
        return model;
    }

    /** {@link #graph} as Turtle. */
    public static Representation document(URI webId, URI oidcIssuer) {
        return RdfIo.serialize(graph(webId, oidcIssuer), Representation.TURTLE);
    }

    /**
     * The ACL for the profile document: public Read, owner everything.
     *
     * <p>Scoped with {@code acl:accessTo} on the document and deliberately <em>no</em>
     * {@code acl:default} — a default would inherit down a container and publish more than the
     * one file this is about.
     */
    public static Model aclGraph(URI webId, ResourceIdentifier profile) {
        Objects.requireNonNull(webId, "webId");
        Objects.requireNonNull(profile, "profile");
        Model model = ModelFactory.createDefaultModel();
        model.setNsPrefix(Acl.PREFIX, Acl.NS);
        model.setNsPrefix(Foaf.PREFIX, Foaf.NS);
        Resource document = model.createResource(profile.uri().toString());
        ResourceIdentifier acl = AclResource.of(profile);

        Resource anyone = model.createResource(acl.uri() + PUBLIC_AUTHORIZATION_FRAGMENT);
        anyone.addProperty(RDF.type, Acl.AUTHORIZATION);
        anyone.addProperty(Acl.AGENT_CLASS, Foaf.AGENT);
        anyone.addProperty(Acl.ACCESS_TO, document);
        anyone.addProperty(Acl.MODE, model.createResource(AccessMode.READ.iri()));

        Resource owner = model.createResource(acl.uri() + OWNER_AUTHORIZATION_FRAGMENT);
        owner.addProperty(RDF.type, Acl.AUTHORIZATION);
        owner.addProperty(Acl.AGENT, model.createResource(webId.toString()));
        owner.addProperty(Acl.ACCESS_TO, document);
        for (AccessMode mode : PodProvisioner.OWNER_MODES) {
            owner.addProperty(Acl.MODE, model.createResource(mode.iri()));
        }
        return model;
    }

    /** {@link #aclGraph} as Turtle. */
    public static Representation acl(URI webId, ResourceIdentifier profile) {
        return RdfIo.serialize(aclGraph(webId, profile), Representation.TURTLE);
    }
}
