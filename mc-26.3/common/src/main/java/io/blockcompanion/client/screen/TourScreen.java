package io.blockcompanion.client.screen;

import com.mojang.blaze3d.platform.InputConstants;
import io.blockcompanion.client.BlockCompanionClient;
import io.blockcompanion.client.LoadedPlacement;
import io.blockcompanion.core.model.BlockState;
import io.blockcompanion.core.model.Structure;
import io.blockcompanion.core.placement.Layers;
import io.blockcompanion.core.placement.Placement;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * The guide's tour in the world: a small demo schematic (a hut) is loaded on the ground just in front of the player,
 * only on this client, and each step moves the player (when they can fly) and turns their view to what it explains:
 * ghosts (its walls), red wrong blocks (its stone brick path over the ground), orange blocks in the way (the ground
 * inside, where it has air), the box, and layers. The world stays running behind a panel at the bottom with Back, Skip tour and
 * Next. However it ends, the demo is unloaded and the player put back where they stood.
 */
public final class TourScreen extends Screen {
    /** A step: its text and, in the demo's own coordinates (x right, z away from the player), where to look from and at. */
    private record Step(String title, String text, Vec3 eye, Vec3 look) {
    }

    private static final int W = 9, D = 7;
    private static final Vec3 CENTRE = new Vec3(4, 1, 3);
    private static final List<Step> STEPS = List.of(
            new Step("A schematic", "This small hut is a demo schematic, loaded just for this tour. Nothing in your world changes.",
                    new Vec3(4, 5, -6), CENTRE),
            new Step("Ghosts", "Ghosts are the blocks still to place: the game's own models, a little see-through and gently pulsing. "
                    + "Place the real block and its ghost goes.", new Vec3(1.5, 2.8, -3), new Vec3(4, 1.5, 1)),
            new Step("{wrong:Red}: a different block", "The hut wants a stone brick path where your ground is: a {wrong:red} "
                    + "tint and outline mark a block that should be something else, or face another way. The hint by the crosshair "
                    + "says what it should be.", new Vec3(1, 3.5, -3), new Vec3(2, 0, 0)),
            new Step("{extra:Orange}: in the way", "Inside, the hut has air, so the ground there is marked {extra:orange}: blocks to clear.",
                    new Vec3(4, 6.5, 0), new Vec3(4, 0, 3)),
            new Step("The box", "The box shows where the schematic sits; it shows while you hold the {tool}. Look at it with the "
                    + "{tool}: {move}+scroll moves it, {rotate}+scroll turns it, {mirror} mirrors it and {lock} locks it.",
                    new Vec3(10.5, 5, -5), CENTRE),
            new Step("Layers", "{layer_up} and {layer_down} step through the layers, like BlockDesigner's slice view: here only the "
                    + "bottom two show. {view} changes how much of a schematic you see.", new Vec3(4, 5, -6), CENTRE),
            new Step("Your turn", "Press {library} to load your own schematics. This tour and the guide are in the settings, under "
                    + "Guide.", null, null));
    private static final int MOVE_TICKS = 30;

    private int step;
    private int moveTick;
    private Vec3 fromPos;
    private float fromYaw, fromPitch;
    private List<FormattedCharSequence> lines = List.of();
    private int panelX, panelY, panelW, panelH;

    // Set up once, undone in removed().
    private boolean started, finished;
    private LoadedPlacement demo;
    private Vec3 homePos;
    private float homeYaw, homePitch;
    private boolean wasFlying, savedBoxesAlways;
    /** The demo's near left cell on the ground, and its right and forward directions. */
    private BlockPos corner;
    private Direction right, forward;

    public TourScreen() {
        super(Component.literal("BlockCompanion tour"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        if (!started) start();
        panelW = Math.min(360, width - 2 * Ui.PAD);
        int textW = panelW - 4 * Ui.PAD;
        lines = font.split(GuideScreen.styled(STEPS.get(step).text()), textW);
        panelH = 2 * Ui.PAD + 16 + lines.size() * 10 + Ui.PAD + Ui.BUTTON_H + Ui.PAD;
        panelX = (width - panelW) / 2;
        panelY = height - panelH - Ui.PAD;

        int by = panelY + panelH - Ui.PAD - Ui.BUTTON_H, bw = (panelW - 4 * Ui.PAD - 2 * Ui.GAP) / 3, bx = panelX + 2 * Ui.PAD;
        boolean last = step == STEPS.size() - 1;
        Button back = addRenderableWidget(Ui.button("Back", null, bx, by, bw, b -> go(step - 1)));
        back.active = step > 0;
        addRenderableWidget(Ui.button(last ? "Close" : "Skip tour", null, bx + bw + Ui.GAP, by, bw, b -> onClose()));
        addRenderableWidget(Button.builder(Component.literal(last ? "Done" : "Next").withColor(Ui.ACCENT), b -> {
            if (last) onClose();
            else go(step + 1);
        }).bounds(bx + 2 * (bw + Ui.GAP), by, bw, Ui.BUTTON_H).build());
    }

    /** Goes to the next step; false on the last one. */
    public boolean next() {
        if (step == STEPS.size() - 1) return false;
        go(step + 1);
        return true;
    }

    private void go(int to) {
        to = Math.max(0, Math.min(STEPS.size() - 1, to));
        if (to == step) return;
        step = to;
        beginMove();
        if (demo != null) {
            demo.layers.set(STEPS.get(step).title().equals("Layers") ? 1 : -1, Layers.Mode.BUILD_UP);
            BlockCompanionClient.changed(demo, false);
        }
        rebuildWidgets();
    }

    // ---- the demo and the camera -------------------------------------------------------------------------------------

    private void start() {
        started = true;
        LocalPlayer p = minecraft.player;
        if (p == null || minecraft.level == null) return;
        homePos = p.position();
        homeYaw = p.getYRot();
        homePitch = p.getXRot();
        wasFlying = p.getAbilities().flying;
        savedBoxesAlways = BlockCompanionClient.config().boxesAlways;
        BlockCompanionClient.config().boxesAlways = true;

        forward = p.getDirection();
        right = forward.getClockWise();
        BlockPos feet = p.blockPosition();
        BlockPos near = feet.relative(forward, 2).relative(right, -(W / 2));
        corner = near.atY(ground(near.relative(right, W / 2).relative(forward, D / 2), feet.getY()));

        Structure s = new Structure();
        BlockState floor = BlockState.of("minecraft:stone_bricks"), wall = BlockState.of("minecraft:oak_planks");
        List<int[]> cells = new ArrayList<>();
        // A stone brick path all round, plank walls one block in (a doorway in the near one), and air inside.
        for (int x = 0; x < W; x++)
            for (int z = 0; z < D; z++) {
                boolean inside = x >= 2 && x <= W - 3 && z >= 2 && z <= D - 3;
                if (!inside) cells.add(new int[]{x, 0, z, 0});
                boolean inWall = x >= 1 && x <= W - 2 && z >= 1 && z <= D - 2 && (x == 1 || x == W - 2 || z == 1 || z == D - 2);
                if (inWall && !(z == 1 && x == W / 2)) for (int y = 1; y <= 2; y++) cells.add(new int[]{x, y, z, 1});
            }
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        for (int[] c : cells) {
            BlockPos w = cell(c[0], c[1], c[2]);
            minX = Math.min(minX, w.getX());
            minZ = Math.min(minZ, w.getZ());
        }
        for (int[] c : cells) {
            BlockPos w = cell(c[0], c[1], c[2]);
            s.set(w.getX() - minX, w.getY() - corner.getY(), w.getZ() - minZ, c[3] == 0 ? floor : wall);
        }
        Placement pl = new Placement("BlockCompanion tour", s, new io.blockcompanion.core.model.BlockPos(minX, corner.getY(), minZ));
        demo = BlockCompanionClient.load(pl);
        beginMove();
    }

    /** The top solid block's height in this column, from a little above the player down; the player's feet if none. */
    private int ground(BlockPos column, int feetY) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int y = feetY + 2; y >= feetY - 24; y--) {
            m.set(column.getX(), y, column.getZ());
            if (minecraft.level.getBlockState(m).isFaceSturdy(minecraft.level, m, Direction.UP)) return y;
        }
        return feetY - 1;
    }

    private BlockPos cell(int x, int y, int z) {
        return corner.relative(right, x).relative(forward, z).above(y);
    }

    /** A point in demo coordinates (cell centres at whole numbers) in the world. */
    private Vec3 point(Vec3 local) {
        Vec3 c = Vec3.atCenterOf(corner);
        Vec3 r = new Vec3(right.getStepX(), 0, right.getStepZ()), f = new Vec3(forward.getStepX(), 0, forward.getStepZ());
        return c.add(r.scale(local.x)).add(f.scale(local.z)).add(0, local.y, 0);
    }

    private void beginMove() {
        LocalPlayer p = minecraft.player;
        if (p == null) return;
        fromPos = p.position();
        fromYaw = p.getYRot();
        fromPitch = p.getXRot();
        moveTick = 0;
    }

    @Override
    public void tick() {
        super.tick();
        LocalPlayer p = minecraft.player;
        if (p == null || demo == null || fromPos == null || moveTick > MOVE_TICKS) return;
        moveTick++;
        double t = moveTick / (double) MOVE_TICKS;
        t = t * t * (3 - 2 * t);
        Step s = STEPS.get(step);
        Vec3 toPos, look;
        float toYaw, toPitch;
        // The last step goes back to where the player stood.
        boolean canFly = p.getAbilities().mayfly;
        if (s.eye() == null) {
            toPos = homePos;
            toYaw = homeYaw;
            toPitch = homePitch;
        } else {
            Vec3 eye = point(s.eye());
            toPos = canFly ? eye.subtract(0, p.getEyeHeight(), 0) : fromPos;
            look = point(s.look());
            Vec3 d = look.subtract(canFly ? eye : p.getEyePosition());
            toYaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
            toPitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
        }
        if (canFly) {
            if (!p.getAbilities().flying) {
                p.getAbilities().flying = true;
                p.onUpdateAbilities();
            }
            p.setPos(fromPos.lerp(toPos, t));
            p.setDeltaMovement(Vec3.ZERO);
        }
        p.setYRot(fromYaw + (float) (Mth.wrapDegrees(toYaw - fromYaw) * t));
        p.setXRot(fromPitch + (float) ((toPitch - fromPitch) * t));
        p.setYHeadRot(p.getYRot());
    }

    /** Takes the demo away and puts the player back, however the tour ends. */
    @Override
    public void removed() {
        super.removed();
        if (finished || !started) return;
        finished = true;
        BlockCompanionClient.config().boxesAlways = savedBoxesAlways;
        if (demo != null) BlockCompanionClient.unload(demo, true);
        LocalPlayer p = minecraft.player;
        if (p != null && homePos != null) {
            if (p.getAbilities().mayfly) p.setPos(homePos);
            p.setYRot(homeYaw);
            p.setXRot(homePitch);
            p.setDeltaMovement(Vec3.ZERO);
            if (p.getAbilities().flying != wasFlying) {
                p.getAbilities().flying = wasFlying;
                p.onUpdateAbilities();
            }
        }
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(null);
    }

    // ---- drawing ------------------------------------------------------------------------------------------------------

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == InputConstants.KEY_RIGHT) {
            go(step + 1);
            return true;
        }
        if (event.key() == InputConstants.KEY_LEFT) {
            go(step - 1);
            return true;
        }
        return super.keyPressed(event);
    }

    /** No blur or dimming: the world behind is the point. */
    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        Ui.panel(g, panelX, panelY, panelW, panelH);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        int x = panelX + 2 * Ui.PAD, y = panelY + 2 * Ui.PAD;
        String count = (step + 1) + " of " + STEPS.size();
        Ui.shadowed(g, font, GuideScreen.styled(STEPS.get(step).title()).copy().withColor(Ui.ACCENT), x, y, Ui.ACCENT);
        Ui.rightText(g, font, "Tour · " + count, panelX + panelW - 2 * Ui.PAD, y, Ui.MUTED);
        y += 16;
        for (FormattedCharSequence line : lines) {
            g.text(font, line, x, y, Ui.SOFT, false);
            y += 10;
        }
    }
}
