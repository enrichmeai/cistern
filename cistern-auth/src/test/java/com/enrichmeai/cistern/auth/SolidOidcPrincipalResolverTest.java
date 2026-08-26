package com.enrichmeai.cistern.auth;

import com.enrichmeai.cistern.core.Agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Optional;

import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("All three checks must hold before a Solid-OIDC request is anybody")
class SolidOidcPrincipalResolverTest {

    private static final URI BASE_URL = URI.create("http://localhost:3939");
    private static final String PATH = "/alice/private/note.ttl";
    private static final Duration WINDOW = Duration.ofSeconds(60);

    private static Clock atProofTime() {
        return Clock.fixed(CssFixtures.dpopIssuedAt(), ZoneOffset.UTC);
    }

    /** Every check wired for success; individual tests swap one out. */
    private static SolidOidcPrincipalResolver resolver(WebIdIssuers webIds) {
        Clock clock = atProofTime();
        return new SolidOidcPrincipalResolver(
                new SolidOidcTokenVerifier(issuer -> Optional.of(
                        new JwtVerifier(CssFixtures.TRUSTED, new InMemoryJwksClient(CssFixtures.jwks()), clock))),
                new DpopValidator(WINDOW, new JtiReplayCache(WINDOW, clock), clock),
                webIds,
                BASE_URL);
    }

    private static WebIdIssuers vouching() {
        return (webId, issuer) -> Mono.just(WebIdVerdict.Verified.instance());
    }

    private static WebIdIssuers refusing() {
        return (webId, issuer) -> Mono.just(
                WebIdVerdict.Refused.of(JwtRejectionReason.WEBID_ISSUER_NOT_NAMED, issuer, "[]"));
    }

    private static ServerWebExchange request(String token, String proof) {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.get(PATH);
        if (token != null) {
            builder.header(HttpHeaders.AUTHORIZATION, "DPoP " + token);
        }
        if (proof != null) {
            builder.header(SolidOidcPrincipalResolver.DPOP_HEADER, proof);
        }
        return MockServerWebExchange.from(builder.build());
    }

    @Test
    @DisplayName("token, proof and a vouching WebID authenticate the agent")
    void allThreeHold() {
        StepVerifier.create(resolver(vouching())
                        .resolve(request(CssFixtures.accessToken(), CssFixtures.dpopProof())))
                .assertNext(agent -> {
                    assertThat(agent.isAuthenticated()).isTrue();
                    assertThat(agent.webId()).contains(CssFixtures.ALICE);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("a WebID that does not name the issuer authenticates nobody")
    void webIdDoesNotVouch() {
        StepVerifier.create(resolver(refusing())
                        .resolve(request(CssFixtures.accessToken(), CssFixtures.dpopProof())))
                .assertNext(agent -> assertThat(agent)
                        .describedAs("without this any IdP could mint a token for any WebID")
                        .isEqualTo(Agent.ANONYMOUS))
                .verifyComplete();
    }

    @Test
    @DisplayName("a valid token with no DPoP proof authenticates nobody")
    void missingProof() {
        StepVerifier.create(resolver(vouching()).resolve(request(CssFixtures.accessToken(), null)))
                .assertNext(agent -> assertThat(agent).isEqualTo(Agent.ANONYMOUS))
                .verifyComplete();
    }

    @Test
    @DisplayName("a valid token with a proof for another target authenticates nobody")
    void proofForAnotherTarget() {
        StepVerifier.create(resolver(vouching())
                        .resolve(request(CssFixtures.accessToken(), CssFixtures.dpop("wrong-htu"))))
                .assertNext(agent -> assertThat(agent).isEqualTo(Agent.ANONYMOUS))
                .verifyComplete();
    }

    @Test
    @DisplayName("a replayed proof authenticates nobody the second time")
    void replayedProof() {
        SolidOidcPrincipalResolver resolver = resolver(vouching());

        StepVerifier.create(resolver.resolve(request(CssFixtures.accessToken(), CssFixtures.dpopProof())))
                .assertNext(agent -> assertThat(agent.isAuthenticated()).isTrue())
                .verifyComplete();
        StepVerifier.create(resolver.resolve(request(CssFixtures.accessToken(), CssFixtures.dpopProof())))
                .assertNext(agent -> assertThat(agent).isEqualTo(Agent.ANONYMOUS))
                .verifyComplete();
    }

    @Test
    @DisplayName("a request with no DPoP credential is passed on, not claimed")
    void noCredentialDefersToTheChain() {
        // Empty, not ANONYMOUS: the chain takes the first resolver that answers, so claiming
        // a request this resolver does not recognise would stop the owner's own credential
        // and the service principals from ever being looked at.
        StepVerifier.create(resolver(vouching()).resolve(request(null, null)))
                .verifyComplete();
    }

    /**
     * §4.3 step 9 compares URIs, and the client signs the URL it dialled — encoded. A
     * decoding refactor here would fail every proof for a URI with an encoded reserved
     * character and look exactly like a broken client, so the raw path is pinned.
     */
    @Test
    @DisplayName("the DPoP target keeps the path exactly as the client encoded it, on the configured base")
    void targetKeepsTheRawPath() {
        var request = org.springframework.mock.http.server.reactive.MockServerHttpRequest
                .method(org.springframework.http.HttpMethod.GET,
                        URI.create("http://some-socket-host:9999/alice/a%20note%2Bmore.ttl"))
                .build();

        assertThat(resolver(vouching()).targetOf(request))
                .describedAs("base-url replaces the socket's origin; the raw path survives")
                .isEqualTo(URI.create(BASE_URL + "/alice/a%20note%2Bmore.ttl"));
    }

    @Test
    @DisplayName("a Bearer credential is not claimed by the DPoP resolver")
    void bearerIsNotOurs() {
        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get(PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + CssFixtures.accessToken())
                        .build());

        StepVerifier.create(resolver(vouching()).resolve(exchange)).verifyComplete();
    }
}
