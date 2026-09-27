package io.blockcompanion.core.placement;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Every placement loaded in one world or server, each in its own slot: {@code placements/<world>/<slot>.properties}
 * (the {@link SavedPlacement}) and {@code <slot>.progress} (its last known build state). Slots are small numbers that
 * stay with a placement while it is loaded. The single file of earlier versions, {@code placements/<world>.properties}
 * with {@code <world>.progress}, becomes slot 1 the first time the world is opened.
 */
public final class SavedPlacements {
    private final Path folder;

    /** One saved slot. */
    public record Slot(int slot, SavedPlacement placement) {
    }

    /**
     * @param placementsDir the {@code placements} folder
     * @param worldKey      the world's key ({@link SavedPlacement#safeKey})
     */
    public SavedPlacements(Path placementsDir, String worldKey) {
        this.folder = placementsDir.resolve(worldKey);
        migrate(placementsDir.resolve(worldKey + ".properties"), placementsDir.resolve(worldKey + ".progress"));
    }

    public Path folder() {
        return folder;
    }

    private void migrate(Path oldProps, Path oldProgress) {
        if (!Files.isRegularFile(oldProps)) return;
        try {
            Files.createDirectories(folder);
            if (!Files.exists(propertiesFile(1))) {
                Files.move(oldProps, propertiesFile(1), StandardCopyOption.REPLACE_EXISTING);
                if (Files.isRegularFile(oldProgress)) Files.move(oldProgress, progressFile(1), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            // Left where it was; tried again next time.
        }
    }

    public Path propertiesFile(int slot) {
        return folder.resolve(slot + ".properties");
    }

    /** The slot's last known build state (see the progress tracker). */
    public Path progressFile(int slot) {
        return folder.resolve(slot + ".progress");
    }

    /** Every readable slot, lowest first. */
    public List<Slot> list() {
        List<Slot> out = new ArrayList<>();
        if (!Files.isDirectory(folder)) return out;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(folder, "*.properties")) {
            for (Path f : files) {
                String n = f.getFileName().toString();
                int slot;
                try {
                    slot = Integer.parseInt(n.substring(0, n.length() - ".properties".length()));
                } catch (NumberFormatException e) {
                    continue;
                }
                Optional<SavedPlacement> sp = SavedPlacement.read(f);
                sp.ifPresent(p -> out.add(new Slot(slot, p)));
            }
        } catch (IOException e) {
            return out;
        }
        out.sort((a, b) -> Integer.compare(a.slot(), b.slot()));
        return out;
    }

    /** The lowest slot number not in {@code used}. */
    public static int freeSlot(java.util.Collection<Integer> used) {
        int s = 1;
        while (used.contains(s)) s++;
        return s;
    }

    public void write(int slot, SavedPlacement p) throws IOException {
        p.write(propertiesFile(slot));
    }

    /** Forgets a slot (its placement was unloaded). */
    public void delete(int slot) throws IOException {
        Files.deleteIfExists(propertiesFile(slot));
        Files.deleteIfExists(progressFile(slot));
    }
}
