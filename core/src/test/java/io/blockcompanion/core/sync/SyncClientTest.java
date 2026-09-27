package io.blockcompanion.core.sync;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** Clients and a server talking through queues, the way the mods and the plugin do over the channel. */
class SyncClientTest {
    @TempDir
    Path dir;

    final AtomicLong now = new AtomicLong(5_000_000);
    SyncServer server;
    final ArrayDeque<Runnable> network = new ArrayDeque<>();

    /** A client placement: a library name, a pose and a version. */
    static final class FakeModel implements ClientPlacementModel {
        String name;
        PlacementPose pose;
        long version;
        String dimension = "minecraft:overworld";

        public Loaded current() {
            return name == null || !pose.dimension().equals(dimension) ? null : new Loaded(name, pose, version);
        }

        public String load(String libraryName, PlacementPose p) {
            if (!p.dimension().equals(dimension)) return "Go to " + p.dimension() + " first";
            name = libraryName;
            pose = p;
            version++;
            return null;
        }

        public void apply(PlacementPose p) {
            pose = p;
            version++;
        }

        void playerMoves(PlacementPose p) {
            pose = p;
            version++;
        }
    }

    /** A library in memory. */
    static final class FakeStorage implements SyncClient.Storage {
        final Map<String, byte[]> files = new HashMap<>();

        public String findLocal(String hash) {
            for (var e : files.entrySet()) if (Hashes.sha256(e.getValue()).equals(hash)) return e.getKey();
            return null;
        }

        public String saveDownloaded(String name, String hash, byte[] data) {
            files.put("shared/" + name, data);
            return "shared/" + name;
        }

        public byte[] read(String libraryName) throws IOException {
            byte[] b = files.get(libraryName);
            if (b == null) throw new IOException("missing");
            return b;
        }
    }

    final class Player implements SyncPeer, SyncClient.Listener {
        final UUID id = UUID.randomUUID();
        final String name;
        final Set<Permission> perms;
        final FakeModel model = new FakeModel();
        final FakeStorage storage = new FakeStorage();
        final List<String> notices = new ArrayList<>();
        final SyncClient client;

        Player(String name, Set<Permission> perms) {
            this.name = name;
            this.perms = perms;
            client = new SyncClient(bytes -> network.add(() -> server.receive(this, bytes)), storage, model, this, "test", id);
        }

        public UUID id() {
            return id;
        }

        public String name() {
            return name;
        }

        public boolean has(Permission p) {
            return perms.contains(p);
        }

        public void send(byte[] message) {
            network.add(() -> client.receive(message));
        }

        public void notice(boolean error, String message) {
            notices.add((error ? "! " : "") + message);
        }
    }

    static final Set<Permission> PLAYER = EnumSet.of(Permission.USE, Permission.UPLOAD, Permission.PLACE, Permission.LOCK);
    Player alice, bob;

    @BeforeEach
    void setUp() throws IOException {
        SharedStore store = new SharedStore(dir, SyncLog.NONE);
        store.load();
        server = new SyncServer(store, new SyncConfig(), "test-server", now::get, SyncLog.NONE);
        alice = new Player("Alice", PLAYER);
        bob = new Player("Bob", PLAYER);
    }

    /** Delivers messages and ticks everyone until the network is quiet. */
    void settle() {
        for (int round = 0; round < 500; round++) {
            while (!network.isEmpty()) network.poll().run();
            alice.client.tick();
            bob.client.tick();
            server.tick();
            if (network.isEmpty() && round > 10) return;
        }
        throw new AssertionError("network never settled");
    }

    @Test
    void helloTellsTheClientWhatTheServerAllows() {
        assertThat(alice.client.features()).isEqualTo(Features.NONE);
        alice.client.sendHello();
        settle();
        assertThat(alice.client.serverPresent()).isTrue();
        assertThat(alice.client.serverSoftware()).isEqualTo("test-server");
        assertThat(alice.client.features().syncEnabled()).isTrue();
        assertThat(alice.client.features().autoPlaceAllowed()).isTrue();
    }

    @Test
    void sharedPlacementTravelsBetweenPlayersBothWays() {
        byte[] file = ChunksTest.random(70_000, 21);
        alice.storage.files.put("towers/tower.litematic", file);
        alice.model.load("towers/tower.litematic", new PlacementPose("minecraft:overworld", 100, 64, 100, 0, false));
        alice.client.sendHello();
        bob.client.sendHello();
        settle();

        alice.client.shareCurrent();
        settle();
        assertThat(alice.notices).contains("Shared tower.litematic");
        UUID id = alice.client.linked();
        assertThat(id).isNotNull();
        assertThat(bob.client.schematics()).extracting(SchematicInfo::name).containsExactly("tower.litematic");
        assertThat(bob.client.placement(id).pose()).isEqualTo(alice.model.pose);

        // Bob loads it: downloaded (hash-checked) into his library and placed where Alice put it.
        bob.client.load(id);
        settle();
        assertThat(bob.storage.files.get("shared/tower.litematic")).isEqualTo(file);
        assertThat(bob.model.name).isEqualTo("shared/tower.litematic");
        assertThat(bob.model.pose).isEqualTo(alice.model.pose);
        assertThat(bob.client.linked()).isEqualTo(id);

        // Alice moves hers; Bob's follows.
        PlacementPose moved = new PlacementPose("minecraft:overworld", 104, 64, 100, 1, true);
        alice.model.playerMoves(moved);
        settle();
        assertThat(bob.model.pose).isEqualTo(moved);
        assertThat(bob.client.placement(id).editor()).isEqualTo(alice.id);

        // While Alice holds the editing lock, Bob's move is put back at once.
        bob.model.playerMoves(new PlacementPose("minecraft:overworld", 0, 64, 0, 0, false));
        settle();
        assertThat(bob.model.pose).isEqualTo(moved);
        assertThat(bob.notices).anyMatch(n -> n.contains("Alice is moving"));

        // The lock lapses; now Bob may move it and Alice follows.
        now.addAndGet(20_000);
        settle();
        PlacementPose bobs = new PlacementPose("minecraft:overworld", 90, 70, 90, 2, false);
        bob.model.playerMoves(bobs);
        settle();
        assertThat(alice.model.pose).isEqualTo(bobs);
    }

    /** BlockDesigner projects and Sponge schematics are shared, downloaded and loaded like any other file. */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"house.bdproj", "gate.schem"})
    void projectsAndSpongeFilesAreSharedByteForByte(String fileName) throws IOException {
        byte[] file;
        if (fileName.endsWith(".bdproj")) {
            try (var in = getClass().getResourceAsStream("/projects/house-format1.bdproj")) {
                file = in.readAllBytes();
            }
        } else {
            file = ChunksTest.random(45_000, 31);
        }
        alice.storage.files.put("builds/" + fileName, file);
        alice.model.load("builds/" + fileName, new PlacementPose("minecraft:overworld", 10, 64, 10, 1, false));
        alice.client.sendHello();
        bob.client.sendHello();
        settle();
        alice.client.shareCurrent();
        settle();
        assertThat(alice.notices).contains("Shared " + fileName);
        UUID id = alice.client.linked();
        assertThat(bob.client.schematics()).extracting(SchematicInfo::name).containsExactly(fileName);
        assertThat(bob.client.schematics().getFirst().hash()).isEqualTo(Hashes.sha256(file));

        bob.client.load(id);
        settle();
        assertThat(bob.storage.files.get("shared/" + fileName)).isEqualTo(file);
        assertThat(bob.model.name).isEqualTo("shared/" + fileName);
        assertThat(bob.model.pose).isEqualTo(alice.model.pose);
    }

    @Test
    void ownerLockedPlacementSnapsBackForOthers() {
        byte[] file = ChunksTest.random(3_000, 22);
        alice.storage.files.put("a.nbt", file);
        alice.model.load("a.nbt", new PlacementPose("minecraft:overworld", 1, 2, 3, 0, false));
        alice.client.sendHello();
        bob.client.sendHello();
        settle();
        alice.client.shareCurrent();
        settle();
        UUID id = alice.client.linked();
        alice.client.setLocked(id, true);
        settle();
        bob.client.load(id);
        settle();
        assertThat(bob.client.placement(id).locked()).isTrue();
        PlacementPose before = bob.model.pose;
        bob.model.playerMoves(new PlacementPose("minecraft:overworld", 50, 2, 3, 0, false));
        settle();
        assertThat(bob.model.pose).isEqualTo(before);
        assertThat(bob.notices).anyMatch(n -> n.contains("locked by Alice"));
    }

    @Test
    void removedPlacementUnlinksButKeepsTheLocalCopy() {
        alice.storage.files.put("a.nbt", ChunksTest.random(3_000, 23));
        alice.model.load("a.nbt", new PlacementPose("minecraft:overworld", 1, 2, 3, 0, false));
        alice.client.sendHello();
        settle();
        alice.client.shareCurrent();
        settle();
        UUID id = alice.client.linked();
        alice.client.deletePlacement(id);
        settle();
        assertThat(alice.client.linked()).isNull();
        assertThat(alice.client.placements()).isEmpty();
        assertThat(alice.model.name).isEqualTo("a.nbt");
    }

    @Test
    void loadingInAnotherDimensionExplainsWhy() {
        alice.storage.files.put("a.nbt", ChunksTest.random(3_000, 24));
        alice.model.load("a.nbt", new PlacementPose("minecraft:overworld", 1, 2, 3, 0, false));
        alice.client.sendHello();
        bob.client.sendHello();
        settle();
        alice.client.shareCurrent();
        settle();
        bob.model.dimension = "minecraft:the_nether";
        bob.client.load(alice.client.linked());
        settle();
        assertThat(bob.client.linked()).isNull();
        assertThat(bob.notices).anyMatch(n -> n.contains("Go to minecraft:overworld"));
    }

    @Test
    void refusedUploadIsReported() {
        Player viewer = new Player("Viewer", EnumSet.of(Permission.USE));
        viewer.storage.files.put("a.nbt", new byte[10]);
        viewer.client.sendHello();
        while (!network.isEmpty()) network.poll().run();
        assertThat(viewer.client.upload("a.nbt")).isNull();
        assertThat(viewer.notices).anyMatch(n -> n.contains("does not let you upload"));
    }

    @Test
    void resetForgetsTheServer() {
        alice.client.sendHello();
        settle();
        alice.client.reset();
        assertThat(alice.client.serverPresent()).isFalse();
        assertThat(alice.client.features()).isEqualTo(Features.NONE);
    }
}
