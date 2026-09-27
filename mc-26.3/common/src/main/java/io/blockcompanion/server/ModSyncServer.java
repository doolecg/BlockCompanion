package io.blockcompanion.server;

import io.blockcompanion.core.link.GameLink;
import io.blockcompanion.core.link.InstanceInfo;
import io.blockcompanion.core.sync.ChestLinkStore;
import io.blockcompanion.core.sync.Permission;
import io.blockcompanion.core.sync.Protocol;
import io.blockcompanion.core.sync.SchematicInfo;
import io.blockcompanion.core.sync.SharedStore;
import io.blockcompanion.core.sync.SyncConfig;
import io.blockcompanion.core.sync.SyncLog;
import io.blockcompanion.core.sync.SyncPeer;
import io.blockcompanion.core.sync.SyncServer;
import io.blockcompanion.network.SyncPayload;
import net.minecraft.SharedConstants;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The shared space on a Fabric or NeoForge server (dedicated, or the integrated one in singleplayer and when a world is
 * opened to LAN). The loader entry points forward server start/stop, ticks, logouts and incoming payloads here; the
 * logic is the core {@link SyncServer}, the same one the Paper plugin runs. Data lives in {@code <world>/blockcompanion/},
 * settings in {@code config/blockcompanion-server.properties}. Linked chests are read and emptied through
 * {@link ModChestAccess}. A dedicated server also opens the BlockDesigner link, so Resource Tracker on the same computer
 * can send projects straight into the shared space. No client classes: safe on a dedicated server.
 */
public final class ModSyncServer {
    private static final Logger LOG = LoggerFactory.getLogger("BlockCompanion");
    private static final SyncLog SYNC_LOG = new SyncLog() {
        public void info(String message) {
            LOG.info(message);
        }

        public void warn(String message) {
            LOG.warn(message);
        }
    };
    private static SyncServer sync;
    private static Path configFile;
    private static GameLink link;
    private static MinecraftServer server;

    private ModSyncServer() {
    }

    public static void start(MinecraftServer s, String software, String loader, String modVersion) {
        server = s;
        configFile = s.getServerDirectory().resolve("config").resolve("blockcompanion-server.properties");
        SyncConfig config = SyncConfig.load(configFile);
        Path root = s.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().resolve("blockcompanion");
        SharedStore store = new SharedStore(root, SYNC_LOG);
        try {
            store.load();
        } catch (IOException e) {
            LOG.error("Could not read the BlockCompanion shared space in {}", root, e);
        }
        sync = new SyncServer(store, config, software, System::currentTimeMillis, SYNC_LOG);
        sync.setChestAccess(new ModChestAccess(s), new ChestLinkStore(root.resolve("chests.json")));
        LOG.info("BlockCompanion shared space ready on {} (protocol {}): {} schematics, {} placements in {}", Protocol.CHANNEL,
                Protocol.VERSION, store.schematics().size(), store.placements().size(), root);
        if (s.isDedicatedServer()) {
            link = new GameLink(root.resolve("incoming"), InstanceInfo.defaultFolder(), InstanceInfo.SERVER, s.getMotd(),
                    SharedConstants.getCurrentVersion().name(), loader, modVersion, s.getServerDirectory(), new ServerGame(), LOG::info);
            link.start();
        }
    }

    public static void stop() {
        if (link != null) link.close();
        link = null;
        sync = null;
        server = null;
    }

    public static void tick() {
        if (sync != null) sync.tick();
        if (link != null) link.tick();
    }

    public static void receive(ServerPlayer player, byte[] data) {
        if (sync != null) sync.receive(new ModPeer(player), data);
    }

    public static void leave(UUID player) {
        if (sync != null) sync.leave(player);
        if (link != null) link.statusChanged();
    }

    /** Re-reads the config file and tells every connected player what changed. */
    public static void reloadConfig() {
        if (sync != null) sync.setConfig(SyncConfig.load(configFile));
    }

    /** The dedicated server as the BlockDesigner link sees it: projects go into the shared space. */
    private static final class ServerGame implements GameLink.Game {
        @Override
        public void projectReceived(String relative, Path file, String projectName, boolean open, String fromApp) {
            if (sync == null) return;
            try {
                String name = file.getFileName().toString();
                sync.addFromApp(name, Files.readAllBytes(file), fromApp);
                if (link != null) link.statusChanged();
            } catch (IOException e) {
                LOG.warn("Could not add {} from {}: {}", relative, fromApp, e.toString());
            }
        }

        @Override
        public InstanceInfo refresh(InstanceInfo info) {
            MinecraftServer s = server;
            if (s == null) return info;
            List<String> packs = new ArrayList<>();
            s.getServerResourcePack().ifPresent(p -> packs.add(p.url()));
            String world = s.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName().toString();
            return info.withName(s.getMotd()).withUpdate(world, List.of(), packs, Instant.now(), false);
        }

        @Override
        public Map<String, Object> status() {
            Map<String, Object> m = new LinkedHashMap<>();
            MinecraftServer s = server;
            if (s == null || sync == null) return m;
            m.put("players", s.getPlayerCount());
            List<Object> schematics = new ArrayList<>();
            for (SchematicInfo i : sync.store().schematics()) {
                schematics.add(Map.of("name", i.name(), "sha256", i.hash(), "size", i.size(), "by", i.uploaderName()));
            }
            m.put("schematics", schematics);
            m.put("placements", sync.store().placements().size());
            return m;
        }
    }

    /** A server player as the sync server sees it: permissions from the config plus operator status. */
    private record ModPeer(ServerPlayer player) implements SyncPeer {
        public UUID id() {
            return player.getUUID();
        }

        public String name() {
            return player.nameAndId().name();
        }

        public boolean has(Permission permission) {
            SyncServer s = sync;
            return s != null && s.config().allows(permission, player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER));
        }

        public void send(byte[] message) {
            if (player.connection != null) player.connection.send(new ClientboundCustomPayloadPacket(new SyncPayload(message)));
        }
    }
}
