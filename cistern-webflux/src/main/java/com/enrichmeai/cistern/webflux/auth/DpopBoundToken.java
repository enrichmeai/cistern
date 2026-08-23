package com.enrichmeai.cistern.webflux.auth;

import com.enrichmeai.cistern.webflux.WebfluxMessage;

import java.util.Objects;
import java.util.Optional;

import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;

/**
 * The credential a request presented as {@code Authorization: DPoP <token>} (RFC 9449 §7.1).
 *
 * <p>A sibling of {@link BearerToken} rather than a scheme argument to it, because the two
 * schemes make different promises and no resolver should be able to confuse them. A bearer
 * token authenticates whoever holds it; a DPoP-bound token authenticates only a holder who can
 * also prove possession of the key named in its {@code cnf.jkt}, and that proof arrives in a
 * separate {@code DPoP} header. Accepting one where the other was meant is precisely the
 * downgrade this type exists to make impossible to write by accident.
 *
 * <p><strong>Presenting this is not proving it.</strong> This type says only that a
 * DPoP-scheme credential was offered; the proof is validated in T4.2. Until then nothing may
 * treat a request carrying one as authenticated — see {@code SolidOidcTokenVerifier}.
 *
 * @param value the token exactly as presented, never blank
 */
public record DpopBoundToken(String value) {

    /** RFC 9449 §7.1 auth-scheme, followed by the single space the grammar requires. */
    private static final String SCHEME_PREFIX = "DPoP ";

    public DpopBoundToken {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException(WebfluxMessage.DPOP_TOKEN_BLANK.format());
        }
    }

    /**
     * The DPoP-bound token of {@code request}, if it presented one.
     *
     * @return empty when there is no {@code Authorization} field, when its scheme is not
     *     {@code DPoP}, or when the token itself is empty
     */
    public static Optional<DpopBoundToken> from(ServerHttpRequest request) {
        String header = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (header == null) {
            return Optional.empty();
        }
        String candidate = header.stripLeading();
        if (candidate.length() < SCHEME_PREFIX.length()
                || !candidate.regionMatches(true, 0, SCHEME_PREFIX, 0, SCHEME_PREFIX.length())) {
            return Optional.empty();
        }
        String token = candidate.substring(SCHEME_PREFIX.length()).strip();
        return token.isEmpty() ? Optional.empty() : Optional.of(new DpopBoundToken(token));
    }
}
