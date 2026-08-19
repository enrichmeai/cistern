package com.enrichmeai.cistern.webflux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.enrichmeai.cistern.core.Agent;
import com.enrichmeai.cistern.core.InMemoryResourceStore;
import com.enrichmeai.cistern.core.Representation;
import com.enrichmeai.cistern.core.ResourceIdentifier;
import com.enrichmeai.cistern.core.ResourceStore;
import com.enrichmeai.cistern.wac.AccessMode;
import com.enrichmeai.cistern.wac.DecisionLog;
import com.enrichmeai.cistern.wac.DecisionRecord;
import com.enrichmeai.cistern.wac.DecisionSink;
import com.enrichmeai.cistern.wac.JsonLinesDecisionSink;
import com.enrichmeai.cistern.wac.Outcome;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * ACL resources over HTTP (#112): every method on a {@code .acl} requires {@code acl:Control}
 * on the resource it governs, and nothing weaker — a public Read on a container reads the
 * container's contents, not its rule.
 *
 * <p>The finding this pins: after {@code cistern grant public --read /trips/}, an anonymous
 * {@code GET /trips/.acl} returned 200 and disclosed who holds access. The same table also let
 * Write on the container replace or delete the container's ACL, which is not a write to a
 * document but a policy change (a deleted ACL falls the resource back to its parent's defaults).
 *
 * <p>Two principals besides the public: the owner (Control everywhere by the root's
 * {@code acl:default}) and a service principal that is granted exactly what each test says.
 * The sink is recorded, as in {@code ReceiptsHttpTest}, so the tests can also state where the
 * receipt for an ACL access lands: on the governed resource, with Control required — the same
 * shape as a receipts query, and unaffected by it.
 */
@SpringBootTest(properties = {
    "cistern.base-url=" + AclResourceAccessHttpTest.BASE,
    "cistern.owner.web-id=" + AclResourceAccessHttpTest.OWNER,
    "cistern.owner.token=" + AclResourceAccessHttpTest.OWNER_TOKEN,
    "cistern.auth.service-principals[0].web-id=" + AclResourceAccessHttpTest.AGENT,
    "cistern.auth.service-principals[0].credential-hash="
            + "sha256:af9f6ca9c55937463513e4cb25829d6eaa89ca74ed5699c0690f13469da4c481",
    // The recording sink below replaces the production sink under the same bean name; the log
    // is pointed at an in-memory store. Nothing in the enforcement path is substituted.
    "spring.main.allow-bean-definition-overriding=true",
})
@AutoConfigureWebTestClient
@Import(AclResourceAccessHttpTest.RecordingSinkConfiguration.class)
class AclResourceAccessHttpTest {

    static final String BASE = "http://localhost:3000";
    static final String OWNER = "https://alice.example/profile/card#me";
    static final String OWNER_TOKEN = "owner-token-112";
    static final String AGENT = "https://valuedocs.co.in/apps/legal#id";
    private static final String AGENT_SECRET = "legal-secret-0f3c8b";
    private static final String TURTLE = "text/turtle";
    private static final String N3 = "text/n3";

    private static final String TRIPS = "/trips/";
    private static final String TRIPS_ACL = TRIPS + ".acl";
    private static final String LISBON = TRIPS + "lisbon";
    private static final String LISBON_ACL = LISBON + ".acl";

    private static final Path STORAGE_ROOT = createTempRoot();
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
            return Files.createTempDirectory("cistern-112-acl-access-");
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

    /**
     * Root: owner only, as OwnerPodSeeder would write it. {@code /trips/}: the owner in full,
     * and — the finding's fixture — the public may Read, this container and everything inside.
     * Every test starts from here, whatever the previous one deleted.
     */
    @BeforeEach
    void seed() {
        put("/.acl", """
                @prefix acl: <http://www.w3.org/ns/auth/acl#> .
                <#owner> a acl:Authorization ;
                    acl:agent <%s> ;
                    acl:accessTo <%s/> ;
                    acl:default <%s/> ;
                    acl:mode acl:Read, acl:Write, acl:Append, acl:Control .
                """.formatted(OWNER, BASE, BASE));
        put(LISBON, "<#t> <http://purl.org/dc/terms/title> \"Lisbon, October\" .");
        store.exists(id(LISBON_ACL)).filter(Boolean::booleanValue)
                .flatMap(present -> store.delete(id(LISBON_ACL))).block();
        publicReadOnTrips();
        sink.clear();
    }

    /** {@code /trips/.acl}: owner full; {@code foaf:Agent} Read on the container and its members. */
    private void publicReadOnTrips() {
        put(TRIPS_ACL, """
                @prefix acl: <http://www.w3.org/ns/auth/acl#> .
                @prefix foaf: <http://xmlns.com/foaf/0.1/> .
                <#owner> a acl:Authorization ;
                    acl:agent <%1$s> ;
                    acl:accessTo <%2$s%3$s> ; acl:default <%2$s%3$s> ;
                    acl:mode acl:Read, acl:Write, acl:Append, acl:Control .
                <#public> a acl:Authorization ;
                    acl:agentClass foaf:Agent ;
                    acl:accessTo <%2$s%3$s> ; acl:default <%2$s%3$s> ;
                    acl:mode acl:Read .
                """.formatted(OWNER, BASE, TRIPS));
    }

    /** {@code /trips/.acl}: owner full; the agent may Read <em>and Write</em>, but not Control. */
    private void agentWriteOnTrips() {
        put(TRIPS_ACL, """
                @prefix acl: <http://www.w3.org/ns/auth/acl#> .
                <#owner> a acl:Authorization ;
                    acl:agent <%1$s> ;
                    acl:accessTo <%2$s%3$s> ; acl:default <%2$s%3$s> ;
                    acl:mode acl:Read, acl:Write, acl:Append, acl:Control .
                <#agent> a acl:Authorization ;
                    acl:agent <%4$s> ;
                    acl:accessTo <%2$s%3$s> ; acl:default <%2$s%3$s> ;
                    acl:mode acl:Read, acl:Write .
                """.formatted(OWNER, BASE, TRIPS, AGENT));
    }

    /** Write straight to the store, bypassing HTTP — fixtures must not depend on enforcement. */
    private void put(String path, String turtle) {
        store.put(id(path), new Representation(TURTLE, turtle.getBytes(StandardCharsets.UTF_8))).block();
    }

    private static ResourceIdentifier id(String path) {
        return new ResourceIdentifier(URI.create(BASE + path));
    }

    private WebTestClient.RequestHeadersSpec<?> asOwner(WebTestClient.RequestHeadersSpec<?> spec) {
        return spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + OWNER_TOKEN);
    }

    private WebTestClient.RequestHeadersSpec<?> asAgent(WebTestClient.RequestHeadersSpec<?> spec) {
        return spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + AGENT_SECRET);
    }

    private static final String REPLACEMENT_ACL = """
            @prefix acl: <http://www.w3.org/ns/auth/acl#> .
            <#owner> a acl:Authorization ;
                acl:agent <%1$s> ;
                acl:accessTo <%2$s%3$s> ; acl:default <%2$s%3$s> ;
                acl:mode acl:Read, acl:Write, acl:Append, acl:Control .
            """.formatted(OWNER, BASE, TRIPS);

    private static final String N3_INSERT = """
            @prefix solid: <http://www.w3.org/ns/solid/terms#> .
            _:p a solid:InsertDeletePatch ;
                solid:inserts { <https://vocab.example/s> <https://vocab.example/p> "o" . } .
            """;

    private DecisionRecord theOneRecord() {
        List<DecisionRecord> records = sink.records();
        assertEquals(1, records.size(), "exactly one record per request, got " + records);
        return records.getFirst();
    }

    // ---------------------------------------------------------------- reading the rule

    @Nested
    @DisplayName("Reading an ACL requires Control on the resource it governs")
    class Reading {

        @Test
        @DisplayName("the finding: public Read on /trips/ reads /trips/lisbon, and GET /trips/.acl is 401")
        void anonymousReadOfTheAclIsUnauthorized() {
            client.get().uri(LISBON).exchange().expectStatus().isOk();

            client.get().uri(TRIPS_ACL).exchange()
                    .expectStatus().isUnauthorized()
                    .expectHeader().exists(HttpHeaders.WWW_AUTHENTICATE);
        }

        @Test
        @DisplayName("an authenticated principal holding Read but not Control is 403")
        void authenticatedReaderIsForbidden() {
            asAgent(client.get().uri(LISBON)).exchange().expectStatus().isOk();

            asAgent(client.get().uri(TRIPS_ACL)).exchange().expectStatus().isForbidden();
        }

        @Test
        @DisplayName("the owner, holding Control, reads the ACL")
        void ownerReadsTheAcl() {
            asOwner(client.get().uri(TRIPS_ACL).header(HttpHeaders.ACCEPT, TURTLE)).exchange()
                    .expectStatus().isOk()
                    .expectBody(String.class).value(body -> assertTrue(
                            body.contains("Authorization") && body.contains("Agent"),
                            "the rule itself, prefixed or not: " + body));
        }

        @Test
        @DisplayName("HEAD and OPTIONS on the ACL are Control too — anonymous 401, non-controller 403")
        void headAndOptionsAreControl() {
            client.head().uri(TRIPS_ACL).exchange().expectStatus().isUnauthorized();
            client.options().uri(TRIPS_ACL).exchange().expectStatus().isUnauthorized();
            asAgent(client.head().uri(TRIPS_ACL)).exchange().expectStatus().isForbidden();
            asAgent(client.options().uri(TRIPS_ACL)).exchange().expectStatus().isForbidden();
            asOwner(client.head().uri(TRIPS_ACL)).exchange().expectStatus().isOk();
        }

        @Test
        @DisplayName("a document's ACL is judged by the document's own effective ACL, not the container's public grant")
        void documentAclIsGovernedByTheDocument() {
            // The document's own ACL exists and names only the owner: public Read on the
            // container reaches neither the document nor its rule any more.
            put(LISBON_ACL, """
                    @prefix acl: <http://www.w3.org/ns/auth/acl#> .
                    <#owner> a acl:Authorization ;
                        acl:agent <%s> ;
                        acl:accessTo <%s%s> ;
                        acl:mode acl:Read, acl:Write, acl:Control .
                    """.formatted(OWNER, BASE, LISBON));

            client.get().uri(LISBON_ACL).exchange().expectStatus().isUnauthorized();
            asAgent(client.get().uri(LISBON_ACL)).exchange().expectStatus().isForbidden();
            asOwner(client.get().uri(LISBON_ACL)).exchange().expectStatus().isOk();
        }
    }

    // ---------------------------------------------------------------- writing the rule

    @Nested
    @DisplayName("Writing or deleting an ACL requires Control — unchanged, and now not Write either")
    class Writing {

        @Test
        @DisplayName("PUT /trips/.acl: anonymous 401, non-controller 403, owner replaces it")
        void putRequiresControl() {
            client.put().uri(TRIPS_ACL).header(HttpHeaders.CONTENT_TYPE, TURTLE)
                    .bodyValue(REPLACEMENT_ACL).exchange().expectStatus().isUnauthorized();
            asAgent(client.put().uri(TRIPS_ACL).header(HttpHeaders.CONTENT_TYPE, TURTLE)
                    .bodyValue(REPLACEMENT_ACL)).exchange().expectStatus().isForbidden();

            asOwner(client.put().uri(TRIPS_ACL).header(HttpHeaders.CONTENT_TYPE, TURTLE)
                    .bodyValue(REPLACEMENT_ACL)).exchange().expectStatus().is2xxSuccessful();
            // The rule the owner wrote is in force on the very next request: the public grant is gone.
            client.get().uri(LISBON).exchange().expectStatus().isUnauthorized();
        }

        @Test
        @DisplayName("PATCH /trips/.acl: anonymous 401, non-controller 403")
        void patchRequiresControl() {
            client.patch().uri(TRIPS_ACL).header(HttpHeaders.CONTENT_TYPE, N3)
                    .bodyValue(N3_INSERT).exchange().expectStatus().isUnauthorized();
            asAgent(client.patch().uri(TRIPS_ACL).header(HttpHeaders.CONTENT_TYPE, N3)
                    .bodyValue(N3_INSERT)).exchange().expectStatus().isForbidden();
        }

        @Test
        @DisplayName("DELETE /trips/.acl: anonymous 401, non-controller 403, the owner revokes")
        void deleteRequiresControl() {
            client.delete().uri(TRIPS_ACL).exchange().expectStatus().isUnauthorized();
            asAgent(client.delete().uri(TRIPS_ACL)).exchange().expectStatus().isForbidden();
            // Neither refusal touched the rule.
            client.get().uri(LISBON).exchange().expectStatus().isOk();

            asOwner(client.delete().uri(TRIPS_ACL)).exchange().expectStatus().isNoContent();
            // The very next request: /trips/ has fallen back to the root's owner-only default.
            client.get().uri(LISBON).exchange().expectStatus().isUnauthorized();
            asAgent(client.get().uri(LISBON)).exchange().expectStatus().isForbidden();
        }

        @Test
        @DisplayName("Write on the container is not Control: the agent may write a trip, not the rule")
        void writeOnTheContainerDoesNotReachTheAcl() {
            agentWriteOnTrips();

            asAgent(client.put().uri(TRIPS + "porto").header(HttpHeaders.CONTENT_TYPE, TURTLE)
                    .bodyValue("<#t> <http://purl.org/dc/terms/title> \"Porto\" ."))
                    .exchange().expectStatus().is2xxSuccessful();

            // Before #112 the method table said PUT = Write and DELETE = Write on the target
            // and its parent — both of which this agent holds on /trips/. A non-controller
            // could rewrite the policy, or delete it and let /trips/ inherit its parent's.
            asAgent(client.put().uri(TRIPS_ACL).header(HttpHeaders.CONTENT_TYPE, TURTLE)
                    .bodyValue(REPLACEMENT_ACL)).exchange().expectStatus().isForbidden();
            asAgent(client.delete().uri(TRIPS_ACL)).exchange().expectStatus().isForbidden();
            asAgent(client.get().uri(TRIPS_ACL)).exchange().expectStatus().isForbidden();
        }
    }

    // ---------------------------------------------------------------- receipts

    @Nested
    @DisplayName("Receipts (T5.9) are unaffected, and an ACL access is a receipt on the governed resource")
    class Receipts {

        @Test
        @DisplayName("GET /trips/?receipts still requires Control on /trips/ — not on /trips/.acl")
        void receiptsRequireControlOnTheResource() {
            client.get().uri(TRIPS + "?receipts").exchange().expectStatus().isUnauthorized();
            asAgent(client.get().uri(TRIPS + "?receipts")).exchange().expectStatus().isForbidden();
            sink.clear();

            asOwner(client.get().uri(TRIPS + "?receipts")).exchange().expectStatus().isOk();

            DecisionRecord query = theOneRecord();
            assertEquals(id(TRIPS), query.target(), "the query is judged on the resource");
            assertEquals(AccessMode.CONTROL, query.required());
            assertEquals(Outcome.ALLOWED, query.outcome());
        }

        @Test
        @DisplayName("an ACL access is recorded on the governed resource, requiring Control")
        void aclAccessIsRecordedOnTheGovernedResource() {
            client.get().uri(TRIPS_ACL).exchange().expectStatus().isUnauthorized();
            DecisionRecord refused = theOneRecord();
            assertEquals(id(TRIPS), refused.target(), "the receipt names the resource whose policy was asked for");
            assertEquals(AccessMode.CONTROL, refused.required());
            assertEquals(Outcome.DENIED_UNAUTHENTICATED, refused.outcome());
            assertEquals(Agent.ANONYMOUS, refused.agent());
            assertTrue(refused.decidedBy().isEmpty(), "a denial names no policy");
            sink.clear();

            asOwner(client.get().uri(TRIPS_ACL)).exchange().expectStatus().isOk();
            DecisionRecord allowed = theOneRecord();
            assertEquals(id(TRIPS), allowed.target());
            assertEquals(AccessMode.CONTROL, allowed.required());
            assertEquals(Outcome.ALLOWED, allowed.outcome());
            assertEquals(Optional.of(id(TRIPS_ACL)), allowed.decidedBy(), "the ACL that granted Control on /trips/ is /trips/.acl itself");
        }

        @Test
        @DisplayName("a preflight for the ACL is still not a decision")
        void preflightIsNotADecision() {
            client.options().uri(TRIPS_ACL)
                    .header(HttpHeaders.ORIGIN, "https://app.example")
                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.PUT.name())
                    .exchange();

            assertTrue(sink.records().isEmpty(), "no decision was taken, so nothing to record");
        }
    }
}
