package com.enrichmeai.cistern.webflux;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.enrichmeai.cistern.core.Representation;
import com.enrichmeai.cistern.core.ResourceIdentifier;
import com.enrichmeai.cistern.core.ResourceStore;
import com.enrichmeai.cistern.wac.DecisionLog;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * The {@code .cistern/} exposure decision (T5.9), pinned against the production wiring — the
 * file backend for the pod <em>and</em> for the log, no test doubles.
 *
 * <p>The log lives at {@code <storage root>/.cistern/decisions/YYYY-MM-DD.jsonl} on disk, and
 * is <strong>not pod content</strong>: the root container does not list it, and no HTTP path
 * reaches it — even the owner, who can read everything in the pod, gets 404 for
 * {@code /.cistern/…} because a leading dot in a client path segment is encoded ({@code %2E})
 * by the file backend and therefore names a different, empty place. The only way in is
 * {@code ?receipts}.
 */
@SpringBootTest(properties = {
    "cistern.base-url=" + DecisionLogExposureHttpTest.BASE,
    "cistern.owner.web-id=" + DecisionLogExposureHttpTest.OWNER,
    "cistern.owner.token=" + DecisionLogExposureHttpTest.TOKEN,
})
@AutoConfigureWebTestClient
class DecisionLogExposureHttpTest {

    static final String BASE = "http://localhost:3000";
    static final String OWNER = "https://alice.example/profile/card#me";
    static final String TOKEN = "owner-token-t59-exposure";
    private static final Path STORAGE_ROOT = createTempRoot();

    @Autowired private WebTestClient client;
    @Autowired private ResourceStore store;

    @DynamicPropertySource
    static void storageRoot(DynamicPropertyRegistry registry) {
        registry.add("cistern.storage.root", STORAGE_ROOT::toString);
    }

    private static Path createTempRoot() {
        try {
            return Files.createTempDirectory("cistern-t59-exposure-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
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
    }

    private WebTestClient.ResponseSpec asOwner(String path) {
        return client.get().uri(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN).exchange();
    }

    @Test
    @DisplayName("the log is on disk under <storage root>/.cistern/decisions/, is not listed by /, and no HTTP path reaches it")
    void logIsOnDiskButNotInThePod() {
        // One decision, so today's file exists.
        asOwner("/notes/hello").expectStatus().isOk();

        String today = DateTimeFormatter.ISO_LOCAL_DATE.format(DecisionLog.dayOf(Instant.now()));
        Path dayFile = STORAGE_ROOT.resolve(CisternProperties.Audit.DEFAULT_DIRECTORY)
                .resolve(DecisionLog.DECISIONS_SEGMENT).resolve(today + DecisionLog.FILE_SUFFIX);
        assertTrue(Files.isRegularFile(dayFile), "the day file is where the docs say: " + dayFile);

        // The root container lists notes/ and .acl, and nothing about the log.
        String root = asOwner("/").expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
        assertTrue(root.contains("/notes/"), root);
        assertFalse(root.contains(".cistern"), "the log is not pod content: " + root);

        // The owner can read everything in the pod, and still cannot reach the log by path.
        asOwner("/.cistern/").expectStatus().isNotFound();
        asOwner("/.cistern/decisions/").expectStatus().isNotFound();
        asOwner("/.cistern/decisions/" + today + DecisionLog.FILE_SUFFIX).expectStatus().isNotFound();

        // And the query surface does reach it.
        asOwner("/notes/hello?receipts").expectStatus().isOk()
                .expectHeader().contentType(ReceiptsHandler.NDJSON);
    }
}
