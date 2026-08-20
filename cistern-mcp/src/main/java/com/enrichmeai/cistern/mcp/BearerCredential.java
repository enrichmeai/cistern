package com.enrichmeai.cistern.mcp;

/**
 * The one credential this MCP connection is bound to (T6.2): presented as
 * {@code Authorization: Bearer <credential>} on <strong>every</strong> HTTP request the front
 * door makes, and resolved by the server's own {@code ChainedPrincipalResolver} — the owner's
 * local token, a service-principal secret or an OIDC JWT, exactly as for any other client.
 * The MCP layer never knows or cares which; it holds a secret, not an identity.
 *
 * <p>A type rather than a {@code String} so a secret cannot be confused with any other text on
 * its way from the environment to the header, and so {@code toString} can refuse to leak it.
 *
 * @param value the secret, non-blank
 */
record BearerCredential(String value) {

    private static final String SCHEME_PREFIX = "Bearer ";

    private static final String REDACTED = "BearerCredential[redacted]";

    BearerCredential {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(McpMessage.MCP_REQUIRES_CREDENTIAL.format());
        }
    }

    /** The {@code Authorization} field value. */
    String headerValue() {
        return SCHEME_PREFIX + value;
    }

    @Override
    public String toString() {
        return REDACTED;
    }
}
