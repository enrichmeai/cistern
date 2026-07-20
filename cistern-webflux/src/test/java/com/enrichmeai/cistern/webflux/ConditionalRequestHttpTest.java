package com.enrichmeai.cistern.webflux;

import com.enrichmeai.cistern.core.Representation;
import com.enrichmeai.cistern.storage.file.FileResourceStore;
import com.enrichmeai.cistern.webflux.error.ProblemDocument;
import com.enrichmeai.cistern.webflux.error.ProblemType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T2.5 over real HTTP: conditional requests through the whole stack — router, handlers,
 * negotiation, {@code LdpService}, and the production {@code FileResourceStore}, wrapped in a
 * {@link MutationRecordingResourceStore} that watches for writes. Nothing is mocked, so what
 * these assertions pin is what a client sees.
 *
 * <p>Every expectation traces to a sentence of RFC 9110, named on the test:
 * <ul>
 *   <li>§13.1.1 {@code If-Match} (strong comparison), §13.1.2 {@code If-None-Match} (weak),
 *       §13.1.3 {@code If-Modified-Since}, §13.1.4 {@code If-Unmodified-Since}.</li>
 *   <li>§13.2.1 when preconditions are evaluated and what outranks them.</li>
 *   <li>§13.2.2 the order the four are evaluated in.</li>
 *   <li>§8.8.3.2 the two entity-tag comparison functions.</li>
 *   <li>§15.4.5 what a 304 carries and what it must not.</li>
 * </ul>
 *
 * <p>Paths are unique per test, because a conditional write is not idempotent in its effect and
 * because a test that seals the store must not observe another's fixtures.
 */
@SpringBootTest(properties = {
        "cistern.base-url=http://localhost:3000",
        // The recording store below replaces the production backend under the same bean name.
        // Nothing else in the stack is substituted.
        "spring.main.allow-bean-definition-overriding=true"})
@AutoConfigureWebTestClient
class ConditionalRequestHttpTest {

    private static final String TURTLE = "<> <https://vocab.example/k> \"v\" .";
    private static final String TURTLE_UPDATED = "<> <https://vocab.example/k> \"v2\" .";

    /** An entity-tag no representation can have: {@link EntityTag} values are SHA-256 hex. */
    private static final String STALE_ETAG = "\"not-the-current-validator\"";

    private static final byte[] PNG_BYTES = {
            (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x01, 0x02, 0x03};

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
            return Files.createTempDirectory("cistern-t25-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Replaces the backend bean with the recording wrapper, which still delegates to the
     * production {@code FileResourceStore}. Everything above the storage SPI — routes,
     * handlers, {@code LdpService}, the error mapper — is the production wiring, untouched: the
     * only thing added is the ability to see whether the store was asked to mutate.
     */
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

    private WebTestClient.RequestBodySpec put(String path) {
        return client.put().uri(URI.create(path));
    }

    private WebTestClient.RequestHeadersSpec<?> putTurtle(String path, String body) {
        return put(path)
                .header(HttpHeaders.CONTENT_TYPE, RdfSerialization.TURTLE.contentType())
                .bodyValue(body.getBytes(StandardCharsets.UTF_8));
    }

    /** Creates a resource and asserts it was a create, so a fixture cannot silently drift. */
    private String seed(String path) {
        putTurtle(path, TURTLE).exchange().expectStatus().isCreated();
        return path;
    }

    private String seedBinary(String path) {
        put(path).header(HttpHeaders.CONTENT_TYPE, "image/png").bodyValue(PNG_BYTES)
                .exchange().expectStatus().isCreated();
        return path;
    }

    /** The validator a client would hold after a {@code GET} — the round-trip's starting point. */
    private String etagOf(String path) {
        return etagOf(get(path));
    }

    private String jsonLdEtagOf(String path) {
        return etagOf(get(path).header(HttpHeaders.ACCEPT, Representation.JSON_LD));
    }

    private static String etagOf(WebTestClient.RequestHeadersSpec<?> request) {
        EntityExchangeResult<byte[]> result =
                request.exchange().expectStatus().isOk().expectBody().returnResult();
        String etag = result.getResponseHeaders().getFirst(HttpHeaders.ETAG);
        assertNotNull(etag, "every successful read carries a validator (RFC 9110 §8.8.3)");
        return etag;
    }

    private static Instant lastModifiedOf(WebTestClient.RequestHeadersSpec<?> request) {
        long millis = request.exchange().expectStatus().isOk().expectBody().returnResult()
                .getResponseHeaders().getLastModified();
        return Instant.ofEpochMilli(millis);
    }

    /** An IMF-fixdate (RFC 9110 §5.6.7), the form a client echoes back from Last-Modified. */
    private static String httpDate(Instant instant) {
        return DateTimeFormatter.RFC_1123_DATE_TIME
                .format(ZonedDateTime.ofInstant(instant, ZoneId.of("GMT")));
    }

    /**
     * Asserts a full RFC 9457 412 document — not merely the status, because a bare 412 would
     * also pass against a handler that wrote a status code itself, which ground rule 4 forbids.
     */
    private static void expectPreconditionFailed(WebTestClient.RequestHeadersSpec<?> request,
                                                 String instance, String... detailMentions) {
        request.exchange()
                .expectStatus().isEqualTo(HttpStatus.PRECONDITION_FAILED)
                .expectHeader().contentType(ProblemDocument.MEDIA_TYPE)
                .expectBody()
                .jsonPath("$.status").isEqualTo(ProblemType.PRECONDITION_FAILED.status().value())
                .jsonPath("$.type").isEqualTo(ProblemType.PRECONDITION_FAILED.uri().toString())
                .jsonPath("$.title").isEqualTo(ProblemType.PRECONDITION_FAILED.title())
                .jsonPath("$.instance").isEqualTo(instance)
                .jsonPath("$.detail").value(detail -> {
                    for (String expected : detailMentions) {
                        assertTrue(String.valueOf(detail).contains(expected),
                                "RFC 9457 §3.1.4: the detail must explain this occurrence,"
                                        + " naming " + expected + "; got: " + detail);
                    }
                });
    }

    /**
     * Runs a request that must be refused, with the store sealed, and proves nothing was
     * written — the ticket's DoD. Both halves matter: the seal makes a stray write fail the
     * request loudly, and the recording proves the write was never even attempted.
     */
    private void expectNoStoreMutation(Runnable request) {
        store.whileSealed(request);
        assertEquals(java.util.List.of(), store.recordedMutations(),
                "RFC 9110 §13.2.1: a precondition is evaluated before the action associated"
                        + " with the request method, so a failed one must not reach the store");
    }

    // ================================================================ If-Match on PUT

    @Test
    void etagFromAGetSatisfiesASubsequentIfMatch() {
        // The round trip that makes If-Match usable at all: the validator a client reads off a
        // GET must be accepted verbatim on the next write, with no intervening change.
        String path = seed(unique("/t25-roundtrip-%d.ttl"));

        putTurtle(path, TURTLE_UPDATED).header(HttpHeaders.IF_MATCH, etagOf(path))
                .exchange().expectStatus().isNoContent();

        get(path).exchange().expectStatus().isOk().expectBody(String.class)
                .value(body -> assertTrue(body.contains("v2"), "the write took effect"));
    }

    @Test
    void staleIfMatchOnPutIsRefusedWithoutTouchingTheStore() {
        // The DoD's headline. §13.1.1: "An origin server that evaluates an If-Match condition
        // MUST NOT perform the requested method if the condition evaluates to false."
        String path = seed(unique("/t25-stale-%d.ttl"));

        expectNoStoreMutation(() -> expectPreconditionFailed(
                putTurtle(path, TURTLE_UPDATED).header(HttpHeaders.IF_MATCH, STALE_ETAG),
                path, HttpHeaders.IF_MATCH, "no current representation", STALE_ETAG));

        get(path).exchange().expectBody(String.class)
                .value(body -> assertTrue(body.contains("\"v\""), "the old representation stands"));
    }

    @Test
    void ifMatchWildcardSucceedsWhenTheResourceExists() {
        // §13.1.1 step 1: "the condition is true if the origin server has a current
        // representation for the target resource".
        String path = seed(unique("/t25-star-present-%d.ttl"));

        putTurtle(path, TURTLE_UPDATED).header(HttpHeaders.IF_MATCH, "*")
                .exchange().expectStatus().isNoContent();
    }

    @Test
    void ifMatchWildcardIsRefusedWhenTheResourceIsAbsent() {
        // The other half of step 1, and the case that proves preconditions are evaluated for a
        // PUT to an absent target at all (§13.2.1: the unconditional response would be 201).
        String path = unique("/t25-star-absent-%d.ttl");

        expectNoStoreMutation(() -> expectPreconditionFailed(
                putTurtle(path, TURTLE).header(HttpHeaders.IF_MATCH, "*"),
                path, HttpHeaders.IF_MATCH));

        get(path).exchange().expectStatus().isNotFound();
    }

    @Test
    void ifMatchUsesStrongComparisonSoAWeakTagNeverMatches() {
        // §13.1.1: "An origin server MUST use the strong comparison function when comparing
        // entity tags for If-Match", and §8.8.3.2: W/"1" against "1" is "no match" strongly.
        // The opaque-tag here is the CURRENT one, so only the W/ prefix can refuse this.
        String path = seed(unique("/t25-weak-match-%d.ttl"));
        String weakened = ClientEntityTag.WEAK_PREFIX + etagOf(path);

        expectNoStoreMutation(() -> expectPreconditionFailed(
                putTurtle(path, TURTLE_UPDATED).header(HttpHeaders.IF_MATCH, weakened),
                path, HttpHeaders.IF_MATCH));
    }

    @Test
    void ifMatchListSucceedsIfAnyListedTagMatches() {
        // §13.1.1 step 2: "the condition is true if any of the listed tags match".
        String path = seed(unique("/t25-list-%d.ttl"));
        String list = STALE_ETAG + ", " + etagOf(path) + ", \"another\"";

        putTurtle(path, TURTLE_UPDATED).header(HttpHeaders.IF_MATCH, list)
                .exchange().expectStatus().isNoContent();
    }

    @Test
    void ifMatchAcceptsTheValidatorOfEitherRdfSerialization() {
        // A PUT selects no representation, so §13.1.1's "having a current representation of the
        // target resource that has an entity tag matching a member of the list" is satisfied by
        // either of the two an RDF source has. A client that fetched JSON-LD must be able to
        // write back conditionally without having to guess which one the server considers
        // selected.
        String path = seed(unique("/t25-crossrep-%d.ttl"));
        String jsonLdTag = jsonLdEtagOf(path);
        assertNotEquals(etagOf(path), jsonLdTag, "the two serializations have distinct validators");

        putTurtle(path, TURTLE_UPDATED).header(HttpHeaders.IF_MATCH, jsonLdTag)
                .exchange().expectStatus().isNoContent();
    }

    @Test
    void malformedIfMatchIsRefusedRatherThanIgnoredOrRejectedAsBadInput() {
        // §13.1.1 step 3: "Otherwise, the condition is false." A value that is neither "*" nor
        // a list of entity tags takes that branch — so 412, not 400 and not a silent pass.
        String path = seed(unique("/t25-malformed-%d.ttl"));

        expectNoStoreMutation(() -> expectPreconditionFailed(
                putTurtle(path, TURTLE_UPDATED).header(HttpHeaders.IF_MATCH, "unquoted-garbage"),
                path, HttpHeaders.IF_MATCH));
    }

    @Test
    void ifMatchOnANonRdfResourceUsesItsStoredRepresentationsValidator() {
        String path = seedBinary(unique("/t25-binary-%d.png"));

        put(path).header(HttpHeaders.CONTENT_TYPE, "image/png")
                .header(HttpHeaders.IF_MATCH, etagOf(path))
                .bodyValue(PNG_BYTES)
                .exchange().expectStatus().isNoContent();
    }

    // ================================================================ If-None-Match on PUT

    @Test
    void ifNoneMatchWildcardCreatesWhenTheResourceIsAbsent() {
        // Solid Protocol §5.3 encourages exactly this: "Clients are encouraged to use the HTTP
        // If-None-Match header field with a value of "*" to prevent an unsafe request method
        // ... from inadvertently modifying an existing representation of the target resource
        // when the client believes that the resource does not have a current representation."
        String path = unique("/t25-createonly-%d.ttl");

        putTurtle(path, TURTLE).header(HttpHeaders.IF_NONE_MATCH, "*")
                .exchange().expectStatus().isCreated();
    }

    @Test
    void ifNoneMatchWildcardIsRefusedWhenTheResourceExists() {
        // §13.1.2 step 1: "the condition is false if the origin server has a current
        // representation for the target resource", and the failure is a 412 because the method
        // is neither GET nor HEAD.
        String path = seed(unique("/t25-createonly-taken-%d.ttl"));

        // The detail must say the resource EXISTS. If-Match fails because nothing matched and
        // If-None-Match because something did, so one shared wording is wrong for one of them —
        // a real defect this assertion now pins.
        expectNoStoreMutation(() -> expectPreconditionFailed(
                putTurtle(path, TURTLE_UPDATED).header(HttpHeaders.IF_NONE_MATCH, "*"),
                path, HttpHeaders.IF_NONE_MATCH, "has a current representation"));

        get(path).exchange().expectBody(String.class)
                .value(body -> assertTrue(body.contains("\"v\""), "the existing resource stands"));
    }

    @Test
    void matchingIfNoneMatchOnAPutIs412AndNot304() {
        // §13.1.2: respond "a) the 304 (Not Modified) status code if the request method is GET
        // or HEAD or b) the 412 (Precondition Failed) status code for all other request
        // methods". A 304 here would tell a client its write succeeded when it did not.
        String path = seed(unique("/t25-nonematch-put-%d.ttl"));

        expectNoStoreMutation(() -> expectPreconditionFailed(
                putTurtle(path, TURTLE_UPDATED).header(HttpHeaders.IF_NONE_MATCH, etagOf(path)),
                path, HttpHeaders.IF_NONE_MATCH));
    }

    // ================================================================ DELETE

    @Test
    void currentIfMatchAllowsADelete() {
        String path = seed(unique("/t25-delete-ok-%d.ttl"));

        client.delete().uri(URI.create(path)).header(HttpHeaders.IF_MATCH, etagOf(path))
                .exchange().expectStatus().isNoContent();

        get(path).exchange().expectStatus().isNotFound();
    }

    @Test
    void staleIfMatchOnDeleteIsRefusedWithoutTouchingTheStore() {
        String path = seed(unique("/t25-delete-stale-%d.ttl"));

        expectNoStoreMutation(() -> expectPreconditionFailed(
                client.delete().uri(URI.create(path)).header(HttpHeaders.IF_MATCH, STALE_ETAG),
                path, HttpHeaders.IF_MATCH));

        get(path).exchange().expectStatus().isOk();
    }

    @Test
    void conditionalDeleteOfAMissingResourceIsStillNotFound() {
        // §13.2.1: "A server MUST ignore all received preconditions if its response to the same
        // request without those conditions ... would have been a status code other than a 2xx
        // (Successful) or 412 (Precondition Failed)." An unconditional DELETE here is a 404, so
        // the If-Match is ignored rather than turned into a 412.
        String path = unique("/t25-delete-missing-%d.ttl");

        client.delete().uri(URI.create(path)).header(HttpHeaders.IF_MATCH, STALE_ETAG)
                .exchange().expectStatus().isNotFound();
    }

    // ================================================================ §13.2.1 method applicability

    /**
     * Solid Protocol §5.4 refuses {@code DELETE} on the storage root unconditionally, and RFC
     * 9110 §13.2.1 says a server "MUST ignore all received preconditions if its response to the
     * same request without those conditions ... would have been a status code other than a 2xx
     * (Successful) or 412 (Precondition Failed)". 405 is such a status, so the answer is 405
     * whatever the {@code If-Match} says — matching, stale, or absent.
     *
     * <p>Both directions are asserted because they fail differently: a stale tag would give 412
     * if applicability were not checked first, and a matching tag would let the delete through
     * to core, which must still refuse it. Neither may touch the store.
     */
    @Test
    void deleteOfTheStorageRootIsMethodNotAllowedWhateverItsIfMatchSays() {
        putTurtle("/", "").exchange().expectStatus().is2xxSuccessful();
        String rootEtag = etagOf("/");

        for (String ifMatch : java.util.List.of(STALE_ETAG, rootEtag, "*")) {
            expectNoStoreMutation(() -> client.delete().uri(URI.create("/"))
                    .header(HttpHeaders.IF_MATCH, ifMatch)
                    .exchange()
                    .expectStatus().isEqualTo(HttpStatus.METHOD_NOT_ALLOWED)
                    // RFC 9110 §15.5.6 makes Allow mandatory on a 405, and §5.4 requires DELETE
                    // to be excluded from it — the same table that decided to skip the
                    // preconditions renders this value, so the two cannot disagree.
                    .expectHeader().valueEquals(HttpHeaders.ALLOW, ResourceKind.STORAGE_ROOT.allow()));
        }

        get("/").exchange().expectStatus().isOk();
    }

    @Test
    void anUnconditionalDeleteOfTheStorageRootIsUnchanged() {
        // The applicability check must not alter the behaviour T2.4 established.
        putTurtle("/", "").exchange().expectStatus().is2xxSuccessful();

        client.delete().uri(URI.create("/"))
                .exchange().expectStatus().isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    }

    @Test
    void aPutToTheStorageRootStillHonoursItsPreconditions() {
        // The rule is per-method, not per-resource: PUT IS in the root's method set (§5.4
        // singles out DELETE only), so a stale If-Match on a root PUT is still a 412. An
        // implementation that skipped preconditions for the whole resource would answer 204.
        putTurtle("/", "").exchange().expectStatus().is2xxSuccessful();

        expectNoStoreMutation(() -> expectPreconditionFailed(
                putTurtle("/", "").header(HttpHeaders.IF_MATCH, STALE_ETAG),
                "/", HttpHeaders.IF_MATCH));
    }

    // ================================================================ 304 on GET / HEAD

    @Test
    void matchingIfNoneMatchOnAGetIsNotModified() {
        String path = seed(unique("/t25-304-%d.ttl"));
        String etag = etagOf(path);

        EntityExchangeResult<byte[]> result = get(path)
                .header(HttpHeaders.IF_NONE_MATCH, etag)
                .exchange()
                .expectStatus().isNotModified()
                .expectBody().returnResult();

        // §15.4.5: the 304 MUST carry the ETag a 200 would have carried...
        assertEquals(etag, result.getResponseHeaders().getFirst(HttpHeaders.ETAG));
        // ...and Vary, for a representation that was negotiated.
        assertEquals(HttpHeaders.ACCEPT, result.getResponseHeaders().getFirst(HttpHeaders.VARY));
        // "A 304 response is terminated by the end of the header section; it cannot contain
        // content or trailers."
        byte[] body = result.getResponseBodyContent();
        assertTrue(body == null || body.length == 0, "a 304 cannot contain content");
        assertNull(result.getResponseHeaders().getFirst(HttpHeaders.CONTENT_TYPE),
                "§15.4.5: a sender SHOULD NOT generate representation metadata beyond the"
                        + " listed fields");
    }

    @Test
    void matchingIfNoneMatchOnAHeadIsNotModifiedToo() {
        String path = seed(unique("/t25-304-head-%d.ttl"));

        client.head().uri(URI.create(path))
                .header(HttpHeaders.IF_NONE_MATCH, etagOf(path))
                .exchange()
                .expectStatus().isNotModified()
                .expectHeader().exists(HttpHeaders.ETAG);
    }

    @Test
    void ifNoneMatchUsesWeakComparisonSoAWeakenedTagStillMatches() {
        // §13.1.2: "A recipient MUST use the weak comparison function when comparing entity
        // tags for If-None-Match", and §8.8.3.2: W/"1" against "1" is a match weakly. This is
        // the exact pair that must behave the OPPOSITE way for If-Match, above.
        String path = seed(unique("/t25-304-weak-%d.ttl"));

        get(path).header(HttpHeaders.IF_NONE_MATCH, ClientEntityTag.WEAK_PREFIX + etagOf(path))
                .exchange().expectStatus().isNotModified();
    }

    @Test
    void ifNoneMatchWildcardOnAGetOfAnExistingResourceIsNotModified() {
        String path = seed(unique("/t25-304-star-%d.ttl"));

        get(path).header(HttpHeaders.IF_NONE_MATCH, "*")
                .exchange().expectStatus().isNotModified();
    }

    @Test
    void staleIfNoneMatchServesTheRepresentation() {
        // §13.1.2 step 3: nothing matched, so the condition is true and the method proceeds.
        String path = seed(unique("/t25-304-stale-%d.ttl"));

        get(path).header(HttpHeaders.IF_NONE_MATCH, STALE_ETAG)
                .exchange().expectStatus().isOk()
                .expectBody(String.class).value(body -> assertTrue(body.contains("\"v\"")));
    }

    @Test
    void aValidatorForTheOtherSerializationDoesNotProduceANotModified() {
        // §13.1.2 step 2 is about "the entity tag of the SELECTED representation". A client
        // holding the Turtle copy and asking for JSON-LD holds different bytes, so it must be
        // sent them — a 304 here would leave it believing its Turtle copy was the JSON-LD one.
        String path = seed(unique("/t25-304-otherrep-%d.ttl"));

        get(path).header(HttpHeaders.ACCEPT, Representation.JSON_LD)
                .header(HttpHeaders.IF_NONE_MATCH, etagOf(path))
                .exchange().expectStatus().isOk();
    }

    @Test
    void aContainerListingIsNotModifiedUntilItsMembershipChanges() {
        // The validator is representation-scoped (see EntityTag), so a container's 304 has to
        // stop being a 304 the moment a child appears — otherwise a cache serves a stale
        // listing (Solid Protocol §4.2).
        String container = unique("/t25-304-container-%d/");
        putTurtle(container, "").exchange().expectStatus().isCreated();
        String etag = etagOf(container);

        get(container).header(HttpHeaders.IF_NONE_MATCH, etag)
                .exchange().expectStatus().isNotModified();

        seed(container + "child.ttl");

        get(container).header(HttpHeaders.IF_NONE_MATCH, etag)
                .exchange().expectStatus().isOk();
    }

    @Test
    void aNonRdfResourceIsNotModifiedAndDoesNotVary() {
        String path = seedBinary(unique("/t25-304-binary-%d.png"));

        EntityExchangeResult<byte[]> result = get(path)
                .header(HttpHeaders.IF_NONE_MATCH, etagOf(path))
                .exchange().expectStatus().isNotModified()
                .expectBody().returnResult();

        // Asked of the Accept entry rather than of the whole field: since T2.8 the CORS layer
        // adds Vary: Origin to every response (Solid Protocol §8.1, see OriginVaryFilter), and
        // what this test is about is that a single-representation resource does not vary by
        // content negotiation.
        assertTrue(result.getResponseHeaders().getVary().stream()
                        .noneMatch(HttpHeaders.ACCEPT::equalsIgnoreCase),
                "one representation, so nothing varies by Accept");
    }

    @Test
    void failingIfMatchOnAGetIs412AndNot304() {
        // §13.1.1: "A client MAY send an If-Match header field in a GET request to indicate
        // that it would prefer a 412 (Precondition Failed) response if the selected
        // representation does not match."
        String path = seed(unique("/t25-get-412-%d.ttl"));

        expectPreconditionFailed(get(path).header(HttpHeaders.IF_MATCH, STALE_ETAG),
                path, HttpHeaders.IF_MATCH);
    }

    // ================================================================ evaluation order

    @Test
    void ifMatchIsEvaluatedBeforeIfNoneMatch() {
        // §13.2.2: If-Match is step 1 and If-None-Match is step 3. With a failing If-Match and
        // a matching If-None-Match on a GET, the answer is 412 — an implementation that
        // evaluated them the other way round would answer 304.
        String path = seed(unique("/t25-order-%d.ttl"));

        expectPreconditionFailed(get(path)
                        .header(HttpHeaders.IF_MATCH, STALE_ETAG)
                        .header(HttpHeaders.IF_NONE_MATCH, etagOf(path)),
                path, HttpHeaders.IF_MATCH);
    }

    @Test
    void ifUnmodifiedSinceIsIgnoredWhenIfMatchIsPresent() {
        // §13.1.4: "A recipient MUST ignore If-Unmodified-Since if the request contains an
        // If-Match header field". The date below would fail on its own; the satisfied If-Match
        // must nonetheless carry the write through.
        String path = seed(unique("/t25-ius-ignored-%d.ttl"));

        putTurtle(path, TURTLE_UPDATED)
                .header(HttpHeaders.IF_MATCH, etagOf(path))
                .header(HttpHeaders.IF_UNMODIFIED_SINCE, httpDate(Instant.EPOCH))
                .exchange().expectStatus().isNoContent();
    }

    @Test
    void ifModifiedSinceIsIgnoredWhenIfNoneMatchIsPresent() {
        // §13.1.3: "A recipient MUST ignore If-Modified-Since if the request contains an
        // If-None-Match header field". The date alone would yield 304; the failing
        // If-None-Match must win and produce the representation.
        String path = seed(unique("/t25-ims-ignored-%d.ttl"));

        get(path)
                .header(HttpHeaders.IF_NONE_MATCH, STALE_ETAG)
                .header(HttpHeaders.IF_MODIFIED_SINCE, httpDate(Instant.now().plus(Duration.ofDays(1))))
                .exchange().expectStatus().isOk();
    }

    // ================================================================ date conditionals

    @Test
    void ifUnmodifiedSinceInThePastRefusesTheWrite() {
        // §13.1.4: the condition is true only if the last modification date "is earlier than or
        // equal to the date provided".
        String path = seed(unique("/t25-ius-%d.ttl"));

        expectNoStoreMutation(() -> expectPreconditionFailed(
                putTurtle(path, TURTLE_UPDATED)
                        .header(HttpHeaders.IF_UNMODIFIED_SINCE, httpDate(Instant.EPOCH)),
                path, HttpHeaders.IF_UNMODIFIED_SINCE));
    }

    @Test
    void ifUnmodifiedSinceAtTheResourcesOwnDateAllowsTheWrite() {
        // "earlier than OR EQUAL TO" — the date a client read off Last-Modified must pass.
        String path = seed(unique("/t25-ius-equal-%d.ttl"));
        Instant lastModified = lastModifiedOf(get(path));

        putTurtle(path, TURTLE_UPDATED)
                .header(HttpHeaders.IF_UNMODIFIED_SINCE, httpDate(lastModified))
                .exchange().expectStatus().isNoContent();
    }

    @Test
    void ifModifiedSinceAtTheResourcesOwnDateIsNotModified() {
        // §13.1.3: "If the selected representation's last modification date is earlier or equal
        // to the date provided in the field value, the condition is false" → 304.
        String path = seed(unique("/t25-ims-%d.ttl"));
        Instant lastModified = lastModifiedOf(get(path));

        get(path).header(HttpHeaders.IF_MODIFIED_SINCE, httpDate(lastModified))
                .exchange().expectStatus().isNotModified();
    }

    @Test
    void ifModifiedSinceIsIgnoredOnAPut() {
        // §13.1.3: "A recipient MUST ignore the If-Modified-Since header field if ... the
        // request method is neither GET nor HEAD."
        String path = seed(unique("/t25-ims-put-%d.ttl"));

        putTurtle(path, TURTLE_UPDATED)
                .header(HttpHeaders.IF_MODIFIED_SINCE, httpDate(Instant.EPOCH))
                .exchange().expectStatus().isNoContent();
    }

    @Test
    void anUnparseableDateIsIgnoredRatherThanRefused() {
        // §13.1.4: "A recipient MUST ignore the If-Unmodified-Since header field if the
        // received field value is not a valid HTTP-date."
        String path = seed(unique("/t25-baddate-%d.ttl"));

        putTurtle(path, TURTLE_UPDATED)
                .header(HttpHeaders.IF_UNMODIFIED_SINCE, "not-a-date")
                .exchange().expectStatus().isNoContent();
    }

    // ================================================================ regressions

    @Test
    void aWriteWithNoContentTypeIsStillA400EvenWithAFailingPrecondition() {
        // §13.2.1: preconditions are evaluated "after it has successfully performed its normal
        // request checks". Solid Protocol §2.1's 400 is such a check, so it outranks the 412.
        String path = seed(unique("/t25-no-ct-%d.ttl"));

        expectNoStoreMutation(() -> client.put().uri(URI.create(path))
                .header(HttpHeaders.IF_MATCH, STALE_ETAG)
                .bodyValue(TURTLE.getBytes(StandardCharsets.UTF_8))
                .exchange().expectStatus().isBadRequest());
    }

    @Test
    void theSealCatchesAWriteSoEveryNoMutationProofAboveIsNonVacuous() {
        // Guards the guard. If whileSealed did not actually intercept, every
        // expectNoStoreMutation assertion in this class would pass for the wrong reason — the
        // most dangerous kind of green. An unconditional PUT under the seal must be recorded
        // and must fail, which is precisely what a precondition regression would look like.
        String path = unique("/t25-seal-selftest-%d.ttl");

        store.whileSealed(() -> putTurtle(path, TURTLE)
                .exchange().expectStatus().is5xxServerError());

        assertEquals(1, store.recordedMutations().size(),
                "the seal must observe and refuse a write that reaches the store");
        get(path).exchange().expectStatus().isNotFound();
    }

    @Test
    void unconditionalRequestsAreUnchanged() {
        // The whole feature is inert unless a conditional field is present.
        String path = unique("/t25-unconditional-%d.ttl");

        putTurtle(path, TURTLE).exchange().expectStatus().isCreated();
        putTurtle(path, TURTLE_UPDATED).exchange().expectStatus().isNoContent();
        get(path).exchange().expectStatus().isOk();
        client.delete().uri(URI.create(path)).exchange().expectStatus().isNoContent();
    }
}
