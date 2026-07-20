package com.enrichmeai.cistern.core.ldp;

import com.enrichmeai.cistern.core.ResourceIdentifier;

/**
 * What a successful write did to the target resource — a closed set, so an enum rather than
 * a boolean named {@code created} (ground rule 7).
 *
 * <p>The distinction is not cosmetic: RFC 9110 §9.3.4 makes it the difference between two
 * mandatory status codes. "If the target resource does not have a current representation and
 * the PUT successfully creates one, then the origin server MUST inform the user agent by
 * sending a 201 (Created) response. If the target resource does have a current representation
 * and that representation is successfully modified in accordance with the state of the
 * enclosed representation, then the origin server MUST send either a 200 (OK) or a 204 (No
 * Content) response to indicate successful completion of the request."
 *
 * <p>Which of the two occurred is a fact about storage that only the write path can observe,
 * so it is decided in core and reported to the front-end rather than re-derived there. The
 * enum carries no status code: naming those is the error/response mapper's job, and core
 * never speaks HTTP.
 */
public enum WriteEffect {

    /** The target had no current representation; the write created one → 201. */
    CREATED,

    /** The target already existed and its representation was replaced → 204 (or 200). */
    REPLACED;

    /**
     * The effect of a write against a target whose prior existence is already known.
     *
     * @param existedBefore whether {@link com.enrichmeai.cistern.core.ResourceStore#exists(
     *                      ResourceIdentifier)} reported the target present immediately
     *                      before the write
     */
    public static WriteEffect of(boolean existedBefore) {
        return existedBefore ? REPLACED : CREATED;
    }
}
