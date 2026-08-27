package com.enrichmeai.cistern.mcp;

import com.enrichmeai.cistern.mcp.transport.WebFluxStreamableTransport;

import io.modelcontextprotocol.json.McpJsonDefaults;

import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;

import com.enrichmeai.cistern.webflux.CisternProperties;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the MCP front door into cistern-app, behind {@code cistern.mcp.enabled} (default
 * off): nothing here exists — no beans, no stdio reader, no credential in memory — unless the
 * deployment asked for the door. The tools, the transport and the identity binding are all
 * {@link McpFrontDoor}'s; this class only decides <em>that</em> it runs and hands it the
 * configuration.
 *
 * <p>Two transports, chosen independently. <b>stdio</b> ({@code cistern.mcp.enabled}) serves a
 * client that launches this process. <b>Streamable HTTP</b>
 * ({@code cistern.mcp.http.enabled}) publishes a route so a client that cannot — anything
 * remote — can reach the pod. The second is off by default and deliberately so: it is the
 * switch that takes MCP from "reachable by a process on this machine" to "reachable by
 * whatever can route to this port", and an operator should choose that.
 *
 * <p>Both consume the HTTP surface over loopback exactly as before (ARCHITECTURE decision 6),
 * and both are built by {@link McpFrontDoor} from one definition of the tools — a client
 * arriving over HTTP is offered the same surface, and the same refusals, as one over stdio.
 *
 * <p>Streamable HTTP was absent in v1 because the MCP SDK's HTTP transports are servlet-based
 * and this server is WebFlux. T6.7 supplies the missing piece as
 * {@link com.enrichmeai.cistern.mcp.transport.WebFluxStreamableTransport}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CisternMcpProperties.class)
@ConditionalOnProperty(prefix = "cistern.mcp", name = "enabled", havingValue = "true")
public class CisternMcpConfiguration {

    @Bean
    public McpStdioRunner cisternMcpStdioRunner(CisternMcpProperties mcp, CisternProperties cistern) {
        return new McpStdioRunner(mcp, cistern);
    }

    /**
     * The Streamable HTTP transport.
     *
     * <p>Published as a bean at startup so its routes are mounted, but <em>not</em> bound to a
     * server here: the loopback URL the tools call needs the port, and the port is not known
     * until {@link WebServerInitializedEvent}. {@link McpHttpRunner} binds it then.
     *
     * <p>That window is why the transport answers 503 with "not started" rather than failing:
     * between the routes being mounted and the port being known, a request is early rather
     * than wrong, and 503 is what tells a client to retry.
     */
    @Bean
    @ConditionalOnProperty(prefix = "cistern.mcp.http", name = "enabled", havingValue = "true")
    public WebFluxStreamableTransport cisternMcpHttpTransport() {
        return new WebFluxStreamableTransport(McpJsonDefaults.getMapper(), MCP_HTTP_ENDPOINT);
    }

    /** The routes, mounted alongside the pod's own handlers. */
    @Bean
    @ConditionalOnProperty(prefix = "cistern.mcp.http", name = "enabled", havingValue = "true")
    public RouterFunction<ServerResponse> cisternMcpHttpRoutes(WebFluxStreamableTransport transport) {
        return transport.routes();
    }

    /** Binds the front door to the transport once the server's port is known. */
    @Bean
    @ConditionalOnProperty(prefix = "cistern.mcp.http", name = "enabled", havingValue = "true")
    public McpHttpRunner cisternMcpHttpRunner(
            WebFluxStreamableTransport transport, CisternMcpProperties mcp, CisternProperties cistern) {
        return new McpHttpRunner(transport, mcp, cistern);
    }

    /** Where the Streamable HTTP transport is published, per the specification's convention. */
    static final String MCP_HTTP_ENDPOINT = "/mcp";
}
