package com.enrichmeai.cistern.webflux;

import com.enrichmeai.cistern.core.CisternException;
import com.enrichmeai.cistern.core.ResourceIdentifier;
import com.enrichmeai.cistern.core.ldp.LdpService;
import com.enrichmeai.cistern.core.ldp.ResourceView;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import reactor.core.publisher.Mono;

/**
 * The seam every handler uses to honour a conditional request (T2.5). It resolves the target's
 * current validators, hands them with the request's conditions to {@link PreconditionEvaluator},
 * and turns the decision into something the caller can compose.
 *
 * <h2>Nothing is written until the preconditions have held</h2>
 * This is the ticket's headline requirement and it is a property of the ordering, not of an
 * error path: the gate below completes — or fails — <em>before</em> the caller subscribes to
 * {@code LdpService.put} or {@code LdpService.delete} at all, so a failed {@code If-Match} does
 * not reach the store, has nothing to roll back, and cannot leave a partial write behind. The
 * gate's own lookup is {@link LdpService#read}, which is read-only. {@code ConditionalRequestHttpTest}
 * proves it against a {@code ResourceStore} that fails the test if {@code put} or {@code delete}
 * is invoked, rather than by inspecting state afterwards — a state check would also pass for an
 * implementation that wrote and then undid the write.
 *
 * <p>It also matches RFC 9110 §13.2.1's placement: preconditions are evaluated "just before it
 * would process the request content (if any) or perform the action associated with the request
 * method". The write handler consequently gates before reading the request body, not after.
 *
 * <h2>Which failures outrank a precondition</h2>
 * §13.2.1: "A server MUST ignore all received preconditions if its response to the same request
 * without those conditions, prior to processing the request content, would have been a status
 * code other than a 2xx (Successful) or 412 (Precondition Failed)." Two consequences are
 * implemented here:
 *
 * <ul>
 *   <li><b>An absent target is method-dependent</b>, which is what {@link AbsentTarget}
 *       expresses. A {@code PUT} to a resource that is not there would be a 201, so its
 *       preconditions are still evaluated — that is exactly what makes {@code If-None-Match: *}
 *       a create-only guard. A {@code DELETE} of a resource that is not there would be a 404,
 *       so its preconditions are ignored and the 404 is allowed to happen.</li>
 *   <li><b>A malformed or absent {@code Content-Type} on a {@code PUT} outranks a
 *       precondition</b>, because that check is a "normal request check" the handler already
 *       performs before this gate is reached: the request is a 400 whatever its
 *       {@code If-Match} said.</li>
 * </ul>
 *
 * <p><b>Known deviation, flagged for the architect.</b> A {@code DELETE} of the storage root
 * carrying a failing {@code If-Match} answers 412, where §13.2.1 argues for the 405 that Solid
 * Protocol §5.4 mandates unconditionally. The root refusal lives inside
 * {@link LdpService#delete}, ahead of the store and inseparable from it without changing core,
 * and this ticket does not touch core. Nothing is written either way — 405 and 412 are both
 * refusals — so the consequence is confined to which refusal a client sees on one exotic
 * combination. The fix belongs with whichever ticket gives core a precondition-aware delete.
 */
@Component
public class ConditionalRequests {

    /**
     * What the request method would do to a target that has no current representation, which is
     * what §13.2.1 turns on. A closed set of two, so an enum rather than a boolean flag whose
     * meaning would have to be recalled at each call site.
     */
    public enum AbsentTarget {

        /** {@code PUT}: absence is a successful create (201), so preconditions are evaluated. */
        IS_CREATED,

        /** {@code DELETE}: absence is a 404, which takes precedence, so they are ignored. */
        IS_REJECTED
    }

    private final LdpService ldp;
    private final PreconditionEvaluator evaluator;

    public ConditionalRequests(LdpService ldp, PreconditionEvaluator evaluator) {
        this.ldp = ldp;
        this.evaluator = evaluator;
    }

    /**
     * The gate an unsafe method composes in front of its write.
     *
     * @param request      the request, for its conditional fields and its method
     * @param target       the resource the method addresses
     * @param absentTarget how §13.2.1 applies when the target has no current representation
     * @return an empty {@code Mono} that completes if the method may proceed, or signals
     *         {@link CisternException.PreconditionFailed} (→ 412 via the single error mapper)
     *         if it may not. Never emits a value, so callers chain it with {@code then}.
     */
    public Mono<Void> requireMayProceed(ServerRequest request, ResourceIdentifier target,
                                        AbsentTarget absentTarget) {
        return Mono.defer(() -> {
            ConditionalRequest conditions = ConditionalRequest.of(request);
            if (conditions.isEmpty()) {
                // No conditional field: no state lookup, so an unconditional write costs
                // exactly what it cost before this ticket.
                return Mono.empty();
            }
            return currentState(target, absentTarget)
                    .flatMap(state -> decide(conditions, request, target, state))
                    .then();
        });
    }

    /**
     * The decision for a {@code GET} or {@code HEAD}, whose 304 is a normal response rather
     * than an error and so is returned to the read handler instead of being signalled.
     *
     * <p>Takes the view the read path already has, so no second read happens, and takes the
     * media type negotiation selected, so the validator compared against is the very one the
     * response would have carried.
     *
     * @param view     the resource as core resolved it
     * @param selected the media type of the representation that would be served
     */
    public PreconditionResult evaluateRead(ServerRequest request, ResourceView view,
                                           MediaType selected) {
        ConditionalRequest conditions = ConditionalRequest.of(request);
        if (conditions.isEmpty()) {
            return PreconditionResult.proceed();
        }
        return evaluator.evaluate(conditions, request.method(),
                TargetState.ofSelected(view, selected));
    }

    /**
     * The 412 detail, naming the field that failed and quoting what the client sent.
     *
     * <p>A message per field rather than one shared entity-tag message: {@code If-Match} fails
     * because <em>nothing</em> matched and {@code If-None-Match} because <em>something did</em>,
     * so a single wording is necessarily wrong for one of them — as a curl transcript of an
     * earlier revision of this class showed, reporting a rejected create-only {@code PUT} as
     * "no current representation matches *".
     */
    String detailFor(PreconditionResult result, ConditionalRequest conditions,
                     ResourceIdentifier target) {
        ConditionalHeader header = result.decidedBy().orElseThrow(IllegalStateException::new);
        return switch (header) {
            case IF_MATCH -> WebfluxMessage.PRECONDITION_IF_MATCH_FAILED
                    .format(header.fieldName(), target.uri(),
                            conditions.conditionOf(header).describe());
            case IF_NONE_MATCH -> WebfluxMessage.PRECONDITION_IF_NONE_MATCH_FAILED
                    .format(header.fieldName(), target.uri(),
                            conditions.conditionOf(header).describe());
            // If-Modified-Since can only ever yield 304, never 412, but the switch stays total
            // so that a future step cannot route through here without choosing its wording.
            case IF_UNMODIFIED_SINCE, IF_MODIFIED_SINCE ->
                    WebfluxMessage.PRECONDITION_MODIFICATION_DATE_FAILED
                            .format(header.fieldName(), target.uri());
        };
    }

    // ---------------------------------------------------------------- internals

    private Mono<PreconditionResult> decide(ConditionalRequest conditions, ServerRequest request,
                                            ResourceIdentifier target, TargetState state) {
        PreconditionResult result = evaluator.evaluate(conditions, request.method(), state);
        return switch (result.outcome()) {
            case PROCEED -> Mono.just(result);
            case PRECONDITION_FAILED -> Mono.error(new CisternException.PreconditionFailed(
                    detailFor(result, conditions, target)));
            // §13.2.2 step 3 reserves 304 for GET and HEAD, and this gate only ever runs for
            // PUT and DELETE, so the evaluator cannot return it here. Signalled rather than
            // silently treated as a success, because reaching it would mean the evaluator and
            // this caller disagree about which methods are reads.
            case NOT_MODIFIED -> Mono.error(new IllegalStateException(
                    WebfluxMessage.NOT_MODIFIED_ON_UNSAFE_METHOD
                            .format(request.method(), target.uri())));
        };
    }

    /**
     * The target's current validators, or an empty {@code Mono} when §13.2.1 says the
     * preconditions must be ignored entirely — in which case the method simply runs and
     * produces whatever it would have produced unconditionally (for a {@code DELETE} of a
     * missing resource, core's own 404).
     *
     * <p>{@link LdpService#find} rather than {@link LdpService#read}: absence is an
     * <em>input</em> to §13.1.1/§13.1.2, which both branch on whether the server "has a current
     * representation for the target resource", so it has to arrive as a value and not as a
     * signal to be caught. There is deliberately no error operator anywhere in this class —
     * ground rule 4 and the guard that enforces it hold here as they do in every handler.
     *
     * <p>It is core's own resolution of a resource either way, so the tags computed from it are
     * exactly the tags a {@code GET} would emit.
     */
    private Mono<TargetState> currentState(ResourceIdentifier target, AbsentTarget absentTarget) {
        return ldp.find(target)
                .map(TargetState::acrossAllRepresentations)
                .switchIfEmpty(Mono.defer(() -> switch (absentTarget) {
                    case IS_CREATED -> Mono.<TargetState>just(new TargetState.Absent());
                    case IS_REJECTED -> Mono.<TargetState>empty();
                }));
    }
}
