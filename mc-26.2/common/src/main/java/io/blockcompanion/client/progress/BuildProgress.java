package io.blockcompanion.client.progress;

import io.blockcompanion.client.BlockCompanionClient;
import io.blockcompanion.client.StateMapper;
import io.blockcompanion.core.model.BlockPos;
import io.blockcompanion.core.placement.Placement;
import io.blockcompanion.core.progress.ProgressFile;
import io.blockcompanion.core.progress.ProgressTracker;
import io.blockcompanion.core.project.BdProject;
import io.blockcompanion.core.sync.Hashes;
import net.minecraft.world.level.Level;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Keeps the {@link ProgressTracker} of the loaded placement fed from the world: every section is scanned once its
 * chunk is loaded, rescanned when the game marks it for re-meshing (chunk loads, bulk changes), and single block
 * changes update their cell at once. Chunk columns that unload keep their last known state. Also saves that state per
 * world (so progress survives leaving) and writes the shared progress file for BlockDesigner's Resource Tracker.
 * Client thread only.
 */
public final class BuildProgress {
    /** Time per tick spent rescanning sections. */
    private static final long SCAN_BUDGET_NANOS = 2_000_000L;
    /** Save the last known state at most this often while it changes. */
    private static final long SAVE_INTERVAL_MS = 30_000L;

    private ProgressTracker tracker;
    private String hash;
    private String schematicFile;
    private String projectName;
    private Path libraryFile;
    private Path stateFile;
    private String worldName;
    private final ArrayDeque<Long> scanQueue = new ArrayDeque<>();
    private final Set<Long> queued = new HashSet<>();
    private final Set<Long> loadedColumns = new HashSet<>();
    private int pollTicks;
    private ProgressFile.Writer fileWriter;
    private boolean stateDirty;
    private long lastStateSave;
    private long lastBuildTick;
    /** While the placement is being moved: its version when last seen, and for how many ticks it stayed put. */
    private long movingVersion = -1;
    private int stillTicks;
    /** Ticks a moved placement must stay put before counting starts again at its new spot. */
    private static final int SETTLE_TICKS = 10;
    /**
     * Single block changes update their cell at once, so the section re-marks that follow them (every section around
     * the block, while light settles) only need a rescan now and then: at most once per section this often.
     */
    private static final long RESCAN_GAP_MS = 2000;
    private final java.util.Map<Long, Long> scannedAt = new java.util.HashMap<>(), deferred = new java.util.HashMap<>();

    /** The tracker for the loaded placement, or null. */
    public ProgressTracker tracker() {
        return tracker;
    }

    /** The loaded schematic's SHA-256, or null. */
    public String hash() {
        return hash;
    }

    /**
     * Makes sure the tracker belongs to {@code p} in its current pose (a new one after moving or turning it, with the
     * last known state restored when it was saved for this exact pose). Null clears.
     */
    public void sync(Placement p, Path libraryFile, Path stateFile, String worldName) {
        if (p == null) {
            close();
            return;
        }
        if (tracker != null && tracker.matches(p)) return;
        boolean sameSchematic = tracker != null && tracker.placement() == p;
        if (sameSchematic) {
            // Being moved or turned: wait until it stays put, then count again at the new spot.
            if (p.version() != movingVersion) {
                movingVersion = p.version();
                stillTicks = 0;
                return;
            }
            if (++stillTicks < SETTLE_TICKS) return;
        } else if (tracker != null) {
            saveState(true);
        }
        if (!sameSchematic) {
            flushFile();
            this.libraryFile = libraryFile;
            this.schematicFile = p.name().substring(p.name().lastIndexOf('/') + 1);
            this.hash = hashOf(libraryFile);
            this.projectName = projectNameOf(libraryFile, schematicFile);
            fileWriter = new ProgressFile.Writer(ProgressFile.defaultFolder());
        }
        this.stateFile = stateFile;
        this.worldName = worldName;
        ProgressTracker.Stats before = sameSchematic ? tracker.stats() : null;
        tracker = new ProgressTracker(p);
        restoreState();
        if (before != null) {
            // Moving the placement doesn't undo the time spent or the blocks placed.
            ProgressTracker.Stats now = tracker.stats();
            now.placed = before.placed;
            now.wrong = before.wrong;
            now.buildMillis = before.buildMillis;
        }
        scanQueue.clear();
        queued.clear();
        deferred.clear();
        scannedAt.clear();
        loadedColumns.clear();
        pollTicks = 0;
        fileWriter.changed();
    }

    /** Saves and forgets everything (placement unloaded, world left). */
    public void close() {
        if (tracker == null) return;
        saveState(true);
        flushFile();
        tracker = null;
        hash = null;
        fileWriter = null;
        scanQueue.clear();
        queued.clear();
        deferred.clear();
        scannedAt.clear();
        loadedColumns.clear();
    }

    private static String hashOf(Path file) {
        if (file == null) return "0".repeat(64);
        try {
            return Hashes.sha256(Files.readAllBytes(file));
        } catch (IOException | RuntimeException e) {
            BlockCompanionClient.LOG.warn("Could not hash {}: {}", file, e.toString());
            return "0".repeat(64);
        }
    }

    private static String projectNameOf(Path file, String fileName) {
        if (file != null && BdProject.isProject(file)) {
            try {
                String n = BdProject.readInfo(file).name();
                if (n != null && !n.isBlank()) return n;
            } catch (IOException | RuntimeException e) {
                BlockCompanionClient.LOG.warn("Could not read the project name of {}: {}", file, e.toString());
            }
        }
        return ProgressFile.baseName(fileName);
    }

    // ---- world events ----------------------------------------------------------------------------------------------

    /** True while the tracker belongs to the placement's current pose (not while it is being moved). */
    private boolean live() {
        return tracker != null && tracker.matches(tracker.placement());
    }

    /** The game marked a world section for re-meshing: rescan ours there soon. */
    public void onSectionDirty(int sx, int sy, int sz) {
        if (!live()) return;
        long k = BlockPos.pack(sx, sy, sz);
        if (!tracker.sectionLoaded(sx, sy, sz) && !loadedColumns.contains(BlockPos.pack(sx, 0, sz))) return;
        long due = scannedAt.getOrDefault(k, 0L) + RESCAN_GAP_MS;
        if (due <= System.currentTimeMillis()) queue(k);
        else if (!queued.contains(k)) deferred.merge(k, due, Math::min);
    }

    private void queue(long k) {
        if (queued.add(k)) scanQueue.add(k);
    }

    /**
     * A single block changed in the world: update its cell now. {@code own}: the player's own click made it (only those
     * count as mistakes in the summary). Returns the change, or null.
     */
    public ProgressTracker.Change onBlockChanged(int x, int y, int z, net.minecraft.world.level.block.state.BlockState state, boolean own) {
        if (!live()) return null;
        io.blockcompanion.core.model.BlockState have = StateMapper.toCore(state);
        ProgressTracker.Change c = tracker.set(x, y, z, have);
        if (c != null) {
            changed();
            if (c.after() == ProgressTracker.Status.CORRECT) tracker.stats().placed++;
            else if (own && c.after() == ProgressTracker.Status.WRONG && !halfway(tracker.placement().stateAt(x, y, z), have)) tracker.stats().wrong++;
        }
        return c;
    }

    /** Half of a wanted double slab: a step on the way, not a mistake. */
    public static boolean halfway(io.blockcompanion.core.model.BlockState want, io.blockcompanion.core.model.BlockState have) {
        return "double".equals(want.get("type")) && want.name().equals(have.name());
    }

    private void changed() {
        stateDirty = true;
        if (fileWriter != null) fileWriter.changed();
    }

    /**
     * Every client tick: follows chunk loads and unloads, rescans queued sections within the time budget, counts
     * building time, and writes the files when due. {@code here} says the player is in the placement's dimension (only
     * then is the world checked); {@code nearBox} says they are building (near the box).
     */
    public void tick(Level level, boolean here, boolean nearBox, boolean writeFile) {
        if (tracker == null) return;
        if (!live()) {
            here = false;
            nearBox = false;
        } else if (!here) {
            // Another dimension: nothing there can be checked; everything keeps its last known state.
            if (!loadedColumns.isEmpty()) {
                for (long col : loadedColumns) {
                    BlockPos c = BlockPos.unpack(col);
                    tracker.markUnloaded(c.x(), c.z());
                }
                loadedColumns.clear();
                scanQueue.clear();
                queued.clear();
                deferred.clear();
            }
            nearBox = false;
        } else if (--pollTicks <= 0) {
            pollTicks = 10;
            pollChunks(level);
        }
        long start = System.nanoTime();
        if (here && !deferred.isEmpty()) {
            long due = System.currentTimeMillis();
            deferred.entrySet().removeIf(e -> {
                if (e.getValue() > due) return false;
                queue(e.getKey());
                return true;
            });
        }
        while (here && !scanQueue.isEmpty() && System.nanoTime() - start < SCAN_BUDGET_NANOS) {
            long k = scanQueue.poll();
            queued.remove(k);
            scannedAt.put(k, System.currentTimeMillis());
            BlockPos s = BlockPos.unpack(k);
            if (!level.hasChunk(s.x(), s.z())) continue;
            net.minecraft.core.BlockPos.MutableBlockPos pos = new net.minecraft.core.BlockPos.MutableBlockPos();
            List<ProgressTracker.Change> changes = tracker.scanSection(s.x(), s.y(), s.z(),
                    (x, y, z) -> StateMapper.toCore(level.getBlockState(pos.set(x, y, z))));
            if (!changes.isEmpty()) changed();
        }
        if (nearBox) {
            tracker.stats().buildMillis += 50;
            if (++lastBuildTick % 200 == 0) stateDirty = true;
        }
        long now = System.currentTimeMillis();
        if (stateDirty && now - lastStateSave >= SAVE_INTERVAL_MS) saveState(false);
        if (writeFile && fileWriter != null) {
            fileWriter.tick(now, this::snapshot);
            if (fileWriter.lastError() != null) {
                BlockCompanionClient.LOG.warn("Could not write the progress file: {}", fileWriter.lastError().toString());
                fileWriter = new ProgressFile.Writer(ProgressFile.defaultFolder());
            }
        }
    }

    private void pollChunks(Level level) {
        for (long col : tracker.chunkColumns()) {
            BlockPos c = BlockPos.unpack(col);
            boolean loaded = level.hasChunk(c.x(), c.z());
            boolean was = loadedColumns.contains(col);
            if (loaded && !was) {
                loadedColumns.add(col);
                for (long k : tracker.sectionKeys()) {
                    BlockPos s = BlockPos.unpack(k);
                    if (s.x() == c.x() && s.z() == c.z()) queue(k);
                }
            } else if (!loaded && was) {
                loadedColumns.remove(col);
                tracker.markUnloaded(c.x(), c.z());
            }
        }
    }

    /** Sections still waiting for their first scan or a rescan. */
    public int pendingScans() {
        return scanQueue.size();
    }

    // ---- files ----------------------------------------------------------------------------------------------------

    private ProgressFile.Snapshot snapshot() {
        ProgressTracker.Totals t = tracker.totals();
        return new ProgressFile.Snapshot(schematicFile, hash, projectName, worldName == null ? "" : worldName, Instant.now(), t.total(),
                t.correct(), t.wrong(), t.missing(), tracker.items());
    }

    /** Writes the shared progress file now if anything changed (unload, quit). */
    public void flushFile() {
        if (tracker == null || fileWriter == null || !BlockCompanionClient.config().progressFile) return;
        fileWriter.flush(System.currentTimeMillis(), this::snapshot);
    }

    private void saveState(boolean force) {
        if (tracker == null || stateFile == null || (!stateDirty && !force)) return;
        stateDirty = false;
        lastStateSave = System.currentTimeMillis();
        try {
            Files.createDirectories(stateFile.getParent());
            Path tmp = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
            try (OutputStream out = Files.newOutputStream(tmp)) {
                tracker.write(out, hash);
            }
            Files.move(tmp, stateFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            BlockCompanionClient.LOG.warn("Could not save build progress: {}", e.toString());
        }
    }

    private void restoreState() {
        if (stateFile == null || !Files.isRegularFile(stateFile)) return;
        try (InputStream in = Files.newInputStream(stateFile)) {
            if (tracker.read(in, hash)) {
                BlockCompanionClient.LOG.info("Restored build progress: {} of {} blocks", tracker.totals().correct(), tracker.totals().total());
            }
        } catch (IOException | RuntimeException e) {
            BlockCompanionClient.LOG.warn("Could not restore build progress: {}", e.toString());
        }
    }

}
