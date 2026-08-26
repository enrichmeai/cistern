package com.enrichmeai.cistern.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers the {@code jti} of every DPoP proof accepted inside the acceptance window, so the
 * same proof cannot be presented twice (RFC 9449 §4.3, and §11.1 on replay).
 *
 * <p>Only has to remember a proof for as long as that proof would still be accepted. Outside
 * the {@code iat} window the validator rejects on time before it ever asks about the
 * {@code jti}, so an entry older than the window can be forgotten without opening a replay.
 *
 * <h2>Two properties that a single-threaded test will not show</h2>
 *
 * <p><strong>The check and the insert are one operation.</strong> {@link Map#putIfAbsent}
 * returns the previous value, so "was it there, and if not claim it" happens under the map's
 * per-bin lock. A {@code containsKey} followed by a {@code put} is the same code to read and
 * lets two concurrent requests carrying one stolen proof both succeed — which is exactly the
 * attack this class exists to stop, and no sequential test can distinguish the two.
 *
 * <p><strong>Full means refuse, not evict.</strong> The map is bounded, because a flood of
 * unique {@code jti}s is otherwise unbounded memory that any unauthenticated caller can
 * allocate. When the bound is reached this reports {@link Result#FULL} and the validator
 * rejects the request. Evicting the oldest entries instead would keep the server answering at
 * the cost of forgetting proofs that are still inside their window — turning memory pressure
 * into a replay window, silently. Refusing is the direction that fails safe.
 */
public final class JtiReplayCache {

    /** Entries held before the cache refuses. ~48 bytes each, so a few MB at this bound. */
    public static final int DEFAULT_MAXIMUM_ENTRIES = 100_000;

    /** What {@link #claim} found. */
    public enum Result {
        /** Not seen before; it is now claimed until its window closes. */
        FRESH,
        /** Seen inside the window: a replay. */
        REPLAYED,
        /** The cache is at its bound and cannot promise uniqueness. */
        FULL
    }

    private final ConcurrentHashMap<String, Instant> seen = new ConcurrentHashMap<>();
    private final Duration retention;
    private final int maximumEntries;
    private final Clock clock;

    /**
     * @param retention     how long a jti is remembered — the validator's acceptance window,
     *                      or longer; never shorter, or a proof could be replayed after its
     *                      entry is forgotten but while it is still accepted on time
     * @param maximumEntries the bound past which this refuses rather than forgets
     */
    public JtiReplayCache(Duration retention, int maximumEntries, Clock clock) {
        this.retention = Objects.requireNonNull(retention, "retention");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (retention.isNegative() || retention.isZero()) {
            throw new IllegalArgumentException(AuthMessage.DPOP_RETENTION_INVALID.format(retention));
        }
        if (maximumEntries <= 0) {
            throw new IllegalArgumentException(AuthMessage.DPOP_BOUND_INVALID.format(maximumEntries));
        }
        this.maximumEntries = maximumEntries;
    }

    public JtiReplayCache(Duration retention, Clock clock) {
        this(retention, DEFAULT_MAXIMUM_ENTRIES, clock);
    }

    /** Claims {@code jti} for this window, reporting whether it was already taken. */
    public Result claim(String jti) {
        Objects.requireNonNull(jti, "jti");
        Instant now = clock.instant();
        Instant expiresAt = now.plus(retention);

        Instant previous = seen.putIfAbsent(jti, expiresAt);
        if (previous == null) {
            // Won the race and claimed it. Only now, off the hot path of a fresh proof, is it
            // worth paying for a sweep — and only if the map has actually grown.
            if (seen.size() > maximumEntries) {
                evictExpired(now);
                if (seen.size() > maximumEntries) {
                    seen.remove(jti, expiresAt);
                    return Result.FULL;
                }
            }
            return Result.FRESH;
        }
        if (now.isBefore(previous)) {
            return Result.REPLAYED;
        }
        // The entry was stale: this jti's earlier window has closed. Take it again, but only
        // if nobody else has, so two concurrent requests still cannot both claim it.
        return seen.replace(jti, previous, expiresAt) ? Result.FRESH : Result.REPLAYED;
    }

    /** How many jtis are currently remembered, expired ones included until the next sweep. */
    public int size() {
        return seen.size();
    }

    private void evictExpired(Instant now) {
        for (Iterator<Map.Entry<String, Instant>> it = seen.entrySet().iterator(); it.hasNext(); ) {
            if (!now.isBefore(it.next().getValue())) {
                it.remove();
            }
        }
    }
}
