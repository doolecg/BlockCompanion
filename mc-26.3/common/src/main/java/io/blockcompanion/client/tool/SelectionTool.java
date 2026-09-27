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
 * The selection tool (a stick by default, {@code tool.item}): the corner modifier (Alt by default) and left-click a block
 * for corner 1, and right-click for corner 2, like WorldEdit's wand; the link modifier (Ctrl) and right-click a chest to
 * link or unlink it; the clear modifier (Shift) and right-click clears the selection, aimed at a block or not. When
 * several are held on a right-click, the corner wins, then the chest link, then clear. Any other right-click is the
 * game's own; a left-click on a block never mines with the tool, and without the corner modifier it does nothing.
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

    /** Whether the corner modifier (Alt by default) is held; never when it is set to none. */
    private static boolean cornerHeld(Minecraft mc) {
        return BlockCompanionClient.held(mc, BlockCompanionClient.config().cornerModifier);
    }

    /**
     * Left click. True when the tool took it: corner 1 set with the corner modifier held, or swallowed without it so the
     * tool never mines. Either way nothing is sent to the server.
     */
    public static boolean onAttack(Minecraft mc) {
        if (!holding(mc.player) || mc.level == null) return false;
        BlockHitResult hit = aimed(mc);
        if (hit == null) return false;
        if (cornerHeld(mc)) set(mc, 1, hit.getBlockPos());
        return true;
    }

    /** Held left button: never mine with the tool while aiming at a block. */
    public static boolean blocksMining(Minecraft mc) {
        return holding(mc.player) && aimed(mc) != null;
    }

    /** Whether the link modifier (Ctrl by default) is held while aiming at a container. */
    private static boolean linking(Minecraft mc, BlockHitResult hit) {
        return hit != null && mc.level != null && BlockCompanionClient.held(mc, BlockCompanionClient.config().linkModifier)
                && ChestTracker.isContainer(mc.level, hit.getBlockPos());
    }

    /**
     * Right click with the clear modifier held (Shift by default, {@code tool.clear.modifier}): clears both corners,
     * unless the corner modifier or a chest link takes the click ({@link #onUse}). True when the tool took it, so the
     * click is never sent as a block use (no chest opens, no door toggles).
     */
    public static boolean onClear(Minecraft mc) {
        if (!holding(mc.player) || !BlockCompanionClient.held(mc, BlockCompanionClient.config().clearModifier)) return false;
        BlockHitResult hit = aimed(mc);
        if (hit != null && (cornerHeld(mc) || linking(mc, hit))) return false;
        Selection sel = BlockCompanionClient.selection();
        BlockCompanionClient.actionBar(sel.isEmpty() ? "No selection to clear" : "Selection cleared");
        sel.clear();
        return true;
    }

    /**
     * Right click (after {@link #onClear}). True when the tool took it: corner 2 set with the corner modifier held, or a
     * chest linked or unlinked with the link modifier. Any other right-click is left to the game.
     */
    public static boolean onUse(Minecraft mc) {
        if (!holding(mc.player) || mc.level == null) return false;
        BlockHitResult hit = aimed(mc);
        if (hit == null) return false;
        if (cornerHeld(mc)) {
            set(mc, 2, hit.getBlockPos());
            return true;
        }
        if (linking(mc, hit)) {
            ChestTracker.get().toggle(mc.level, hit.getBlockPos());
            return true;
        }
        return false;
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
            String mod = BlockCompanionClient.config().cornerModifier.label();
            msg += corner == 1 ? ": " + mod + "+right-click the opposite corner" : ": " + mod + "+left-click the opposite corner";
        }
        BlockCompanionClient.actionBar(msg);
    }
}
