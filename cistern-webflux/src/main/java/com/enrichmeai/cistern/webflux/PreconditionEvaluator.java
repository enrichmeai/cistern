package com.enrichmeai.cistern.webflux;

import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

/**
 * RFC 9110 §13.2.2, "Precedence of Preconditions" — the numbered order a recipient "MUST
 * evaluate the request preconditions defined by this specification in", implemented as one
 * method that reads in the same order the section is written.
 *
 * <p>Pure and synchronous: it is handed the request's conditions, the method, and the target's
 * current validator state, and returns a decision. It performs no I/O, touches no store, and
 * knows nothing about status codes — which is what lets it be exercised directly, without an
 * HTTP stack or a pod, over every branch the RFC defines.
 *
 * <h2>The steps, and what each does here</h2>
 * <ol>
 *   <li><b>{@code If-Match}</b> (§13.1.1) — evaluated whenever present, for every method.
 *       False → 412. Strong comparison.</li>
 *   <li><b>{@code If-Unmodified-Since}</b> (§13.1.4) — evaluated only when {@code If-Match}
 *       is absent, because "a recipient MUST ignore If-Unmodified-Since if the request
 *       contains an If-Match header field". False → 412.</li>
 *   <li><b>{@code If-None-Match}</b> (§13.1.2) — evaluated whenever present. False → 304 for
 *       {@code GET}/{@code HEAD}, 412 for every other method. Weak comparison.</li>
 *   <li><b>{@code If-Modified-Since}</b> (§13.1.3) — evaluated only for {@code GET}/{@code
 *       HEAD} and only when {@code If-None-Match} is absent, on both of the RFC's grounds: "a
 *       recipient MUST ignore If-Modified-Since if the request contains an If-None-Match
 *       header field", and "a recipient MUST ignore the If-Modified-Since header field if ...
 *       the request method is neither GET nor HEAD". False → 304.</li>
 *   <li><b>{@code If-Range}</b> (§13.1.5) — <b>not implemented</b>, and step 5 is unreachable
 *       rather than skipped: it applies only "when the method is GET and both Range and
 *       If-Range are present", and Cistern implements no range requests (RFC 9110 §14 makes
 *       them optional). Its fallback — "otherwise, ignore the Range header field and respond
 *       200 (OK)" — is already Cistern's unconditional behaviour. See
 *       {@link ConditionalHeader}.</li>
 *   <li><b>Otherwise</b> — {@link PreconditionOutcome#PROCEED}.</li>
 * </ol>
 *
 * <h2>A failed precondition is never answered with a 2xx</h2>
 * §13.1.1 and §13.1.4 both permit an alternative to 412: an origin server MAY answer 2xx "if
 * the request is a state-changing operation that appears to have already been applied to the
 * selected representation". Cistern deliberately does not, and the RFC's own guidance is the
 * reason — it warns that the shortcut "comes with some risk if multiple user agents are making
 * change requests that are very similar but not cooperative", and that for resources used as a
 * semaphore "an origin server is better off being stringent in sending 412 for every failed
 * precondition on an unsafe method". A pod is exactly that kind of resource: {@code If-Match}
 * is the only lost-update defence a Solid client has, and a 412 it can retry is strictly safer
 * than a 2xx that silently tells it a write happened when none did.
 */
@Component
public class PreconditionEvaluator {

    /**
     * Applies §13.2.2's order to one request.
     *
     * @param conditions the request's parsed conditional fields
     * @param method     the request method — decides 304-versus-412 at step 3, and whether
     *                   step 4 runs at all
     * @param state      the target's current validators; {@link TargetState.Absent} when it has
     *                   no current representation
     * @return the decision, naming the field that made it
     */
    public PreconditionResult evaluate(ConditionalRequest conditions, HttpMethod method,
                                       TargetState state) {
        // Step 1 / step 2: the "lost update" preconditions. Entity tags are presumed more
        // accurate than date validators, so the date field is only consulted in the absence of
        // its entity-tag counterpart (§13.1.4).
        if (conditions.ifMatch().isPresent()) {
            if (!conditions.ifMatch().satisfiedBy(state)) {
                return PreconditionResult.failed(ConditionalHeader.IF_MATCH);
            }
        } else if (conditions.ifUnmodifiedSince().isPresent()
                && !unmodifiedSince(conditions.ifUnmodifiedSince(), state)) {
            return PreconditionResult.failed(ConditionalHeader.IF_UNMODIFIED_SINCE);
        }

        // Step 3 / step 4: the cache-validation preconditions, with the same entity-tag-wins
        // relationship between them (§13.1.3).
        if (conditions.ifNoneMatch().isPresent()) {
            if (!conditions.ifNoneMatch().notSatisfiedBy(state)) {
                return failedRead(method, ConditionalHeader.IF_NONE_MATCH);
            }
        } else if (isRead(method) && conditions.ifModifiedSince().isPresent()
                && !modifiedSince(conditions.ifModifiedSince(), state)) {
            return PreconditionResult.notModified(ConditionalHeader.IF_MODIFIED_SINCE);
        }

        // Step 5 is unreachable (no Range support); step 6.
        return PreconditionResult.proceed();
    }

    /**
     * §13.2.2 step 3: "if false for GET/HEAD, respond 304 (Not Modified); if false for other
     * methods, respond 412 (Precondition Failed)". The one place the outcome of a failed
     * condition depends on the method.
     */
    private static PreconditionResult failedRead(HttpMethod method, ConditionalHeader header) {
        return isRead(method)
                ? PreconditionResult.notModified(header)
                : PreconditionResult.failed(header);
    }

    /**
     * §13.1.4: "If the selected representation's last modification date is earlier than or
     * equal to the date provided in the field value, the condition is true."
     *
     * <p>An absent target has no modification date, and §13.1.4 says a recipient "MUST ignore
     * the If-Unmodified-Since header field if the resource does not have a modification date
     * available" — so the condition is treated as satisfied rather than failed.
     */
    private static boolean unmodifiedSince(Optional<Instant> date, TargetState state) {
        return modificationDateOf(state)
                .map(lastModified -> !lastModified.isAfter(date.orElseThrow()))
                .orElse(true);
    }

    /**
     * §13.1.3: "If the selected representation's last modification date is earlier or equal to
     * the date provided in the field value, the condition is false." Absence is ignored for the
     * same reason as in {@link #unmodifiedSince}.
     */
    private static boolean modifiedSince(Optional<Instant> date, TargetState state) {
        return modificationDateOf(state)
                .map(lastModified -> lastModified.isAfter(date.orElseThrow()))
                .orElse(true);
    }

    private static Optional<Instant> modificationDateOf(TargetState state) {
        return state instanceof TargetState.Present present
                ? Optional.of(present.lastModified())
                : Optional.empty();
    }

    /** The methods §13.2.2 answers with 304 rather than 412, and the only ones step 4 runs for. */
    private static boolean isRead(HttpMethod method) {
        return HttpMethod.GET.equals(method) || HttpMethod.HEAD.equals(method);
    }
}
