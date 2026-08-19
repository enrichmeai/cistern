package com.enrichmeai.cistern.auth;

import com.nimbusds.jose.jwk.JWKSet;

import reactor.core.publisher.Mono;

/**
 * The issuer's published signing keys (T4.0, #88).
 *
 * <p>An interface so the verifier can be tested against a captured key set without a network,
 * and so a deployment could supply keys some other way (pinned, or from a secret store) without
 * touching verification. The production implementation is {@link CachingJwksClient}.
 *
 * <p>Both methods may fail with {@link JwksUnavailableException}, and only with that: the
 * verifier turns it into a {@link JwtRejectionReason#KEYS_UNAVAILABLE} verdict, so a token
 * that cannot be checked authenticates nobody rather than failing the request.
 */
public interface JwksClient {

    /** The current key set — from cache when the cache is fresh, otherwise fetched. */
    Mono<JWKSet> keys();

    /**
     * Fetch again, now: for a token whose {@code kid} the cached set does not know, which is
     * what a key rotation looks like from here. Implementations rate-limit this, since a
     * stream of tokens with invented kids must not become a stream of requests to the issuer;
     * when limited, the cached set is returned.
     */
    Mono<JWKSet> refresh();
}
