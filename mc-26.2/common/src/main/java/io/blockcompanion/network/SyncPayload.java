package io.blockcompanion.network;

import io.blockcompanion.core.sync.Protocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * The one custom payload on {@code blockcompanion:main}. Its body is a raw core-protocol message with no framing of its
 * own, exactly what a Bukkit plugin message on the same channel carries, so one client works with the mod server and
 * the Paper plugin alike.
 */
public record SyncPayload(byte[] data) implements CustomPacketPayload {
    public static final Type<SyncPayload> TYPE = new Type<>(Identifier.parse(Protocol.CHANNEL));
    public static final StreamCodec<FriendlyByteBuf, SyncPayload> CODEC = CustomPacketPayload.codec(SyncPayload::write, SyncPayload::read);

    private static SyncPayload read(FriendlyByteBuf buf) {
        int n = buf.readableBytes();
        if (n > Protocol.MAX_MESSAGE + 1024) throw new IllegalArgumentException("BlockCompanion payload too large: " + n);
        byte[] b = new byte[n];
        buf.readBytes(b);
        return new SyncPayload(b);
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeBytes(data);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
