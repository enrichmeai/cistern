package com.enrichmeai.cistern.webflux.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The at-rest form of a service credential (T4.0). */
class HashedCredentialTest {

    /** {@code printf '%s' 'legal-secret-0f3c8b' | shasum -a 256} — computed outside the JVM. */
    private static final String LEGAL_SECRET = "legal-secret-0f3c8b";
    private static final String LEGAL_HASH =
            "sha256:af9f6ca9c55937463513e4cb25829d6eaa89ca74ed5699c0690f13469da4c481";

    @Test
    @DisplayName("hash() agrees with shasum -a 256, so an operator can produce the value from a shell")
    void hashAgreesWithShasum() {
        assertEquals(LEGAL_HASH, HashedCredential.hash(HashAlgorithm.SHA_256, LEGAL_SECRET).encoded());
    }

    @Test
    @DisplayName("parse() and encoded() are inverses")
    void parseRoundTrips() {
        assertEquals(LEGAL_HASH, HashedCredential.parse(LEGAL_HASH).encoded());
        assertEquals(HashedCredential.parse(LEGAL_HASH), HashedCredential.hash(HashAlgorithm.SHA_256, LEGAL_SECRET));
    }

    @Test
    @DisplayName("matches the secret it was made from and nothing else")
    void matches() {
        HashedCredential credential = HashedCredential.parse(LEGAL_HASH);
        assertTrue(credential.matches(LEGAL_SECRET));
        assertFalse(credential.matches(LEGAL_SECRET + "x"));
        assertFalse(credential.matches(""));
        assertFalse(credential.matches(LEGAL_HASH), "the hash itself is not the credential");
    }

    @Test
    @DisplayName("no separator: malformed")
    void malformedWithoutSeparator() {
        assertThrows(IllegalArgumentException.class, () -> HashedCredential.parse("af9f6c"));
    }

    @Test
    @DisplayName("an algorithm this server does not implement is refused, not guessed")
    void unknownAlgorithm() {
        assertThrows(IllegalArgumentException.class,
                () -> HashedCredential.parse("md5:af9f6ca9c55937463513e4cb25829d6e"));
    }

    @Test
    @DisplayName("a digest of the wrong length is refused — the usual copy-paste error")
    void wrongLength() {
        assertThrows(IllegalArgumentException.class, () -> HashedCredential.parse("sha256:af9f6c"));
    }

    @Test
    @DisplayName("non-hex digest is refused")
    void nonHex() {
        assertThrows(IllegalArgumentException.class, () -> HashedCredential.parse(
                "sha256:zz9f6ca9c55937463513e4cb25829d6eaa89ca74ed5699c0690f13469da4c481"));
    }

    @Test
    @DisplayName("toString is the encoded digest, never a secret")
    void toStringIsEncoded() {
        assertEquals(LEGAL_HASH, HashedCredential.parse(LEGAL_HASH).toString());
        assertNotEquals(LEGAL_SECRET, HashedCredential.parse(LEGAL_HASH).toString());
    }
}
