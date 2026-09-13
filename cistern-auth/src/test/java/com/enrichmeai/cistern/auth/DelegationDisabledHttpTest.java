package com.enrichmeai.cistern.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.enrichmeai.cistern.core.Representation;
import com.enrichmeai.cistern.core.ResourceIdentifier;
import com.enrichmeai.cistern.core.ResourceStore;
import com.enrichmeai.cistern.core.vocab.Cistern;
import com.enrichmeai.cistern.wac.AccessMode;
import com.enrichmeai.cistern.wac.DecisionField;
import com.enrichmeai.cistern.wac.DecisionRecord;
import com.enrichmeai.cistern.wac.DecisionRecordJson;
import com.enrichmeai.cistern.wac.Outcome;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterAll;
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
 * The same ACL, the same tokens, the flag left at its default (T6.5; AD-16): the
 * {@code cistern:client} term is not read, alice reads through any client, and every receipt is
 * one the pre-delegation server would have written — no {@code narrowedBy} member on the line.
 *
 * <p>This is the invariance evidence the conformance harness cannot yet supply for WAC (its run
 * halts before the WAC suite; 0/41 recorded honestly): a pod whose ACLs carry the extension,
 * with the flag off, behaves as plain WAC through the whole chain.
 */
@SpringBootTest(properties = {
    "cistern.base-url=" + DelegationDisabledHttpTest.BASE,
    "cistern.owner.web-id=" + DelegationDisabledHttpTest.OWNER,
    "cistern.owner.token=" + DelegationDisabledHttpTest.OWNER_TOKEN,
    "cistern.auth.oidc.issuer=http://localhost:18092/realms/cistern-delegation",
    "cistern.auth.oidc.audiences=cistern",
})
@AutoConfigureWebTestClient
class DelegationDisabledHttpTest {

    static final String BASE = "http://localhost:3000";
    static final String OWNER = "https://owner.example/profile/card#me";
    static final String OWNER_TOKEN = "owner-token-t65-off";
    private static final String KEYS_PATH = "/realms/cistern-delegation/protocol/openid-connect/certs";
    private static final String TURTLE = "text/turtle";
    private static final String NOTE = "/notes/week";

    private static final FixtureServer KEYS = new FixtureServer().serve(KEYS_PATH, DelegationFixtures.text("jwks.json"));
    private static final Path STORAGE_ROOT = createTempRoot();

    @DynamicPropertySource
    static void dynamicProperties(DynamicPropertyRegistry registry) {
        registry.add("cistern.auth.oidc.jwks-uri", () -> KEYS.uri(KEYS_PATH).toString());
        registry.add("cistern.storage.root", STORAGE_ROOT::toString);
    }

    @AfterAll
    static void stopKeys() {
        KEYS.close();
    }

    private static Path createTempRoot() {
        try {
            return Files.createTempDirectory("cistern-t65-delegation-off-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Autowired private WebTestClient client;
    @Autowired private ResourceStore store;

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
        put(NOTE, "<#w> <http://purl.org/dc/terms/title> \"Week\" .");
        put("/notes/.acl", """
                @prefix acl: <http://www.w3.org/ns/auth/acl#> .
                @prefix cistern: <%1$s> .
                <#owner> a acl:Authorization ;
                    acl:agent <%2$s> ;
                    acl:accessTo <%3$s/notes/> ;
                    acl:default <%3$s/notes/> ;
                    acl:mode acl:Read, acl:Write, acl:Append, acl:Control .
                <#alice-via-claude> a acl:Authorization ;
                    acl:agent <%4$s> ;
                    cistern:client <%5$s> ;
                    acl:accessTo <%3$s/notes/> ;
                    acl:default <%3$s/notes/> ;
                    acl:mode acl:Read .
                """.formatted(Cistern.NS, OWNER, BASE, DelegationFixtures.ALICE, DelegationFixtures.CLAUDE));
    }

    private void put(String path, String turtle) {
        store.put(new ResourceIdentifier(URI.create(BASE + path)),
                        new Representation(TURTLE, turtle.getBytes(StandardCharsets.UTF_8)))
                .block();
    }

    private WebTestClient.ResponseSpec getAs(String bearer, String path) {
        return client.get().uri(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer).exchange();
    }

    @Test
    @DisplayName("flag off: alice reads through either client, and every receipt is a plain-WAC receipt")
    void constraintIsNotRead() {
        getAs(DelegationFixtures.token("alice-via-claude"), NOTE).expectStatus().isOk();
        getAs(DelegationFixtures.token("alice-via-other"), NOTE).expectStatus().isOk();

        String body = getAs(OWNER_TOKEN, NOTE + "?receipts").expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();

        List<DecisionRecord> reads = body.lines()
                .map(DecisionRecordJson::parse)
                .map(Optional::orElseThrow)
                .filter(record -> record.required() == AccessMode.READ)
                .toList();
        assertEquals(2, reads.size(), body);
        for (DecisionRecord receipt : reads) {
            assertEquals(Outcome.ALLOWED, receipt.outcome());
            assertEquals(Optional.of(DelegationFixtures.ALICE), receipt.agent().webId());
            assertTrue(receipt.narrowedBy().isEmpty(), "nothing is ever narrowed with the flag off");
        }
        assertFalse(body.contains(DecisionField.NARROWED_BY.key()),
                "no line carries the member — the pre-delegation shape: " + body);
    }
}
