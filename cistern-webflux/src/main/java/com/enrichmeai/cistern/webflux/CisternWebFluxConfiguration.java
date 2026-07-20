package com.enrichmeai.cistern.webflux;

import com.enrichmeai.cistern.core.ResourceStore;
import com.enrichmeai.cistern.core.ldp.LdpService;
import com.enrichmeai.cistern.storage.file.FileResourceStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
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
