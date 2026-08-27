package com.enrichmeai.cistern.mcp;

import com.enrichmeai.cistern.mcp.transport.WebFluxStreamableTransport;
import com.enrichmeai.cistern.webflux.CisternProperties;

import java.net.URI;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;

/**
 * Binds the MCP front door to the Streamable HTTP transport once the server's port is known
 * (T6.7).
 *
 * <p>The same shape and the same reason as {@link McpStdioRunner}: the tools reach the pod over
 * loopback, and the loopback URL needs a port that does not exist until the web server has
 * started. The transport's routes are mounted before this runs, which is deliberate — a
 * request arriving in that window gets 503 "not started", which is a client's cue to retry,
 * rather than a 404 suggesting the endpoint does not exist.
 *
 * <p>Idempotent: {@link WebServerInitializedEvent} can fire more than once in a context that
 * restarts, and binding twice would replace a live session factory underneath connected
 * clients.
 */
public final class McpHttpRunner implements ApplicationListener<WebServerInitializedEvent> {

    private static final Logger log = LoggerFactory.getLogger(McpHttpRunner.class);
    private static final String LOOPBACK_URL_TEMPLATE = "http://127.0.0.1:%d";

    private final WebFluxStreamableTransport transport;
    private final CisternMcpProperties mcp;
    private final CisternProperties cistern;
    private volatile boolean bound;

    public McpHttpRunner(WebFluxStreamableTransport transport,
                         CisternMcpProperties mcp, CisternProperties cistern) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.mcp = Objects.requireNonNull(mcp, "mcp");
        this.cistern = Objects.requireNonNull(cistern, "cistern");
    }

    @Override
    public void onApplicationEvent(WebServerInitializedEvent event) {
        if (bound) {
            return;
        }
        URI connect = mcp.baseUrl() != null
                ? mcp.baseUrl()
                : URI.create(LOOPBACK_URL_TEMPLATE.formatted(event.getWebServer().getPort()));
        McpFrontDoor.serve(transport, mcp.boundCredential(),
                new PodAddress(connect, URI.create(cistern.baseUrl())));
        bound = true;
        log.info(McpMessage.HTTP_TRANSPORT_WIRED.format(
                CisternMcpConfiguration.MCP_HTTP_ENDPOINT, connect));
    }
}
