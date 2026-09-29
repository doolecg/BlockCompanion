package io.blockcompanion.client;

import com.mojang.blaze3d.platform.InputConstants;
import io.blockcompanion.client.autobuild.AutoBuildClient;
import io.blockcompanion.client.chests.ChestTracker;
import io.blockcompanion.client.easyplace.EasyPlace;
import io.blockcompanion.client.screen.LibraryScreen;
import io.blockcompanion.client.screen.ResourceScreen;
import io.blockcompanion.core.model.Box;
import io.blockcompanion.core.placement.Layers;
import io.blockcompanion.core.placement.PlacementLock;
import io.blockcompanion.network.ClientSync;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * Development check of the tutorial, with {@code BLOCKCOMPANION_TUTORIAL_SELFTEST=1}: starting from the title screen, it
 * opens the tutorial world, and for every step saves a screenshot ({@code screenshots/bc-tutorial-NN.png}), then does
 * what the step asks (the way a player would end up doing it) and checks that the tutorial notices and moves on. Steps
 * with a menu also get a screenshot of it ({@code bc-tutorial-NN-menu.png}). It logs each step and "Tutorial self-test
 * done" (or what went wrong). {@code BLOCKCOMPANION_SELFTEST_QUIT=1} closes the game after.
 */
final class TutorialSelfTest {
    static final boolean ENABLED = "1".equals(System.getenv("BLOCKCOMPANION_TUTORIAL_SELFTEST"));
    /** Ticks to wait on each step before the screenshot (the teleport, the area set-up and the ghosts take a moment). */
    private static final int SHOT_AFTER = 50;
    /** Ticks to wait after doing a step for the tutorial to move on, before calling it a failure. */
    private static final int GIVE_UP_AFTER = 200;
    /** AutoBuild places its blocks one after another, so its step is given longer. */
    private static final int AUTOBUILD_GIVE_UP_AFTER = 900;
    /** The last step: the tutorial is done when it is reached and its panel has been seen. */
    private static final int LAST_STEP = 20;
    /** How long a menu stays open before it is photographed, for it to draw itself. */
    private static final int MENU_WAIT = 15;

    private static int ticks;
    private static boolean opened, finished;
    /** The step being worked on, and the tick it started at. */
    private static int step = -1, stepStart;
    private static boolean shot, acted, doneShot;
    /** How many ticks "Good! You did it" has been up for this step. */
    private static int praisedFor;

    private TutorialSelfTest() {
    }

    /** Every client tick, also on the title screen. */
    static void tick(Minecraft mc) {
        if (!ENABLED || finished) return;
        ticks++;

        // On the title screen: open the tutorial world, like its button does.
        if (!opened) {
            // A screenshot of the title screen first, for the tutorial button's place under Realms.
            // (Counted from when the loading screen is gone.)
            if (!(mc.gui.screen() instanceof TitleScreen) || mc.gui.overlay() != null) {
                ticks = 0;
                return;
            }
            if (ticks == 40) screenshot(mc, "bc-tutorial-00-title.png");
            if (ticks > 50) {
                opened = true;
                Tutorial.open(mc.gui.screen());
            }
            return;
        }
        if (mc.player == null || Tutorial.step() < 0 || Tutorial.settingUp()) return;

        // A new step has started: wait a moment, take its screenshot, then do it.
        if (Tutorial.step() != step) {
            step = Tutorial.step();
            stepStart = ticks;
            shot = false;
            acted = false;
            doneShot = false;
            praisedFor = 0;
            BlockCompanionClient.LOG.info("Tutorial self-test: step {}", step + 1);
        }
        int age = ticks - stepStart;
        if (!shot && age >= SHOT_AFTER) {
            shot = true;
            screenshot(mc, String.format("bc-tutorial-%02d.png", step + 1));
        }
        if (shot && !acted && age >= SHOT_AFTER + 5) {
            acted = act(mc, age - (SHOT_AFTER + 5));
        }
        // The step's own screens are closed a moment after opening them, as a player would.
        if (acted && age == SHOT_AFTER + 25 && mc.gui.screen() != null) mc.gui.setScreen(null);
        if (Tutorial.praised()) praisedFor++;
        if (acted && !doneShot && praisedFor >= 8) {
            doneShot = true;
            screenshot(mc, String.format("bc-tutorial-%02d-done.png", step + 1));
        }
        // A done step waits for its reading timer; press Enter to go on straight away, as a player can.
        if (doneShot && praisedFor >= 20) Tutorial.onKey(com.mojang.blaze3d.platform.InputConstants.KEY_RETURN);

        if (step == LAST_STEP && acted && age >= SHOT_AFTER + 10) {
            finish(mc, "Tutorial self-test done");
        } else if (age > SHOT_AFTER + (step == 17 ? AUTOBUILD_GIVE_UP_AFTER : GIVE_UP_AFTER)) {
            finish(mc, "Tutorial self-test failed: step " + (step + 1) + " was not noticed");
        }
    }

    /**
     * Does what the current step asks. It is called every tick from a moment after the screenshot, with the ticks since
     * then, until it returns true (it is done).
     */
    private static boolean act(Minecraft mc, int since) {
        switch (step) {
            case 0, 8 -> {
                mc.gui.setScreen(step == 0 ? new LibraryScreen(null) : new ResourceScreen(null, Tutorial.placementIn(4)));
                return true;
            }
            case 1 -> {
                // The tool went into the first hotbar slot; look at the middle of the hut's box.
                mc.player.getInventory().setSelectedSlot(0);
                var box = Tutorial.placementIn(0).placement.worldBox();
                lookAt(mc, new Vec3((box.minX() + box.maxX() + 1) / 2.0, box.minY() + 1.5, box.minZ() + 0.5));
            }
            case 2 -> {
                BlockPos floor = Tutorial.hutFloor();
                Tutorial.placementIn(0).placement.moveTo(new io.blockcompanion.core.model.BlockPos(floor.getX(), floor.getY(), floor.getZ()));
                BlockCompanionClient.changed(Tutorial.placementIn(0));
            }
            case 3 -> {
                Tutorial.placementIn(0).locks.add(PlacementLock.POSITION);
                BlockCompanionClient.changed(Tutorial.placementIn(0));
            }
            case 4 -> setBlock(mc, Tutorial.wrongBlockPos(), "stone_bricks");
            case 5 -> setBlock(mc, Tutorial.inTheWayPos(), "air");
            case 6 -> {
                var box = Tutorial.placementIn(3).placement.worldBox();
                command(mc, "fill " + box.minX() + " " + box.minY() + " " + box.minZ() + " " + box.maxX() + " " + box.minY() + " " + box.minZ()
                        + " oak_stairs[facing=south]");
                command(mc, "fill " + box.minX() + " " + box.minY() + " " + (box.minZ() + 1) + " " + box.maxX() + " " + box.minY() + " " + box.maxZ()
                        + " oak_planks");
            }
            case 7 -> {
                Tutorial.placementIn(4).layers.set(0, Layers.Mode.BUILD_UP);
                BlockCompanionClient.changed(Tutorial.placementIn(4));
            }
            case 9 -> {
                // "Chapter 1 done": Enter starts chapter 2.
                Tutorial.onKey(InputConstants.KEY_RETURN);
            }
            case 10 -> {
                // Press the menu key, pick the well under Source and press Load.
                if (since == 0) mc.gui.setScreen(new LibraryScreen(null, LibraryScreen.Step.SOURCE, false));
                if (since < MENU_WAIT) return false;
                screenshot(mc, "bc-tutorial-11-menu.png");
                BlockCompanionClient.load(Tutorial.WELL_FILE);
                mc.gui.setScreen(null);
            }
            case 11 -> {
                // Tool in hand, looking at the well's box, and the turn a Ctrl+scroll makes.
                mc.player.getInventory().setSelectedSlot(0);
                lookAtBox(mc, Tutorial.well());
                if (since < 3) return false;
                Tutorial.well().placement.rotate(1);
                BlockCompanionClient.changed(Tutorial.well());
            }
            case 12 -> {
                // What the mirror key does.
                Tutorial.well().placement.toggleMirror();
                BlockCompanionClient.changed(Tutorial.well());
            }
            case 13 -> {
                // The menu's Placement step, and its "In place" button.
                if (since == 0) mc.gui.setScreen(new LibraryScreen(null, LibraryScreen.Step.PLACEMENT, false));
                if (since < MENU_WAIT) return false;
                screenshot(mc, "bc-tutorial-14-menu.png");
                var well = Tutorial.well();
                well.locks.clear();
                well.locks.addAll(PlacementLock.IN_PLACE);
                BlockCompanionClient.changed(well);
                mc.gui.setScreen(null);
            }
            case 14 -> {
                // Look at the first ghost and middle-click it.
                var ghosts = Tutorial.placementIn(6).placement.worldBox();
                lookAt(mc, new Vec3(ghosts.minX() + 0.5, ghosts.minY() + 0.6, ghosts.minZ() + 0.05));
                if (since < 5) return false;
                boolean picked = BlockCompanionClient.onPickBlock();
                BlockCompanionClient.LOG.info("Tutorial self-test: middle-click on the ghost picked: {}", picked);
                if (!picked) EasyPlace.pick(mc, new ItemStack(Items.OAK_PLANKS));
            }
            case 15 -> {
                // The block in the hand goes on its ghost.
                var ghosts = Tutorial.placementIn(6).placement.worldBox();
                setBlock(mc, new BlockPos(ghosts.minX(), ghosts.minY(), ghosts.minZ()), "oak_planks");
            }
            case 16 -> {
                // Tool in hand, looking at the chest, and the link a Ctrl+right-click makes.
                mc.player.getInventory().setSelectedSlot(0);
                BlockPos chest = Tutorial.chest();
                lookAt(mc, Vec3.atCenterOf(chest));
                if (since < 3) return false;
                ChestTracker.get().toggle(mc.level, chest);
            }
            case 17 -> {
                // The menu's Resources step and its Start AutoBuild button, once the button is ready.
                if (since == 0) mc.gui.setScreen(new LibraryScreen(null, LibraryScreen.Step.RESOURCES, false));
                var platform = Tutorial.placementIn(7);
                if (since < MENU_WAIT || platform == null) return false;
                if (!AutoBuildClient.check(platform).canStart()) {
                    if (since % 100 == 0) BlockCompanionClient.LOG.info("Tutorial self-test: AutoBuild not ready: {}", AutoBuildClient.check(platform).tip());
                    return false;
                }
                screenshot(mc, "bc-tutorial-18-menu.png");
                AutoBuildClient.start(platform);
                mc.gui.setScreen(null);
            }
            case 18 -> {
                // The menu's Source step on the server, and its Share mine button, once the server has answered.
                if (since == 0) mc.gui.setScreen(new LibraryScreen(null, LibraryScreen.Step.SOURCE, true));
                var sync = ClientSync.client();
                if (since < MENU_WAIT || sync == null || !sync.serverPresent()) return false;
                screenshot(mc, "bc-tutorial-19-menu.png");
                sync.shareCurrent();
                mc.gui.setScreen(null);
            }
            case 19 -> {
                // The menu's BlockDesigner step and its Start button, then Enter, as no BlockDesigner is here to connect.
                if (since == 0) mc.gui.setScreen(new LibraryScreen(null, LibraryScreen.Step.LINK, false));
                if (since < MENU_WAIT) return false;
                BlockCompanionClient.startLink();
                screenshot(mc, "bc-tutorial-20-menu.png");
                mc.gui.setScreen(null);
                Tutorial.onKey(InputConstants.KEY_RETURN);
            }
            default -> {
            }
        }
        return true;
    }

    private static void finish(Minecraft mc, String message) {
        finished = true;
        BlockCompanionClient.LOG.info(message);
        if ("1".equals(System.getenv("BLOCKCOMPANION_SELFTEST_QUIT"))) mc.stop();
    }

    /** Turns to look at the middle of a placement's box. */
    private static void lookAtBox(Minecraft mc, LoadedPlacement placement) {
        Box box = placement.placement.worldBox();
        lookAt(mc, new Vec3((box.minX() + box.maxX() + 1) / 2.0, box.minY() + 1.5, (box.minZ() + box.maxZ() + 1) / 2.0));
    }

    private static void lookAt(Minecraft mc, Vec3 target) {
        Vec3 d = target.subtract(mc.player.getEyePosition());
        mc.player.setYRot((float) Math.toDegrees(Math.atan2(-d.x, d.z)));
        mc.player.setXRot((float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z))));
    }

    private static void setBlock(Minecraft mc, BlockPos pos, String block) {
        command(mc, "setblock " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " " + block);
    }

    private static void command(Minecraft mc, String command) {
        var server = mc.getSingleplayerServer();
        server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command));
    }

    private static void screenshot(Minecraft mc, String name) {
        net.minecraft.client.Screenshot.grab(mc.gameDirectory, name, mc.gameRenderer.mainRenderTarget(), 1,
                msg -> BlockCompanionClient.LOG.info("Tutorial self-test: {}", msg.getString()));
    }
}
