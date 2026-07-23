package com.enrichmeai.cistern.webflux;

import com.enrichmeai.cistern.core.ResourceStore;
import com.enrichmeai.cistern.core.ldp.LdpService;
import com.enrichmeai.cistern.wac.AccessControl;
import com.enrichmeai.cistern.wac.AclDiscovery;
import com.enrichmeai.cistern.wac.WacEngine;
import com.enrichmeai.cistern.webflux.auth.AnonymousResolver;
import com.enrichmeai.cistern.webflux.auth.LocalCredentialResolver;
import com.enrichmeai.cistern.webflux.auth.PrincipalResolver;
import com.enrichmeai.cistern.storage.file.FileResourceStore;
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
     * Seeds the root ACL on first boot (T5.4). Without it, deny-by-default plus an absent
     * root ACL means nobody can reach anything — including the owner, and including the ACL
     * they would need to write to fix it.
     */
    @Bean
    @ConditionalOnMissingBean
    public OwnerPodSeeder ownerPodSeeder(ResourceStore store, CisternProperties properties) {
        return new OwnerPodSeeder(store, properties);
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
     * How a request becomes an {@code Agent}. The local credential when an owner is
     * configured, otherwise nothing authenticates — an unconfigured server must not ship a
     * usable default credential, and "no owner set" is a clearer failure than a secret
     * everybody knows.
     *
     * <p>{@code @ConditionalOnMissingBean} so cistern-auth can supply a Solid-OIDC resolver
     * in Phase 4 without this having to know about it.
     */
    @Bean
    @ConditionalOnMissingBean
    public PrincipalResolver principalResolver(CisternProperties properties) {
        CisternProperties.Owner owner = properties.owner();
        return owner.isConfigured()
                ? new LocalCredentialResolver(owner.webId(), owner.token())
                : new AnonymousResolver();
    }

    /**
     * Enforcement, ahead of every handler (T5.3).
     *
     * <p><strong>Registered only when an owner is configured.</strong> Web Access Control
     * needs two things to be useful: a principal, and a root ACL granting that principal
     * something. Without {@code cistern.owner.web-id} there is neither — nobody can
     * authenticate, so the only reachable decision is "deny", and the pod would be inert
     * rather than secure. There would also be no way in to write the ACL that would fix it.
     *
     * <p>This is a deliberate choice with a real edge: a deployment that forgets to set an
     * owner is unprotected, exactly as it is today. It is made visible rather than silent —
     * {@code OwnerPodSeeder} logs {@code NO_OWNER_CONFIGURED} at WARN on every boot — and the
     * alternative, enforcing with no way to authenticate, turns "upgrade" into "brick".
     * ADR 0001 keeps such a deployment on loopback regardless.
     */
    @Bean
    @ConditionalOnProperty(prefix = "cistern.owner", name = "web-id")
    public AuthorizationFilter cisternAuthorizationFilter(
            PrincipalResolver principals, AccessControl accessControl, RequestPaths paths) {
        return new AuthorizationFilter(principals, accessControl, paths);
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
        // Enumerated, never "*": Fetch excludes Authorization from the wildcard, and §8.1 asks
        // for Accept by name. See AllowedRequestHeader.
        AllowedRequestHeader.fieldNames().forEach(configuration::addAllowedHeader);
        // §8.1: "The server MUST make all used response headers readable for the Solid app
        // through Access-Control-Expose-Headers". See ExposedResponseHeader.
        ExposedResponseHeader.fieldNames().forEach(configuration::addExposedHeader);
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(cors.maxAge());

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration(ALL_PATHS, configuration);
        return new CorsWebFilter(source);
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
