package com.enrichmeai.cistern.core.ldp;

import com.enrichmeai.cistern.core.CisternException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code Link rel="type"} → interaction model table of LDP 1.0 §5.2.3.4, one test per row.
 *
 * <p>The sentence being implemented has two halves that pull in opposite directions, and this
 * class exists so the asymmetry between them is pinned rather than assumed:
 * <ul>
 *   <li>"If any requested interaction model cannot be honored, the server MUST fail the request"
 *       — so {@code ldp:DirectContainer} and {@code ldp:IndirectContainer} are refused, because
 *       Solid Protocol §4.2 gives Cistern Basic Containers only and there is no membership
 *       machinery to honour them with.</li>
 *   <li>"This specification does not constrain the server's behavior in other cases" — so a
 *       {@code rel="type"} link naming something outside the LDP namespace is <em>not</em> a
 *       requested interaction model, and creating a document for it is permitted.</li>
 * </ul>
 */
class InteractionModelTest {

    private static final String LDP_NS = Ldp.NS;

    private static InteractionModel model(String... typeIris) {
        return InteractionModel.forTypeIris(List.of(typeIris));
    }

    // ---------------------------------------------------------------- honoured: containers

    /** Solid Protocol §4.2 — Solid containers correspond to LDP Basic Container. */
    @Test
    @DisplayName("ldp:BasicContainer creates a container")
    void basicContainerIsHonoured() {
        assertEquals(InteractionModel.BASIC_CONTAINER, model(Ldp.BASIC_CONTAINER.getURI()));
        assertTrue(InteractionModel.BASIC_CONTAINER.container());
    }

    /**
     * The superclass. Solid has no container kind other than Basic, so creating a basic container
     * is how a request for the LDPC interaction model gets honoured — not a downgrade.
     */
    @Test
    @DisplayName("ldp:Container creates a container, via the only container kind Solid has")
    void containerIsHonouredAsBasic() {
        assertEquals(InteractionModel.BASIC_CONTAINER, model(Ldp.CONTAINER.getURI()));
    }

    // ---------------------------------------------------------------- honoured: documents

    /**
     * The LDPR interaction model in its three spellings. {@code ldp:NonRDFSource} is included
     * deliberately: it is an LDPR, so creating a document handles subsequent requests to it as an
     * LDPR, which is exactly what §5.2.3.4 requires — and what the resource's state actually is
     * comes from {@code Content-Type} (§5.2.3.6), not from this header.
     */
    @ParameterizedTest(name = "[{index}] {0} creates a document")
    @ValueSource(strings = {"Resource", "RDFSource", "NonRDFSource"})
    void ldprModelsCreateADocument(String localName) {
        assertEquals(InteractionModel.RESOURCE, model(LDP_NS + localName));
    }

    @Test
    @DisplayName("no Link at all is no requested model")
    void noLinkIsADocument() {
        assertEquals(InteractionModel.RESOURCE, model());
    }

    // ---------------------------------------------------------------- refused

    /**
     * The ruling this test exists for. Both types carry membership semantics
     * ({@code ldp:membershipResource}, {@code ldp:hasMemberRelation},
     * {@code ldp:insertedContentRelation}) that Cistern implements nothing for, so a container
     * created for them would not behave as asked on any later request — the silent downgrade
     * §5.2.3.4 forbids.
     */
    @ParameterizedTest(name = "[{index}] ldp:{0} must fail the request")
    @ValueSource(strings = {"DirectContainer", "IndirectContainer"})
    void unhonourableContainerModelsFailTheRequest(String localName) {
        CisternException.BadInput refusal = assertThrows(CisternException.BadInput.class,
                () -> model(LDP_NS + localName));

        assertTrue(refusal.getMessage().contains(localName),
                "the refusal must name what was refused: " + refusal.getMessage());
    }

    /**
     * "If <b>any</b> requested interaction model cannot be honored" — so a request pairing one
     * model this server can serve with one it cannot is refused outright rather than
     * half-honoured. Both orderings, because the check must not depend on which link came first.
     */
    @Test
    @DisplayName("an unhonourable model refuses the request even beside an honourable one")
    void oneUnhonourableModelRefusesTheWholeRequest() {
        assertThrows(CisternException.BadInput.class,
                () -> model(Ldp.BASIC_CONTAINER.getURI(), Ldp.DIRECT_CONTAINER.getURI()));
        assertThrows(CisternException.BadInput.class,
                () -> model(Ldp.DIRECT_CONTAINER.getURI(), Ldp.BASIC_CONTAINER.getURI()));
        assertThrows(CisternException.BadInput.class,
                () -> model(Ldp.RESOURCE.getURI(), Ldp.INDIRECT_CONTAINER.getURI()));
    }

    // ---------------------------------------------------------------- unconstrained

    /**
     * The other half of the sentence. A domain vocabulary class is not an LDP interaction model,
     * so §5.2.3.4's MUST does not reach it and the request is served as an ordinary create. This
     * is the case that keeps the refusal above from becoming "reject any Link I do not know",
     * which would break every client that types its own data.
     */
    @ParameterizedTest(name = "[{index}] {0} is not a requested interaction model")
    @ValueSource(strings = {
            "https://vocab.example/Note",
            "http://xmlns.com/foaf/0.1/Document",
            "https://www.w3.org/ns/activitystreams#Note",
            "http://www.w3.org/ns/ldpx#DirectContainer",
            "",
    })
    void nonLdpTypesAreUnconstrainedAndCreateADocument(String typeIri) {
        assertEquals(InteractionModel.RESOURCE, model(typeIri),
                "LDP §5.2.3.4: \"does not constrain the server's behavior in other cases\"");
    }

    /**
     * A near-miss inside the LDP namespace must not be mistaken for a refused type — the match is
     * on the whole IRI, not on a substring of it.
     */
    @Test
    @DisplayName("an LDP-namespace IRI that is not an interaction model creates a document")
    void unknownLdpTypesCreateADocument() {
        assertEquals(InteractionModel.RESOURCE, model(LDP_NS + "MemberSubject"));
        assertEquals(InteractionModel.RESOURCE, model(LDP_NS + "DirectContainerish"));
    }

    // ---------------------------------------------------------------- precedence

    /** Every container is also a resource, so clients send both; the container request wins. */
    @Test
    @DisplayName("the most specific honourable model wins")
    void containerWinsOverResource() {
        assertEquals(InteractionModel.BASIC_CONTAINER,
                model(Ldp.RESOURCE.getURI(), Ldp.BASIC_CONTAINER.getURI()));
        assertEquals(InteractionModel.BASIC_CONTAINER,
                model(Ldp.BASIC_CONTAINER.getURI(), Ldp.RESOURCE.getURI()));
    }

    @Test
    @DisplayName("an unrecognised type alongside a container request does not dilute it")
    void unrecognisedTypesDoNotDiluteAContainerRequest() {
        assertEquals(InteractionModel.BASIC_CONTAINER,
                model("https://vocab.example/Archive", Ldp.BASIC_CONTAINER.getURI()));
    }
}
