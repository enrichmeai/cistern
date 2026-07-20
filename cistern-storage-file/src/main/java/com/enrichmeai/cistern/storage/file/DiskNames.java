package com.enrichmeai.cistern.storage.file;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Bijective mapping between raw URI path segments and on-disk file names.
 *
 * <p>Encoding rule (applied per UTF-8 byte of the raw segment):
 * <ul>
 *   <li>{@code A–Z a–z 0–9 - _} are kept as-is;</li>
 *   <li>{@code .} is kept as-is EXCEPT as the first byte (escaped there);</li>
 *   <li>every other byte — including {@code %} itself and all non-ASCII bytes — is
 *       written as {@code %XX} (uppercase hex).</li>
 * </ul>
 *
 * <p>Consequences, all load-bearing:
 * <ul>
 *   <li><b>Injective</b>: {@code %} is always escaped, so decoding is unambiguous and two
 *       distinct raw segments never share a disk name.</li>
 *   <li><b>No encoded name ever starts with a dot</b> — a leading {@code .} becomes
 *       {@code %2E}. Everything the store creates for itself (the {@code .meta} directory,
 *       {@code .tmp-*} in-flight files, {@code .self.*} container records) IS dot-prefixed,
 *       so client resource names can never collide with, shadow, or corrupt internal
 *       files. A client resource literally named {@code .meta} or {@code .tmp-x} is stored
 *       as {@code %2Emeta} / {@code %2Etmp-x} and round-trips as a normal resource.</li>
 *   <li><b>No path traversal</b>: {@code /} cannot appear (it is the URI segment
 *       delimiter) and {@code .}/{@code ..} encode to {@code %2E}/{@code %2E.}, so an
 *       encoded segment can never navigate outside its directory.</li>
 *   <li><b>ASCII-only output</b>: non-ASCII bytes are escaped, sidestepping filesystem
 *       Unicode normalization (e.g. APFS NFD) that would otherwise break round-trips.</li>
 * </ul>
 */
final class DiskNames {

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private DiskNames() {
    }

    /** True for names the store owns (never produced by {@link #encode}). */
    static boolean isInternal(String diskName) {
        return diskName.startsWith(".");
    }

    /** Encode a raw URI path segment to a filesystem-safe name. */
    static String encode(String rawSegment) {
        byte[] utf8 = rawSegment.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder(utf8.length + 8);
        for (int i = 0; i < utf8.length; i++) {
            int b = utf8[i] & 0xFF;
            boolean safe = (b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z')
                    || (b >= '0' && b <= '9') || b == '-' || b == '_'
                    || (b == '.' && i > 0);
            if (safe) {
                sb.append((char) b);
            } else {
                sb.append('%').append(HEX[b >>> 4]).append(HEX[b & 0xF]);
            }
        }
        return sb.toString();
    }

    /** Inverse of {@link #encode}. */
    static String decode(String diskName) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(diskName.length());
        for (int i = 0; i < diskName.length(); i++) {
            char c = diskName.charAt(i);
            if (c == '%') {
                if (i + 2 >= diskName.length()) {
                    throw new IllegalArgumentException(
                            StorageFileMessage.DISK_NAME_ESCAPE_TRUNCATED.format(diskName));
                }
                out.write(hex(diskName.charAt(i + 1)) << 4 | hex(diskName.charAt(i + 2)));
                i += 2;
            } else {
                out.write(c);
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static int hex(char c) {
        int v = Character.digit(c, 16);
        if (v < 0) {
            throw new IllegalArgumentException(StorageFileMessage.DISK_NAME_HEX_DIGIT_BAD.format(c));
        }
        return v;
    }
}
