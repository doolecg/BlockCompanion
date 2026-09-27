package io.blockcompanion.core.sync;

import io.blockcompanion.core.chests.LinkedChests;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** Linked chests through the sync server: linking, contents, restocking, persistence, and files from the app. */
class ChestSyncTest {
    @TempDir
    Path dir;

    final AtomicLong now = new AtomicLong(1_000_000);
    SyncServer server;
    FakeWorld world;
    SyncServerTest.FakePeer alice;

    /** Containers as position to contents; players' inventories as item counts. */
    static class FakeWorld implements ChestAccess {
        final Map<String, Map<String, Long>> containers = new HashMap<>();
        final Map<UUID, Map<String, Long>> inventories = new HashMap<>();
        int room = Integer.MAX_VALUE;

        static String key(String dim, int x, int y, int z) {
            return dim + x + "," + y + "," + z;
        }

        public Map<String, Long> contents(String dimension, int x, int y, int z) {
            Map<String, Long> c = containers.get(key(dimension, x, y, z));
            return c == null ? null : Map.copyOf(c);
        }

        public int take(UUID player, String dimension, int x, int y, int z, String item, int count) {
            Map<String, Long> c = containers.get(key(dimension, x, y, z));
            if (c == null) return 0;
            long have = c.getOrDefault(item, 0L);
            int n = (int) Math.min(Math.min(have, count), room);
            if (n <= 0) return 0;
            if (have - n == 0) c.remove(item);
            else c.put(item, have - n);
            inventories.computeIfAbsent(player, k -> new HashMap<>()).merge(item, (long) n, Long::sum);
            room -= n;
            return n;
        }

        public int remove(String dimension, int x, int y, int z, String item, int count) {
            Map<String, Long> c = containers.get(key(dimension, x, y, z));
            if (c == null) return 0;
            long have = c.getOrDefault(item, 0L);
            int n = (int) Math.min(have, count);
            if (n <= 0) return 0;
            if (have - n == 0) c.remove(item);
            else c.put(item, have - n);
            return n;
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        world = new FakeWorld();
        server = newServer();
        alice = new SyncServerTest.FakePeer("Alice", SyncServerTest.PLAYER);
    }

    SyncServer newServer() throws IOException {
        SharedStore store = new SharedStore(dir.resolve("world"), SyncLog.NONE);
        store.load();
        SyncServer s = new SyncServer(store, new SyncConfig(), "test", now::get, SyncLog.NONE);
        s.setChestAccess(world, new ChestLinkStore(dir.resolve("world").resolve("chests.json")));
        return s;
    }

    void send(SyncServerTest.FakePeer p, Message m) {
        server.receive(p, Protocol.encode(m));
    }

    void chest(int x, Map<String, Long> items) {
        world.containers.put(FakeWorld.key("minecraft:overworld", x, 64, 0), new HashMap<>(items));
    }

    @Test
    void featureNeedsChestAccess() throws IOException {
        send(alice, new Message.Hello(Protocol.VERSION, "client", 0));
        assertThat(alice.last(Message.ServerFeatures.class).features().chestBuildAllowed()).isTrue();

        SharedStore store = new SharedStore(dir.resolve("other"), SyncLog.NONE);
        store.load();
        SyncServer plain = new SyncServer(store, new SyncConfig(), "test", now::get, SyncLog.NONE);
        SyncServerTest.FakePeer bob = new SyncServerTest.FakePeer("Bob", SyncServerTest.PLAYER);
        plain.receive(bob, Protocol.encode(new Message.Hello(Protocol.VERSION, "client", 0)));
        assertThat(bob.last(Message.ServerFeatures.class).features().chestBuildAllowed()).isFalse();
    }

    @Test
    void linkReportsContentsAndRefusesNonContainers() {
        chest(1, Map.of("minecraft:oak_planks", 64L, "minecraft:stone", 12L));
        send(alice, new Message.Hello(Protocol.VERSION, "client", 0));
        assertThat(alice.last(Message.ChestContents.class).entries()).isEmpty();

        send(alice, new Message.ChestLink("minecraft:overworld", 5, 64, 0, true));
        assertThat(alice.last(Message.Notice.class).error()).isTrue();

        send(alice, new Message.ChestLink("minecraft:overworld", 1, 64, 0, true));
        Message.ChestContents c = alice.last(Message.ChestContents.class);
        assertThat(c.reset()).isTrue();
        assertThat(c.entries()).singleElement().satisfies(e -> {
            assertThat(e.valid()).isTrue();
            assertThat(e.items()).containsEntry("minecraft:oak_planks", 64L).containsEntry("minecraft:stone", 12L);
        });

        send(alice, new Message.ChestLink("minecraft:overworld", 1, 64, 0, false));
        assertThat(alice.last(Message.ChestContents.class).entries()).isEmpty();
    }

    /** A container that counts how often it is read. */
    static final class CountingWorld extends FakeWorld {
        int reads;

        @Override
        public Map<String, Long> contents(String dimension, int x, int y, int z) {
            reads++;
            return super.contents(dimension, x, y, z);
        }
    }

    @Test
    void chestsAreReadOnEventsNotEveryTick() throws IOException {
        CountingWorld counting = new CountingWorld();
        world = counting;
        server = newServer();
        chest(1, Map.of("minecraft:stone", 10L));
        send(alice, new Message.Hello(Protocol.VERSION, "client", 0));
        send(alice, new Message.ChestLink("minecraft:overworld", 1, 64, 0, true));
        int before = alice.all(Message.ChestContents.class).size();
        int reads = counting.reads;

        // Changed in the world without anyone closing it: not read, nothing sent.
        chest(1, Map.of("minecraft:stone", 3L));
        for (int i = 0; i < 10 * SyncServer.CHEST_REFRESH_TICKS; i++) server.tick();
        assertThat(counting.reads).isEqualTo(reads);
        assertThat(alice.all(Message.ChestContents.class)).hasSize(before);

        // A player closed it: read once, and the new contents go out.
        server.chestChanged("minecraft:overworld", 1, 64, 0);
        assertThat(counting.reads).isEqualTo(reads + 1);
        assertThat(alice.all(Message.ChestContents.class)).hasSize(before + 1);
        assertThat(alice.last(Message.ChestContents.class).entries().get(0).items()).containsEntry("minecraft:stone", 3L);

        // Closed again with nothing changed, or a chest nobody linked: nothing sent.
        server.chestChanged("minecraft:overworld", 1, 64, 0);
        server.chestChanged("minecraft:overworld", 9, 64, 0);
        assertThat(counting.reads).isEqualTo(reads + 2);
        assertThat(alice.all(Message.ChestContents.class)).hasSize(before + 1);
        assertThat(server.linkedChests()).containsExactly(new LinkedChests.Pos("minecraft:overworld", 1, 64, 0));
    }

    @Test
    void closingAChestThatIsntLoadedKeepsWhatWasKnown() throws IOException {
        CountingWorld counting = new CountingWorld();
        world = counting;
        server = newServer();
        chest(1, Map.of("minecraft:stone", 10L));
        send(alice, new Message.Hello(Protocol.VERSION, "client", 0));
        send(alice, new Message.ChestLink("minecraft:overworld", 1, 64, 0, true));
        int lists = alice.all(Message.ChestContents.class).size();

        // Its chunk unloaded (or it was broken): the close reads nothing, nothing goes out, and a restock still counts it.
        counting.containers.clear();
        server.chestChanged("minecraft:overworld", 1, 64, 0);
        assertThat(alice.all(Message.ChestContents.class)).hasSize(lists);
        assertThat(alice.last(Message.ChestContents.class).entries().get(0).items()).containsEntry("minecraft:stone", 10L);
    }

    @Test
    void aRestockSendsTheListOnceAndTicksSendNothingMore() throws IOException {
        CountingWorld counting = new CountingWorld();
        world = counting;
        server = newServer();
        chest(1, Map.of("minecraft:stone", 100L));
        send(alice, new Message.Hello(Protocol.VERSION, "client", 0));
        send(alice, new Message.ChestLink("minecraft:overworld", 1, 64, 0, true));
        send(alice, new Message.ChestRestock("minecraft:stone", 10));
        int lists = alice.all(Message.ChestContents.class).size();
        int reads = counting.reads;

        for (int i = 0; i < 10 * SyncServer.CHEST_REFRESH_TICKS; i++) server.tick();
        assertThat(alice.all(Message.ChestContents.class)).hasSize(lists);
        assertThat(counting.reads).isEqualTo(reads);
        assertThat(alice.last(Message.ChestContents.class).entries().get(0).items()).containsEntry("minecraft:stone", 90L);
    }

    @Test
    void restockSubtractsWithoutReadingAgain() throws IOException {
        CountingWorld counting = new CountingWorld();
        world = counting;
        server = newServer();
        chest(1, Map.of("minecraft:stone", 100L));
        send(alice, new Message.Hello(Protocol.VERSION, "client", 0));
        send(alice, new Message.ChestLink("minecraft:overworld", 1, 64, 0, true));
        int reads = counting.reads;

        send(alice, new Message.ChestRestock("minecraft:stone", 64));
        assertThat(counting.reads).isEqualTo(reads);
        assertThat(alice.last(Message.ChestContents.class).entries().get(0).items()).containsEntry("minecraft:stone", 36L);
    }

    @Test
    void restockTakesFromSeveralChests() {
        chest(1, Map.of("minecraft:stone", 10L));
        chest(2, Map.of("minecraft:stone", 100L, "minecraft:dirt", 5L));
        send(alice, new Message.Hello(Protocol.VERSION, "client", 0));
        send(alice, new Message.ChestLink("minecraft:overworld", 1, 64, 0, true));
        send(alice, new Message.ChestLink("minecraft:overworld", 2, 64, 0, true));

        send(alice, new Message.ChestRestock("minecraft:stone", 64));
        assertThat(world.inventories.get(alice.id)).containsEntry("minecraft:stone", 64L);
        assertThat(world.contents("minecraft:overworld", 1, 64, 0)).doesNotContainKey("minecraft:stone");
        assertThat(world.contents("minecraft:overworld", 2, 64, 0)).containsEntry("minecraft:stone", 46L);

        int notices = alice.all(Message.Notice.class).size();
        send(alice, new Message.ChestRestock("minecraft:glass", 64));
        assertThat(alice.all(Message.Notice.class)).hasSize(notices + 1);
    }

    @Test
    void linksSurviveRestart() throws IOException {
        chest(1, Map.of("minecraft:stone", 10L));
        send(alice, new Message.Hello(Protocol.VERSION, "client", 0));
        send(alice, new Message.ChestLink("minecraft:overworld", 1, 64, 0, true));

        server = newServer();
        alice.inbox.clear();
        send(alice, new Message.Hello(Protocol.VERSION, "client", 0));
        assertThat(alice.last(Message.ChestContents.class).entries()).hasSize(1);
    }

    @Test
    void manyChestsArePaged() {
        send(alice, new Message.Hello(Protocol.VERSION, "client", 0));
        for (int i = 0; i < ChestLinkStore.MAX_PER_PLAYER; i++) {
            Map<String, Long> items = new HashMap<>();
            for (int k = 0; k < 54; k++) items.put("minecraft:some_long_item_name_number_" + k, (long) k + 1);
            chest(i, items);
            send(alice, new Message.ChestLink("minecraft:overworld", i, 64, 0, true));
        }
        alice.inbox.clear();
        send(alice, new Message.ChestLink("minecraft:overworld", 0, 64, 0, true));
        List<Message.ChestContents> pages = alice.all(Message.ChestContents.class);
        assertThat(pages.size()).isGreaterThan(1);
        assertThat(pages.get(0).reset()).isTrue();
        assertThat(pages.stream().mapToInt(p -> p.entries().size()).sum()).isEqualTo(ChestLinkStore.MAX_PER_PLAYER);

        send(alice, new Message.ChestLink("minecraft:overworld", 999, 64, 0, true));
        assertThat(alice.last(Message.Notice.class).error()).isTrue();
    }

    @Test
    void appFilesReplaceSharedPlacementsOfTheSameName() throws IOException {
        send(alice, new Message.Hello(Protocol.VERSION, "client", 0));
        String v1 = server.addFromApp("Tower.bdproj", new byte[]{1, 2, 3}, "Resource Tracker");
        send(alice, new Message.PlacementCreate(1, v1, SyncServerTest.POSE));
        UUID id = alice.last(Message.PlacementCreated.class).id();

        String v2 = server.addFromApp("Tower.bdproj", new byte[]{4, 5, 6}, "Resource Tracker");
        assertThat(v2).isNotEqualTo(v1);
        assertThat(server.store().placement(id).hash()).isEqualTo(v2);
        assertThat(alice.last(Message.PlacementUpdate.class).placement().hash()).isEqualTo(v2);
        assertThat(server.store().schematic(v2).uploader()).isEqualTo(SyncServer.APP_UPLOADER);
    }

    @Test
    void clientKeepsServerChests() {
        List<byte[]> sent = new ArrayList<>();
        SyncClient c = new SyncClient(sent::add, null, null, new SyncClient.Listener() {
            public void changed() {
            }

            public void notice(boolean error, String message) {
            }
        }, "c", UUID.randomUUID());
        long v = c.chestsVersion();
        c.receive(Protocol.encode(new Message.ChestContents(true, List.of(
                new Message.ChestEntry("minecraft:overworld", 1, 2, 3, true, Map.of("minecraft:stone", 4L))))));
        c.receive(Protocol.encode(new Message.ChestContents(false, List.of(
                new Message.ChestEntry("minecraft:overworld", 1, 2, 3, true, Map.of("minecraft:stone", 9L))))));
        assertThat(c.chestsVersion()).isGreaterThan(v);
        assertThat(c.chests()).singleElement().satisfies(e -> assertThat(e.items()).containsEntry("minecraft:stone", 9L));
        // Without a server nothing is sent.
        c.linkChest("minecraft:overworld", 1, 2, 3, true);
        assertThat(sent).isEmpty();
        assertThat(new LinkedChests.Pos("minecraft:overworld", 1, 2, 3).toString()).isEqualTo("1, 2, 3");
    }
}
