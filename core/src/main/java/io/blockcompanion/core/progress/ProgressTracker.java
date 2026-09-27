package io.blockcompanion.core.progress;

import io.blockcompanion.core.compare.Compare;
import io.blockcompanion.core.items.Items;
import io.blockcompanion.core.model.BlockPos;
import io.blockcompanion.core.model.BlockState;
import io.blockcompanion.core.model.Box;
import io.blockcompanion.core.placement.Placement;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.IntPredicate;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * How far a placed schematic is built: every schematic block's last known state (missing, correct, wrong, or not seen
 * yet), with totals per level and per item kept up to date incrementally as single blocks change or world sections are
 * rescanned. Nothing is ever rescanned as a whole each tick.
 *
 * <p>Only loaded chunks can be checked. When a chunk unloads its cells keep their last known state (and count as
 * "last seen"), so progress doesn't drop when the player walks away. Cells never seen count as missing.
 *
 * <p>The tracker is bound to one pose of the placement (origin, rotation, mirror): when the placement moves, make a new
 * one. Main thread only.
 */
public final class ProgressTracker {
    /** A schematic cell's state as last seen. */
    public enum Status {
        /** Schematic air with nothing (or only clutter) in the world, or schematic air never seen. */
        NONE,
        /** A schematic block in a section that was never loaded: counts as missing. */
        UNKNOWN,
        MISSING,
        CORRECT,
        WRONG,
        /** Schematic air with a block in the way. */
        EXTRA;

        private static final Status[] VALUES = values();

        static Status of(int code) {
            return VALUES[code];
        }
    }

    /** Where world states come from when a section is scanned: the block at a world position, in core terms. */
    @FunctionalInterface
    public interface CellSource {
        BlockState get(int x, int y, int z);
    }

    /** Block counts: total schematic blocks, how many are correct or wrong, the rest missing; plus extra blocks. */
    public record Totals(long total, long correct, long wrong, long extra) {
        public long missing() {
            return total - correct - wrong;
        }

        /** Blocks still to place or fix. */
        public long left() {
            return total - correct;
        }

        /** 0 to 1. */
        public double fraction() {
            return total == 0 ? 1 : (double) correct / total;
        }

        public boolean complete() {
            return total > 0 && correct == total;
        }
    }

    /** One item: how many the blocks need, and how many are represented by correctly placed blocks. */
    public record ItemProgress(long needed, long placed) {
        public long left() {
            return Math.max(0, needed - placed);
        }
    }

    /**
     * A cell whose status changed. {@code levelCompleted} is set when this change made its whole level correct,
     * {@code allCompleted} when it finished the schematic.
     */
    public record Change(int x, int y, int z, Status before, Status after, int level, boolean levelCompleted, boolean allCompleted) {
    }

    /** Running totals of what the player did while the tracker was live (not rescans). */
    public static final class Stats {
        /** Blocks that became correct through a live change. */
        public long placed;
        /** Blocks that became wrong through a live change. */
        public long wrong;
        /** Time spent building, as the client measures it. */
        public long buildMillis;
        /** Set once the finish was celebrated, so it isn't again after a reload. */
        public boolean finished;

        /** Correct placements out of all placements, 0 to 1 (1 when nothing was placed). */
        public double accuracy() {
            long all = placed + wrong;
            return all == 0 ? 1 : (double) placed / all;
        }
    }

    private static final int NONE = 0, UNKNOWN = 1, MISSING = 2, CORRECT = 3, WRONG = 4, EXTRA = 5;

    private static final class Sec {
        final byte[] st = new byte[4096];
        /** Schematic blocks in this section. */
        int blocks;
        boolean loaded, seen;
    }

    private final Placement placement;
    private final Box box;
    private final BlockPos origin;
    private final int rotation;
    private final boolean mirrored;
    private final int height;
    private final Map<Long, Sec> sections = new HashMap<>();
    private final long[] total, correct, wrong, extra;
    private long allTotal, allCorrect, allWrong, allExtra;
    private final Map<String, long[]> neededByLevel = new TreeMap<>();
    private final Map<String, long[]> placedByLevel = new HashMap<>();
    private final Map<BlockState, Map<String, Integer>> itemCache = new IdentityHashMap<>();
    private final Stats stats = new Stats();
    private long version;

    public ProgressTracker(Placement placement) {
        this.placement = placement;
        this.box = placement.worldBox();
        this.origin = placement.origin();
        this.rotation = placement.rotation();
        this.mirrored = placement.mirrored();
        this.height = box.sizeY();
        total = new long[height];
        correct = new long[height];
        wrong = new long[height];
        extra = new long[height];
        for (int sx = box.minX() >> 4; sx <= box.maxX() >> 4; sx++)
            for (int sy = box.minY() >> 4; sy <= box.maxY() >> 4; sy++)
                for (int sz = box.minZ() >> 4; sz <= box.maxZ() >> 4; sz++) sections.put(BlockPos.pack(sx, sy, sz), new Sec());
        placement.structure().forEachBlock((x, y, z, s) -> {
            BlockPos w = placement.toWorld(x, y, z);
            Sec sec = sections.get(key(w.x(), w.y(), w.z()));
            sec.st[index(w.x(), w.y(), w.z())] = UNKNOWN;
            sec.blocks++;
            int level = w.y() - box.minY();
            total[level]++;
            allTotal++;
            items(s).forEach((item, n) -> neededByLevel.computeIfAbsent(item, k -> new long[height])[level] += n);
        });
    }

    /** True while the placement still has the pose this tracker was made for. */
    public boolean matches(Placement p) {
        return p == placement && p.origin().equals(origin) && p.rotation() == rotation && p.mirrored() == mirrored;
    }

    public Placement placement() {
        return placement;
    }

    public Box box() {
        return box;
    }

    /** Number of levels (the schematic's height). */
    public int height() {
        return height;
    }

    /** Bumps on every counted change. */
    public long version() {
        return version;
    }

    public Stats stats() {
        return stats;
    }

    private static long key(int x, int y, int z) {
        return BlockPos.pack(x >> 4, y >> 4, z >> 4);
    }

    private static int index(int x, int y, int z) {
        return ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
    }

    private Map<String, Integer> items(BlockState s) {
        return itemCache.computeIfAbsent(s, Items::forBlock);
    }

    // ---- queries ---------------------------------------------------------------------------------------------------

    public Status status(int x, int y, int z) {
        if (!box.contains(x, y, z)) return Status.NONE;
        Sec sec = sections.get(key(x, y, z));
        return Status.of(sec.st[index(x, y, z)]);
    }

    /** The whole schematic. */
    public Totals totals() {
        return new Totals(allTotal, allCorrect, allWrong, allExtra);
    }

    /** Levels {@code minLevel..maxLevel} (0 = the bottom of the schematic), clamped. */
    public Totals totals(int minLevel, int maxLevel) {
        int a = Math.max(0, minLevel), b = Math.min(height - 1, maxLevel);
        long t = 0, c = 0, w = 0, e = 0;
        for (int i = a; i <= b; i++) {
            t += total[i];
            c += correct[i];
            w += wrong[i];
            e += extra[i];
        }
        return new Totals(t, c, w, e);
    }

    /** True if the level has blocks and every one of them is correct. */
    public boolean levelComplete(int level) {
        return level >= 0 && level < height && total[level] > 0 && correct[level] == total[level];
    }

    /** Per item, the whole schematic, sorted by item id. */
    public Map<String, ItemProgress> items() {
        return items(0, height - 1);
    }

    /** Per item for levels {@code minLevel..maxLevel}, sorted by item id; items with nothing needed there are left out. */
    public Map<String, ItemProgress> items(int minLevel, int maxLevel) {
        int a = Math.max(0, minLevel), b = Math.min(height - 1, maxLevel);
        Map<String, ItemProgress> out = new TreeMap<>();
        neededByLevel.forEach((item, need) -> {
            long[] got = placedByLevel.get(item);
            long n = 0, p = 0;
            for (int i = a; i <= b; i++) {
                n += need[i];
                if (got != null) p += got[i];
            }
            if (n > 0) out.put(item, new ItemProgress(n, p));
        });
        return out;
    }

    /** One item over the whole schematic (zero when it isn't needed). */
    public ItemProgress item(String item) {
        return item(item, 0, height - 1);
    }

    public ItemProgress item(String item, int minLevel, int maxLevel) {
        long[] need = neededByLevel.get(item);
        if (need == null) return new ItemProgress(0, 0);
        long[] got = placedByLevel.get(item);
        int a = Math.max(0, minLevel), b = Math.min(height - 1, maxLevel);
        long n = 0, p = 0;
        for (int i = a; i <= b; i++) {
            n += need[i];
            if (got != null) p += got[i];
        }
        return new ItemProgress(n, p);
    }

    /** Sections with schematic blocks whose chunk isn't loaded now but was seen earlier: their counts are "last seen". */
    public int lastSeenSections() {
        int n = 0;
        for (Sec s : sections.values()) if (s.blocks > 0 && s.seen && !s.loaded) n++;
        return n;
    }

    /** Sections with schematic blocks that were never seen: their blocks count as missing. */
    public int unseenSections() {
        int n = 0;
        for (Sec s : sections.values()) if (s.blocks > 0 && !s.seen) n++;
        return n;
    }

    /** True if the section (section coordinates) is part of the box and its chunk is currently marked loaded. */
    public boolean sectionLoaded(int sx, int sy, int sz) {
        Sec s = sections.get(BlockPos.pack(sx, sy, sz));
        return s != null && s.loaded;
    }

    /** The packed keys of every section the box touches. */
    public List<Long> sectionKeys() {
        return new ArrayList<>(sections.keySet());
    }

    /**
     * Up to {@code limit} cells that still need {@code item} (known missing, in a visible level), nearest to
     * {@code (px, py, pz)} first, within {@code radius} blocks.
     */
    public List<BlockPos> nearestMissing(String item, double px, double py, double pz, double radius, int limit, IntPredicate levelVisible) {
        if (limit <= 0 || !neededByLevel.containsKey(item)) return List.of();
        record Sd(long key, double dist) {
        }
        List<Sd> order = new ArrayList<>();
        for (var e : sections.entrySet()) {
            if (e.getValue().blocks == 0) continue;
            BlockPos s = BlockPos.unpack(e.getKey());
            double d = boxDistance(px, py, pz, s.x() << 4, s.y() << 4, s.z() << 4);
            if (d <= radius) order.add(new Sd(e.getKey(), d));
        }
        order.sort((a, b) -> Double.compare(a.dist, b.dist));
        record Found(BlockPos pos, double dist) {
        }
        List<Found> found = new ArrayList<>();
        double r2 = radius * radius;
        for (Sd sd : order) {
            if (found.size() >= limit && sd.dist > found.get(limit - 1).dist) break;
            Sec sec = sections.get(sd.key);
            BlockPos s = BlockPos.unpack(sd.key);
            int ox = s.x() << 4, oy = s.y() << 4, oz = s.z() << 4;
            for (int i = 0; i < 4096; i++) {
                if (sec.st[i] != MISSING) continue;
                int x = ox + (i & 15), y = oy + (i >> 8), z = oz + ((i >> 4) & 15);
                if (!levelVisible.test(y - box.minY())) continue;
                double dx = x + 0.5 - px, dy = y + 0.5 - py, dz = z + 0.5 - pz;
                double d2 = dx * dx + dy * dy + dz * dz;
                if (d2 > r2) continue;
                if (!items(placement.stateAt(x, y, z)).containsKey(item)) continue;
                found.add(new Found(new BlockPos(x, y, z), Math.sqrt(d2)));
            }
            found.sort((a, b) -> Double.compare(a.dist, b.dist));
            if (found.size() > limit) found.subList(limit, found.size()).clear();
        }
        List<BlockPos> out = new ArrayList<>(found.size());
        for (Found f : found) out.add(f.pos);
        return out;
    }

    private static double boxDistance(double px, double py, double pz, int x0, int y0, int z0) {
        double dx = Math.max(0, Math.max(x0 - px, px - (x0 + 16)));
        double dy = Math.max(0, Math.max(y0 - py, py - (y0 + 16)));
        double dz = Math.max(0, Math.max(z0 - pz, pz - (z0 + 16)));
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    // ---- updates --------------------------------------------------------------------------------------------------

    /**
     * The world block at a position is now {@code actual}: updates that cell and the totals. Returns the change, or
     * null if the position is outside the box or its status stayed the same.
     */
    public Change set(int x, int y, int z, BlockState actual) {
        if (!box.contains(x, y, z)) return null;
        Sec sec = sections.get(key(x, y, z));
        return apply(sec, x, y, z, actual);
    }

    private Change apply(Sec sec, int x, int y, int z, BlockState actual) {
        int i = index(x, y, z);
        int before = sec.st[i];
        BlockState want = null;
        int after;
        if (before == NONE || before == EXTRA) {
            // Schematic air (the structure knows): only extra or not.
            after = Compare.isAirLike(actual) ? NONE : EXTRA;
        } else {
            want = placement.stateAt(x, y, z);
            after = switch (Compare.classify(want, actual)) {
                case CORRECT -> CORRECT;
                case WRONG -> WRONG;
                case EXTRA, EMPTY, MISSING -> MISSING;
            };
        }
        if (after == before) return null;
        int level = y - box.minY();
        uncount(before, level, want == null ? null : want);
        sec.st[i] = (byte) after;
        count(after, level, want);
        version++;
        boolean levelDone = after == CORRECT && correct[level] == total[level];
        boolean allDone = after == CORRECT && allCorrect == allTotal;
        return new Change(x, y, z, Status.of(before), Status.of(after), level, levelDone, allDone);
    }

    private void uncount(int st, int level, BlockState want) {
        switch (st) {
            case CORRECT -> {
                correct[level]--;
                allCorrect--;
                items(want).forEach((item, n) -> placedByLevel.computeIfAbsent(item, k -> new long[height])[level] -= n);
            }
            case WRONG -> {
                wrong[level]--;
                allWrong--;
            }
            case EXTRA -> {
                extra[level]--;
                allExtra--;
            }
            default -> {
            }
        }
    }

    private void count(int st, int level, BlockState want) {
        switch (st) {
            case CORRECT -> {
                correct[level]++;
                allCorrect++;
                items(want).forEach((item, n) -> placedByLevel.computeIfAbsent(item, k -> new long[height])[level] += n);
            }
            case WRONG -> {
                wrong[level]++;
                allWrong++;
            }
            case EXTRA -> {
                extra[level]++;
                allExtra++;
            }
            default -> {
            }
        }
    }

    /**
     * Checks every cell of one world section (section coordinates) against the world and marks it loaded. Never-seen
     * cells that are still empty become missing. Returns the changes (rescans are quiet: callers usually ignore them
     * for effects).
     */
    public List<Change> scanSection(int sx, int sy, int sz, CellSource world) {
        Sec sec = sections.get(BlockPos.pack(sx, sy, sz));
        if (sec == null) return List.of();
        sec.loaded = true;
        sec.seen = true;
        int x0 = Math.max(sx << 4, box.minX()), x1 = Math.min((sx << 4) + 15, box.maxX());
        int y0 = Math.max(sy << 4, box.minY()), y1 = Math.min((sy << 4) + 15, box.maxY());
        int z0 = Math.max(sz << 4, box.minZ()), z1 = Math.min((sz << 4) + 15, box.maxZ());
        List<Change> changes = null;
        for (int y = y0; y <= y1; y++) {
            for (int z = z0; z <= z1; z++) {
                for (int x = x0; x <= x1; x++) {
                    int i = index(x, y, z);
                    BlockState actual = world.get(x, y, z);
                    if (sec.st[i] == UNKNOWN) {
                        // First sight: count it as missing first so the change below is a real transition.
                        sec.st[i] = MISSING;
                    }
                    Change c = apply(sec, x, y, z, actual);
                    if (c != null) {
                        if (changes == null) changes = new ArrayList<>();
                        changes.add(c);
                    }
                }
            }
        }
        return changes == null ? List.of() : changes;
    }

    /** A chunk column (chunk coordinates) unloaded: its sections keep their last known state. */
    public void markUnloaded(int chunkX, int chunkZ) {
        for (var e : sections.entrySet()) {
            BlockPos s = BlockPos.unpack(e.getKey());
            if (s.x() == chunkX && s.z() == chunkZ) e.getValue().loaded = false;
        }
    }

    /** The chunk columns (packed as x, 0, z) that the box touches. */
    public List<Long> chunkColumns() {
        List<Long> out = new ArrayList<>();
        for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++)
            for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++) out.add(BlockPos.pack(cx, 0, cz));
        return Collections.unmodifiableList(out);
    }

    /** True if any section in the chunk column is marked loaded. */
    public boolean columnLoaded(int chunkX, int chunkZ) {
        for (int sy = box.minY() >> 4; sy <= box.maxY() >> 4; sy++) {
            Sec s = sections.get(BlockPos.pack(chunkX, sy, chunkZ));
            if (s != null && s.loaded) return true;
        }
        return false;
    }

    // ---- saving the last known state --------------------------------------------------------------------------------

    private static final int MAGIC = 0x42435047; // "BCPG"
    private static final int FORMAT = 1;

    /**
     * Writes the last known state (every seen section) and the stats, gzipped, so progress survives leaving the world.
     * {@code id} names what was placed (e.g. the schematic's hash); {@link #read} only accepts a matching id and pose.
     */
    public void write(OutputStream out, String id) throws IOException {
        GZIPOutputStream gz = new GZIPOutputStream(out);
        DataOutputStream d = new DataOutputStream(gz);
        d.writeInt(MAGIC);
        d.writeInt(FORMAT);
        d.writeUTF(id);
        d.writeInt(origin.x());
        d.writeInt(origin.y());
        d.writeInt(origin.z());
        d.writeByte(rotation);
        d.writeBoolean(mirrored);
        d.writeLong(allTotal);
        d.writeLong(stats.placed);
        d.writeLong(stats.wrong);
        d.writeLong(stats.buildMillis);
        d.writeBoolean(stats.finished);
        List<Map.Entry<Long, Sec>> seen = new ArrayList<>();
        for (var e : sections.entrySet()) if (e.getValue().seen) seen.add(e);
        d.writeInt(seen.size());
        for (var e : seen) {
            d.writeLong(e.getKey());
            d.write(e.getValue().st);
        }
        d.flush();
        gz.finish();
    }

    /**
     * Restores a state written by {@link #write} into this (fresh) tracker, if it was written for the same id and pose.
     * Restored sections count as seen but not loaded ("last seen") until they are scanned again. Returns false (and
     * changes nothing) when the data doesn't fit.
     */
    public boolean read(InputStream in, String id) throws IOException {
        DataInputStream d = new DataInputStream(new GZIPInputStream(in));
        if (d.readInt() != MAGIC || d.readInt() != FORMAT) return false;
        if (!d.readUTF().equals(id)) return false;
        BlockPos o = new BlockPos(d.readInt(), d.readInt(), d.readInt());
        int rot = d.readByte();
        boolean mir = d.readBoolean();
        if (!o.equals(origin) || rot != rotation || mir != mirrored || d.readLong() != allTotal) return false;
        Stats s = new Stats();
        s.placed = d.readLong();
        s.wrong = d.readLong();
        s.buildMillis = d.readLong();
        s.finished = d.readBoolean();
        int n = d.readInt();
        Map<Long, byte[]> data = new HashMap<>();
        for (int i = 0; i < n; i++) {
            long k = d.readLong();
            byte[] st = new byte[4096];
            d.readFully(st);
            if (!sections.containsKey(k)) return false;
            data.put(k, st);
        }
        // Validate before touching anything: a cell must agree with the schematic on whether it holds a block.
        for (var e : data.entrySet()) {
            byte[] mine = sections.get(e.getKey()).st, theirs = e.getValue();
            for (int i = 0; i < 4096; i++) {
                boolean block = mine[i] != NONE && mine[i] != EXTRA;
                int t = theirs[i];
                if (t < NONE || t > EXTRA) return false;
                boolean tBlock = t != NONE && t != EXTRA;
                if (block != tBlock) return false;
            }
        }
        for (var e : data.entrySet()) {
            Sec sec = sections.get(e.getKey());
            BlockPos sp = BlockPos.unpack(e.getKey());
            byte[] theirs = e.getValue();
            for (int i = 0; i < 4096; i++) {
                int t = theirs[i];
                if (t == sec.st[i]) continue;
                int x = (sp.x() << 4) + (i & 15), y = (sp.y() << 4) + (i >> 8), z = (sp.z() << 4) + ((i >> 4) & 15);
                int level = y - box.minY();
                BlockState want = t == CORRECT || sec.st[i] == CORRECT ? placement.stateAt(x, y, z) : null;
                uncount(sec.st[i], level, want);
                sec.st[i] = (byte) t;
                count(t, level, want);
            }
            sec.seen = true;
            sec.loaded = false;
        }
        stats.placed = s.placed;
        stats.wrong = s.wrong;
        stats.buildMillis = s.buildMillis;
        stats.finished = s.finished;
        version++;
        return true;
    }
}
