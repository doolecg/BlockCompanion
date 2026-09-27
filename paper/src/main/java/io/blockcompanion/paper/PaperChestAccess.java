package io.blockcompanion.paper;

import io.blockcompanion.core.sync.ChestAccess;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Linked chests through the Bukkit API: containers are read and emptied on the main thread. */
final class PaperChestAccess implements ChestAccess {

    /** The world of a dimension id ({@code minecraft:overworld}, {@code minecraft:the_nether}...), or null. */
    private static World world(String dimension) {
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
}
