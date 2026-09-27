package io.blockcompanion.core.sync;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/** SHA-256 helpers. A file is identified everywhere by the lowercase hex of its SHA-256 (64 characters). */
public final class Hashes {
    public static final int BYTES = 32;
    private static final HexFormat HEX = HexFormat.of();

    private Hashes() {
    }

    public static String sha256(byte[] data) {
        return toHex(digest().digest(data));
    }

    public static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing from this Java runtime", e);
        }
    }

    public static String toHex(byte[] raw) {
        return HEX.formatHex(raw);
    }

    public static byte[] fromHex(String hex) {
        if (!isHash(hex)) throw new IllegalArgumentException("Not a SHA-256 hex string: " + hex);
        return HEX.parseHex(hex);
    }

    /** True for exactly 64 lowercase hex digits. */
    public static boolean isHash(String s) {
        if (s == null || s.length() != BYTES * 2) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
        }
        return true;
    }

    /** The first eight hex digits, for display and file-name suffixes. */
    public static String shortHash(String hex) {
        return hex.substring(0, 8).toLowerCase(Locale.ROOT);
    }
}
