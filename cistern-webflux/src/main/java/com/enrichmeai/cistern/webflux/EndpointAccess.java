package com.enrichmeai.cistern.webflux;

/**
 * What a {@link ServerEndpoint} requires of a request before it is served — the closed set of
 * answers, because an endpoint that is not a pod resource cannot be judged by Web Access
 * Control (there is no ACL for a path that stores nothing) and the alternative to naming its
 * policy is guessing it.
 */
public enum EndpointAccess {

    /**
     * Served to anyone: no principal is resolved, no authorization decision is taken and no
     * receipt is recorded. For discovery documents a client must be able to read <em>before</em>
     * it holds a credential — the OAuth protected resource metadata is the first thing an MCP
     * client fetches, in response to the 401 that told it where to look.
     */
    PUBLIC,

    /**
     * Served only to a request whose credential resolved to an authenticated agent. An
     * anonymous request — no credential, or one no resolver accepted — is refused with 401 and
     * the {@code WWW-Authenticate} challenge, exactly as on a pod resource. No Web Access
     * Control decision is taken, because the endpoint names no resource for one to be about;
     * what the agent may then do to the pod is decided per request when the endpoint makes
     * them.
     */
    AUTHENTICATED
}
