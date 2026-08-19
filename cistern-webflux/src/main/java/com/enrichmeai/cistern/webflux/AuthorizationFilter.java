package com.enrichmeai.cistern.webflux;

import com.enrichmeai.cistern.core.Agent;
import com.enrichmeai.cistern.core.CisternException;
import com.enrichmeai.cistern.core.ResourceIdentifier;
import com.enrichmeai.cistern.wac.AccessControl;
import com.enrichmeai.cistern.wac.AccessDecision;
import com.enrichmeai.cistern.wac.AccessRequirement;
import com.enrichmeai.cistern.wac.AccessVerdict;
import com.enrichmeai.cistern.wac.DecisionRecord;
import com.enrichmeai.cistern.wac.DecisionSink;
import com.enrichmeai.cistern.wac.RequestId;
import com.enrichmeai.cistern.wac.RequiredAccess;
import com.enrichmeai.cistern.webflux.auth.PrincipalResolver;

import java.time.Clock;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Enforces Web Access Control before any handler runs (T5.3), and leaves a receipt for every
 * decision (T5.9).
 *
 * <p>A filter rather than a check inside each handler, because "deny by default" is only true
 * if there is no path that forgets to ask. Adding a handler must not be a way to add an
 * unprotected route — and, since T5.9, must not be a way to add an unrecorded one either.
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
 *
 * <h2>Receipts (T5.9)</h2>
 * Every request that reaches the decision yields <strong>exactly one</strong>
 * {@link DecisionRecord} — allow and deny, every method — handed to the {@link DecisionSink}
 * <em>before</em> the request proceeds or is refused. The order is deliberate: the response
 * is not sent until the receipt is durable, so a receipts query issued after a response always
 * sees the decision that produced it, and "if you got an answer there is a receipt" holds
 * without a race. The cost is one asynchronous append on the store's scheduler; the event
 * loop is never blocked.
 *
 * <p>A sink failure does not change the outcome — the decision stands, the failure is logged
 * — unless {@code cistern.audit.required} is set, in which case the request fails closed with
 * {@link CisternException.ServiceUnavailable} (503, retry later) through the one error mapper.
 * Neither branch touches the authorization result: {@link AccessControl} remains the only
 * decision point, and nothing here caches what it said.
 *
 * <p>The request's correlation identifier ({@code X-Request-Id}) is honoured when the client
 * sent a well-formed one and minted otherwise, echoed on every response this filter sees, and
 * written into the receipt — so a refusal an application logged on its side can be matched to
 * the receipt on this side.
 *
 * <p>A {@code GET ?receipts} is recognised here and requires <strong>Control</strong> on the
 * resource ({@link RequiredAccess#forReceipts}) rather than Read; the handler that answers it
 * is reachable only through this filter.
 */
public final class AuthorizationFilter implements WebFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationFilter.class);

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
    private final DecisionSink sink;
    private final CisternProperties.Audit audit;
    private final Clock clock;

    public AuthorizationFilter(
            PrincipalResolver principals,
            AccessControl accessControl,
            RequestPaths paths,
            DecisionSink sink,
            CisternProperties.Audit audit,
            Clock clock) {
        this.principals = Objects.requireNonNull(principals, "principals");
        this.accessControl = Objects.requireNonNull(accessControl, "accessControl");
        this.paths = Objects.requireNonNull(paths, "paths");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        if (isCorsPreflight(request)) {
            // A preflight carries no credentials by definition — the browser sends it before
            // it will attach any. Requiring authorization here would make every cross-origin
            // request fail at the first hop, including ones that would have been permitted.
            return chain.filter(exchange);
        }

        // Correlation first, so that every response below — allowed, refused, or failed
        // closed by the error mapper — carries the same identifier the receipt does.
        RequestId requestId = RequestId.parse(request.getHeaders().getFirst(HttpConstants.X_REQUEST_ID))
                .orElseGet(RequestId::generate);
        exchange.getResponse().getHeaders().set(HttpConstants.X_REQUEST_ID, requestId.value());

        ResourceIdentifier target = paths.identifierFor(request.getPath().value());
        List<AccessRequirement> requirements = requirementsFor(request, target);

        return principals.resolve(exchange)
                .flatMap(agent -> accessControl.authorize(requirements, agent)
                        .flatMap(verdict -> record(verdict, agent, requestId, request)
                                .then(Mono.defer(() -> verdict.allowed()
                                        ? proceed(exchange, chain, agent, target, verdict)
                                        : refuse(exchange, agent)))));
    }

    /**
     * What this request must be granted. A {@code GET}/{@code HEAD} carrying {@code ?receipts}
     * is a query of the decision log, not a read of the resource, and requires Control; every
     * other request is what {@link RequiredAccess#forRequest} says of its method.
     */
    private static List<AccessRequirement> requirementsFor(ServerHttpRequest request, ResourceIdentifier target) {
        if (isReadMethod(request) && ReceiptsRequest.isRequested(request.getQueryParams())) {
            return RequiredAccess.forReceipts(target);
        }
        return RequiredAccess.forRequest(request.getMethod().name(), target);
    }

    /**
     * Leave the receipt. Completes when it is durable; on a sink failure, completes anyway
     * (outcome unchanged) or fails closed, per {@code cistern.audit.required}. Either way the
     * failure is logged with the request id and the outcome that was, or was not, acted on.
     */
    private Mono<Void> record(AccessVerdict verdict, Agent agent, RequestId requestId, ServerHttpRequest request) {
        DecisionRecord record = DecisionRecord.of(clock.instant(), agent, verdict, requestId);
        Mono<Void> recorded = sink.record(record)
                .doOnError(failure -> log.warn(WebfluxMessage.AUDIT_RECORD_FAILED.format(
                        requestId, request.getMethod(), request.getPath().value(), record.outcome(),
                        audit.required()
                                ? WebfluxMessage.AUDIT_FAILED_CLOSED.format()
                                : WebfluxMessage.AUDIT_OUTCOME_UNCHANGED.format()), failure));
        return audit.required()
                // A domain signal, not a status: the one error mapper renders it as 503.
                ? recorded.onErrorMap(failure -> new CisternException.ServiceUnavailable(
                        WebfluxMessage.AUDIT_UNAVAILABLE.format()))
                : recorded.onErrorComplete();
    }

    /** Publish the agent for downstream readers, advertise WAC-Allow, then continue. */
    private Mono<Void> proceed(
            ServerWebExchange exchange, WebFilterChain chain, Agent agent,
            ResourceIdentifier target, AccessVerdict verdict) {
        return wacAllow(exchange, agent, target, verdict)
                .then(Mono.defer(() -> chain.filter(exchange)
                        .contextWrite(context -> context.put(AGENT_CONTEXT_KEY, agent))));
    }

    /**
     * Emit {@code WAC-Allow: user="...",public="..."} on GET and HEAD.
     *
     * <p>WAC defines the two groups as the modes held by the requester and by the public. The
     * requester's modes are already in hand — the verdict's primary judgement is exactly
     * {@code grantedFor(target, agent)}, so it is reused rather than evaluated again — and the
     * public's cost one further evaluation, for {@link Agent#ANONYMOUS}. That is a real cost,
     * taken deliberately: a browser app that cannot see what the user may do has to discover
     * it by attempting writes and reading the failures.
     *
     * <p>When the requester already <em>is</em> anonymous the two groups are identical, so the
     * second evaluation is skipped rather than repeated.
     *
     * <p>Set on the response before the chain runs, because a handler that has begun writing
     * its body has already committed the headers.
     */
    private Mono<Void> wacAllow(
            ServerWebExchange exchange, Agent agent, ResourceIdentifier target, AccessVerdict verdict) {
        if (!isReadMethod(exchange.getRequest())) {
            return Mono.empty();
        }
        AccessDecision user = verdict.primary().decision();
        Mono<String> header = agent.isAuthenticated()
                ? accessControl.grantedFor(target, Agent.ANONYMOUS).map(publicModes -> header(user, publicModes))
                : Mono.just(header(user, user));
        return header
                .doOnNext(value -> exchange.getResponse().getHeaders().set(HttpConstants.WAC_ALLOW, value))
                .then();
    }

    private static String header(AccessDecision user, AccessDecision publicModes) {
        return HttpConstants.WAC_ALLOW_USER + "=\"" + user.toHeaderModes() + "\","
                + HttpConstants.WAC_ALLOW_PUBLIC + "=\"" + publicModes.toHeaderModes() + "\"";
    }

    private static boolean isReadMethod(ServerHttpRequest request) {
        return HttpMethod.GET.equals(request.getMethod()) || HttpMethod.HEAD.equals(request.getMethod());
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
        return HttpMethod.OPTIONS.equals(request.getMethod())
                && request.getHeaders().getAccessControlRequestMethod() != null;
    }
}
