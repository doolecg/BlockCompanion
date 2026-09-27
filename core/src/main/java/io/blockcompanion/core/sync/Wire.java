package io.blockcompanion.core.sync;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Byte-level building blocks of the protocol: varints, length-prefixed UTF-8 strings, UUIDs, SHA-256 hashes and byte
 * arrays. Everything is big-endian, like Minecraft's own packets. {@link Out} grows as needed; {@link In} reads a fixed
 * array and throws {@link IOException} on anything malformed (truncated data, over-long strings, bad hashes), so a
 * handler can drop a bad message without trusting it.
 */
public final class Wire {
    /** Longest string the protocol carries (names, dimension ids, messages), in UTF-8 bytes. */
    public static final int MAX_STRING_BYTES = 1024;

    private Wire() {
    }

    /** A growing output buffer. */
    public static final class Out {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream(64);

        public Out varInt(int value) {
            while ((value & ~0x7F) != 0) {
                bytes.write((value & 0x7F) | 0x80);
                value >>>= 7;
            }
            bytes.write(value);
            return this;
        }

        public Out varLong(long value) {
            while ((value & ~0x7FL) != 0) {
                bytes.write((int) (value & 0x7F) | 0x80);
                value >>>= 7;
            }
            bytes.write((int) value);
            return this;
        }

        public Out bool(boolean b) {
            bytes.write(b ? 1 : 0);
            return this;
        }

        public Out i32(int v) {
            bytes.write(v >>> 24);
            bytes.write(v >>> 16);
            bytes.write(v >>> 8);
            bytes.write(v);
            return this;
        }

        public Out i64(long v) {
            i32((int) (v >>> 32));
            return i32((int) v);
        }

        public Out string(String s) {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            if (b.length > MAX_STRING_BYTES) throw new IllegalArgumentException("String too long for the protocol: " + b.length + " bytes");
            varInt(b.length);
            bytes.write(b, 0, b.length);
            return this;
        }

        public Out uuid(UUID id) {
            return i64(id.getMostSignificantBits()).i64(id.getLeastSignificantBits());
        }

        /** A UUID that may be null: a presence flag, then the UUID. */
        public Out optUuid(UUID id) {
            bool(id != null);
            return id == null ? this : uuid(id);
        }

        /** A SHA-256 hash given as 64 hex digits, written as its 32 raw bytes. */
        public Out hash(String hex) {
            byte[] raw = Hashes.fromHex(hex);
            bytes.write(raw, 0, raw.length);
            return this;
        }

        public Out bytes(byte[] b) {
            varInt(b.length);
            bytes.write(b, 0, b.length);
            return this;
        }

        public int size() {
            return bytes.size();
        }

        public byte[] toByteArray() {
            return bytes.toByteArray();
        }
    }

    /** Reads a byte array written by {@link Out}. */
    public static final class In {
        private final byte[] data;
        private int pos;

        public In(byte[] data) {
            this.data = data;
        }

        private int u8() throws IOException {
            if (pos >= data.length) throw new IOException("Message ends early");
            return data[pos++] & 0xFF;
        }

        public int varInt() throws IOException {
            int value = 0, shift = 0, b;
            do {
                if (shift >= 35) throw new IOException("VarInt too long");
                b = u8();
                value |= (b & 0x7F) << shift;
                shift += 7;
            } while ((b & 0x80) != 0);
            return value;
        }

        public long varLong() throws IOException {
            long value = 0;
            int shift = 0, b;
            do {
                if (shift >= 70) throw new IOException("VarLong too long");
                b = u8();
                value |= (long) (b & 0x7F) << shift;
                shift += 7;
            } while ((b & 0x80) != 0);
            return value;
        }

        public boolean bool() throws IOException {
            int b = u8();
            if (b > 1) throw new IOException("Bad boolean " + b);
            return b == 1;
        }

        public int i32() throws IOException {
            return (u8() << 24) | (u8() << 16) | (u8() << 8) | u8();
        }

        public long i64() throws IOException {
            return ((long) i32() << 32) | (i32() & 0xFFFFFFFFL);
        }

        public String string() throws IOException {
            int len = varInt();
            if (len < 0 || len > MAX_STRING_BYTES) throw new IOException("Bad string length " + len);
            need(len);
            String s = new String(data, pos, len, StandardCharsets.UTF_8);
            pos += len;
            return s;
        }

        public UUID uuid() throws IOException {
            return new UUID(i64(), i64());
        }

        public UUID optUuid() throws IOException {
            return bool() ? uuid() : null;
        }

        public String hash() throws IOException {
            need(Hashes.BYTES);
            byte[] raw = new byte[Hashes.BYTES];
            System.arraycopy(data, pos, raw, 0, raw.length);
            pos += raw.length;
            return Hashes.toHex(raw);
        }

        /** A length-prefixed byte array of at most {@code max} bytes. */
        public byte[] bytes(int max) throws IOException {
            int len = varInt();
            if (len < 0 || len > max) throw new IOException("Bad byte array length " + len);
            need(len);
            byte[] b = new byte[len];
            System.arraycopy(data, pos, b, 0, len);
            pos += len;
            return b;
        }

        /** A list length, refused when it could not possibly fit in what is left of the message. */
        public int count() throws IOException {
            int n = varInt();
            if (n < 0 || n > data.length - pos) throw new IOException("Bad list length " + n);
            return n;
        }

        private void need(int n) throws IOException {
            if (data.length - pos < n) throw new IOException("Message ends early");
        }

        public int remaining() {
            return data.length - pos;
        }
    }
}
