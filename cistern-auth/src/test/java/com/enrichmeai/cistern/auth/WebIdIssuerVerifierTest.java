package com.enrichmeai.cistern.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("A WebID must name the issuer that minted a token for it (Solid-OIDC §5)")
class WebIdIssuerVerifierTest {

    private static final URI WEB_ID = URI.create("https://alice.example/profile/card#me");
    private static final URI ISSUER = URI.create("https://idp.example/");
    private static final URI OTHER_ISSUER = URI.create("https://attacker.example/");

    private static WebIdIssuerVerifier verifier() {
        return new WebIdIssuerVerifier(WebClient.builder().build(), WebIdFetchPolicy.defaults(),
                Duration.ofMinutes(5), Clock.fixed(Instant.parse("2026-08-25T00:00:00Z"), ZoneOffset.UTC));
    }

    private static WebIdVerdict check(String document, URI issuer) {
        return verifier().check(WEB_ID, issuer, document);
    }

    private static String profileNaming(String issuer) {
        return """
            @prefix solid: <http://www.w3.org/ns/solid/terms#> .
            <https://alice.example/profile/card#me> solid:oidcIssuer <%s> .
            """.formatted(issuer);
    }

    @Test
    @DisplayName("the issuer the WebID names is accepted")
    void namedIssuerVerifies() {
        assertThat(check(profileNaming("https://idp.example/"), ISSUER))
                .isInstanceOf(WebIdVerdict.Verified.class);
    }

    @Test
    @DisplayName("an issuer the WebID does not name is refused — the check the model rests on")
    void unnamedIssuerRefused() {
        WebIdVerdict verdict = check(profileNaming("https://idp.example/"), OTHER_ISSUER);

        assertThat(verdict).isInstanceOf(WebIdVerdict.Refused.class);
        assertThat(((WebIdVerdict.Refused) verdict).reason())
                .describedAs("otherwise anyone running an IdP can mint a token for any WebID")
                .isEqualTo(JwtRejectionReason.WEBID_ISSUER_NOT_NAMED);
    }

    @Test
    @DisplayName("a WebID naming several issuers accepts any of them")
    void multipleIssuers() {
        String document = """
            @prefix solid: <http://www.w3.org/ns/solid/terms#> .
            <https://alice.example/profile/card#me>
                solid:oidcIssuer <https://other.example/>, <https://idp.example/> .
            """;

        assertThat(check(document, ISSUER)).isInstanceOf(WebIdVerdict.Verified.class);
    }

    @Test
    @DisplayName("a trailing slash on either side is the one difference tolerated")
    void trailingSlashTolerated() {
        assertThat(check(profileNaming("https://idp.example"), URI.create("https://idp.example/")))
                .isInstanceOf(WebIdVerdict.Verified.class);
        assertThat(check(profileNaming("https://idp.example/"), URI.create("https://idp.example")))
                .isInstanceOf(WebIdVerdict.Verified.class);
    }

    @Test
    @DisplayName("a triple on a different subject does not count")
    void issuerNamedForSomebodyElse() {
        String document = """
            @prefix solid: <http://www.w3.org/ns/solid/terms#> .
            <https://alice.example/profile/card#bob> solid:oidcIssuer <https://idp.example/> .
            """;

        assertThat(((WebIdVerdict.Refused) check(document, ISSUER)).reason())
                .describedAs("the triple must be about the WebID the token asserts")
                .isEqualTo(JwtRejectionReason.WEBID_ISSUER_NOT_NAMED);
    }

    @Test
    @DisplayName("a document with no oidcIssuer triple at all is refused")
    void noIssuerTriple() {
        String document = """
            @prefix foaf: <http://xmlns.com/foaf/0.1/> .
            <https://alice.example/profile/card#me> foaf:name "Alice" .
            """;

        assertThat(((WebIdVerdict.Refused) check(document, ISSUER)).reason())
                .isEqualTo(JwtRejectionReason.WEBID_ISSUER_NOT_NAMED);
    }

    @Test
    @DisplayName("a document that is not RDF is refused, not thrown")
    void unparseable() {
        assertThat(((WebIdVerdict.Refused) check("<html>not turtle</html>", ISSUER)).reason())
                .isEqualTo(JwtRejectionReason.WEBID_UNPARSEABLE);
    }

    @Test
    @DisplayName("a refused URL never reaches the network: the policy is wired in")
    void policyRefusalShortCircuitsTheFetch() {
        StepVerifier.create(verifier().verify(URI.create("http://alice.example/profile#me"), ISSUER))
                .assertNext(verdict -> assertThat(((WebIdVerdict.Refused) verdict).reason())
                        .describedAs("http is refused before any connection is attempted")
                        .isEqualTo(JwtRejectionReason.WEBID_SCHEME_REFUSED))
                .verifyComplete();
    }

    @Test
    @DisplayName("a loopback WebID is refused before any connection is attempted")
    void loopbackRefused() {
        StepVerifier.create(verifier().verify(URI.create("https://127.0.0.1/profile#me"), ISSUER))
                .assertNext(verdict -> assertThat(((WebIdVerdict.Refused) verdict).reason())
                        .isEqualTo(JwtRejectionReason.WEBID_ADDRESS_REFUSED))
                .verifyComplete();
    }

    @Test
    @DisplayName("the document is fetched without its fragment")
    void fragmentStripped() {
        assertThat(WebIdIssuerVerifier.documentOf(WEB_ID))
                .isEqualTo(URI.create("https://alice.example/profile/card"));
        assertThat(WebIdIssuerVerifier.documentOf(URI.create("https://alice.example/card")))
                .isEqualTo(URI.create("https://alice.example/card"));
    }
}
