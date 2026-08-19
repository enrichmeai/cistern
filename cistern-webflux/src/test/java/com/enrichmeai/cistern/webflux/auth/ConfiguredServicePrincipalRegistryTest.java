package com.enrichmeai.cistern.webflux.auth;

import com.enrichmeai.cistern.core.Agent;
import com.enrichmeai.cistern.webflux.CisternProperties;

import java.net.URI;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Service principals from configuration, and the resolver over them (T4.0). */
class ConfiguredServicePrincipalRegistryTest {

    private static final URI LEGAL = URI.create("https://valuedocs.co.in/apps/legal#id");
    private static final URI TAX = URI.create("https://valuedocs.co.in/apps/tax#id");
    private static final String LEGAL_SECRET = "legal-secret-0f3c8b";
    private static final String TAX_SECRET = "tax-secret-71ad2e";

    private static CisternProperties.ServicePrincipal entry(URI webId, String secret) {
        return new CisternProperties.ServicePrincipal(
                webId, HashedCredential.hash(HashAlgorithm.SHA_256, secret).encoded());
    }

    private static final ServicePrincipalRegistry REGISTRY = ConfiguredServicePrincipalRegistry.from(
            List.of(entry(LEGAL, LEGAL_SECRET), entry(TAX, TAX_SECRET)));

    @Test
    @DisplayName("each secret proves exactly its own principal")
    void eachSecretProvesItsOwnPrincipal() {
        assertEquals(LEGAL, REGISTRY.byCredential(LEGAL_SECRET).orElseThrow().webId());
        assertEquals(TAX, REGISTRY.byCredential(TAX_SECRET).orElseThrow().webId());
        assertTrue(REGISTRY.byCredential("neither").isEmpty());
        assertEquals(Set.of(LEGAL, TAX), REGISTRY.webIds());
    }

    @Test
    @DisplayName("nothing configured is an empty registry, not an error")
    void emptyConfiguration() {
        assertTrue(ConfiguredServicePrincipalRegistry.from(List.of()).isEmpty());
    }

    @Test
    @DisplayName("two principals with one credential are refused at startup — whose would it be?")
    void duplicateCredentialRefused() {
        assertThrows(IllegalArgumentException.class, () -> ConfiguredServicePrincipalRegistry.from(
                List.of(entry(LEGAL, LEGAL_SECRET), entry(TAX, LEGAL_SECRET))));
    }

    @Test
    @DisplayName("one identity with two credentials (rotation) is fine")
    void sameWebIdTwiceIsAllowed() {
        ServicePrincipalRegistry registry = ConfiguredServicePrincipalRegistry.from(
                List.of(entry(LEGAL, LEGAL_SECRET), entry(LEGAL, TAX_SECRET)));
        assertEquals(LEGAL, registry.byCredential(TAX_SECRET).orElseThrow().webId());
        assertEquals(Set.of(LEGAL), registry.webIds());
    }

    @Test
    @DisplayName("a malformed hash fails the boot, not the request")
    void malformedHashRefused() {
        assertThrows(IllegalArgumentException.class, () -> ConfiguredServicePrincipalRegistry.from(
                List.of(new CisternProperties.ServicePrincipal(LEGAL, "not-a-hash"))));
    }

    @Test
    @DisplayName("the resolver turns a presented secret into the principal's own WebID")
    void resolverProducesTheServicesWebId() {
        var resolver = new ServiceCredentialResolver(REGISTRY);

        StepVerifier.create(resolver.resolve(bearer(TAX_SECRET)))
                .expectNext(Agent.of(TAX)).verifyComplete();
        StepVerifier.create(resolver.resolve(bearer("wrong")))
                .expectNext(Agent.ANONYMOUS).verifyComplete();
        StepVerifier.create(resolver.resolve(MockServerWebExchange.from(MockServerHttpRequest.get("/"))))
                .expectNext(Agent.ANONYMOUS).verifyComplete();
    }

    private static MockServerWebExchange bearer(String token) {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }
}
