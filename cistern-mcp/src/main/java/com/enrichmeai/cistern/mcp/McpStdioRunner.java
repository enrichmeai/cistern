package com.enrichmeai.cistern.mcp;

import com.enrichmeai.cistern.webflux.CisternProperties;

import java.net.URI;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;

/**
 * The app-embedded shape: when {@code cistern.mcp.enabled} is set, cistern-app serves MCP on
 * its own stdio, and every tool call loops back over HTTP to the server's own port — so one
 * process is both the pod and its agent door, and the loop-back requests cross
 * {@code AuthorizationFilter} exactly as an external client's would (ARCHITECTURE decision 6;
 * there is deliberately no shortcut into the service layer, though it is centimetres away).
 *
 * <p>Starts on {@link WebServerInitializedEvent} because the loop-back address needs the
 * <em>actual</em> port — {@code server.port=0} in tests — and because serving MCP before the
 * HTTP stack can answer would refuse the first tool calls for no reason. Identifiers are
 * unaffected by connecting to {@code 127.0.0.1}: {@code RequestPaths} resolves them from
 * {@code cistern.base-url}, never from the {@code Host} header.
 *
 * <p>Run under the {@code mcp-stdio} Spring profile when a desktop client launches the app,
 * so logging moves to stderr and stdout stays clean for the frames (cistern-app's
 * {@code logback-spring.xml}); {@link McpStdioSession} redirects {@code System.out} as a
 * second line of defence either way.
 */
final class McpStdioRunner implements ApplicationListener<WebServerInitializedEvent>, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(McpStdioRunner.class);

    /** The one address a loop-back can trust to reach its own listener. */
    private static final String LOOPBACK_URL_TEMPLATE = "http://127.0.0.1:%d";

    private final CisternMcpProperties mcp;
    private final CisternProperties cistern;

    private volatile McpStdioSession session;

    McpStdioRunner(CisternMcpProperties mcp, CisternProperties cistern) {
        this.mcp = Objects.requireNonNull(mcp, "mcp");
        this.cistern = Objects.requireNonNull(cistern, "cistern");
    }

    @Override
    public void onApplicationEvent(WebServerInitializedEvent event) {
        if (session != null) {
            return;
        }
        URI connect = mcp.baseUrl() != null
                ? mcp.baseUrl()
                : URI.create(LOOPBACK_URL_TEMPLATE.formatted(event.getWebServer().getPort()));
        PodAddress address = new PodAddress(connect, URI.create(cistern.baseUrl()));
        session = McpStdioSession.open(mcp.boundCredential(), address);
        log.info(McpMessage.EMBEDDED_STARTED.format(connect));
    }

    @Override
    public void destroy() {
        McpStdioSession open = session;
        if (open != null) {
            session = null;
            open.close();
            log.info(McpMessage.EMBEDDED_STOPPED.format());
        }
    }
}
