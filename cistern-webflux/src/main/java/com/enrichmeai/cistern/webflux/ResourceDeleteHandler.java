package com.enrichmeai.cistern.webflux;

import com.enrichmeai.cistern.core.CisternException;
import com.enrichmeai.cistern.core.ResourceIdentifier;
import com.enrichmeai.cistern.core.ldp.LdpService;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

/**
 * Serves {@code DELETE} (T2.4).
 *
 * <p>Thin to the point of being almost nothing, which is the point: every rule of Solid
 * Protocol §5.4 is decided in {@link LdpService#delete} or in the storage contract beneath it,
 * and arrives here already decided. This class maps a request path to an identifier, asks core
 * to remove it, and — on success only — writes the status.
 *
 * <h2>Why 204</h2>
 * RFC 9110 §9.3.5: a {@code DELETE} that has been enacted and has no further information to
 * supply answers {@code 204 (No Content)}. Nothing survives the delete to describe, so there is
 * no representation to return and no validator to carry.
 *
 * <h2>Errors</h2>
 * Nothing here maps a status code (ground rule 4), and there is no {@code onErrorResume}. The
 * storage root leaves core as {@link CisternException.MethodNotAllowed} (→ 405 + {@code Allow},
 * Solid Protocol §5.4), a non-empty container as {@link CisternException.Conflict} (→ 409), a
 * resource that is not there as {@link CisternException.NotFound} (→ 404), and an unusable
 * request-target as {@link CisternException.BadInput} (→ 400); the single error mapper (T2.6)
 * renders each as {@code application/problem+json}. The whole body sits inside
 * {@code Mono.defer} so that the synchronous throw from {@link RequestPaths} becomes an error
 * signal rather than escaping the reactive chain.
 */
@Component
public class ResourceDeleteHandler {

    private final LdpService ldp;
    private final RequestPaths requestPaths;
    private final ConditionalRequests conditionalRequests;

    public ResourceDeleteHandler(LdpService ldp, RequestPaths requestPaths,
                                 ConditionalRequests conditionalRequests) {
        this.ldp = ldp;
        this.requestPaths = requestPaths;
        this.conditionalRequests = conditionalRequests;
    }

    /**
     * The handler behind {@code DELETE}.
     *
     * <p>The precondition gate (T2.5) sits in front of {@link LdpService#delete}, so a failed
     * {@code If-Match} means nothing is ever asked of the store — RFC 9110 §13.2.1 requires
     * preconditions to be evaluated "just before it would ... perform the action associated
     * with the request method", and a delete has no content to process first.
     *
     * <p>{@link ConditionalRequests.AbsentTarget#IS_REJECTED} because a {@code DELETE} of a
     * resource that is not there is a 404, and §13.2.1 says a server "MUST ignore all received
     * preconditions if its response to the same request without those conditions ... would have
     * been a status code other than a 2xx (Successful) or 412 (Precondition Failed)". So a
     * conditional {@code DELETE} of a missing resource still answers 404, not 412 — the
     * opposite of {@code PUT}, whose absent target is a create.
     */
    public Mono<ServerResponse> delete(ServerRequest request) {
        return Mono.defer(() -> {
            ResourceIdentifier target = requestPaths.identifierFor(request);
            return conditionalRequests
                    .requireMayProceed(request, target, ConditionalRequests.AbsentTarget.IS_REJECTED)
                    .then(ldp.delete(target))
                    .then(ServerResponse.noContent().build());
        });
    }
}
