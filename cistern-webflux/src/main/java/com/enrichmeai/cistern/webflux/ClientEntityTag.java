package com.enrichmeai.cistern.webflux;

import java.util.Objects;

/**
 * One entity-tag as a <em>client</em> sent it in an {@code If-Match} or {@code If-None-Match}
 * field (RFC 9110 §8.8.3):
 *
 * <pre>
 *   entity-tag = [ weak ] opaque-tag
 *   weak       = %s"W/"
 *   opaque-tag = DQUOTE *etagc DQUOTE
 * </pre>
 *
 * <p>Distinct from {@link EntityTag}, and deliberately so. {@link EntityTag} is a validator
 * <em>Cistern computed</em> for a representation it can serve, and is always strong; this is
 * an opaque token that <em>arrived</em>, may be weak, and may name a representation that no
 * longer exists or never did. Collapsing the two into one type would lose the weak flag,
 * which is the whole basis of §8.8.3.2's two comparison functions.
 *
 * @param opaqueTag the {@code opaque-tag}'s content, without its surrounding quotes. May be
 *                  empty — {@code *etagc} permits {@code ""} — in which case it can never
 *                  match, since {@link EntityTag} rejects a blank value.
 * @param weak      whether the client prefixed it with {@code W/}
 */
record ClientEntityTag(String opaqueTag, boolean weak) {

    /** RFC 9110 §8.8.3: the weak prefix is case-sensitive ({@code %s"W/"}). */
    static final String WEAK_PREFIX = "W/";

    private static final char DQUOTE = '"';

    ClientEntityTag {
        Objects.requireNonNull(opaqueTag, "opaqueTag");
    }

    /**
     * RFC 9110 §8.8.3.2, strong comparison: "two entity tags are equivalent if both are not
     * weak and their opaque-tags match character-by-character."
     *
     * <p>{@code current} is always strong (see {@link EntityTag#headerValue()}), so the only
     * way this can fail on weakness is the received tag being weak — which is precisely the
     * case §13.1.1 means to exclude, since the client "intends this precondition to prevent
     * the method from being applied if there have been any changes to the representation
     * data" and a weak tag does not promise that.
     */
    boolean matchesStrongly(EntityTag current) {
        return !weak && opaqueTag.equals(current.value());
    }

    /**
     * RFC 9110 §8.8.3.2, weak comparison: "two entity tags are equivalent if their opaque-tags
     * match character-by-character, regardless of either or both being tagged as 'weak'."
     * Required for {@code If-None-Match} by §13.1.2, "since weak entity tags can be used for
     * cache validation even if there have been changes to the representation data".
     */
    boolean matchesWeakly(EntityTag current) {
        return opaqueTag.equals(current.value());
    }

    /** The tag as it would appear in a field value — used in problem details, never emitted. */
    String fieldValue() {
        return (weak ? WEAK_PREFIX : "") + DQUOTE + opaqueTag + DQUOTE;
    }
}
