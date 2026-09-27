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
import net.minecraft.world.inventory.InventoryMenu;
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
 * BlockCompanion (singleplayer included): it reads a chest when it is linked and when a player closes it, and subtracts
 * what AutoBuild and restocks take. Without it, a chest's contents are what its screen showed when the player last
 * closed it. Nothing scans the world for chests. Saved per world in {@code placements/<world>/chests.json}.
 */
public final class ChestTracker {
    private static final ChestTracker INSTANCE = new ChestTracker();
    /** How long after a right-click a container screen can still be the clicked chest's. */
    private static final int CLICK_TO_SCREEN_TICKS = 40;

    private final LinkedChests chests = new LinkedChests();
    private Path file;
    private long serverVersion = -1;
    private boolean serverAdopted;
    /** The block the player last right-clicked, to know which chest a container screen belongs to. */
    private BlockPos lastClicked;
    private String lastClickedDimension;
    private long lastClickedTime;
    /** The last container screen seen, and the linked chest it shows (null when it shows none): read when it closes. */
    private AbstractContainerScreen<?> openScreen;
    private LinkedChests.Pos openChest;
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
        openScreen = null;
        openChest = null;
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
        return new LinkedChests.Pos(level.dimension().identifier().toString(), pos.getX(), pos.getY(), pos.getZ());
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
        lastClickedDimension = level.dimension().identifier().toString();
        lastClickedTime = level.getGameTime();
    }

    /** Every tick: follows the server's list and notices linked chests' screens opening and closing. */
    public void tick(Minecraft mc) {
        if (mc.level == null || mc.player == null) return;
        followServer();
        watchScreen(mc);
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

    /** True when the server keeps the chests' contents (then it reads them when they close, not this client). */
    private static boolean serverKeepsContents() {
        SyncClient c = ClientSync.client();
        return c != null && c.serverPresent() && c.features().chestBuildAllowed();
    }

    /**
     * Notices a container screen opening (which linked chest it shows is worked out once) and closing: then, without a
     * server that keeps the contents, what the screen last showed becomes the chest's contents until it is opened again.
     */
    private void watchScreen(Minecraft mc) {
        AbstractContainerScreen<?> now = mc.gui.screen() instanceof AbstractContainerScreen<?> s ? s : null;
        if (now == openScreen) return;
        if (openScreen != null && openChest != null && !serverKeepsContents()) read(openScreen.getMenu(), openChest);
        openScreen = now;
        openChest = null;
        if (now == null || lastClicked == null || now.getMenu() instanceof InventoryMenu) return;
        BlockPos clicked = lastClicked;
        lastClicked = null;
        // A screen long after the click (a command, another mod) isn't that chest's.
        if (mc.level.getGameTime() - lastClickedTime > CLICK_TO_SCREEN_TICKS) return;
        if (!mc.level.dimension().identifier().toString().equals(lastClickedDimension)) return;
        LinkedChests.Pos k = key(mc.level, canonical(mc.level, clicked));
        if (chests.isLinked(k)) openChest = k;
    }

    /** The container slots of a closed chest's screen (the player's own slots left out) as the chest's contents. */
    private void read(AbstractContainerMenu menu, LinkedChests.Pos pos) {
        Map<String, Long> items = new TreeMap<>();
        boolean any = false;
        for (Slot slot : menu.slots) {
            if (slot.container instanceof Inventory) continue;
            any = true;
            ItemStack st = slot.getItem();
            if (!st.isEmpty()) items.merge(BuiltInRegistries.ITEM.getKey(st.getItem()).toString(), (long) st.getCount(), Long::sum);
        }
        if (!any) return;
        chests.setContents(pos, items, System.currentTimeMillis(), LinkedChests.Source.OPENED);
        save();
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
