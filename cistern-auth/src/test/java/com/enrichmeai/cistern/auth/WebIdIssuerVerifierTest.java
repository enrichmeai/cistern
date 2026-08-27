package com.enrichmeai.cistern.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

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
        return profileFor(WEB_ID, issuer);
    }

    private static String profileFor(URI webId, String issuer) {
        return """
            @prefix solid: <http://www.w3.org/ns/solid/terms#> .
            <%s> solid:oidcIssuer <%s> .
            """.formatted(webId, issuer);
    }

    /** Reserved {@code .example} hosts never resolve; this declares them public instead. */
    private static WebIdFetchPolicy publicPolicy() {
        return new WebIdFetchPolicy(WebIdFetchPolicy.DEFAULT_TIMEOUT,
                WebIdFetchPolicy.DEFAULT_MAX_REDIRECTS, WebIdFetchPolicy.DEFAULT_MAX_BODY_BYTES,
                host -> new InetAddress[] {InetAddress.getByName("93.184.216.34")}, java.util.Set.of());
    }

    /** A client that proves no fetch happened by failing loudly the moment one is tried. */
    private static WebClient noNetwork() {
        return WebClient.builder()
                .exchangeFunction(request -> Mono.error(new IllegalStateException("the test allows no network")))
                .build();
    }

    /** A clock the test moves by hand. */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-08-25T00:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
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
    @DisplayName("the DNS check runs on boundedElastic, never the subscriber's thread (ground rule 3)")
    void dnsCheckLeavesTheCallingThread() {
        AtomicReference<String> resolverThread = new AtomicReference<>();
        WebIdFetchPolicy policy = new WebIdFetchPolicy(WebIdFetchPolicy.DEFAULT_TIMEOUT,
                WebIdFetchPolicy.DEFAULT_MAX_REDIRECTS, WebIdFetchPolicy.DEFAULT_MAX_BODY_BYTES,
                host -> {
                    resolverThread.set(Thread.currentThread().getName());
                    return new InetAddress[] {InetAddress.getByName("127.0.0.1")};
                },
                java.util.Set.of());
        WebIdIssuerVerifier verifier = new WebIdIssuerVerifier(noNetwork(), policy,
                Duration.ofMinutes(5), Clock.fixed(Instant.parse("2026-08-25T00:00:00Z"), ZoneOffset.UTC));

        StepVerifier.create(verifier.verify(WEB_ID, ISSUER))
                .assertNext(verdict -> assertThat(((WebIdVerdict.Refused) verdict).reason())
                        .isEqualTo(JwtRejectionReason.WEBID_ADDRESS_REFUSED))
                .verifyComplete();
        assertThat(resolverThread.get())
                .describedAs("InetAddress.getAllByName blocks; on an event loop it stalls "
                        + "every request that loop serves")
                .contains("boundedElastic");
    }

    @Test
    @DisplayName("a resolver slower than the timeout is a 401, not a hang")
    void slowDnsIsBoundedByTheTimeout() {
        WebIdFetchPolicy policy = new WebIdFetchPolicy(Duration.ofMillis(100),
                WebIdFetchPolicy.DEFAULT_MAX_REDIRECTS, WebIdFetchPolicy.DEFAULT_MAX_BODY_BYTES,
                host -> {
                    try {
                        Thread.sleep(5_000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return new InetAddress[] {InetAddress.getByName("93.184.216.34")};
                }, java.util.Set.of());
        WebIdIssuerVerifier verifier = new WebIdIssuerVerifier(noNetwork(), policy,
                Duration.ofMinutes(5), Clock.fixed(Instant.parse("2026-08-25T00:00:00Z"), ZoneOffset.UTC));

        StepVerifier.create(verifier.verify(WEB_ID, ISSUER))
                .assertNext(verdict -> assertThat(((WebIdVerdict.Refused) verdict).reason())
                        .describedAs("the fetch budget covers the resolver, not just the server")
                        .isEqualTo(JwtRejectionReason.WEBID_UNREACHABLE))
                .verifyComplete();
    }

    @Test
    @DisplayName("past its bound the cache declines entries, and verification still succeeds")
    void cacheBoundSkipsCachingNotVerification() {
        URI other = URI.create("https://bob.example/profile/card#me");
        WebIdIssuerVerifier verifier = new WebIdIssuerVerifier(noNetwork(), publicPolicy(),
                Duration.ofMinutes(5), 1,
                Clock.fixed(Instant.parse("2026-08-25T00:00:00Z"), ZoneOffset.UTC));

        assertThat(verifier.check(WEB_ID, ISSUER, profileNaming("https://idp.example/")))
                .describedAs("fills the single slot")
                .isInstanceOf(WebIdVerdict.Verified.class);
        assertThat(verifier.check(other, ISSUER, profileFor(other, "https://idp.example/")))
                .describedAs("the bound must never refuse a verification, only decline to cache it")
                .isInstanceOf(WebIdVerdict.Verified.class);

        // The cached pair answers without the network; the uncached one has to fetch, and
        // the no-network client turns that attempt into proof the cache declined it.
        StepVerifier.create(verifier.verify(WEB_ID, ISSUER))
                .assertNext(verdict -> assertThat(verdict).isInstanceOf(WebIdVerdict.Verified.class))
                .verifyComplete();
        StepVerifier.create(verifier.verify(other, ISSUER))
                .assertNext(verdict -> assertThat(((WebIdVerdict.Refused) verdict).reason())
                        .isEqualTo(JwtRejectionReason.WEBID_UNREACHABLE))
                .verifyComplete();
    }

    @Test
    @DisplayName("expired entries make room at the bound — the cache cannot leak")
    void expiredEntriesMakeRoomAtTheBound() {
        URI other = URI.create("https://bob.example/profile/card#me");
        MutableClock clock = new MutableClock();
        Duration ttl = Duration.ofMinutes(5);
        WebIdIssuerVerifier verifier = new WebIdIssuerVerifier(noNetwork(), publicPolicy(), ttl, 1, clock);

        assertThat(verifier.check(WEB_ID, ISSUER, profileNaming("https://idp.example/")))
                .isInstanceOf(WebIdVerdict.Verified.class);
        clock.advance(ttl);
        assertThat(verifier.check(other, ISSUER, profileFor(other, "https://idp.example/")))
                .isInstanceOf(WebIdVerdict.Verified.class);

        StepVerifier.create(verifier.verify(other, ISSUER))
                .assertNext(verdict -> assertThat(verdict)
                        .describedAs("the expired entry was swept, so this one was cached")
                        .isInstanceOf(WebIdVerdict.Verified.class))
                .verifyComplete();
    }

    @Test
    @DisplayName("a cache bound that would disable the guard is refused at construction")
    void rejectsInvalidCacheBound() {
        assertThatIllegalArgumentException().isThrownBy(() -> new WebIdIssuerVerifier(
                noNetwork(), publicPolicy(), Duration.ofMinutes(5), 0,
                Clock.fixed(Instant.parse("2026-08-25T00:00:00Z"), ZoneOffset.UTC)));
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
