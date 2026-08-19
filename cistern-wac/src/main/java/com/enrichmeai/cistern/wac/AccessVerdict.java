package com.enrichmeai.cistern.wac;

import com.enrichmeai.cistern.core.ResourceIdentifier;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The outcome of authorizing one request: every {@link AccessRequirement} it carried, each
 * with the {@link AccessDecision} that answered it, in the order {@link RequiredAccess} listed
 * them.
 *
 * <p>Richer than the boolean {@link AccessControl#isAllowed} used to return, and for one
 * reason: the audit trail (T5.9). A receipt has to say what was required, on what, and — when
 * granted — under which policy, and a boolean has already thrown that away. Keeping the
 * per-requirement decisions is also what lets a {@code DELETE} that failed on its
 * <em>parent's</em> Write be told apart from one that failed on the resource itself, without
 * evaluating anything twice.
 *
 * <p>{@link #allowed()} is <strong>every</strong> requirement satisfied, not any — the same
 * rule {@link AccessControl} has always applied. An empty verdict does not occur:
 * {@link RequiredAccess} always yields at least the requirement on the request's own target,
 * and the constructor refuses an empty list rather than letting {@code allMatch} over nothing
 * read as "allowed".
 *
 * @param judgements one per requirement, in requirement order; the first is always the
 *                   request's own target
 */
public record AccessVerdict(List<Judgement> judgements) {

    /**
     * One requirement and the decision that answered it.
     *
     * @param requirement the mode required, on which resource
     * @param decision    what the agent holds there, and under which policy
     */
    public record Judgement(AccessRequirement requirement, AccessDecision decision) {

        public Judgement {
            Objects.requireNonNull(requirement, "requirement");
            Objects.requireNonNull(decision, "decision");
        }

        /** Whether the decision grants the mode the requirement asks for. */
        public boolean satisfied() {
            return decision.allows(requirement.mode());
        }
    }

    public AccessVerdict {
        Objects.requireNonNull(judgements, "judgements");
        if (judgements.isEmpty()) {
            throw new IllegalArgumentException(WacMessage.VERDICT_WITHOUT_REQUIREMENTS.format());
        }
        judgements = List.copyOf(judgements);
    }

    /** Whether the request may proceed: every requirement satisfied. */
    public boolean allowed() {
        return judgements.stream().allMatch(Judgement::satisfied);
    }

    /**
     * The judgement on the request's own target — the first requirement, by
     * {@link RequiredAccess}'s ordering. This is the one a receipt describes: the record names
     * the request target and the mode required <em>there</em>, even when a second requirement
     * (a {@code DELETE}'s parent) is what actually refused.
     */
    public Judgement primary() {
        return judgements.getFirst();
    }

    /**
     * The policy an <em>allowed</em> request was granted under: the ACL resource that decided
     * the primary requirement. Empty when the request was refused — a denial names no policy
     * (see {@link AccessDecision}), even if some other mode happened to be granted by some ACL,
     * because that ACL did not decide <em>this</em> outcome.
     */
    public Optional<ResourceIdentifier> decidedBy() {
        return allowed() ? primary().decision().decidedBy() : Optional.empty();
    }
}
