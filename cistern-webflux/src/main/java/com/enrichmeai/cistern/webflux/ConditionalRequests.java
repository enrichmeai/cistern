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
 *   <li><b>A method the resource does not support outranks a precondition.</b> An unsupported
 *       method is a 405 (RFC 9110 §15.5.6) whatever the request's {@code If-Match} says, so the
 *       preconditions are ignored and the request proceeds to the handler that produces it.
 *       Decided by {@link ResourceKind#permits} — the table {@code Allow} is rendered from — so
 *       Solid Protocol §5.4's storage root is covered without this class ever asking whether a
 *       resource <em>is</em> the root, and §5.4's other 405 will be covered for free.</li>
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
 * <p>"Ignored" throughout means the preconditions do not change the answer, never that the
 * request succeeds: the method still runs and still produces its own 404, 405 or 400.
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
            return ldp.find(target)
                    .map(view -> resultFor(conditions, request, view))
                    .switchIfEmpty(Mono.fromSupplier(
                            () -> resultForAbsentTarget(conditions, request, absentTarget)))
                    .flatMap(result -> apply(result, conditions, request, target))
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
        if (conditions.isEmpty() || !supportsMethod(view, request)) {
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

    /**
     * The decision for a target that exists.
     *
     * <p>Method applicability is checked first, because RFC 9110 §13.2.1 puts it first: a
     * method the resource does not support answers 405 (§15.5.6) unconditionally, and 405 is
     * "a status code other than a 2xx (Successful) or 412 (Precondition Failed)", so the
     * preconditions "MUST" be ignored — "redirects and failures that can be detected before
     * significant processing occurs take precedence over the evaluation of preconditions".
     * Ignoring them means the request proceeds to the handler that produces the 405; it does
     * not mean it succeeds.
     *
     * <p>Concretely this is Solid Protocol §5.4's storage root: a {@code DELETE} of it answers
     * 405 whether its {@code If-Match} matches, is stale, or is absent.
     */
    private PreconditionResult resultFor(ConditionalRequest conditions, ServerRequest request,
                                         ResourceView view) {
        if (!supportsMethod(view, request)) {
            return PreconditionResult.proceed();
        }
        return evaluator.evaluate(conditions, request.method(),
                TargetState.acrossAllRepresentations(view));
    }

    /**
     * The decision for a target that has no current representation, which §13.2.1 makes
     * method-dependent — see {@link AbsentTarget}. There is no kind to consult here: a resource
     * that does not exist advertises no method set, and the response the preconditions would
     * have to defer to is core's 404 rather than a 405.
     */
    private PreconditionResult resultForAbsentTarget(ConditionalRequest conditions,
                                                     ServerRequest request,
                                                     AbsentTarget absentTarget) {
        return switch (absentTarget) {
            case IS_CREATED -> evaluator.evaluate(conditions, request.method(),
                    new TargetState.Absent());
            case IS_REJECTED -> PreconditionResult.proceed();
        };
    }

    /**
     * RFC 9110 §13.2.1's applicability test, in one place for every method and every kind.
     *
     * <p>Driven off {@link ResourceKind#permits} — the same table {@code Allow} is rendered
     * from — rather than off a test like "is this the storage root". That is what makes it a
     * general implementation of the rule instead of a special case: it already covers §5.4's
     * other 405 and any future kind with a narrower method set, and there is no second place
     * for "what this resource supports" to be decided.
     *
     * <p>It cannot fire for {@code GET} or {@code HEAD} today, since every kind supports them
     * (Solid Protocol §5.2 requires it); the read path consults it anyway so the rule has one
     * implementation rather than two that could drift.
     */
    private static boolean supportsMethod(ResourceView view, ServerRequest request) {
        return ResourceKind.of(view).permits(request.method());
    }

    /** Turns a decision into the signal — or the silence — the caller composes with. */
    private Mono<Void> apply(PreconditionResult result, ConditionalRequest conditions,
                             ServerRequest request, ResourceIdentifier target) {
        return switch (result.outcome()) {
            case PROCEED -> Mono.empty();
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
}
