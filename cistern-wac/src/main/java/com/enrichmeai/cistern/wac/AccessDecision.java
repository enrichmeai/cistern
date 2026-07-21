package com.enrichmeai.cistern.wac;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;

/**
 * What an agent may do to a resource: the set of modes granted, already closed under
 * implication (a Write grant carries Append with it — see {@link AccessMode}).
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
 * <p>{@link #DENIED} is the empty decision, and it is what every path that fails to match
 * returns: WAC has no deny rule, so "denied" is simply "nothing granted it".
 */
public record AccessDecision(Set<AccessMode> modes) {

    /** Nothing granted. WAC denies by default, so this is the result of every non-match. */
    public static final AccessDecision DENIED = new AccessDecision(Collections.emptySet());

    public AccessDecision {
        Objects.requireNonNull(modes, "modes");
        modes = modes.isEmpty()
                ? Collections.emptySet()
                : Collections.unmodifiableSet(EnumSet.copyOf(modes));
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
     * This decision plus {@code other}. WAC composes authorizations additively — "evaluation
     * stops when all access permission requests have been granted by one or more
     * Authorizations" — so combining is union, and there is no rule that can take a mode away.
     */
    public AccessDecision union(AccessDecision other) {
        Objects.requireNonNull(other, "other");
        if (other.isDenied()) {
            return this;
        }
        if (isDenied()) {
            return other;
        }
        Set<AccessMode> combined = EnumSet.copyOf(modes);
        combined.addAll(other.modes);
        return new AccessDecision(combined);
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
