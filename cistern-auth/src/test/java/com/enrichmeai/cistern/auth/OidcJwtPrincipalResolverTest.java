package com.enrichmeai.cistern.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.enrichmeai.cistern.core.Agent;

import java.time.Clock;
import java.util.Optional;

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

    // ---- the client half of the principal (T6.5, #119) ---------------------------------------

    /** The delegation realm: the same resolver, over a realm whose client ids are URIs. */
    private static final OidcJwtPrincipalResolver DELEGATION_REALM = new OidcJwtPrincipalResolver(
            DelegationFixtures.TRUSTED, new WebIdMapping.Claim("webid"),
            new InMemoryJwksClient(DelegationFixtures.jwks(), DelegationFixtures.jwks()), Clock.systemUTC());

    /**
     * The finding the delegation capture records: a Keycloak-issued user token carries no
     * {@code client_id} at all — its client is in {@code azp}. Reading {@code client_id} alone
     * would leave every person-through-an-application principal without a client.
     */
    @Test
    @DisplayName("a user's token names its client in azp, and that becomes Agent.client(): alice via claude")
    void userTokenClientIsReadFromAzp() {
        assertNull(DelegationFixtures.claims("alice-via-claude").getClaim(ClientIdentifier.CLIENT_ID_CLAIM),
                "the capture carries no client_id on a user token");
        assertEquals(DelegationFixtures.CLAUDE.toString(),
                DelegationFixtures.claims("alice-via-claude").getClaim(ClientIdentifier.AUTHORIZED_PARTY_CLAIM));

        expectAgent(DELEGATION_REALM, DelegationFixtures.token("alice-via-claude"),
                Agent.of(DelegationFixtures.ALICE, Optional.of(DelegationFixtures.CLAUDE)));
        expectAgent(DELEGATION_REALM, DelegationFixtures.token("alice-via-other"),
                Agent.of(DelegationFixtures.ALICE, Optional.of(DelegationFixtures.OTHER)));
    }

    @Test
    @DisplayName("a client-credentials token carries client_id, and that is what is read: the client as itself")
    void clientCredentialsTokenClientIsReadFromClientId() {
        assertEquals(DelegationFixtures.CLAUDE.toString(),
                DelegationFixtures.claims("claude-self").getClaim(ClientIdentifier.CLIENT_ID_CLAIM));

        expectAgent(DELEGATION_REALM, DelegationFixtures.token("claude-self"),
                Agent.of(DelegationFixtures.CLAUDE, Optional.of(DelegationFixtures.CLAUDE)));
    }

    @Test
    @DisplayName("an opaque client id — Keycloak's short names, in azp or in client_id — reads as no client")
    void opaqueClientIsAbsent() {
        assertEquals("valuedocs-legal", Fixtures.claims("alice-valid").getClaim(ClientIdentifier.AUTHORIZED_PARTY_CLAIM));
        assertNull(Fixtures.claims("alice-valid").getClaim(ClientIdentifier.CLIENT_ID_CLAIM));
        expectAgent(BY_CLAIM, Fixtures.token("alice-valid"), Agent.of(Fixtures.ALICE));

        assertEquals("valuedocs-legal", Fixtures.claims("valuedocs-legal-valid").getClaim(ClientIdentifier.CLIENT_ID_CLAIM));
        expectAgent(BY_CLAIM, Fixtures.token("valuedocs-legal-valid"), Agent.of(Fixtures.VALUEDOCS_LEGAL));
    }

    @Test
    @DisplayName("a token from the delegation realm under the T4.0 realm's resolver is a different issuer: anonymous")
    void otherRealmIsAnonymousHere() {
        expectAgent(BY_CLAIM, DelegationFixtures.token("alice-via-claude"), Agent.ANONYMOUS);
    }
}
