package com.enrichmeai.cistern.webflux;

import com.enrichmeai.cistern.core.Agent;
import com.enrichmeai.cistern.core.ResourceIdentifier;
import com.enrichmeai.cistern.wac.AccessControl;
import com.enrichmeai.cistern.webflux.auth.PrincipalResolver;

import java.util.Objects;

import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Enforces Web Access Control before any handler runs (T5.3).
 *
 * <p>A filter rather than a check inside each handler, because "deny by default" is only true
 * if there is no path that forgets to ask. Adding a handler must not be a way to add an
 * unprotected route.
 *
 * <p>The two refusals are different and the conformance harness distinguishes them everywhere:
 *
 * <ul>
 *   <li><strong>401</strong> when the request proved no identity. It is not a statement that
 *       the resource is forbidden — it is "authenticate and it may work", and it carries
 *       {@code WWW-Authenticate} so a client knows how.</li>
 *   <li><strong>403</strong> when an authenticated agent is not permitted. Retrying with the
 *       same credentials will not help.</li>
 * </ul>
 *
 * <p>Returning 403 to an anonymous request would tell an unauthenticated client that
 * authenticating is pointless, and returning 401 to an authenticated one would invite a
 * credential retry loop.
 */
public final class AuthorizationFilter implements WebFilter, Ordered {

    /**
     * Runs before the handlers but after CORS. The precise value matters less than being
     * negative — Spring's own handler adapters sit at 0 and above, and an authorization check
     * that ran after them would be checking a request already served.
     */
    public static final int ORDER = -100;

    /**
     * The Reactor context key the authenticated agent is published under. A single population
     * point (this filter) and read-only access downstream, per T4.3's shape: nothing else may
     * put an Agent into the context, so there is one place to audit for "who does the server
     * think you are".
     */
    public static final String AGENT_CONTEXT_KEY = "cistern.agent";

    private final PrincipalResolver principals;
    private final AccessControl accessControl;
    private final RequestPaths paths;

    public AuthorizationFilter(
            PrincipalResolver principals, AccessControl accessControl, RequestPaths paths) {
        this.principals = Objects.requireNonNull(principals, "principals");
        this.accessControl = Objects.requireNonNull(accessControl, "accessControl");
        this.paths = Objects.requireNonNull(paths, "paths");
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (isCorsPreflight(exchange.getRequest())) {
            // A preflight carries no credentials by definition — the browser sends it before
            // it will attach any. Requiring authorization here would make every cross-origin
            // request fail at the first hop, including ones that would have been permitted.
            return chain.filter(exchange);
        }

        ResourceIdentifier target = paths.identifierFor(exchange.getRequest().getPath().value());
        String method = exchange.getRequest().getMethod().name();

        return principals.resolve(exchange)
                .flatMap(agent -> accessControl.isAllowed(method, target, agent)
                        .flatMap(allowed -> allowed
                                ? proceed(exchange, chain, agent, target)
                                : refuse(exchange, agent)));
    }

    /** Publish the agent for downstream readers, advertise WAC-Allow, then continue. */
    private Mono<Void> proceed(
            ServerWebExchange exchange, WebFilterChain chain, Agent agent, ResourceIdentifier target) {
        return wacAllow(exchange, agent, target)
                .then(Mono.defer(() -> chain.filter(exchange)
                        .contextWrite(context -> context.put(AGENT_CONTEXT_KEY, agent))));
    }

    /**
     * Emit {@code WAC-Allow: user="...",public="..."} on GET and HEAD.
     *
     * <p>WAC defines the two groups as the modes held by the requester and by the public, so
     * this costs a second evaluation — once for the agent, once for {@link Agent#ANONYMOUS}.
     * That is a real cost, taken deliberately: a browser app that cannot see what the user may
     * do has to discover it by attempting writes and reading the failures.
     *
     * <p>Skipped for an anonymous requester's {@code user} group being recomputed: when the
     * agent already <em>is</em> anonymous the two decisions are identical, so the second
     * evaluation is skipped rather than repeated.
     *
     * <p>Set on the response before the chain runs, because a handler that has begun writing
     * its body has already committed the headers.
     */
    private Mono<Void> wacAllow(ServerWebExchange exchange, Agent agent, ResourceIdentifier target) {
        if (!isReadMethod(exchange.getRequest())) {
            return Mono.empty();
        }
        return accessControl.grantedFor(target, agent)
                .flatMap(user -> agent.isAuthenticated()
                        ? accessControl.grantedFor(target, Agent.ANONYMOUS)
                                .map(publicModes -> header(user, publicModes))
                        : Mono.just(header(user, user)))
                .doOnNext(value -> exchange.getResponse().getHeaders()
                        .set(HttpConstants.WAC_ALLOW, value))
                .then();
    }

    private static String header(
            com.enrichmeai.cistern.wac.AccessDecision user,
            com.enrichmeai.cistern.wac.AccessDecision publicModes) {
        return HttpConstants.WAC_ALLOW_USER + "=\"" + user.toHeaderModes() + "\","
                + HttpConstants.WAC_ALLOW_PUBLIC + "=\"" + publicModes.toHeaderModes() + "\"";
    }

    private static boolean isReadMethod(ServerHttpRequest request) {
        return org.springframework.http.HttpMethod.GET.equals(request.getMethod())
                || org.springframework.http.HttpMethod.HEAD.equals(request.getMethod());
    }

    /**
     * Refuse, without a body. A problem document here would be the one place in the server
     * where an error is written outside the global error mapper (ground rule 4); the status
     * and {@code WWW-Authenticate} carry everything a client can act on, and a denial is not
     * the place to volunteer detail about what exists.
     */
    private Mono<Void> refuse(ServerWebExchange exchange, Agent agent) {
        boolean unauthenticated = !agent.isAuthenticated();
        exchange.getResponse().setStatusCode(
                unauthenticated ? HttpStatus.UNAUTHORIZED : HttpStatus.FORBIDDEN);
        if (unauthenticated) {
            exchange.getResponse().getHeaders()
                    .set(HttpHeaders.WWW_AUTHENTICATE, HttpConstants.WWW_AUTHENTICATE_CHALLENGE);
        }
        return exchange.getResponse().setComplete();
    }

    private static boolean isCorsPreflight(ServerHttpRequest request) {
        return org.springframework.http.HttpMethod.OPTIONS.equals(request.getMethod())
                && request.getHeaders().getAccessControlRequestMethod() != null;
    }
}
