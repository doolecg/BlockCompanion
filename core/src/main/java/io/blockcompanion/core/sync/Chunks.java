package io.blockcompanion.core.sync;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.function.BiFunction;

/** Splitting files into transfer chunks, putting them back together, and paging long lists. */
public final class Chunks {
    private Chunks() {
    }

    /** How many chunks a file of {@code size} bytes takes. */
    public static int count(long size, int chunkSize) {
        if (size <= 0) return 0;
        long n = (size + chunkSize - 1) / chunkSize;
        if (n > Integer.MAX_VALUE) throw new IllegalArgumentException("File too large");
        return (int) n;
    }

    /** Chunk {@code index} of {@code data}. */
    public static byte[] slice(byte[] data, int index, int chunkSize) {
        int from = Math.multiplyExact(index, chunkSize);
        if (index < 0 || from >= data.length) throw new IndexOutOfBoundsException("Chunk " + index);
        return Arrays.copyOfRange(data, from, Math.min(data.length, from + chunkSize));
    }

    /**
     * Collects the chunks of one file, in any order and with repeats. When every chunk is in, {@link #finish()} checks the
     * SHA-256 against the expected one.
     */
    public static final class Assembler {
        public enum Result {
            /** Stored (or a repeat, ignored); more are needed. */
            OK,
            /** That was the last missing chunk: call {@link #finish()}. */
            COMPLETE,
            /** Index out of range or wrong length: ignored. */
            BAD
        }

        private final String expectedHash;
        private final int size, chunkSize, count;
        private final byte[] buffer;
        private final BitSet have;
        private long lastActivity;

        public Assembler(String expectedHash, long size, int chunkSize) {
            if (size <= 0 || size > Integer.MAX_VALUE - 8) throw new IllegalArgumentException("Bad file size " + size);
            if (chunkSize <= 0) throw new IllegalArgumentException("Bad chunk size " + chunkSize);
            this.expectedHash = expectedHash;
            this.size = (int) size;
            this.chunkSize = chunkSize;
            this.count = Chunks.count(size, chunkSize);
            this.buffer = new byte[this.size];
            this.have = new BitSet(count);
        }

        public Result accept(int index, byte[] data) {
            if (index < 0 || index >= count) return Result.BAD;
            int from = index * chunkSize;
            int expectedLength = Math.min(chunkSize, size - from);
            if (data.length != expectedLength) return Result.BAD;
            if (!have.get(index)) {
                System.arraycopy(data, 0, buffer, from, data.length);
                have.set(index);
            }
            return isComplete() ? Result.COMPLETE : Result.OK;
        }

        public boolean isComplete() {
            return have.cardinality() == count;
        }

        /** The file if its hash matches, else null. Only meaningful once {@link #isComplete()}. */
        public byte[] finish() {
            if (!isComplete()) return null;
            return Hashes.sha256(buffer).equals(expectedHash) ? buffer : null;
        }

        /** Forgets every chunk (after a hash mismatch). */
        public void reset() {
            have.clear();
        }

        /** Which chunks are in, for resuming. */
        public BitSet have() {
            return (BitSet) have.clone();
        }

        public int received() {
            return have.cardinality();
        }

        public int count() {
            return count;
        }

        public long size() {
            return size;
        }

        public int chunkSize() {
            return chunkSize;
        }

        public String expectedHash() {
            return expectedHash;
        }

        public long lastActivity() {
            return lastActivity;
        }

        public void touch(long now) {
            lastActivity = now;
        }
    }

    /**
     * Splits {@code items} into as few list messages as possible, each encoding to at most {@link Protocol#MAX_MESSAGE}
     * bytes. The first page has {@code reset} set; an empty list still yields one (empty, resetting) page.
     *
     * @param make builds a list message from ({@code reset}, items)
     */
    public static <T> List<Message> pages(List<T> items, BiFunction<Boolean, List<T>, Message> make) {
        List<Message> out = new ArrayList<>();
        List<T> page = new ArrayList<>();
        for (T item : items) {
            page.add(item);
            if (page.size() > 1 && Protocol.encode(make.apply(out.isEmpty(), page)).length > Protocol.MAX_MESSAGE) {
                page.remove(page.size() - 1);
                out.add(make.apply(out.isEmpty(), List.copyOf(page)));
                page.clear();
                page.add(item);
            }
        }
        out.add(make.apply(out.isEmpty(), List.copyOf(page)));
        return out;
    }
}
