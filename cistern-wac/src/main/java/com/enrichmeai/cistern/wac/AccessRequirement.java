package com.enrichmeai.cistern.wac;

import com.enrichmeai.cistern.core.ResourceIdentifier;

import java.util.Objects;

/**
 * One thing an agent must be granted for a request to proceed: a mode, on a specific
 * resource.
 *
 * <p>A pair rather than a bare mode because a single HTTP request can require access to more
 * than one resource. {@code DELETE} is the case that forces it — see
 * {@link RequiredAccess#forRequest}.
 *
 * @param target the resource the mode is required on, which is not always the request's target
 * @param mode   the mode required there
 */
public record AccessRequirement(ResourceIdentifier target, AccessMode mode) {

    public AccessRequirement {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(mode, "mode");
    }
}
