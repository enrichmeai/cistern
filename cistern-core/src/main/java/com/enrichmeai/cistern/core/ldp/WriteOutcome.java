package com.enrichmeai.cistern.core.ldp;

import com.enrichmeai.cistern.core.ResourceIdentifier;

import java.util.Objects;

/**
 * The result of a successful write: what happened, and what the resource now is.
 *
 * <h2>Why the post-write {@link ResourceView} is part of the result</h2>
 * A front-end cannot shape a write response from the effect alone. It needs the resource's
 * kind to emit the interface metadata Solid Protocol §5.2 requires in <em>successful</em>
 * responses ({@code Allow}, {@code Accept-Put}/{@code Accept-Post}/{@code Accept-Patch}) and
 * the LDP type links of LDP 1.0 §4.2.1.4, plus the validator inputs for an {@code ETag}.
 *
 * <p>Handing back the same {@link ResourceView} the read path produces — built by the very
 * same code, not a second construction — is what stops {@code PUT} and {@code GET} from
 * disagreeing about a resource: the view a {@code PUT} reports is by construction the view
 * the next {@code GET} would resolve, derived containment (Solid Protocol §4.2) included.
 *
 * @param effect whether the write created the resource or replaced it
 * @param view   the resource as it stands after the write, exactly as
 *               {@link LdpService#read(ResourceIdentifier)} would resolve it
 */
public record WriteOutcome(WriteEffect effect, ResourceView view) {

    public WriteOutcome {
        Objects.requireNonNull(effect, "effect");
        Objects.requireNonNull(view, "view");
    }

    /** Convenience for front-ends branching on the status code to emit. */
    public boolean created() {
        return effect == WriteEffect.CREATED;
    }
}
