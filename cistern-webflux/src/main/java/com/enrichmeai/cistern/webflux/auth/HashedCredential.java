package com.enrichmeai.cistern.webflux.auth;

import com.enrichmeai.cistern.webflux.WebfluxMessage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/**
 * A service principal's credential as it is held at rest: the digest of the secret, never the
 * secret (T4.0, #88).
 *
 * <p>Encoded as {@code <label>:<hex digest>}, e.g. {@code sha256:9f86d0…}, so that the
 * algorithm travels with the value and the format can grow without an existing configuration
 * changing meaning. An operator produces one with
 * <pre>{@code printf '%s' "$SECRET" | shasum -a 256 | cut -d' ' -f1 | sed 's/^/sha256:/'}</pre>
 * or with {@link #hash}.
 *
 * <p>Comparison is constant-time ({@link MessageDigest#isEqual}): what is compared is the
 * digest of what was presented against the stored digest, both of fixed length, and ordinary
 * array equality returning at the first differing byte would leak how much of a guess was
 * right to anyone who can time the response.
 */
public final class HashedCredential {

    /** Separates the algorithm label from the hex digest in the encoded form. */
    private static final char SEPARATOR = ':';

    private static final HexFormat HEX = HexFormat.of();

    private final HashAlgorithm algorithm;
    private final byte[] digest;

    private HashedCredential(HashAlgorithm algorithm, byte[] digest) {
        this.algorithm = algorithm;
        this.digest = digest;
    }

    /**
     * Parse the encoded form, {@code <label>:<hex digest>}.
     *
     * @throws IllegalArgumentException if the label names no known algorithm, the digest is
     *                                  not hex, or its length is wrong for the algorithm
     */
    public static HashedCredential parse(String encoded) {
        Objects.requireNonNull(encoded, "encoded");
        int separator = encoded.indexOf(SEPARATOR);
        if (separator < 0) {
            throw new IllegalArgumentException(
                    WebfluxMessage.CREDENTIAL_HASH_MALFORMED.format(encoded));
        }
        String label = encoded.substring(0, separator);
        HashAlgorithm algorithm = HashAlgorithm.byLabel(label).orElseThrow(() ->
                new IllegalArgumentException(
                        WebfluxMessage.CREDENTIAL_HASH_UNKNOWN_ALGORITHM.format(
                                label, HashAlgorithm.labels())));
        byte[] digest;
        try {
            digest = HEX.parseHex(encoded.substring(separator + 1));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    WebfluxMessage.CREDENTIAL_HASH_MALFORMED.format(encoded), e);
        }
        if (digest.length != algorithm.digestLength()) {
            throw new IllegalArgumentException(
                    WebfluxMessage.CREDENTIAL_HASH_WRONG_LENGTH.format(
                            algorithm.label(), algorithm.digestLength(), digest.length));
        }
        return new HashedCredential(algorithm, digest);
    }

    /** The stored form of {@code secret} under {@code algorithm}: what an operator configures. */
    public static HashedCredential hash(HashAlgorithm algorithm, String secret) {
        Objects.requireNonNull(algorithm, "algorithm");
        Objects.requireNonNull(secret, "secret");
        return new HashedCredential(algorithm, digestOf(algorithm, secret));
    }

    /** Whether {@code presented} is the secret this is the digest of. Constant-time. */
    public boolean matches(String presented) {
        Objects.requireNonNull(presented, "presented");
        return MessageDigest.isEqual(digestOf(algorithm, presented), digest);
    }

    public HashAlgorithm algorithm() {
        return algorithm;
    }

    /** The encoded form, {@code <label>:<hex digest>} — the inverse of {@link #parse}. */
    public String encoded() {
        return algorithm.label() + SEPARATOR + HEX.formatHex(digest);
    }

    private static byte[] digestOf(HashAlgorithm algorithm, String secret) {
        return algorithm.digest().digest(secret.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof HashedCredential that
                && algorithm == that.algorithm
                && Arrays.equals(digest, that.digest);
    }

    @Override
    public int hashCode() {
        return Objects.hash(algorithm, Arrays.hashCode(digest));
    }

    /** The encoded form. A digest of a secret is not the secret, so this is safe to log. */
    @Override
    public String toString() {
        return encoded();
    }
}
