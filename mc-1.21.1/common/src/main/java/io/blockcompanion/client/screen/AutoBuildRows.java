package io.blockcompanion.client.screen;

import io.blockcompanion.core.autobuild.AutoBuildOptions;
import io.blockcompanion.core.sync.Features;
import net.minecraft.client.gui.components.CycleButton;

import java.util.ArrayList;
import java.util.List;

/**
 * AutoBuild's options as rows of an {@link OptionList}: speed, order, replace mode, ignore air, skip missing, radius and
 * "only build". The same rows set the defaults (Settings, AutoBuild section) and one build's options (the AutoBuild
 * options screen). Replace and clear and Ignore air go together: picking one sets the other.
 */
final class AutoBuildRows {
    /** Where a change goes: the new options, and whether only the block in hand is built. */
    interface Sink {
        void set(AutoBuildOptions options, boolean onlyHeld);
    }

    private AutoBuildRows() {
    }

    /**
     * Adds the rows. {@code server} is what the server allows (null when not on a BlockCompanion server, e.g. on the
     * title screen), for the notes on what it caps.
     */
    static void fill(OptionList l, AutoBuildOptions start, boolean startHeld, Features server, Sink sink) {
        AutoBuildOptions[] cur = {start};
        boolean[] held = {startHeld};
        boolean limits = server != null && server.autoBuildAllowed();

        l.header("Speed and order");
        List<Integer> rates = new ArrayList<>();
        for (int r : AutoBuildOptions.RATES) rates.add(r);
        l.option("Speed", "Blocks a second." + (limits ? " This server allows up to " + Math.max(1, server.autoBuildRate()) + "." : " The server may allow less."),
                Ui.cycle(rates, start.blocksPerSecond(), n -> n + " a second", v -> {
                    cur[0] = cur[0].withRate(v);
                    sink.set(cur[0], held[0]);
                }, null));
        l.option("Order", "Bottom up, top down, nearest to you first (it follows you), or all of one kind of block, then the next.",
                Ui.cycle(List.of(AutoBuildOptions.Order.values()), start.order(), o -> o.label, v -> {
                    cur[0] = cur[0].withOrder(v);
                    sink.set(cur[0], held[0]);
                }, null));

        l.header("Blocks in the way");
        String allowed = !limits ? "" : !server.autoBuildOptions() ? " This server's AutoBuild never breaks blocks."
                : server.autoBuildReplace() == AutoBuildOptions.Replace.KEEP ? " This server doesn't allow breaking blocks."
                : server.autoBuildReplace() == AutoBuildOptions.Replace.CLEAR ? "" : " This server allows up to " + server.autoBuildReplace().label + ".";
        List<CycleButton<Boolean>> air = new ArrayList<>(1);
        CycleButton<AutoBuildOptions.Replace> replace = Ui.cycle(List.of(AutoBuildOptions.Replace.values()), start.replace(), r -> r.label, v -> {
            // Replace and clear is the one that clears the schematic's air: air isn't ignored with it, and is with the others.
            boolean ignore = v != AutoBuildOptions.Replace.CLEAR;
            cur[0] = cur[0].withReplace(v).withIgnoreAir(ignore);
            if (!air.isEmpty()) air.get(0).setValue(ignore);
            sink.set(cur[0], held[0]);
        }, null);
        l.option("Replace", "Empty spots only never breaks a block. Replace solid breaks stone, dirt and the like in the way; Replace all "
                + "anything but bedrock (chests too); Replace and clear also clears where the schematic has air. What breaks goes into "
                + "your linked chests (on the ground when they are full; nothing drops in creative)." + allowed, replace);
        CycleButton<Boolean> ignoreAir = Ui.onOff(start.ignoreAir(), v -> {
            AutoBuildOptions.Replace r = cur[0].replace();
            if (!v && r != AutoBuildOptions.Replace.CLEAR) r = AutoBuildOptions.Replace.CLEAR;
            else if (v && r == AutoBuildOptions.Replace.CLEAR) r = AutoBuildOptions.Replace.ALL;
            cur[0] = cur[0].withIgnoreAir(v).withReplace(r);
            replace.setValue(r);
            sink.set(cur[0], held[0]);
        }, null);
        air.add(ignoreAir);
        l.option("Ignore air", "On: the schematic's air is never touched. Off: blocks standing where the schematic has air are cleared "
                + "(sets Replace and clear).", ignoreAir);

        l.header("What to build");
        l.option("Skip missing", "A block your linked chests have no items for is skipped instead of pausing AutoBuild.",
                Ui.onOff(start.skipMissing(), v -> {
                    cur[0] = cur[0].withSkipMissing(v);
                    sink.set(cur[0], held[0]);
                }, null));
        List<Integer> radii = new ArrayList<>();
        for (int r : AutoBuildOptions.RADII) radii.add(r);
        int maxRadius = limits && server.autoBuildOptions() ? server.autoBuildMaxRadius() : 0;
        l.option("Radius", "Only the part of the schematic within this many blocks of you; it follows you as you move."
                        + (maxRadius > 0 ? " This server builds at most " + maxRadius + " blocks around you." : ""),
                Ui.cycle(radii, start.radius(), n -> n == 0 ? "Whole schematic" : "Within " + n, v -> {
                    cur[0] = cur[0].withRadius(v);
                    sink.set(cur[0], held[0]);
                }, null));
        l.option("Only build", "Block in hand: only the blocks placed with what you hold when it starts, all of them (\"build all of these\").",
                Ui.cycle(List.of(false, true), startHeld, v -> v ? "Block in hand" : "Everything", v -> {
                    held[0] = v;
                    sink.set(cur[0], held[0]);
                }, null));
    }
}
