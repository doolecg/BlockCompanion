package io.blockcompanion.client;

import io.blockcompanion.client.easyplace.EasyPlace;
import io.blockcompanion.core.compare.Compare;
import io.blockcompanion.core.model.BlockState;
import io.blockcompanion.core.model.Box;
import io.blockcompanion.core.model.Structure;
import io.blockcompanion.core.placement.Placement;
import io.blockcompanion.core.progress.ProgressFile;
import io.blockcompanion.core.progress.ProgressTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Development check, with {@code BLOCKCOMPANION_PLACE_SELFTEST=1} in a singleplayer creative world: saves a small test
 * schematic (stairs, slab, log, furnace, chest, observer, trapdoor, a door on stone, plus a sign and water that stay
 * ghosts), loads it floating next to the player, puts a wrong block in one cell, then fills every placeable cell through
 * easy place (planned clicks, rotation packets, vanilla placement) and logs what the world got, the tracker's totals and
 * the shared progress file. It cleans up after itself (blocks, placement, schematic and progress file).
 */
final class PlaceSelfTest {
    private static int ticks;
    private static BlockPos origin;
    private static final List<int[]> ORDER = new ArrayList<>();
    private static int next;
    private static final String NAME = "blockcompanion-placetest";
    /** Where the player stood, to go back afterwards; the test happens in the sky above, away from any build. */
    private static Vec3 home;
    /** Where the player places from (within reach of every cell). */
    private static Vec3 standpoint;
    /** The player's game mode before the test (it runs in creative), put back afterwards. */
    private static net.minecraft.world.level.GameType savedMode;

    private PlaceSelfTest() {
    }

    private static Structure schematic() {
        Structure s = new Structure();
        s.set(0, 0, 0, BlockState.of("minecraft:stone"));
        s.set(1, 0, 0, BlockState.parse("minecraft:oak_stairs[facing=east,half=top,shape=straight,waterlogged=false]"));
        s.set(2, 0, 0, BlockState.parse("minecraft:oak_log[axis=x]"));
        s.set(3, 0, 0, BlockState.parse("minecraft:stone_slab[type=top,waterlogged=false]"));
        s.set(4, 0, 0, BlockState.parse("minecraft:furnace[facing=west,lit=false]"));
        s.set(5, 0, 0, BlockState.parse("minecraft:chest[facing=south,type=single,waterlogged=false]"));
        s.set(6, 0, 0, BlockState.parse("minecraft:observer[facing=up,powered=false]"));
        s.set(7, 0, 0, BlockState.parse("minecraft:oak_trapdoor[facing=north,half=top,open=false,powered=false,waterlogged=false]"));
        s.set(0, 1, 0, BlockState.parse("minecraft:oak_door[facing=north,half=lower,hinge=right,open=false,powered=false]"));
        s.set(0, 2, 0, BlockState.parse("minecraft:oak_door[facing=north,half=upper,hinge=right,open=false,powered=false]"));
        s.set(4, 1, 0, BlockState.parse("minecraft:oak_sign[rotation=4,waterlogged=false]"));
        s.set(7, 2, 0, BlockState.parse("minecraft:water[level=0]"));
        s.set(3, 1, 0, BlockState.parse("minecraft:stone_slab[type=double,waterlogged=false]"));
        return s;
    }

    /** With {@code BLOCKCOMPANION_SELFTEST_QUIT=1} the game closes a few seconds after the test. */
    private static int quitAt = -1;

    static void tick(Minecraft mc) {
        ticks++;
        if (ticks == quitAt && "1".equals(System.getenv("BLOCKCOMPANION_SELFTEST_QUIT"))) {
            BlockCompanionClient.LOG.info("Place self-test: closing the game");
            mc.stop();
            return;
        }
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null || mc.player == null) return;
        try {
            if (ticks == 100) goUp(mc, server);
            else if (ticks == 140) setUp(mc, server);
            else if (ticks == 165) teleport(mc, server, viewpoint());
            else if (ticks == 178) lookAtBox(mc);
            else if (ticks == 185) shot(mc, "bc-selftest-ghosts.png");
            else if (ticks == 190) wrongBlock(mc, server, true);
            else if (ticks == 205) shot(mc, "bc-selftest-wrong.png");
            else if (ticks == 210) {
                ProgressTracker t = BlockCompanionClient.progress();
                BlockCompanionClient.LOG.info("Place self-test: with a wrong block: {}", t == null ? "no tracker" : t.totals());
                wrongBlock(mc, server, false);
            } else if (ticks == 215) teleport(mc, server, standpoint);
            else if (ticks >= 230 && ticks % 5 == 0 && next < ORDER.size()) placeNext(mc);
            else if (ticks == 230 + ORDER.size() * 5 + 15) teleport(mc, server, viewpoint());
            else if (ticks == 230 + ORDER.size() * 5 + 30) {
                lookAtBox(mc);
                shot(mc, "bc-selftest-placed.png");
            } else if (ticks == 230 + ORDER.size() * 5 + 40) verify(mc);
            else if (ticks == 230 + ORDER.size() * 5 + 60) cleanUp(mc, server);
        } catch (RuntimeException e) {
            BlockCompanionClient.LOG.warn("Place self-test failed", e);
        }
    }

    /** Flies the player 20 blocks up (the test's schematic is loaded next to whatever else is loaded). */
    private static void goUp(Minecraft mc, IntegratedServer server) {
        home = mc.player.position();
        java.util.UUID id = mc.player.getUUID();
        server.execute(() -> {
            var sp = server.getPlayerList().getPlayer(id);
            if (sp == null) return;
            savedMode = sp.gameMode.getGameModeForPlayer();
            sp.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
        });
        teleport(mc, server, home.add(0, 20, 0));
    }

    private static void teleport(Minecraft mc, IntegratedServer server, Vec3 to) {
        java.util.UUID id = mc.player.getUUID();
        server.execute(() -> {
            var sp = server.getPlayerList().getPlayer(id);
            if (sp == null) return;
            sp.getAbilities().flying = true;
            sp.onUpdateAbilities();
            sp.teleportTo(to.x, to.y, to.z);
        });
    }

    private static void setUp(Minecraft mc, IntegratedServer server) throws RuntimeException {
        try {
            BlockCompanionClient.library().save(NAME, schematic(), 3955, "self-test", true);
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
        BlockPos p = mc.player.blockPosition();
        origin = new BlockPos(p.getX() - 3, p.getY() + 1, p.getZ() + 2);
        standpoint = mc.player.position();
        var key = mc.level.dimension();
        server.execute(() -> {
            var level = server.getLevel(key);
            if (level == null) return;
            for (int x = -1; x <= 8; x++)
                for (int y = -1; y <= 3; y++)
                    for (int z = -1; z <= 1; z++) level.setBlockAndUpdate(origin.offset(x, y, z), Blocks.AIR.defaultBlockState());
        });
        if (!BlockCompanionClient.load(NAME + ".schem")) return;
        Placement pl = BlockCompanionClient.placement();
        pl.moveTo(new io.blockcompanion.core.model.BlockPos(origin.getX(), origin.getY(), origin.getZ()));
        BlockCompanionClient.changed();
        // Placing order: the door's stone first; the sign, water and the door's upper half are left to the game.
        int[][] cells = {{0, 0}, {1, 0}, {2, 0}, {3, 0}, {4, 0}, {5, 0}, {6, 0}, {7, 0}, {0, 1}, {3, 1}, {3, 1}};
        ORDER.clear();
        for (int[] c : cells) ORDER.add(c);
        BlockCompanionClient.LOG.info("Place self-test: loaded at {}", origin);
    }

    /** A spot a few blocks back from the box, to see it whole. */
    private static Vec3 viewpoint() {
        return new Vec3(origin.getX() + 4.5, origin.getY() + 1, origin.getZ() - 5.5);
    }

    /** Turns the player to look at the middle of the test box. */
    private static void lookAtBox(Minecraft mc) {
        Vec3 eye = mc.player.getEyePosition();
        Vec3 target = new Vec3(origin.getX() + 4, origin.getY() + 1, origin.getZ() + 0.5);
        Vec3 d = target.subtract(eye);
        float yaw = (float) (Math.toDegrees(Math.atan2(-d.x, d.z)));
        float pitch = (float) (-Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z))));
        mc.player.setYRot(yaw);
        mc.player.setXRot(pitch);
    }

    /** The game's own frame (not the desktop) saved to the run folder's screenshots, to check the look. */
    private static void shot(Minecraft mc, String name) {
        net.minecraft.client.Screenshot.grab(mc.gameDirectory, name, mc.gameRenderer.mainRenderTarget(), 1,
                msg -> BlockCompanionClient.LOG.info("Place self-test: screenshot {}", msg.getString()));
    }

    private static void wrongBlock(Minecraft mc, IntegratedServer server, boolean put) {
        var key = mc.level.dimension();
        BlockPos at = origin.offset(2, 0, 0);
        server.execute(() -> {
            var level = server.getLevel(key);
            if (level != null) level.setBlockAndUpdate(at, put ? Blocks.DIRT.defaultBlockState() : Blocks.AIR.defaultBlockState());
        });
    }

    private static void placeNext(Minecraft mc) {
        int[] c = ORDER.get(next++);
        Placement pl = BlockCompanionClient.placement();
        BlockPos pos = origin.offset(c[0], c[1], 0);
        io.blockcompanion.core.model.BlockState want = pl.stateAt(pos.getX(), pos.getY(), pos.getZ());
        net.minecraft.world.level.block.state.BlockState wantMc = StateMapper.toMc(want);
        // The first click of the double slab places a single slab; the second click makes it double.
        if ("double".equals(want.get("type")) && !mc.level.getBlockState(pos).is(wantMc.getBlock())) {
            want = want.with("type", "bottom");
        }
        EasyPlace.pick(mc, new ItemStack(wantMc.getBlock().asItem()));
        Vec3 eye = mc.player.getEyePosition();
        Vec3 centre = Vec3.atCenterOf(pos);
        Direction face = Direction.getApproximateNearest(eye.x - centre.x, eye.y - centre.y, eye.z - centre.z);
        Vec3 hit = centre.add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        EasyPlace.Target t = new EasyPlace.Target(pos, face, hit, eye.distanceTo(hit), pl.stateAt(pos.getX(), pos.getY(), pos.getZ()), wantMc);
        boolean ok = EasyPlace.place(mc, t, InteractionHand.MAIN_HAND);
        BlockCompanionClient.LOG.info("Place self-test: easy place {} at {} -> {}", want, pos, ok ? "sent" : "refused");
    }

    private static void verify(Minecraft mc) {
        Placement pl = BlockCompanionClient.placement();
        Box b = pl.worldBox();
        int correct = 0, placeable = 0;
        for (int x = b.minX(); x <= b.maxX(); x++) {
            for (int y = b.minY(); y <= b.maxY(); y++) {
                for (int z = b.minZ(); z <= b.maxZ(); z++) {
                    io.blockcompanion.core.model.BlockState want = pl.stateAt(x, y, z);
                    if (want.isAir()) continue;
                    io.blockcompanion.core.model.BlockState have = StateMapper.toCore(mc.level.getBlockState(new BlockPos(x, y, z)));
                    Compare.Result r = Compare.classify(want, have);
                    boolean ghostOnly = want.path().endsWith("sign") || want.path().equals("water");
                    if (!ghostOnly) placeable++;
                    if (r == Compare.Result.CORRECT) correct++;
                    BlockCompanionClient.LOG.info("Place self-test: {} want {} have {} -> {}", new BlockPos(x, y, z), want, have, r);
                }
            }
        }
        ProgressTracker t = BlockCompanionClient.progress();
        BlockCompanionClient.LOG.info("Place self-test: {} of {} placeable cells correct; tracker {}; stats placed {} wrong {}", correct, placeable,
                t == null ? null : t.totals(), t == null ? -1 : t.stats().placed, t == null ? -1 : t.stats().wrong);
        BlockCompanionClient.buildProgress().flushFile();
        try {
            String hash = BlockCompanionClient.buildProgress().hash();
            Path file = ProgressFile.defaultFolder().resolve(ProgressFile.fileName(NAME + ".schem", hash));
            BlockCompanionClient.LOG.info("Place self-test: progress file {} exists={}\n{}", file, Files.exists(file),
                    Files.exists(file) ? Files.readString(file) : "");
        } catch (Exception e) {
            BlockCompanionClient.LOG.warn("Place self-test: progress file check failed", e);
        }
    }

    private static void cleanUp(Minecraft mc, IntegratedServer server) {
        String hash = BlockCompanionClient.buildProgress().hash();
        BlockCompanionClient.unload(true);
        if (home != null) teleport(mc, server, home);
        java.util.UUID id = mc.player.getUUID();
        net.minecraft.world.level.GameType mode = savedMode;
        if (mode != null) server.execute(() -> {
            var sp = server.getPlayerList().getPlayer(id);
            if (sp != null) sp.setGameMode(mode);
        });
        var key = mc.level.dimension();
        BlockPos o = origin;
        server.execute(() -> {
            var level = server.getLevel(key);
            if (level == null) return;
            for (int x = -1; x <= 8; x++)
                for (int y = -1; y <= 3; y++)
                    for (int z = -1; z <= 1; z++) level.setBlockAndUpdate(o.offset(x, y, z), Blocks.AIR.defaultBlockState());
        });
        try {
            Files.deleteIfExists(BlockCompanionClient.library().resolve(NAME + ".schem"));
            if (hash != null) Files.deleteIfExists(ProgressFile.defaultFolder().resolve(ProgressFile.fileName(NAME + ".schem", hash)));
        } catch (Exception e) {
            BlockCompanionClient.LOG.warn("Place self-test: clean-up failed", e);
        }
        BlockCompanionClient.LOG.info("Place self-test: done");
        quitAt = ticks + 60;
    }
}
