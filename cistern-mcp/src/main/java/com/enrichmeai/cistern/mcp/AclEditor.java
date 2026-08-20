package com.enrichmeai.cistern.mcp;

import com.enrichmeai.cistern.core.ResourceIdentifier;
import com.enrichmeai.cistern.wac.EffectiveAcl;
import com.enrichmeai.cistern.wac.GrantOutcome;
import com.enrichmeai.cistern.wac.GrantRequest;
import com.enrichmeai.cistern.wac.GrantService;
import com.enrichmeai.cistern.wac.RevokeRequest;

import java.util.Objects;
import java.util.function.Function;

import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * Read the effective ACL, let {@link GrantService} say what it should become, write it back —
 * conditionally, and once more from a fresh read if the write found the ACL changed. The same
 * discover → transform → conditional-write sequence as cistern-cli's editor, because it is the
 * same operation: what an owner editing the file by hand would do, under the caller's own
 * credential, so the server enforces Control (T5.7's decision, inherited).
 *
 * <p>The transformation is the pure service's. A {@code revoke} the service refuses because it
 * would drop Control signals {@code CisternException.Conflict} and is <em>not</em> retried —
 * only a lost conditional write ({@link PodProblem.PreconditionFailed}) is, exactly once; a
 * second loss is reported as {@link PodProblem.AclEditConflict} with nothing written.
 */
final class AclEditor {

    /** One automatic re-read-and-retry on 412; a second conflict is reported, not fought. */
    static final long RETRIES_ON_CONFLICT = 1;

    private final RemoteAclDiscovery discovery;
    private final PodHttp http;
    private final PodAddress address;
    private final GrantService service;

    AclEditor(RemoteAclDiscovery discovery, PodHttp http, PodAddress address, GrantService service) {
        this.discovery = Objects.requireNonNull(discovery, "discovery");
        this.http = Objects.requireNonNull(http, "http");
        this.address = Objects.requireNonNull(address, "address");
        this.service = Objects.requireNonNull(service, "service");
    }

    /** Apply {@code request}; the outcome describes what the target's ACL now says. */
    Mono<GrantOutcome> grant(GrantRequest request) {
        Objects.requireNonNull(request, "request");
        return edit(request.target(), current -> service.grant(current, request));
    }

    /** Apply {@code request}; the outcome describes what the target's ACL now says. */
    Mono<GrantOutcome> revoke(RevokeRequest request) {
        Objects.requireNonNull(request, "request");
        return edit(request.target(), current -> service.revoke(current, request));
    }

    /**
     * Discover → transform → write, as one re-subscribable unit so the retry starts again
     * from discovery and never writes a graph computed against a stale read.
     */
    private Mono<GrantOutcome> edit(ResourceIdentifier target,
                                    Function<EffectiveAcl, GrantOutcome> transform) {
        return Mono.defer(() -> discovery.findFor(target)
                        .flatMap(discovered -> {
                            GrantOutcome outcome = transform.apply(discovered.acl());
                            if (!outcome.changed()) {
                                return Mono.just(outcome);
                            }
                            ResourceIdentifier acl = outcome.aclResource();
                            return http.putAcl(acl, address.requestUriFor(acl),
                                            outcome.aclGraph(), discovered.writePrecondition())
                                    .thenReturn(outcome);
                        }))
                .retryWhen(Retry.max(RETRIES_ON_CONFLICT)
                        .filter(PodProblem.PreconditionFailed.class::isInstance)
                        .onRetryExhaustedThrow((spec, signal) ->
                                new PodProblem.AclEditConflict(
                                        ((PodProblem.PreconditionFailed) signal.failure()).target())));
    }
}
