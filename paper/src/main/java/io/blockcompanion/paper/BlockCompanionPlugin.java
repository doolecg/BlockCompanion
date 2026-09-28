package io.blockcompanion.paper;

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
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

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
 * Server side of BlockCompanion for Paper, Spigot and Bukkit: the shared space (uploads, shared placements, locks) over
 * plugin messaging on {@code blockcompanion:main}, speaking the same protocol as the Fabric and NeoForge mods. Only the
 * Bukkit API is used. Permissions are the nodes {@code blockcompanion.use/upload/place/lock/admin} (see plugin.yml).
 * Linked chests are read and emptied through {@link PaperChestAccess} (and read again when a player closes one), AutoBuild places blocks through
 * {@link PaperBuildWorld} (who may start it: {@code blockcompanion.autobuild}), and the BlockDesigner link lets BlockCompanion Plugin
 * on the same computer send projects straight into the shared space.
 */
public final class BlockCompanionPlugin extends JavaPlugin implements PluginMessageListener, Listener {
    private SyncServer sync;
    private Path configFile;
    private GameLink link;

    @Override
    public void onEnable() {
        Path data = getDataFolder().toPath();
        configFile = data.resolve("config.properties");
        SyncConfig config = SyncConfig.load(configFile, true);
        // One shared space per server, stored under the main world's name so a new world starts a fresh one.
        List<World> worlds = getServer().getWorlds();
        String level = worlds.isEmpty() ? "world" : worlds.get(0).getName();
        SyncLog log = new SyncLog() {
            public void info(String message) {
                getLogger().info(message);
            }

            public void warn(String message) {
                getLogger().warning(message);
            }
        };
        SharedStore store = new SharedStore(data.resolve("worlds").resolve(safe(level)), log);
        try {
            store.load();
        } catch (IOException e) {
            getLogger().severe("Could not read the shared space in " + store.root() + ": " + e);
        }
        sync = new SyncServer(store, config, "BlockCompanion-Paper " + getDescription().getVersion(), System::currentTimeMillis, log);
        sync.setChestAccess(new PaperChestAccess(), new ChestLinkStore(store.root().resolve("chests.json")));
        sync.setBuildWorld(new PaperBuildWorld());
        link = new GameLink(store.root().resolve("incoming"), InstanceInfo.defaultFolder(), InstanceInfo.SERVER, getServer().getMotd(),
                getServer().getBukkitVersion().split("-")[0], "paper", getDescription().getVersion(), getServer().getWorldContainer().toPath(), new ServerGame(), getLogger()::info);
        link.start();

        getServer().getMessenger().registerOutgoingPluginChannel(this, Protocol.CHANNEL);
        getServer().getMessenger().registerIncomingPluginChannel(this, Protocol.CHANNEL, this);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getScheduler().runTaskTimer(this, () -> {
            sync.tick();
            if (link != null) link.tick();
        }, 1L, 1L);
        getLogger().info("BlockCompanion channel " + Protocol.CHANNEL + " ready (protocol " + Protocol.VERSION + "), "
                + store.schematics().size() + " shared schematics and " + store.placements().size() + " placements in " + store.root());
    }

    @Override
    public void onDisable() {
        getServer().getMessenger().unregisterIncomingPluginChannel(this);
        getServer().getMessenger().unregisterOutgoingPluginChannel(this);
        if (link != null) link.close();
        link = null;
        sync = null;
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!Protocol.CHANNEL.equals(channel) || sync == null) return;
        sync.receive(new PaperPeer(player), message);
    }

    /** A closed container: a linked chest is read once now (nothing polls the chests). */
    @EventHandler
    public void onInventoryClose(InventoryCloseEvent e) {
        if (sync != null) PaperChestAccess.closed(sync, e.getInventory());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        if (sync != null) sync.leave(e.getPlayer().getUniqueId());
        if (link != null) link.statusChanged();
    }

    /** The server as the BlockDesigner link sees it: projects go into the shared space. */
    private final class ServerGame implements GameLink.Game {
        @Override
        public void projectReceived(String relative, Path file, String projectName, boolean open, String fromApp) {
            if (sync == null) return;
            try {
                sync.addFromApp(file.getFileName().toString(), Files.readAllBytes(file), fromApp);
                if (link != null) link.statusChanged();
            } catch (IOException e) {
                getLogger().warning("Could not add " + relative + " from " + fromApp + ": " + e);
            }
        }

        @Override
        public InstanceInfo refresh(InstanceInfo info) {
            List<String> packs = new ArrayList<>();
            String url = getServer().getResourcePack();
            if (url != null && !url.isBlank()) packs.add(url);
            List<World> worlds = getServer().getWorlds();
            return info.withName(getServer().getMotd()).withUpdate(worlds.isEmpty() ? "" : worlds.get(0).getName(), List.of(), packs,
                    Instant.now(), false);
        }

        @Override
        public Map<String, Object> status() {
            Map<String, Object> m = new LinkedHashMap<>();
            if (sync == null) return m;
            m.put("players", getServer().getOnlinePlayers().size());
            List<Object> schematics = new ArrayList<>();
            for (SchematicInfo i : sync.store().schematics()) {
                schematics.add(Map.of("name", i.name(), "sha256", i.hash(), "size", i.size(), "by", i.uploaderName()));
            }
            m.put("schematics", schematics);
            m.put("placements", sync.store().placements().size());
            return m;
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (sync == null) return true;
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            sync.setConfig(SyncConfig.load(configFile, true));
            sender.sendMessage("BlockCompanion config reloaded.");
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("info")) {
            SyncConfig c = sync.config();
            sender.sendMessage("BlockCompanion " + getDescription().getVersion() + " (protocol " + Protocol.VERSION + "): sharing "
                    + (c.enabled ? "on" : "off") + ", " + sync.store().schematics().size() + " schematics ("
                    + sync.store().usedTotal() / 1024 + " of " + c.totalQuota / 1024 + " KiB), "
                    + sync.store().placements().size() + " placements.");
            return true;
        }
        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return args.length == 1 ? List.of("info", "reload") : List.of();
    }

    private static String safe(String raw) {
        String s = raw.replaceAll("[^A-Za-z0-9._-]", "_");
        return s.isEmpty() ? "world" : s;
    }

    /** A Bukkit player as the sync server sees it. */
    private final class PaperPeer implements SyncPeer {
        private final Player player;

        PaperPeer(Player player) {
            this.player = player;
        }

        public UUID id() {
            return player.getUniqueId();
        }

        public String name() {
            return player.getName();
        }

        public boolean has(Permission permission) {
            return player.hasPermission(permission.node());
        }

        public void send(byte[] message) {
            if (!player.isOnline() || !isEnabled()) return;
            // Plugin messages go out from the main thread; the sync server is driven from it already.
            if (Bukkit.isPrimaryThread()) player.sendPluginMessage(BlockCompanionPlugin.this, Protocol.CHANNEL, message);
            else Bukkit.getScheduler().runTask(BlockCompanionPlugin.this,
                    () -> player.sendPluginMessage(BlockCompanionPlugin.this, Protocol.CHANNEL, message));
        }
    }
}
