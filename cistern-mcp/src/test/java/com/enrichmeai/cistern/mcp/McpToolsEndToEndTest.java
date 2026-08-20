package com.enrichmeai.cistern.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.enrichmeai.cistern.core.ResourceIdentifier;
import com.enrichmeai.cistern.core.ResourceStore;
import com.enrichmeai.cistern.wac.DecisionField;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import reactor.test.StepVerifier;

/**
 * The MCP tools against the real server (ground rule 6): the WebFlux stack from
 * cistern-webflux is booted in-process with an owner and a service principal configured, so
 * enforcement is on, {@code OwnerPodSeeder} writes the root ACL, and every decision leaves a
 * receipt — exactly as a deployment would. Nothing on the wire is mocked.
 *
 * <p>The scenario is the flagship demo's four beats ({@code docs/demo/walkthrough.md}) as the
 * MCP layer must carry them: it works; <strong>the refusal</strong> (structured, naming the
 * resource and mode — never an empty success); <strong>live revocation</strong> (the very
 * next call refused, no restart); the receipt (naming the deciding ACL, Control-guarded).
 */
class McpToolsEndToEndTest {

    private static final String OWNER = "https://you.example/profile/card#me";
    private static final String TOKEN = "mcp-e2e-owner-token";
    /** The assistant's own identity: a service principal, per the #89 ruling. */
    private static final String CLAUDE = "https://connectors.example/claude#agent";
    private static final String CLAUDE_SECRET = "claude-connector-secret-2af8";
    /** {@code shasum -a 256} of {@link #CLAUDE_SECRET}, computed outside the JVM. */
    private static final String CLAUDE_HASH =
            "sha256:670c454c28a3edc6b9ecdfb0dc0ba6990045959b674d03f65068eb92b6e56e61";
    private static final String NOT_A_CREDENTIAL = "not-a-credential";

    private static final String TURTLE = "text/turtle";
    private static final String NOTE = "<#w> <http://purl.org/dc/terms/title> \"Week 34: Lisbon written up\" .";
    private static final String PLAN = "<#p> <http://purl.org/dc/terms/title> \"Private: acquisition plan\" .";

    private static ConfigurableApplicationContext server;
    private static String base;
    private static final HttpClient http = HttpClient.newHttpClient();

    /** Three bindings, three principals — each MCP connection is exactly one (T6.2). */
    private static PodToolHandlers owner;
    private static PodToolHandlers claude;
    private static PodToolHandlers anonymous;

    @BeforeAll
    static void bootServer() throws IOException {
        int port = freePort();
        base = "http://127.0.0.1:" + port;
        Path storage = Files.createTempDirectory("cistern-mcp-e2e");
        server = new SpringApplicationBuilder(TestServer.class)
                .properties(
                        "server.port=" + port,
                        "cistern.base-url=" + base,
                        "cistern.storage.root=" + storage,
                        "cistern.owner.web-id=" + OWNER,
                        "cistern.owner.token=" + TOKEN,
                        "cistern.auth.service-principals[0].web-id=" + CLAUDE,
                        "cistern.auth.service-principals[0].credential-hash=" + CLAUDE_HASH)
                .run();
        owner = handlers(TOKEN);
        claude = handlers(CLAUDE_SECRET);
        anonymous = handlers(NOT_A_CREDENTIAL);
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.close();
        }
    }

    /**
     * Fresh state per test: {@code /notes/} inherits from the owner-seeded root again, the
     * note and the private plan exist. ACLs a previous test wrote are removed straight from
     * the store, as the webflux and CLI tests do — a fixture must not depend on enforcement.
     */
    @BeforeEach
    void resetPod() throws Exception {
        ResourceStore store = server.getBean(ResourceStore.class);
        for (String path : List.of("/notes/.acl", "/notes/week.acl", "/private/.acl")) {
            ResourceIdentifier acl = new ResourceIdentifier(URI.create(base + path));
            store.exists(acl).filter(Boolean::booleanValue).flatMap(exists -> store.delete(acl)).block();
        }
        assertWritten(ownerPut("/notes/week", NOTE));
        assertWritten(ownerPut("/private/plan", PLAN));
    }

    // ---- the four beats ---------------------------------------------------------------------

    @Nested
    @DisplayName("The flagship demo's four beats, over the MCP tools")
    class FourBeats {

        @Test
        @DisplayName("beat 1 — it works: granted read, the agent reads the note")
        void beat1ItWorks() {
            grantClaudeReadOnNotes();

            call(claude, PodTool.READ_RESOURCE, Map.of(arg(ToolArgument.URL), "/notes/week"), result -> {
                assertFalse(Boolean.TRUE.equals(result.isError()), text(result));
                assertTrue(text(result).contains("Lisbon written up"), text(result));
                assertEquals(ToolResults.Outcome.OK.token(), structured(result).get(field(ToolResults.Field.OUTCOME)));
                assertEquals(TURTLE, structured(result).get(field(ToolResults.Field.CONTENT_TYPE)));
                assertNotNull(structured(result).get(field(ToolResults.Field.ETAG)), "validator surfaced");
            });
        }

        @Test
        @DisplayName("beat 2 — the refusal: outside the grant, a structured REFUSED naming resource and mode")
        void beat2TheRefusal() {
            grantClaudeReadOnNotes();

            call(claude, PodTool.READ_RESOURCE, Map.of(arg(ToolArgument.URL), "/private/plan"), result -> {
                assertTrue(Boolean.TRUE.equals(result.isError()), "a refusal is not a success");
                assertTrue(text(result).startsWith("REFUSED"), text(result));
                assertTrue(text(result).contains(base + "/private/plan"), "names the resource: " + text(result));
                assertTrue(text(result).contains("read"), "names the mode: " + text(result));
                assertFalse(text(result).contains("acquisition"), "no content leaks through a refusal");
                Map<String, Object> structured = structured(result);
                assertEquals(ToolResults.Outcome.REFUSED.token(), structured.get(field(ToolResults.Field.OUTCOME)));
                assertEquals(PodStatus.FORBIDDEN.code(), structured.get(field(ToolResults.Field.STATUS)),
                        "authenticated and denied is 403, not 401");
                assertEquals(List.of(Map.of(
                                field(ToolResults.Field.RESOURCE), base + "/private/plan",
                                field(ToolResults.Field.MODE), "read")),
                        structured.get(field(ToolResults.Field.REQUIRED)));
            });
        }

        @Test
        @DisplayName("beat 3 — live revocation: revoke mid-session, the very next call is refused")
        void beat3LiveRevocation() {
            grantClaudeReadOnNotes();
            call(claude, PodTool.READ_RESOURCE, Map.of(arg(ToolArgument.URL), "/notes/week"),
                    result -> assertFalse(Boolean.TRUE.equals(result.isError()), text(result)));

            // The owner revokes over the same MCP surface — no restart, no token reissue.
            call(owner, PodTool.REVOKE, Map.of(
                            arg(ToolArgument.URL), "/notes/",
                            arg(ToolArgument.AGENT), CLAUDE),
                    result -> assertFalse(Boolean.TRUE.equals(result.isError()), text(result)));

            call(claude, PodTool.READ_RESOURCE, Map.of(arg(ToolArgument.URL), "/notes/week"), result -> {
                assertTrue(Boolean.TRUE.equals(result.isError()), "the very next call is refused");
                assertEquals(ToolResults.Outcome.REFUSED.token(),
                        structured(result).get(field(ToolResults.Field.OUTCOME)));
            });
        }

        @Test
        @DisplayName("beat 4 — the receipt: names the deciding ACL; Control-guarded")
        void beat4TheReceipt() {
            grantClaudeReadOnNotes();
            call(claude, PodTool.READ_RESOURCE, Map.of(arg(ToolArgument.URL), "/notes/week"),
                    result -> assertFalse(Boolean.TRUE.equals(result.isError()), text(result)));
            call(claude, PodTool.READ_RESOURCE, Map.of(arg(ToolArgument.URL), "/private/plan"),
                    result -> assertTrue(Boolean.TRUE.equals(result.isError()), text(result)));

            // The owner holds Control and sees who read what, under which grant.
            call(owner, PodTool.RECEIPTS, Map.of(arg(ToolArgument.URL), "/notes/week"), result -> {
                assertFalse(Boolean.TRUE.equals(result.isError()), text(result));
                List<Map<String, Object>> records = records(result);
                assertTrue(records.stream().anyMatch(record ->
                                CLAUDE.equals(record.get(DecisionField.AGENT.key()))
                                        && (base + "/notes/.acl").equals(record.get(DecisionField.DECIDED_BY.key()))),
                        "the allow names the deciding ACL: " + records);
            });
            call(owner, PodTool.RECEIPTS, Map.of(arg(ToolArgument.URL), "/private/plan"), result -> {
                List<Map<String, Object>> records = records(result);
                assertTrue(records.stream().anyMatch(record ->
                                CLAUDE.equals(record.get(DecisionField.AGENT.key()))
                                        && record.get(DecisionField.DECIDED_BY.key()) == null),
                        "the denial names no policy: " + records);
            });

            // The agent whose access is reported holds Read at most: receipts are refused.
            call(claude, PodTool.RECEIPTS, Map.of(arg(ToolArgument.URL), "/notes/week"), result -> {
                assertTrue(Boolean.TRUE.equals(result.isError()), text(result));
                Map<String, Object> structured = structured(result);
                assertEquals(ToolResults.Outcome.REFUSED.token(), structured.get(field(ToolResults.Field.OUTCOME)));
                assertEquals(List.of(Map.of(
                                field(ToolResults.Field.RESOURCE), base + "/notes/week",
                                field(ToolResults.Field.MODE), "control")),
                        structured.get(field(ToolResults.Field.REQUIRED)), "receipts require Control");
            });
        }
    }

    // ---- identity binding (T6.2) ------------------------------------------------------------

    @Nested
    @DisplayName("Identity binding: one credential, one principal, 401 vs 403 kept distinct")
    class IdentityBinding {

        @Test
        @DisplayName("a credential the server does not recognise is anonymous: refused 401")
        void unknownCredentialIsAnonymous() {
            call(anonymous, PodTool.READ_RESOURCE, Map.of(arg(ToolArgument.URL), "/notes/week"), result -> {
                assertTrue(Boolean.TRUE.equals(result.isError()));
                assertEquals(PodStatus.UNAUTHORIZED.code(),
                        structured(result).get(field(ToolResults.Field.STATUS)));
                assertTrue(text(result).startsWith("REFUSED"), text(result));
            });
        }

        @Test
        @DisplayName("the bound principal cannot grant itself in: grant without Control is refused")
        void grantWithoutControlIsRefused() {
            call(claude, PodTool.GRANT, Map.of(
                    arg(ToolArgument.URL), "/notes/",
                    arg(ToolArgument.AGENT), CLAUDE,
                    arg(ToolArgument.MODES), List.of("read")), result -> {
                assertTrue(Boolean.TRUE.equals(result.isError()), text(result));
                Map<String, Object> structured = structured(result);
                assertEquals(ToolResults.Outcome.REFUSED.token(), structured.get(field(ToolResults.Field.OUTCOME)));
                assertTrue(text(result).contains("control"), "names Control: " + text(result));
            });
            // And nothing was written: the owner's next read of the ACL walk still 404s.
            assertEquals(404, ownerGet("/notes/.acl").statusCode(), "no ACL appeared");
        }
    }

    // ---- the write path ---------------------------------------------------------------------

    @Nested
    @DisplayName("write-resource, delete-resource, list-container")
    class WritePath {

        @Test
        @DisplayName("create then replace under if-match; a stale validator fails and writes nothing")
        void conditionalWrites() {
            call(owner, PodTool.WRITE_RESOURCE, Map.of(
                    arg(ToolArgument.URL), "/notes/tuesday",
                    arg(ToolArgument.CONTENT), NOTE,
                    arg(ToolArgument.CONTENT_TYPE), TURTLE), result -> {
                assertFalse(Boolean.TRUE.equals(result.isError()), text(result));
                assertEquals(PodStatus.CREATED.name().toLowerCase(java.util.Locale.ROOT),
                        structured(result).get(field(ToolResults.Field.EFFECT)));
            });

            String[] etag = new String[1];
            call(owner, PodTool.READ_RESOURCE, Map.of(arg(ToolArgument.URL), "/notes/tuesday"),
                    result -> etag[0] = (String) structured(result).get(field(ToolResults.Field.ETAG)));
            assertNotNull(etag[0]);

            call(owner, PodTool.WRITE_RESOURCE, Map.of(
                    arg(ToolArgument.URL), "/notes/tuesday",
                    arg(ToolArgument.CONTENT), PLAN,
                    arg(ToolArgument.CONTENT_TYPE), TURTLE,
                    arg(ToolArgument.IF_MATCH), etag[0]), result -> {
                assertFalse(Boolean.TRUE.equals(result.isError()), text(result));
                assertEquals(PodStatus.NO_CONTENT.name().toLowerCase(java.util.Locale.ROOT),
                        structured(result).get(field(ToolResults.Field.EFFECT)));
            });

            // The replace changed the graph, so the old validator is now stale.
            call(owner, PodTool.WRITE_RESOURCE, Map.of(
                    arg(ToolArgument.URL), "/notes/tuesday",
                    arg(ToolArgument.CONTENT), NOTE,
                    arg(ToolArgument.CONTENT_TYPE), TURTLE,
                    arg(ToolArgument.IF_MATCH), etag[0]), result -> {
                assertTrue(Boolean.TRUE.equals(result.isError()), text(result));
                assertEquals(ToolResults.Outcome.FAILED.token(),
                        structured(result).get(field(ToolResults.Field.OUTCOME)));
                assertEquals(PodStatus.PRECONDITION_FAILED.code(),
                        structured(result).get(field(ToolResults.Field.STATUS)));
            });
            call(owner, PodTool.READ_RESOURCE, Map.of(arg(ToolArgument.URL), "/notes/tuesday"),
                    result -> assertTrue(text(result).contains("acquisition"),
                            "the stale write did not land: " + text(result)));

            call(owner, PodTool.WRITE_RESOURCE, Map.of(
                    arg(ToolArgument.URL), "/notes/tuesday",
                    arg(ToolArgument.CONTENT), NOTE,
                    arg(ToolArgument.CONTENT_TYPE), TURTLE,
                    arg(ToolArgument.CREATE_ONLY), Boolean.TRUE), result -> {
                assertTrue(Boolean.TRUE.equals(result.isError()), "create-only over an existing resource");
                assertEquals(PodStatus.PRECONDITION_FAILED.code(),
                        structured(result).get(field(ToolResults.Field.STATUS)));
            });
        }

        @Test
        @DisplayName("list-container names the members; a document is a caller mistake")
        void listContainer() {
            call(owner, PodTool.LIST_CONTAINER, Map.of(arg(ToolArgument.URL), "/notes/"), result -> {
                assertFalse(Boolean.TRUE.equals(result.isError()), text(result));
                @SuppressWarnings("unchecked")
                List<String> members = (List<String>) structured(result).get(field(ToolResults.Field.MEMBERS));
                assertTrue(members.contains(base + "/notes/week"), members.toString());
            });
            call(owner, PodTool.LIST_CONTAINER, Map.of(arg(ToolArgument.URL), "/notes/week"), result -> {
                assertTrue(Boolean.TRUE.equals(result.isError()));
                assertEquals(ToolResults.Outcome.FAILED.token(),
                        structured(result).get(field(ToolResults.Field.OUTCOME)));
            });
        }

        @Test
        @DisplayName("delete works; a non-empty container fails with the server's own words")
        void deleteAndConflict() {
            call(owner, PodTool.DELETE_RESOURCE, Map.of(arg(ToolArgument.URL), "/private/plan"),
                    result -> assertFalse(Boolean.TRUE.equals(result.isError()), text(result)));
            call(owner, PodTool.READ_RESOURCE, Map.of(arg(ToolArgument.URL), "/private/plan"), result -> {
                assertTrue(Boolean.TRUE.equals(result.isError()));
                assertEquals(404, structured(result).get(field(ToolResults.Field.STATUS)));
            });

            call(owner, PodTool.DELETE_RESOURCE, Map.of(arg(ToolArgument.URL), "/notes/"), result -> {
                assertTrue(Boolean.TRUE.equals(result.isError()));
                assertEquals(409, structured(result).get(field(ToolResults.Field.STATUS)));
                assertNotNull(structured(result).get(field(ToolResults.Field.DETAIL)),
                        "the server's problem document is carried, not replaced");
            });
        }
    }

    // ---- argument hygiene -------------------------------------------------------------------

    @Nested
    @DisplayName("Arguments outside the pod or outside the vocabulary fail in words, before any request")
    class Arguments {

        @Test
        @DisplayName("a URL outside the bound pod is refused locally")
        void outsideThePod() {
            call(owner, PodTool.READ_RESOURCE,
                    Map.of(arg(ToolArgument.URL), "https://elsewhere.example/notes/week"), result -> {
                        assertTrue(Boolean.TRUE.equals(result.isError()));
                        assertEquals(ToolResults.Outcome.FAILED.token(),
                                structured(result).get(field(ToolResults.Field.OUTCOME)));
                    });
        }

        @Test
        @DisplayName("an unknown access mode never reaches the server")
        void unknownMode() {
            call(owner, PodTool.GRANT, Map.of(
                    arg(ToolArgument.URL), "/notes/",
                    arg(ToolArgument.AGENT), CLAUDE,
                    arg(ToolArgument.MODES), List.of("admin")), result -> {
                assertTrue(Boolean.TRUE.equals(result.isError()));
                assertTrue(text(result).contains("admin"), text(result));
            });
        }
    }

    // ---- helpers ---------------------------------------------------------------------------

    private static PodToolHandlers handlers(String credential) {
        return new PodToolHandlers(
                PodHttp.connect(new BearerCredential(credential)),
                PodAddress.of(URI.create(base)));
    }

    /** The owner grants the assistant read on {@code /notes/} — over the MCP grant tool itself. */
    private static void grantClaudeReadOnNotes() {
        call(owner, PodTool.GRANT, Map.of(
                        arg(ToolArgument.URL), "/notes/",
                        arg(ToolArgument.AGENT), CLAUDE,
                        arg(ToolArgument.MODES), List.of("read")),
                result -> assertFalse(Boolean.TRUE.equals(result.isError()), text(result)));
    }

    private static void call(PodToolHandlers handlers, PodTool tool, Map<String, Object> arguments,
                             Consumer<McpSchema.CallToolResult> assertions) {
        StepVerifier.create(handlers.handle(tool,
                        McpSchema.CallToolRequest.builder(tool.toolName()).arguments(arguments).build()))
                .assertNext(assertions)
                .verifyComplete();
    }

    private static String arg(ToolArgument argument) {
        return argument.jsonName();
    }

    private static String field(ToolResults.Field field) {
        return field.key();
    }

    private static String text(McpSchema.CallToolResult result) {
        return ((McpSchema.TextContent) result.content().get(0)).text();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> structured(McpSchema.CallToolResult result) {
        return (Map<String, Object>) result.structuredContent();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> records(McpSchema.CallToolResult result) {
        return (List<Map<String, Object>>) structured(result).get(field(ToolResults.Field.RECORDS));
    }

    private static void assertWritten(HttpResponse<String> response) {
        assertTrue(response.statusCode() == 201 || response.statusCode() == 204,
                "fixture written: " + response.statusCode());
    }

    private static HttpResponse<String> ownerPut(String path, String turtle) {
        try {
            return http.send(HttpRequest.newBuilder(URI.create(base + path))
                            .header(HttpHeaderName.AUTHORIZATION.fieldName(), "Bearer " + TOKEN)
                            .header(HttpHeaderName.CONTENT_TYPE.fieldName(), TURTLE)
                            .PUT(HttpRequest.BodyPublishers.ofString(turtle))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static HttpResponse<String> ownerGet(String path) {
        try {
            return http.send(HttpRequest.newBuilder(URI.create(base + path))
                            .header(HttpHeaderName.AUTHORIZATION.fieldName(), "Bearer " + TOKEN)
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
