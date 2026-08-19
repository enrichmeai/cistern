package com.enrichmeai.cistern.auth;

import com.nimbusds.jose.jwk.JWKSet;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import reactor.core.publisher.Mono;

/**
 * A {@link JwksClient} over captured key sets, for the verifier's tests: what {@link #keys()}
 * returns now, what {@link #refresh()} switches to, and how many times each was asked.
 */
final class InMemoryJwksClient implements JwksClient {

    private final AtomicReference<Mono<JWKSet>> current;
    private final Mono<JWKSet> afterRefresh;
    final AtomicInteger keysCalls = new AtomicInteger();
    final AtomicInteger refreshCalls = new AtomicInteger();

    /** Serves {@code current}; a refresh switches to {@code afterRefresh} from then on. */
    InMemoryJwksClient(JWKSet current, JWKSet afterRefresh) {
        this(Mono.just(current), Mono.just(afterRefresh));
    }

    /** Serves {@code current}; a refresh changes nothing (no rotation happened). */
    InMemoryJwksClient(JWKSet current) {
        this(current, current);
    }

    private InMemoryJwksClient(Mono<JWKSet> current, Mono<JWKSet> afterRefresh) {
        this.current = new AtomicReference<>(current);
        this.afterRefresh = afterRefresh;
    }

    /** A client whose issuer is down: every call fails as the real one would. */
    static InMemoryJwksClient unavailable(String message) {
        Mono<JWKSet> failing = Mono.error(() -> new JwksUnavailableException(message));
        return new InMemoryJwksClient(failing, failing);
    }

    @Override
    public Mono<JWKSet> keys() {
        keysCalls.incrementAndGet();
        return current.get();
    }

    @Override
    public Mono<JWKSet> refresh() {
        refreshCalls.incrementAndGet();
        current.set(afterRefresh);
        return afterRefresh;
    }
}
