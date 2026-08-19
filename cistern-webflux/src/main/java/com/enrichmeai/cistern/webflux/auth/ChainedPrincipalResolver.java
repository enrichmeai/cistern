package com.enrichmeai.cistern.webflux.auth;

import com.enrichmeai.cistern.core.Agent;

import java.util.List;
import java.util.Objects;

import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Asks each resolver in turn; the first authenticated {@link Agent} wins, else
 * {@link Agent#ANONYMOUS} (T4.0, #88).
 *
 * <p>This is the whole of the "resolver seam" from {@code docs/ideas/first-user-path.md}: a
 * pod can accept the owner's local credential, a service principal's credential and an OIDC
 * JWT at the same time, and the enforcement path — {@code AuthorizationFilter}, then
 * {@code AccessControl} — neither knows nor cares which one spoke. Adding a way to prove
 * identity is adding a member here; it is never a change to how a decision is made.
 *
 * <p><strong>Ordering is meaningful.</strong> Members are consulted in the order given and
 * lazily — a member is not invoked once an earlier one has authenticated the request — so
 * cheap, local checks (a constant-time secret comparison) go before ones that may cost a
 * signature verification or a key fetch. Two members that would both accept the same
 * presented credential are a configuration mistake, and the earlier one wins.
 *
 * <p>Inherits every member's contract ({@link PrincipalResolver}): a member never signals an
 * error for a bad credential, and this class does not swallow one that does. A member that
 * errors has a bug, and a bug should surface as the failure it is rather than as a 401 that
 * looks like a wrong password.
 */
public final class ChainedPrincipalResolver implements PrincipalResolver {

    private final List<PrincipalResolver> ordered;

    /**
     * @param ordered the members, most preferred first; may be empty, in which case every
     *                request is anonymous
     */
    public ChainedPrincipalResolver(List<PrincipalResolver> ordered) {
        this.ordered = List.copyOf(Objects.requireNonNull(ordered, "ordered"));
    }

    @Override
    public Mono<Agent> resolve(ServerWebExchange exchange) {
        return Flux.fromIterable(ordered)
                // concatMap, not flatMap: the members run one at a time, in order, and next()
                // cancels the rest as soon as one authenticates — so the JWT resolver never
                // does its work for a request the owner token already accepted.
                .concatMap(resolver -> resolver.resolve(exchange))
                .filter(Agent::isAuthenticated)
                .next()
                .defaultIfEmpty(Agent.ANONYMOUS);
    }

    /** The members, in the order consulted. Read-only; for wiring diagnostics and tests. */
    public List<PrincipalResolver> members() {
        return ordered;
    }

    /**
     * A resolver another module adds to the chain by declaring a bean of this type.
     *
     * <p>Distinct from {@link PrincipalResolver} on purpose. The chain is the one
     * {@code PrincipalResolver} bean the filter injects; a second bean of that type would
     * either <em>replace</em> it (the chain is {@code @ConditionalOnMissingBean}) or make the
     * injection ambiguous. Wrapping the contribution in its own type means declaring one adds
     * a member and touches nothing else. Position is fixed by the wiring — after the local
     * secrets, before anonymous — and several members order among themselves by
     * {@code @Order}, as any Spring collection does.
     *
     * @param resolver the member to add
     */
    public record Member(PrincipalResolver resolver) {

        public Member {
            Objects.requireNonNull(resolver, "resolver");
        }
    }
}
