package com.enrichmeai.cistern.webflux;

import com.enrichmeai.cistern.core.rdf.N3Patch;
import com.enrichmeai.cistern.storage.file.FileResourceStore;
import com.enrichmeai.cistern.webflux.error.ProblemDocument;
import com.enrichmeai.cistern.webflux.error.ProblemType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T2.7 over real HTTP: {@code PATCH} with an N3 Patch body through the whole stack — router,
 * handler, precondition gate, {@code LdpService}, the error mapper, and the production
 * {@code FileResourceStore} wrapped in a {@link MutationRecordingResourceStore}. Nothing is
 * mocked, so what these assertions pin is what a client sees.
 *
 * <h2>The DoD: "spec examples pass over HTTP"</h2>
 * T1.5's suite proves the Solid Protocol's N3 Patch example applies correctly at the core level.
 * {@link SpecExample} below proves it <em>over the wire</em>: the example is sent as a real
 * {@code PATCH} request and the result is read back with a real {@code GET}, so the claim covers
 * routing, the media-type check, serialization of the patched graph, and storage — none of which
 * a core test exercises.
 *
 * <p>Every other expectation traces to a named sentence:
 * <ul>
 *   <li>Solid Protocol §5.3.1 — the patch algorithm, its 409s, its 422, and {@code Accept-Patch};
 *       §5.3 — the containment rule; §5.2 — {@code Allow} and the {@code Accept-*} fields.</li>
 *   <li>RFC 5789 §2.1 (204), §2.2 (400/415/422 and {@code Accept-Patch} on a 415), §3.1
 *       ({@code Accept-Patch} means PATCH is allowed).</li>
 *   <li>RFC 9110 §15.3.2 (201), §15.5.6 (405 + {@code Allow}), §13.2.1 (preconditions).</li>
 * </ul>
 *
 * <p>Paths are unique per test: a patch is not idempotent in its effect, and a test that seals
 * the store must not observe another's fixtures.
 */
@SpringBootTest(properties = {
        "cistern.base-url=http://localhost:3000",
        // The recording store replaces the production backend under the same bean name so a
        // refused request can be proven never to have reached storage. Nothing else is swapped.
        "spring.main.allow-bean-definition-overriding=true"})
@AutoConfigureWebTestClient
// Imported explicitly rather than relied on as an auto-detected nested @TestConfiguration:
// auto-detection applies to the class being bootstrapped, and this suite's @Nested classes are
// bootstrapped in their own right. @Import is an annotation, so NestedTestConfiguration's
// default INHERIT carries it to every nested class and all of them share one context.
@Import(ResourcePatchHttpTest.RecordingStoreConfiguration.class)
class ResourcePatchHttpTest {

    private static final String BASE = "http://localhost:3000";

    private static final String EX = "http://www.example.org/terms#";

    private static final byte[] PNG_BYTES = {
            (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x01, 0x02, 0x03};

    /** An entity-tag no representation can have: {@link EntityTag} values are SHA-256 hex. */
    private static final String STALE_ETAG = "\"not-the-current-validator\"";

    private static final Path STORAGE_ROOT = createTempRoot();

    private static final AtomicInteger UNIQUE = new AtomicInteger();

    @Autowired
    private WebTestClient client;

    @Autowired
    private MutationRecordingResourceStore store;

    @DynamicPropertySource
    static void storageRoot(DynamicPropertyRegistry registry) {
        registry.add("cistern.storage.root", STORAGE_ROOT::toString);
    }

    private static Path createTempRoot() {
        try {
            return Files.createTempDirectory("cistern-t27-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RecordingStoreConfiguration {

        @Bean
        MutationRecordingResourceStore resourceStore() {
            return new MutationRecordingResourceStore(new FileResourceStore(STORAGE_ROOT));
        }
    }

    // ---------------------------------------------------------------- helpers

    private static String unique(String template) {
        return template.formatted(UNIQUE.incrementAndGet());
    }

    private WebTestClient.RequestHeadersSpec<?> get(String path) {
        return client.get().uri(URI.create(path));
    }

    /** A {@code PATCH} carrying an N3 Patch document, the only form Solid Protocol §5.3.1 defines. */
    private WebTestClient.RequestHeadersSpec<?> patch(String path, String n3Document) {
        return patch(path, N3Patch.MEDIA_TYPE, n3Document);
    }

    private WebTestClient.RequestHeadersSpec<?> patch(String path, String contentType, String body) {
        return client.patch().uri(URI.create(path))
                .header(HttpHeaders.CONTENT_TYPE, contentType)
                .bodyValue(body.getBytes(StandardCharsets.UTF_8));
    }

    /** Creates a Turtle document and asserts it was a create, so a fixture cannot silently drift. */
    private String seed(String path, String turtle) {
        client.put().uri(URI.create(path))
                .header(HttpHeaders.CONTENT_TYPE, RdfSerialization.TURTLE.contentType())
                .bodyValue(turtle.getBytes(StandardCharsets.UTF_8))
                .exchange().expectStatus().isCreated();
        return path;
    }

    private String seedBinary(String path) {
        client.put().uri(URI.create(path))
                .header(HttpHeaders.CONTENT_TYPE, "image/png")
                .bodyValue(PNG_BYTES)
                .exchange().expectStatus().isCreated();
        return path;
    }

    private String bodyOf(String path) {
        EntityExchangeResult<byte[]> result =
                get(path).exchange().expectStatus().isOk().expectBody().returnResult();
        return new String(result.getResponseBodyContent(), StandardCharsets.UTF_8);
    }

    /**
     * Asserts a full RFC 9457 problem document, not merely the status: a bare status would also
     * pass against a handler that wrote one itself, which ground rule 4 forbids.
     */
    private WebTestClient.BodyContentSpec expectProblem(
            WebTestClient.RequestHeadersSpec<?> request, ProblemType expected, String instance) {
        return request.exchange()
                .expectStatus().isEqualTo(expected.status())
                .expectHeader().contentType(ProblemDocument.MEDIA_TYPE)
                .expectBody()
                .jsonPath("$.status").isEqualTo(expected.status().value())
                .jsonPath("$.type").isEqualTo(expected.uri().toString())
                .jsonPath("$.title").isEqualTo(expected.title())
                .jsonPath("$.detail").isNotEmpty()
                .jsonPath("$.instance").isEqualTo(instance);
    }

    private void expectNoStoreMutation(Runnable request) {
        store.whileSealed(request);
        assertEquals(List.of(), store.recordedMutations(),
                "RFC 9110 §13.2.1: a precondition is evaluated before the action associated with"
                        + " the request method, so a failed one must not reach the store");
    }

    // ================================================================ the DoD

    @Nested
    @DisplayName("Solid Protocol §5.3.1's example, applied over HTTP")
    class SpecExample {

        /**
         * The example from "Modifying Resources Using N3 Patches", verbatim:
         *
         * <pre>
         * &#64;prefix solid: &lt;http://www.w3.org/ns/solid/terms#&gt;.
         * &#64;prefix ex: &lt;http://www.example.org/terms#&gt;.
         *
         * _:rename a solid:InsertDeletePatch;
         *   solid:where   { ?person ex:familyName "Garcia". };
         *   solid:inserts { ?person ex:givenName "Alex". };
         *   solid:deletes { ?person ex:givenName "Claudia". }.
         * </pre>
         *
         * Spec text: "This N3 Patch instructs to rename Claudia Garcia into Alex Garcia, on the
         * condition that no other Garcia family members are present in the target RDF document."
         */
        private static final String RENAME = """
                @prefix solid: <http://www.w3.org/ns/solid/terms#>.
                @prefix ex: <http://www.example.org/terms#>.

                _:rename a solid:InsertDeletePatch;
                  solid:where   { ?person ex:familyName "Garcia". };
                  solid:inserts { ?person ex:givenName "Alex". };
                  solid:deletes { ?person ex:givenName "Claudia". }.
                """;

        private static final String CLAUDIA = """
                @prefix ex: <http://www.example.org/terms#>.
                <#claudia> ex:familyName "Garcia" ;
                           ex:givenName "Claudia" .
                """;

        @Test
        @DisplayName("the example renames Claudia Garcia into Alex Garcia, and a GET proves it")
        void appliesOverHttp() {
            String path = seed(unique("/t27-spec-%d.ttl"), CLAUDIA);

            patch(path, RENAME).exchange().expectStatus().isNoContent();

            String served = bodyOf(path);
            assertTrue(served.contains("Alex"), "solid:inserts must have added the new given name; got:\n" + served);
            assertFalse(served.contains("Claudia"), "solid:deletes must have removed the old one; got:\n" + served);
            assertTrue(served.contains("Garcia"), "the condition's own triple is untouched; got:\n" + served);
        }

        /**
         * The example's own stated condition — "on the condition that no other Garcia family
         * members are present". Two Garcias mean two variable mappings, and §5.3.1: "if multiple
         * mappings exist, the server MUST respond with a 409 status code."
         */
        @Test
        @DisplayName("with a second Garcia present, the example's condition makes it a 409")
        void refusesWhenTheConditionIsAmbiguous() {
            String path = seed(unique("/t27-spec-ambiguous-%d.ttl"), """
                    @prefix ex: <http://www.example.org/terms#>.
                    <#claudia> ex:familyName "Garcia" ; ex:givenName "Claudia" .
                    <#other>   ex:familyName "Garcia" ; ex:givenName "Jo" .
                    """);

            expectProblem(patch(path, RENAME), ProblemType.CONFLICT, path);

            assertTrue(bodyOf(path).contains("Claudia"), "the refused patch changed nothing");
        }
    }

    // ================================================================ status codes

    @Nested
    @DisplayName("what a successful PATCH answers")
    class SuccessStatuses {

        /**
         * §5.3.1 step 1 — "or an empty RDF dataset if the target resource does not exist yet" —
         * makes a patch of an absent resource a create, and RFC 9110 §15.3.2 makes a create a
         * 201. This is the ticket's headline behaviour.
         */
        @Test
        @DisplayName("§5.3.1: patching a resource that does not exist creates it, with 201")
        void createsAnAbsentResource() {
            String path = unique("/t27-created-%d.ttl");

            patch(path, """
                    @prefix solid: <http://www.w3.org/ns/solid/terms#>.
                    _:add a solid:InsertDeletePatch;
                      solid:inserts { <https://vocab.example/s> <https://vocab.example/p> "o". }.
                    """).exchange().expectStatus().isCreated();

            assertTrue(bodyOf(path).contains("https://vocab.example/p"),
                    "the created resource holds the inserted triple");
        }

        /**
         * RFC 9110 §15.3.2 identifies a created resource "by either a Location header field in
         * the response or, if no Location field is received, by the target URI". A patch applies
         * to the resource the client named, so Location would only restate the request line.
         */
        @Test
        @DisplayName("RFC 9110 §15.3.2: the 201 carries no Location — the target URI is the answer")
        void sendsNoLocationOnCreate() {
            String path = unique("/t27-no-location-%d.ttl");

            EntityExchangeResult<byte[]> result = patch(path, """
                    @prefix solid: <http://www.w3.org/ns/solid/terms#>.
                    _:add a solid:InsertDeletePatch;
                      solid:inserts { <https://vocab.example/s> <https://vocab.example/p> "o". }.
                    """).exchange().expectStatus().isCreated().expectBody().returnResult();

            assertNull(result.getResponseHeaders().getFirst(HttpHeaders.LOCATION),
                    "a PATCH creates at the target URI, so Location would be redundant");
        }

        /** RFC 5789 §2.1: "The 204 response code is used because the response does not carry a message body". */
        @Test
        @DisplayName("RFC 5789 §2.1: patching an existing resource answers 204 with no body")
        void modifiesWith204() {
            String path = seed(unique("/t27-modified-%d.ttl"), "<> <https://vocab.example/k> \"v\" .");

            patch(path, """
                    @prefix solid: <http://www.w3.org/ns/solid/terms#>.
                    _:add a solid:InsertDeletePatch;
                      solid:inserts { <https://vocab.example/s> <https://vocab.example/p> "o". }.
                    """).exchange()
                    .expectStatus().isNoContent()
                    .expectBody().isEmpty();
        }

        /**
         * A PATCH negotiates no representation — RFC 5789 §2: its entity-headers "apply only to
         * the contained patch document and MUST NOT be applied to the resource being modified" —
         * so there is no representation for a validator to describe. See the handler's javadoc.
         */
        @Test
        @DisplayName("a PATCH response carries no validator, since it selects no representation")
        void sendsNoValidators() {
            String path = seed(unique("/t27-novalidator-%d.ttl"), "<> <https://vocab.example/k> \"v\" .");

            EntityExchangeResult<byte[]> result = patch(path, """
                    @prefix solid: <http://www.w3.org/ns/solid/terms#>.
                    _:add a solid:InsertDeletePatch;
                      solid:inserts { <https://vocab.example/s> <https://vocab.example/p> "o". }.
                    """).exchange().expectStatus().isNoContent().expectBody().returnResult();

            assertNull(result.getResponseHeaders().getFirst(HttpHeaders.ETAG));
            assertNull(result.getResponseHeaders().getFirst(HttpHeaders.LAST_MODIFIED));
        }
    }

    // ================================================================ 415

    @Nested
    @DisplayName("RFC 5789 §2.2: a body that is not an N3 Patch document")
    class UnsupportedMediaType {

        private static final String ANY_PATCH = """
                @prefix solid: <http://www.w3.org/ns/solid/terms#>.
                _:add a solid:InsertDeletePatch;
                  solid:inserts { <https://vocab.example/s> <https://vocab.example/p> "o". }.
                """;

        /**
         * §5.3.1 identifies an N3 Patch by {@code text/n3} and by nothing else, and RFC 5789 §2.2
         * makes an unsupported patch document a 415 — not a 400: the document may be perfectly
         * well-formed in its own format; it is simply not one this resource takes.
         */
        @Test
        @DisplayName("a text/turtle body on a PATCH is 415")
        void refusesTurtle() {
            String path = seed(unique("/t27-415-%d.ttl"), "<> <https://vocab.example/k> \"v\" .");

            expectProblem(patch(path, RdfSerialization.TURTLE.contentType(), ANY_PATCH),
                    ProblemType.UNSUPPORTED_MEDIA_TYPE, path);
        }

        /**
         * RFC 5789 §2.2: such a response "SHOULD include an Accept-Patch response header ... to
         * notify the client what patch document media types are supported" — which is the reason
         * this refusal goes through Cistern's own domain signal rather than Spring's
         * {@code UnsupportedMediaTypeStatusException}, whose headers carry {@code Accept}.
         */
        @Test
        @DisplayName("RFC 5789 §2.2: the 415 tells the client what to retry with, in Accept-Patch")
        void advertisesAcceptPatchOnThe415() {
            String path = seed(unique("/t27-415-hdr-%d.ttl"), "<> <https://vocab.example/k> \"v\" .");

            patch(path, RdfSerialization.TURTLE.contentType(), ANY_PATCH).exchange()
                    .expectStatus().isEqualTo(ProblemType.UNSUPPORTED_MEDIA_TYPE.status())
                    .expectHeader().valueEquals(HttpHeaders.ACCEPT_PATCH, N3Patch.MEDIA_TYPE);
        }

        @Test
        @DisplayName("a PATCH with no Content-Type at all is 415")
        void refusesAMissingContentType() {
            String path = seed(unique("/t27-415-none-%d.ttl"), "<> <https://vocab.example/k> \"v\" .");

            expectProblem(client.patch().uri(URI.create(path))
                            .bodyValue(ANY_PATCH.getBytes(StandardCharsets.UTF_8)),
                    ProblemType.UNSUPPORTED_MEDIA_TYPE, path);
        }

        /** {@link org.springframework.http.MediaType#isCompatibleWith} compares type and subtype only. */
        @Test
        @DisplayName("text/n3 with a charset parameter is accepted")
        void acceptsAParameterizedN3ContentType() {
            String path = unique("/t27-415-param-%d.ttl");

            patch(path, N3Patch.MEDIA_TYPE + ";charset=utf-8", ANY_PATCH)
                    .exchange().expectStatus().isCreated();
        }

        /**
         * A 415 is decided from the request alone, so it outranks the precondition — RFC 9110
         * §13.2.1: "failures that can be detected before significant processing occurs take
         * precedence over the evaluation of preconditions". Nothing may reach the store.
         */
        @Test
        @DisplayName("the 415 outranks a precondition and never touches the store")
        void refusesBeforeTheStoreIsTouched() {
            String path = seed(unique("/t27-415-sealed-%d.ttl"), "<> <https://vocab.example/k> \"v\" .");

            expectNoStoreMutation(() -> patch(path, RdfSerialization.TURTLE.contentType(), ANY_PATCH)
                    .header(HttpHeaders.IF_MATCH, STALE_ETAG)
                    .exchange()
                    .expectStatus().isEqualTo(ProblemType.UNSUPPORTED_MEDIA_TYPE.status()));
        }
    }

    // ================================================================ the error taxonomy

    @Nested
    @DisplayName("T1.5's error split, verified end to end through the mapper")
    class ErrorTaxonomy {

        /** RFC 5789 §2.2 "Malformed patch document ... SHOULD return a 400 (Bad Request)". */
        @Test
        @DisplayName("400: a document that is not parseable N3")
        void malformedDocumentIs400() {
            String path = seed(unique("/t27-400-%d.ttl"), "<> <https://vocab.example/k> \"v\" .");

            expectProblem(patch(path, "this is not N3 {{{"), ProblemType.BAD_INPUT, path);
        }

        /**
         * §5.3.1: "Servers MUST respond with a 422 status code [RFC4918] if a patch document does
         * not satisfy all of the above constraints." Here the patch resource lacks its required
         * {@code solid:InsertDeletePatch} type — well-formed N3, invalid as a patch.
         */
        @Test
        @DisplayName("422: well-formed N3 that breaches §5.3.1's constraints")
        void constraintBreachIs422() {
            String path = seed(unique("/t27-422-%d.ttl"), "<> <https://vocab.example/k> \"v\" .");

            expectProblem(patch(path, """
                    @prefix solid: <http://www.w3.org/ns/solid/terms#>.
                    _:a solid:inserts { <https://vocab.example/s> <https://vocab.example/p> "o". }.
                    """), ProblemType.UNPROCESSABLE_ENTITY, path);
        }

        /**
         * The 400/422 split is load-bearing and cannot be reconstructed after the fact, so both
         * halves are asserted against the same resource: a malformed document and a
         * constraint-breaching one must not collapse to one status.
         */
        @Test
        @DisplayName("the 400 and the 422 are genuinely different answers")
        void theSplitSurvivesTheMapper() {
            String path = seed(unique("/t27-split-%d.ttl"), "<> <https://vocab.example/k> \"v\" .");

            assertEquals(400, ProblemType.BAD_INPUT.status().value());
            assertEquals(422, ProblemType.UNPROCESSABLE_ENTITY.status().value());

            patch(path, "{{{ not n3").exchange().expectStatus().isEqualTo(400);
            patch(path, """
                    @prefix solid: <http://www.w3.org/ns/solid/terms#>.
                    _:a solid:inserts { <https://vocab.example/s> <https://vocab.example/p> "o". }.
                    """).exchange().expectStatus().isEqualTo(422);
        }

        /** §5.3.1: "If no such mapping exists ... the server MUST respond with a 409 status code." */
        @Test
        @DisplayName("409: a where clause nothing in the graph satisfies")
        void noMappingIs409() {
            String path = seed(unique("/t27-409-nomatch-%d.ttl"), "<> <https://vocab.example/k> \"v\" .");

            expectProblem(patch(path, """
                    @prefix solid: <http://www.w3.org/ns/solid/terms#>.
                    _:u a solid:InsertDeletePatch;
                      solid:where   { ?s <https://vocab.example/absent> "nothing". };
                      solid:inserts { ?s <https://vocab.example/p> "o". }.
                    """), ProblemType.CONFLICT, path);
        }

        /**
         * §5.3.1: "If the set of triples resulting from ?deletions is non-empty and the dataset
         * does not contain all of these triples, the server MUST respond with a 409 status code."
         */
        @Test
        @DisplayName("409: deleting a triple the graph does not contain")
        void deletingAnAbsentTripleIs409() {
            String path = seed(unique("/t27-409-del-%d.ttl"), "<> <https://vocab.example/k> \"v\" .");

            expectProblem(patch(path, """
                    @prefix solid: <http://www.w3.org/ns/solid/terms#>.
                    _:d a solid:InsertDeletePatch;
                      solid:deletes { <https://vocab.example/s> <https://vocab.example/nope> "gone". }.
                    """), ProblemType.CONFLICT, path);

            assertTrue(bodyOf(path).contains("https://vocab.example/k"), "the refused patch changed nothing");
        }

        /**
         * Solid Protocol §5.3: "Servers MUST NOT allow HTTP PUT or PATCH on a container to update
         * its containment triples; if the server receives such a request, it MUST respond with a
         * 409 status code."
         */
        @Test
        @DisplayName("409: a patch inserting ldp:contains for the target container")
        void insertingContainmentIs409() {
            String container = unique("/t27-c-%d/");
            seed(container + "seed.ttl", "<> <https://vocab.example/k> \"v\" .");

            expectProblem(patch(container, """
                    @prefix solid: <http://www.w3.org/ns/solid/terms#>.
                    @prefix ldp: <http://www.w3.org/ns/ldp#>.
                    _:a a solid:InsertDeletePatch;
                      solid:inserts { <%s%s> ldp:contains <%s%sfake>. }.
                    """.formatted(BASE, container, BASE, container)),
                    ProblemType.CONFLICT, container);
        }
    }

    // ================================================================ non-RDF resources

    @Nested
    @DisplayName("Solid Protocol §5.3.1: PATCH is for RDF documents")
    class NonRdfResources {

        private static final String ANY_PATCH = """
                @prefix solid: <http://www.w3.org/ns/solid/terms#>.
                _:add a solid:InsertDeletePatch;
                  solid:inserts { <https://vocab.example/s> <https://vocab.example/p> "o". }.
                """;

        /**
         * §5.3.1 scopes the PATCH requirement to "when the target of the request is an RDF
         * document", and there is no graph in a JPEG to apply a patch to. RFC 9110 §15.5.6 is the
         * matching status: the method is "known by the origin server but not supported by the
         * target resource".
         */
        @Test
        @DisplayName("patching a binary resource is 405")
        void refusesABinaryTarget() {
            String path = seedBinary(unique("/t27-binary-%d.png"));

            expectProblem(patch(path, ANY_PATCH), ProblemType.METHOD_NOT_ALLOWED, path);
        }

        /**
         * <b>The architect's requirement from issue #18.</b> RFC 9110 §15.5.6 makes {@code Allow}
         * mandatory on a 405 and defines it as the target resource's supported methods, so the
         * refusal of a PATCH must not list PATCH. Before T2.7 this value was derived from the
         * request path, which could not tell a graph from a JPEG; it now comes from the kind core
         * carries on the domain exception.
         */
        @Test
        @DisplayName("the 405's Allow excludes PATCH and lists everything a binary still supports")
        void the405AdvertisesATruthfulAllow() {
            String path = seedBinary(unique("/t27-binary-allow-%d.png"));

            EntityExchangeResult<byte[]> result = patch(path, ANY_PATCH).exchange()
                    .expectStatus().isEqualTo(ProblemType.METHOD_NOT_ALLOWED.status())
                    .expectBody().returnResult();

            String allow = result.getResponseHeaders().getFirst(HttpHeaders.ALLOW);
            assertNotNull(allow, "RFC 9110 §15.5.6 makes Allow mandatory on a 405");
            List<String> methods = List.of(allow.split(",\\s*"));
            assertFalse(methods.contains("PATCH"),
                    "a 405 for PATCH must not advertise PATCH; got: " + allow);
            assertTrue(methods.containsAll(List.of("GET", "HEAD", "OPTIONS", "PUT", "DELETE")),
                    "everything a binary document still supports must be listed; got: " + allow);
        }

        /**
         * RFC 5789 §3.1: "The presence of the Accept-Patch header in response to any method is an
         * implicit indication that PATCH is allowed on the resource identified by the
         * Request-URI." So a resource that cannot be patched must never send the field.
         */
        @Test
        @DisplayName("RFC 5789 §3.1: a binary resource never advertises Accept-Patch")
        void aBinaryResourceNeverAdvertisesAcceptPatch() {
            String path = seedBinary(unique("/t27-binary-nopatch-%d.png"));

            EntityExchangeResult<byte[]> read = get(path).exchange()
                    .expectStatus().isOk().expectBody().returnResult();
            assertNull(read.getResponseHeaders().getFirst(HttpHeaders.ACCEPT_PATCH),
                    "Accept-Patch would assert that PATCH is allowed, which it is not");

            EntityExchangeResult<byte[]> refusal = patch(path, ANY_PATCH).exchange()
                    .expectStatus().isEqualTo(ProblemType.METHOD_NOT_ALLOWED.status())
                    .expectBody().returnResult();
            assertNull(refusal.getResponseHeaders().getFirst(HttpHeaders.ACCEPT_PATCH),
                    "nor may the refusal itself imply PATCH is available");
        }

        /**
         * The distinction the whole core-carried-kind change exists for: two documents, one RDF
         * and one binary, at paths that differ in nothing a router can read. Their advertised
         * interfaces must differ.
         */
        @Test
        @DisplayName("§5.2: an RDF document and a binary one advertise different interfaces")
        void rdfAndBinaryDocumentsAdvertiseDifferently() {
            String rdf = seed(unique("/t27-iface-rdf-%d.ttl"), "<> <https://vocab.example/k> \"v\" .");
            String binary = seedBinary(unique("/t27-iface-bin-%d.png"));

            EntityExchangeResult<byte[]> rdfRead = get(rdf).exchange()
                    .expectStatus().isOk().expectBody().returnResult();
            EntityExchangeResult<byte[]> binaryRead = get(binary).exchange()
                    .expectStatus().isOk().expectBody().returnResult();

            assertTrue(rdfRead.getResponseHeaders().getFirst(HttpHeaders.ALLOW).contains("PATCH"),
                    "an RDF document is patchable (§5.3.1)");
            assertFalse(binaryRead.getResponseHeaders().getFirst(HttpHeaders.ALLOW).contains("PATCH"),
                    "a binary document is not");
            assertEquals(N3Patch.MEDIA_TYPE,
                    rdfRead.getResponseHeaders().getFirst(HttpHeaders.ACCEPT_PATCH),
                    "§5.3.1: servers indicate N3 Patch support by listing text/n3 in Accept-Patch");
            assertNull(binaryRead.getResponseHeaders().getFirst(HttpHeaders.ACCEPT_PATCH));
        }
    }

    // ================================================================ interface metadata

    @Nested
    @DisplayName("Solid Protocol §5.2: what a PATCH response advertises")
    class InterfaceMetadata {

        /**
         * §5.2 requires the {@code Accept-Patch}, {@code Accept-Post} and {@code Accept-Put}
         * fields to "correspond to acceptable HTTP methods listed in Allow header field value",
         * and §5.3.1 requires {@code text/n3} to be listed where PATCH is supported.
         */
        @Test
        @DisplayName("a patched document advertises the same interface a GET of it does")
        void advertisesTheDocumentInterface() {
            String path = seed(unique("/t27-iface-%d.ttl"), "<> <https://vocab.example/k> \"v\" .");

            EntityExchangeResult<byte[]> patched = patch(path, """
                    @prefix solid: <http://www.w3.org/ns/solid/terms#>.
                    _:a a solid:InsertDeletePatch;
                      solid:inserts { <https://vocab.example/s> <https://vocab.example/p> "o". }.
                    """).exchange().expectStatus().isNoContent().expectBody().returnResult();

            EntityExchangeResult<byte[]> read =
                    get(path).exchange().expectStatus().isOk().expectBody().returnResult();

            assertEquals(read.getResponseHeaders().getFirst(HttpHeaders.ALLOW),
                    patched.getResponseHeaders().getFirst(HttpHeaders.ALLOW),
                    "one ResourceKind table serves both, so they cannot differ");
            assertEquals(N3Patch.MEDIA_TYPE,
                    patched.getResponseHeaders().getFirst(HttpHeaders.ACCEPT_PATCH));
        }

        /** LDP 1.0 §4.2.1.4 requires the type advertisement "in all responses". */
        @Test
        @DisplayName("a resource created by a patch is typed as an LDP Resource")
        void advertisesTheLdpTypeOnCreate() {
            String path = unique("/t27-iface-created-%d.ttl");

            patch(path, """
                    @prefix solid: <http://www.w3.org/ns/solid/terms#>.
                    _:a a solid:InsertDeletePatch;
                      solid:inserts { <https://vocab.example/s> <https://vocab.example/p> "o". }.
                    """).exchange()
                    .expectStatus().isCreated()
                    .expectHeader().valueEquals(HttpHeaders.LINK,
                            "<http://www.w3.org/ns/ldp#Resource>; rel=\"type\"");
        }
    }

    // ================================================================ preconditions

    @Nested
    @DisplayName("RFC 9110 §13: preconditions apply to PATCH")
    class Preconditions {

        private static final String INSERT = """
                @prefix solid: <http://www.w3.org/ns/solid/terms#>.
                _:a a solid:InsertDeletePatch;
                  solid:inserts { <https://vocab.example/s> <https://vocab.example/p> "o". }.
                """;

        /**
         * A patch is defined relative to a state the client believes the resource is in, which is
         * exactly the "lost update" case RFC 5789 §2 cites {@code If-Match} for. §13.2.1 places
         * the evaluation "just before it would process the request content", so a stale validator
         * must fail the request before anything is applied.
         */
        @Test
        @DisplayName("a stale If-Match fails the patch with 412 and never reaches the store")
        void staleIfMatchIs412AndWritesNothing() {
            String path = seed(unique("/t27-ifmatch-%d.ttl"), "<> <https://vocab.example/k> \"v\" .");

            expectNoStoreMutation(() -> patch(path, INSERT)
                    .header(HttpHeaders.IF_MATCH, STALE_ETAG)
                    .exchange()
                    .expectStatus().isEqualTo(ProblemType.PRECONDITION_FAILED.status()));

            assertFalse(bodyOf(path).contains("https://vocab.example/p"),
                    "the refused patch must not have been applied");
        }

        /** The round trip that makes a conditional patch usable: a current validator is accepted. */
        @Test
        @DisplayName("a current If-Match lets the patch through")
        void currentIfMatchSucceeds() {
            String path = seed(unique("/t27-ifmatch-ok-%d.ttl"), "<> <https://vocab.example/k> \"v\" .");
            EntityExchangeResult<byte[]> read =
                    get(path).exchange().expectStatus().isOk().expectBody().returnResult();
            String etag = read.getResponseHeaders().getFirst(HttpHeaders.ETAG);
            assertNotNull(etag, "arrange: a GET must supply a validator");

            patch(path, INSERT).header(HttpHeaders.IF_MATCH, etag)
                    .exchange().expectStatus().isNoContent();

            assertTrue(bodyOf(path).contains("https://vocab.example/p"));
        }

        /**
         * §13.2.1 evaluates preconditions when the unconditional response would be 2xx or 412.
         * A patch of an absent target is a 201, so {@code If-None-Match: *} is a create-only
         * guard on PATCH exactly as it is on PUT — and here the target exists, so it fails.
         */
        @Test
        @DisplayName("If-None-Match: * makes a patch create-only")
        void ifNoneMatchStarIsACreateOnlyGuard() {
            String existing = seed(unique("/t27-inm-%d.ttl"), "<> <https://vocab.example/k> \"v\" .");

            expectNoStoreMutation(() -> patch(existing, INSERT)
                    .header(HttpHeaders.IF_NONE_MATCH, "*")
                    .exchange()
                    .expectStatus().isEqualTo(ProblemType.PRECONDITION_FAILED.status()));

            // The same guard on a target that is not there is satisfied, and the patch creates.
            patch(unique("/t27-inm-absent-%d.ttl"), INSERT)
                    .header(HttpHeaders.IF_NONE_MATCH, "*")
                    .exchange().expectStatus().isCreated();
        }
    }
}
