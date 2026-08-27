package com.enrichmeai.cistern.wac;

import com.enrichmeai.cistern.core.ResourceIdentifier;
import com.enrichmeai.cistern.core.vocab.Acl;
import com.enrichmeai.cistern.core.vocab.Foaf;
import com.enrichmeai.cistern.core.vocab.Solid;

import org.apache.jena.rdf.model.Model;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("A seeded WebID document is what makes a pod's owner able to log in anywhere")
class WebIdProfileTest {

    private static final URI ALICE = URI.create("https://pod.example/alice/profile/card#me");
    private static final URI ISSUER = URI.create("https://idp.example/");
    private static final ResourceIdentifier ROOT =
            new ResourceIdentifier(URI.create("https://pod.example/alice/"));

    @Test
    @DisplayName("it carries the solid:oidcIssuer triple the trust model turns on")
    void namesTheIssuer() {
        Model profile = WebIdProfile.graph(ALICE, ISSUER);

        assertTrue(profile.contains(
                        profile.createResource(ALICE.toString()),
                        Solid.OIDC_ISSUER,
                        profile.createResource(ISSUER.toString())),
                "without this triple a resource server can trust no token for Alice");
    }

    @Test
    @DisplayName("the document is the WebID without its fragment — what a server actually fetches")
    void documentDropsTheFragment() {
        assertEquals(URI.create("https://pod.example/alice/profile/card"),
                WebIdProfile.documentOf(ALICE).uri());
    }

    @Test
    @DisplayName("a WebID hosted elsewhere is not this pod's to write")
    void foreignWebIdIsNotSeeded() {
        assertTrue(WebIdProfile.livesIn(ALICE, ROOT));
        assertFalse(WebIdProfile.livesIn(URI.create("https://elsewhere.example/me#i"), ROOT),
                "writing a profile at a URI we do not own would invent an identity");
    }

    @Test
    @DisplayName("the profile is publicly readable, because an unauthenticated server must read it")
    void publiclyReadable() {
        ResourceIdentifier profile = WebIdProfile.documentOf(ALICE);
        Model acl = WebIdProfile.aclGraph(ALICE, profile);

        assertTrue(acl.listStatements(null, Acl.AGENT_CLASS, Foaf.AGENT).hasNext(),
                "a private WebID document cannot be used to log in anywhere");
        assertTrue(acl.listStatements(null, Acl.MODE,
                acl.createResource(AccessMode.READ.iri())).hasNext());
    }

    @Test
    @DisplayName("the public grant is Read only, and never Write")
    void publicCannotWrite() {
        ResourceIdentifier profile = WebIdProfile.documentOf(ALICE);
        Model acl = WebIdProfile.aclGraph(ALICE, profile);

        var publicAuthorization = acl.listResourcesWithProperty(Acl.AGENT_CLASS, Foaf.AGENT).next();
        var modes = publicAuthorization.listProperties(Acl.MODE).toList();
        assertEquals(1, modes.size(), "the public reads a WebID; it never edits one");
        assertEquals(AccessMode.READ.iri(), modes.get(0).getObject().toString());
    }

    @Test
    @DisplayName("the public grant does not inherit down the container")
    void noDefaultOnThePublicGrant() {
        ResourceIdentifier profile = WebIdProfile.documentOf(ALICE);
        Model acl = WebIdProfile.aclGraph(ALICE, profile);

        assertFalse(acl.listStatements(null, Acl.DEFAULT, (org.apache.jena.rdf.model.RDFNode) null)
                        .hasNext(),
                "acl:default would publish more than the one document this is about");
    }

    @Test
    @DisplayName("the owner keeps every mode on their own profile")
    void ownerKeepsControl() {
        ResourceIdentifier profile = WebIdProfile.documentOf(ALICE);
        Model acl = WebIdProfile.aclGraph(ALICE, profile);

        var owner = acl.listResourcesWithProperty(
                Acl.AGENT, acl.createResource(ALICE.toString())).next();
        assertEquals(PodProvisioner.OWNER_MODES.size(), owner.listProperties(Acl.MODE).toList().size());
    }
}
