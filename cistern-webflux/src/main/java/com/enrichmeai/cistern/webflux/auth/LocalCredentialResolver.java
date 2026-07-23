package com.enrichmeai.cistern.webflux.auth;

import com.enrichmeai.cistern.core.Agent;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;
import java.util.Optional;

import org.springframework.http.HttpHeaders;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Recognises the pod owner by a single configured bearer token.
 *
 * <p>Deliberately the simplest thing that makes a single-owner pod usable: one owner, one
 * secret, no token issuance, no network calls. It exists so that the authorization engine can
 * be exercised — and the pod actually used — before Solid-OIDC lands, and it is expected to
 * sit alongside a Solid-OIDC resolver rather than be replaced by one.
 *
 * <p><strong>What this is not.</strong> It is not Solid-OIDC, carries no proof of possession,
 * and is only as good as a secret in a config file. It is appropriate for a pod on your own
 * machine reached over loopback, which is the only deployment ADR 0001 permits today. It is
 * not appropriate for a pod on a network, and it does not become so by being given a longer
 * token.
 *
 * <p>Disabled unless both the owner WebID and the token are configured, so the default posture
 * of an unconfigured server is that nobody authenticates rather than that a default credential
 * exists.
 */
public final class LocalCredentialResolver implements PrincipalResolver {

    private static final String BEARER = "Bearer ";

    private final URI ownerWebId;
    private final byte[] expectedToken;

    /**
     * @param ownerWebId the WebID a successful credential authenticates as
     * @param token      the shared secret; blank disables the resolver
     */
    public LocalCredentialResolver(URI ownerWebId, String token) {
        this.ownerWebId = Objects.requireNonNull(ownerWebId, "ownerWebId");
        this.expectedToken = Objects.requireNonNull(token, "token").getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public Mono<Agent> resolve(ServerWebExchange exchange) {
        return Mono.fromSupplier(() -> bearerToken(exchange)
                .filter(this::matches)
                .map(token -> Agent.of(ownerWebId))
                .orElse(Agent.ANONYMOUS));
    }

    private Optional<String> bearerToken(ServerWebExchange exchange) {
        String header = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            return Optional.empty();
        }
        return Optional.of(header.substring(BEARER.length()).trim());
    }

    /**
     * Constant-time comparison. Ordinary string equality returns as soon as two bytes differ,
     * which leaks the length of the matching prefix to anyone who can time the response — the
     * classic way a secret compared with {@code equals} is recovered a byte at a time.
     */
    private boolean matches(String presented) {
        return MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), expectedToken);
    }
}
