package com.enrichmeai.cistern.wac;

import com.enrichmeai.cistern.core.ResourceIdentifier;
import com.enrichmeai.cistern.core.ResourceStore;
import com.enrichmeai.cistern.core.StoredResource;
import com.enrichmeai.cistern.core.rdf.RdfIo;

import java.util.Objects;

import org.apache.jena.rdf.model.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Finds the ACL that governs a resource, by the algorithm WAC specifies:
 *
 * <blockquote>
 * "If resource has an associated aclResource with a representation, return aclResource.
 * Otherwise, repeat using the container resource of resource."
 * </blockquote>
 *
 * <p>So: the resource's own ACL if it has one, else the nearest ancestor container that does —
 * "a member resource inherits Authorizations from the closest container resource (heading
 * towards the root)". The walk stops at the storage root, which {@link ResourceIdentifier} is
 * already the authority on: {@code parent()} is empty there and nowhere else.
 *
 * <p>Two decisions worth stating, because both are places where the obvious implementation is
 * subtly more permissive than the spec:
 *
 * <ul>
 *   <li><strong>An ACL that exists but does not parse denies; it does not fall through.</strong>
 *       Continuing the walk past a broken ACL would silently substitute an ancestor's rules for
 *       the ones the owner actually wrote — and an ancestor's {@code acl:default} is very often
 *       the more generous of the two. A corrupt ACL must fail closed.</li>
 *   <li><strong>An ACL that exists but is empty still terminates the walk.</strong> The spec's
 *       condition is "has an associated aclResource with a representation", not "with a useful
 *       one". An empty ACL is a deliberate statement — it denies everyone — and treating it as
 *       absent would let a parent's defaults leak back in, which is the opposite of what
 *       writing an empty ACL means.</li>
 * </ul>
 *
 * <p>Reactive throughout and free of blocking I/O: the walk is a chain of {@code Mono}s built
 * lazily by {@link Mono#defer}, so a deep hierarchy costs one store lookup per level and never
 * recurses on the calling stack.
 */
public final class AclDiscovery {

    private static final Logger log = LoggerFactory.getLogger(AclDiscovery.class);

    private final ResourceStore store;

    public AclDiscovery(ResourceStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    /**
     * The effective ACL for {@code target}.
     *
     * @return the ACL and the scope under which it applies, or empty if neither the resource
     *     nor any ancestor up to the storage root has one. Empty means <em>deny</em>: WAC
     *     requires the root container's ACL to exist, so its absence is a misconfigured pod
     *     rather than a licence to allow.
     */
    public Mono<EffectiveAcl> findFor(ResourceIdentifier target) {
        Objects.requireNonNull(target, "target");
        return walk(target, AclScope.ACCESS_TO)
                .switchIfEmpty(Mono.fromRunnable(
                        () -> log.warn(WacMessage.NO_ACL_TO_THE_ROOT.format(target.uri()))));
    }

    /**
     * Look for an ACL on {@code candidate}; if there is none, continue with its parent.
     *
     * <p>{@code scope} is {@link AclScope#ACCESS_TO} only on the first step — once the walk has
     * ascended, whatever it finds is inherited, and only {@code acl:default} authorizations in
     * it apply.
     */
    private Mono<EffectiveAcl> walk(ResourceIdentifier candidate, AclScope scope) {
        ResourceIdentifier aclIdentifier = AclResource.of(candidate);
        return store.get(aclIdentifier)
                .map(stored -> new EffectiveAcl(parse(stored, aclIdentifier), scope, candidate))
                .switchIfEmpty(Mono.defer(() -> ascend(candidate)));
    }

    /** Continue the walk at the parent container, or stop if this was the storage root. */
    private Mono<EffectiveAcl> ascend(ResourceIdentifier candidate) {
        return candidate.parent()
                .map(parent -> walk(parent, AclScope.INHERITED))
                .orElseGet(Mono::empty);
    }

    /**
     * Parse a stored ACL, relative to the ACL's own URI so that the relative IRIs an ACL
     * conventionally uses ({@code <#owner>}, {@code <./>}) resolve the way its author meant.
     *
     * <p>A parse failure propagates as an error rather than being swallowed: see the class
     * note — falling through to an ancestor here would substitute more permissive rules for
     * broken ones.
     */
    private static Model parse(StoredResource stored, ResourceIdentifier aclIdentifier) {
        try {
            return RdfIo.parse(stored.representation(), aclIdentifier);
        } catch (RuntimeException e) {
            log.warn(WacMessage.UNPARSEABLE_ACL.format(aclIdentifier.uri()));
            throw e;
        }
    }
}
