package com.enrichmeai.cistern.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The SHA-256 of a file's bytes, as lower-case hex — what the state file remembers about a
 * document so that a re-run can tell an unchanged file from a changed one without sending it.
 * A hash rather than a modification time, because a copy, a checkout or a restore rewrites
 * every mtime and changes no content, and the whole point is to send nothing then.
 *
 * @param hex sixty-four lower-case hex digits
 */
record ContentHash(String hex) {

    static final String ALGORITHM = "SHA-256";
    private static final Pattern SHAPE = Pattern.compile("[0-9a-f]{64}");

    ContentHash {
        Objects.requireNonNull(hex, "hex");
        if (!SHAPE.matcher(hex).matches()) {
            throw new IllegalArgumentException(CliMessage.INVALID_CONTENT_HASH.format(hex));
        }
    }

    /** The hash of {@code data}. */
    static ContentHash of(byte[] data) {
        return new ContentHash(HexFormat.of().formatHex(digest().digest(data)));
    }

    /** The hash of {@code file}'s content, streamed rather than loaded. */
    static ContentHash of(Path file) throws IOException {
        MessageDigest digest = digest();
        try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
            in.transferTo(OutputStream.nullOutputStream());
        }
        return new ContentHash(HexFormat.of().formatHex(digest.digest()));
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance(ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(CliMessage.DIGEST_UNAVAILABLE.format(ALGORITHM), e);
        }
    }

    @Override
    public String toString() {
        return hex;
    }
}
