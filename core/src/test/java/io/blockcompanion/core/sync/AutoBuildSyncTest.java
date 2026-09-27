package io.blockcompanion.core.sync;

import io.blockcompanion.core.autobuild.AutoBuildJob;
import io.blockcompanion.core.autobuild.BuildWorld;
import io.blockcompanion.core.chests.LinkedChests;
import io.blockcompanion.core.formats.SchematicFile;
import io.blockcompanion.core.formats.Schematics;
import io.blockcompanion.core.formats.WriteOptions;
import io.blockcompanion.core.model.BlockState;
import io.blockcompanion.core.model.Structure;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** AutoBuild through the sync server: the feature flag, the server's own need-against-have check, building, the ding. */
class AutoBuildSyncTest {
    static final String DIM = "minecraft:overworld";
    static final Set<Permission> BUILDER = EnumSet.of(Permission.USE, Permission.UPLOAD, Permission.PLACE, Permission.LOCK, Permission.AUTOBUILD);
    /** Where the tower goes: its lowest block at y 64. */
    static final PlacementPose POSE = new PlacementPose(DIM, 10, 64, 20, 0, false);

    @TempDir
    Path dir;

    final AtomicLong now = new AtomicLong(1_000_000);
    ChestSyncTest.FakeWorld chests;
    Blocks blocks;
    SyncServer server;
    SyncServerTest.FakePeer alice;
    String hash;

    /** The world's blocks for AutoBuild: anything unset is air. */
    static final class Blocks implements BuildWorld {
        final Map<String, BlockState> set = new HashMap<>();
        final Set<UUID> creative = new java.util.HashSet<>();
        int dings;

        public boolean dimensionExists(String dimension) {
            return dimension.equals(DIM);
        }

        public boolean isLoaded(String dimension, int x, int y, int z) {
            return true;
        }

        public BlockState get(String dimension, int x, int y, int z) {
            return set.getOrDefault(x + "," + y + "," + z, BlockState.AIR);
        }

        public Check check(String dimension, int x, int y, int z, BlockState state) {
            return Check.OK;
        }

        public boolean place(String dimension, int x, int y, int z, BlockState state) {
            set.put(x + "," + y + "," + z, state);
            return true;
        }

        public boolean isCreative(UUID player) {
            return creative.contains(player);
        }

        public boolean isOnline(UUID player) {
            return true;
        }

        public void ding(UUID player) {
            dings++;
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        chests = new ChestSyncTest.FakeWorld();
        blocks = new Blocks();
        server = newServer(new SyncConfig(), true);
        alice = new SyncServerTest.FakePeer("Alice", BUILDER);
        // A little tower: two stone, a door on top (one item for both halves).
        Structure s = new Structure();
        s.set(0, 0, 0, BlockState.of("minecraft:stone"));
        s.set(1, 0, 0, BlockState.of("minecraft:stone"));
        s.set(0, 1, 0, BlockState.parse("minecraft:oak_door[facing=north,half=lower,hinge=left,open=false,powered=false]"));
        s.set(0, 2, 0, BlockState.parse("minecraft:oak_door[facing=north,half=upper,hinge=left,open=false,powered=false]"));
        Path file = dir.resolve("Tower.schem");
        Schematics.write(SchematicFile.single("Tower", s), Schematics.SPONGE, WriteOptions.defaults(WriteOptions.MC_1_21_1), file);
        byte[] data = Files.readAllBytes(file);
        hash = Hashes.sha256(data);
        server.store().addSchematic(new SchematicInfo(hash, "Tower.schem", data.length, alice.id(), "Alice", 0), data);
        send(alice, new Message.Hello(Protocol.VERSION, "client", 0));
    }

    SyncServer newServer(SyncConfig config, boolean canBuild) throws IOException {
        SharedStore store = new SharedStore(dir.resolve("world"), SyncLog.NONE);
        store.load();
        SyncServer s = new SyncServer(store, config, "test", now::get, SyncLog.NONE);
        s.setChestAccess(chests, new ChestLinkStore(dir.resolve("world").resolve("chests.json")));
        if (canBuild) s.setBuildWorld(blocks);
        return s;
    }

    void send(SyncServerTest.FakePeer p, Message m) {
        server.receive(p, Protocol.encode(m));
    }

    LinkedChests.Pos linkChest(int x, Map<String, Long> items) {
        chests.containers.put(ChestSyncTest.FakeWorld.key(DIM, x, 64, 0), new HashMap<>(items));
        send(alice, new Message.ChestLink(DIM, x, 64, 0, true));
        return new LinkedChests.Pos(DIM, x, 64, 0);
    }

    void tick(int n) {
        for (int i = 0; i < n; i++) server.tick();
    }

    @Test
    void theFeatureNeedsABuildWorldAndTheSwitchAndThePermissionIsItsOwn() throws IOException {
        Features f = alice.last(Message.ServerFeatures.class).features();
        assertThat(f.autoBuildAllowed()).isTrue();
        assertThat(f.autoBuildRate()).isEqualTo(20);
        assertThat(f.can(Permission.AUTOBUILD)).isTrue();

        SyncConfig off = new SyncConfig();
        off.allowAutoBuild = false;
        server = newServer(off, true);
        send(alice, new Message.Hello(Protocol.VERSION, "client", 0));
        assertThat(alice.last(Message.ServerFeatures.class).features().autoBuildAllowed()).isFalse();
        send(alice, new Message.AutoBuildStart(hash, POSE, 5, List.of()));
        assertThat(alice.last(Message.Notice.class).message()).isEqualTo("This server has AutoBuild turned off");

        server = newServer(new SyncConfig(), false);
        send(alice, new Message.Hello(Protocol.VERSION, "client", 0));
        assertThat(alice.last(Message.ServerFeatures.class).features().autoBuildAllowed()).isFalse();
    }

    @Test
    void dedicatedServersDefaultToOperatorsOnly(@TempDir Path cfg) {
        SyncConfig single = SyncConfig.load(cfg.resolve("a.properties"), false);
        SyncConfig dedicated = SyncConfig.load(cfg.resolve("b.properties"), true);
        assertThat(single.allows(Permission.AUTOBUILD, false)).isTrue();
        assertThat(dedicated.allows(Permission.AUTOBUILD, false)).isFalse();
        assertThat(dedicated.allows(Permission.AUTOBUILD, true)).isTrue();
        // The file keeps what it says once written.
        assertThat(SyncConfig.load(cfg.resolve("b.properties"), false).allows(Permission.AUTOBUILD, false)).isFalse();
    }

    @Test
    void withoutThePermissionItIsRefused() {
        SyncServerTest.FakePeer bob = new SyncServerTest.FakePeer("Bob", SyncServerTest.PLAYER);
        server.receive(bob, Protocol.encode(new Message.Hello(Protocol.VERSION, "client", 0)));
        assertThat(bob.last(Message.ServerFeatures.class).features().can(Permission.AUTOBUILD)).isFalse();
        server.receive(bob, Protocol.encode(new Message.AutoBuildStart(hash, POSE, 5, List.of())));
        assertThat(bob.last(Message.Notice.class).message()).isEqualTo("You may not use AutoBuild on this server");
    }

    @Test
    void theServerChecksTheChestsItselfAndSaysWhatIsShort() {
        LinkedChests.Pos c = linkChest(1, Map.of("minecraft:stone", 1L));
        send(alice, new Message.AutoBuildStart(hash, POSE, 5, List.of(c)));
        assertThat(alice.last(Message.Notice.class).message()).isEqualTo("Short: 1 oak door, 1 stone: AutoBuild didn't start");
        assertThat(alice.all(Message.AutoBuildStatus.class)).isEmpty();
        assertThat(chests.containers.get(ChestSyncTest.FakeWorld.key(DIM, 1, 64, 0))).containsEntry("minecraft:stone", 1L);
    }

    @Test
    void chestsThePlayerDidNotLinkDontCount() {
        chests.containers.put(ChestSyncTest.FakeWorld.key(DIM, 7, 64, 0), new HashMap<>(Map.of("minecraft:stone", 64L, "minecraft:oak_door", 4L)));
        send(alice, new Message.AutoBuildStart(hash, POSE, 5, List.of(new LinkedChests.Pos(DIM, 7, 64, 0))));
        assertThat(alice.last(Message.Notice.class).message()).isEqualTo("Link chests with the materials before starting AutoBuild");
    }

    @Test
    void buildsFromTheChestsThenDingsAndSaysSo() {
        LinkedChests.Pos c = linkChest(1, Map.of("minecraft:stone", 10L, "minecraft:oak_door", 3L));
        send(alice, new Message.AutoBuildStart(hash, POSE, 20, List.of(c)));
        assertThat(alice.last(Message.Notice.class).message()).isEqualTo("AutoBuild started: Tower (3 blocks, 20 per second)");
        Message.AutoBuildStatus first = alice.last(Message.AutoBuildStatus.class);
        assertThat(first.state()).isEqualTo(AutoBuildJob.State.RUNNING);
        assertThat(first.total()).isEqualTo(3);
        assertThat(first.pose()).isEqualTo(POSE);

        tick(20);
        assertThat(blocks.get(DIM, 10, 64, 20).path()).isEqualTo("stone");
        assertThat(blocks.get(DIM, 11, 64, 20).path()).isEqualTo("stone");
        assertThat(blocks.get(DIM, 10, 65, 20).get("half")).isEqualTo("lower");
        assertThat(blocks.get(DIM, 10, 66, 20).get("half")).isEqualTo("upper");
        assertThat(chests.containers.get(ChestSyncTest.FakeWorld.key(DIM, 1, 64, 0)))
                .containsEntry("minecraft:stone", 8L).containsEntry("minecraft:oak_door", 2L);
        assertThat(blocks.dings).isEqualTo(1);
        assertThat(alice.last(Message.Notice.class).message()).isEqualTo("AutoBuild finished: Tower (3 placed, 0 skipped)");
        Message.AutoBuildStatus last = alice.last(Message.AutoBuildStatus.class);
        assertThat(last.state()).isEqualTo(AutoBuildJob.State.FINISHED);
        assertThat(last.done()).isEqualTo(3);
    }

    @Test
    void whatItTakesIsSubtractedWithoutReadingTheChestsAgain() throws IOException {
        ChestSyncTest.CountingWorld counting = new ChestSyncTest.CountingWorld();
        chests = counting;
        server = newServer(new SyncConfig(), true);
        send(alice, new Message.Hello(Protocol.VERSION, "client", 0));
        LinkedChests.Pos c = linkChest(1, Map.of("minecraft:stone", 10L, "minecraft:oak_door", 3L));
        send(alice, new Message.AutoBuildStart(hash, POSE, 20, List.of(c)));
        int reads = counting.reads;

        tick(SyncServer.CHEST_REFRESH_TICKS);
        assertThat(alice.last(Message.AutoBuildStatus.class).state()).isEqualTo(AutoBuildJob.State.FINISHED);
        assertThat(counting.reads).isEqualTo(reads);
        // The player's list follows what was taken.
        assertThat(alice.last(Message.ChestContents.class).entries()).singleElement()
                .satisfies(e -> assertThat(e.items()).containsEntry("minecraft:stone", 8L).containsEntry("minecraft:oak_door", 2L));

        // Once it is done, ticks neither read the chests nor send the list again.
        int lists = alice.all(Message.ChestContents.class).size();
        tick(10 * SyncServer.CHEST_REFRESH_TICKS);
        assertThat(counting.reads).isEqualTo(reads);
        assertThat(alice.all(Message.ChestContents.class)).hasSize(lists);
    }

    @Test
    void creativeNeedsNoChestsAndTakesNothing() {
        LinkedChests.Pos c = linkChest(1, Map.of("minecraft:stone", 10L));
        blocks.creative.add(alice.id());
        send(alice, new Message.AutoBuildStart(hash, POSE, 20, List.of()));
        tick(20);
        assertThat(alice.last(Message.AutoBuildStatus.class).state()).isEqualTo(AutoBuildJob.State.FINISHED);
        assertThat(blocks.set).hasSize(4);
        assertThat(chests.containers.get(ChestSyncTest.FakeWorld.key(DIM, c.x(), 64, 0))).containsEntry("minecraft:stone", 10L);
    }

    @Test
    void onePerPlacementAndPauseResumeStop() {
        LinkedChests.Pos c = linkChest(1, Map.of("minecraft:stone", 10L, "minecraft:oak_door", 3L));
        send(alice, new Message.AutoBuildStart(hash, POSE, 1, List.of(c)));
        UUID job = alice.last(Message.AutoBuildStatus.class).job();
        send(alice, new Message.AutoBuildStart(hash, POSE, 1, List.of(c)));
        assertThat(alice.last(Message.Notice.class).message()).isEqualTo("AutoBuild is already running on Tower");

        send(alice, new Message.AutoBuildControl(job, Message.AutoBuildAction.PAUSE));
        assertThat(alice.last(Message.AutoBuildStatus.class).state()).isEqualTo(AutoBuildJob.State.PAUSED);
        tick(100);
        assertThat(blocks.set).isEmpty();
        send(alice, new Message.AutoBuildControl(job, Message.AutoBuildAction.RESUME));
        tick(25);
        assertThat(blocks.set).hasSize(1);
        send(alice, new Message.AutoBuildControl(job, Message.AutoBuildAction.STOP));
        assertThat(alice.last(Message.AutoBuildStatus.class).state()).isEqualTo(AutoBuildJob.State.STOPPED);
        assertThat(alice.last(Message.Notice.class).message()).isEqualTo("AutoBuild stopped: Tower (1 placed, 0 skipped)");
        tick(100);
        assertThat(blocks.set).hasSize(1);
        assertThat(blocks.dings).isZero();
    }

    @Test
    void runningOutPartwayPausesAndTellsThePlayer() {
        LinkedChests.Pos c = linkChest(1, Map.of("minecraft:stone", 2L, "minecraft:oak_door", 1L));
        send(alice, new Message.AutoBuildStart(hash, POSE, 20, List.of(c)));
        // Someone takes the door.
        chests.containers.get(ChestSyncTest.FakeWorld.key(DIM, 1, 64, 0)).remove("minecraft:oak_door");
        tick(20);
        assertThat(alice.last(Message.Notice.class).message()).startsWith("AutoBuild paused: Out of oak door");
        assertThat(alice.last(Message.AutoBuildStatus.class).state()).isEqualTo(AutoBuildJob.State.PAUSED);
        assertThat(blocks.set).hasSize(2);
    }
}
