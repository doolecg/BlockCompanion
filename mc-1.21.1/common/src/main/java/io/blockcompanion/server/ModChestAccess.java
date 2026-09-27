package io.blockcompanion.server;

import io.blockcompanion.core.sync.ChestAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Linked chests on a Fabric or NeoForge server: reads containers and moves items from them into a player's inventory. */
final class ModChestAccess implements ChestAccess {
    private final MinecraftServer server;

    ModChestAccess(MinecraftServer server) {
        this.server = server;
    }

    /** The container at a position (both halves of a double chest), or null when there is none or it isn't loaded. */
    private Container container(String dimension, int x, int y, int z) {
        ResourceLocation id = ResourceLocation.tryParse(dimension);
        if (id == null) return null;
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
        BlockPos pos = new BlockPos(x, y, z);
        if (level == null || !level.isLoaded(pos)) return null;
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock chest) {
            Container both = ChestBlock.getContainer(chest, state, level, pos, true);
            if (both != null) return both;
        }
        return level.getBlockEntity(pos) instanceof Container c ? c : null;
    }

    @Override
    public Map<String, Long> contents(String dimension, int x, int y, int z) {
        Container c = container(dimension, x, y, z);
        if (c == null) return null;
        Map<String, Long> items = new TreeMap<>();
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack st = c.getItem(i);
            if (!st.isEmpty()) items.merge(BuiltInRegistries.ITEM.getKey(st.getItem()).toString(), (long) st.getCount(), Long::sum);
        }
        return items;
    }

    @Override
    public int take(UUID playerId, String dimension, int x, int y, int z, String item, int count) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        Container c = container(dimension, x, y, z);
        if (player == null || c == null) return 0;
        int moved = 0;
        for (int i = 0; i < c.getContainerSize() && moved < count; i++) {
            ItemStack st = c.getItem(i);
            if (st.isEmpty() || !BuiltInRegistries.ITEM.getKey(st.getItem()).toString().equals(item)) continue;
            int n = Math.min(count - moved, st.getCount());
            ItemStack give = st.copyWithCount(n);
            player.getInventory().add(give);
            int given = n - give.getCount();
            if (given <= 0) break;
            st.shrink(given);
            c.setItem(i, st);
            moved += given;
        }
        if (moved > 0) {
            c.setChanged();
            player.containerMenu.broadcastChanges();
        }
        return moved;
    }
}
