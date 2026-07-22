package com.enrichmeai.cistern.webflux;

import com.enrichmeai.cistern.core.vocab.Pim;
import com.enrichmeai.cistern.core.vocab.Solid;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The discovery facts of Solid Protocol §4.1 in isolation, with no HTTP stack — where the
 * storage root and its description resource are, and what the description says.
 * {@code StorageDescriptionHttpTest} then pins that a client sees them over the wire.
 *
 * <p>The property this class exists for is that §4.1's three requirements are consistent: the
 * link a resource emits must name the URI the description is served from, and the description
 * must name the URI that advertises itself as the storage. A server that got any one of them
 * right in isolation and the others out of step would send a client on a traversal that dead-ends.
 */
class StorageDescriptionTest {

    private static StorageDescription describing(String baseUrl) {
        return new StorageDescription(new CisternProperties(baseUrl, null, null, null));
    }

    @Test
    @DisplayName("the storage root is the base URL's own container")
    void theStorageRootIsTheBaseUrlContainer() {
        StorageDescription description = describing("https://pod.example");

        assertEquals("https://pod.example/", description.storageRoot().uri().toString());
        assertTrue(description.storageRoot().isContainer(),
                "§4.1: the storage resource is the root container, so its URI ends in '/'");
        assertTrue(description.storageRoot().isStorageRoot(),
                "the root has no parent, which is what ResourceKind.STORAGE_ROOT is selected by");
    }

    /**
     * {@code cistern.base-url} tolerates a trailing slash and a path prefix, so the two URIs
     * derived from it must be right for every accepted spelling — otherwise a deployment behind a
     * path prefix would advertise a description resource at a URI that 404s.
     */
    @ParameterizedTest
    @ValueSource(strings = {"https://pod.example", "https://pod.example/", "https://pod.example///"})
    @DisplayName("a trailing slash on the base URL is insignificant to both derived URIs")
    void trailingSlashesOnTheBaseUrlDoNotLeakIn(String baseUrl) {
        StorageDescription description = describing(baseUrl);

        assertEquals("https://pod.example/", description.storageRoot().uri().toString());
        assertEquals("https://pod.example/.well-known/solid",
                description.descriptionUri().toString());
    }

    @Test
    @DisplayName("a base URL with a path prefix keeps both URIs under it")
    void aPathPrefixIsCarriedIntoBothUris() {
        StorageDescription description = describing("https://host.example/pods/alice");

        assertEquals("https://host.example/pods/alice/", description.storageRoot().uri().toString());
        assertEquals("https://host.example/pods/alice/.well-known/solid",
                description.descriptionUri().toString());
    }

    /**
     * §4.1: the {@code Link} field with {@code rel="http://www.w3.org/ns/solid/terms#storageDescription"}
     * targets "the URI of the storage description resource" — so the header and
     * {@link StorageDescription#descriptionUri()} are one URI, checked here by parsing the header
     * back with the production parser rather than by string comparison.
     */
    @Test
    @DisplayName("§4.1: the emitted Link targets exactly the description URI")
    void theLinkTargetsTheDescriptionResource() {
        StorageDescription description = describing("https://pod.example");

        List<String> targets = LinkHeader.targetsWithRelation(
                List.of(description.linkValue()), LinkRelation.STORAGE_DESCRIPTION);

        assertEquals(List.of(description.descriptionUri().toString()), targets);
    }

    /** RFC 8288 §3: an extension relation type is a URI, so it has to be quoted to be legal. */
    @Test
    @DisplayName("the relation is the solid: vocabulary IRI, quoted")
    void theRelationIsTheVocabularyIri() {
        assertEquals("<https://pod.example/.well-known/solid>; rel=\""
                        + Solid.STORAGE_DESCRIPTION.getURI() + "\"",
                describing("https://pod.example").linkValue());
    }

    /**
     * §4.1: "Servers MUST include statements about the storage as part of the storage description
     * resource ... {@code rdf:type} — A class whose URI is
     * {@code http://www.w3.org/ns/pim/space#Storage}."
     */
    @Test
    @DisplayName("§4.1: the graph states <root> a pim:Storage and nothing else")
    void theGraphStatesTheStorageType() {
        StorageDescription description = describing("https://pod.example");
        Model graph = description.graph();

        assertTrue(graph.contains(graph.createResource("https://pod.example/"), RDF.type,
                Pim.STORAGE));
        assertEquals(1, graph.size(),
                "Cistern asserts only what §4.1 requires; any further property would advertise a"
                        + " capability it does not implement");
    }

    /**
     * The subject of the description is the resource that advertises {@code rel="type"}
     * {@code pim:Storage} — one fact, said in a graph and in a header. If these ever diverged, a
     * client following §4.1's traversal would be handed a URI that denies being the storage.
     */
    @Test
    @DisplayName("the graph's subject is the resource ResourceKind.STORAGE_ROOT types")
    void theGraphSubjectIsTheResourceThatAdvertisesPimStorage() {
        StorageDescription description = describing("https://pod.example");
        Model graph = description.graph();

        List<String> subjects = graph.listResourcesWithProperty(RDF.type, Pim.STORAGE)
                .toList().stream().map(resource -> resource.getURI()).toList();

        assertEquals(List.of(description.storageRoot().uri().toString()), subjects);
        assertTrue(ResourceKind.STORAGE_ROOT.linkTypeValues()
                        .contains(HttpConstants.linkType(Pim.STORAGE.getURI())),
                "the same kind must advertise pim:Storage in its rel=\"type\" links");
    }

    /**
     * A fresh model per call. Jena models are mutable and not thread-safe, so a shared one handed
     * to a serializer from several request threads would be a data race.
     */
    @Test
    @DisplayName("each call returns its own model")
    void theGraphIsNotShared() {
        StorageDescription description = describing("https://pod.example");

        assertTrue(description.graph() != description.graph());
        assertTrue(description.graph().isIsomorphicWith(description.graph()));
    }
}
