package com.enrichmeai.cistern.mcp.transport;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MCP over Streamable HTTP: the session lifecycle a remote client depends on")
class WebFluxStreamableTransportTest {

    private static final String ENDPOINT = "/mcp";
    private static final String LIST_TOOLS = """
        {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}""";

    private WebFluxStreamableTransport transport;
    private WebTestClient client;

    private static String initializeBody() {
        return """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
              "protocolVersion":"2024-11-05",
              "capabilities":{},
              "clientInfo":{"name":"test","version":"1"}}}""";
    }

    /** A factory that fails the test if reached: these cases must be refused before it. */
    private void refuseBeforeTheSessionFactory() {
        transport.setSessionFactory(request -> {
            throw new AssertionError("the request should have been refused before this");
        });
    }

    @BeforeEach
    void setUp() {
        transport = new WebFluxStreamableTransport(McpJsonDefaults.getMapper(), ENDPOINT);
        client = WebTestClient.bindToRouterFunction(transport.routes()).build();
    }

    @Test
    @DisplayName("before the server is started, requests are refused rather than dropped")
    void notStartedIsRefused() {
        client.post().uri(ENDPOINT)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(initializeBody())
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("a POST missing either Accept type is refused, and told about both at once")
    void acceptHeaderIsEnforced() {
        refuseBeforeTheSessionFactory();

        client.post().uri(ENDPOINT)
                .accept(MediaType.TEXT_PLAIN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(initializeBody())
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error.message").value(message ->
                        assertThat(message.toString())
                                .describedAs("both problems in one response, not a round trip each")
                                .contains("text/event-stream")
                                .contains("application/json"));
    }

    @Test
    @DisplayName("a non-initialize request with no session id is refused")
    void sessionIdRequired() {
        refuseBeforeTheSessionFactory();

        client.post().uri(ENDPOINT)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(LIST_TOOLS)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error.message").value(message ->
                        assertThat(message.toString())
                                .contains(WebFluxStreamableTransport.SESSION_ID_HEADER));
    }

    @Test
    @DisplayName("an unknown session is 404, not 500 — the client should re-initialize")
    void unknownSessionIsNotFound() {
        refuseBeforeTheSessionFactory();

        client.post().uri(ENDPOINT)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .contentType(MediaType.APPLICATION_JSON)
                .header(WebFluxStreamableTransport.SESSION_ID_HEADER, "no-such-session")
                .bodyValue(LIST_TOOLS)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    @DisplayName("GET without a session id is refused")
    void getRequiresSession() {
        refuseBeforeTheSessionFactory();

        client.get().uri(ENDPOINT)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    @DisplayName("stream resumption is refused explicitly, never accepted and ignored")
    void resumptionIsRefusedNotIgnored() {
        refuseBeforeTheSessionFactory();

        client.get().uri(ENDPOINT)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .header(WebFluxStreamableTransport.LAST_EVENT_ID_HEADER, "42")
                .header(WebFluxStreamableTransport.SESSION_ID_HEADER, "any")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.NOT_IMPLEMENTED)
                .expectBody().jsonPath("$.error.message").value(message ->
                        assertThat(message.toString())
                                .describedAs("a client told it resumed, then silently missing "
                                        + "messages, is worse off than one told it cannot")
                                .contains("not supported"));
    }

    @Test
    @DisplayName("DELETE on an unknown session is 404")
    void deleteUnknownSession() {
        client.method(HttpMethod.DELETE).uri(ENDPOINT)
                .header(WebFluxStreamableTransport.SESSION_ID_HEADER, "no-such-session")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    @DisplayName("malformed JSON is a 400, not a 500")
    void malformedJson() {
        refuseBeforeTheSessionFactory();

        client.post().uri(ENDPOINT)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{ not json at all")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    @DisplayName("the initialize method name is the SDK's, not a literal we guessed")
    void initializeMethodName() {
        assertThat(McpSchema.METHOD_INITIALIZE).isEqualTo("initialize");
    }
}
