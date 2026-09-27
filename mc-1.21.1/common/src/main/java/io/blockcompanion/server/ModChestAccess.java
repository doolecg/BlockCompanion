package io.blockcompanion.server;

import io.blockcompanion.core.chests.LinkedChests;
import io.blockcompanion.core.sync.ChestAccess;
import io.blockcompanion.core.sync.SyncServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.HopperBlockEntity;

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Linked chests on a Fabric or NeoForge server: reads containers and moves items from them into a player's inventory,
 * and notices players closing a container screen ({@link #tick}) so the sync server reads a linked chest then, and
 * only then.
 */
final class ModChestAccess implements ChestAccess {
    private final MinecraftServer server;
    /** Each player's open container screen (anything but their own inventory) and the level it was opened in. */
    private final Map<UUID, Open> open = new HashMap<>();

    private record Open(AbstractContainerMenu menu, Level level) {
    }

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

    /**
     * Every server tick: compares each player's open menu with last tick's. When a container screen went away (closed,
     * or replaced by another), the linked chests it showed are passed to {@code sync} to be read once. Nothing is read
     * while a screen stays open or when none closed.
     */
    void tick(SyncServer sync) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            AbstractContainerMenu menu = p.containerMenu;
            Open was = open.get(p.getUUID());
            if (was != null && was.menu() == menu) continue;
            if (was == null && menu == p.inventoryMenu) continue;
            if (was != null) closed(sync, was);
            if (menu == p.inventoryMenu) open.remove(p.getUUID());
            else open.put(p.getUUID(), new Open(menu, p.level()));
        }
    }

    /** The player left: a container screen they had open closed with them. */
    void left(SyncServer sync, UUID player) {
        Open was = open.remove(player);
        if (was != null) closed(sync, was);
    }

    /** A container screen closed: tells {@code sync} about every block container it showed (it ignores unlinked ones). */
    private static void closed(SyncServer sync, Open o) {
        Set<Container> shown = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Slot slot : o.menu().slots) if (!(slot.container instanceof Inventory)) shown.add(slot.container);
        if (shown.isEmpty()) return;
        String dimension = o.level().dimension().location().toString();
        Set<LinkedChests.Pos> linked = null;
        for (Container c : shown) {
            if (c instanceof BlockEntity be) {
                BlockPos p = be.getBlockPos();
                sync.chestChanged(dimension, p.getX(), p.getY(), p.getZ());
            } else if (c instanceof CompoundContainer both) {
                // A double chest: find which linked position is one of its halves.
                if (linked == null) linked = sync.linkedChests();
                for (LinkedChests.Pos p : linked) {
                    if (!p.dimension().equals(dimension)) continue;
                    BlockPos pos = new BlockPos(p.x(), p.y(), p.z());
                    if (o.level().isLoaded(pos) && o.level().getBlockEntity(pos) instanceof Container half && both.contains(half)) {
                        sync.chestChanged(dimension, p.x(), p.y(), p.z());
                    }
                }
            }
        }
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

    @Override
    public int remove(String dimension, int x, int y, int z, String item, int count) {
        Container c = container(dimension, x, y, z);
        if (c == null) return 0;
        int removed = 0;
        for (int i = 0; i < c.getContainerSize() && removed < count; i++) {
            ItemStack st = c.getItem(i);
            if (st.isEmpty() || !BuiltInRegistries.ITEM.getKey(st.getItem()).toString().equals(item)) continue;
            int n = Math.min(count - removed, st.getCount());
            st.shrink(n);
            c.setItem(i, st);
            removed += n;
        }
        if (removed > 0) c.setChanged();
        return removed;
    }

    /**
     * Puts a stack into the container at the position as a hopper would (filling matching stacks, then empty slots);
     * returns what didn't fit (all of it when there is no container there or it isn't loaded). For AutoBuild's drops.
     */
    ItemStack insert(String dimension, int x, int y, int z, ItemStack stack) {
        Container c = container(dimension, x, y, z);
        if (c == null || stack.isEmpty()) return stack;
        ItemStack left = HopperBlockEntity.addItem(null, c, stack.copy(), null);
        if (left.getCount() != stack.getCount()) c.setChanged();
        return left;
    }
}
