package com.enrichmeai.cistern.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The whole thing on the real wire (ground rule 6): the actual HTTP server in-process, the
 * actual {@link McpBridge} spawned as a child JVM speaking MCP over its stdio, and the
 * official SDK's own {@link McpAsyncClient} driving it — the same client library Claude
 * Desktop-class hosts embed. No frame, no transport, no fixture is simulated.
 *
 * <p>One test method, deliberately: the four beats are a sequence (grant → read → refusal →
 * revoke → refusal → receipt) whose order is the point, and the bridge process is expensive.
 *
 * <p>The revocation in beat 3 is performed exactly as the demo script performs it — the owner
 * deletes the rule over plain HTTP mid-session, outside the MCP channel — proving there is no
 * cache between the agent and the policy.
 */
class McpWireEndToEndTest {

    private static final String OWNER = "https://you.example/profile/card#me";
    private static final String TOKEN = "mcp-wire-owner-token";
    private static final String CLAUDE = "https://connectors.example/claude#agent";
    private static final String CLAUDE_SECRET = "claude-connector-secret-2af8";
    private static final String CLAUDE_HASH =
            "sha256:670c454c28a3edc6b9ecdfb0dc0ba6990045959b674d03f65068eb92b6e56e61";

    private static final String TURTLE = "text/turtle";
    private static final String NOTE = "<#w> <http://purl.org/dc/terms/title> \"Week 34: Lisbon written up\" .";
    private static final String PLAN = "<#p> <http://purl.org/dc/terms/title> \"Private: acquisition plan\" .";

    private static final Duration WIRE_TIMEOUT = Duration.ofSeconds(30);

    private static ConfigurableApplicationContext server;
    private static String base;
    private static final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll
    static void bootServer() throws Exception {
        int port = freePort();
        base = "http://127.0.0.1:" + port;
        Path storage = Files.createTempDirectory("cistern-mcp-wire");
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
        assertWritten(owner("PUT", "/notes/week", NOTE));
        assertWritten(owner("PUT", "/private/plan", PLAN));
        // The owner grants the assistant read on /notes/ — the rule beat 2 will show.
        assertWritten(owner("PUT", "/notes/.acl", """
                @prefix acl: <http://www.w3.org/ns/auth/acl#> .
                <#owner> a acl:Authorization ;
                    acl:agent <%s> ;
                    acl:accessTo <%s/notes/> ; acl:default <%s/notes/> ;
                    acl:mode acl:Read, acl:Write, acl:Append, acl:Control .
                <#claude> a acl:Authorization ;
                    acl:agent <%s> ;
                    acl:accessTo <%s/notes/> ; acl:default <%s/notes/> ;
                    acl:mode acl:Read .
                """.formatted(OWNER, base, base, CLAUDE, base, base)));
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void fourBeatsOverTheRealWire() {
        McpAsyncClient client = McpClient.async(bridgeTransport())
                .requestTimeout(WIRE_TIMEOUT)
                .build();
        try {
            McpSchema.InitializeResult initialized = client.initialize().block(WIRE_TIMEOUT);
            assertNotNull(initialized);
            assertEquals(McpFrontDoor.SERVER_NAME, initialized.serverInfo().name());

            // The advertised tools are exactly the closed set, kebab-case.
            McpSchema.ListToolsResult tools = client.listTools().block(WIRE_TIMEOUT);
            assertNotNull(tools);
            Set<String> names = tools.tools().stream()
                    .map(McpSchema.Tool::name)
                    .collect(Collectors.toSet());
            for (PodTool tool : PodTool.values()) {
                assertTrue(names.contains(tool.toolName()), names.toString());
            }
            assertEquals(PodTool.values().length, names.size(), names.toString());

            // Beat 1 — it works: the granted note is read through the bridge.
            McpSchema.CallToolResult read = call(client, PodTool.READ_RESOURCE,
                    Map.of(ToolArgument.URL.jsonName(), "/notes/week"));
            assertFalse(Boolean.TRUE.equals(read.isError()), text(read));
            assertTrue(text(read).contains("Lisbon written up"), text(read));

            // Beat 2 — the refusal: same session, same identity, outside the grant.
            McpSchema.CallToolResult refused = call(client, PodTool.READ_RESOURCE,
                    Map.of(ToolArgument.URL.jsonName(), "/private/plan"));
            assertTrue(Boolean.TRUE.equals(refused.isError()));
            assertTrue(text(refused).startsWith("REFUSED"), text(refused));
            assertTrue(text(refused).contains("read"), text(refused));
            assertTrue(text(refused).contains(base + "/private/plan"), text(refused));
            assertFalse(text(refused).contains("acquisition"), "no content leaks: " + text(refused));

            // Beat 3 — live revocation: the owner deletes the rule over plain HTTP,
            // mid-session; the bridge's very next call is refused. No restart, no reissue.
            assertEquals(204, owner("DELETE", "/notes/.acl", null).statusCode());
            McpSchema.CallToolResult revoked = call(client, PodTool.READ_RESOURCE,
                    Map.of(ToolArgument.URL.jsonName(), "/notes/week"));
            assertTrue(Boolean.TRUE.equals(revoked.isError()), "the very next call: " + text(revoked));
            assertTrue(text(revoked).startsWith("REFUSED"), text(revoked));

            // Beat 4 — the receipt: the record exists and names the deciding ACL (owner's
            // view, over HTTP); the agent's own receipts call is refused for want of Control.
            String receipts = owner("GET", "/notes/week?receipts", null).body();
            assertTrue(receipts.contains(CLAUDE), receipts);
            assertTrue(receipts.contains(base + "/notes/.acl"), receipts);
            McpSchema.CallToolResult receiptsRefused = call(client, PodTool.RECEIPTS,
                    Map.of(ToolArgument.URL.jsonName(), "/notes/week"));
            assertTrue(Boolean.TRUE.equals(receiptsRefused.isError()));
            assertTrue(text(receiptsRefused).contains("control"), text(receiptsRefused));
        } finally {
            client.closeGracefully().block(WIRE_TIMEOUT);
        }
    }

    // ---- launching the real bridge ----------------------------------------------------------

    /**
     * The bridge exactly as a desktop connector launches it: a child JVM, configured only by
     * environment, bound to the assistant's service credential. The classpath is this test
     * JVM's own, which carries the module and its runtime dependencies.
     */
    private static StdioClientTransport bridgeTransport() {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ServerParameters parameters = ServerParameters.builder(java)
                .args("-cp", System.getProperty("java.class.path"), McpBridge.class.getName())
                .env(Map.of(
                        McpBridge.ENV_BASE_URL, base,
                        McpBridge.ENV_CREDENTIAL, CLAUDE_SECRET))
                .build();
        return new StdioClientTransport(parameters, McpJsonDefaults.getMapper());
    }

    private static McpSchema.CallToolResult call(McpAsyncClient client, PodTool tool,
                                                 Map<String, Object> arguments) {
        McpSchema.CallToolResult result = client.callTool(
                        McpSchema.CallToolRequest.builder(tool.toolName()).arguments(arguments).build())
                .block(WIRE_TIMEOUT);
        assertNotNull(result);
        return result;
    }

    private static String text(McpSchema.CallToolResult result) {
        return ((McpSchema.TextContent) result.content().get(0)).text();
    }

    private static void assertWritten(HttpResponse<String> response) {
        assertTrue(response.statusCode() == 201 || response.statusCode() == 204,
                "fixture written: " + response.statusCode());
    }

    private static HttpResponse<String> owner(String method, String path, String turtle) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                    .header(HttpHeaderName.AUTHORIZATION.fieldName(), "Bearer " + TOKEN);
            if (turtle != null) {
                builder.header(HttpHeaderName.CONTENT_TYPE.fieldName(), TURTLE);
            }
            builder.method(method, turtle == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(turtle));
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
