package com.enrichmeai.cistern.cli;

import com.enrichmeai.cistern.core.ResourceIdentifier;
import com.enrichmeai.cistern.wac.AclResource;
import com.enrichmeai.cistern.wac.AclScope;
import com.enrichmeai.cistern.wac.EffectiveAcl;

import java.util.Objects;
import java.util.Optional;

import reactor.core.publisher.Mono;

/**
 * The effective-ACL walk, over HTTP: {@code GET <target>.acl}; on 404, the parent's; up to the
 * storage root. The same algorithm as {@code AclDiscovery} in cistern-wac — the resource's own
 * ACL under {@link AclScope#ACCESS_TO}, else the nearest ancestor's under
 * {@link AclScope#INHERITED} — done with the caller's credential, so the server's own
 * enforcement decides whether the caller may see each ACL at all.
 *
 * <p>The naming convention ({@link AclResource}) and the parent relation
 * ({@link ResourceIdentifier#parent()}) are reused rather than restated, so this walk cannot
 * disagree with the server's about where an ACL lives or when the root has been reached.
 *
 * <p>Alongside the ACL it reports the validator of the target's <em>own</em> ACL when that is
 * what was found, because that is what a conditional replace needs; an inherited ACL is never
 * written to, so its validator is not carried.
 */
final class RemoteAclDiscovery {

    /** What discovery found: the ACL that governs the target, plus the validator if it is the target's own. */
    record Discovered(EffectiveAcl acl, Optional<EntityTagHeader> ownEtag) {
        Discovered {
            Objects.requireNonNull(acl, "acl");
            Objects.requireNonNull(ownEtag, "ownEtag");
        }

        /** The precondition under which {@code <target>.acl} may now be written. */
        WritePrecondition writePrecondition() {
            return ownEtag.<WritePrecondition>map(WritePrecondition.IfMatch::new)
                    .orElseGet(WritePrecondition.IfNoneMatchAny::new);
        }
    }

    private final AclTransport client;

    RemoteAclDiscovery(AclTransport client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    /**
     * The ACL that governs {@code target}.
     *
     * @return the ACL and how it applies; {@link CliFailure.NoAcl} if the walk reaches the
     *     storage root without finding one, which means the pod was never seeded
     */
    Mono<Discovered> findFor(ResourceIdentifier target) {
        Objects.requireNonNull(target, "target");
        return walk(target, AclScope.ACCESS_TO)
                .switchIfEmpty(Mono.error(() -> new CliFailure.NoAcl(target)));
    }

    private Mono<Discovered> walk(ResourceIdentifier candidate, AclScope scope) {
        ResourceIdentifier acl = AclResource.of(candidate);
        return client.fetch(acl)
                .flatMap(fetch -> switch (fetch) {
                    case AclFetch.Found found -> Mono.just(new Discovered(
                            new EffectiveAcl(found.graph(), scope, candidate),
                            scope == AclScope.ACCESS_TO ? Optional.of(found.etag()) : Optional.empty()));
                    case AclFetch.Absent _ -> ascend(candidate);
                });
    }

    private Mono<Discovered> ascend(ResourceIdentifier candidate) {
        return candidate.parent()
                .map(parent -> Mono.defer(() -> walk(parent, AclScope.INHERITED)))
                .orElseGet(Mono::empty);
    }
}
