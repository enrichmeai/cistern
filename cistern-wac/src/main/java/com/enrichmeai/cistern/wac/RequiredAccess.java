package com.enrichmeai.cistern.wac;

import com.enrichmeai.cistern.core.ResourceIdentifier;

import java.util.List;
import java.util.Objects;

/**
 * What each HTTP method requires, as one table rather than a conditional spread through the
 * handlers (ground rule 7).
 *
 * <p>Derived from the conformance harness's {@code protected-operation} assertions, not from a
 * reading of the spec — the two differ in one place that matters, below. The rows it encodes:
 *
 * <table>
 *   <caption>Method to requirement</caption>
 *   <tr><th>Method</th><th>Requires</th></tr>
 *   <tr><td>{@code GET}, {@code HEAD}, {@code OPTIONS}</td><td>Read on the target</td></tr>
 *   <tr><td>{@code PUT}</td><td>Write on the target</td></tr>
 *   <tr><td>{@code POST}</td><td>Append on the target container</td></tr>
 *   <tr><td>{@code PATCH}</td><td>Append on the target</td></tr>
 *   <tr><td>{@code DELETE}</td><td>Write on the target <strong>and</strong> Write on its parent</td></tr>
 * </table>
 *
 * <p><strong>DELETE requiring the parent is the row that is easy to miss</strong>, and the
 * harness is unambiguous about it: with Write on the resource but nothing on its container
 * ({@code container=no, resource=W}) a delete is <em>403</em>, while Write on the container
 * with the resource inheriting it succeeds. That is the right shape — removing a resource
 * edits its parent's containment triples, so it is a write to the parent as much as to the
 * resource — and implementing only the resource-side check would let an agent granted Write
 * on a single document delete it out of a container it has no rights over.
 *
 * <p>{@code POST} and {@code PATCH} require only Append because {@link AccessMode} treats
 * Append as a subclass of Write: an agent granted Write satisfies them too, so the weaker
 * requirement is the correct one to state. The harness confirms both directions — a
 * container granted only {@code A} accepts a POST, and one granted only {@code W} does as
 * well.
 *
 * <p>Two deliberate simplifications, both erring towards refusing rather than allowing:
 *
 * <ul>
 *   <li><strong>{@code PATCH} is treated as Append regardless of the patch body.</strong> A
 *       patch that deletes triples is really a Write, and reading the body here would mean
 *       consuming it before the handler. Requiring only Append is the <em>weaker</em> check,
 *       so the delete-bearing case is caught where it belongs — the N3 engine already refuses
 *       to delete triples that are not present, and T5.3's follow-up should tighten this to
 *       Write once the parsed patch is available to the enforcement point. Recorded rather
 *       than left implicit.</li>
 *   <li><strong>An unrecognised method requires Write.</strong> Refusing an unknown verb to
 *       everyone but a Write-holder is the safe default; the alternative is deciding that a
 *       method nobody has enumerated needs no permission.</li>
 * </ul>
 */
public final class RequiredAccess {

    private RequiredAccess() {
        // static table only
    }

    /**
     * What {@code method} on {@code target} requires.
     *
     * @param method the HTTP method, case-insensitive
     * @param target the request's target resource
     * @return every requirement that must be satisfied; all of them, not any of them
     */
    public static List<AccessRequirement> forRequest(String method, ResourceIdentifier target) {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(target, "target");

        return switch (method.toUpperCase(java.util.Locale.ROOT)) {
            case "GET", "HEAD", "OPTIONS" -> List.of(new AccessRequirement(target, AccessMode.READ));
            case "PUT" -> List.of(new AccessRequirement(target, AccessMode.WRITE));
            case "POST" -> List.of(new AccessRequirement(target, AccessMode.APPEND));
            case "PATCH" -> List.of(new AccessRequirement(target, AccessMode.APPEND));
            case "DELETE" -> deleteRequirements(target);
            default -> List.of(new AccessRequirement(target, AccessMode.WRITE));
        };
    }

    /**
     * Write on the resource, and Write on the container it will be removed from.
     *
     * <p>The storage root has no parent, so it yields the resource-side check alone — and it
     * is refused earlier anyway: Solid Protocol §5.4 makes {@code DELETE} on the storage root
     * a 405.
     */
    private static List<AccessRequirement> deleteRequirements(ResourceIdentifier target) {
        AccessRequirement onTarget = new AccessRequirement(target, AccessMode.WRITE);
        return target.parent()
                .map(parent -> List.of(onTarget, new AccessRequirement(parent, AccessMode.WRITE)))
                .orElseGet(() -> List.of(onTarget));
    }
}
