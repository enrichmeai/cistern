package com.enrichmeai.cistern.core;

import java.net.URI;
import java.util.Objects;
import java.util.Optional;

/**
 * Who is making a request: a WebID, or nobody.
 *
 * <p>Lives in cistern-core rather than in cistern-wac or cistern-auth because three modules
 * need it and none of them should depend on the others: authentication <em>produces</em> an
 * Agent, authorization <em>consumes</em> one, and the HTTP and MCP front-ends carry it. A
 * value class rather than a bare {@code String} or {@code URI} (ground rule 7), so that
 * "anonymous" is a state of the type rather than a null every caller must remember to check.
 *
 * <p>Carries the <em>client</em> alongside the WebID so a policy can one day say "Alice, but
 * only via this application" (issue #89, ruled 2026-08-23; {@code
 * docs/ideas/agent-scoped-delegation.md}). Taken now because T4.1's capture from a real Solid
 * identity provider settled the open question: the <strong>access</strong> token carries
 * {@code client_id} (CSS 7.2.0 emits it; {@code azp} appears only on the ID token), so the
 * client is knowable at authentication with no extra round trip. Adding the component later
 * would have been a cross-cutting refactor against a frozen conformance baseline.
 *
 * <p>Nothing consumes {@link #client()} yet — WAC still matches on the WebID alone. It is
 * carried so that the intersection cap ({@code effective = user ∩ client}), per-client
 * grants, and the MCP identity binding land without touching this record again.
 *
 * @param webId  the authenticated WebID, or empty for an unauthenticated request
 * @param client the application the request came through, or empty when the credential names
 *               none — including when it names one that is not a URI, which a
 *               client-credentials {@code client_id} typically is not
 */
public record Agent(Optional<URI> webId, Optional<URI> client) {

    /**
     * A request that proved no identity. Distinct from "an agent whose WebID happens to match
     * nothing": WAC treats the two differently, since {@code acl:AuthenticatedAgent} matches
     * any agent that authenticated and no unauthenticated one, and the HTTP layer owes an
     * unauthenticated denial 401 rather than 403.
     */
    public static final Agent ANONYMOUS = new Agent(Optional.empty(), Optional.empty());

    public Agent {
        Objects.requireNonNull(webId, "webId");
        Objects.requireNonNull(client, "client");
    }

    /** An authenticated agent identified by {@code webId}, through no named client. */
    public static Agent of(URI webId) {
        return new Agent(Optional.of(Objects.requireNonNull(webId, "webId")), Optional.empty());
    }

    /**
     * An authenticated agent identified by {@code webId}, acting through {@code client}.
     *
     * @param client the client, or empty when the credential named none
     */
    public static Agent of(URI webId, Optional<URI> client) {
        return new Agent(Optional.of(Objects.requireNonNull(webId, "webId")),
                Objects.requireNonNull(client, "client"));
    }

    /** Whether this request proved an identity — i.e. whether {@code acl:AuthenticatedAgent} matches. */
    public boolean isAuthenticated() {
        return webId.isPresent();
    }
}
