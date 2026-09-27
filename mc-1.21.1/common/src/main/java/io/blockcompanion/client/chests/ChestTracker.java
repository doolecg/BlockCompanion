package io.blockcompanion.client.chests;

import io.blockcompanion.client.BlockCompanionClient;
import io.blockcompanion.core.chests.LinkedChests;
import io.blockcompanion.core.sync.Message;
import io.blockcompanion.core.sync.SyncClient;
import io.blockcompanion.network.ClientSync;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The player's linked chests in the current world or server. Contents come from the server when it runs
 * BlockCompanion (singleplayer included), otherwise from the last time the player opened the chest. Saved per world in
 * {@code placements/<world>/chests.json}.
 */
public final class ChestTracker {
    private static final ChestTracker INSTANCE = new ChestTracker();

    private final LinkedChests chests = new LinkedChests();
    private Path file;
    private long serverVersion = -1;
    private boolean serverAdopted;
    /** The block the player last right-clicked, to know which chest a container screen belongs to. */
    private BlockPos lastClicked;
    private String lastClickedDimension;
    private long savedVersion = -1;

    public static ChestTracker get() {
        return INSTANCE;
    }

    public LinkedChests chests() {
        return chests;
    }

    /** A new world (or none): loads its links. */
    public void open(Path file) {
        save();
        this.file = file;
        serverVersion = -1;
        serverAdopted = false;
        lastClicked = null;
        if (file == null) chests.clear();
        else chests.load(file);
        savedVersion = chests.version();
    }

    public void save() {
        if (file == null || chests.version() == savedVersion) return;
        savedVersion = chests.version();
        try {
            chests.save(file);
        } catch (IOException e) {
            BlockCompanionClient.LOG.warn("Could not save linked chests: {}", e.toString());
        }
    }

    /** A container block the tool can link: anything with an inventory (chests, barrels, shulker boxes, hoppers...). */
    public static boolean isContainer(Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof Container;
    }

    /** The position a chest is linked by: for a double chest, the same half whichever half was clicked. */
    public static BlockPos canonical(Level level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        if (s.getBlock() instanceof ChestBlock && s.hasProperty(ChestBlock.TYPE) && s.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            Direction d = ChestBlock.getConnectedDirection(s);
            BlockPos other = pos.relative(d);
            if (other.asLong() < pos.asLong()) return other;
        }
        return pos;
    }

    private static LinkedChests.Pos key(Level level, BlockPos pos) {
        return new LinkedChests.Pos(level.dimension().location().toString(), pos.getX(), pos.getY(), pos.getZ());
    }

    /** Links or unlinks the container at {@code pos}; says what happened. */
    public void toggle(Level level, BlockPos clicked) {
        if (!isContainer(level, clicked)) {
            BlockCompanionClient.actionBar("That's not a container");
            return;
        }
        BlockPos pos = canonical(level, clicked);
        LinkedChests.Pos k = key(level, pos);
        boolean linked = chests.toggle(k);
        SyncClient c = ClientSync.client();
        boolean server = c != null && c.serverPresent() && c.features().chestBuildAllowed();
        if (server) c.linkChest(k.dimension(), k.x(), k.y(), k.z(), linked);
        String name = level.getBlockState(pos).getBlock().getName().getString();
        if (linked) {
            BlockCompanionClient.actionBar(name + " linked (" + chests.size() + ")" + (server ? "" : ": open it once to count what's inside"));
        } else {
            BlockCompanionClient.actionBar(name + " unlinked");
        }
        save();
    }

    public boolean isLinked(Level level, BlockPos pos) {
        return chests.isLinked(key(level, canonical(level, pos)));
    }

    /** From the use-item hook: the block right-clicked. */
    public void clicked(Level level, BlockPos pos) {
        lastClicked = pos;
        lastClickedDimension = level.dimension().location().toString();
    }

    /** Every tick: follows the server's list and reads open container screens of linked chests. */
    public void tick(Minecraft mc) {
        if (mc.level == null || mc.player == null) return;
        followServer();
        readOpenScreen(mc);
        if (mc.level.getGameTime() % 100 == 0) save();
    }

    private void followServer() {
        SyncClient c = ClientSync.client();
        if (c == null || !c.serverPresent() || !c.features().chestBuildAllowed()) {
            serverAdopted = false;
            return;
        }
        if (c.chestsVersion() == serverVersion) return;
        serverVersion = c.chestsVersion();
        Set<LinkedChests.Pos> onServer = new HashSet<>();
        long now = System.currentTimeMillis();
        for (Message.ChestEntry e : c.chests()) {
            LinkedChests.Pos p = new LinkedChests.Pos(e.dimension(), e.x(), e.y(), e.z());
            onServer.add(p);
            chests.link(p);
            if (e.valid()) chests.setContents(p, e.items(), now, LinkedChests.Source.LIVE);
        }
        if (!serverAdopted) {
            // First list after joining: links made here without the server go to it too.
            serverAdopted = true;
            for (LinkedChests.Pos p : chests.all()) if (!onServer.contains(p)) c.linkChest(p.dimension(), p.x(), p.y(), p.z(), true);
        } else {
            for (LinkedChests.Pos p : chests.all()) if (!onServer.contains(p)) chests.unlink(p);
        }
    }

    private void readOpenScreen(Minecraft mc) {
        if (lastClicked == null || !(mc.screen instanceof AbstractContainerScreen<?> screen)) return;
        if (!mc.level.dimension().location().toString().equals(lastClickedDimension)) return;
        if (!isLinked(mc.level, lastClicked)) return;
        AbstractContainerMenu menu = screen.getMenu();
        Map<String, Long> items = new TreeMap<>();
        boolean any = false;
        for (Slot slot : menu.slots) {
            if (slot.container instanceof Inventory) continue;
            any = true;
            ItemStack st = slot.getItem();
            if (!st.isEmpty()) items.merge(BuiltInRegistries.ITEM.getKey(st.getItem()).toString(), (long) st.getCount(), Long::sum);
        }
        if (!any) return;
        chests.setContents(key(mc.level, canonical(mc.level, lastClicked)), items, System.currentTimeMillis(), LinkedChests.Source.OPENED);
    }

    /** Item totals in the linked chests (empty when counting chests is off). */
    public Map<String, Long> totals() {
        return BlockCompanionClient.config().countChests ? chests.totals() : Map.of();
    }

    /** How many of an item the linked chests hold, as last seen. */
    public long available(String item) {
        return chests.totals().getOrDefault(item, 0L);
    }
}
