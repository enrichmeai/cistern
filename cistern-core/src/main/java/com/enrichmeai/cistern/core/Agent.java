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
 * <p>Deliberately <strong>not</strong> carrying a client identity yet. {@code
 * docs/ideas/agent-scoped-delegation.md} proposes {@code Agent(URI webId, Optional<URI>
 * client)} so a policy can say "Alice, but only via this application", and that decision is
 * open and the owner's to take — it is entangled with whether ACP moves from a v1 non-goal.
 * The seam is here: adding a component to this record is the whole change, and every
 * consumer already goes through {@link #webId()} rather than passing a raw URI around.
 *
 * @param webId the authenticated WebID, or empty for an unauthenticated request
 */
public record Agent(Optional<URI> webId) {

    /**
     * A request that proved no identity. Distinct from "an agent whose WebID happens to match
     * nothing": WAC treats the two differently, since {@code acl:AuthenticatedAgent} matches
     * any agent that authenticated and no unauthenticated one, and the HTTP layer owes an
     * unauthenticated denial 401 rather than 403.
     */
    public static final Agent ANONYMOUS = new Agent(Optional.empty());

    public Agent {
        Objects.requireNonNull(webId, "webId");
    }

    /** An authenticated agent identified by {@code webId}. */
    public static Agent of(URI webId) {
        return new Agent(Optional.of(Objects.requireNonNull(webId, "webId")));
    }

    /** Whether this request proved an identity — i.e. whether {@code acl:AuthenticatedAgent} matches. */
    public boolean isAuthenticated() {
        return webId.isPresent();
    }
}
