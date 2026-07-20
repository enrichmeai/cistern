package com.enrichmeai.cistern.webflux;

import com.enrichmeai.cistern.core.Representation;
import com.enrichmeai.cistern.core.ResourceIdentifier;
import com.enrichmeai.cistern.core.ldp.Ldp;
import com.enrichmeai.cistern.core.ldp.ResourceView;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The representation validator in isolation (RFC 9110 §8.8.1: a strong validator changes
 * whenever the representation changes, and only then).
 *
 * <p>The two defects this type exists to fix are pinned first: a container's derived
 * containment must move the tag, and the two RDF serializations of one resource must not
 * share one. The determinism tests are the counterweight — the digest covers canonical
 * inputs, so it must not move for anything that leaves the representation identical.
 */
class EntityTagTest {

    private static final String BASE = "https://pod.example";
    private static final String STORED_ETAG = "0123456789abcdef";
    private static final Instant MODIFIED = Instant.parse("2026-07-20T08:03:09Z");

    private static ResourceIdentifier id(String path) {
        return new ResourceIdentifier(URI.create(BASE + path));
    }

    /** A container view whose graph contains the given children, in the order given. */
    private static ResourceView.Rdf container(String path, String... children) {
        ResourceIdentifier identifier = id(path);
        Model graph = ModelFactory.createDefaultModel();
        Resource subject = graph.createResource(identifier.uri().toString());
        for (String child : children) {
            graph.add(subject, Ldp.CONTAINS, graph.createResource(BASE + child));
        }
        return new ResourceView.Rdf(identifier, STORED_ETAG, MODIFIED, true, graph);
    }

    private static ResourceView.Rdf document(String path) {
        return new ResourceView.Rdf(id(path), STORED_ETAG, MODIFIED, false,
                ModelFactory.createDefaultModel());
    }

    private static ResourceView.NonRdf binary(String path, String contentType) {
        return new ResourceView.NonRdf(id(path), STORED_ETAG, MODIFIED,
                new Representation(contentType, "bytes".getBytes(StandardCharsets.UTF_8)));
    }

    private static EntityTag tagOf(ResourceView view, MediaType mediaType) {
        return EntityTag.forRepresentation(view, mediaType);
    }

    private static EntityTag turtleTag(ResourceView view) {
        return tagOf(view, RdfSerialization.TURTLE.mediaType());
    }

    // ---------------------------------------------------------------- the defects it fixes

    @Test
    void containmentIsPartOfTheContainersRepresentation() {
        assertNotEquals(
                turtleTag(container("/c/", "/c/a.ttl")),
                turtleTag(container("/c/", "/c/a.ttl", "/c/b.ttl")),
                "adding a child changes what a GET returns (Solid Protocol §4.2), so the"
                        + " validator must move even though the stored bytes did not");
    }

    @Test
    void anEmptiedContainerDiffersFromAPopulatedOne() {
        assertNotEquals(turtleTag(container("/c/", "/c/a.ttl")), turtleTag(container("/c/")));
    }

    @Test
    void theTwoRdfSerializationsOfOneResourceDiffer() {
        ResourceView.Rdf view = document("/a.ttl");
        assertNotEquals(
                tagOf(view, RdfSerialization.TURTLE.mediaType()),
                tagOf(view, RdfSerialization.JSON_LD.mediaType()),
                "different bytes, therefore different strong validators (RFC 9110 §8.8.1)");
    }

    // ---------------------------------------------------------------- determinism

    @Test
    void theSameRepresentationAlwaysYieldsTheSameTag() {
        // The counterweight to the tests above: hashing canonical inputs rather than Jena's
        // output means repeated computation cannot drift, which is what makes the tag usable
        // for caching and for T2.5's conditional requests.
        for (int i = 0; i < 8; i++) {
            assertEquals(turtleTag(container("/c/", "/c/a.ttl", "/c/b.ttl")),
                    turtleTag(container("/c/", "/c/a.ttl", "/c/b.ttl")));
        }
    }

    @Test
    void childOrderDoesNotAffectTheTag() {
        assertEquals(
                turtleTag(container("/c/", "/c/a.ttl", "/c/b.ttl")),
                turtleTag(container("/c/", "/c/b.ttl", "/c/a.ttl")),
                "the URI set is sorted before hashing, so a backend's directory order is"
                        + " not observable to clients");
    }

    @Test
    void theStoredValidatorStillParticipates() {
        ResourceView.Rdf before = document("/a.ttl");
        ResourceView.Rdf after = new ResourceView.Rdf(
                before.identifier(), "ffffffffffffffff", MODIFIED, false, before.graph());

        assertNotEquals(turtleTag(before), turtleTag(after),
                "a write that changes the stored bytes must still change the validator");
    }

    @Test
    void aNonRdfResourceTagFollowsItsStoredMediaType() {
        assertNotEquals(
                tagOf(binary("/f", MediaType.IMAGE_PNG_VALUE), MediaType.IMAGE_PNG),
                tagOf(binary("/f", MediaType.IMAGE_JPEG_VALUE), MediaType.IMAGE_JPEG));
    }

    @Test
    void fieldsCannotCollideByConcatenation() {
        // Without a separator between hashed fields, ("ab","c") and ("a","bc") would collide.
        assertNotEquals(
                turtleTag(container("/c/", "/ab", "/c")),
                turtleTag(container("/c/", "/a", "/bc")));
    }

    // ---------------------------------------------------------------- header form

    @Test
    void headerValueIsQuotedAndStrong() {
        String header = turtleTag(document("/a.ttl")).headerValue();
        assertTrue(header.startsWith("\"") && header.endsWith("\""), header);
        assertTrue(!header.startsWith("W/"), "strong validators carry no W/ prefix");
    }

    @Test
    void anEmptyValidatorIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new EntityTag(" "));
        assertThrows(IllegalArgumentException.class, () -> new EntityTag(null));
    }

    @Test
    void tagsAreValueObjects() {
        assertEquals(new EntityTag("abc"), new EntityTag("abc"));
        assertEquals(List.of(new EntityTag("abc")), List.of(new EntityTag("abc")));
    }
}
