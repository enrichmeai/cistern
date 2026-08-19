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
 *   <tr><td>{@code GET}/{@code HEAD} {@code ?receipts}</td><td>Control on the target — see {@link #forReceipts} (T5.9)</td></tr>
 *   <tr><td><em>any</em> method on an ACL resource</td><td>Control on the resource the ACL governs — see {@link #forAcl} (#112)</td></tr>
 * </table>
 *
 * <p><strong>The ACL row comes first</strong>: when the target is an ACL resource
 * ({@link AclResource#isAcl}) the method is not consulted at all. WAC (Authorization
 * Evaluation, "Reading and Writing Resources"): "when an operation requests to read and
 * write an ACL resource, the server MUST match an Authorization allowing the
 * {@code acl:Control} access privilege on the resource" — and its note on method mapping is
 * blunter still: "when the target of the HTTP request is the ACL resource, the operation can
 * only be allowed with the {@code acl:Control} access mode". Without this row a public Read
 * on a container made its ACL publicly readable, disclosing who holds access, and Write on
 * the container let a non-controller delete the ACL — which is not a deletion but a
 * silent widening, since the resource then inherits its parent's defaults.
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

        if (AclResource.isAcl(target)) {
            return forAcl(target);
        }
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
     * What any request targeting an ACL resource requires: <strong>Control on the resource the
     * ACL governs</strong>, whatever the method (#112).
     *
     * <p>On the governed resource, not on the ACL itself, and that is not a nicety. An ACL has
     * no ACL of its own — WAC's discovery walk, asked about {@code /notes/hello.acl}, would find
     * nothing at {@code /notes/hello.acl.acl} and ascend to the <em>container's</em> defaults,
     * skipping {@code /notes/hello.acl} entirely — so a requirement stated on the ACL would be
     * judged by the wrong policy: an agent granted Control in the document's own ACL could not
     * read it, and one holding only the container's default could. Stated on the governed
     * resource, the requirement is judged by exactly the effective ACL that governs it, which
     * is what the spec means by "the resource" in "Control on the resource".
     *
     * <p>Every method, including {@code DELETE} and {@code POST}. WAC defines control
     * operations as "view, create, delete, or modify ACL resources": deleting an ACL is a
     * policy change (the resource falls back to inherited defaults), not a containment edit,
     * so the parent-Write rule for {@code DELETE} does not apply; and a {@code POST} to an ACL
     * — which no handler will accept, an ACL not being a container — is refused here for
     * anyone without Control rather than letting the method table decide it needs only Append.
     *
     * @param acl the ACL resource the request targets; must satisfy {@link AclResource#isAcl}
     * @return the single requirement: Control on {@link AclResource#governedBy}
     * @throws IllegalArgumentException if {@code acl} is not an ACL resource — a caller bug,
     *     since {@link #forRequest} routes here only for one
     */
    public static List<AccessRequirement> forAcl(ResourceIdentifier acl) {
        Objects.requireNonNull(acl, "acl");
        return List.of(new AccessRequirement(AclResource.governedBy(acl), AccessMode.CONTROL));
    }

    /**
     * What reading the decision log for {@code target} requires: <strong>Control</strong> on
     * the resource whose receipts are asked for (T5.9).
     *
     * <p>Not Read, deliberately. Receipts say who reached a resource, when, and under which
     * grant — the same class of information as the ACL itself, so they take the ACL's mode. In
     * particular the agent whose access is being reported holds Read at most, and Read on a
     * document must not become a view of everyone else's traffic to it. Control alone, on the
     * other hand, does <em>not</em> reveal the content — the query returns decisions, never
     * bytes — so the mode fits in both directions.
     *
     * <p>The per-agent query at a pod root asks the same thing of the root: Control there is
     * what governs the whole pod's policy by {@code acl:default}, so it is what may see the
     * whole pod's receipts.
     *
     * @param target the resource (or pod root) whose receipts are requested
     * @return the single requirement: Control on {@code target}
     */
    public static List<AccessRequirement> forReceipts(ResourceIdentifier target) {
        Objects.requireNonNull(target, "target");
        return List.of(new AccessRequirement(target, AccessMode.CONTROL));
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
