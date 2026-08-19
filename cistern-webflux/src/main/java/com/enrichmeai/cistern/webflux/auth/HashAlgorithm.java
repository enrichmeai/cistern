package com.enrichmeai.cistern.webflux.auth;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The digest a service principal's credential is stored under (T4.0, #88).
 *
 * <p>An enum, not a string, because it is a closed set (ground rule 7): the label is what an
 * operator writes in {@code cistern.auth.service-principals[n].credential-hash}, the JCA name
 * is what the runtime asks the provider for, and a label that is not listed here is a
 * configuration error rather than a lookup that happens to fail.
 *
 * <p>Only SHA-256 in v1, and that is a documented choice rather than an oversight. A service
 * credential is a high-entropy machine secret (an operator generates it with
 * {@code openssl rand -hex 32}), not a human password: brute-forcing a 256-bit random value
 * is infeasible whatever the hash, so the slow, salted password hashes (Argon2, bcrypt) buy
 * nothing here and would cost every request a deliberate delay. What SHA-256 does buy is that
 * the configuration file — and the environment, and the Kubernetes Secret — holds nothing an
 * attacker can present. Adding an algorithm is adding a constant; the labelled format means an
 * existing configuration keeps meaning what it meant.
 */
public enum HashAlgorithm {

    SHA_256("sha256", "SHA-256", 32);

    private final String label;
    private final String jcaName;
    private final int digestLength;

    HashAlgorithm(String label, String jcaName, int digestLength) {
        this.label = label;
        this.jcaName = jcaName;
        this.digestLength = digestLength;
    }

    /** The label that prefixes an encoded credential hash: {@code sha256:…}. */
    public String label() {
        return label;
    }

    /** Length in bytes of a digest this algorithm produces. */
    public int digestLength() {
        return digestLength;
    }

    /** The algorithm known by {@code label}, if any. */
    public static Optional<HashAlgorithm> byLabel(String label) {
        return Arrays.stream(values()).filter(algorithm -> algorithm.label.equals(label)).findFirst();
    }

    /** Every label this server accepts, for the message that names them. */
    public static List<String> labels() {
        return Arrays.stream(values()).map(HashAlgorithm::label).toList();
    }

    /** A fresh, unshared digest instance — {@link MessageDigest} is not thread-safe. */
    MessageDigest digest() {
        try {
            return MessageDigest.getInstance(jcaName);
        } catch (NoSuchAlgorithmException e) {
            // Every JDK ships SHA-256 (it is in the "required" list of the MessageDigest
            // specification), so this cannot happen on a conforming runtime.
            throw new IllegalStateException(jcaName, e);
        }
    }
}
