package com.enrichmeai.cistern.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

@DisplayName("Issuer discovery trusts any public HTTPS issuer, and is bounded because iss is attacker-chosen")
class DiscoveringIssuersTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-25T00:00:00Z"), ZoneOffset.UTC);

    /**
     * Hostnames under {@code .example} are reserved and never resolve, so the policy would
     * fail them closed on DNS before its address rule ran. Resolving them to a public address
     * here keeps the test about the bound and the scheme, not about the network.
     */
    private static final WebIdFetchPolicy.HostResolver PUBLIC = host -> {
        if (host.endsWith(".example")) {
            return new java.net.InetAddress[] {java.net.InetAddress.getByName("93.184.216.34")};
        }
        return java.net.InetAddress.getAllByName(host);
    };

    private static DiscoveringIssuers issuers(int bound) {
        WebIdFetchPolicy policy = new WebIdFetchPolicy(WebIdFetchPolicy.DEFAULT_TIMEOUT,
                WebIdFetchPolicy.DEFAULT_MAX_REDIRECTS, WebIdFetchPolicy.DEFAULT_MAX_BODY_BYTES, PUBLIC, java.util.Set.of());
        return new DiscoveringIssuers(WebClient.builder().build(), policy,
                Duration.ofSeconds(60), bound, CLOCK);
    }

    @Test
    @DisplayName("a public https issuer gets a verifier")
    void publicIssuerAccepted() {
        assertThat(issuers(8).verifierFor(URI.create("https://idp.example/"))).isPresent();
    }

    @Test
    @DisplayName("the verifier for an issuer is built once and reused")
    void verifiersAreCached() {
        DiscoveringIssuers discovering = issuers(8);
        URI issuer = URI.create("https://idp.example/");

        assertThat(discovering.verifierFor(issuer))
                .containsSame(discovering.verifierFor(issuer).orElseThrow());
        assertThat(discovering.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("an issuer on a non-public address is refused, as a WebID there would be")
    void privateIssuerRefused() {
        assertThat(issuers(8).verifierFor(URI.create("https://169.254.169.254/")))
                .describedAs("an issuer unreachable over public https could not have issued "
                        + "a token any WebID will vouch for")
                .isEmpty();
        assertThat(issuers(8).verifierFor(URI.create("http://idp.example/"))).isEmpty();
    }

    @Test
    @DisplayName("past its bound it refuses new issuers rather than growing")
    void boundedAgainstAttackerChosenIssuers() {
        DiscoveringIssuers discovering = issuers(2);

        assertThat(discovering.verifierFor(URI.create("https://one.example/"))).isPresent();
        assertThat(discovering.verifierFor(URI.create("https://two.example/"))).isPresent();
        assertThat(discovering.verifierFor(URI.create("https://three.example/")))
                .describedAs("iss is chosen by the caller: unbounded here is unbounded memory "
                        + "and unbounded outbound fetches")
                .isEmpty();
        assertThat(discovering.verifierFor(URI.create("https://one.example/")))
                .describedAs("issuers already known keep working")
                .isPresent();
    }

    @Test
    @DisplayName("a bound that would disable the guard is refused at construction")
    void rejectsInvalidBound() {
        assertThatIllegalArgumentException().isThrownBy(() -> issuers(0));
    }

    @Test
    @DisplayName("the bound holds even when racing requests all name fresh issuers")
    void boundHoldsUnderConcurrency() throws Exception {
        DiscoveringIssuers discovering = issuers(4);
        int racers = 16;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(racers);
        try {
            java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
            java.util.List<java.util.concurrent.Future<Boolean>> outcomes = new java.util.ArrayList<>();
            for (int i = 0; i < racers; i++) {
                URI issuer = URI.create("https://idp-" + i + ".example/");
                outcomes.add(pool.submit(() -> {
                    start.await();
                    return discovering.verifierFor(issuer).isPresent();
                }));
            }
            start.countDown();
            int admitted = 0;
            for (java.util.concurrent.Future<Boolean> outcome : outcomes) {
                if (outcome.get()) {
                    admitted++;
                }
            }
            assertThat(admitted)
                    .describedAs("size-check-then-insert as two operations would admit "
                            + "every racer that read the map before the first insert landed")
                    .isEqualTo(4);
            assertThat(discovering.size()).isEqualTo(4);
        } finally {
            pool.shutdownNow();
        }
    }
}
