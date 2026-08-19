package com.enrichmeai.cistern.wac;

import com.enrichmeai.cistern.core.ResourceIdentifier;

import java.net.URI;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;

/**
 * What an agent may do to a resource, and <em>under which policy</em>: the set of modes
 * granted — already closed under implication (a Write grant carries Append with it — see
 * {@link AccessMode}) — the ACL resource that granted them, and the {@code acl:Authorization}s
 * in it that matched.
 *
 * <p>A set rather than a boolean because one evaluation has to answer two different questions,
 * and evaluating twice for them would be both slower and a chance to disagree with itself:
 *
 * <ul>
 *   <li><em>May this request proceed?</em> — {@link #allows(AccessMode)}, for enforcement.</li>
 *   <li><em>What should {@code WAC-Allow} advertise?</em> — {@link #modes()}, which the header
 *       lists verbatim.</li>
 * </ul>
 *
 * <p>The decision <strong>carries its policy</strong> (T5.9) so that the audit trail can name
 * it: "which agent read what, under which grant" is only answerable if the decision point says
 * which ACL decided and which rules in it matched, and reconstructing that later — by
 * re-running discovery against a store that may since have changed — would be a guess.
 * {@link EffectiveAcl} already knows the resource; {@link WacEngine} already knows the matched
 * rules; this record is where the two are kept together with the outcome they produced.
 *
 * <p>{@link #DENIED} is the empty decision, and it is what every path that fails to match
 * returns: WAC has no deny rule, so "denied" is simply "nothing granted it" — and, by the same
 * token, <em>a denial names no policy</em>. There is no rule that refused; there is only the
 * absence of one that granted. The invariant is enforced here: a decision with no modes has no
 * {@code decidedBy} and no {@code authorizations}, and a decision with modes must say where they
 * came from.
 *
 * @param modes          the granted modes, closed under implication; empty for a denial
 * @param decidedBy      the ACL resource whose authorizations granted {@code modes}; empty iff
 *                       {@code modes} is empty
 * @param authorizations the IRIs of the {@code acl:Authorization} subjects that matched — the
 *                       specific rules — in the order they were read; empty on a denial, and
 *                       possibly empty on a grant whose matching rules were all blank nodes,
 *                       which have no IRI to name
 */
public record AccessDecision(
        Set<AccessMode> modes, Optional<ResourceIdentifier> decidedBy, Set<URI> authorizations) {

    /** Nothing granted, by nothing. WAC denies by default, so this is the result of every non-match. */
    public static final AccessDecision DENIED =
            new AccessDecision(Collections.emptySet(), Optional.empty(), Collections.emptySet());

    public AccessDecision {
        Objects.requireNonNull(modes, "modes");
        Objects.requireNonNull(decidedBy, "decidedBy");
        Objects.requireNonNull(authorizations, "authorizations");
        modes = modes.isEmpty()
                ? Collections.emptySet()
                : Collections.unmodifiableSet(EnumSet.copyOf(modes));
        authorizations = authorizations.isEmpty()
                ? Collections.emptySet()
                : Collections.unmodifiableSet(new LinkedHashSet<>(authorizations));
        if (modes.isEmpty()) {
            if (decidedBy.isPresent() || !authorizations.isEmpty()) {
                throw new IllegalArgumentException(WacMessage.DENIAL_NAMES_A_POLICY.format());
            }
        } else if (decidedBy.isEmpty()) {
            throw new IllegalArgumentException(WacMessage.GRANT_NAMES_NO_POLICY.format());
        }
    }

    /**
     * A grant of {@code modes} by {@code decidedBy} through {@code authorizations}, or
     * {@link #DENIED} if {@code modes} is empty — the constructor an evaluation should use, so
     * that "nothing matched" collapses to the canonical denial rather than to an empty grant
     * that happens to name a policy.
     */
    public static AccessDecision of(
            Set<AccessMode> modes, ResourceIdentifier decidedBy, Set<URI> authorizations) {
        Objects.requireNonNull(modes, "modes");
        Objects.requireNonNull(decidedBy, "decidedBy");
        Objects.requireNonNull(authorizations, "authorizations");
        return modes.isEmpty()
                ? DENIED
                : new AccessDecision(modes, Optional.of(decidedBy), authorizations);
    }

    /** Whether {@code required} is granted. */
    public boolean allows(AccessMode required) {
        return modes.contains(Objects.requireNonNull(required, "required"));
    }

    /** Whether nothing at all is granted. */
    public boolean isDenied() {
        return modes.isEmpty();
    }

    /**
     * The modes as a {@code WAC-Allow} value fragment — space-separated lower-case tokens, in
     * the enum's declaration order so the output is stable and testable. The header's quoting
     * and its {@code user=} / {@code public=} grouping belong to the HTTP layer (T5.3); this is
     * only the mode list.
     */
    public String toHeaderModes() {
        StringJoiner joiner = new StringJoiner(" ");
        for (AccessMode mode : AccessMode.values()) {
            if (modes.contains(mode)) {
                joiner.add(mode.headerToken());
            }
        }
        return joiner.toString();
    }
}
