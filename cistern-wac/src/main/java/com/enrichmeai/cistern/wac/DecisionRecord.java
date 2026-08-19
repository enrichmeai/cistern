package com.enrichmeai.cistern.wac;

import com.enrichmeai.cistern.core.Agent;
import com.enrichmeai.cistern.core.ResourceIdentifier;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * One authorization decision, as the audit trail keeps it: the receipt (T5.9).
 *
 * <p>Answers the question the demo's fourth beat asks — <em>which agent touched which
 * resource, needing what, when, and under which grant</em> — from what the decision point
 * already knew at the moment it decided. Nothing here is reconstructed after the fact.
 *
 * <p>The record is written once and read many times, so its shape is a format as much as a
 * type: {@link DecisionRecordJson} is its serialization and the field names live in
 * {@link DecisionField}. Adding a component here is adding a column to every log a deployment
 * has already written; do it knowingly.
 *
 * @param at        when the decision was taken
 * @param agent     who asked; {@link Agent#ANONYMOUS} when the request proved no identity
 * @param target    the request's own target — not, for a {@code DELETE}, its parent; for a
 *                  request addressed to an ACL resource, the resource that ACL governs, since
 *                  that is where Control was required ({@link RequiredAccess#forAcl})
 * @param required  the mode the request needed on {@code target}
 * @param outcome   how it ended
 * @param decidedBy the ACL resource whose authorizations granted it, when it was allowed;
 *                  empty on every denial, because a denial names no policy (see
 *                  {@link AccessDecision})
 * @param requestId the correlation identifier the request carried, or the one minted for it
 */
public record DecisionRecord(
        Instant at,
        Agent agent,
        ResourceIdentifier target,
        AccessMode required,
        Outcome outcome,
        Optional<ResourceIdentifier> decidedBy,
        RequestId requestId) {

    public DecisionRecord {
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(agent, "agent");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(required, "required");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(decidedBy, "decidedBy");
        Objects.requireNonNull(requestId, "requestId");
        if (!outcome.isAllowed() && decidedBy.isPresent()) {
            throw new IllegalArgumentException(WacMessage.DENIAL_NAMES_A_POLICY.format());
        }
    }

    /**
     * The receipt for {@code verdict}, taken {@code at} for {@code agent}. The one place a
     * verdict becomes a record, so the mapping — target and mode from the primary judgement,
     * outcome from the verdict and the agent, policy only on allow — is written once.
     */
    public static DecisionRecord of(
            Instant at, Agent agent, AccessVerdict verdict, RequestId requestId) {
        Objects.requireNonNull(verdict, "verdict");
        AccessRequirement primary = verdict.primary().requirement();
        return new DecisionRecord(
                at, agent, primary.target(), primary.mode(),
                Outcome.of(verdict, agent), verdict.decidedBy(), requestId);
    }
}
