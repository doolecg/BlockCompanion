package io.blockcompanion.core.sync;

import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.UUID;

/**
 * Every message of the protocol. {@link Protocol#encode} wraps one in the envelope; {@link Protocol#decode} reads one
 * back. The direction each travels is noted on the type ({@link Protocol.Type}); a receiver ignores messages that do not
 * belong to its side.
 */
public sealed interface Message {

    Protocol.Type type();

    void writeBody(Wire.Out out);

    // ---- handshake ------------------------------------------------------------------------------------------------

    /**
     * Both ways. The client sends it once it can talk to the server; the server answers with its own hello, then
     * {@link ServerFeatures}, the schematic list and the placement list. Its layout never changes, so two sides on
     * different protocol versions can still tell each other apart.
     *
     * @param features capability bits of the sender (none defined yet)
     */
    record Hello(int protocolVersion, String software, long features) implements Message {
        public Protocol.Type type() {
            return Protocol.Type.HELLO;
        }

        public void writeBody(Wire.Out out) {
            out.varInt(protocolVersion).string(software).i64(features);
        }

        static Hello read(Wire.In in) throws IOException {
            return new Hello(in.varInt(), in.string(), in.i64());
        }
    }

    /** Server to client: what the server allows this player. */
    record ServerFeatures(Features features) implements Message {
        public Protocol.Type type() {
            return Protocol.Type.FEATURES;
        }

        public void writeBody(Wire.Out out) {
            features.write(out);
        }

        static ServerFeatures read(Wire.In in) throws IOException {
            return new ServerFeatures(Features.read(in));
        }
    }

    /** Server to client: a free-text answer (an error when {@code error}), shown to the player. */
    record Notice(boolean error, String message) implements Message {
        public Protocol.Type type() {
            return Protocol.Type.NOTICE;
        }

        public void writeBody(Wire.Out out) {
            out.bool(error).string(Protocol.clip(message));
        }

        static Notice read(Wire.In in) throws IOException {
            return new Notice(in.bool(), in.string());
        }
    }

    // ---- schematic list -------------------------------------------------------------------------------------------

    /**
     * Server to client: a page of the shared schematic list. The first page has {@code reset} set, which clears what the
     * client had; later pages add to it. (Pages keep every message under the Bukkit plugin-message limit.)
     */
    record SchematicList(boolean reset, List<SchematicInfo> entries) implements Message {
        public Protocol.Type type() {
            return Protocol.Type.SCHEMATIC_LIST;
        }

        public void writeBody(Wire.Out out) {
            out.bool(reset).varInt(entries.size());
            entries.forEach(e -> e.write(out));
        }

        static SchematicList read(Wire.In in) throws IOException {
            boolean reset = in.bool();
            int n = in.count();
            List<SchematicInfo> list = new ArrayList<>(n);
            for (int i = 0; i < n; i++) list.add(SchematicInfo.read(in));
            return new SchematicList(reset, list);
        }
    }

    /** Server to client: a schematic finished uploading. */
    record SchematicAdded(SchematicInfo info) implements Message {
        public Protocol.Type type() {
            return Protocol.Type.SCHEMATIC_ADDED;
        }

        public void writeBody(Wire.Out out) {
            info.write(out);
        }

        static SchematicAdded read(Wire.In in) throws IOException {
            return new SchematicAdded(SchematicInfo.read(in));
        }
    }

    /** Server to client: a schematic was deleted. */
    record SchematicRemoved(String hash) implements Message {
        public Protocol.Type type() {
            return Protocol.Type.SCHEMATIC_REMOVED;
        }

        public void writeBody(Wire.Out out) {
            out.hash(hash);
        }

        static SchematicRemoved read(Wire.In in) throws IOException {
            return new SchematicRemoved(in.hash());
        }
    }

    /** Client to server: delete a schematic (its uploader or an admin). */
    record SchematicDelete(String hash) implements Message {
        public Protocol.Type type() {
            return Protocol.Type.SCHEMATIC_DELETE;
        }

        public void writeBody(Wire.Out out) {
            out.hash(hash);
        }

        static SchematicDelete read(Wire.In in) throws IOException {
            return new SchematicDelete(in.hash());
        }
    }

    // ---- transfers ------------------------------------------------------------------------------------------------

    /**
     * Client to server: wants to upload a file. {@code transfer} is the client's own number for this upload, echoed in
     * every {@link UploadStatus} and used in its {@link Chunk}s.
     */
    record UploadBegin(int transfer, String hash, String name, long size) implements Message {
        public Protocol.Type type() {
            return Protocol.Type.UPLOAD_BEGIN;
        }

        public void writeBody(Wire.Out out) {
            out.varInt(transfer).hash(hash).string(name).varLong(size);
        }

        static UploadBegin read(Wire.In in) throws IOException {
            return new UploadBegin(in.varInt(), in.hash(), in.string(), in.varLong());
        }
    }

    /** How an upload stands. */
    enum UploadCode {
        /** Go ahead: send every chunk not set in {@code have} (resume: the server may hold some already). */
        ACCEPTED,
        /** The server has this file already; nothing to send. */
        ALREADY_HAVE,
        /** Refused (permission, size, quota, name): see the message. */
        REJECTED,
        /** All chunks arrived and the SHA-256 matched: the schematic is shared. */
        DONE,
        /** All chunks arrived but the SHA-256 did not match; the server dropped what it had. */
        HASH_MISMATCH
    }

    /** Server to client: the answer to an {@link UploadBegin}, and later the result of the upload. */
    record UploadStatus(int transfer, UploadCode code, BitSet have, String message) implements Message {
        public UploadStatus {
            if (have == null) have = new BitSet();
            if (message == null) message = "";
        }

        public Protocol.Type type() {
            return Protocol.Type.UPLOAD_STATUS;
        }

        public void writeBody(Wire.Out out) {
            out.varInt(transfer).varInt(code.ordinal()).bytes(have.toByteArray()).string(Protocol.clip(message));
        }

        static UploadStatus read(Wire.In in) throws IOException {
            int transfer = in.varInt();
            int c = in.varInt();
            if (c < 0 || c >= UploadCode.values().length) throw new IOException("Bad upload code " + c);
            return new UploadStatus(transfer, UploadCode.values()[c], BitSet.valueOf(in.bytes(Protocol.MAX_MESSAGE)), in.string());
        }
    }

    /** Both ways: one piece of a file. Pieces may arrive in any order and more than once. */
    record Chunk(int transfer, int index, byte[] data) implements Message {
        public Protocol.Type type() {
            return Protocol.Type.CHUNK;
        }

        public void writeBody(Wire.Out out) {
            out.varInt(transfer).varInt(index).bytes(data);
        }

        static Chunk read(Wire.In in) throws IOException {
            return new Chunk(in.varInt(), in.varInt(), in.bytes(Protocol.CHUNK_SIZE));
        }
    }

    /** Client to server: send me this file. {@code transfer} is the client's number for the download. */
    record DownloadRequest(int transfer, String hash) implements Message {
        public Protocol.Type type() {
            return Protocol.Type.DOWNLOAD_REQUEST;
        }

        public void writeBody(Wire.Out out) {
            out.varInt(transfer).hash(hash);
        }

        static DownloadRequest read(Wire.In in) throws IOException {
            return new DownloadRequest(in.varInt(), in.hash());
        }
    }

    /** Server to client: the download starts ({@link Chunk}s follow), or {@code found} is false. */
    record DownloadBegin(int transfer, String hash, String name, long size, boolean found) implements Message {
        public Protocol.Type type() {
            return Protocol.Type.DOWNLOAD_BEGIN;
        }

        public void writeBody(Wire.Out out) {
            out.varInt(transfer).hash(hash).string(name).varLong(size).bool(found);
        }

        static DownloadBegin read(Wire.In in) throws IOException {
            return new DownloadBegin(in.varInt(), in.hash(), in.string(), in.varLong(), in.bool());
        }
    }

    // ---- placements -----------------------------------------------------------------------------------------------

    /** Server to client: a page of the shared placements; {@code reset} on the first page clears the client's list. */
    record PlacementList(boolean reset, List<SharedPlacement> entries) implements Message {
        public Protocol.Type type() {
            return Protocol.Type.PLACEMENT_LIST;
        }

        public void writeBody(Wire.Out out) {
            out.bool(reset).varInt(entries.size());
            entries.forEach(e -> e.write(out));
        }

        static PlacementList read(Wire.In in) throws IOException {
            boolean reset = in.bool();
            int n = in.count();
            List<SharedPlacement> list = new ArrayList<>(n);
            for (int i = 0; i < n; i++) list.add(SharedPlacement.read(in));
            return new PlacementList(reset, list);
        }
    }

    /** Server to client: a placement was added or changed (moved, locked, editing lock taken or dropped). */
    record PlacementUpdate(SharedPlacement placement) implements Message {
        public Protocol.Type type() {
            return Protocol.Type.PLACEMENT_UPDATE;
        }

        public void writeBody(Wire.Out out) {
            placement.write(out);
        }

        static PlacementUpdate read(Wire.In in) throws IOException {
            return new PlacementUpdate(SharedPlacement.read(in));
        }
    }

    /** Server to client: a placement was deleted. */
    record PlacementRemoved(UUID id) implements Message {
        public Protocol.Type type() {
            return Protocol.Type.PLACEMENT_REMOVED;
        }

        public void writeBody(Wire.Out out) {
            out.uuid(id);
        }

        static PlacementRemoved read(Wire.In in) throws IOException {
            return new PlacementRemoved(in.uuid());
        }
    }

    /** Client to server: share a placement of an uploaded schematic. {@code request} comes back in {@link PlacementCreated}. */
    record PlacementCreate(int request, String hash, PlacementPose pose) implements Message {
        public Protocol.Type type() {
            return Protocol.Type.PLACEMENT_CREATE;
        }

        public void writeBody(Wire.Out out) {
            out.varInt(request).hash(hash);
            pose.write(out);
        }

        static PlacementCreate read(Wire.In in) throws IOException {
            return new PlacementCreate(in.varInt(), in.hash(), PlacementPose.read(in));
        }
    }

    /** Server to the creating client: the placement for its request exists now, with this id. */
    record PlacementCreated(int request, UUID id) implements Message {
        public Protocol.Type type() {
            return Protocol.Type.PLACEMENT_CREATED;
        }

        public void writeBody(Wire.Out out) {
            out.varInt(request).uuid(id);
        }

        static PlacementCreated read(Wire.In in) throws IOException {
            return new PlacementCreated(in.varInt(), in.uuid());
        }
    }

    /**
     * Client to server: move, turn or mirror a placement. Takes (or renews) the temporary editing lock for the sender;
     * if refused, the server sends back the placement as it is, so the client can snap back.
     */
    record PlacementMove(UUID id, PlacementPose pose) implements Message {
        public Protocol.Type type() {
            return Protocol.Type.PLACEMENT_MOVE;
        }

        public void writeBody(Wire.Out out) {
            out.uuid(id);
            pose.write(out);
        }

        static PlacementMove read(Wire.In in) throws IOException {
            return new PlacementMove(in.uuid(), PlacementPose.read(in));
        }
    }

    /** Client to server: delete a placement. */
    record PlacementDelete(UUID id) implements Message {
        public Protocol.Type type() {
            return Protocol.Type.PLACEMENT_DELETE;
        }

        public void writeBody(Wire.Out out) {
            out.uuid(id);
        }

        static PlacementDelete read(Wire.In in) throws IOException {
            return new PlacementDelete(in.uuid());
        }
    }

    /** The two kinds of lock. */
    enum LockKind {
        /** The owner lock: only the owner and admins may move or delete. Stays until unlocked. */
        OWNER,
        /** The editing lock: someone is moving it; others wait. Expires by itself when they stop. */
        EDIT
    }

    /** Client to server: take ({@code acquire}) or drop a lock. */
    record Lock(UUID id, LockKind kind, boolean acquire) implements Message {
        public Protocol.Type type() {
            return Protocol.Type.LOCK;
        }

        public void writeBody(Wire.Out out) {
            out.uuid(id).varInt(kind.ordinal()).bool(acquire);
        }

        static Lock read(Wire.In in) throws IOException {
            UUID id = in.uuid();
            int k = in.varInt();
            if (k < 0 || k >= LockKind.values().length) throw new IOException("Bad lock kind " + k);
            return new Lock(id, LockKind.values()[k], in.bool());
        }
    }

    // ---- linked chests --------------------------------------------------------------------------------------------

    /** Client to server: link ({@code link}) or unlink the container at a position, for counting and building from. */
    record ChestLink(String dimension, int x, int y, int z, boolean link) implements Message {
        public Protocol.Type type() {
            return Protocol.Type.CHEST_LINK;
        }

        public void writeBody(Wire.Out out) {
            out.string(dimension).i32(x).i32(y).i32(z).bool(link);
        }

        static ChestLink read(Wire.In in) throws IOException {
            return new ChestLink(in.string(), in.i32(), in.i32(), in.i32(), in.bool());
        }
    }

    /**
     * One linked chest as the server sees it.
     *
     * @param valid false when there is no container there (any more); the link stays until the player removes it
     * @param items item id to count
     */
    record ChestEntry(String dimension, int x, int y, int z, boolean valid, java.util.Map<String, Long> items) {
        public ChestEntry {
            items = java.util.Map.copyOf(items);
        }

        void write(Wire.Out out) {
            out.string(dimension).i32(x).i32(y).i32(z).bool(valid).varInt(items.size());
            items.entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).forEach(e -> out.string(e.getKey()).varLong(e.getValue()));
        }

        static ChestEntry read(Wire.In in) throws IOException {
            String dim = in.string();
            int x = in.i32(), y = in.i32(), z = in.i32();
            boolean valid = in.bool();
            int n = in.count();
            java.util.Map<String, Long> items = new java.util.LinkedHashMap<>();
            for (int i = 0; i < n; i++) items.put(in.string(), in.varLong());
            return new ChestEntry(dim, x, y, z, valid, items);
        }
    }

    /**
     * Server to client: a page of the player's linked chests with their contents. {@code reset} (first page of a full
     * list) replaces the client's list; otherwise the entries update or add chests.
     */
    record ChestContents(boolean reset, List<ChestEntry> entries) implements Message {
        public Protocol.Type type() {
            return Protocol.Type.CHEST_CONTENTS;
        }

        public void writeBody(Wire.Out out) {
            out.bool(reset).varInt(entries.size());
            entries.forEach(e -> e.write(out));
        }

        static ChestContents read(Wire.In in) throws IOException {
            boolean reset = in.bool();
            int n = in.count();
            List<ChestEntry> list = new ArrayList<>(n);
            for (int i = 0; i < n; i++) list.add(ChestEntry.read(in));
            return new ChestContents(reset, list);
        }
    }

    /** Client to server: move up to {@code count} of {@code item} from the player's linked chests into their inventory. */
    record ChestRestock(String item, int count) implements Message {
        public Protocol.Type type() {
            return Protocol.Type.CHEST_RESTOCK;
        }

        public void writeBody(Wire.Out out) {
            out.string(item).varInt(count);
        }

        static ChestRestock read(Wire.In in) throws IOException {
            return new ChestRestock(in.string(), in.varInt());
        }
    }

    // ---- AutoBuild ------------------------------------------------------------------------------------------------

    /**
     * Client to server: build this placement from the linked chests, block by block and layer by layer from the bottom.
     * The schematic must be in the shared space already ({@code hash}); {@code chests} are the linked chests to take
     * from (the server only uses those the player really linked). The server checks everything again itself.
     *
     * @param blocksPerSecond the speed the player picked; the server caps it
     */
    record AutoBuildStart(String hash, PlacementPose pose, int blocksPerSecond, List<io.blockcompanion.core.chests.LinkedChests.Pos> chests)
            implements Message {
        public AutoBuildStart {
            chests = List.copyOf(chests);
        }

        public Protocol.Type type() {
            return Protocol.Type.AUTOBUILD_START;
        }

        public void writeBody(Wire.Out out) {
            out.hash(hash);
            pose.write(out);
            out.varInt(blocksPerSecond).varInt(chests.size());
            for (var c : chests) out.string(c.dimension()).i32(c.x()).i32(c.y()).i32(c.z());
        }

        static AutoBuildStart read(Wire.In in) throws IOException {
            String hash = in.hash();
            PlacementPose pose = PlacementPose.read(in);
            int rate = in.varInt();
            int n = in.count();
            List<io.blockcompanion.core.chests.LinkedChests.Pos> chests = new ArrayList<>(n);
            for (int i = 0; i < n; i++) chests.add(new io.blockcompanion.core.chests.LinkedChests.Pos(in.string(), in.i32(), in.i32(), in.i32()));
            return new AutoBuildStart(hash, pose, rate, chests);
        }
    }

    /** What the player does to a running AutoBuild. */
    enum AutoBuildAction {
        PAUSE, RESUME, STOP
    }

    /** Client to server: pause, resume or stop one of the player's AutoBuilds. */
    record AutoBuildControl(UUID job, AutoBuildAction action) implements Message {
        public Protocol.Type type() {
            return Protocol.Type.AUTOBUILD_CONTROL;
        }

        public void writeBody(Wire.Out out) {
            out.uuid(job).varInt(action.ordinal());
        }

        static AutoBuildControl read(Wire.In in) throws IOException {
            UUID job = in.uuid();
            int a = in.varInt();
            if (a < 0 || a >= AutoBuildAction.values().length) throw new IOException("Bad AutoBuild action " + a);
            return new AutoBuildControl(job, AutoBuildAction.values()[a]);
        }
    }

    /**
     * Server to the player who started it: how an AutoBuild stands. Sent when it starts, about twice a second while it
     * runs, and when it pauses, finishes or stops. {@code hash} and {@code pose} say which placement it builds.
     *
     * @param done    steps dealt with (placed, already right or skipped); a door or bed counts once
     * @param message why it waits, paused or stopped, or the finishing line; may be empty
     */
    record AutoBuildStatus(UUID job, String hash, PlacementPose pose, String name, io.blockcompanion.core.autobuild.AutoBuildJob.State state,
                           long done, long total, long placed, long skipped, String message) implements Message {
        public AutoBuildStatus {
            if (message == null) message = "";
        }

        public Protocol.Type type() {
            return Protocol.Type.AUTOBUILD_STATUS;
        }

        public void writeBody(Wire.Out out) {
            out.uuid(job).hash(hash);
            pose.write(out);
            out.string(name).varInt(state.ordinal()).varLong(done).varLong(total).varLong(placed).varLong(skipped).string(Protocol.clip(message));
        }

        static AutoBuildStatus read(Wire.In in) throws IOException {
            UUID job = in.uuid();
            String hash = in.hash();
            PlacementPose pose = PlacementPose.read(in);
            String name = in.string();
            int st = in.varInt();
            var states = io.blockcompanion.core.autobuild.AutoBuildJob.State.values();
            if (st < 0 || st >= states.length) throw new IOException("Bad AutoBuild state " + st);
            return new AutoBuildStatus(job, hash, pose, name, states[st], in.varLong(), in.varLong(), in.varLong(), in.varLong(), in.string());
        }
    }
}
