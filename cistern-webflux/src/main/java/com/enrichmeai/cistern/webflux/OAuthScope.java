package com.enrichmeai.cistern.webflux;

/**
 * The scope values a client uses "in authorization requests to request access to this
 * protected resource" (RFC 9728 §2, {@code scopes_supported}) — the closed set this server
 * publishes.
 *
 * <p>One value, and deliberately not {@code openid}. Solid-OIDC §5 has the client request the
 * {@code webid} scope, and that scope is what yields the {@code webid} claim this server reads
 * to name the agent (T4.0, {@code cistern.auth.oidc.webid-claim}); it is the scope that means
 * "reach this pod as myself". {@code openid} asks the authorization server for an ID token,
 * which the pod never sees, so it is not a scope of this resource — and a metadata document
 * that listed it would have an MCP client register with an authorization server for a scope
 * the resource does not use.
 */
enum OAuthScope {

    /** Solid-OIDC §5: the scope that yields the {@code webid} claim naming the agent. */
    WEBID("webid");

    private final String token;

    OAuthScope(String token) {
        this.token = token;
    }

    /** The scope token as it appears in {@code scope} parameters and in the document. */
    String token() {
        return token;
    }
}
