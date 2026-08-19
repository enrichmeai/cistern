package com.enrichmeai.cistern.webflux;

import com.enrichmeai.cistern.core.Representation;
import com.enrichmeai.cistern.core.ResourceIdentifier;
import com.enrichmeai.cistern.core.ResourceStore;
import com.enrichmeai.cistern.wac.DecisionRecord;
import com.enrichmeai.cistern.wac.DecisionSink;
import com.enrichmeai.cistern.webflux.error.ProblemDocument;
import com.enrichmeai.cistern.webflux.error.ProblemType;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code cistern.audit.required=true} (T5.9): a decision that cannot be recorded is not acted
 * on. The sink here always fails; every request — allowed or refused — is answered 503 through
 * the one error mapper, with the request id still on the response.
 */
@SpringBootTest(properties = {
    "cistern.base-url=" + AuditRequiredHttpTest.BASE,
    "cistern.owner.web-id=" + AuditRequiredHttpTest.OWNER,
    "cistern.owner.token=" + AuditRequiredHttpTest.TOKEN,
    "cistern.audit.required=true",
    "spring.main.allow-bean-definition-overriding=true",
})
@AutoConfigureWebTestClient
class AuditRequiredHttpTest {

    static final String BASE = "http://localhost:3000";
    static final String OWNER = "https://alice.example/profile/card#me";
    static final String TOKEN = "owner-token-t59-required";
    private static final Path STORAGE_ROOT = createTempRoot();

    @Autowired private WebTestClient client;
    @Autowired private ResourceStore store;
    @Autowired private FailingDecisionSink sink;

    @DynamicPropertySource
    static void storageRoot(DynamicPropertyRegistry registry) {
        registry.add("cistern.storage.root", STORAGE_ROOT::toString);
    }

    private static Path createTempRoot() {
        try {
            return Files.createTempDirectory("cistern-t59-required-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A sink that records what it was asked and refuses every time — the disk is full, say. */
    static final class FailingDecisionSink implements DecisionSink {
        final List<DecisionRecord> asked = new CopyOnWriteArrayList<>();

        @Override
        public Mono<Void> record(DecisionRecord record) {
            asked.add(record);
            return Mono.error(new IllegalStateException("decision log unavailable (test)"));
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FailingSinkConfiguration {
        @Bean
        FailingDecisionSink decisionSink() {
            return new FailingDecisionSink();
        }
    }

    @BeforeEach
    void seed() {
        store.put(new ResourceIdentifier(URI.create(BASE + "/.acl")), new Representation("text/turtle", """
                @prefix acl: <http://www.w3.org/ns/auth/acl#> .
                <#owner> a acl:Authorization ;
                    acl:agent <%s> ;
                    acl:accessTo <%s/> ;
                    acl:default <%s/> ;
                    acl:mode acl:Read, acl:Write, acl:Append, acl:Control .
                """.formatted(OWNER, BASE, BASE).getBytes(StandardCharsets.UTF_8))).block();
        store.put(new ResourceIdentifier(URI.create(BASE + "/notes/hello")),
                new Representation("text/turtle", "<#a> <#b> \"c\" .".getBytes(StandardCharsets.UTF_8))).block();
        sink.asked.clear();
    }

    @Test
    @DisplayName("an ALLOWED decision that cannot be recorded fails closed: 503, problem+json, and the resource is not served")
    void allowedFailsClosed() {
        client.get().uri("/notes/hello")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                .header(HttpConstants.X_REQUEST_ID, "req-required-1")
                .exchange()
                .expectStatus().isEqualTo(ProblemType.SERVICE_UNAVAILABLE.status())
                .expectHeader().contentType(ProblemDocument.MEDIA_TYPE)
                .expectHeader().valueEquals(HttpConstants.X_REQUEST_ID, "req-required-1")
                .expectBody()
                .jsonPath("$.type").isEqualTo(ProblemType.SERVICE_UNAVAILABLE.uri().toString())
                .jsonPath("$.status").isEqualTo(ProblemType.SERVICE_UNAVAILABLE.status().value());

        assertEquals(1, sink.asked.size(), "the decision was taken and offered to the sink exactly once");
    }

    @Test
    @DisplayName("a DENIED decision that cannot be recorded is 503 too — not 401: the refusal is a decision as well")
    void deniedFailsClosed() {
        client.get().uri("/notes/hello").exchange()
                .expectStatus().isEqualTo(ProblemType.SERVICE_UNAVAILABLE.status())
                .expectHeader().exists(HttpConstants.X_REQUEST_ID);

        assertEquals(1, sink.asked.size());
    }

    @Test
    @DisplayName("a write that cannot be recorded does not happen")
    void writeFailsClosed() {
        client.put().uri("/notes/new")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                .header(HttpHeaders.CONTENT_TYPE, "text/turtle")
                .bodyValue("<#a> <#b> \"c\" .")
                .exchange()
                .expectStatus().isEqualTo(ProblemType.SERVICE_UNAVAILABLE.status());

        assertEquals(Boolean.FALSE, store.exists(new ResourceIdentifier(URI.create(BASE + "/notes/new"))).block());
    }
}
