package com.enrichmeai.cistern.mcp.transport;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStreamableServerSession;
import io.modelcontextprotocol.spec.McpStreamableServerTransport;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Serves MCP over Streamable HTTP on Netty (T6.7), so a client that is not a local subprocess
 * can reach the pod.
 *
 * <p>Written rather than imported because the MCP Java SDK 2.0.0 ships Servlet transports only
 * — {@code HttpServletStreamableServerTransportProvider} and its siblings — and
 * {@code io.modelcontextprotocol.sdk:mcp-spring-webflux} stops at 0.18.4, never carried to the
 * 2.x line. Cistern is WebFlux on Netty, so there was no first-party HTTP transport to use, and
 * without one MCP reaches only clients that can spawn a process on the same machine.
 *
 * <p>This follows the Servlet implementation's protocol decisions closely and departs from it
 * in one respect that matters here: it never blocks. The Servlet version calls {@code .block()}
 * on the initialize result and on notification handling because a servlet thread is allowed to
 * wait; on an event loop that is a stalled thread under load, and ground rule 3 forbids it. The
 * session's {@link Mono}s are composed into the response instead.
 *
 * <h2>What is implemented</h2>
 *
 * <ul>
 *   <li>{@code POST} — a JSON-RPC message. An {@code initialize} request opens a session and
 *       answers with JSON plus {@code Mcp-Session-Id}; any other request answers with an SSE
 *       stream carrying its response; a notification or a response answers {@code 202}.
 *   <li>{@code GET} — the server-initiated stream for a session.
 *   <li>{@code DELETE} — ends a session.
 * </ul>
 *
 * <h2>What is not, and is a stated non-goal for now</h2>
 *
 * <p><strong>Resumability.</strong> The specification lets a client resume a dropped stream with
 * {@code Last-Event-ID}, which requires the server to retain per-stream event history. This
 * does not, so a client that reconnects starts a new stream and may miss messages sent while it
 * was away. Every message here carries an id so that adding a store later is additive, and
 * {@code Last-Event-ID} is refused explicitly rather than accepted and quietly ignored — a
 * client told "resumed" and then silently missing messages is worse than one told "cannot".
 */
public final class WebFluxStreamableTransport implements McpStreamableServerTransportProvider {

    private static final Logger log = LoggerFactory.getLogger(WebFluxStreamableTransport.class);

    /** The header carrying the session identifier, per the Streamable HTTP specification. */
    public static final String SESSION_ID_HEADER = "Mcp-Session-Id";

    /** The header a client sends to resume a dropped stream. Refused — see the class comment. */
    public static final String LAST_EVENT_ID_HEADER = "Last-Event-ID";

    private final McpJsonMapper json;
    private final String endpoint;
    private final ConcurrentHashMap<String, McpStreamableServerSession> sessions = new ConcurrentHashMap<>();
    private volatile McpStreamableServerSession.Factory sessionFactory;

    public WebFluxStreamableTransport(McpJsonMapper json, String endpoint) {
        this.json = Objects.requireNonNull(json, "json");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
    }

    /** The routes to publish. Mount alongside the pod's own handlers. */
    public RouterFunction<ServerResponse> routes() {
        return RouterFunctions.route()
                .POST(endpoint, this::handlePost)
                .GET(endpoint, this::handleGet)
                .DELETE(endpoint, this::handleDelete)
                .build();
    }

    @Override
    public void setSessionFactory(McpStreamableServerSession.Factory sessionFactory) {
        this.sessionFactory = Objects.requireNonNull(sessionFactory, "sessionFactory");
    }

    @Override
    public Mono<Void> notifyClients(String method, Object params) {
        return Flux.fromIterable(sessions.values())
                .flatMap(session -> session.sendNotification(method, params)
                        // One unreachable client must not stop the others being told.
                        .onErrorResume(e -> {
                            log.debug("notify failed for session {}: {}", session.getId(), e.toString());
                            return Mono.empty();
                        }))
                .then();
    }

    @Override
    public Mono<Void> closeGracefully() {
        return Flux.fromIterable(sessions.values())
                .flatMap(McpStreamableServerSession::closeGracefully)
                .then(Mono.fromRunnable(sessions::clear));
    }

    // ---- POST ----------------------------------------------------------------------------

    private Mono<ServerResponse> handlePost(ServerRequest request) {
        if (sessionFactory == null) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "The MCP server is not started");
        }
        List<String> problems = acceptProblems(request);
        return request.bodyToMono(String.class).defaultIfEmpty("").flatMap(body -> {
            McpSchema.JSONRPCMessage message;
            try {
                message = McpSchema.deserializeJsonRpcMessage(json, body);
            } catch (Exception e) {
                return error(HttpStatus.BAD_REQUEST, "Malformed JSON-RPC message: " + e.getMessage());
            }
            if (message instanceof McpSchema.JSONRPCRequest jsonRpc
                    && McpSchema.METHOD_INITIALIZE.equals(jsonRpc.method())) {
                return problems.isEmpty()
                        ? initialize(jsonRpc)
                        : error(HttpStatus.BAD_REQUEST, String.join("; ", problems));
            }
            String sessionId = request.headers().firstHeader(SESSION_ID_HEADER);
            if (sessionId == null || sessionId.isBlank()) {
                return error(HttpStatus.BAD_REQUEST, "Session ID required in " + SESSION_ID_HEADER);
            }
            McpStreamableServerSession session = sessions.get(sessionId);
            if (session == null) {
                return error(HttpStatus.NOT_FOUND, "Session not found: " + sessionId);
            }
            return switch (message) {
                case McpSchema.JSONRPCResponse response ->
                        session.accept(response).then(accepted());
                case McpSchema.JSONRPCNotification notification ->
                        session.accept(notification).then(accepted());
                case McpSchema.JSONRPCRequest jsonRpc -> respondOverStream(session, jsonRpc, problems);
                default -> error(HttpStatus.BAD_REQUEST, "Unsupported JSON-RPC message");
            };
        });
    }

    /**
     * Opens a session and answers with JSON.
     *
     * <p>The Servlet transport blocks on {@code initResult()} here; this composes it, which is
     * the only reason this class can live on an event loop at all.
     */
    private Mono<ServerResponse> initialize(McpSchema.JSONRPCRequest jsonRpc) {
        McpSchema.InitializeRequest initializeRequest =
                json.convertValue(jsonRpc.params(), new TypeRef<McpSchema.InitializeRequest>() { });
        McpStreamableServerSession.McpStreamableServerSessionInit init =
                sessionFactory.startSession(initializeRequest);
        sessions.put(init.session().getId(), init.session());

        return init.initResult()
                .map(result -> new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, jsonRpc.id(), result, null))
                .flatMap(response -> ServerResponse.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(SESSION_ID_HEADER, init.session().getId())
                        .bodyValue(response))
                .onErrorResume(e -> {
                    // The session was registered before the result resolved; if it never does,
                    // leaving it in the map would leak a session no client can use.
                    sessions.remove(init.session().getId());
                    return error(HttpStatus.INTERNAL_SERVER_ERROR, "Initialize failed: " + e.getMessage());
                });
    }

    /** A request answered over its own SSE stream, which is what "streamable" means here. */
    private Mono<ServerResponse> respondOverStream(
            McpStreamableServerSession session, McpSchema.JSONRPCRequest jsonRpc, List<String> problems) {
        if (!problems.isEmpty()) {
            return error(HttpStatus.BAD_REQUEST, String.join("; ", problems));
        }
        SinkTransport transport = new SinkTransport(json);
        return ServerResponse.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(transport.events()
                        // The session writes into the sink; completing it ends the response.
                        .doOnSubscribe(ignored -> session.responseStream(jsonRpc, transport)
                                .doFinally(signal -> transport.complete())
                                .subscribe(null, transport::fail)),
                        ServerSentEvent.class);
    }

    // ---- GET, DELETE ---------------------------------------------------------------------

    private Mono<ServerResponse> handleGet(ServerRequest request) {
        if (sessionFactory == null) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "The MCP server is not started");
        }
        if (!accepts(request, MediaType.TEXT_EVENT_STREAM_VALUE)) {
            return error(HttpStatus.BAD_REQUEST, "text/event-stream required in Accept header");
        }
        if (request.headers().firstHeader(LAST_EVENT_ID_HEADER) != null) {
            // Refused rather than ignored: a client told its stream resumed, and then silently
            // missing everything sent while it was away, is worse off than one told it cannot.
            return error(HttpStatus.NOT_IMPLEMENTED,
                    "Stream resumption is not supported by this server; reconnect without "
                            + LAST_EVENT_ID_HEADER + " and expect to have missed messages");
        }
        String sessionId = request.headers().firstHeader(SESSION_ID_HEADER);
        if (sessionId == null || sessionId.isBlank()) {
            return error(HttpStatus.BAD_REQUEST, "Session ID required in " + SESSION_ID_HEADER);
        }
        McpStreamableServerSession session = sessions.get(sessionId);
        if (session == null) {
            return error(HttpStatus.NOT_FOUND, "Session not found: " + sessionId);
        }
        SinkTransport transport = new SinkTransport(json);
        return ServerResponse.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(transport.events()
                        .doOnSubscribe(ignored -> session.listeningStream(transport))
                        .doFinally(signal -> transport.complete()),
                        ServerSentEvent.class);
    }

    private Mono<ServerResponse> handleDelete(ServerRequest request) {
        String sessionId = request.headers().firstHeader(SESSION_ID_HEADER);
        if (sessionId == null || sessionId.isBlank()) {
            return error(HttpStatus.BAD_REQUEST, "Session ID required in " + SESSION_ID_HEADER);
        }
        McpStreamableServerSession session = sessions.remove(sessionId);
        if (session == null) {
            return error(HttpStatus.NOT_FOUND, "Session not found: " + sessionId);
        }
        return session.delete().then(ServerResponse.noContent().build());
    }

    // ---- helpers -------------------------------------------------------------------------

    /**
     * The Accept problems the specification requires a POST to be refused for.
     *
     * <p>Collected rather than returned on the first failure so a client sending neither is told
     * about both at once, which is the difference between one round trip and two.
     */
    private static List<String> acceptProblems(ServerRequest request) {
        List<String> problems = new java.util.ArrayList<>();
        if (!accepts(request, MediaType.TEXT_EVENT_STREAM_VALUE)) {
            problems.add("text/event-stream required in Accept header");
        }
        if (!accepts(request, MediaType.APPLICATION_JSON_VALUE)) {
            problems.add("application/json required in Accept header");
        }
        return problems;
    }

    private static boolean accepts(ServerRequest request, String mediaType) {
        return request.headers().accept().stream()
                .anyMatch(accepted -> accepted.toString().contains(mediaType)
                        || MediaType.ALL.equalsTypeAndSubtype(accepted));
    }

    private static Mono<ServerResponse> accepted() {
        return ServerResponse.accepted().build();
    }

    private Mono<ServerResponse> error(HttpStatus status, String message) {
        McpError error = McpError.builder(McpSchema.ErrorCodes.INTERNAL_ERROR).message(message).build();
        return ServerResponse.status(status).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("error", Map.of("code", McpSchema.ErrorCodes.INTERNAL_ERROR,
                        "message", error.getMessage() == null ? message : error.getMessage())));
    }

    /**
     * One SSE stream, as the session sees it.
     *
     * <p>A {@link Sinks.Many} rather than a writer: the session pushes messages in from whatever
     * thread it is on, and Reactor delivers them to the HTTP response without either side
     * blocking on the other. {@code onBackpressureBuffer} because a slow client must not stall
     * the session that is writing to it.
     */
    private static final class SinkTransport implements McpStreamableServerTransport {

        private final McpJsonMapper json;
        private final Sinks.Many<ServerSentEvent<String>> sink =
                Sinks.many().unicast().onBackpressureBuffer();

        SinkTransport(McpJsonMapper json) {
            this.json = json;
        }

        Flux<ServerSentEvent<String>> events() {
            return sink.asFlux();
        }

        void complete() {
            sink.tryEmitComplete();
        }

        void fail(Throwable error) {
            sink.tryEmitError(error);
        }

        @Override
        public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
            return sendMessage(message, null);
        }

        @Override
        public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message, String messageId) {
            return Mono.fromCallable(() -> json.writeValueAsString(message))
                    .map(payload -> {
                        ServerSentEvent.Builder<String> event = ServerSentEvent.<String>builder()
                                .event("message")
                                .data(payload);
                        // Always stamped, even though resumption is unsupported: it costs
                        // nothing now, and adding an event store later changes the store
                        // rather than the wire.
                        if (messageId != null) {
                            event.id(messageId);
                        }
                        return event.build();
                    })
                    // A message that will not serialise is this stream's problem, not the
                    // session's: fail the stream so the client sees it end, rather than
                    // dropping one message silently and leaving it waiting for a reply.
                    .doOnNext(sink::tryEmitNext)
                    .doOnError(this::fail)
                    .then();
        }

        @Override
        public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
            return json.convertValue(data, typeRef);
        }

        @Override
        public Mono<Void> closeGracefully() {
            return Mono.fromRunnable(this::complete);
        }

        @Override
        public void close() {
            complete();
        }
    }
}
