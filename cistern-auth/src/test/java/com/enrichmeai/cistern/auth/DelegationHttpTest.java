package com.enrichmeai.cistern.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.enrichmeai.cistern.core.Representation;
import com.enrichmeai.cistern.core.ResourceIdentifier;
import com.enrichmeai.cistern.core.ResourceStore;
import com.enrichmeai.cistern.core.vocab.Cistern;
import com.enrichmeai.cistern.wac.AccessMode;
import com.enrichmeai.cistern.wac.DecisionField;
import com.enrichmeai.cistern.wac.DecisionRecord;
import com.enrichmeai.cistern.wac.DecisionRecordJson;
import com.enrichmeai.cistern.wac.DelegationTerm;
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
 * The (person, client) principal through the whole chain (T6.5, #119): a real Keycloak token
 * ({@code fixtures/keycloak-delegation}) whose client is a URI, resolved by
 * {@link OidcJwtPrincipalResolver}, decided by {@code WacEngine} with
 * {@code cistern.wac.delegation.enabled=true}, and the receipt {@code AuthorizationFilter}
 * leaves — read back over {@code GET ?receipts} through the real JSON Lines log, so the
 * serialization is exercised too rather than only the in-memory record.
 *
 * <p>Through the filter rather than at a handler, deliberately: the client rides on the agent
 * the filter publishes, and the narrowing term is written by the filter, so a handler-level
 * test could pass with either half broken (AD-18).
 *
 * <p>Each case uses a document of its own, because the receipts of a resource accumulate for
 * the life of the class and a shared target would make one case's assertions depend on which
 * others had run.
 */
@SpringBootTest(properties = {
    "cistern.base-url=" + DelegationHttpTest.BASE,
    "cistern.owner.web-id=" + DelegationHttpTest.OWNER,
    "cistern.owner.token=" + DelegationHttpTest.OWNER_TOKEN,
    "cistern.auth.oidc.issuer=http://localhost:18092/realms/cistern-delegation",
    "cistern.auth.oidc.audiences=cistern",
    "cistern.wac.delegation.enabled=true",
})
@AutoConfigureWebTestClient
class DelegationHttpTest {

    static final String BASE = "http://localhost:3000";
    static final String OWNER = "https://owner.example/profile/card#me";
    static final String OWNER_TOKEN = "owner-token-t65";
    private static final String KEYS_PATH = "/realms/cistern-delegation/protocol/openid-connect/certs";
    private static final String TURTLE = "text/turtle";
    private static final String NOTES = "/notes/";
    private static final String RECEIPTS = "?receipts";

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
            return Files.createTempDirectory("cistern-t65-delegation-");
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
        // Alice may read the notes — but only through the claude client. Exactly what
        // `cistern grant <alice> --read --client <claude> /notes/` writes.
        put(NOTES + ".acl", """
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

    /** A document of this case's own, seeded straight to the store (a fixture must not depend on enforcement). */
    private String note(String name) {
        String path = NOTES + name;
        put(path, "<#w> <http://purl.org/dc/terms/title> \"Week\" .");
        return path;
    }

    private void put(String path, String turtle) {
        store.put(new ResourceIdentifier(URI.create(BASE + path)),
                        new Representation(TURTLE, turtle.getBytes(StandardCharsets.UTF_8)))
                .block();
    }

    private WebTestClient.ResponseSpec getAs(String bearer, String path) {
        return client.get().uri(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer).exchange();
    }

    /** The receipts of {@code path} as the owner reads them, raw. */
    private String receiptLines(String path) {
        return getAs(OWNER_TOKEN, path + RECEIPTS).expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
    }

    /** The receipts of {@code path} left by requests other than the receipts query itself. */
    private List<DecisionRecord> receiptsFor(String path) {
        return receiptLines(path).lines()
                .map(DecisionRecordJson::parse)
                .map(Optional::orElseThrow)
                .filter(record -> record.required() != AccessMode.CONTROL)
                .toList();
    }

    private DecisionRecord onlyReceipt(String path) {
        List<DecisionRecord> records = receiptsFor(path);
        assertEquals(1, records.size(), records.toString());
        return records.getFirst();
    }

    @Test
    @DisplayName("alice via claude reads: allowed, WAC-Allow says read, and the receipt names no term")
    void aliceViaClaudeReads() {
        String note = note("via-claude");

        getAs(DelegationFixtures.token("alice-via-claude"), note).expectStatus().isOk()
                .expectHeader().value("WAC-Allow", value -> assertTrue(value.contains("user=\"read\""), value));

        DecisionRecord receipt = onlyReceipt(note);
        assertEquals(Outcome.ALLOWED, receipt.outcome());
        assertEquals(Optional.of(DelegationFixtures.ALICE), receipt.agent().webId());
        assertTrue(receipt.decidedBy().isPresent());
        assertTrue(receipt.narrowedBy().isEmpty(), "nothing was capped");
    }

    @Test
    @DisplayName("alice via another client is refused — 403, authenticated — and the receipt says the delegation capped it")
    void aliceViaOtherIsCapped() {
        String note = note("via-other");

        getAs(DelegationFixtures.token("alice-via-other"), note).expectStatus().isForbidden();

        DecisionRecord receipt = onlyReceipt(note);
        assertEquals(Outcome.DENIED_FORBIDDEN, receipt.outcome());
        assertEquals(Optional.of(DelegationFixtures.ALICE), receipt.agent().webId());
        assertEquals(Optional.of(DelegationTerm.CLIENT), receipt.narrowedBy(),
                "'the delegation capped it', not 'she never had it'");
        assertTrue(receipt.decidedBy().isEmpty(), "a denial names no policy");
    }

    @Test
    @DisplayName("the claude application as itself is another WebID with no grant: 403, and the receipt names no term")
    void claudeAsItselfNeverHadIt() {
        String note = note("claude-self");

        getAs(DelegationFixtures.token("claude-self"), note).expectStatus().isForbidden();

        DecisionRecord receipt = onlyReceipt(note);
        assertEquals(Outcome.DENIED_FORBIDDEN, receipt.outcome());
        assertEquals(Optional.of(DelegationFixtures.CLAUDE), receipt.agent().webId());
        assertTrue(receipt.narrowedBy().isEmpty(), "'never had it': matching the client is not a way in");
    }

    @Test
    @DisplayName("alice cannot write via claude either: the grant was Read, and the cap is not what refused")
    void readIsNotWrite() {
        String note = note("write-attempt");

        client.put().uri(note)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + DelegationFixtures.token("alice-via-claude"))
                .header(HttpHeaders.CONTENT_TYPE, TURTLE)
                .bodyValue("<#x> <#y> \"z\" .")
                .exchange().expectStatus().isForbidden();

        assertTrue(onlyReceipt(note).narrowedBy().isEmpty(), "she never had Write, through any client");
    }

    @Test
    @DisplayName("the term is on the wire, only on the capped line, and last")
    void theTermIsOnTheWire() {
        String note = note("both");

        getAs(DelegationFixtures.token("alice-via-claude"), note).expectStatus().isOk();
        getAs(DelegationFixtures.token("alice-via-other"), note).expectStatus().isForbidden();

        List<String> lines = receiptLines(note).lines()
                .filter(line -> line.contains(DelegationFixtures.ALICE.toString()))
                .toList();

        assertEquals(2, lines.size(), lines.toString());
        assertTrue(lines.get(0).contains("\"" + Outcome.ALLOWED.name() + "\"")
                        && !lines.get(0).contains(DecisionField.NARROWED_BY.key()),
                "the allowed line is a pre-delegation line: " + lines.get(0));
        assertTrue(lines.get(1).contains("\"" + Outcome.DENIED_FORBIDDEN.name() + "\"")
                        && lines.get(1).endsWith(
                                "\"" + DecisionField.NARROWED_BY.key() + "\":\"" + DelegationTerm.CLIENT.name() + "\"}"),
                "the capped line names the term, last: " + lines.get(1));
    }

    @Test
    @DisplayName("the owner is unaffected: no rule constrains them")
    void ownerIsUnaffected() {
        String note = note("owner");

        getAs(OWNER_TOKEN, note).expectStatus().isOk();

        assertTrue(onlyReceipt(note).narrowedBy().isEmpty());
    }
}
