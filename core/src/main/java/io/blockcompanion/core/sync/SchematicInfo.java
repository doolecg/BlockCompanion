package io.blockcompanion.core.sync;

import java.io.IOException;
import java.util.UUID;

/**
 * A schematic in the server's shared space.
 *
 * @param hash         SHA-256 of the file (hex); the file's identity
 * @param name         file name as uploaded, e.g. {@code Pinecrest Watchtower.litematic}
 * @param size         file size in bytes
 * @param uploader     player who uploaded it (counts against their quota)
 * @param uploaderName their name when they uploaded it
 * @param uploadedAt   upload time, epoch milliseconds
 */
public record SchematicInfo(String hash, String name, long size, UUID uploader, String uploaderName, long uploadedAt) {

    void write(Wire.Out out) {
        out.hash(hash).string(name).varLong(size).uuid(uploader).string(uploaderName).i64(uploadedAt);
    }

    static SchematicInfo read(Wire.In in) throws IOException {
        return new SchematicInfo(in.hash(), in.string(), in.varLong(), in.uuid(), in.string(), in.i64());
    }
}
