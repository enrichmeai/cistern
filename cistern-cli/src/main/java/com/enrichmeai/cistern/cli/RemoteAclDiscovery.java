package com.enrichmeai.cistern.cli;

import com.enrichmeai.cistern.core.ResourceIdentifier;
import com.enrichmeai.cistern.core.vocab.Acl;
import com.enrichmeai.cistern.wac.AclResource;
import com.enrichmeai.cistern.wac.AclScope;
import com.enrichmeai.cistern.wac.EffectiveAcl;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;

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
 * <p>An ACL whose rules name the pod's resources only under another origin than the target's
 * — the server's {@code cistern.base-url} differs from {@code --base} — is a
 * {@link CliFailure.BaseMismatch}: anything built from it names nothing the server reads, and a
 * fresh ACL built from an inherited one would re-state nobody's Control.
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

    /** The predicates whose objects say which resources an ACL's rules are about. */
    private static final List<Property> TARGET_PREDICATES = List.of(Acl.ACCESS_TO, Acl.DEFAULT);

    private static final Comparator<PodOrigin> ORIGIN_ORDER = Comparator.comparing(PodOrigin::toString);

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
        return walk(target, target, AclScope.ACCESS_TO)
                .switchIfEmpty(Mono.error(() -> new CliFailure.NoAcl(target)));
    }

    private Mono<Discovered> walk(ResourceIdentifier target, ResourceIdentifier candidate, AclScope scope) {
        ResourceIdentifier acl = AclResource.of(candidate);
        return client.fetch(acl)
                .flatMap(fetch -> switch (fetch) {
                    case AclFetch.Found found -> foreignOrigin(found.graph(), candidate)
                            .<Mono<Discovered>>map(named -> Mono.error(new CliFailure.BaseMismatch(
                                    target, named, PodOrigin.of(candidate.uri()).orElseThrow())))
                            .orElseGet(() -> Mono.just(new Discovered(
                                    new EffectiveAcl(found.graph(), scope, candidate),
                                    scope == AclScope.ACCESS_TO ? Optional.of(found.etag()) : Optional.empty())));
                    case AclFetch.Absent _ -> ascend(target, candidate);
                });
    }

    private Mono<Discovered> ascend(ResourceIdentifier target, ResourceIdentifier candidate) {
        return candidate.parent()
                .map(parent -> Mono.defer(() -> walk(target, parent, AclScope.INHERITED)))
                .orElseGet(Mono::empty);
    }

    /**
     * The origin the ACL's rules name instead of {@code candidate}'s, if they name resources and
     * none of them under {@code candidate}'s origin. An ACL that names no resource at all (empty,
     * or rules with no target) is left to the service, which handles it as it always has.
     */
    private static Optional<PodOrigin> foreignOrigin(Model acl, ResourceIdentifier candidate) {
        Optional<PodOrigin> own = PodOrigin.of(candidate.uri());
        Set<PodOrigin> named = new TreeSet<>(ORIGIN_ORDER);
        for (Property predicate : TARGET_PREDICATES) {
            acl.listObjectsOfProperty(predicate).forEachRemaining(object -> originOf(object).ifPresent(named::add));
        }
        if (own.isEmpty() || named.isEmpty() || named.contains(own.get())) {
            return Optional.empty();
        }
        return Optional.of(named.iterator().next());
    }

    /** The origin of an IRI node; empty for a literal, a blank node, or an IRI {@link URI} rejects. */
    private static Optional<PodOrigin> originOf(RDFNode node) {
        if (!node.isURIResource()) {
            return Optional.empty();
        }
        try {
            return PodOrigin.of(new URI(node.asResource().getURI()));
        } catch (URISyntaxException notAUri) {
            return Optional.empty();
        }
    }
}
