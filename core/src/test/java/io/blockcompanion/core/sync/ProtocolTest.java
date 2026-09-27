package io.blockcompanion.core.sync;

import io.blockcompanion.core.autobuild.AutoBuildOptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.BitSet;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProtocolTest {
    static final String HASH = Hashes.sha256("hello".getBytes());
    static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    static final AutoBuildOptions OPTIONS = new AutoBuildOptions(40, AutoBuildOptions.Replace.CLEAR, AutoBuildOptions.Order.NEAREST, false,
            true, 24, "minecraft:oak_planks");

    static SharedPlacement placement() {
        return new SharedPlacement(UUID.randomUUID(), HASH, "Pinecrest Watchtower.litematic", "minecraft:overworld", -120, 64, 3_000_001,
                3, true, A, "Alice", true, B, "Bob", 42);
    }

    static SchematicInfo info() {
        return new SchematicInfo(HASH, "tower.schem", 123_456, A, "Alice", 1_790_000_000_000L);
    }

    static Message roundTrip(Message m) throws IOException {
        byte[] data = Protocol.encode(m);
        assertThat(data.length).isLessThanOrEqualTo(Protocol.MAX_MESSAGE);
        return Protocol.decode(data);
    }

    @Test
    void everyRecordMessageRoundTrips() throws IOException {
        BitSet have = new BitSet();
        have.set(0);
        have.set(7);
        have.set(200);
        Features f = new Features(true, 8 << 20, -1, 5000, Protocol.CHUNK_SIZE, Permission.mask(EnumSet.allOf(Permission.class)),
                true, 5, 20, false, true, false);
        PlacementPose pose = new PlacementPose("minecraft:the_nether", 1, -64, -7, 2, false);
        List<Message> all = List.of(
                new Message.Hello(Protocol.VERSION, "BlockCompanion-Fabric 0.1.0", 5L),
                new Message.ServerFeatures(f),
                new Message.Notice(true, "No"),
                new Message.SchematicList(true, List.of(info(), info())),
                new Message.SchematicAdded(info()),
                new Message.SchematicRemoved(HASH),
                new Message.SchematicDelete(HASH),
                new Message.UploadBegin(3, HASH, "a.nbt", 99_999),
                new Message.UploadStatus(3, Message.UploadCode.ACCEPTED, have, ""),
                new Message.DownloadRequest(4, HASH),
                new Message.DownloadBegin(4, HASH, "a.nbt", 99_999, true),
                new Message.PlacementList(false, List.of(placement())),
                new Message.PlacementUpdate(placement()),
                new Message.PlacementRemoved(A),
                new Message.PlacementCreate(9, HASH, pose),
                new Message.PlacementCreated(9, B),
                new Message.PlacementMove(A, pose),
                new Message.PlacementDelete(A),
                new Message.Lock(A, Message.LockKind.EDIT, true),
                new Message.ChestLink("minecraft:overworld", -3, 64, 12, true),
                new Message.ChestContents(true, List.of(new Message.ChestEntry("minecraft:overworld", -3, 64, 12, true,
                        java.util.Map.of("minecraft:stone", 1234L, "minecraft:oak_log", 5L)))),
                new Message.ChestRestock("minecraft:stone", 64),
                new Message.AutoBuildStart(HASH, pose, 5, List.of(new io.blockcompanion.core.chests.LinkedChests.Pos("minecraft:overworld", -3, 64, 12))),
                new Message.AutoBuildControl(A, Message.AutoBuildAction.RESUME),
                new Message.AutoBuildStatus(B, HASH, pose, "Castle", io.blockcompanion.core.autobuild.AutoBuildJob.State.PAUSED, 340, 1200, 300,
                        40, "Out of glass"),
                new Message.AutoBuildBegin(HASH, pose, OPTIONS, List.of(new io.blockcompanion.core.chests.LinkedChests.Pos("minecraft:overworld", -3, 64, 12))),
                new Message.AutoBuildSetOptions(A, OPTIONS.withOnlyItem("")));
        for (Message m : all) assertThat(roundTrip(m)).as(m.type().name()).isEqualTo(m);
        // Every type but CHUNK (byte[] has no value equality) is covered above.
        assertThat(all.stream().map(Message::type).distinct().count()).isEqualTo(Protocol.Type.values().length - 1);
    }

    @Test
    void chunkRoundTrips() throws IOException {
        byte[] data = new byte[Protocol.CHUNK_SIZE];
        Arrays.fill(data, (byte) 7);
        Message.Chunk c = (Message.Chunk) roundTrip(new Message.Chunk(12, 34, data));
        assertThat(c.transfer()).isEqualTo(12);
        assertThat(c.index()).isEqualTo(34);
        assertThat(c.data()).isEqualTo(data);
    }

    @Test
    void placementWithoutEditorRoundTrips() throws IOException {
        SharedPlacement p = placement().withEditor(null, "");
        assertThat(((Message.PlacementUpdate) roundTrip(new Message.PlacementUpdate(p))).placement()).isEqualTo(p);
    }

    @Test
    void featuresSkipUnknownKeysAndDefaultMissingOnes() throws IOException {
        Wire.Out out = new Wire.Out();
        out.varInt(Protocol.VERSION).varInt(Protocol.Type.FEATURES.id).varInt(3);
        out.string("sync").i64(1).string("from_the_future").i64(77).string("auto_place").i64(1);
        Features f = ((Message.ServerFeatures) Protocol.decode(out.toByteArray())).features();
        assertThat(f.syncEnabled()).isTrue();
        assertThat(f.autoPlaceAllowed()).isTrue();
        assertThat(f.maxFileSize()).isZero();
        assertThat(f.chunkSize()).isEqualTo(Protocol.CHUNK_SIZE);
        // A server from before easy_place existed allows it.
        assertThat(f.easyPlaceAllowed()).isTrue();
        assertThat(f.easyPlaceAutoAllowed()).isTrue();
    }

    @Test
    void easyPlaceAutoFollowsEasyPlaceUnlessSent() throws IOException {
        Wire.Out out = new Wire.Out();
        out.varInt(Protocol.VERSION).varInt(Protocol.Type.FEATURES.id).varInt(1);
        out.string("easy_place").i64(0);
        Features f = ((Message.ServerFeatures) Protocol.decode(out.toByteArray())).features();
        assertThat(f.easyPlaceAllowed()).isFalse();
        assertThat(f.easyPlaceAutoAllowed()).isFalse();

        out = new Wire.Out();
        out.varInt(Protocol.VERSION).varInt(Protocol.Type.FEATURES.id).varInt(2);
        out.string("easy_place").i64(1).string("easy_place_auto").i64(0);
        f = ((Message.ServerFeatures) Protocol.decode(out.toByteArray())).features();
        assertThat(f.easyPlaceAllowed()).isTrue();
        assertThat(f.easyPlaceAutoAllowed()).isFalse();

        // Auto mode never outlives easy place itself.
        assertThat(new Features(true, 0, 0, 0, Protocol.CHUNK_SIZE, 0, false, 0, 0, false, false, false, true).easyPlaceAutoAllowed())
                .isFalse();
    }

    @Test
    void autoBuildOptionsSkipUnknownKeysAndDefaultMissingOnes() throws IOException {
        Wire.Out out = new Wire.Out();
        out.varInt(Protocol.VERSION).varInt(Protocol.Type.AUTOBUILD_SET_OPTIONS.id).uuid(A);
        out.varInt(3).string("rate").i64(12).string("from_the_future").i64(9).string("order").i64(1);
        out.varInt(1).string("some_text_later").string("hi");
        AutoBuildOptions o = ((Message.AutoBuildSetOptions) Protocol.decode(out.toByteArray())).options();
        assertThat(o.blocksPerSecond()).isEqualTo(12);
        assertThat(o.order()).isEqualTo(AutoBuildOptions.Order.TOP_DOWN);
        // Missing keys read as the defaults: never breaking, air ignored, pausing on missing items, whole schematic.
        assertThat(o.replace()).isEqualTo(AutoBuildOptions.Replace.KEEP);
        assertThat(o.ignoreAir()).isTrue();
        assertThat(o.skipMissing()).isFalse();
        assertThat(o.radius()).isZero();
        assertThat(o.onlyItem()).isEmpty();
    }

    @Test
    void autoBuildFeaturesDefaultToNothingFromOlderServers() throws IOException {
        Wire.Out out = new Wire.Out();
        out.varInt(Protocol.VERSION).varInt(Protocol.Type.FEATURES.id).varInt(2);
        out.string("auto_build").i64(1).string("auto_build_rate").i64(20);
        Features f = ((Message.ServerFeatures) Protocol.decode(out.toByteArray())).features();
        assertThat(f.autoBuildAllowed()).isTrue();
        assertThat(f.autoBuildOptions()).isFalse();
        assertThat(f.autoBuildReplace()).isEqualTo(AutoBuildOptions.Replace.KEEP);
        assertThat(f.autoBuildMaxRadius()).isZero();
        // Such a server only takes the speed.
        assertThat(f.cap(OPTIONS)).isEqualTo(AutoBuildOptions.ofRate(20));

        Features now = new Features(true, 0, 0, 0, Protocol.CHUNK_SIZE, 0, false, 0, 0, false, true, true, true, true, 100, true,
                AutoBuildOptions.Replace.SOLID, 32);
        Features back = ((Message.ServerFeatures) roundTrip(new Message.ServerFeatures(now))).features();
        assertThat(back).isEqualTo(now);
        assertThat(back.cap(OPTIONS)).isEqualTo(OPTIONS.withReplace(AutoBuildOptions.Replace.SOLID).withRadius(24));
        assertThat(back.cap(OPTIONS.withRadius(0))).isEqualTo(OPTIONS.withReplace(AutoBuildOptions.Replace.SOLID).withRadius(32));
    }

    @Test
    void typeIdsAreStable() {
        assertThat(Protocol.Type.HELLO.id).isZero();
        assertThat(Protocol.Type.CHUNK.id).isEqualTo(9);
        assertThat(Protocol.Type.LOCK.id).isEqualTo(19);
        assertThat(Protocol.Type.byId(99)).isNull();
    }

    @Test
    void helloIsReadableAcrossVersionsButOtherMessagesAreNot() throws IOException {
        Wire.Out hello = new Wire.Out();
        hello.varInt(Protocol.VERSION + 1).varInt(0).varInt(Protocol.VERSION + 1).string("future").i64(0).string("extra field");
        Message.Hello h = (Message.Hello) Protocol.decode(hello.toByteArray());
        assertThat(h.protocolVersion()).isEqualTo(Protocol.VERSION + 1);

        Wire.Out other = new Wire.Out();
        other.varInt(Protocol.VERSION + 1).varInt(Protocol.Type.PLACEMENT_DELETE.id).uuid(A);
        assertThatThrownBy(() -> Protocol.decode(other.toByteArray())).isInstanceOf(Protocol.VersionMismatchException.class);
    }

    @Test
    void malformedMessagesAreRefused() {
        byte[] good = Protocol.encode(new Message.PlacementMove(A, new PlacementPose("minecraft:overworld", 1, 2, 3, 0, false)));
        assertThatThrownBy(() -> Protocol.decode(Arrays.copyOf(good, good.length - 1))).isInstanceOf(IOException.class);
        byte[] trailing = Arrays.copyOf(good, good.length + 1);
        assertThatThrownBy(() -> Protocol.decode(trailing)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> Protocol.decode(new byte[]{1, 99})).isInstanceOf(IOException.class);
        // A list claiming far more entries than bytes left.
        Wire.Out out = new Wire.Out();
        out.varInt(Protocol.VERSION).varInt(Protocol.Type.SCHEMATIC_LIST.id).bool(true).varInt(1_000_000);
        assertThatThrownBy(() -> Protocol.decode(out.toByteArray())).isInstanceOf(IOException.class);
    }

    @Test
    void oversizedChunkIsRefused() {
        Wire.Out out = new Wire.Out();
        out.varInt(Protocol.VERSION).varInt(Protocol.Type.CHUNK.id).varInt(1).varInt(0).bytes(new byte[Protocol.CHUNK_SIZE + 1]);
        assertThatThrownBy(() -> Protocol.decode(out.toByteArray())).isInstanceOf(IOException.class);
    }

    @Test
    void permissionMaskRoundTrips() {
        EnumSet<Permission> set = EnumSet.of(Permission.USE, Permission.LOCK);
        assertThat(Permission.fromMask(Permission.mask(set))).isEqualTo(set);
        assertThat(Permission.UPLOAD.node()).isEqualTo("blockcompanion.upload");
    }
}
