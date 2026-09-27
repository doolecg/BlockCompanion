package io.blockcompanion.paper;

import io.blockcompanion.core.sync.ChestAccess;
import io.blockcompanion.core.sync.SyncServer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.DoubleChestInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Linked chests through the Bukkit API: containers are read and emptied on the main thread. */
final class PaperChestAccess implements ChestAccess {

    /** The world of a dimension id ({@code minecraft:overworld}, {@code minecraft:the_nether}...), or null. */
    static World world(String dimension) {
        for (World w : Bukkit.getWorlds()) {
            try {
                if (w.getKey().toString().equals(dimension)) return w;
            } catch (NoSuchMethodError e) {
                // An old Bukkit without world keys: go by environment below.
                break;
            }
        }
        World.Environment env = switch (dimension) {
            case "minecraft:the_nether" -> World.Environment.NETHER;
            case "minecraft:the_end" -> World.Environment.THE_END;
            case "minecraft:overworld" -> World.Environment.NORMAL;
            default -> null;
        };
        if (env == null) return null;
        for (World w : Bukkit.getWorlds()) if (w.getEnvironment() == env) return w;
        return null;
    }

    /** The dimension id of a world, as the clients name it. */
    static String dimension(World w) {
        try {
            return w.getKey().toString();
        } catch (NoSuchMethodError e) {
            return switch (w.getEnvironment()) {
                case NETHER -> "minecraft:the_nether";
                case THE_END -> "minecraft:the_end";
                default -> "minecraft:overworld";
            };
        }
    }

    /**
     * A player closed a container screen: the block containers it showed (both halves of a double chest) go to
     * {@code sync}, which reads the linked ones once. The player's own inventory and ender chest are left out.
     */
    static void closed(SyncServer sync, Inventory inv) {
        InventoryType type = inv.getType();
        if (type == InventoryType.PLAYER || type == InventoryType.CRAFTING || type == InventoryType.ENDER_CHEST) return;
        if (inv instanceof DoubleChestInventory both) {
            changed(sync, both.getLeftSide().getLocation());
            changed(sync, both.getRightSide().getLocation());
        } else {
            changed(sync, inv.getLocation());
        }
    }

    private static void changed(SyncServer sync, Location at) {
        if (at == null || at.getWorld() == null) return;
        sync.chestChanged(dimension(at.getWorld()), at.getBlockX(), at.getBlockY(), at.getBlockZ());
    }

    /** The inventory at a position (both halves of a double chest), or null when there is none or it isn't loaded. */
    private static Inventory inventory(String dimension, int x, int y, int z) {
        World w = world(dimension);
        if (w == null || !w.isChunkLoaded(x >> 4, z >> 4)) return null;
        Block b = w.getBlockAt(x, y, z);
        return b.getState() instanceof Container c ? c.getInventory() : null;
    }

    private static String id(ItemStack st) {
        return st.getType().getKey().toString();
    }

    @Override
    public Map<String, Long> contents(String dimension, int x, int y, int z) {
        Inventory inv = inventory(dimension, x, y, z);
        if (inv == null) return null;
        Map<String, Long> items = new TreeMap<>();
        for (ItemStack st : inv.getContents()) {
            if (st != null && !st.getType().isAir()) items.merge(id(st), (long) st.getAmount(), Long::sum);
        }
        return items;
    }

    @Override
    public int take(UUID playerId, String dimension, int x, int y, int z, String item, int count) {
        Player player = Bukkit.getPlayer(playerId);
        Inventory inv = inventory(dimension, x, y, z);
        if (player == null || inv == null) return 0;
        int moved = 0;
        ItemStack[] contents = inv.getContents();
        for (int i = 0; i < contents.length && moved < count; i++) {
            ItemStack st = contents[i];
            if (st == null || st.getType().isAir() || !id(st).equals(item)) continue;
            int n = Math.min(count - moved, st.getAmount());
            ItemStack give = st.clone();
            give.setAmount(n);
            int left = player.getInventory().addItem(give).values().stream().mapToInt(ItemStack::getAmount).sum();
            int given = n - left;
            if (given <= 0) break;
            st.setAmount(st.getAmount() - given);
            inv.setItem(i, st.getAmount() <= 0 ? null : st);
            moved += given;
        }
        return moved;
    }

    @Override
    public int remove(String dimension, int x, int y, int z, String item, int count) {
        Inventory inv = inventory(dimension, x, y, z);
        if (inv == null) return 0;
        int removed = 0;
        ItemStack[] contents = inv.getContents();
        for (int i = 0; i < contents.length && removed < count; i++) {
            ItemStack st = contents[i];
            if (st == null || st.getType().isAir() || !id(st).equals(item)) continue;
            int n = Math.min(count - removed, st.getAmount());
            st.setAmount(st.getAmount() - n);
            inv.setItem(i, st.getAmount() <= 0 ? null : st);
            removed += n;
        }
        return removed;
    }

    /**
     * Puts a stack into the container at the position (filling matching stacks, then empty slots); returns what didn't
     * fit, or null when all of it went in. For AutoBuild's drops.
     */
    static ItemStack insert(String dimension, int x, int y, int z, ItemStack stack) {
        Inventory inv = inventory(dimension, x, y, z);
        if (inv == null) return stack;
        var left = inv.addItem(stack.clone());
        return left.isEmpty() ? null : left.values().iterator().next();
    }
}
