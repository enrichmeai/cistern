package com.enrichmeai.cistern.wac;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The correlation identifier of one request, as a receipt records it and as the front door
 * echoes it back (T5.9). A value type rather than a bare {@code String} (ground rule 7) because
 * the value has rules, and because it is written into an audit log and reflected into a
 * response header — two places where an unconstrained client string is a liability.
 *
 * <p>A client-supplied identifier is honoured when it is well-formed, so an application can
 * match a refusal it logged on its side to the receipt on this side. Well-formed means: 1 to
 * {@value #MAX_LENGTH} characters, each from the URL-safe alphabet — letters, digits and
 * {@code - _ . ~ : / + =} — which admits UUIDs, ULIDs, hex and base64 trace identifiers and
 * refuses whitespace, control characters and anything that could smuggle a header line or a
 * JSON delimiter. Anything else is not an error; it is simply not the request's identifier,
 * and a fresh one is minted.
 */
public record RequestId(String value) {

    /**
     * Long enough for any identifier scheme in common use (a UUID is 36, a W3C trace-id 32, a
     * base64 128-bit value 24), short enough that a hostile client cannot bloat every receipt.
     */
    public static final int MAX_LENGTH = 128;

    /** The admissible alphabet, anchored; see the class comment. */
    private static final Pattern WELL_FORMED = Pattern.compile("[A-Za-z0-9._~:/+=-]{1," + MAX_LENGTH + "}");

    public RequestId {
        Objects.requireNonNull(value, "value");
        if (!WELL_FORMED.matcher(value).matches()) {
            throw new IllegalArgumentException(WacMessage.REQUEST_ID_MALFORMED.format(value));
        }
    }

    /**
     * {@code candidate} as a request identifier, if it is well-formed; empty otherwise, and
     * empty for {@code null}. Never throws: a malformed client value is a reason to generate,
     * not a reason to fail the request.
     */
    public static Optional<RequestId> parse(String candidate) {
        if (candidate == null || !WELL_FORMED.matcher(candidate).matches()) {
            return Optional.empty();
        }
        return Optional.of(new RequestId(candidate));
    }

    /** A fresh, unguessable identifier — a random UUID, which is well-formed by construction. */
    public static RequestId generate() {
        return new RequestId(UUID.randomUUID().toString());
    }

    @Override
    public String toString() {
        return value;
    }
}
