package com.enrichmeai.cistern.wac;

import com.enrichmeai.cistern.core.ResourceIdentifier;

import java.net.URI;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * "Let {@code grantee} do {@code modes} to {@code target}" — optionally only through
 * {@code clients} — the input to {@link GrantService#grant}.
 *
 * <p>Modes are stored closed under implication (a Write grant carries Append, see
 * {@link AccessMode#withImplied()}), so the request already says exactly what the resulting
 * authorization will say and no later step can forget the rule.
 *
 * <p><strong>The grantee is the grant; the clients only narrow it</strong> (ADR 0004,
 * AD-DEL-1, AD-DEL-2). A request always names whom it is for in portable Web Access Control
 * terms — a WebID or the public — and a {@code cistern:client} constraint is a reduction of
 * that grant, never a way to name someone. That is what makes the grant safe to export: a
 * server that does not read Cistern's terms applies the WebID's grant as it stands, which is a
 * grant the owner already accepted as permanent. So a request with clients and no grantee is
 * refused here, at the authoring boundary, with the reason: {@code Grantee} is sealed to a
 * WebID or the public and there is no client shape to give it, which is the structural half of
 * the same rule.
 *
 * <p>There is no expiry here. {@code docs/INTEGRATION.md} §6.3 sketches a {@code validUntil}
 * component; it belongs to T5.8 (#92), which also has to teach the engine to honour it, and a
 * field the engine ignores would be a grant that silently never expires. Left out rather than
 * accepted-and-ignored.
 *
 * @param target  the resource the grant is on — a container (the grant applies to it and, via
 *                {@code acl:default}, to everything inside it) or a document
 * @param grantee who is granted
 * @param modes   what is granted; at least one
 * @param clients the clients through which the grantee may exercise the grant — OAuth client
 *                identifiers as absolute URIs, what the access token's {@code client_id}
 *                carries; empty means through any client, or none, exactly as a grant read
 *                before delegation existed
 */
public record GrantRequest(
        ResourceIdentifier target, Grantee grantee, Set<AccessMode> modes, Set<URI> clients) {

    public GrantRequest {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(modes, "modes");
        Objects.requireNonNull(clients, "clients");
        if (grantee == null) {
            throw new IllegalArgumentException(WacMessage.GRANT_WITHOUT_GRANTEE.format(target.uri()));
        }
        if (modes.isEmpty()) {
            throw new IllegalArgumentException(WacMessage.GRANT_WITHOUT_MODES.format(target.uri()));
        }
        if (AclResource.isAcl(target)) {
            throw new IllegalArgumentException(WacMessage.TARGET_IS_AN_ACL.format(target.uri()));
        }
        for (URI client : clients) {
            if (!Objects.requireNonNull(client, "client").isAbsolute()) {
                throw new IllegalArgumentException(WacMessage.CLIENT_NOT_ABSOLUTE.format(client));
            }
        }
        Set<AccessMode> closed = EnumSet.noneOf(AccessMode.class);
        for (AccessMode mode : modes) {
            closed.addAll(mode.withImplied());
        }
        modes = Collections.unmodifiableSet(closed);
        clients = Collections.unmodifiableSet(new LinkedHashSet<>(clients));
    }

    /** An unconstrained grant: the grantee may exercise it through any client, or none. */
    public GrantRequest(ResourceIdentifier target, Grantee grantee, Set<AccessMode> modes) {
        this(target, grantee, modes, Set.of());
    }

    /** Whether the grant is constrained to particular clients. */
    public boolean isClientConstrained() {
        return !clients.isEmpty();
    }
}
