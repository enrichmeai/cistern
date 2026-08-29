package com.enrichmeai.cistern.webflux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.enrichmeai.cistern.core.Agent;
import com.enrichmeai.cistern.core.InMemoryResourceStore;
import com.enrichmeai.cistern.core.Representation;
import com.enrichmeai.cistern.core.ResourceIdentifier;
import com.enrichmeai.cistern.core.ResourceStore;
import com.enrichmeai.cistern.wac.AccessMode;
import com.enrichmeai.cistern.wac.DecisionLog;
import com.enrichmeai.cistern.wac.DecisionRecord;
import com.enrichmeai.cistern.wac.DecisionRecordJson;
import com.enrichmeai.cistern.wac.DecisionSink;
import com.enrichmeai.cistern.wac.JsonLinesDecisionSink;
import com.enrichmeai.cistern.wac.Outcome;
import com.enrichmeai.cistern.wac.RequestId;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Receipts over HTTP (T5.9): every request through {@code AuthorizationFilter} leaves exactly
 * one record, the record names its policy on allow and nothing on deny, and the log is read back
 * through {@code GET ?receipts} — by whoever holds Control, and by nobody else.
 *
 * <p>The sink is the real {@code JsonLinesDecisionSink} over the reference in-memory store,
 * wrapped so the tests can see what was handed to it; the query is the production
 * {@code JsonLinesDecisionQuery} over the same log. Nothing about the enforcement path is
 * substituted.
 */
@SpringBootTest(properties = {
    "cistern.base-url=" + ReceiptsHttpTest.BASE,
    "cistern.owner.web-id=" + ReceiptsHttpTest.OWNER,
    "cistern.owner.token=" + ReceiptsHttpTest.OWNER_TOKEN,
    "cistern.auth.service-principals[0].web-id=" + ReceiptsHttpTest.AGENT,
    "cistern.auth.service-principals[0].credential-hash="
            + "sha256:af9f6ca9c55937463513e4cb25829d6eaa89ca74ed5699c0690f13469da4c481",
    // The recording sink below replaces the production sink under the same bean name, and
    // the log is pointed at an in-memory store. Nothing else in the stack is substituted.
    "spring.main.allow-bean-definition-overriding=true",
})
@AutoConfigureWebTestClient
@Import(ReceiptsHttpTest.RecordingSinkConfiguration.class)
class ReceiptsHttpTest {

    static final String BASE = "http://localhost:3000";
    static final String OWNER = "https://alice.example/profile/card#me";
    static final String OWNER_TOKEN = "owner-token-t59";
    static final String AGENT = "https://valuedocs.co.in/apps/legal#id";
    private static final String AGENT_SECRET = "legal-secret-0f3c8b";
    /** The agent's WebID as a query value: the fragment must be escaped or it is the request's, not the value's. */
    private static final String AGENT_ENCODED = URLEncoder.encode(AGENT, StandardCharsets.UTF_8);
    private static final String TURTLE = "text/turtle";

    private static final Path STORAGE_ROOT = createTempRoot();
    private static final AtomicInteger UNIQUE = new AtomicInteger();

    /** One log for the whole context: the recording sink and the production query share it. */
    private static final DecisionLog LOG = DecisionLog.in(new InMemoryResourceStore());

    @Autowired private WebTestClient client;
    @Autowired private ResourceStore store;
    @Autowired private RecordingDecisionSink sink;

    @DynamicPropertySource
    static void storageRoot(DynamicPropertyRegistry registry) {
        registry.add("cistern.storage.root", STORAGE_ROOT::toString);
    }

    private static Path createTempRoot() {
        try {
            return Files.createTempDirectory("cistern-t59-receipts-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RecordingSinkConfiguration {

        @Bean
        DecisionLog decisionLog() {
            return LOG;
        }

        @Bean
        RecordingDecisionSink decisionSink(DecisionLog decisionLog) {
            return new RecordingDecisionSink(new JsonLinesDecisionSink(decisionLog));
        }
    }

    @BeforeEach
    void seed() {
        // Root: owner only, as OwnerPodSeeder would write it.
        put("/.acl", """
                @prefix acl: <http://www.w3.org/ns/auth/acl#> .
                <#owner> a acl:Authorization ;
                    acl:agent <%s> ;
                    acl:accessTo <%s/> ;
                    acl:default <%s/> ;
                    acl:mode acl:Read, acl:Write, acl:Append, acl:Control .
                """.formatted(OWNER, BASE, BASE));
        sink.clear();
    }

    // ---------------------------------------------------------------- helpers

    private static String unique(String template) {
        return template.formatted(UNIQUE.incrementAndGet());
    }

    /** Write straight to the store, bypassing HTTP — fixtures must not depend on enforcement. */
    private void put(String path, String turtle) {
        store.put(new ResourceIdentifier(URI.create(BASE + path)),
                        new Representation(TURTLE, turtle.getBytes(StandardCharsets.UTF_8)))
                .block();
    }

    /** {@code /notes-N/.acl}: owner full, the public may Read, the agent may Read. */
    private void grantReadOn(String container) {
        put(container + ".acl", """
                @prefix acl: <http://www.w3.org/ns/auth/acl#> .
                @prefix foaf: <http://xmlns.com/foaf/0.1/> .
                <#owner> a acl:Authorization ;
                    acl:agent <%1$s> ;
                    acl:accessTo <%2$s%3$s> ; acl:default <%2$s%3$s> ;
                    acl:mode acl:Read, acl:Write, acl:Append, acl:Control .
                <#agent> a acl:Authorization ;
                    acl:agentClass foaf:Agent ;
                    acl:accessTo <%2$s%3$s> ; acl:default <%2$s%3$s> ;
                    acl:mode acl:Read .
                """.formatted(OWNER, BASE, container));
    }

    private WebTestClient.RequestHeadersSpec<?> asOwner(WebTestClient.RequestHeadersSpec<?> spec) {
        return spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + OWNER_TOKEN);
    }

    private WebTestClient.RequestHeadersSpec<?> asAgent(WebTestClient.RequestHeadersSpec<?> spec) {
        return spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + AGENT_SECRET);
    }

    private static ResourceIdentifier id(String path) {
        return new ResourceIdentifier(URI.create(BASE + path));
    }

    /**
     * The receipts of {@code path}, as the owner, parsed back from the NDJSON body. Note that
     * the query is itself a decision (Control on the resource) and is recorded before the
     * handler reads the log, so its own receipt is the last line of every answer; tests that
     * count use {@link #withoutQueries} to look past that.
     */
    private List<DecisionRecord> receiptsAsOwner(String pathAndQuery) {
        String body = asOwner(client.get().uri(URI.create(pathAndQuery))).exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(ReceiptsHandler.NDJSON)
                .expectHeader().cacheControl(org.springframework.http.CacheControl.noStore())
                .expectBody(String.class).returnResult().getResponseBody();
        if (body == null || body.isEmpty()) {
            return List.of();
        }
        return List.of(body.split(DecisionLog.LINE_SEPARATOR)).stream()
                .map(line -> DecisionRecordJson.parse(line).orElseThrow(
                        () -> new AssertionError("not a record: " + line)))
                .toList();
    }

    /** The receipts that are not receipts queries — everything but the Control decisions. */
    private static List<DecisionRecord> withoutQueries(List<DecisionRecord> receipts) {
        return receipts.stream().filter(record -> record.required() != AccessMode.CONTROL).toList();
    }

    private DecisionRecord theOneRecord() {
        List<DecisionRecord> records = sink.records();
        assertEquals(1, records.size(), "exactly one record per request, got " + records);
        return records.getFirst();
    }

    // ---------------------------------------------------------------- one record per request

    @Nested
    @DisplayName("Every request through the filter yields exactly one record")
    class OneRecordPerRequest {

        @Test
        @DisplayName("an allowed GET: ALLOWED, Read, the owner, and the ACL that decided — the root's")
        void allowedGet() {
            String path = unique("/notes-%d/hello");
            put(path, "<#a> <#b> \"c\" .");
            sink.clear();

            asOwner(client.get().uri(path)).exchange().expectStatus().isOk();

            DecisionRecord record = theOneRecord();
            assertEquals(Outcome.ALLOWED, record.outcome());
            assertEquals(AccessMode.READ, record.required());
            assertEquals(id(path), record.target());
            assertEquals(Agent.of(URI.create(OWNER)), record.agent());
            assertEquals(Optional.of(id("/.acl")), record.decidedBy());
        }

        @Test
        @DisplayName("an anonymous 401: DENIED_UNAUTHENTICATED, anonymous, no policy")
        void anonymousDenied() {
            String path = unique("/notes-%d/hello");

            client.get().uri(path).exchange().expectStatus().isUnauthorized();

            DecisionRecord record = theOneRecord();
            assertEquals(Outcome.DENIED_UNAUTHENTICATED, record.outcome());
            assertEquals(Agent.ANONYMOUS, record.agent());
            assertTrue(record.decidedBy().isEmpty(), "a denial names no policy");
        }

        @Test
        @DisplayName("an authenticated 403: DENIED_FORBIDDEN, the agent, no policy")
        void authenticatedForbidden() {
            String path = unique("/notes-%d/hello");
            put(path, "<#a> <#b> \"c\" .");
            sink.clear();

            asAgent(client.get().uri(path)).exchange().expectStatus().isForbidden();

            DecisionRecord record = theOneRecord();
            assertEquals(Outcome.DENIED_FORBIDDEN, record.outcome());
            assertEquals(Agent.of(URI.create(AGENT)), record.agent());
            assertTrue(record.decidedBy().isEmpty());
        }

        @Test
        @DisplayName("PUT, PATCH, POST, DELETE, HEAD, OPTIONS: one record each, with the mode each requires")
        void everyMethod() {
            String container = unique("/notes-%d/");
            String path = container + "hello";

            asOwner(client.put().uri(path).header(HttpHeaders.CONTENT_TYPE, TURTLE).bodyValue("<#a> <#b> \"c\" ."))
                    .exchange().expectStatus().is2xxSuccessful();
            assertEquals(AccessMode.WRITE, theOneRecord().required());
            sink.clear();

            asOwner(client.head().uri(path)).exchange().expectStatus().isOk();
            assertEquals(AccessMode.READ, theOneRecord().required());
            sink.clear();

            asOwner(client.options().uri(path)).exchange().expectStatus().is2xxSuccessful();
            assertEquals(AccessMode.READ, theOneRecord().required());
            sink.clear();

            asOwner(client.patch().uri(path).header(HttpHeaders.CONTENT_TYPE, "text/n3")
                    .bodyValue("@prefix solid: <http://www.w3.org/ns/solid/terms#>.\n"
                            + "_:add a solid:InsertDeletePatch;\n"
                            + "  solid:inserts { <https://vocab.example/s> <https://vocab.example/p> \"o\". }."))
                    .exchange().expectStatus().is2xxSuccessful();
            assertEquals(AccessMode.APPEND, theOneRecord().required());
            sink.clear();

            asOwner(client.post().uri(container).header(HttpHeaders.CONTENT_TYPE, TURTLE)
                    .header("Slug", "posted").bodyValue("<#a> <#b> \"c\" ."))
                    .exchange().expectStatus().is2xxSuccessful();
            assertEquals(AccessMode.APPEND, theOneRecord().required());
            sink.clear();

            // DELETE has two requirements (resource and parent) and still exactly one record,
            // describing the resource.
            asOwner(client.delete().uri(path)).exchange().expectStatus().is2xxSuccessful();
            DecisionRecord delete = theOneRecord();
            assertEquals(AccessMode.WRITE, delete.required());
            assertEquals(id(path), delete.target());
        }

        @Test
        @DisplayName("a request that is allowed and then fails in the handler (404) is still one ALLOWED record")
        void allowedThenNotFound() {
            String path = unique("/notes-%d/missing");

            asOwner(client.get().uri(path)).exchange().expectStatus().isNotFound();

            assertEquals(Outcome.ALLOWED, theOneRecord().outcome());
        }

        @Test
        @DisplayName("a public grant: the anonymous read is ALLOWED and names the container's ACL")
        void publicGrantNamesTheContainerAcl() {
            String container = unique("/notes-%d/");
            put(container + "week", "<#n> <#t> \"Weekly notes\" .");
            grantReadOn(container);
            sink.clear();

            client.get().uri(container + "week").exchange().expectStatus().isOk();

            DecisionRecord record = theOneRecord();
            assertEquals(Outcome.ALLOWED, record.outcome());
            assertEquals(Agent.ANONYMOUS, record.agent());
            assertEquals(Optional.of(id(container + ".acl")), record.decidedBy());
        }

        @Test
        @DisplayName("a CORS preflight is not a decision and leaves no record")
        void preflightIsNotRecorded() {
            // The status is CorsWebFilter's business and is asserted over a raw socket in
            // CorsHttpTest (WebTestClient cannot be trusted for CORS here); what matters to
            // this ticket is that the preflight never reached the decision point.
            client.options().uri(unique("/notes-%d/hello"))
                    .header(HttpHeaders.ORIGIN, "https://app.example")
                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.PUT.name())
                    .exchange();

            assertTrue(sink.records().isEmpty(), "no decision was taken, so nothing to record");
        }
    }

    // ---------------------------------------------------------------- the query

    @Nested
    @DisplayName("GET ?receipts — the demo's fourth beat")
    class Query {

        @Test
        @DisplayName("the owner sees the resource's receipts: the allow names the ACL, the denies name none")
        void ownerSeesReceipts() {
            String container = unique("/notes-%d/");
            String note = container + "week";
            put(note, "<#n> <#t> \"Weekly notes\" .");

            // Beat 2: no grant, refused.
            client.get().uri(note).exchange().expectStatus().isUnauthorized();
            // Beat 3: the rule.
            grantReadOn(container);
            // Beat 4: inside the grant, and outside it.
            client.get().uri(note).exchange().expectStatus().isOk();
            client.delete().uri(note).exchange().expectStatus().isUnauthorized();
            // Beat 5: revoked; the very next request.
            store.delete(id(container + ".acl")).block();
            client.get().uri(note).exchange().expectStatus().isUnauthorized();

            List<DecisionRecord> all = receiptsAsOwner(note + "?receipts");
            DecisionRecord last = all.getLast();
            assertEquals(AccessMode.CONTROL, last.required(), "the query itself is the last receipt");
            assertEquals(Outcome.ALLOWED, last.outcome());
            List<DecisionRecord> receipts = withoutQueries(all);

            assertEquals(4, receipts.size(), receipts.toString());
            assertEquals(List.of(Outcome.DENIED_UNAUTHENTICATED, Outcome.ALLOWED,
                            Outcome.DENIED_UNAUTHENTICATED, Outcome.DENIED_UNAUTHENTICATED),
                    receipts.stream().map(DecisionRecord::outcome).toList(), "in the order taken");
            assertEquals(Optional.of(id(container + ".acl")), receipts.get(1).decidedBy(),
                    "the allow names the policy that granted it");
            assertEquals(AccessMode.WRITE, receipts.get(2).required(), "the DELETE needed Write");
            for (DecisionRecord denied : List.of(receipts.get(0), receipts.get(2), receipts.get(3))) {
                assertTrue(denied.decidedBy().isEmpty(), "a denial names no policy: " + denied);
            }
        }

        @Test
        @DisplayName("receipts require Control: anonymous is 401, the agent whose access is reported is 403")
        void receiptsRequireControl() {
            String container = unique("/notes-%d/");
            String note = container + "week";
            put(note, "<#n> <#t> \"Weekly notes\" .");
            grantReadOn(container);
            asAgent(client.get().uri(note)).exchange().expectStatus().isOk();

            client.get().uri(note + "?receipts").exchange().expectStatus().isUnauthorized();
            asAgent(client.get().uri(note + "?receipts")).exchange().expectStatus().isForbidden();
            asOwner(client.get().uri(note + "?receipts")).exchange().expectStatus().isOk();
        }

        @Test
        @DisplayName("the receipts query is itself a decision: recorded, requiring Control")
        void receiptsQueryIsRecorded() {
            String note = unique("/notes-%d/hello");
            sink.clear();

            asOwner(client.get().uri(note + "?receipts")).exchange().expectStatus().isOk();

            DecisionRecord record = theOneRecord();
            assertEquals(AccessMode.CONTROL, record.required());
            assertEquals(Outcome.ALLOWED, record.outcome());
        }

        @Test
        @DisplayName("receipts exist for a resource that never did: a refused PUT is a receipt")
        void receiptsForANonexistentResource() {
            String never = unique("/never-%d/created");

            client.put().uri(never).header(HttpHeaders.CONTENT_TYPE, TURTLE).bodyValue("<#a> <#b> \"c\" .")
                    .exchange().expectStatus().isUnauthorized();

            List<DecisionRecord> receipts = withoutQueries(receiptsAsOwner(never + "?receipts"));
            assertEquals(1, receipts.size());
            assertEquals(Outcome.DENIED_UNAUTHENTICATED, receipts.getFirst().outcome());
            assertEquals(AccessMode.WRITE, receipts.getFirst().required());
        }

        @Test
        @DisplayName("from/to narrow the window; a malformed bound is 400; an empty window is 400")
        void interval() {
            String note = unique("/notes-%d/hello");
            put(note, "<#a> <#b> \"c\" .");
            asOwner(client.get().uri(note)).exchange().expectStatus().isOk();
            Instant now = Instant.now();

            assertEquals(1, withoutQueries(receiptsAsOwner(note + "?receipts&from=" + now.minusSeconds(60))).size());
            assertEquals(0, withoutQueries(receiptsAsOwner(note + "?receipts&from=" + now.plusSeconds(60))).size());
            assertEquals(1, withoutQueries(receiptsAsOwner(note + "?receipts&to=" + now.plusSeconds(60))).size());
            assertEquals(0, withoutQueries(receiptsAsOwner(note + "?receipts&to=" + now.minusSeconds(60))).size());

            asOwner(client.get().uri(URI.create(note + "?receipts&from=yesterday"))).exchange()
                    .expectStatus().isBadRequest();
            asOwner(client.get().uri(URI.create(note + "?receipts&from=" + now + "&to=" + now.minusSeconds(1)))).exchange()
                    .expectStatus().isBadRequest();
        }

        @Test
        @DisplayName("the per-agent query at the root: everything one agent did, anywhere, for the owner")
        void perAgentAtTheRoot() {
            String container = unique("/matters-%d/");
            put(container + "brief", "<#b> <#t> \"Brief\" .");
            put(container + "other", "<#o> <#t> \"Other\" .");
            grantReadOn(container);
            asAgent(client.get().uri(container + "brief")).exchange().expectStatus().isOk();
            asAgent(client.get().uri(container + "other")).exchange().expectStatus().isOk();
            asOwner(client.get().uri(container + "brief")).exchange().expectStatus().isOk();

            List<DecisionRecord> agents = receiptsAsOwner("/?receipts&agent=" + AGENT_ENCODED).stream()
                    .filter(record -> record.target().uri().getPath().startsWith(container))
                    .toList();

            assertEquals(2, agents.size(), agents.toString());
            assertTrue(agents.stream().allMatch(record -> record.agent().equals(Agent.of(URI.create(AGENT)))));
            assertEquals(List.of(id(container + "brief"), id(container + "other")),
                    agents.stream().map(DecisionRecord::target).toList());

            // The agent may not ask that question: Control on the root is the owner's.
            asAgent(client.get().uri(URI.create("/?receipts&agent=" + AGENT_ENCODED))).exchange().expectStatus().isForbidden();
            // And a malformed WebID is the owner's 400.
            asOwner(client.get().uri(URI.create("/?receipts&agent=not-a-uri"))).exchange().expectStatus().isBadRequest();
        }

        @Test
        @DisplayName("agent= on a resource narrows that resource's receipts to one agent")
        void perAgentOnAResource() {
            String container = unique("/matters-%d/");
            String brief = container + "brief";
            put(brief, "<#b> <#t> \"Brief\" .");
            grantReadOn(container);
            asAgent(client.get().uri(brief)).exchange().expectStatus().isOk();
            client.get().uri(brief).exchange().expectStatus().isOk();

            assertEquals(2, withoutQueries(receiptsAsOwner(brief + "?receipts")).size());
            List<DecisionRecord> agentOnly = withoutQueries(receiptsAsOwner(brief + "?receipts&agent=" + AGENT_ENCODED));
            assertEquals(1, agentOnly.size());
            assertEquals(Agent.of(URI.create(AGENT)), agentOnly.getFirst().agent());
        }

        @Test
        @DisplayName("without ?receipts a GET is a read; the query string is not part of the resource")
        void plainGetIsARead() {
            String note = unique("/notes-%d/hello");
            put(note, "<#a> <#b> \"c\" .");

            asOwner(client.get().uri(note + "?other=1")).exchange()
                    .expectStatus().isOk()
                    .expectHeader().contentTypeCompatibleWith(MediaType.parseMediaType(TURTLE));
        }
    }

    // ---------------------------------------------------------------- correlation

    @Nested
    @DisplayName("X-Request-Id: honoured, minted, echoed, recorded")
    class Correlation {

        @Test
        @DisplayName("a well-formed client id is echoed and written into the receipt")
        void clientIdIsHonoured() {
            String note = unique("/notes-%d/hello");
            String requestId = "app-trace-" + UNIQUE.incrementAndGet();

            client.get().uri(note).header(HttpConstants.X_REQUEST_ID, requestId).exchange()
                    .expectStatus().isUnauthorized()
                    .expectHeader().valueEquals(HttpConstants.X_REQUEST_ID, requestId);

            assertEquals(new RequestId(requestId), theOneRecord().requestId());
        }

        @Test
        @DisplayName("no client id: one is minted, echoed on the response and written into the receipt")
        void idIsMinted() {
            String note = unique("/notes-%d/hello");

            String echoed = client.get().uri(note).exchange()
                    .expectStatus().isUnauthorized()
                    .expectHeader().exists(HttpConstants.X_REQUEST_ID)
                    .returnResult(Void.class).getResponseHeaders().getFirst(HttpConstants.X_REQUEST_ID);

            assertNotNull(echoed);
            assertEquals(new RequestId(echoed), theOneRecord().requestId());
        }

        @Test
        @DisplayName("a malformed client id is not an error — it is replaced")
        void malformedIdIsReplaced() {
            String note = unique("/notes-%d/hello");

            String echoed = client.get().uri(note).header(HttpConstants.X_REQUEST_ID, "has spaces in it").exchange()
                    .expectStatus().isUnauthorized()
                    .returnResult(Void.class).getResponseHeaders().getFirst(HttpConstants.X_REQUEST_ID);

            assertNotNull(echoed);
            assertNotEquals("has spaces in it", echoed);
            assertTrue(RequestId.parse(echoed).isPresent());
        }

        @Test
        @DisplayName("the id is on an allowed response and on an error-mapped one alike")
        void idOnEveryResponse() {
            String note = unique("/notes-%d/hello");
            put(note, "<#a> <#b> \"c\" .");

            asOwner(client.get().uri(note)).header(HttpConstants.X_REQUEST_ID, "ok-1").exchange()
                    .expectStatus().isOk()
                    .expectHeader().valueEquals(HttpConstants.X_REQUEST_ID, "ok-1");
            asOwner(client.get().uri(unique("/notes-%d/missing"))).header(HttpConstants.X_REQUEST_ID, "nf-1").exchange()
                    .expectStatus().isNotFound()
                    .expectHeader().valueEquals(HttpConstants.X_REQUEST_ID, "nf-1");
        }
    }

    @Test
    @DisplayName("the response headers a receipts query adds are all in the CORS expose list")
    void newHeadersAreExposed() {
        assertTrue(ExposedResponseHeader.fieldNames().contains(HttpConstants.X_REQUEST_ID));
        assertTrue(ExposedResponseHeader.fieldNames().contains(HttpHeaders.CACHE_CONTROL));
        // Request-side allowance needs no list entry: the preflight grant echoes whatever a
        // script requests (CorsHttpTest.anArbitraryHeaderIsEchoed pins that).
    }
}
