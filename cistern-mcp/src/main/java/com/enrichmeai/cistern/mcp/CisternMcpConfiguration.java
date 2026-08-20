package com.enrichmeai.cistern.mcp;

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
 * <p>Deliberately not a {@code RouterFunction} or anything else on the HTTP surface: MCP is a
 * peer front-end (ARCHITECTURE decision 6) that <em>consumes</em> the HTTP surface over
 * loopback. Streamable-HTTP transport is not offered in v1 — the managed MCP SDK's HTTP
 * server transports are servlet-based and this server is WebFlux; recorded as a decision in
 * the T6.1 PR rather than bridged expensively.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CisternMcpProperties.class)
@ConditionalOnProperty(prefix = "cistern.mcp", name = "enabled", havingValue = "true")
public class CisternMcpConfiguration {

    @Bean
    public McpStdioRunner cisternMcpStdioRunner(CisternMcpProperties mcp, CisternProperties cistern) {
        return new McpStdioRunner(mcp, cistern);
    }
}
