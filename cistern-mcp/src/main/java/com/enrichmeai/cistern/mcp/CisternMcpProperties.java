package com.enrichmeai.cistern.mcp;

import java.net.URI;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The MCP front door's configuration (T6.2): {@code cistern.mcp.*}. Off by default — a pod
 * server does not grow an agent door without being asked.
 *
 * <p>The <em>binding</em> is the point: enabling the door names exactly one bearer credential,
 * and every tool call this process serves is made as whoever that credential authenticates —
 * through the server's own resolver chain, like any HTTP client. There is no per-tool or
 * per-call identity, no {@code Agent} change, no privileged path (the #89 ruling: static
 * binding for v1).
 *
 * <p>An enabled door without a credential is refused at bind time, for the same reason
 * T7.7's enforcement guard refuses a credential source without an owner: the misconfiguration
 * would not fail, it would quietly act as the anonymous agent.
 *
 * @param enabled    whether cistern-app serves MCP on its stdio ({@code false} by default)
 * @param credential the bearer credential the connection is bound to — the owner's local
 *                   token or a service principal's secret; as environment,
 *                   {@code CISTERN_MCP_CREDENTIAL}
 * @param baseUrl    where this process should send its own loopback HTTP requests; unset
 *                   (the default) means the server's own port on {@code 127.0.0.1}
 */
@ConfigurationProperties(prefix = "cistern.mcp")
public record CisternMcpProperties(boolean enabled, String credential, URI baseUrl) {

    public CisternMcpProperties {
        if (enabled && (credential == null || credential.isBlank())) {
            throw new IllegalArgumentException(McpMessage.MCP_REQUIRES_CREDENTIAL.format());
        }
    }

    /** The bound credential; only meaningful when {@link #enabled}. */
    BearerCredential boundCredential() {
        return new BearerCredential(credential);
    }
}
