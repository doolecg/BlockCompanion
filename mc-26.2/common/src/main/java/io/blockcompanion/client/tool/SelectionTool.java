package io.blockcompanion.client.tool;

import io.blockcompanion.client.BlockCompanionClient;
import io.blockcompanion.client.chests.ChestTracker;
import io.blockcompanion.core.model.BlockPos;
import io.blockcompanion.core.model.Box;
import io.blockcompanion.core.placement.Selection;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * The selection tool (a stick by default, {@code tool.item}): left-click a block for corner 1, right-click for corner 2,
 * like WorldEdit's wand; sneak and right-click a chest to link or unlink it. While it is held and aimed at a block, the
 * clicks don't break or use anything.
 */
public final class SelectionTool {
    private SelectionTool() {
    }

    public static boolean holding(Player player) {
        String id = BlockCompanionClient.config().toolItem;
        if (player == null || id == null || id.isBlank()) return false;
        ItemStack main = player.getMainHandItem();
        return !main.isEmpty() && BuiltInRegistries.ITEM.getKey(main.getItem()).toString().equals(id);
    }

    private static BlockHitResult aimed(Minecraft mc) {
        if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) return hit;
        return null;
    }

    /** Left click. True when the tool took it (corner 1 set). */
    public static boolean onAttack(Minecraft mc) {
        if (!holding(mc.player) || mc.level == null) return false;
        BlockHitResult hit = aimed(mc);
        if (hit == null) return false;
        set(mc, 1, hit.getBlockPos());
        return true;
    }

    /** Held left button: never mine with the tool while aiming at a block. */
    public static boolean blocksMining(Minecraft mc) {
        return holding(mc.player) && aimed(mc) != null;
    }

    /** Right click. True when the tool took it (corner 2 set, or a chest linked). */
    public static boolean onUse(Minecraft mc) {
        if (!holding(mc.player) || mc.level == null) return false;
        BlockHitResult hit = aimed(mc);
        if (hit == null) return false;
        if (mc.player.isShiftKeyDown() && ChestTracker.isContainer(mc.level, hit.getBlockPos())) {
            ChestTracker.get().toggle(mc.level, hit.getBlockPos());
            return true;
        }
        set(mc, 2, hit.getBlockPos());
        return true;
    }

    private static void set(Minecraft mc, int corner, net.minecraft.core.BlockPos p) {
        Selection sel = BlockCompanionClient.selection();
        BlockPos pos = new BlockPos(p.getX(), p.getY(), p.getZ());
        sel.set(corner, pos, mc.level.dimension().identifier().toString());
        String msg = "Corner " + corner + " at " + pos.x() + ", " + pos.y() + ", " + pos.z();
        if (sel.isComplete()) {
            Box b = sel.box().orElseThrow();
            msg += String.format(java.util.Locale.ROOT, ": %d × %d × %d (%,d blocks), %s saves it", b.sizeX(), b.sizeY(), b.sizeZ(),
                    (long) b.sizeX() * b.sizeY() * b.sizeZ(), BlockCompanionClient.keyName("save"));
        } else {
            msg += corner == 1 ? ": right-click the opposite corner" : ": left-click the opposite corner";
        }
        BlockCompanionClient.actionBar(msg);
    }
}
