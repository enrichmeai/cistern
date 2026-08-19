package com.enrichmeai.cistern.cli;

/**
 * The caller's credential, sent as {@code Authorization: Bearer <value>}.
 *
 * <p>Today this is the pod owner's local token ({@code cistern.owner.token}); after #88 it is
 * whatever credential the server's resolvers accept for the caller. The CLI never inspects it —
 * "who is calling" is the server's decision, and Control is enforced there.
 *
 * @param value the secret, non-blank; never logged or printed
 */
record BearerToken(String value) {

    /** RFC 6750 §2.1 authentication scheme, with its separating space. */
    private static final String SCHEME = "Bearer ";

    BearerToken {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    CliMessage.BLANK_HEADER_VALUE.format(HttpHeaderName.AUTHORIZATION.fieldName()));
        }
    }

    /** The {@code Authorization} field value. */
    String headerValue() {
        return SCHEME + value;
    }

    @Override
    public String toString() {
        // A credential must not leak into a log line or a stack trace by accident.
        return getClass().getSimpleName();
    }
}
