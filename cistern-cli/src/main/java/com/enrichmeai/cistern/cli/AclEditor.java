package com.enrichmeai.cistern.cli;

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
 * conditionally, and once more from a fresh read if the write found the ACL changed.
 *
 * <p>The transformation is the pure service's; the only decisions made here are the ones that
 * need I/O: which precondition to write under (from how discovery found the ACL), whether to
 * write at all ({@link GrantOutcome#changed()}), and the single retry. A 412 that survives the
 * retry is reported as {@link CliFailure.Conflict}, and nothing has been written.
 */
final class AclEditor {

    /** One automatic re-read-and-retry on 412; a second conflict is reported, not fought. */
    static final long RETRIES_ON_CONFLICT = 1;

    private final RemoteAclDiscovery discovery;
    private final AclTransport client;
    private final GrantService service;

    AclEditor(RemoteAclDiscovery discovery, AclTransport client, GrantService service) {
        this.discovery = Objects.requireNonNull(discovery, "discovery");
        this.client = Objects.requireNonNull(client, "client");
        this.service = Objects.requireNonNull(service, "service");
    }

    /** Apply {@code request}; the outcome describes what the target's ACL now says. */
    Mono<GrantOutcome> grant(GrantRequest request) {
        Objects.requireNonNull(request, "request");
        return edit(request.target(), current -> service.grant(current, request));
    }

    /**
     * Apply {@code request}; the outcome describes what the target's ACL now says. A revoke the
     * service refuses (it would drop Control) surfaces as {@code CisternException.Conflict}.
     */
    Mono<GrantOutcome> revoke(RevokeRequest request) {
        Objects.requireNonNull(request, "request");
        return edit(request.target(), current -> service.revoke(current, request));
    }

    /**
     * Discover → transform → write, as one re-subscribable unit so a retry starts again from
     * discovery and never writes a graph computed against a stale read.
     */
    private Mono<GrantOutcome> edit(ResourceIdentifier target, Function<EffectiveAcl, GrantOutcome> transform) {
        return Mono.defer(() -> discovery.findFor(target)
                        .flatMap(discovered -> {
                            GrantOutcome outcome = transform.apply(discovered.acl());
                            if (!outcome.changed()) {
                                return Mono.just(outcome);
                            }
                            return client.put(outcome.aclResource(), outcome.aclGraph(), discovered.writePrecondition())
                                    .thenReturn(outcome);
                        }))
                .retryWhen(Retry.max(RETRIES_ON_CONFLICT)
                        .filter(CliFailure.Conflict.class::isInstance)
                        .onRetryExhaustedThrow((spec, signal) -> signal.failure()));
    }
}
