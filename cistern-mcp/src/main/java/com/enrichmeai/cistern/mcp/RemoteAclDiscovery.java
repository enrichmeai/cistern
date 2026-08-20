package com.enrichmeai.cistern.mcp;

import com.enrichmeai.cistern.core.ResourceIdentifier;
import com.enrichmeai.cistern.wac.AclResource;
import com.enrichmeai.cistern.wac.AclScope;
import com.enrichmeai.cistern.wac.EffectiveAcl;

import java.util.Objects;
import java.util.Optional;

import reactor.core.publisher.Mono;

/**
 * The effective-ACL walk, over HTTP: {@code GET <target>.acl}; on 404, the parent's; up to the
 * storage root — the same algorithm as the server's {@code AclDiscovery} and as cistern-cli's
 * walk, done with the bound credential, so the server's own enforcement decides whether this
 * connection may see each ACL at all (an ACL takes Control on what it governs, #112).
 *
 * <p>The naming convention ({@link AclResource}) and the parent relation
 * ({@link ResourceIdentifier#parent()}) are reused rather than restated, so this walk cannot
 * disagree with the server about where an ACL lives or when the root has been reached.
 */
final class RemoteAclDiscovery {

    /** What discovery found: the governing ACL, plus the validator when it is the target's own. */
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

    private final PodHttp http;
    private final PodAddress address;

    RemoteAclDiscovery(PodHttp http, PodAddress address) {
        this.http = Objects.requireNonNull(http, "http");
        this.address = Objects.requireNonNull(address, "address");
    }

    /**
     * The ACL that governs {@code target}; {@link PodProblem.NoAcl} if the walk reaches the
     * storage root without finding one, which means the pod was never seeded.
     */
    Mono<Discovered> findFor(ResourceIdentifier target) {
        Objects.requireNonNull(target, "target");
        return walk(target, AclScope.ACCESS_TO)
                .switchIfEmpty(Mono.error(() -> new PodProblem.NoAcl(target)));
    }

    private Mono<Discovered> walk(ResourceIdentifier candidate, AclScope scope) {
        ResourceIdentifier acl = AclResource.of(candidate);
        return http.fetchAcl(acl, address.requestUriFor(acl))
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
