package io.blockcompanion.client;

import com.mojang.blaze3d.platform.InputConstants;
import io.blockcompanion.client.chests.ChestTracker;
import io.blockcompanion.client.link.ClientLink;
import io.blockcompanion.client.screen.GuideScreen;
import io.blockcompanion.client.screen.LibraryScreen;
import io.blockcompanion.client.screen.ResourceScreen;
import io.blockcompanion.client.screen.Ui;
import io.blockcompanion.client.tool.SelectionTool;
import io.blockcompanion.core.compare.Compare;
import io.blockcompanion.core.model.BlockState;
import io.blockcompanion.core.model.Structure;
import io.blockcompanion.core.placement.Placement;
import io.blockcompanion.core.placement.PlacementLock;
import io.blockcompanion.core.progress.ProgressFile;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

/**
 * The tutorial world, opened from the title screen's "BlockCompanion tutorial" button.
 *
 * <p>It is a normal singleplayer world (peaceful, from a fixed seed) with five small areas side by side, one per lesson.
 * Each step tells the player to do one thing, waits until they have done it, says "Good! You did it" and moves on,
 * teleporting them to the next area when the lesson changes:
 * <ol>
 *     <li>Area 0, a hut: open the menu, hold the tool, move the hut onto its floor, lock it.</li>
 *     <li>Area 1, a wall with one wrong (red) block: break it and place the right one.</li>
 *     <li>Area 2, a platform with a pumpkin in the way (orange): break it.</li>
 *     <li>Area 3, a small porch: build it with easy place.</li>
 *     <li>Area 4, a tower: step through its layers and open the resource list.</li>
 * </ol>
 * The game mode follows the lesson: adventure while looking and moving things (nothing can be broken by accident),
 * survival while breaking and placing blocks, creative at the end. Enter skips a step.
 *
 * <p>Chapter 2 goes on eastwards with more areas, one lesson each, and uses real schematic files: the tutorial writes a
 * small well and a small platform into the player's schematic library, and takes them out again when the world is left.
 * <ol>
 *     <li>Area 5, a well: load it from the menu, turn it, mirror it and lock it in place.</li>
 *     <li>Area 6, a row of ghosts: pick a block from a ghost with middle-click, then place it.</li>
 *     <li>Area 7, a platform and a chest with its materials: link the chest, then let AutoBuild build the platform.</li>
 *     <li>Area 5 again: share the well, then read about the live link with BlockDesigner.</li>
 * </ol>
 *
 * <p>The schematics are "demo" placements: they only exist on this client and are never saved (see
 * {@link LoadedPlacement#demo}). The areas are levelled and built again with commands every time the tutorial starts,
 * so it always starts fresh.
 */
public final class Tutorial {
    /** The tutorial world's folder in {@code saves}. */
    public static final String FOLDER = "blockcompanion-tutorial";
    private static final String WORLD_NAME = "BlockCompanion tutorial";
    /** A fixed seed, so every player gets the same world. */
    private static final long SEED = 7_220_194_411L;

    /** How far apart the areas are, from the middle of one to the middle of the next (east). */
    private static final int AREA_SPACING = 22;
    /** Chapter 2's areas, east of chapter 1's areas 0 to 4. The well's area is visited twice: to turn it and to share it. */
    private static final int WELL_AREA = 5, PICK_AREA = 6, CHEST_AREA = 7;
    /** The last area: the strip of land, and the chunks kept loaded, reach this far. */
    private static final int LAST_AREA = CHEST_AREA;

    /** The schematic files the tutorial writes into the player's schematic library (and takes out again). */
    static final String WELL_FILE = "BlockCompanion tutorial well.schem";
    private static final String PLATFORM_FILE = "BlockCompanion tutorial platform.schem";

    /** Every step stays up at least this long, so there's time to read it (20 ticks = 1 second). */
    private static final int READ_TICKS = 20 * 20;
    /** And "Good! You did it" stays up at least this long before the next step. */
    private static final int PRAISE_TICKS = 3 * 20;

    // ---- the steps ---------------------------------------------------------------------------------------------------

    /**
     * One thing for the player to learn and do. {@code chapter} is 1 or 2, {@code area} is where it happens and {@code mode}
     * the game mode for it. The title and text may use {key} names and {wrong:...} / {extra:...} colours, like the guide's
     * pages. {@code footer} is the line at the bottom of the panel about the Enter key.
     */
    private record Step(int chapter, int area, GameType mode, String title, String text, String footer) {
        /** A step with the usual footer: Enter skips it. */
        Step(int chapter, int area, GameType mode, String title, String text) {
            this(chapter, area, mode, title, text, "Enter: skip this step");
        }
    }

    /** The index in STEPS where chapter 2 starts. */
    private static final int CHAPTER_2_START = 10;
    /** The steps of chapter 2 that get special care, by their place in STEPS. */
    private static final int TURN_STEP = CHAPTER_2_START + 1;
    private static final int LIVE_LINK_STEP = CHAPTER_2_START + 9;

    private static final List<Step> STEPS = List.of(
            // ---- chapter 1: the basics ----
            new Step(1, 0, GameType.ADVENTURE, "Welcome to BlockCompanion!",
                    "Let's learn the basics. First, press {library} to open the BlockCompanion menu. Close it again with Esc."),
            new Step(1, 0, GameType.ADVENTURE, "Your building tool",
                    "You now have a {tool}. Hold it in your hand and look at the hut's box, the outline around it. It turns yellow."),
            new Step(1, 0, GameType.ADVENTURE, "Move it into place",
                    "The hut belongs on the stone floor, but it's floating and too far back. Hold {move} and scroll to move it, "
                            + "one block per click. It moves towards the side of the box you look at: scroll up pushes it away, "
                            + "scroll down pulls it closer. Easiest of all: walk inside it. Then it moves the way you look, so "
                            + "look down and scroll up to lower it."),
            new Step(1, 0, GameType.ADVENTURE, "Lock it",
                    "Now press {lock} while you look at it. A locked schematic can't be moved by accident, and its box turns blue."),
            new Step(1, 1, GameType.SURVIVAL, "{wrong:Red} means a wrong block",
                    "One block in this wall is the wrong kind, so it is marked {wrong:red}. Break it with the pickaxe ({attack}), then "
                            + "{use} its ghost with the stone bricks from your hotbar."),
            new Step(1, 2, GameType.SURVIVAL, "{extra:Orange} means in the way",
                    "The schematic wants air where the pumpkin is, so the pumpkin is marked {extra:orange}. Break it ({attack})."),
            new Step(1, 3, GameType.SURVIVAL, "Easy place",
                    "Easy place is on now ({easy_place} turns it on and off). Hold the stairs or the planks and {use} a ghost. "
                            + "BlockCompanion places exactly that block there, turned the right way. Build the whole porch."),
            new Step(1, 4, GameType.ADVENTURE, "Layers",
                    "Big builds are easier one layer at a time. Look at the tower and press {layer_down} or {layer_up} to step "
                            + "through its layers."),
            new Step(1, 4, GameType.ADVENTURE, "What you need",
                    "Press {resources} to see every block the tower still needs and how many you already have. Close it with Esc."),
            new Step(1, 0, GameType.CREATIVE, "Chapter 1 done!",
                    "That's all you need to start building. The hut is yours to finish in creative mode. Chapter 2 is about your own "
                            + "schematics, chests, AutoBuild, sharing and BlockDesigner. Press Enter to start chapter 2.",
                    "Enter: start chapter 2"),

            // ---- chapter 2: your own schematics ----
            new Step(2, WELL_AREA, GameType.ADVENTURE, "Load a schematic",
                    "A schematic is a saved build. We put a small well in your schematic folder. Press {library}, pick "
                            + "BlockCompanion tutorial well under Source and press Load."),
            new Step(2, WELL_AREA, GameType.ADVENTURE, "Turn it",
                    "The well is in front of you. Hold the {tool} and look at its box. Then hold {rotate} and scroll to turn it."),
            new Step(2, WELL_AREA, GameType.ADVENTURE, "Mirror it",
                    "Hold the {tool}, look at the well and press {mirror}. Mirroring flips it left to right. Watch the red "
                            + "bricks in the wall."),
            new Step(2, WELL_AREA, GameType.ADVENTURE, "Lock it in place",
                    "Happy with it? Press {library}, open the Placement step and press In place. Now it can't be moved, turned "
                            + "or mirrored by accident. Looking at it and pressing {lock} does the same."),
            new Step(2, PICK_AREA, GameType.SURVIVAL, "Pick a block",
                    "Some blocks are in your inventory, not in your hotbar. Look at a ghost and {pick} it to take its "
                            + "block into your hand."),
            new Step(2, PICK_AREA, GameType.SURVIVAL, "Place it",
                    "Now {use} the ghost with the block in your hand. BlockCompanion places it exactly there."),
            new Step(2, CHEST_AREA, GameType.SURVIVAL, "Link a chest",
                    "The chest next to the platform holds the blocks it needs. Hold the {tool}, hold {link} and {use} the "
                            + "chest to link it. Linked chests count as your materials."),
            new Step(2, CHEST_AREA, GameType.SURVIVAL, "AutoBuild",
                    "Let BlockCompanion build for you. Press {library}, open the Resources step and press Start AutoBuild. It "
                            + "takes the blocks from your chest and places them, one after another."),
            new Step(2, WELL_AREA, GameType.ADVENTURE, "Share it",
                    "On a server with BlockCompanion you can share a schematic, so your friends see it and can build it too. "
                            + "This world works like such a server. Press {library}, press Server under Source and press "
                            + "Share mine."),
            new Step(2, WELL_AREA, GameType.ADVENTURE, "Live link with BlockDesigner",
                    "BlockDesigner is a free Windows app for designing builds, and its BlockCompanion Plugin links it to this "
                            + "game. Press {library}, open the BlockDesigner step and press Start link. One friend can then design "
                            + "in BlockDesigner while another builds here, and the ghosts follow every change live. No BlockDesigner "
                            + "at hand? Just press Enter.",
                    "Enter: continue"),
            new Step(2, 0, GameType.CREATIVE, "You did it!",
                    "Well done! You can load, turn, lock, share and build any schematic now. {library} opens the menu any time, "
                            + "and the settings have a guide too. To leave, press Esc, then Save and Quit to Title.",
                    "Enter: close"));

    // ---- state -------------------------------------------------------------------------------------------------------

    /** Ticks since the tutorial world was joined; -1 while not in it. */
    private static int ticks = -1;
    /** The current step (an index into STEPS), or -1 before the first one starts. */
    private static int step = -1;
    /** The tick "Good! You did it" was shown for the current step, or -1 while the step isn't done yet. */
    private static int praisedAt = -1;
    /** The tick the current step started at, for the reading timer. */
    private static int stepStartedAt;
    /** Set once the player closed the tutorial at the last step: the panel goes away. */
    private static boolean closed;
    /** The tick the current area's blocks and schematic are set up at (a moment after the teleport), or -1 when done. */
    private static int setUpAt = -1;
    /** The tick the land is shaped at (once the area chunks have loaded), or -1 when done. */
    private static int shapeAt = -1;
    /**
     * Steps aren't checked before this tick: an area's blocks are placed by the server and take a moment to reach this
     * game, and until they do, a block to break would look broken already.
     */
    private static int checkFrom;
    /** The area schematic waiting to be loaded once its real blocks have arrived (at checkFrom), and its area. */
    private static Placement waitingSchematic;
    private static int waitingArea;
    /** The same for an area whose schematic is a file in the library (chapter 2's platform), and where it goes. */
    private static String waitingFile;
    private static BlockPos waitingOrigin;
    /** Whether the tutorial wrote its schematic files this visit, so that they are taken out again. */
    private static boolean wroteFiles;
    /** How the well was turned when the current step began: turning and mirroring are noticed as a change from this. */
    private static int rotationAtStart;
    private static boolean mirroredAtStart;
    /** The live link's state when its step began, so that a link the tutorial started is stopped again. */
    private static boolean linkReached, linkWasRunning;

    /** Where area 0's middle is: the ground block the player first stood on. The other areas are east of it. */
    private static int baseX, groundY, baseZ;
    /** The schematic in each area, once loaded: a demo in chapter 1, the platform (a library file) in area 7. */
    private static final LoadedPlacement[] AREA_PLACEMENTS = new LoadedPlacement[LAST_AREA + 1];

    /** For the "open a screen, then close it" steps: whether the wanted screen has been open this step. */
    private static boolean sawScreen;

    private Tutorial() {
    }

    // ---- opening the world -------------------------------------------------------------------------------------------

    /** From the title screen's button: opens the tutorial world, making it first if it doesn't exist yet. */
    public static void open(Screen parent) {
        Minecraft mc = Minecraft.getInstance();
        // The tutorial teaches what the guide says, so the guide doesn't need to pop up in this world.
        BlockCompanionClient.config().guideSeen = true;
        BlockCompanionClient.configChanged();
        if (mc.getLevelSource().levelExists(FOLDER)) {
            mc.createWorldOpenFlows().openWorld(FOLDER, () -> mc.setScreen(parent));
        } else {
            LevelSettings settings = new LevelSettings(WORLD_NAME, GameType.ADVENTURE, false, Difficulty.PEACEFUL, true,
                    new GameRules(), WorldDataConfiguration.DEFAULT);
            mc.createWorldOpenFlows().createFreshLevel(FOLDER, settings, new WorldOptions(SEED, true, false),
                    WorldPresets::createNormalWorldDimensions, parent);
        }
    }

    /** Whether the player is in the tutorial world right now. */
    public static boolean inTutorialWorld() {
        IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
        return server != null && FOLDER.equals(server.getWorldPath(LevelResource.ROOT).normalize().getFileName().toString());
    }

    /** The world was left (or another one joined): forget everything, so the next visit starts from the beginning. */
    static void reset() {
        removeSchematicFiles();
        if (linkReached && !linkWasRunning && !BlockCompanionClient.config().link) ClientLink.stop();
        linkReached = false;
        ticks = -1;
        step = -1;
        praisedAt = -1;
        setUpAt = -1;
        shapeAt = -1;
        closed = false;
        sawScreen = false;
        waitingSchematic = null;
        waitingFile = null;
        java.util.Arrays.fill(AREA_PLACEMENTS, null);
    }

    // ---- every tick --------------------------------------------------------------------------------------------------

    /** Called every client tick while in a world: runs the tutorial when that world is the tutorial world. */
    static void tick(Minecraft mc) {
        if (mc.player == null || !inTutorialWorld()) return;
        ticks++;

        // Two seconds after joining (the chunks around the player have loaded by then), the tutorial gets ready.
        // Two seconds after that, once the areas' chunks have loaded too, the land is shaped and the first step starts.
        if (step < 0) {
            if (ticks == 40) start(mc);
            if (shapeAt >= 0 && ticks >= shapeAt) {
                shapeAt = -1;
                shapeLand(mc);
                step = 0;
                stepStartedAt = ticks;
                goToArea(mc, 0);
            }
            return;
        }

        // Nothing else on screen while the tutorial runs: chat and pop-ups are cleared away every tick.
        if (!closed) {
            mc.gui.getChat().clearMessages(false);
            mc.getToasts().clear();
        }

        // A moment after a teleport, the new area's blocks and schematic are set up.
        if (setUpAt >= 0 && ticks >= setUpAt) {
            setUpAt = -1;
            setUpArea(mc, STEPS.get(step).area());
            checkFrom = ticks + 10;
        }
        if (setUpAt >= 0 || closed || ticks < checkFrom) return;

        // The area's blocks are here now, so its schematic can be loaded: its ghosts and marks match what is built.
        if (waitingSchematic != null) {
            AREA_PLACEMENTS[waitingArea] = BlockCompanionClient.load(waitingSchematic);
            waitingSchematic = null;
        }
        if (waitingFile != null) {
            AREA_PLACEMENTS[waitingArea] = loadFromLibrary(waitingFile, waitingOrigin);
            waitingFile = null;
            // A chest linked on an earlier visit to this world starts unlinked again.
            if (ChestTracker.get().isLinked(mc.level, chestPos())) ChestTracker.get().toggle(mc.level, chestPos());
        }

        // Has the player done what the step asks? Then praise them, and move on once the timer runs out.
        if (praisedAt < 0 && isDone(mc)) {
            praisedAt = ticks;
            mc.player.playSound(SoundEvents.PLAYER_LEVELUP, 0.5f, 1.2f);
        }
        if (praisedAt >= 0 && ticks >= nextStepAt()) nextStep(mc);
    }

    /** Remembers where the player stands as area 0's middle, then starts the first step there. */
    private static void start(Minecraft mc) {
        // A game closed in the middle of chapter 2 can leave the tutorial's schematics loaded and their files behind:
        // clear those first, so every run starts the same.
        wroteFiles = true;
        removeSchematicFiles();

        // The first time, the tutorial is built where the player stands, and that spot is written down in the world
        // folder. Later visits read it back and build in the same place, wherever the player was when they left.
        java.nio.file.Path spotFile = spotFile(mc);
        int[] spot = readSpot(spotFile);
        if (spot == null) {
            BlockPos feet = mc.player.blockPosition();
            spot = new int[]{feet.getX(), feet.getY() - 1, feet.getZ()};
            writeSpot(spotFile, spot);
        }
        baseX = spot[0];
        groundY = spot[1];
        baseZ = spot[2];
        // Load (and generate) every area's chunks now and keep them loaded, so the areas can be built and visited.
        command(mc, "forceload add " + (baseX - 40) + " " + (baseZ - 40) + " " + (areaX(LAST_AREA) + 40) + " " + (baseZ + 40));
        // A sunny day that stays sunny, and no chat lines or pop-ups that would cover the tutorial. Minecraft 26.x
        // spells the game rules like show_advancement_messages, older versions like showAdvancementMessages: both
        // are sent, and the one this version doesn't know is ignored.
        command(mc, "time set noon");
        command(mc, "weather clear");
        for (String rule : new String[]{"advance_time", "doDaylightCycle", "advance_weather", "doWeatherCycle", "send_command_feedback",
                "sendCommandFeedback", "show_advancement_messages", "announceAdvancements"}) {
            command(mc, "gamerule " + rule + " false");
        }
        // Unlocking every recipe and advancement now means none pop up later, when items are given.
        command(mc, "recipe give @a *");
        command(mc, "advancement grant @a everything");
        command(mc, "clear @a");
        shapeAt = ticks + 40;
    }

    /** The file in the tutorial world's folder that remembers where the tutorial is built. */
    private static java.nio.file.Path spotFile(Minecraft mc) {
        return mc.getSingleplayerServer().getWorldPath(LevelResource.ROOT).resolve("blockcompanion-tutorial.txt");
    }

    /** Reads the remembered spot (x, ground y, z), or null the first time (or if the file can't be read). */
    private static int[] readSpot(java.nio.file.Path file) {
        try {
            String[] parts = Files.readString(file).trim().split(" ");
            return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2])};
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static void writeSpot(java.nio.file.Path file, int[] spot) {
        try {
            Files.writeString(file, spot[0] + " " + spot[1] + " " + spot[2]);
        } catch (IOException e) {
            BlockCompanionClient.LOG.warn("Could not remember where the tutorial is: {}", e.toString());
        }
    }

    /** Whether the tutorial's panel is up (the rest of BlockCompanion's HUD stays out of its way meanwhile). */
    public static boolean active() {
        return step >= 0 && !closed && inTutorialWorld();
    }

    /**
     * When a done step moves on: once it has been up for the reading time, and "Good! You did it" for a moment. A step
     * done slowly moves on soon after; one done straight away still leaves time to read it.
     */
    private static int nextStepAt() {
        return Math.max(praisedAt + PRAISE_TICKS, stepStartedAt + READ_TICKS);
    }

    /** Goes on to the next step, or finishes after the last one. */
    private static void nextStep(Minecraft mc) {
        praisedAt = -1;
        sawScreen = false;
        stepStartedAt = ticks;
        if (step >= STEPS.size() - 1) {
            closed = true;
            return;
        }
        int oldArea = STEPS.get(step).area();
        step++;
        Step now = STEPS.get(step);
        onStepStart(mc, now);
        if (now.area() != oldArea) {
            goToArea(mc, now.area());
        } else {
            command(mc, "gamemode " + modeName(now.mode()) + " @a");
        }
    }

    /** Remembers what the new step needs to know about where things stood when it began. */
    private static void onStepStart(Minecraft mc, Step now) {
        // The steps that place blocks by clicking ghosts need easy place on.
        if (now.title().equals("Easy place") || now.title().equals("Place it")) {
            BlockCompanionClient.config().easyPlace = true;
            BlockCompanionClient.configChanged();
        }
        // In the well's area, the well is the selected schematic (what the menu's Placement step and Share mine act on).
        LoadedPlacement well = loadedFile(WELL_FILE);
        if (well != null && now.chapter() == 2 && now.area() == WELL_AREA) BlockCompanionClient.setActive(well);
        rotationAtStart = well == null ? 0 : well.placement.rotation();
        mirroredAtStart = well != null && well.placement.mirrored();
        if (step == LIVE_LINK_STEP) {
            linkReached = true;
            linkWasRunning = ClientLink.running();
        }
        // The menu loads the well right in front of the player, much too close to see it whole: step back before turning it.
        if (step == TURN_STEP) standBackFromWell(mc);
    }

    /** Teleports the player back from the well, looking at it, so that the whole well is in view. */
    private static void standBackFromWell(Minecraft mc) {
        LoadedPlacement well = loadedFile(WELL_FILE);
        if (well == null) return;
        io.blockcompanion.core.model.Box box = well.placement.worldBox();
        double x = (box.minX() + box.maxX() + 1) / 2.0;
        double z = box.minZ() - 6;
        command(mc, "tp @a " + x + " " + (groundY + 1) + " " + z + " 0 10");
    }

    /**
     * Teleports the player to an area, facing south where its schematic is. The area itself is set up a moment later,
     * once the chunks there have loaded (see {@link #tick}).
     */
    private static void goToArea(Minecraft mc, int area) {
        int x = areaX(area);
        if (area == WELL_AREA && loadedFile(WELL_FILE) != null) {
            // Coming back to the well: to a spot where the whole well can be seen, wherever it was moved to.
            standBackFromWell(mc);
        } else {
            command(mc, "tp @a " + x + ".5 " + (groundY + 1) + " " + (baseZ - 3) + ".5 0 20");
        }
        setUpAt = ticks + 10;
    }

    /** The x of an area's middle. */
    private static int areaX(int area) {
        return baseX + area * AREA_SPACING;
    }

    // ---- building the areas ------------------------------------------------------------------------------------------

    /**
     * Levels the area into a grass pad, builds its real blocks, loads its schematic, and gives the player what the step
     * needs. The steps that go back to the hut (the end of each chapter) find it already there, so only the game mode changes.
     */
    private static void setUpArea(Minecraft mc, int area) {
        Step now = STEPS.get(step);
        command(mc, "gamemode " + modeName(now.mode()) + " @a");
        if (area == 0 && step > 0) return;

        int x = areaX(area), y = groundY, z = baseZ;
        // Take away anything built here on an earlier visit, and put the grass back.
        fill(mc, x - 6, y + 1, z + 2, x + 6, y + 12, z + 10, "air");
        fill(mc, x - 6, y, z + 2, x + 6, y, z + 10, "grass_block");
        command(mc, "clear @a");
        if (area >= WELL_AREA) {
            setUpChapter2Area(mc, area);
            return;
        }

        Structure schematic = new Structure();
        BlockPos origin;
        switch (area) {
            case 0 -> {
                buildHut(schematic);
                // The real floor is built where the hut belongs...
                BlockPos floor = hutTarget();
                fill(mc, floor.getX(), y, floor.getZ(), floor.getX() + 6, y, floor.getZ() + 4, "stone_bricks");
                // ...but the schematic starts two blocks up and three too far back, for the player to move it into place.
                origin = floor.offset(0, 2, 3);
                command(mc, "give @a " + BlockCompanionClient.config().toolItem);
            }
            case 1 -> {
                // A wall of stone bricks. The real one has cobblestone in the middle of its bottom row: the wrong block.
                for (int dx = 0; dx < 5; dx++) {
                    for (int dy = 0; dy < 2; dy++) schematic.set(dx, dy, 0, BlockState.of("minecraft:stone_bricks"));
                }
                origin = new BlockPos(x - 2, y + 1, z + 2);
                fill(mc, x - 2, y + 1, z + 2, x + 2, y + 2, z + 2, "stone_bricks");
                command(mc, "setblock " + wrongBlock().getX() + " " + wrongBlock().getY() + " " + wrongBlock().getZ() + " cobblestone");
                command(mc, "give @a iron_pickaxe");
                command(mc, "give @a stone_bricks");
            }
            case 2 -> {
                // A platform with a pillar on each corner. The schematic has air in the middle, where the real one has a pumpkin.
                for (int dx = 0; dx < 3; dx++) {
                    for (int dz = 0; dz < 3; dz++) schematic.set(dx, 0, dz, BlockState.of("minecraft:stone_bricks"));
                }
                for (int[] c : new int[][]{{0, 0}, {2, 0}, {0, 2}, {2, 2}}) schematic.set(c[0], 1, c[1], BlockState.of("minecraft:stone_bricks"));
                origin = new BlockPos(x - 1, y + 1, z + 2);
                fill(mc, x - 1, y + 1, z + 2, x + 1, y + 1, z + 4, "stone_bricks");
                for (int[] c : new int[][]{{0, 0}, {2, 0}, {0, 2}, {2, 2}}) {
                    command(mc, "setblock " + (x - 1 + c[0]) + " " + (y + 2) + " " + (z + 2 + c[1]) + " stone_bricks");
                }
                command(mc, "setblock " + inTheWay().getX() + " " + inTheWay().getY() + " " + inTheWay().getZ() + " pumpkin");
            }
            case 3 -> {
                // A porch: three steps going up away from the player, and a plank floor behind them.
                for (int dx = 0; dx < 3; dx++) {
                    schematic.set(dx, 0, 0, BlockState.parse("minecraft:oak_stairs[facing=south,half=bottom,shape=straight,waterlogged=false]"));
                    schematic.set(dx, 0, 1, BlockState.of("minecraft:oak_planks"));
                    schematic.set(dx, 0, 2, BlockState.of("minecraft:oak_planks"));
                }
                origin = new BlockPos(x - 1, y + 1, z + 2);
                command(mc, "give @a oak_stairs 3");
                command(mc, "give @a oak_planks 6");
            }
            default -> {
                // A tower, three by three, with a different block on each of its five layers.
                String[] layers = {"stone_bricks", "oak_planks", "bricks", "glass", "oak_planks"};
                for (int dy = 0; dy < layers.length; dy++) {
                    for (int dx = 0; dx < 3; dx++) {
                        for (int dz = 0; dz < 3; dz++) schematic.set(dx, dy, dz, BlockState.of("minecraft:" + layers[dy]));
                    }
                }
                origin = new BlockPos(x - 1, y + 1, z + 2);
            }
        }
        // The schematic is loaded a moment later, once the blocks just placed have arrived (see tick).
        waitingSchematic = new Placement("Tutorial " + (area + 1), schematic,
                new io.blockcompanion.core.model.BlockPos(origin.getX(), origin.getY(), origin.getZ()));
        waitingArea = area;
    }

    /**
     * Chapter 2's areas. Unlike chapter 1's demos, their schematics are real files in the player's schematic library, so
     * that the menu, sharing and AutoBuild can work with them like with any schematic of their own.
     */
    private static void setUpChapter2Area(Minecraft mc, int area) {
        int x = areaX(area), y = groundY, z = baseZ;
        switch (area) {
            case WELL_AREA -> {
                // The player loads the well themselves from the menu, so only its file is made, and the tool given.
                Structure well = new Structure();
                buildWell(well);
                writeSchematicFile(WELL_FILE, well);
                command(mc, "give @a " + BlockCompanionClient.config().toolItem);
            }
            case PICK_AREA -> {
                // A row of ghosts in three kinds of block. Their blocks are in the inventory, not in the hotbar, with two
                // kinds that aren't needed. "inventory.0" is the first slot above the hotbar.
                Structure row = new Structure();
                buildPickRow(row);
                String[] inventory = {"cobblestone", PICK_BLOCKS.get(0), "stone_bricks", PICK_BLOCKS.get(1), PICK_BLOCKS.get(2)};
                for (int slot = 0; slot < inventory.length; slot++) {
                    command(mc, "item replace entity @a inventory." + slot + " with minecraft:" + inventory[slot] + " 16");
                }
                waitingSchematic = new Placement("Tutorial " + (area + 1), row,
                        new io.blockcompanion.core.model.BlockPos(x - 1, y + 1, z + 1));
                waitingArea = area;
            }
            default -> {
                // A platform to build, and a chest next to it with the blocks it needs (and a few more).
                Structure platform = new Structure();
                buildPlatform(platform);
                writeSchematicFile(PLATFORM_FILE, platform);
                BlockPos chest = chestPos();
                String at = chest.getX() + " " + chest.getY() + " " + chest.getZ();
                command(mc, "setblock " + at + " air");
                command(mc, "setblock " + at + " chest[facing=north]");
                command(mc, "item replace block " + at + " container.0 with minecraft:stone_bricks 16");
                command(mc, "item replace block " + at + " container.1 with minecraft:oak_planks 8");
                command(mc, "give @a " + BlockCompanionClient.config().toolItem);
                waitingFile = PLATFORM_FILE;
                waitingOrigin = new BlockPos(x - 3, y + 1, z + 1);
                waitingArea = area;
            }
        }
    }

    /** The blocks of the pick block lesson's ghosts. */
    private static final List<String> PICK_BLOCKS = List.of("oak_planks", "bricks", "glass");

    /** A row of ghosts three wide and two deep, in three kinds of block that repeat diagonally. */
    private static void buildPickRow(Structure row) {
        for (int dx = 0; dx < 3; dx++) {
            for (int dz = 0; dz < 2; dz++) row.set(dx, 0, dz, BlockState.of("minecraft:" + PICK_BLOCKS.get((dx + dz) % 3)));
        }
    }

    /**
     * A small well, 5 by 5: a stone brick floor with lapis lazuli in the middle for the water, a low cobblestone wall
     * around it, oak log posts in the corners and a spruce slab roof. Two red bricks in the front wall make it lopsided, so
     * turning and mirroring it can be seen.
     */
    private static void buildWell(Structure well) {
        for (int dx = 0; dx < 5; dx++) {
            for (int dz = 0; dz < 5; dz++) {
                boolean edge = dx == 0 || dx == 4 || dz == 0 || dz == 4;
                // The floor, with the water in the middle.
                well.set(dx, 0, dz, BlockState.of(edge ? "minecraft:stone_bricks" : "minecraft:lapis_block"));
                // The low wall, only around the edge.
                if (edge) well.set(dx, 1, dz, BlockState.of("minecraft:cobblestone"));
                // The roof.
                well.set(dx, 4, dz, BlockState.parse("minecraft:spruce_slab[type=bottom,waterlogged=false]"));
            }
        }
        // The posts, two blocks high on each corner.
        for (int[] corner : new int[][]{{0, 0}, {4, 0}, {0, 4}, {4, 4}}) {
            well.set(corner[0], 2, corner[1], BlockState.of("minecraft:oak_log"));
            well.set(corner[0], 3, corner[1], BlockState.of("minecraft:oak_log"));
        }
        // Two red bricks in the front wall, on the left side only.
        well.set(0, 1, 4, BlockState.of("minecraft:bricks"));
        well.set(1, 1, 4, BlockState.of("minecraft:bricks"));
    }

    /** A platform, 3 by 3 in stone bricks, with an oak plank post on each corner. */
    private static void buildPlatform(Structure platform) {
        for (int dx = 0; dx < 3; dx++) {
            for (int dz = 0; dz < 3; dz++) platform.set(dx, 0, dz, BlockState.of("minecraft:stone_bricks"));
        }
        for (int[] corner : new int[][]{{0, 0}, {2, 0}, {0, 2}, {2, 2}}) {
            platform.set(corner[0], 1, corner[1], BlockState.of("minecraft:oak_planks"));
        }
    }

    // ---- the schematic files -----------------------------------------------------------------------------------------

    /** Saves a schematic in the player's schematic library, where the menu lists it. It is taken out again by {@link #reset}. */
    private static void writeSchematicFile(String fileName, Structure schematic) {
        try {
            int dataVersion = SharedConstants.getCurrentVersion().getDataVersion().getVersion();
            BlockCompanionClient.library().save(fileName, schematic, dataVersion, "BlockCompanion tutorial", true);
            wroteFiles = true;
        } catch (IOException e) {
            BlockCompanionClient.LOG.warn("Could not write the tutorial schematic {}: {}", fileName, e.toString());
        }
    }

    /** Unloads the tutorial's schematics from the world and deletes their files (and progress files), if the tutorial wrote any. */
    private static void removeSchematicFiles() {
        if (!wroteFiles) return;
        wroteFiles = false;
        for (LoadedPlacement lp : BlockCompanionClient.placements()) {
            if (lp.demo || !(lp.name().equals(WELL_FILE) || lp.name().equals(PLATFORM_FILE))) continue;
            String hash = lp.progress().hash();
            BlockCompanionClient.unload(lp, true);
            // The progress file that was written for it, for BlockDesigner to read.
            if (hash != null) {
                try {
                    Files.deleteIfExists(ProgressFile.defaultFolder().resolve(ProgressFile.fileName(lp.name(), hash)));
                } catch (IOException | RuntimeException e) {
                    BlockCompanionClient.LOG.warn("Could not delete the progress file of {}: {}", lp.name(), e.toString());
                }
            }
        }
        for (String file : new String[]{WELL_FILE, PLATFORM_FILE}) {
            try {
                Files.deleteIfExists(BlockCompanionClient.library().resolve(file));
            } catch (IOException e) {
                BlockCompanionClient.LOG.warn("Could not delete the tutorial schematic {}: {}", file, e.toString());
            }
        }
    }

    /** Loads a schematic file of the library and puts it where the area wants it. */
    private static LoadedPlacement loadFromLibrary(String file, BlockPos origin) {
        if (!BlockCompanionClient.load(file)) return null;
        LoadedPlacement loaded = BlockCompanionClient.active();
        loaded.placement.moveTo(new io.blockcompanion.core.model.BlockPos(origin.getX(), origin.getY(), origin.getZ()));
        BlockCompanionClient.changed(loaded);
        return loaded;
    }

    /** The placement loaded from one of the tutorial's files, or null when the player hasn't loaded it (yet). */
    private static LoadedPlacement loadedFile(String file) {
        for (LoadedPlacement lp : BlockCompanionClient.placements()) {
            if (!lp.demo && lp.name().equals(file)) return lp;
        }
        return null;
    }

    /**
     * A small cottage, 7 blocks wide and 5 deep: a stone brick floor, oak log corners, spruce plank walls three blocks
     * high with glass windows, an oak door facing the player, and a spruce roof sloping down to the front and back.
     */
    private static void buildHut(Structure hut) {
        for (int dx = 0; dx < 7; dx++) {
            for (int dz = 0; dz < 5; dz++) {
                // The floor.
                hut.set(dx, 0, dz, BlockState.of("minecraft:stone_bricks"));

                // The walls, only around the edge.
                boolean edgeX = dx == 0 || dx == 6, edgeZ = dz == 0 || dz == 4;
                if (!edgeX && !edgeZ) continue;
                for (int dy = 1; dy <= 3; dy++) {
                    String block = "minecraft:spruce_planks";
                    if (edgeX && edgeZ) block = "minecraft:oak_log";
                    boolean window = dy == 2 && ((edgeZ && (dx == 1 || dx == 5)) || (edgeX && dz == 2));
                    if (window) block = "minecraft:glass";
                    hut.set(dx, dy, dz, BlockState.parse(block));
                }
            }
        }
        // The door, in the middle of the front wall.
        hut.set(3, 1, 0, BlockState.parse("minecraft:oak_door[facing=north,half=lower,hinge=left,open=false,powered=false]"));
        hut.set(3, 2, 0, BlockState.parse("minecraft:oak_door[facing=north,half=upper,hinge=left,open=false,powered=false]"));

        // The roof: two rows of stairs on each side, climbing to a row of slabs along the middle. The ends are planks.
        for (int dx = 0; dx < 7; dx++) {
            hut.set(dx, 4, 0, roofStairs("south"));
            hut.set(dx, 4, 4, roofStairs("north"));
            hut.set(dx, 5, 1, roofStairs("south"));
            hut.set(dx, 5, 3, roofStairs("north"));
            hut.set(dx, 6, 2, BlockState.parse("minecraft:spruce_slab[type=bottom,waterlogged=false]"));
        }
        for (int dx : new int[]{0, 6}) {
            for (int dz = 1; dz <= 3; dz++) hut.set(dx, 4, dz, BlockState.of("minecraft:spruce_planks"));
            hut.set(dx, 5, 2, BlockState.of("minecraft:spruce_planks"));
        }
    }

    /** Spruce stairs facing one way: "south" climbs away from the player, "north" towards them. */
    private static BlockState roofStairs(String facing) {
        return BlockState.parse("minecraft:spruce_stairs[facing=" + facing + ",half=bottom,shape=straight,waterlogged=false]");
    }

    // ---- shaping the land --------------------------------------------------------------------------------------------

    /** How far the flat ground reaches around the areas, in blocks. */
    private static final int FLAT_MARGIN = 12;
    /** How wide the slope is between the flat ground and the land around it. */
    private static final int FEATHER = 10;
    /** Past the slope, trees are cleared this much further, so none are left cut in half. */
    private static final int TREE_MARGIN = 5;

    /**
     * Levels one long strip of land for all the areas, at the height the player started on. Around the strip the ground
     * slopes smoothly back to the natural land (no cliffs or cut-off hills), and trees near the edge are removed whole.
     * Then a path is laid up to the hut's door. This runs on the server, straight on the world.
     */
    private static void shapeLand(Minecraft mc) {
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null) return;
        int minX = baseX - FLAT_MARGIN, maxX = areaX(LAST_AREA) + FLAT_MARGIN;
        int minZ = baseZ - FLAT_MARGIN, maxZ = baseZ + FLAT_MARGIN;
        int flatY = groundY;
        server.execute(() -> {
            net.minecraft.server.level.ServerLevel level = server.overworld();
            int reach = FEATHER + TREE_MARGIN;
            for (int x = minX - reach; x <= maxX + reach; x++) {
                for (int z = minZ - reach; z <= maxZ + reach; z++) {
                    // How far this column is from the flat strip (0 inside it).
                    int dx = Math.max(0, Math.max(minX - x, x - maxX));
                    int dz = Math.max(0, Math.max(minZ - z, z - maxZ));
                    double distance = Math.sqrt(dx * dx + dz * dz);
                    int natural = naturalGround(level, x, z);
                    if (distance > FEATHER) {
                        // Only take away trees out here.
                        clearTrees(level, x, z, natural);
                        continue;
                    }
                    // Inside the strip the ground is flat. On the slope it moves from flat to natural, gently at both ends.
                    double t = distance / FEATHER;
                    double smooth = t * t * (3 - 2 * t);
                    int target = (int) Math.round(flatY + (natural - flatY) * smooth);
                    setColumn(level, x, z, target, natural, Math.max(target, natural) + 30);
                }
            }
            layPath(level, flatY);
        });
    }

    /** The height of the natural ground in a column: its top solid block, ignoring trees, plants and water. */
    private static int naturalGround(net.minecraft.server.level.ServerLevel level, int x, int z) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
        for (; y > level.getMinBuildHeight(); y--) {
            net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos.set(x, y, z));
            boolean tree = state.is(net.minecraft.tags.BlockTags.LOGS) || state.is(net.minecraft.tags.BlockTags.LEAVES);
            if (!tree && !state.canBeReplaced() && state.getFluidState().isEmpty()) return y;
        }
        return y;
    }

    /**
     * Makes a column's ground top out at {@code top}: grass on top, dirt under it (down to the natural ground, where the
     * ground is raised), and air above up to {@code clearTo}.
     */
    private static void setColumn(net.minecraft.server.level.ServerLevel level, int x, int z, int top, int natural, int clearTo) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = top + 1; y <= clearTo; y++) {
            if (!level.getBlockState(pos.set(x, y, z)).isAir()) level.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2);
        }
        level.setBlock(pos.set(x, top, z), net.minecraft.world.level.block.Blocks.GRASS_BLOCK.defaultBlockState(), 2);
        for (int y = Math.min(natural + 1, top - 3); y < top; y++) {
            level.setBlock(pos.set(x, y, z), net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState(), 2);
        }
    }

    /** Removes the logs and leaves above the ground in a column. */
    private static void clearTrees(net.minecraft.server.level.ServerLevel level, int x, int z, int ground) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = ground + 1; y <= ground + 30; y++) {
            net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos.set(x, y, z));
            if (state.is(net.minecraft.tags.BlockTags.LOGS) || state.is(net.minecraft.tags.BlockTags.LEAVES)) {
                level.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2);
            }
        }
    }

    /**
     * A path from where the player starts up to the hut's door, three blocks wide. It starts as a few scattered patches
     * and gets thicker closer to the door, where it is solid.
     */
    private static void layPath(net.minecraft.server.level.ServerLevel level, int y) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int start = baseZ - 10, end = hutTarget().getZ() - 1;
        for (int z = start; z <= end; z++) {
            // 0 at the start of the path, 1 at the door.
            double closeness = (z - start) / (double) (end - start);
            for (int x = baseX - 1; x <= baseX + 1; x++) {
                double chance = x == baseX ? 0.3 + 0.7 * closeness : 0.1 + 0.6 * closeness;
                double roll = random(x, z);
                net.minecraft.world.level.block.Block block = null;
                if (roll < chance) block = net.minecraft.world.level.block.Blocks.DIRT_PATH;
                else if (roll < chance + 0.15) block = net.minecraft.world.level.block.Blocks.COARSE_DIRT;
                if (block != null) level.setBlock(pos.set(x, y, z), block.defaultBlockState(), 2);
            }
        }
    }

    /** A number from 0 to 1 that looks random but is always the same for the same spot, so the path never changes. */
    private static double random(int x, int z) {
        long h = x * 73_428_767L ^ z * 912_931L;
        h = (h ^ (h >>> 13)) * 0x5bd1e995L;
        return ((h ^ (h >>> 15)) & 0xFFFF) / 65536.0;
    }

    /** Where the hut's floor is built: the corner nearest the player on the left. */
    private static BlockPos hutTarget() {
        return new BlockPos(areaX(0) - 3, groundY, baseZ + 2);
    }

    /** The wall's wrong block: the middle of its bottom row. */
    private static BlockPos wrongBlock() {
        return new BlockPos(areaX(1), groundY + 1, baseZ + 2);
    }

    /** The chest with the platform's blocks, to the right of the platform. */
    private static BlockPos chestPos() {
        return new BlockPos(areaX(CHEST_AREA) + 1, groundY + 1, baseZ + 1);
    }

    /** The pumpkin in the way: on top of the middle of the platform. */
    private static BlockPos inTheWay() {
        return new BlockPos(areaX(2), groundY + 2, baseZ + 3);
    }

    // ---- checking the steps ------------------------------------------------------------------------------------------

    /** Whether the player has done what the current step asks. */
    private static boolean isDone(Minecraft mc) {
        Screen screen = mc.screen;
        switch (step) {
            case 0 -> {
                // Opened the menu, then closed it again.
                if (screen instanceof LibraryScreen) sawScreen = true;
                return sawScreen && screen == null;
            }
            case 1 -> {
                return SelectionTool.holding(mc.player) && AREA_PLACEMENTS[0] != null && BlockCompanionClient.hovered() == AREA_PLACEMENTS[0];
            }
            case 2 -> {
                if (AREA_PLACEMENTS[0] == null) return false;
                io.blockcompanion.core.model.BlockPos at = AREA_PLACEMENTS[0].placement.origin();
                BlockPos target = hutTarget();
                return at.x() == target.getX() && at.y() == target.getY() && at.z() == target.getZ();
            }
            case 3 -> {
                return AREA_PLACEMENTS[0] != null && AREA_PLACEMENTS[0].locked(PlacementLock.POSITION);
            }
            case 4 -> {
                return mc.level.getBlockState(wrongBlock()).getBlock() == net.minecraft.world.level.block.Blocks.STONE_BRICKS;
            }
            case 5 -> {
                return mc.level.getBlockState(inTheWay()).isAir();
            }
            case 6 -> {
                return AREA_PLACEMENTS[3] != null && allBuilt(mc, AREA_PLACEMENTS[3]);
            }
            case 7 -> {
                return AREA_PLACEMENTS[4] != null && !AREA_PLACEMENTS[4].layers.showsAll();
            }
            case 8 -> {
                // Opened the resource list, then closed it again.
                if (screen instanceof ResourceScreen) sawScreen = true;
                return sawScreen && screen == null;
            }
            default -> {
                // Chapter 2's steps have their own checks. The last step of a chapter has nothing to do: Enter goes on.
                return step >= CHAPTER_2_START && isChapter2StepDone(mc, step - CHAPTER_2_START);
            }
        }
    }

    /** Whether the player has done what one of chapter 2's steps asks ({@code lesson} 0 is its first). */
    private static boolean isChapter2StepDone(Minecraft mc, int lesson) {
        LoadedPlacement well = loadedFile(WELL_FILE);
        switch (lesson) {
            case 0 -> {
                // Loaded the well from the menu.
                return well != null;
            }
            case 1 -> {
                return well != null && well.placement.rotation() != rotationAtStart;
            }
            case 2 -> {
                return well != null && well.placement.mirrored() != mirroredAtStart;
            }
            case 3 -> {
                // Everything "In place" locks: moving, turning and mirroring.
                return well != null && well.locks.containsAll(PlacementLock.IN_PLACE);
            }
            case 4 -> {
                // One of the ghosts' blocks is in the hand.
                String held = BuiltInRegistries.ITEM.getKey(mc.player.getMainHandItem().getItem()).getPath();
                return PICK_BLOCKS.contains(held);
            }
            case 5 -> {
                // At least one of the ghosts has become a real block.
                return AREA_PLACEMENTS[PICK_AREA] != null && builtCount(mc, AREA_PLACEMENTS[PICK_AREA]) > 0;
            }
            case 6 -> {
                return ChestTracker.get().isLinked(mc.level, chestPos());
            }
            case 7 -> {
                // AutoBuild has built the whole platform.
                return AREA_PLACEMENTS[CHEST_AREA] != null && allBuilt(mc, AREA_PLACEMENTS[CHEST_AREA]);
            }
            case 8 -> {
                // The well is shared: it now follows its shared copy on the server.
                return well != null && BlockCompanionClient.sharedLink() == well;
            }
            case 9 -> {
                // BlockDesigner connected. Without it, Enter goes on.
                return ClientLink.state() == ClientLink.State.CONNECTED;
            }
            default -> {
                return false;
            }
        }
    }

    /** Whether every block of a schematic is in the world, the right way round. */
    private static boolean allBuilt(Minecraft mc, LoadedPlacement demo) {
        io.blockcompanion.core.model.Box box = demo.placement.worldBox();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = box.minX(); x <= box.maxX(); x++) {
            for (int y = box.minY(); y <= box.maxY(); y++) {
                for (int z = box.minZ(); z <= box.maxZ(); z++) {
                    BlockState want = demo.placement.stateAt(x, y, z);
                    if (want.isAir()) continue;
                    pos.set(x, y, z);
                    BlockState have = StateMapper.toCore(mc.level.getBlockState(pos));
                    if (Compare.classify(want, have) != Compare.Result.CORRECT) return false;
                }
            }
        }
        return true;
    }

    /** How many of a schematic's blocks are in the world, the right way round. */
    private static int builtCount(Minecraft mc, LoadedPlacement placement) {
        io.blockcompanion.core.model.Box box = placement.placement.worldBox();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int built = 0;
        for (int x = box.minX(); x <= box.maxX(); x++) {
            for (int y = box.minY(); y <= box.maxY(); y++) {
                for (int z = box.minZ(); z <= box.maxZ(); z++) {
                    BlockState want = placement.placement.stateAt(x, y, z);
                    if (want.isAir()) continue;
                    pos.set(x, y, z);
                    BlockState have = StateMapper.toCore(mc.level.getBlockState(pos));
                    if (Compare.classify(want, have) == Compare.Result.CORRECT) built++;
                }
            }
        }
        return built;
    }

    // ---- for the self-test -------------------------------------------------------------------------------------------

    /** The current step (0 is the first), or -1 before it starts. */
    static int step() {
        return step;
    }

    /** Whether "Good! You did it" is showing for the current step. */
    static boolean praised() {
        return praisedAt >= 0;
    }

    /** Whether the tutorial is waiting for an area to be set up after a teleport. */
    static boolean settingUp() {
        return setUpAt >= 0;
    }

    /** The schematic in an area (a demo, or chapter 2's platform), once loaded. */
    static LoadedPlacement placementIn(int area) {
        return AREA_PLACEMENTS[area];
    }

    /** The well the player loaded, or null. */
    static LoadedPlacement well() {
        return loadedFile(WELL_FILE);
    }

    static BlockPos chest() {
        return chestPos();
    }

    static BlockPos hutFloor() {
        return hutTarget();
    }

    static BlockPos wrongBlockPos() {
        return wrongBlock();
    }

    static BlockPos inTheWayPos() {
        return inTheWay();
    }

    // ---- keys --------------------------------------------------------------------------------------------------------

    /** From the keyboard mixin: Enter skips the current step (or closes the last one). True when the key was used. */
    public static boolean onKey(int key) {
        Minecraft mc = Minecraft.getInstance();
        if (step < 0 || closed || setUpAt >= 0 || mc.screen != null) return false;
        if (key == InputConstants.KEY_RETURN) {
            nextStep(mc);
            return true;
        }
        if (key == InputConstants.KEY_BACKSPACE && step > 0) {
            previousStep(mc);
            return true;
        }
        return false;
    }

    /**
     * Goes back one step, to read it again. The world is left as it is (only the player is taken back to that step's
     * area), so a step that is already done counts as done again, and moves on when its timer runs out.
     */
    private static void previousStep(Minecraft mc) {
        praisedAt = -1;
        sawScreen = false;
        stepStartedAt = ticks;
        int oldArea = STEPS.get(step).area();
        step--;
        Step now = STEPS.get(step);
        onStepStart(mc, now);
        command(mc, "gamemode " + modeName(now.mode()) + " @a");
        if (now.area() != oldArea) {
            command(mc, "tp @a " + areaX(now.area()) + ".5 " + (groundY + 1) + " " + (baseZ - 3) + ".5 0 20");
        }
    }

    // ---- the panel ---------------------------------------------------------------------------------------------------

    /** Draws the step's panel at the top middle of the screen. */
    static void render(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        if (step < 0 || closed || !inTutorialWorld()) return;
        Step now = STEPS.get(step);
        boolean praised = praisedAt >= 0;

        int w = Math.min(320, g.guiWidth() - 2 * Ui.PAD), x = (g.guiWidth() - w) / 2, y = Ui.PAD;
        int textW = w - 4 * Ui.PAD;
        List<FormattedCharSequence> lines = mc.font.split(GuideScreen.styled(now.text()), textW);
        int h = 2 * Ui.PAD + 14 + lines.size() * 10 + 14 + (praised ? 8 : 0);
        Ui.panel(g, x, y, w, h);

        int tx = x + 2 * Ui.PAD, ty = y + 2 * Ui.PAD;
        if (praised) {
            Ui.shadowed(g, mc.font, "Good! You did it.", tx, ty, Ui.GOOD);
        } else {
            Ui.shadowed(g, mc.font, GuideScreen.styled(now.title()), tx, ty, Ui.ACCENT);
        }
        Ui.rightText(g, mc.font, "Chapter " + now.chapter() + " · " + stepInChapter() + " of " + stepsInChapter(), x + w - 2 * Ui.PAD, ty, Ui.MUTED);
        ty += 14;
        for (FormattedCharSequence line : lines) {
            g.drawString(mc.font, line, tx, ty, praised ? Ui.DIM : Ui.SOFT, false);
            ty += 10;
        }
        // Once the step is done: a bar that empties until the next step starts.
        if (praised) {
            int total = nextStepAt() - praisedAt, left = Math.max(0, nextStepAt() - ticks);
            int barW = w - 4 * Ui.PAD, filled = total <= 0 ? 0 : barW * left / total;
            g.fill(tx, ty + 2, tx + barW, ty + 5, 0x40FFFFFF);
            g.fill(tx, ty + 2, tx + filled, ty + 5, Ui.GOOD);
            ty += 8;
        }
        String keys = praised ? "Next step in " + (Math.max(0, nextStepAt() - ticks) + 19) / 20 + "s · Enter: next now" : now.footer();
        if (step > 0) keys += " · Backspace: back";
        Ui.text(g, mc.font, keys, tx, ty + 2, Ui.DIM);
    }

    /** The current step's number within its chapter (1 is the first). */
    private static int stepInChapter() {
        int chapter = STEPS.get(step).chapter();
        int number = 0;
        for (int i = 0; i <= step; i++) {
            if (STEPS.get(i).chapter() == chapter) number++;
        }
        return number;
    }

    /** How many steps the current step's chapter has. */
    private static int stepsInChapter() {
        int chapter = STEPS.get(step).chapter();
        int count = 0;
        for (Step other : STEPS) {
            if (other.chapter() == chapter) count++;
        }
        return count;
    }

    // ---- small helpers -----------------------------------------------------------------------------------------------

    /** Runs a command on the singleplayer server, as the server itself (it may do anything), without chat output. */
    private static void command(Minecraft mc, String command) {
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null) return;
        server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command));
    }

    private static void fill(Minecraft mc, int x1, int y1, int z1, int x2, int y2, int z2, String block) {
        command(mc, "fill " + x1 + " " + y1 + " " + z1 + " " + x2 + " " + y2 + " " + z2 + " " + block);
    }

    /** The name the /gamemode command uses for a game mode. */
    private static String modeName(GameType mode) {
        return switch (mode) {
            case SURVIVAL -> "survival";
            case CREATIVE -> "creative";
            case SPECTATOR -> "spectator";
            default -> "adventure";
        };
    }
}
