package com.enrichmeai.cistern.webflux;

/**
 * What RFC 9110 §13.2.2's evaluation decided — the closed set of three things a precondition
 * can do to a request, so an enum rather than a pair of booleans.
 *
 * <p>Note what is <em>not</em> here: a status code. Two of these become one (412 through the
 * single error mapper, 304 written by the read handler) and one becomes whatever the method
 * would have returned anyway, and that translation belongs to the caller.
 */
enum PreconditionOutcome {

    /**
     * Perform the method (§13.2.2 step 6, "perform the requested method and respond according
     * to its success or failure") — either because every precondition held, or because §13.2.1
     * required them all to be ignored.
     *
     * <p>The two are one outcome on purpose: in both cases the request gets whatever answer it
     * would have had unconditionally, which may perfectly well be a 404 or a 405. This does not
     * mean "succeed".
     */
    PROCEED,

    /**
     * {@code If-None-Match} or {@code If-Modified-Since} failed on a {@code GET} or
     * {@code HEAD}: the client already holds the current representation, so RFC 9110 §15.4.5
     * says to redirect it to its stored copy instead of sending one.
     *
     * <p><b>Not an error.</b> A 304 is a normal, successful outcome of a conditional read and
     * is written by the read handler; it never travels through the error mapper, which would
     * render it as an RFC 9457 problem document — and §15.4.5 forbids a 304 from carrying
     * content at all.
     */
    NOT_MODIFIED,

    /**
     * A precondition failed on a request that must not proceed: {@code If-Match} or
     * {@code If-Unmodified-Since} on any method, or {@code If-None-Match} on a method other
     * than {@code GET}/{@code HEAD}. Signalled as {@link
     * com.enrichmeai.cistern.core.CisternException.PreconditionFailed} and rendered as 412 by
     * the single error mapper.
     */
    PRECONDITION_FAILED
}
