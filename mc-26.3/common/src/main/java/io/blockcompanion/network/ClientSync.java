package io.blockcompanion.network;

import io.blockcompanion.client.BlockCompanionClient;
import io.blockcompanion.client.LoadedPlacement;
import io.blockcompanion.client.screen.SharedScreen;
import io.blockcompanion.core.library.SchematicLibrary;
import io.blockcompanion.core.model.BlockPos;
import io.blockcompanion.core.placement.Placement;
import io.blockcompanion.core.sync.ClientPlacementModel;
import io.blockcompanion.core.sync.Hashes;
import io.blockcompanion.core.sync.PlacementPose;
import io.blockcompanion.core.sync.SyncClient;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Client side of the shared space (client only). Wraps the core {@link SyncClient} with what it needs from the game: a
 * sender over the {@code blockcompanion:main} payload, the schematic library as storage (downloads go to
 * {@code schematics/shared/}), and the player's placement through {@link ClientPlacementModel}. The platforms' sync
 * client entry points call {@link #init()}, {@link #tick} every client tick and {@link #receive} for each payload.
 */
public final class ClientSync {
    /** Hellos sent per connection before giving up (a server without BlockCompanion never answers). */
    private static final int HELLO_TRIES = 5;
    private static SyncClient client;
    private static ClientPacketListener lastConnection;
    private static int helloTries, helloCooldown;
    /**
     * Development check: with the environment variable {@code BLOCKCOMPANION_SYNC_SELFTEST=1}, the client shares its
     * loaded placement once the server answers, so a dev run exercises upload, placement and link end to end.
     */
    private static final boolean SELF_TEST = "1".equals(System.getenv("BLOCKCOMPANION_SYNC_SELFTEST"));
    private static boolean selfTestDone;

    private ClientSync() {
    }

    /** Creates the sync client; the player's id is read here, so call it once the game is up (the first tick does). */
    public static void init() {
        if (client != null) return;
        UUID self = Minecraft.getInstance().getUser().getProfileId();
        client = new SyncClient(ClientSync::send, new LibraryStorage(), new PlacementBridge(), new Listener(), "BlockCompanion client", self);
    }

    /** The sync client, or null before {@link #init()}. */
    public static SyncClient client() {
        return client;
    }

    public static void receive(byte[] data) {
        if (client != null) client.receive(data);
    }

    public static void tick(Minecraft mc) {
        init();
        while (SyncKeys.SHARED != null && SyncKeys.SHARED.consumeClick()) mc.gui.setScreen(new SharedScreen(mc.gui.screen()));
        ClientPacketListener conn = mc.getConnection();
        if (conn != lastConnection) {
            lastConnection = conn;
            client.reset();
            helloTries = 0;
            helloCooldown = 20;
        }
        if (conn == null || mc.player == null) return;
        if (!client.serverPresent() && helloTries < HELLO_TRIES && --helloCooldown <= 0) {
            helloCooldown = 60;
            if (SyncNetwork.canSendToServer.getAsBoolean()) {
                helloTries++;
                client.sendHello();
            }
        }
        client.tick();
        // Which placement follows the shared one: the selected one at the moment the link was made.
        if (client.linked() == null) BlockCompanionClient.setSharedLink(null);
        else if (BlockCompanionClient.sharedLink() == null) BlockCompanionClient.setSharedLink(BlockCompanionClient.active());
        if (SELF_TEST && !selfTestDone && client.serverPresent() && client.features().syncEnabled() && BlockCompanionClient.placement() != null) {
            selfTestDone = true;
            BlockCompanionClient.LOG.info("Sync self-test: sharing {}", BlockCompanionClient.placement().name());
            client.shareCurrent();
        }
    }

    private static void send(byte[] data) {
        ClientPacketListener conn = Minecraft.getInstance().getConnection();
        if (conn != null && SyncNetwork.canSendToServer.getAsBoolean()) conn.send(new ServerboundCustomPayloadPacket(new SyncPayload(data)));
    }

    private static final class Listener implements SyncClient.Listener {
        @Override
        public void changed() {
            if (Minecraft.getInstance().gui.screen() instanceof SharedScreen s) s.refresh();
        }

        @Override
        public void notice(boolean error, String message) {
            BlockCompanionClient.LOG.info("Shared space: {}{}", error ? "(error) " : "", message);
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;
            mc.player.sendSystemMessage(Component.literal("[BlockCompanion] ").withStyle(ChatFormatting.DARK_AQUA)
                    .append(Component.literal(message).withStyle(error ? ChatFormatting.RED : ChatFormatting.GRAY)));
        }
    }

    /**
     * The player's placements as the sync client sees them: the one following a shared placement, or else the selected
     * one (what "Share mine" shares). Loading a shared placement adds a new one.
     */
    private static final class PlacementBridge implements ClientPlacementModel {
        private static LoadedPlacement target() {
            LoadedPlacement linked = BlockCompanionClient.sharedLink();
            return linked != null ? linked : BlockCompanionClient.active();
        }

        @Override
        public Loaded current() {
            LoadedPlacement lp = target();
            Minecraft mc = Minecraft.getInstance();
            if (lp == null || mc.level == null || !BlockCompanionClient.here(lp)) return null;
            Placement p = lp.placement;
            BlockPos o = p.origin();
            PlacementPose pose = new PlacementPose(lp.dimension, o.x(), o.y(), o.z(), p.rotation(), p.mirrored());
            // A reload makes a new Placement whose own counter restarts, so mix in which object it is.
            long version = ((long) System.identityHashCode(p) << 32) ^ p.version();
            return new Loaded(p.name(), pose, version);
        }

        @Override
        public String load(String libraryName, PlacementPose pose) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) return "Not in a world";
            String here = mc.level.dimension().identifier().toString();
            if (!here.equals(pose.dimension())) return "That placement is in " + pose.dimension() + "; go there to load it";
            if (!BlockCompanionClient.load(libraryName)) return "Could not load " + libraryName;
            BlockCompanionClient.setSharedLink(BlockCompanionClient.active());
            apply(pose);
            return null;
        }

        @Override
        public void apply(PlacementPose pose) {
            LoadedPlacement lp = target();
            if (lp == null) return;
            lp.placement.setOrientation(pose.rotation(), pose.mirrored());
            lp.placement.moveTo(new BlockPos(pose.x(), pose.y(), pose.z()));
            BlockCompanionClient.changed(lp);
        }

        @Override
        public String reload(String libraryName) {
            LoadedPlacement lp = target();
            if (lp == null) return "Nothing loaded to update";
            return BlockCompanionClient.reload(lp, libraryName);
        }
    }

    /** The schematic library as the sync client's storage, with SHA-256s cached by path, size and modification time. */
    private static final class LibraryStorage implements SyncClient.Storage {
        private record Cached(long size, long modified, String hash) {
        }

        private final Map<Path, Cached> cache = new HashMap<>();

        private SchematicLibrary library() {
            return BlockCompanionClient.library();
        }

        private String hashOf(Path file, long size) throws IOException {
            long modified = Files.getLastModifiedTime(file).toMillis();
            Cached c = cache.get(file);
            if (c != null && c.size == size && c.modified == modified) return c.hash;
            String hash = Hashes.sha256(Files.readAllBytes(file));
            cache.put(file, new Cached(size, modified, hash));
            return hash;
        }

        @Override
        public String findLocal(String hash) {
            try {
                for (SchematicLibrary.Entry e : library().list()) {
                    if (hashOf(e.path(), e.size()).equals(hash)) return e.name();
                }
            } catch (IOException e) {
                BlockCompanionClient.LOG.warn("Could not scan the schematic library: {}", e.toString());
            }
            return null;
        }

        @Override
        public String saveDownloaded(String name, String hash, byte[] data) throws IOException {
            String clean = name.replaceAll("[\\\\/:*?\"<>|]", "_");
            if (clean.isBlank()) clean = Hashes.shortHash(hash) + ".schem";
            String rel = "shared/" + clean;
            Path target = library().resolve(rel);
            if (Files.exists(target) && !hashOf(target, Files.size(target)).equals(hash)) {
                int dot = clean.lastIndexOf('.');
                String base = dot > 0 ? clean.substring(0, dot) : clean, ext = dot > 0 ? clean.substring(dot) : "";
                rel = "shared/" + base + "-" + Hashes.shortHash(hash) + ext.toLowerCase(Locale.ROOT);
                target = library().resolve(rel);
            }
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".part");
            Files.write(tmp, data);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            return rel;
        }

        @Override
        public byte[] read(String libraryName) throws IOException {
            return Files.readAllBytes(library().resolve(libraryName));
        }
    }
}
