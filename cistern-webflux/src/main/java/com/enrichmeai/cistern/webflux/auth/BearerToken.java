package com.enrichmeai.cistern.webflux.auth;

import com.enrichmeai.cistern.webflux.WebfluxMessage;

import java.util.Objects;
import java.util.Optional;

import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;

/**
 * The credential a request presented as {@code Authorization: Bearer <token>} (RFC 6750 §2.1).
 *
 * <p>A value type rather than a {@code String} plucked from the header at each call site: three
 * resolvers now read this field — the owner's local credential, the service-principal
 * credential, and the OIDC JWT — and they must agree on what "the bearer token" is (scheme
 * matched case-insensitively, surrounding whitespace insignificant, empty token absent) or a
 * credential one of them accepts is one another silently ignores.
 *
 * <p>Says nothing about what the token <em>is</em>. Whether it is a shared secret or a JWT is
 * for each resolver to decide; this only establishes that one was presented.
 *
 * @param value the token exactly as presented, never blank
 */
public record BearerToken(String value) {

    /** RFC 6750 §2.1 auth-scheme, followed by the single space the grammar requires. */
    private static final String SCHEME_PREFIX = "Bearer ";

    public BearerToken {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException(
                    WebfluxMessage.BEARER_TOKEN_BLANK.format());
        }
    }

    /**
     * The bearer token of {@code request}, if it presented one.
     *
     * @return empty when there is no {@code Authorization} field, when its scheme is not
     *     {@code Bearer}, or when the token itself is empty
     */
    public static Optional<BearerToken> from(ServerHttpRequest request) {
        String header = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (header == null
                || !header.regionMatches(true, 0, SCHEME_PREFIX, 0, SCHEME_PREFIX.length())) {
            return Optional.empty();
        }
        String token = header.substring(SCHEME_PREFIX.length()).trim();
        return token.isEmpty() ? Optional.empty() : Optional.of(new BearerToken(token));
    }

    /** Never the token itself: this can end up in a log line. */
    @Override
    public String toString() {
        return "BearerToken[redacted]";
    }
}
