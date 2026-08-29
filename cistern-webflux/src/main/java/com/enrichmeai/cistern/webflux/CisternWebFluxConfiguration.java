package com.enrichmeai.cistern.webflux;

import com.enrichmeai.cistern.core.ResourceStore;
import com.enrichmeai.cistern.core.ldp.LdpService;
import com.enrichmeai.cistern.wac.AccessControl;
import com.enrichmeai.cistern.wac.AclDiscovery;
import com.enrichmeai.cistern.wac.AuditPolicy;
import com.enrichmeai.cistern.wac.DecisionLog;
import com.enrichmeai.cistern.wac.DecisionQuery;
import com.enrichmeai.cistern.wac.DecisionSink;
import com.enrichmeai.cistern.wac.JsonLinesDecisionQuery;
import com.enrichmeai.cistern.wac.JsonLinesDecisionSink;
import com.enrichmeai.cistern.wac.PodProvisioner;
import com.enrichmeai.cistern.wac.WacEngine;
import com.enrichmeai.cistern.webflux.auth.AnonymousResolver;
import com.enrichmeai.cistern.webflux.auth.ChainedPrincipalResolver;
import com.enrichmeai.cistern.webflux.auth.ConfiguredServicePrincipalRegistry;
import com.enrichmeai.cistern.webflux.auth.LocalCredentialResolver;
import com.enrichmeai.cistern.webflux.auth.PrincipalResolver;
import com.enrichmeai.cistern.webflux.auth.ServiceCredentialResolver;
import com.enrichmeai.cistern.webflux.auth.ServicePrincipalRegistry;
import com.enrichmeai.cistern.storage.file.FileResourceStore;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.reactive.function.server.RequestPredicate;
import org.springframework.web.reactive.function.server.RequestPredicates;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

/**
 * Spring wiring for the HTTP layer. Lives here rather than in cistern-core, which must stay
 * free of Spring (module rule), and rather than in cistern-app, which is configuration only.
 *
 * <p>Routes are {@link RouterFunction}s, not annotated controllers: a pod's URI space is
 * "every path is a resource", which is a runtime predicate over the whole path space rather
 * than a set of compile-time mappings, and the functional model lets the same handler serve
 * several methods without a dispatch table.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CisternProperties.class)
public class CisternWebFluxConfiguration {

    private static final Logger log = LoggerFactory.getLogger(CisternWebFluxConfiguration.class);

    /** Separator for the resolver names in the {@code PRINCIPAL_RESOLVERS_WIRED} log line. */
    private static final String RESOLVER_LIST_SEPARATOR = ", ";

    /**
     * Every path in a pod names a resource, so the read route matches the whole path space;
     * whether a resource exists there is a storage question, not a routing one.
     */
    private static final String ALL_PATHS = "/**";

    /**
     * The storage description resource (T2.9), Solid Protocol §4.1 — the one route that is not
     * about the stored resource space.
     *
     * <h2>Why it needs precedence</h2>
     * Every other route matches {@code /**}, and {@value StorageDescription#WELL_KNOWN_PATH} is a
     * path like any other, so without an explicit order this route and the catch-alls would be
     * combined in whatever order the container happened to supply the beans. {@code @Order} makes
     * it deterministic: {@code RouterFunctionMapping} sorts {@code RouterFunction} beans with
     * {@code AnnotationAwareOrderComparator}, an unannotated bean sorts at
     * {@code LOWEST_PRECEDENCE}, and the first route whose predicate matches wins. So this one is
     * consulted first and the rest are unaffected.
     *
     * <h2>Only the methods it serves</h2>
     * The predicate is {@link StorageDescriptionHandler#METHODS}, not every method: a request
     * this resource does not serve falls through to the ordinary handlers rather than being
     * captured and refused here. That is a deliberate limitation of T2.9 rather than an
     * oversight — reserving a server-managed URI space from client writes is the same mechanism
     * WAC's {@code .acl} and the auxiliary description resources of §4.3 will need, and building
     * a one-off for this path would be a design decision made in the wrong ticket. Until it
     * lands, a {@code PUT} to {@value StorageDescription#WELL_KNOWN_PATH} creates an ordinary pod
     * resource that this route then shadows on read.
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public RouterFunction<ServerResponse> cisternStorageDescriptionRoutes(
            StorageDescriptionHandler handler) {
        RequestPredicate path = RequestPredicates.path(StorageDescription.WELL_KNOWN_PATH);
        return RouterFunctions
                .route(RequestPredicates.method(HttpMethod.GET)
                        .or(RequestPredicates.method(HttpMethod.HEAD))
                        .and(path), handler::read)
                .andRoute(RequestPredicates.method(HttpMethod.OPTIONS).and(path), handler::options);
    }

    /**
     * The read route. {@code GET} and {@code HEAD} are one route to one handler method, so
     * they are the same code path by construction rather than by discipline — RFC 9110
     * §9.3.2 requires {@code HEAD} to be {@code GET} minus the content, and WebFlux's
     * {@code HttpHeadResponseDecorator} drops the body for us.
     *
     * <p>{@code /**} because every path in a pod names a resource; whether one exists is a
     * storage question, answered by {@code LdpService.read} with a
     * {@code CisternException.NotFound} signal, not a routing question.
     */
    @Bean
    public RouterFunction<ServerResponse> cisternReadRoutes(ResourceReadHandler handler) {
        return RouterFunctions.route(
                RequestPredicates.method(HttpMethod.GET)
                        .or(RequestPredicates.method(HttpMethod.HEAD))
                        .and(RequestPredicates.path(ALL_PATHS)),
                handler::read);
    }

    /**
     * The delete route (T2.4). Its own bean rather than another predicate on the read route:
     * {@code RouterFunction} beans compose, one method per handler keeps each handler's
     * signature honest, and a route added per ticket is a route no other ticket has to edit.
     *
     * <p>{@code /**} for the same reason the read route uses it — whether a resource exists at
     * a path, and whether it may be removed, are questions for
     * {@code LdpService.delete}, not for routing.
     */
    // ---- Authorization (T5.2/T5.1/T5.3) ---------------------------------------------

    /**
     * Brings pods into being — container plus owner ACL, together, never over an existing ACL
     * (T5.6). One instance serves the owner's root, the seeded pods, and any embedder that
     * provisions at runtime; conditional so an embedder can supply its own.
     */
    @Bean
    @ConditionalOnMissingBean
    public PodProvisioner podProvisioner(ResourceStore store) {
        return new PodProvisioner(store);
    }

    /**
     * Seeds the root ACL on first boot (T5.4). Without it, deny-by-default plus an absent
     * root ACL means nobody can reach anything — including the owner, and including the ACL
     * they would need to write to fix it.
     */
    @Bean
    @ConditionalOnMissingBean
    public OwnerPodSeeder ownerPodSeeder(PodProvisioner provisioner, CisternProperties properties) {
        return new OwnerPodSeeder(provisioner, properties);
    }

    /**
     * Seeds the pods of {@code cistern.pods.seed[]} on boot (T5.6), after the owner's root and
     * idempotently. Always registered: with nothing configured it does nothing, and a bean that
     * appears only when a list is non-empty is a condition an operator has to know about.
     */
    @Bean
    @ConditionalOnMissingBean
    public PodSeeder podSeeder(PodProvisioner provisioner, CisternProperties properties) {
        return new PodSeeder(provisioner, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public WacEngine wacEngine() {
        return new WacEngine();
    }

    @Bean
    @ConditionalOnMissingBean
    public AclDiscovery aclDiscovery(ResourceStore store) {
        return new AclDiscovery(store);
    }

    @Bean
    @ConditionalOnMissingBean
    public AccessControl accessControl(AclDiscovery discovery, WacEngine engine) {
        return new AccessControl(discovery, engine);
    }

    /**
     * The service principals of {@code cistern.auth.service-principals} (T4.0). Its own bean so
     * that a later ticket (T5.6 provisioning) can replace the configuration-backed registry
     * with one that learns of applications at runtime; empty when none are configured.
     */
    @Bean
    @ConditionalOnMissingBean
    public ServicePrincipalRegistry servicePrincipalRegistry(CisternProperties properties) {
        return ConfiguredServicePrincipalRegistry.from(properties.auth().servicePrincipals());
    }

    /**
     * How a request becomes an {@code Agent} (T4.0, #88): a {@link ChainedPrincipalResolver}
     * over every configured way of proving identity, first authenticated wins, else anonymous.
     *
     * <p>The order is fixed here and is part of the contract:
     * <ol>
     *   <li>the owner's local credential, when an owner is configured;</li>
     *   <li>service-principal credentials, when any are configured;</li>
     *   <li>whatever other modules contribute as {@link ChainedPrincipalResolver.Member}
     *       beans — cistern-auth's OIDC JWT resolver, when an issuer is configured;</li>
     *   <li>{@link AnonymousResolver}, always, so the chain's fallback is stated rather than
     *       implied.</li>
     * </ol>
     * Secrets compared in constant time go first because they are cheap and local; a JWT
     * costs a signature verification and, on rotation, a key fetch, and is never attempted for
     * a request an earlier member already accepted.
     *
     * <p>An unconfigured server still authenticates nobody: no owner, no service principals
     * and no issuer leaves only the anonymous member, and an unconfigured server must not
     * ship a usable default credential.
     *
     * <p>{@code @ConditionalOnMissingBean} so an embedder can replace the whole chain; a module
     * that only wants to <em>add</em> to it declares a {@code Member} instead — see that type
     * for why the two are distinct.
     */
    @Bean
    @ConditionalOnMissingBean
    public PrincipalResolver principalResolver(
            CisternProperties properties,
            ServicePrincipalRegistry servicePrincipals,
            ObjectProvider<ChainedPrincipalResolver.Member> contributed) {
        List<PrincipalResolver> chain = new ArrayList<>();
        CisternProperties.Owner owner = properties.owner();
        if (owner.hasLocalCredential()) {
            chain.add(new LocalCredentialResolver(owner.webId(), owner.token()));
        }
        if (!servicePrincipals.isEmpty()) {
            chain.add(new ServiceCredentialResolver(servicePrincipals));
        }
        contributed.orderedStream()
                .map(ChainedPrincipalResolver.Member::resolver)
                .forEach(chain::add);
        // Enforcement on, nobody able to authenticate: safe (deny by default) but inert, and
        // said out loud here, where the whole chain is known — a contributed member counts. Not
        // refused: a public read-only pod whose ACLs were written on disk is a legitimate shape.
        if (owner.isNamed() && chain.isEmpty()) {
            log.warn(WebfluxMessage.ENFORCEMENT_WITHOUT_CREDENTIAL.format());
        }
        chain.add(new AnonymousResolver());
        log.info(WebfluxMessage.PRINCIPAL_RESOLVERS_WIRED.format(chain.stream()
                .map(resolver -> resolver.getClass().getSimpleName())
                .collect(Collectors.joining(RESOLVER_LIST_SEPARATOR))));
        return new ChainedPrincipalResolver(chain);
    }

    /**
     * Enforcement, ahead of every handler (T5.3).
     *
     * <p><strong>Registered only when an owner is named.</strong> Web Access Control needs a
     * root ACL granting somebody Control, or the only reachable decision is "deny" and the pod
     * is inert rather than secure — with no way in to write the ACL that would fix it.
     * {@code cistern.owner.web-id} is who that somebody is, and {@link OwnerPodSeeder} seeds the
     * root ACL for them; so the enforcement switch and its precondition are the same fact, and
     * there is deliberately no second switch (a {@code cistern.enforcement=on} that could be
     * set without an owner would only ever be a brick or a synonym — T7.7, #94).
     *
     * <p>The owner's <em>token</em> is not part of the condition. In production it is unset
     * (ADR 0002): the owner authenticates through the OIDC issuer or a hashed service
     * credential, and enforcement is on all the same.
     *
     * <p>The edge this leaves — a deployment that names no owner is unprotected — is handled
     * in two ways. Nothing configured at all is allowed and made loud ({@code OwnerPodSeeder}
     * warns {@code NO_OWNER_CONFIGURED} on every boot; refusing would turn "upgrade" into
     * "brick" for a laptop pod, and ADR 0001/0002 keep such a pod off any address a stranger
     * can reach). A credential source configured <em>without</em> an owner — the shape a
     * production deployment could mistakenly land in — is refused at bind time
     * ({@link CisternProperties}, {@code ENFORCEMENT_REQUIRES_OWNER}): the credentials would
     * never be asked for, and the pod would be open while its configuration read as locked.
     */
    @Bean
    @ConditionalOnProperty(prefix = "cistern.owner", name = "web-id")
    public AuthorizationFilter cisternAuthorizationFilter(
            PrincipalResolver principals, AccessControl accessControl, RequestPaths paths,
            DecisionSink decisionSink, CisternProperties properties) {
        // The audit policy is applied here, around whatever DecisionSink the context holds, so
        // that replacing the sink (an embedder, a test) does not silently replace the policy.
        DecisionSink guarded = AuditPolicy.of(properties.audit().required()).guard(decisionSink);
        return new AuthorizationFilter(principals, accessControl, paths, guarded, Clock.systemUTC());
    }

    // ---- Receipts (T5.9) -------------------------------------------------------------

    /**
     * Where the decision log lives: a {@link ResourceStore} of its own, rooted at
     * {@code cistern.audit.root} (default {@code <storage root>/.cistern}), addressed in the
     * log's own URI space rather than the pod's.
     *
     * <p>A second store instance rather than a subtree of the pod's, deliberately. The log
     * must not be pod content — not listed by a container, not readable with Read, not
     * deletable with Write, not addressable by an HTTP path — and the cleanest way to have none
     * of those is for the pod's store never to know it exists. The file backend already skips
     * dot-prefixed directories in a listing and encodes a client's leading dot as {@code %2E},
     * so a sibling directory named {@code .cistern} is structurally unreachable from the pod's
     * URI space; nothing in the storage layer had to learn what an audit log is. The same
     * backend, the same volume, the same backup — a different store.
     *
     * <p>{@code @ConditionalOnMissingBean} so an embedder (or a test) can point the log at any
     * {@link ResourceStore}: the sink and the query below only ever see a {@link DecisionLog}.
     */
    @Bean
    @ConditionalOnMissingBean
    public DecisionLog decisionLog(CisternProperties properties) {
        Path root = properties.audit().rootOrDefault(properties.storage());
        return DecisionLog.in(new FileResourceStore(root));
    }

    /**
     * Where every decision goes (T5.9): the JSON Lines log. Replaceable as a whole — an
     * embedder that ships receipts elsewhere declares its own {@link DecisionSink} — but there
     * is always exactly one, because {@link AuthorizationFilter} records through it
     * unconditionally.
     */
    @Bean
    @ConditionalOnMissingBean
    public DecisionSink decisionSink(DecisionLog decisionLog, CisternProperties properties) {
        JsonLinesDecisionSink sink = new JsonLinesDecisionSink(decisionLog);
        log.info(WebfluxMessage.AUDIT_WIRED.format(
                sink.getClass().getSimpleName(), decisionLog.root().uri(),
                AuditPolicy.of(properties.audit().required())));
        return sink;
    }

    /** Reading the same log back, for the receipts query. */
    @Bean
    @ConditionalOnMissingBean
    public DecisionQuery decisionQuery(DecisionLog decisionLog) {
        return new JsonLinesDecisionQuery(decisionLog);
    }

    /**
     * The receipts query, {@code GET <resource>?receipts} (T5.9).
     *
     * <p>Registered under the same condition as the filter, so the query surface exists only
     * when enforcement does: without an owner there are no decisions to record and nothing to
     * protect the log with, and a route that answered anyway would be a receipts endpoint with
     * no receipts and no guard. With the filter present, the request is a plain {@code GET}
     * through the same {@code /**} space as every other — the filter sees {@code ?receipts},
     * requires Control, records the decision, and only then does routing reach this handler.
     * No route bypasses the filter, this one included.
     *
     * <p>Ordered just after the storage-description route and ahead of the catch-all read
     * route, so that a {@code GET} carrying {@code ?receipts} is answered here rather than as a
     * read of the resource. The query string is not part of a resource's identity
     * ({@link RequestPaths}), which is exactly why routing has to look at it: nothing else
     * will.
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 1)
    @ConditionalOnProperty(prefix = "cistern.owner", name = "web-id")
    public RouterFunction<ServerResponse> cisternReceiptsRoutes(DecisionQuery decisionQuery, RequestPaths paths) {
        ReceiptsHandler handler = new ReceiptsHandler(decisionQuery, paths);
        RequestPredicate receipts = RequestPredicates.queryParam(
                ReceiptsParameter.RECEIPTS.parameterName(), value -> true);
        return RouterFunctions.route(
                RequestPredicates.method(HttpMethod.GET)
                        .or(RequestPredicates.method(HttpMethod.HEAD))
                        .and(RequestPredicates.path(ALL_PATHS))
                        .and(receipts),
                handler::receipts);
    }

    @Bean
    public RouterFunction<ServerResponse> cisternDeleteRoutes(ResourceDeleteHandler handler) {
        return RouterFunctions.route(
                RequestPredicates.method(HttpMethod.DELETE).and(RequestPredicates.path(ALL_PATHS)),
                handler::delete);
    }

    /**
     * The write route (T2.2), one bean per method for the reason T2.4 gives above: a read
     * negotiates a representation out while a write validates one in, so they are genuinely
     * different handlers, and a route added per ticket is a route no other ticket has to edit.
     *
     * <p>{@code /**} for the same reason the read route uses it: every path in a pod names a
     * resource, and whether one already exists there is a storage question answered by
     * {@code LdpService.put} (which reports create versus replace), not a routing question.
     */
    @Bean
    public RouterFunction<ServerResponse> cisternWriteRoutes(ResourceWriteHandler handler) {
        return RouterFunctions.route(
                RequestPredicates.method(HttpMethod.PUT).and(RequestPredicates.path(ALL_PATHS)),
                handler::put);
    }

    /**
     * The create route (T2.3), one bean per method for the reason T2.4 gives above.
     *
     * <p>{@code /**} rather than a predicate restricted to paths ending in {@code /}: whether a
     * {@code POST} target is a container is not a routing fact, and the two ways it can fail
     * have different answers that only core can choose between — Solid Protocol §5.3 makes a
     * target with no representation a 404, while a target that exists but is not a container is
     * a 405. Routing containers only would collapse both into whatever the framework produced
     * for an unmatched path.
     */
    @Bean
    public RouterFunction<ServerResponse> cisternCreateRoutes(ResourceCreateHandler handler) {
        return RouterFunctions.route(
                RequestPredicates.method(HttpMethod.POST).and(RequestPredicates.path(ALL_PATHS)),
                handler::post);
    }

    /**
     * The patch route (T2.7), one bean per method for the reason T2.4 gives above.
     *
     * <p>{@code /**} with no {@code contentType} predicate, deliberately. Restricting the route
     * to {@code text/n3} would let the framework answer a wrongly-typed {@code PATCH} — as a 404
     * for an unmatched route, or a 415 with an {@code Accept} field instead of the
     * {@code Accept-Patch} RFC 5789 §2.2 asks for. Routing every {@code PATCH} to the handler
     * keeps that refusal a decision Cistern makes and the single error mapper renders.
     */
    @Bean
    public RouterFunction<ServerResponse> cisternPatchRoutes(ResourcePatchHandler handler) {
        return RouterFunctions.route(
                RequestPredicates.method(HttpMethod.PATCH).and(RequestPredicates.path(ALL_PATHS)),
                handler::patch);
    }

    /**
     * The {@code OPTIONS} route (T2.8).
     *
     * <p>The only route with no path predicate, and deliberately so. RFC 9110 §9.3.7 gives
     * {@code OPTIONS} a second request-target form — {@code OPTIONS *}, which "applies to the
     * server in general rather than to a specific resource" — and {@code *} is not a path, so a
     * {@code /**} predicate would not match it and the asterisk-form would fall through to
     * whatever the framework does with an unrouted request. Matching on the method alone routes
     * both forms to the one handler, which tells them apart itself.
     *
     * <p>CORS preflights never arrive here: {@link #cisternCorsWebFilter} answers them ahead of
     * routing. What this route serves is the plain {@code OPTIONS} of Solid Protocol §5.2.
     */
    @Bean
    public RouterFunction<ServerResponse> cisternOptionsRoutes(ResourceOptionsHandler handler) {
        return RouterFunctions.route(RequestPredicates.method(HttpMethod.OPTIONS), handler::options);
    }

    /**
     * Cross-origin sharing (T2.8), wide open by default because a Solid app is cross-origin by
     * nature — Solid Protocol §8.1: "A server MUST implement the CORS protocol [FETCH] such
     * that, to the extent possible, the browser allows Solid apps to send any request and
     * combination of request headers to the server."
     *
     * <p>A {@code CorsWebFilter} rather than hand-written headers: it implements the Fetch
     * algorithm — preflight detection, {@code Vary}, the header and method checks — and it runs
     * <em>before</em> routing, which is what makes preflight work for a resource that does not
     * exist yet. A browser must preflight a {@code PUT} that creates a resource, and that
     * preflight is addressed to a URI with nothing behind it; handled here, it is answered from
     * configuration without a storage lookup, so it succeeds where a preflight routed to a
     * resource handler would have 404'd and blocked the write. It also puts the
     * {@code Access-Control-*} fields on error responses, so a browser app can read the CORS
     * headers on a 404 or a 412 instead of seeing an opaque network failure.
     *
     * <h2>Origins are echoed, never {@code *}</h2>
     * {@code setAllowedOriginPatterns} rather than {@code setAllowedOrigins}, even for the
     * wide-open default. The two are equally permissive but they emit different responses, and
     * §8.1 requires the specific one: "the server MUST set the
     * {@code Access-Control-Allow-Origin} header field value to the valid {@code Origin} header
     * field value from the request and list {@code Origin} in the {@code Vary} header field
     * value." {@code setAllowedOrigins("*")} would emit the literal {@code *} and violate that;
     * the pattern form echoes the request's own origin. Spring's CORS processor adds
     * {@code Vary: Origin, Access-Control-Request-Method, Access-Control-Request-Headers} either
     * way, satisfying the second half.
     *
     * <h2>Credentials are not allowed, and that is what makes wide-open origins safe</h2>
     * Allowing credentials would tell the browser to attach cookies and HTTP-auth state to
     * cross-origin pod requests — ambient authority any page on the web could then spend as the
     * user. Solid-OIDC does not work that way: it authenticates with an explicit
     * {@code Authorization} header and a {@code DPoP} proof, which a script must set on each
     * request and a browser never adds by itself, so credentials mode would buy the protocol
     * nothing. The Fetch standard also forbids the combination outright — a response to a
     * credentialed request may not carry {@code Access-Control-Allow-Origin: *} — so "permissive
     * origins" and "credentials" cannot both be had; this configuration keeps the one Solid
     * needs and drops the one it does not. See {@link CisternProperties.Cors} for why there is
     * no switch.
     */
    @Bean
    @ConditionalOnMissingBean
    public CorsWebFilter cisternCorsWebFilter(CisternProperties properties) {
        CisternProperties.Cors cors = properties.cors();
        CorsConfiguration configuration = new CorsConfiguration();
        // Patterns, not origins — see the class comment: the request's origin is echoed back.
        cors.allowedOrigins().forEach(configuration::addAllowedOriginPattern);
        // The union of ResourceKind's rows, so a method the server serves is a method a browser
        // may preflight, with no second list to keep in step (the architect requirement on #19).
        ResourceKind.supportedMethods()
                .forEach(method -> configuration.addAllowedMethod(method.name()));
        // Echo, not enumerate (reversed 2026-08-29 on CTH evidence): §8.1 asks the server to
        // let a Solid app send "any request and combination of request headers", and the
        // harness holds both directions — a preflight requesting X-CUSTOM must see it in
        // Access-Control-Allow-Headers, and one NOT requesting Accept must NOT see Accept
        // there. No fixed list satisfies both; echoing the request's own list does, and
        // Spring's processor does exactly that when the wildcard is configured: the response
        // names the headers the preflight asked for, never a literal "*".
        configuration.addAllowedHeader(CorsConfiguration.ALL);
        // §8.1: "The server MUST make all used response headers readable for the Solid app
        // through Access-Control-Expose-Headers". See ExposedResponseHeader.
        ExposedResponseHeader.fieldNames().forEach(configuration::addExposedHeader);
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(cors.maxAge());

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration(ALL_PATHS, configuration);
        // Ordered ahead of authorization — a refusal must still carry the CORS fields; see
        // CorsFirstWebFilter for the evidence.
        return new CorsFirstWebFilter(source);
    }

    /**
     * Restores the {@code Vary: Origin} that Solid Protocol §8.1 requires and that a handler
     * writing its own {@code Vary} would otherwise replace — see {@link OriginVaryFilter}, which
     * explains why this is a filter rather than a line in each handler.
     */
    @Bean
    @ConditionalOnMissingBean
    public OriginVaryFilter cisternOriginVaryFilter() {
        return new OriginVaryFilter();
    }

    /**
     * The LDP/Solid semantics layer over whichever {@link ResourceStore} is configured.
     * Conditional so an embedder can supply its own.
     */
    @Bean
    @ConditionalOnMissingBean
    public LdpService ldpService(ResourceStore store) {
        return new LdpService(store);
    }

    /**
     * Default backend: the file store, rooted at {@code cistern.storage.root}. Conditional so
     * any other backend (or a test's in-memory store) replaces it just by being declared.
     *
     * <p>Backend selection arguably belongs to cistern-spring-boot-starter (T7.1) rather than
     * to the HTTP module; this is here so cistern-app stays configuration only until the
     * starter exists.
     */
    @Bean
    @ConditionalOnMissingBean(ResourceStore.class)
    public ResourceStore resourceStore(CisternProperties properties) {
        return new FileResourceStore(properties.storage().root());
    }
}
