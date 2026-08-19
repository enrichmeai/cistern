package com.enrichmeai.cistern.webflux;

import com.enrichmeai.cistern.core.Representation;
import com.enrichmeai.cistern.core.ResourceIdentifier;
import com.enrichmeai.cistern.core.ResourceStore;
import com.enrichmeai.cistern.wac.DecisionRecord;
import com.enrichmeai.cistern.wac.DecisionSink;

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
 * {@code cistern.audit.required} unset — the default (T5.9): a sink failure never changes the
 * authorization outcome. The sink here always fails; the owner still gets 200 and the anonymous
 * caller still gets 401, exactly as if the log were healthy, and each decision was offered to
 * the sink exactly once.
 */
@SpringBootTest(properties = {
    "cistern.base-url=" + AuditOptionalHttpTest.BASE,
    "cistern.owner.web-id=" + AuditOptionalHttpTest.OWNER,
    "cistern.owner.token=" + AuditOptionalHttpTest.TOKEN,
    "spring.main.allow-bean-definition-overriding=true",
})
@AutoConfigureWebTestClient
class AuditOptionalHttpTest {

    static final String BASE = "http://localhost:3000";
    static final String OWNER = "https://alice.example/profile/card#me";
    static final String TOKEN = "owner-token-t59-optional";
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
            return Files.createTempDirectory("cistern-t59-optional-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

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
    @DisplayName("the owner's allowed read is still 200 when the receipt cannot be written")
    void allowedOutcomeUnchanged() {
        client.get().uri("/notes/hello")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists(HttpConstants.X_REQUEST_ID);

        assertEquals(1, sink.asked.size(), "offered to the sink exactly once");
    }

    @Test
    @DisplayName("the anonymous refusal is still 401 when the receipt cannot be written")
    void deniedOutcomeUnchanged() {
        client.get().uri("/notes/hello").exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().exists(HttpHeaders.WWW_AUTHENTICATE);

        assertEquals(1, sink.asked.size());
    }
}
