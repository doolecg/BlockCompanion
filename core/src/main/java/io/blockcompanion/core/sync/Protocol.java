package io.blockcompanion.core.sync;

import java.io.IOException;

/**
 * The messages mods and the Paper plugin exchange on the {@code blockcompanion:main} channel, and their envelope.
 *
 * <p>Every message is {@code [protocol version: varint][type id: varint][body]}. The body of each type is written and
 * read by its record in {@link Message}. Only {@link Type#HELLO} is readable across protocol versions; anything else
 * from a different version is refused with {@link VersionMismatchException}.
 *
 * <p>Size limits: a serverbound custom payload must stay under 32 KiB (vanilla and NeoForge) and a Bukkit plugin message
 * under 32766 bytes, while clientbound mod payloads may be 1 MiB. The protocol keeps every message, both ways, under
 * {@link #MAX_MESSAGE}: file data travels in {@link #CHUNK_SIZE} pieces and long lists are paged.
 */
public final class Protocol {
    public static final String CHANNEL = "blockcompanion:main";
    public static final int VERSION = 1;
    /** Bytes of file data in one {@link Message.Chunk}. */
    public static final int CHUNK_SIZE = 16 * 1024;
    /** Largest encoded message either side sends (well under the 32766-byte Bukkit plugin-message limit). */
    public static final int MAX_MESSAGE = 30_000;
    /** Longest notice text sent, in characters. */
    public static final int MAX_NOTICE_CHARS = 256;

    private Protocol() {
    }

    /**
     * Message types and their wire ids. Ids are part of the wire format: never renumber, only append.
     * The arrow says who sends it: C for the client, S for the server.
     */
    public enum Type {
        /** C&harr;S handshake. */
        HELLO(0),
        /** S&rarr;C what the server allows. */
        FEATURES(1),
        /** S&rarr;C a text answer. */
        NOTICE(2),
        /** S&rarr;C a page of the schematic list. */
        SCHEMATIC_LIST(3),
        /** S&rarr;C a schematic was added. */
        SCHEMATIC_ADDED(4),
        /** S&rarr;C a schematic was removed. */
        SCHEMATIC_REMOVED(5),
        /** C&rarr;S delete a schematic. */
        SCHEMATIC_DELETE(6),
        /** C&rarr;S start an upload. */
        UPLOAD_BEGIN(7),
        /** S&rarr;C upload accepted/refused/finished. */
        UPLOAD_STATUS(8),
        /** C&harr;S a piece of a file. */
        CHUNK(9),
        /** C&rarr;S ask for a file. */
        DOWNLOAD_REQUEST(10),
        /** S&rarr;C a download starts. */
        DOWNLOAD_BEGIN(11),
        /** S&rarr;C a page of the placement list. */
        PLACEMENT_LIST(12),
        /** S&rarr;C a placement was added or changed. */
        PLACEMENT_UPDATE(13),
        /** S&rarr;C a placement was removed. */
        PLACEMENT_REMOVED(14),
        /** C&rarr;S share a placement. */
        PLACEMENT_CREATE(15),
        /** S&rarr;C the id of a placement the client asked for. */
        PLACEMENT_CREATED(16),
        /** C&rarr;S move a placement. */
        PLACEMENT_MOVE(17),
        /** C&rarr;S delete a placement. */
        PLACEMENT_DELETE(18),
        /** C&rarr;S take or drop a lock. */
        LOCK(19),
        /** C&rarr;S link or unlink a chest. */
        CHEST_LINK(20),
        /** S&rarr;C a page of what is in the player's linked chests. */
        CHEST_CONTENTS(21),
        /** C&rarr;S fetch items from the linked chests into the inventory. */
        CHEST_RESTOCK(22),
        /** C&rarr;S start AutoBuild on a placement. */
        AUTOBUILD_START(23),
        /** C&rarr;S pause, resume or stop an AutoBuild. */
        AUTOBUILD_CONTROL(24),
        /** S&rarr;C how an AutoBuild stands. */
        AUTOBUILD_STATUS(25),
        /** C&rarr;S start AutoBuild on a placement with options (servers that announce {@code auto_build_options}). */
        AUTOBUILD_BEGIN(26),
        /** C&rarr;S change a running AutoBuild's options. */
        AUTOBUILD_SET_OPTIONS(27);

        public final int id;

        Type(int id) {
            this.id = id;
        }

        private static final Type[] BY_ID;

        static {
            int max = 0;
            for (Type t : values()) max = Math.max(max, t.id);
            BY_ID = new Type[max + 1];
            for (Type t : values()) BY_ID[t.id] = t;
        }

        public static Type byId(int id) {
            return id >= 0 && id < BY_ID.length ? BY_ID[id] : null;
        }
    }

    /** The other side speaks a different protocol version. */
    public static final class VersionMismatchException extends IOException {
        public final int version;

        public VersionMismatchException(int version) {
            super("Protocol version " + version + ", this side speaks " + VERSION);
            this.version = version;
        }
    }

    /** Envelope plus body. */
    public static byte[] encode(Message m) {
        Wire.Out out = new Wire.Out();
        out.varInt(VERSION).varInt(m.type().id);
        m.writeBody(out);
        return out.toByteArray();
    }

    /**
     * Reads one message.
     *
     * @throws VersionMismatchException for a non-hello message of another protocol version
     * @throws IOException              for an unknown type or malformed body
     */
    public static Message decode(byte[] data) throws IOException {
        Wire.In in = new Wire.In(data);
        int version = in.varInt();
        Type type = Type.byId(in.varInt());
        if (type == null) throw new IOException("Unknown message type");
        if (version != VERSION && type != Type.HELLO) throw new VersionMismatchException(version);
        Message m = switch (type) {
            case HELLO -> Message.Hello.read(in);
            case FEATURES -> Message.ServerFeatures.read(in);
            case NOTICE -> Message.Notice.read(in);
            case SCHEMATIC_LIST -> Message.SchematicList.read(in);
            case SCHEMATIC_ADDED -> Message.SchematicAdded.read(in);
            case SCHEMATIC_REMOVED -> Message.SchematicRemoved.read(in);
            case SCHEMATIC_DELETE -> Message.SchematicDelete.read(in);
            case UPLOAD_BEGIN -> Message.UploadBegin.read(in);
            case UPLOAD_STATUS -> Message.UploadStatus.read(in);
            case CHUNK -> Message.Chunk.read(in);
            case DOWNLOAD_REQUEST -> Message.DownloadRequest.read(in);
            case DOWNLOAD_BEGIN -> Message.DownloadBegin.read(in);
            case PLACEMENT_LIST -> Message.PlacementList.read(in);
            case PLACEMENT_UPDATE -> Message.PlacementUpdate.read(in);
            case PLACEMENT_REMOVED -> Message.PlacementRemoved.read(in);
            case PLACEMENT_CREATE -> Message.PlacementCreate.read(in);
            case PLACEMENT_CREATED -> Message.PlacementCreated.read(in);
            case PLACEMENT_MOVE -> Message.PlacementMove.read(in);
            case PLACEMENT_DELETE -> Message.PlacementDelete.read(in);
            case LOCK -> Message.Lock.read(in);
            case CHEST_LINK -> Message.ChestLink.read(in);
            case CHEST_CONTENTS -> Message.ChestContents.read(in);
            case CHEST_RESTOCK -> Message.ChestRestock.read(in);
            case AUTOBUILD_START -> Message.AutoBuildStart.read(in);
            case AUTOBUILD_CONTROL -> Message.AutoBuildControl.read(in);
            case AUTOBUILD_STATUS -> Message.AutoBuildStatus.read(in);
            case AUTOBUILD_BEGIN -> Message.AutoBuildBegin.read(in);
            case AUTOBUILD_SET_OPTIONS -> Message.AutoBuildSetOptions.read(in);
        };
        // A hello from a newer version may carry more after the fields we know; anything else must end exactly.
        if (type != Type.HELLO && in.remaining() != 0) throw new IOException("Trailing bytes after " + type);
        return m;
    }

    static String clip(String s) {
        if (s == null) return "";
        return s.length() <= MAX_NOTICE_CHARS ? s : s.substring(0, MAX_NOTICE_CHARS - 3) + "...";
    }
}
