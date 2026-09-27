package io.blockcompanion.core.sync;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class SyncServerTest {
    @TempDir
    Path dir;

    final AtomicLong now = new AtomicLong(1_000_000);
    SyncConfig config;
    SyncServer server;

    /** A player that records what the server sends it. */
    static final class FakePeer implements SyncPeer {
        final UUID id = UUID.randomUUID();
        final String name;
        final Set<Permission> perms;
        final List<Message> inbox = new ArrayList<>();

        FakePeer(String name, Set<Permission> perms) {
            this.name = name;
            this.perms = perms;
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
            assertThat(message.length).isLessThanOrEqualTo(Protocol.MAX_MESSAGE);
            try {
                inbox.add(Protocol.decode(message));
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        }

        <T extends Message> List<T> all(Class<T> type) {
            return inbox.stream().filter(type::isInstance).map(type::cast).toList();
        }

        <T extends Message> T last(Class<T> type) {
            List<T> l = all(type);
            assertThat(l).as("a " + type.getSimpleName()).isNotEmpty();
            return l.get(l.size() - 1);
        }
    }

    static final Set<Permission> PLAYER = EnumSet.of(Permission.USE, Permission.UPLOAD, Permission.PLACE, Permission.LOCK);
    FakePeer alice, bob, admin;

    @BeforeEach
    void setUp() throws IOException {
        config = new SyncConfig();
        server = newServer();
        alice = new FakePeer("Alice", PLAYER);
        bob = new FakePeer("Bob", PLAYER);
        admin = new FakePeer("Admin", EnumSet.allOf(Permission.class));
    }

    SyncServer newServer() throws IOException {
        SharedStore store = new SharedStore(dir.resolve("world"), SyncLog.NONE);
        store.load();
        return new SyncServer(store, config, "test", now::get, SyncLog.NONE);
    }

    void send(FakePeer p, Message m) {
        server.receive(p, Protocol.encode(m));
    }

    void join(FakePeer p) {
        send(p, new Message.Hello(Protocol.VERSION, "client", 0));
    }

    /** Uploads a whole file (chunks shuffled and doubled); returns the final status. */
    Message.UploadStatus upload(FakePeer p, int transfer, String name, byte[] data) {
        String hash = Hashes.sha256(data);
        send(p, new Message.UploadBegin(transfer, hash, name, data.length));
        Message.UploadStatus st = p.last(Message.UploadStatus.class);
        if (st.code() != Message.UploadCode.ACCEPTED) return st;
        int n = Chunks.count(data.length, Protocol.CHUNK_SIZE);
        for (int i = n - 1; i >= 0; i--) {
            if (st.have().get(i)) continue;
            send(p, new Message.Chunk(transfer, i, Chunks.slice(data, i, Protocol.CHUNK_SIZE)));
            if (i > 0) send(p, new Message.Chunk(transfer, i, Chunks.slice(data, i, Protocol.CHUNK_SIZE)));
        }
        return p.last(Message.UploadStatus.class);
    }

    static final PlacementPose POSE = new PlacementPose("minecraft:overworld", 10, 64, -20, 1, false);

    UUID share(FakePeer p, String hash) {
        send(p, new Message.PlacementCreate(1, hash, POSE));
        return p.last(Message.PlacementCreated.class).id();
    }

    @Test
    void helloSendsFeaturesAndLists() {
        join(alice);
        assertThat(alice.inbox.get(0)).isInstanceOf(Message.Hello.class);
        Features f = alice.last(Message.ServerFeatures.class).features();
        assertThat(f.syncEnabled()).isTrue();
        assertThat(f.can(Permission.UPLOAD)).isTrue();
        assertThat(f.can(Permission.ADMIN)).isFalse();
        assertThat(f.autoPlaceAllowed()).isTrue();
        assertThat(f.easyPlaceAllowed()).isTrue();
        assertThat(alice.last(Message.SchematicList.class).reset()).isTrue();
        assertThat(alice.last(Message.PlacementList.class).reset()).isTrue();
    }

    @Test
    void featuresFollowTheConfig() {
        config.allowAutoPlace = false;
        config.allowEasyPlace = false;
        config.enabled = false;
        join(alice);
        Features f = alice.last(Message.ServerFeatures.class).features();
        assertThat(f.syncEnabled()).isFalse();
        assertThat(f.autoPlaceAllowed()).isFalse();
        assertThat(f.easyPlaceAllowed()).isFalse();
        assertThat(alice.all(Message.SchematicList.class)).isEmpty();
    }

    @Test
    void wrongProtocolVersionGetsOnlyAHello() {
        send(alice, new Message.Hello(Protocol.VERSION + 1, "future", 0));
        assertThat(alice.inbox).hasSize(1);
        assertThat(server.isReady(alice.id)).isFalse();
    }

    @Test
    void messagesBeforeHelloAreIgnored() {
        send(alice, new Message.UploadBegin(1, ProtocolTest.HASH, "a.nbt", 10));
        assertThat(alice.inbox).isEmpty();
    }

    @Test
    void uploadIsStoredAnnouncedAndDeduplicated() throws IOException {
        join(alice);
        join(bob);
        byte[] file = ChunksTest.random(50_000, 7);
        Message.UploadStatus done = upload(alice, 1, "tower.litematic", file);
        assertThat(done.code()).isEqualTo(Message.UploadCode.DONE);
        SchematicInfo added = bob.last(Message.SchematicAdded.class).info();
        assertThat(added.name()).isEqualTo("tower.litematic");
        assertThat(added.uploader()).isEqualTo(alice.id);
        assertThat(server.store().readFile(added.hash())).isEqualTo(file);
        assertThat(alice.last(Message.ServerFeatures.class).features().playerUsed()).isEqualTo(50_000);

        assertThat(upload(bob, 5, "copy.litematic", file).code()).isEqualTo(Message.UploadCode.ALREADY_HAVE);
    }

    @Test
    void acceptsEveryLibraryFormat() {
        for (String n : new String[]{"house.bdproj", "gate.schem", "old.schematic", "tower.litematic", "hut.nbt", "Upper Case.BDPROJ"}) {
            assertThat(SharedStore.allowedName(n)).as(n).isTrue();
        }
        for (String n : new String[]{"house.bdproj.tmp", "project.json", "a/b.bdproj", "", ".."}) {
            assertThat(SharedStore.allowedName(n)).as(n).isFalse();
        }
        join(alice);
        assertThat(upload(alice, 1, "house.bdproj", ChunksTest.random(20_000, 41)).code()).isEqualTo(Message.UploadCode.DONE);
        assertThat(upload(alice, 2, "gate.schem", ChunksTest.random(20_000, 42)).code()).isEqualTo(Message.UploadCode.DONE);
    }

    @Test
    void uploadRefusals() {
        join(alice);
        FakePeer viewer = new FakePeer("Viewer", EnumSet.of(Permission.USE));
        join(viewer);
        assertThat(upload(viewer, 1, "a.nbt", new byte[100]).code()).isEqualTo(Message.UploadCode.REJECTED);
        assertThat(upload(alice, 2, "virus.exe", new byte[100]).code()).isEqualTo(Message.UploadCode.REJECTED);
        assertThat(upload(alice, 3, "../escape.nbt", new byte[100]).code()).isEqualTo(Message.UploadCode.REJECTED);
        config.maxFileSize = 1000;
        Message.UploadStatus big = upload(alice, 4, "big.nbt", new byte[1001]);
        assertThat(big.code()).isEqualTo(Message.UploadCode.REJECTED);
        assertThat(big.message()).startsWith("Too large");
    }

    @Test
    void playerQuotaIsEnforcedButNotForAdmins() {
        config.playerQuota = 60_000;
        join(alice);
        join(admin);
        assertThat(upload(alice, 1, "a.nbt", ChunksTest.random(40_000, 1)).code()).isEqualTo(Message.UploadCode.DONE);
        Message.UploadStatus over = upload(alice, 2, "b.nbt", ChunksTest.random(40_000, 2));
        assertThat(over.code()).isEqualTo(Message.UploadCode.REJECTED);
        assertThat(over.message()).contains("quota");
        assertThat(upload(admin, 3, "c.nbt", ChunksTest.random(40_000, 3)).code()).isEqualTo(Message.UploadCode.DONE);
        assertThat(upload(admin, 4, "d.nbt", ChunksTest.random(40_000, 4)).code()).isEqualTo(Message.UploadCode.DONE);
    }

    @Test
    void uploadResumesAfterReconnect() {
        join(alice);
        byte[] file = ChunksTest.random(5 * Protocol.CHUNK_SIZE, 9);
        String hash = Hashes.sha256(file);
        send(alice, new Message.UploadBegin(1, hash, "big.schem", file.length));
        send(alice, new Message.Chunk(1, 0, Chunks.slice(file, 0, Protocol.CHUNK_SIZE)));
        send(alice, new Message.Chunk(1, 3, Chunks.slice(file, 3, Protocol.CHUNK_SIZE)));
        server.leave(alice.id);
        alice.inbox.clear();

        join(alice);
        send(alice, new Message.UploadBegin(7, hash, "big.schem", file.length));
        Message.UploadStatus st = alice.last(Message.UploadStatus.class);
        assertThat(st.code()).isEqualTo(Message.UploadCode.ACCEPTED);
        BitSet have = st.have();
        assertThat(have.stream().boxed().toList()).containsExactly(0, 3);
        for (int i : new int[]{1, 2, 4}) send(alice, new Message.Chunk(7, i, Chunks.slice(file, i, Protocol.CHUNK_SIZE)));
        assertThat(alice.last(Message.UploadStatus.class).code()).isEqualTo(Message.UploadCode.DONE);
    }

    @Test
    void staleUploadsExpire() {
        config.uploadTimeoutSeconds = 60;
        join(alice);
        byte[] file = ChunksTest.random(3 * Protocol.CHUNK_SIZE, 10);
        String hash = Hashes.sha256(file);
        send(alice, new Message.UploadBegin(1, hash, "a.nbt", file.length));
        send(alice, new Message.Chunk(1, 0, Chunks.slice(file, 0, Protocol.CHUNK_SIZE)));
        now.addAndGet(61_000);
        server.tick();
        send(alice, new Message.UploadBegin(2, hash, "a.nbt", file.length));
        assertThat(alice.last(Message.UploadStatus.class).have().isEmpty()).isTrue();
    }

    @Test
    void damagedUploadIsDropped() {
        join(alice);
        byte[] file = ChunksTest.random(20_000, 11);
        String hash = Hashes.sha256(file);
        byte[] bad = file.clone();
        bad[5] ^= 1;
        send(alice, new Message.UploadBegin(1, hash, "a.nbt", file.length));
        send(alice, new Message.Chunk(1, 0, Chunks.slice(bad, 0, Protocol.CHUNK_SIZE)));
        send(alice, new Message.Chunk(1, 1, Chunks.slice(bad, 1, Protocol.CHUNK_SIZE)));
        assertThat(alice.last(Message.UploadStatus.class).code()).isEqualTo(Message.UploadCode.HASH_MISMATCH);
        assertThat(server.store().schematic(hash)).isNull();
    }

    @Test
    void downloadStreamsChunksOverTicks() {
        join(alice);
        byte[] file = ChunksTest.random(10 * Protocol.CHUNK_SIZE + 3, 12);
        upload(alice, 1, "a.nbt", file);
        join(bob);
        String hash = Hashes.sha256(file);
        send(bob, new Message.DownloadRequest(4, hash));
        Message.DownloadBegin begin = bob.last(Message.DownloadBegin.class);
        assertThat(begin.found()).isTrue();
        assertThat(begin.size()).isEqualTo(file.length);
        Chunks.Assembler a = new Chunks.Assembler(hash, begin.size(), Protocol.CHUNK_SIZE);
        server.tick();
        assertThat(bob.all(Message.Chunk.class)).hasSize(config.chunksPerTick);
        for (int t = 0; t < 5; t++) server.tick();
        for (Message.Chunk c : bob.all(Message.Chunk.class)) a.accept(c.index(), c.data());
        assertThat(a.finish()).isEqualTo(file);

        send(bob, new Message.DownloadRequest(5, Hashes.sha256(new byte[]{1})));
        assertThat(bob.last(Message.DownloadBegin.class).found()).isFalse();
    }

    @Test
    void placementsAreSharedMovedAndEditLocked() {
        join(alice);
        join(bob);
        String hash = Hashes.sha256(ChunksTest.random(1000, 13));
        upload(alice, 1, "tower.nbt", ChunksTest.random(1000, 13));
        UUID id = share(alice, hash);
        SharedPlacement shared = bob.last(Message.PlacementUpdate.class).placement();
        assertThat(shared.pose()).isEqualTo(POSE);
        assertThat(shared.owner()).isEqualTo(alice.id);

        PlacementPose moved = new PlacementPose("minecraft:overworld", 11, 64, -20, 1, false);
        send(bob, new Message.PlacementMove(id, moved));
        SharedPlacement afterMove = alice.last(Message.PlacementUpdate.class).placement();
        assertThat(afterMove.pose()).isEqualTo(moved);
        assertThat(afterMove.editor()).isEqualTo(bob.id);
        assertThat(afterMove.editorName()).isEqualTo("Bob");

        // Alice can't move it while Bob edits: refused, and she gets the current state back.
        alice.inbox.clear();
        send(alice, new Message.PlacementMove(id, POSE));
        assertThat(alice.last(Message.Notice.class).message()).contains("Bob is moving");
        assertThat(alice.last(Message.PlacementUpdate.class).placement().pose()).isEqualTo(moved);

        // Bob stops; the lock lapses and everyone hears.
        now.addAndGet(config.editLockSeconds * 1000L + 1);
        server.tick();
        assertThat(alice.last(Message.PlacementUpdate.class).placement().editor()).isNull();
        send(alice, new Message.PlacementMove(id, POSE));
        assertThat(bob.last(Message.PlacementUpdate.class).placement().pose()).isEqualTo(POSE);
    }

    @Test
    void editLockIsReleasedExplicitlyOrOnLeave() {
        join(alice);
        join(bob);
        byte[] f = ChunksTest.random(500, 14);
        upload(alice, 1, "a.nbt", f);
        UUID id = share(alice, Hashes.sha256(f));
        send(bob, new Message.Lock(id, Message.LockKind.EDIT, true));
        assertThat(alice.last(Message.PlacementUpdate.class).placement().editor()).isEqualTo(bob.id);
        send(alice, new Message.Lock(id, Message.LockKind.EDIT, false));
        assertThat(alice.last(Message.Notice.class).message()).contains("holds the editing lock");
        send(bob, new Message.Lock(id, Message.LockKind.EDIT, false));
        assertThat(alice.last(Message.PlacementUpdate.class).placement().editor()).isNull();

        send(bob, new Message.Lock(id, Message.LockKind.EDIT, true));
        server.leave(bob.id);
        assertThat(alice.last(Message.PlacementUpdate.class).placement().editor()).isNull();
    }

    @Test
    void ownerLockKeepsOthersOutButNotAdmins() {
        join(alice);
        join(bob);
        join(admin);
        byte[] f = ChunksTest.random(500, 15);
        upload(alice, 1, "a.nbt", f);
        UUID id = share(alice, Hashes.sha256(f));
        send(bob, new Message.Lock(id, Message.LockKind.OWNER, true));
        assertThat(bob.last(Message.Notice.class).message()).contains("Only Alice");
        send(alice, new Message.Lock(id, Message.LockKind.OWNER, true));
        assertThat(bob.last(Message.PlacementUpdate.class).placement().locked()).isTrue();

        send(bob, new Message.PlacementDelete(id));
        assertThat(bob.last(Message.Notice.class).message()).contains("locked by Alice");
        assertThat(server.store().placement(id)).isNotNull();

        send(admin, new Message.PlacementDelete(id));
        assertThat(bob.last(Message.PlacementRemoved.class).id()).isEqualTo(id);
    }

    @Test
    void schematicDeleteRules() {
        join(alice);
        join(bob);
        join(admin);
        byte[] f = ChunksTest.random(500, 16);
        String hash = Hashes.sha256(f);
        upload(alice, 1, "a.nbt", f);
        UUID bobs = share(bob, hash);
        send(bob, new Message.SchematicDelete(hash));
        assertThat(bob.last(Message.Notice.class).message()).contains("Only Alice");
        send(alice, new Message.SchematicDelete(hash));
        assertThat(alice.last(Message.Notice.class).message()).contains("Bob still has");
        send(admin, new Message.SchematicDelete(hash));
        assertThat(alice.last(Message.PlacementRemoved.class).id()).isEqualTo(bobs);
        assertThat(alice.last(Message.SchematicRemoved.class).hash()).isEqualTo(hash);
        assertThat(server.store().schematic(hash)).isNull();
    }

    @Test
    void placementLimit() {
        config.maxPlacementsPerPlayer = 1;
        join(alice);
        byte[] f = ChunksTest.random(500, 17);
        upload(alice, 1, "a.nbt", f);
        share(alice, Hashes.sha256(f));
        send(alice, new Message.PlacementCreate(2, Hashes.sha256(f), POSE));
        assertThat(alice.last(Message.Notice.class).message()).contains("limit 1");
    }

    @Test
    void sharedSpaceSurvivesARestart() throws IOException {
        join(alice);
        byte[] f = ChunksTest.random(30_000, 18);
        String hash = Hashes.sha256(f);
        upload(alice, 1, "Pinecrest Watchtower.litematic", f);
        UUID id = share(alice, hash);
        send(alice, new Message.Lock(id, Message.LockKind.OWNER, true));
        send(alice, new Message.PlacementMove(id, new PlacementPose("minecraft:the_end", -5, 70, 5, 3, true)));

        server = newServer();
        SharedPlacement p = server.store().placement(id);
        assertThat(p).isNotNull();
        assertThat(p.pose()).isEqualTo(new PlacementPose("minecraft:the_end", -5, 70, 5, 3, true));
        assertThat(p.locked()).isTrue();
        assertThat(p.editor()).as("editing locks are not saved").isNull();
        assertThat(server.store().schematic(hash).name()).isEqualTo("Pinecrest Watchtower.litematic");
        assertThat(server.store().readFile(hash)).isEqualTo(f);
        FakePeer again = new FakePeer("Alice", PLAYER);
        join(again);
        assertThat(again.last(Message.PlacementList.class).entries()).extracting(SharedPlacement::id).containsExactly(id);
    }

    @Test
    void viewersWithoutUsePermissionGetNothing() {
        FakePeer outsider = new FakePeer("Outsider", EnumSet.noneOf(Permission.class));
        join(outsider);
        assertThat(outsider.last(Message.ServerFeatures.class).features().syncEnabled()).isFalse();
        assertThat(outsider.all(Message.SchematicList.class)).isEmpty();
    }
}
