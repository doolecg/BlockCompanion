package io.blockcompanion.client;

import io.blockcompanion.client.progress.BuildProgress;
import io.blockcompanion.client.render.GhostRenderer;
import io.blockcompanion.core.link.GameLink;
import io.blockcompanion.core.placement.Layers;
import io.blockcompanion.core.placement.Placement;
import io.blockcompanion.core.placement.PlacementLock;
import io.blockcompanion.core.placement.SavedPlacement;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * One schematic loaded in the world: where it is and how it's turned ({@link Placement}), its layer view, what is locked,
 * whether it shows, whether it follows BlockDesigner, and its own ghost meshes and build progress. Several can be loaded
 * at once; each is saved in its own slot per world.
 */
public final class LoadedPlacement {
    /** Its slot in the world's saved placements. */
    public final int slot;
    public Placement placement;
    public final String dimension;
    public final Layers layers = new Layers();
    public final Set<PlacementLock> locks = EnumSet.noneOf(PlacementLock.class);
    public boolean visible = true;
    /** Reloads when BlockDesigner sends a new version of its project. */
    public boolean live;
    final BuildProgress progress = new BuildProgress();
    final GhostRenderer ghosts = new GhostRenderer();
    /** The material helper's marks for this placement. */
    List<io.blockcompanion.core.model.BlockPos> helperCells = List.of();
    boolean dirty;

    LoadedPlacement(int slot, Placement placement, String dimension) {
        this.slot = slot;
        this.placement = placement;
        this.dimension = dimension;
        this.live = placement.name().startsWith(GameLink.FOLDER + "/");
    }

    public String name() {
        return placement.name();
    }

    /** The file name without folders or extension, for lists and the HUD. */
    public String shortName() {
        String n = placement.name();
        n = n.substring(n.lastIndexOf('/') + 1);
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    public boolean locked(PlacementLock l) {
        return locks.contains(l);
    }

    public boolean fromBlockDesigner() {
        return placement.name().startsWith(GameLink.FOLDER + "/");
    }

    public BuildProgress progress() {
        return progress;
    }

    public GhostRenderer ghosts() {
        return ghosts;
    }

    SavedPlacement saved() {
        return SavedPlacement.of(placement, dimension, layers, locks, visible, live);
    }
}
