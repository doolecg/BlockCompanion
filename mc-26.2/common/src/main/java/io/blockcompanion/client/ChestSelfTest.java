package io.blockcompanion.client;

import io.blockcompanion.client.chests.ChestTracker;
import io.blockcompanion.core.sync.SyncClient;
import io.blockcompanion.network.ClientSync;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

/**
 * Development check, with {@code BLOCKCOMPANION_CHEST_SELFTEST=1} in a singleplayer world: puts a chest with stone next
 * to the player, links it the way the selection tool does, waits for the integrated server to report what is inside,
 * asks it for 20 stone (as easy place does when the block isn't in the inventory), logs what arrived, and cleans up.
 */
final class ChestSelfTest {
    static final boolean ENABLED = "1".equals(System.getenv("BLOCKCOMPANION_CHEST_SELFTEST"));
    private static int ticks;
    private static BlockPos chest;
    private static int stoneBefore;

    private ChestSelfTest() {
    }

    static void tick(Minecraft mc) {
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null || mc.player == null || mc.level == null) return;
        ticks++;
        var key = mc.level.dimension();
        if (ticks == 120) {
            chest = mc.player.blockPosition().offset(3, 0, 0);
            BlockPos pos = chest;
            server.execute(() -> {
                var level = server.getLevel(key);
                if (level == null) return;
                level.setBlockAndUpdate(pos, Blocks.CHEST.defaultBlockState());
                if (level.getBlockEntity(pos) instanceof ChestBlockEntity c) {
                    c.setItem(0, new ItemStack(Items.STONE, 64));
                    c.setItem(1, new ItemStack(Items.OAK_PLANKS, 10));
                }
            });
        } else if (ticks == 160) {
            SyncClient c = ClientSync.client();
            BlockCompanionClient.LOG.info("Chest self-test: server allows chests: {}", c != null && c.serverPresent() && c.features().chestBuildAllowed());
            ChestTracker.get().toggle(mc.level, chest);
        } else if (ticks == 220) {
            SyncClient c = ClientSync.client();
            BlockCompanionClient.LOG.info("Chest self-test: server reports {}; tracker totals {}", c == null ? null : c.chests(), ChestTracker.get().totals());
            stoneBefore = count(mc);
            if (c != null) c.restock("minecraft:stone", 20);
        } else if (ticks == 280) {
            BlockCompanionClient.LOG.info("Chest self-test: stone in inventory {} -> {}; tracker totals now {}", stoneBefore, count(mc),
                    ChestTracker.get().totals());
            ChestTracker.get().toggle(mc.level, chest);
            BlockPos pos = chest;
            java.util.UUID id = mc.player.getUUID();
            server.execute(() -> {
                var level = server.getLevel(key);
                if (level != null) {
                    if (level.getBlockEntity(pos) instanceof ChestBlockEntity c) c.clearContent();
                    level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
                }
                var sp = server.getPlayerList().getPlayer(id);
                if (sp == null) return;
                int left = 20;
                var inv = sp.getInventory();
                for (int i = 0; i < inv.getContainerSize() && left > 0; i++) {
                    ItemStack st = inv.getItem(i);
                    if (!st.is(Items.STONE)) continue;
                    int n = Math.min(left, st.getCount());
                    st.shrink(n);
                    left -= n;
                }
            });
            BlockCompanionClient.LOG.info("Chest self-test: done");
        }
    }

    private static int count(Minecraft mc) {
        int n = 0;
        var inv = mc.player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) if (inv.getItem(i).is(Items.STONE)) n += inv.getItem(i).getCount();
        return n;
    }
}
