package com.enrichmeai.cistern.auth;

import com.enrichmeai.cistern.core.Agent;
import com.enrichmeai.cistern.webflux.auth.DpopBoundToken;
import com.enrichmeai.cistern.webflux.auth.PrincipalResolver;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Authenticates a Solid-OIDC request (T4.4): the last of the three checks, and the only place
 * they are allowed to add up to an {@link Agent}.
 *
 * <p>All three must hold, and each answers a question the others cannot:
 *
 * <ol>
 *   <li>{@link SolidOidcTokenVerifier} — an issuer signed this token and it has not expired.
 *   <li>{@link DpopValidator} — the caller holds the key the token is bound to, for
 *       <em>this</em> method and <em>this</em> URL, and has not replayed the proof.
 *   <li>{@link WebIdIssuerVerifier} — the WebID authorises that issuer to speak for it.
 * </ol>
 *
 * <p>Drop the third and any identity provider can mint a token for any WebID. Drop the second
 * and a token lifted off the wire is as good as the key. Drop the first and none of it means
 * anything. This class exists so that nobody has to remember that: it is the single caller of
 * {@link SolidOidcIdentity#toAgent()}, and it only calls it once all three have passed.
 *
 * <h2>The DPoP target comes from configuration, not from the request</h2>
 *
 * <p>RFC 9449 §4.3 step 9 compares the proof's {@code htu} against this request's URL — which
 * is the URL the <em>client</em> dialled. Behind a reverse proxy the socket sees something
 * else entirely, and trusting {@code X-Forwarded-*} would let a caller choose the URL their own
 * proof is checked against, defeating the step.
 *
 * <p>So the target is {@code cistern.base-url} plus the request path. That property is already
 * load-bearing — in Solid a resource's identifier <em>is</em> its HTTP URI, so a pod with the
 * wrong base URL is already broken in more visible ways. Reusing it means no new trust
 * decision, no attacker-influenced input, and one place to be right.
 *
 * <p>Honours the resolver contract exactly: every failure resolves to {@link Agent#ANONYMOUS}
 * and is logged at the level its reason carries. Nothing here signals an error, so a bad
 * credential is a 401 where a grant was needed and nothing at all where it was not — which is
 * what keeps a public resource readable by a caller holding a broken token.
 */
public final class SolidOidcPrincipalResolver implements PrincipalResolver {

    private static final Logger log = LoggerFactory.getLogger(SolidOidcPrincipalResolver.class);

    private final SolidOidcTokenVerifier tokens;
    private final DpopValidator proofs;
    private final WebIdIssuers webIds;
    private final URI baseUrl;

    /**
     * @param baseUrl {@code cistern.base-url} — the externally visible origin this pod is
     *                addressed by, and what {@code htu} is checked against
     */
    public SolidOidcPrincipalResolver(SolidOidcTokenVerifier tokens, DpopValidator proofs,
                                      WebIdIssuers webIds, URI baseUrl) {
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.proofs = Objects.requireNonNull(proofs, "proofs");
        this.webIds = Objects.requireNonNull(webIds, "webIds");
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
    }

    @Override
    public Mono<Agent> resolve(ServerWebExchange exchange) {
        Optional<DpopBoundToken> presented = DpopBoundToken.from(exchange.getRequest());
        if (presented.isEmpty()) {
            // Not a Solid-OIDC request. Another resolver in the chain may recognise it, and if
            // none does the request is anonymous — which is a state, not a failure.
            return Mono.empty();
        }
        String token = presented.get().value();

        return tokens.verify(token)
                .flatMap(verdict -> switch (verdict) {
                    case SolidOidcVerdict.Rejected rejected -> anonymous(rejected.reason().level(),
                            AuthMessage.TOKEN_REJECTED.format(rejected.reason(), rejected.detail()));
                    case SolidOidcVerdict.Accepted accepted -> withProof(exchange, token, accepted.identity());
                });
    }

    private Mono<Agent> withProof(ServerWebExchange exchange, String token, SolidOidcIdentity identity) {
        ServerHttpRequest request = exchange.getRequest();
        List<String> headers = request.getHeaders().getOrDefault(DPOP_HEADER, List.of());
        DpopRequest dpopRequest;
        try {
            dpopRequest = new DpopRequest(
                    request.getMethod().name(), targetOf(request), Optional.of(token));
        } catch (IllegalArgumentException e) {
            // No reachable request is known to land here (the path below came out of a URI
            // the server already parsed), but the resolver contract is "never an error", and
            // a contract kept by argument is one refactor away from broken. A target this
            // cannot build is a request this cannot authenticate.
            return anonymous(org.slf4j.event.Level.WARN,
                    AuthMessage.DPOP_TARGET_UNBUILDABLE.format(e.getMessage()));
        }

        DpopVerdict verdict = proofs.validate(headers, dpopRequest);
        if (verdict instanceof DpopVerdict.Rejected rejected) {
            return anonymous(rejected.reason().level(),
                    AuthMessage.DPOP_REJECTED.format(rejected.reason(), rejected.detail()));
        }
        return webIds.verify(identity.webId(), identity.issuer())
                .flatMap(webIdVerdict -> switch (webIdVerdict) {
                    case WebIdVerdict.Refused refused -> anonymous(refused.reason().level(),
                            AuthMessage.TOKEN_REJECTED.format(refused.reason(), refused.detail()));
                    case WebIdVerdict.Verified ignored -> {
                        log.debug(AuthMessage.TOKEN_ACCEPTED.format(
                                identity.webId(), identity.issuer(), identity.client()));
                        // The only call to toAgent() on the request path, and only here.
                        yield Mono.just(identity.toAgent());
                    }
                });
    }

    /**
     * {@code cistern.base-url} + this request's path — never the socket, never a header.
     *
     * <p>{@link ServerHttpRequest#getPath()} is parsed from the request URI's <em>raw</em>
     * path, so percent-encoding survives into the comparison exactly as the client signed it
     * (§4.3 step 9 compares URIs, not decoded strings). Package-private so the encoded-path
     * test pins that, since a decoding refactor would fail step 9 for every URI with an
     * encoded reserved character and look exactly like a broken client.
     */
    URI targetOf(ServerHttpRequest request) {
        String base = baseUrl.toString();
        if (base.endsWith(PATH_SEPARATOR)) {
            base = base.substring(0, base.length() - PATH_SEPARATOR.length());
        }
        return URI.create(base + request.getPath().value());
    }

    private static Mono<Agent> anonymous(org.slf4j.event.Level level, String message) {
        log.atLevel(level).log(message);
        return Mono.just(Agent.ANONYMOUS);
    }

    /** RFC 9449 §7.1: the proof travels in its own header, beside the Authorization field. */
    public static final String DPOP_HEADER = "DPoP";

    private static final String PATH_SEPARATOR = "/";
}
