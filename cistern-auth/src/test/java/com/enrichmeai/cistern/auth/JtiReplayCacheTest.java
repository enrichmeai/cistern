package com.enrichmeai.cistern.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

@DisplayName("The jti replay cache promises uniqueness, or refuses")
class JtiReplayCacheTest {

    private static final Duration RETENTION = Duration.ofSeconds(60);
    private static final Instant T0 = Instant.parse("2026-08-25T00:00:00Z");

    private static final class MovableClock extends Clock {
        private Instant now = T0;

        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advance(Duration by) { now = now.plus(by); }
    }

    @Test
    @DisplayName("a jti is fresh once and replayed after")
    void freshThenReplayed() {
        JtiReplayCache cache = new JtiReplayCache(RETENTION, Clock.fixed(T0, ZoneOffset.UTC));

        assertThat(cache.claim("a")).isEqualTo(JtiReplayCache.Result.FRESH);
        assertThat(cache.claim("a")).isEqualTo(JtiReplayCache.Result.REPLAYED);
    }

    @Test
    @DisplayName("once the window has passed the jti may be claimed again")
    void reclaimableAfterRetention() {
        MovableClock clock = new MovableClock();
        JtiReplayCache cache = new JtiReplayCache(RETENTION, clock);
        assertThat(cache.claim("a")).isEqualTo(JtiReplayCache.Result.FRESH);

        clock.advance(RETENTION.plusSeconds(1));

        // Safe only because the validator rejects on iat before it ever asks about the jti:
        // outside the window the proof is dead on time, so forgetting it opens no replay.
        assertThat(cache.claim("a")).isEqualTo(JtiReplayCache.Result.FRESH);
    }

    @Test
    @DisplayName("at its bound it refuses rather than forgetting a live entry")
    void refusesWhenFull() {
        JtiReplayCache cache = new JtiReplayCache(RETENTION, 2, Clock.fixed(T0, ZoneOffset.UTC));

        assertThat(cache.claim("a")).isEqualTo(JtiReplayCache.Result.FRESH);
        assertThat(cache.claim("b")).isEqualTo(JtiReplayCache.Result.FRESH);
        assertThat(cache.claim("c"))
                .describedAs("evicting instead would turn memory pressure into a replay window")
                .isEqualTo(JtiReplayCache.Result.FULL);
        assertThat(cache.claim("a"))
                .describedAs("and the entries it already holds are still honoured")
                .isEqualTo(JtiReplayCache.Result.REPLAYED);
    }

    @Test
    @DisplayName("expired entries are swept, so the bound is about live entries")
    void sweepsExpiredAtTheBound() {
        MovableClock clock = new MovableClock();
        JtiReplayCache cache = new JtiReplayCache(RETENTION, 2, clock);
        cache.claim("a");
        cache.claim("b");

        clock.advance(RETENTION.plusSeconds(1));

        assertThat(cache.claim("c")).isEqualTo(JtiReplayCache.Result.FRESH);
        assertThat(cache.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("exactly one of many concurrent claims on one jti wins")
    void oneWinnerUnderConcurrency() throws Exception {
        int racers = 64;
        JtiReplayCache cache = new JtiReplayCache(RETENTION, Clock.fixed(T0, ZoneOffset.UTC));
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        try {
            List<Callable<JtiReplayCache.Result>> attempts = IntStream.range(0, racers)
                    .<Callable<JtiReplayCache.Result>>mapToObj(i -> () -> cache.claim("same"))
                    .toList();

            long fresh = pool.invokeAll(attempts).stream().map(f -> {
                try { return f.get(); } catch (Exception e) { throw new IllegalStateException(e); }
            }).filter(JtiReplayCache.Result.FRESH::equals).count();

            assertThat(fresh)
                    .describedAs("check-then-insert must be one atomic operation")
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("construction rejects a retention or bound that cannot hold the promise")
    void rejectsInvalidConstruction() {
        Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new JtiReplayCache(Duration.ZERO, clock));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new JtiReplayCache(RETENTION.negated(), clock));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new JtiReplayCache(RETENTION, 0, clock));
    }
}
