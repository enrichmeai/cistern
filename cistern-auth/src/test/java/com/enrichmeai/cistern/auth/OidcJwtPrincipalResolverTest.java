package com.enrichmeai.cistern.auth;

import com.enrichmeai.cistern.core.Agent;

import java.time.Clock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.test.StepVerifier;

/** The resolver end to end, in memory: bearer → verdict → mapping → Agent (T4.0). */
class OidcJwtPrincipalResolverTest {

    private static OidcJwtPrincipalResolver resolver(WebIdMapping mapping) {
        return new OidcJwtPrincipalResolver(
                Fixtures.TRUSTED, mapping, new InMemoryJwksClient(Fixtures.jwks(), Fixtures.jwksRotated()),
                Clock.systemUTC());
    }

    private static final OidcJwtPrincipalResolver BY_CLAIM = resolver(new WebIdMapping.Claim("webid"));

    private static ServerWebExchange bearer(String token) {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private static void expectAgent(OidcJwtPrincipalResolver resolver, String token, Agent agent) {
        StepVerifier.create(resolver.resolve(bearer(token))).expectNext(agent).verifyComplete();
    }

    @Test
    @DisplayName("alice's valid token authenticates alice, by the webid claim")
    void validTokenAuthenticates() {
        expectAgent(BY_CLAIM, Fixtures.token("alice-valid"), Agent.of(Fixtures.ALICE));
        expectAgent(BY_CLAIM, Fixtures.token("bob-valid"), Agent.of(Fixtures.BOB));
    }

    @Test
    @DisplayName("the two applications' client-credentials tokens authenticate their own WebIDs")
    void applicationsAuthenticateAsThemselves() {
        expectAgent(BY_CLAIM, Fixtures.token("valuedocs-legal-valid"), Agent.of(Fixtures.VALUEDOCS_LEGAL));
        expectAgent(BY_CLAIM, Fixtures.token("valuedocs-tax-valid"), Agent.of(Fixtures.VALUEDOCS_TAX));
    }

    @Test
    @DisplayName("template mapping: the same token, a WebID minted from iss and sub")
    void templateMapping() {
        OidcJwtPrincipalResolver byTemplate = resolver(new WebIdMapping.Template("{iss}/users/{sub}#me"));
        expectAgent(byTemplate, Fixtures.token("alice-valid"),
                Agent.of(java.net.URI.create(Fixtures.ISSUER + "/users/" + Fixtures.claims("alice-valid").getSubject() + "#me")));
    }

    @Test
    @DisplayName("expired, wrong audience, bad signature: anonymous, never an error")
    void rejectedTokensAreAnonymous() {
        expectAgent(BY_CLAIM, Fixtures.token("alice-expired"), Agent.ANONYMOUS);
        expectAgent(BY_CLAIM, Fixtures.token("alice-wrong-audience"), Agent.ANONYMOUS);
        expectAgent(BY_CLAIM, Fixtures.token("alice-bad-signature"), Agent.ANONYMOUS);
    }

    @Test
    @DisplayName("a token under a rotated key authenticates after the refresh")
    void rotatedKeyAuthenticates() {
        expectAgent(BY_CLAIM, Fixtures.token("alice-rotated-key"), Agent.of(Fixtures.ALICE));
    }

    @Test
    @DisplayName("no bearer, a non-JWT bearer, and an unreachable issuer are all anonymous")
    void nothingUsableIsAnonymous() {
        StepVerifier.create(BY_CLAIM.resolve(MockServerWebExchange.from(MockServerHttpRequest.get("/"))))
                .expectNext(Agent.ANONYMOUS).verifyComplete();
        expectAgent(BY_CLAIM, "owner-token-3f9a", Agent.ANONYMOUS);

        OidcJwtPrincipalResolver issuerDown = new OidcJwtPrincipalResolver(Fixtures.TRUSTED,
                new WebIdMapping.Claim("webid"), InMemoryJwksClient.unavailable("down"), Clock.systemUTC());
        expectAgent(issuerDown, Fixtures.token("alice-valid"), Agent.ANONYMOUS);
    }

    @Test
    @DisplayName("a verified token whose claims name no WebID authenticates nobody")
    void verifiedButUnmappableIsAnonymous() {
        expectAgent(resolver(new WebIdMapping.Claim("no_such_claim")), Fixtures.token("alice-valid"), Agent.ANONYMOUS);
    }
}
