package io.blockcompanion.core.sync;

import io.blockcompanion.core.chests.LinkedChests;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * The server side of the shared space, the same on Fabric, NeoForge and Paper. The platform feeds it every message on
 * {@code blockcompanion:main} ({@link #receive}), tells it when a player leaves ({@link #leave}) and calls
 * {@link #tick()} once per server tick; it answers through {@link SyncPeer#send}. All methods are synchronized, so the
 * platform may call from its network thread, though the main thread is expected.
 */
public final class SyncServer {
    /** Unfinished uploads kept at once, over all players (each holds its file in memory until done). */
    static final int MAX_PENDING_UPLOADS = 16;
    /** Uploads one player may run at once. */
    static final int MAX_UPLOADS_PER_PLAYER = 2;
    /** Downloads queued for one player at once. */
    static final int MAX_DOWNLOADS_PER_PLAYER = 4;

    private final SharedStore store;
    private SyncConfig config;
    private final String software;
    private final LongSupplier clock;
    private final SyncLog log;
    private final Map<UUID, Session> sessions = new LinkedHashMap<>();
    /** Unfinished uploads by file hash: a second uploader of the same file, or a reconnect, resumes the same one. */
    private final Map<String, PendingUpload> uploads = new HashMap<>();
    /** When each placement's editing lock lapses. */
    private final Map<UUID, Long> editExpiry = new HashMap<>();
    /** The world, for linked chests; null where the platform can't read containers. */
    private ChestAccess chests;
    private ChestLinkStore chestLinks;
    private int chestTicks;
    /** Ticks between two looks at every player's linked chests. */
    static final int CHEST_REFRESH_TICKS = 40;
    /** Most items one restock may move. */
    static final int MAX_RESTOCK = 64 * 9;

    private static final class Session {
        /** Updated on every message: platforms may hand over a fresh wrapper (e.g. after a respawn). */
        SyncPeer peer;
        boolean ready;
        /** The client's upload transfer numbers, to the file hash. */
        final Map<Integer, String> uploadTransfers = new HashMap<>();
        final ArrayDeque<OutgoingDownload> downloads = new ArrayDeque<>();
        /** The chest list last sent, to send again only when something changed. */
        List<Message.ChestEntry> chestsSent = List.of();

        Session(SyncPeer peer) {
            this.peer = peer;
        }
    }

    private record PendingUpload(Chunks.Assembler assembler, String name, UUID uploader, String uploaderName) {
    }

    private static final class OutgoingDownload {
        final int transfer;
        final byte[] data;
        final int count;
        int next;

        OutgoingDownload(int transfer, byte[] data) {
            this.transfer = transfer;
            this.data = data;
            this.count = Chunks.count(data.length, Protocol.CHUNK_SIZE);
        }
    }

    public SyncServer(SharedStore store, SyncConfig config, String software, LongSupplier clock, SyncLog log) {
        this.store = store;
        this.config = config;
        this.software = software;
        this.clock = clock;
        this.log = log;
    }

    /**
     * Turns on linked chests: {@code access} reads and takes from containers, {@code links} keeps who linked what.
     * Without this, chest messages are answered with a notice.
     */
    public synchronized void setChestAccess(ChestAccess access, ChestLinkStore links) {
        this.chests = access;
        this.chestLinks = links;
        links.load();
    }

    public synchronized SyncConfig config() {
        return config;
    }

    public SharedStore store() {
        return store;
    }

    /** Swaps the config (after a reload) and tells every player what changed. */
    public synchronized void setConfig(SyncConfig config) {
        this.config = config;
        for (Session s : sessions.values()) if (s.ready) sendFeatures(s);
    }

    /** Re-sends a player's features, e.g. after their permissions changed. */
    public synchronized void refreshFeatures(UUID player) {
        Session s = sessions.get(player);
        if (s != null && s.ready) sendFeatures(s);
    }

    // ---- connection -----------------------------------------------------------------------------------------------

    /** One message from a player. Malformed messages are logged and dropped. */
    public synchronized void receive(SyncPeer peer, byte[] data) {
        Session s = sessions.computeIfAbsent(peer.id(), k -> new Session(peer));
        s.peer = peer;
        Message m;
        try {
            m = Protocol.decode(data);
        } catch (Protocol.VersionMismatchException e) {
            return; // They sent a hello first, which already told them.
        } catch (IOException e) {
            log.warn("Bad BlockCompanion message from " + peer.name() + ": " + e.getMessage());
            return;
        }
        try {
            handle(s, m);
        } catch (IOException e) {
            log.warn("BlockCompanion could not save the shared space: " + e);
            notice(s, true, "The server could not save that: " + e.getMessage());
        }
    }

    /** The player left: forget their session and drop the editing locks they held. Their unfinished uploads stay for a while. */
    public synchronized void leave(UUID player) {
        if (sessions.remove(player) == null) return;
        for (SharedPlacement p : List.copyOf(store.placements())) {
            if (player.equals(p.editor())) releaseEdit(p);
        }
    }

    public synchronized boolean isReady(UUID player) {
        Session s = sessions.get(player);
        return s != null && s.ready;
    }

    /** Once per server tick: stream downloads, expire editing locks and stale uploads. */
    public synchronized void tick() {
        long now = clock.getAsLong();
        for (Session s : sessions.values()) {
            int budget = config.chunksPerTick;
            while (budget > 0 && !s.downloads.isEmpty()) {
                OutgoingDownload d = s.downloads.peek();
                s.peer.send(Protocol.encode(new Message.Chunk(d.transfer, d.next, Chunks.slice(d.data, d.next, Protocol.CHUNK_SIZE))));
                budget--;
                if (++d.next >= d.count) s.downloads.poll();
            }
        }
        for (Iterator<Map.Entry<UUID, Long>> it = editExpiry.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Long> e = it.next();
            if (now < e.getValue()) continue;
            it.remove();
            SharedPlacement p = store.placement(e.getKey());
            if (p != null && p.editor() != null) putAndBroadcast(p.withEditor(null, ""));
        }
        long timeout = config.uploadTimeoutSeconds * 1000L;
        uploads.values().removeIf(u -> now - u.assembler().lastActivity() > timeout);
        if (chests != null && ++chestTicks >= CHEST_REFRESH_TICKS) {
            chestTicks = 0;
            for (Session s : sessions.values()) if (s.ready) sendChests(s, false);
        }
    }

    // ---- dispatch -------------------------------------------------------------------------------------------------

    private void handle(Session s, Message m) throws IOException {
        if (m instanceof Message.Hello h) {
            hello(s, h);
            return;
        }
        if (!s.ready || !config.enabled) return;
        LockRules.Actor actor = actor(s.peer);
        if (!actor.has(Permission.USE)) return;
        switch (m) {
            case Message.UploadBegin u -> uploadBegin(s, actor, u);
            case Message.Chunk c -> uploadChunk(s, c);
            case Message.DownloadRequest d -> download(s, d);
            case Message.SchematicDelete d -> deleteSchematic(s, actor, d.hash());
            case Message.PlacementCreate c -> createPlacement(s, actor, c);
            case Message.PlacementMove mv -> movePlacement(s, actor, mv);
            case Message.PlacementDelete d -> deletePlacement(s, actor, d.id());
            case Message.Lock l -> lock(s, actor, l);
            case Message.ChestLink l -> chestLink(s, l);
            case Message.ChestRestock r -> chestRestock(s, r);
            default -> {
                // Server-to-client types coming the wrong way: ignore.
            }
        }
    }

    private LockRules.Actor actor(SyncPeer peer) {
        Set<Permission> perms = EnumSet.noneOf(Permission.class);
        for (Permission p : Permission.values()) if (peer.has(p)) perms.add(p);
        return new LockRules.Actor(peer.id(), peer.name(), perms);
    }

    private void hello(Session s, Message.Hello h) {
        s.peer.send(Protocol.encode(new Message.Hello(Protocol.VERSION, software, 0L)));
        if (h.protocolVersion() != Protocol.VERSION) {
            s.ready = false;
            log.info(s.peer.name() + " has BlockCompanion protocol " + h.protocolVersion() + ", this server speaks " + Protocol.VERSION);
            return;
        }
        if (!s.ready) log.info(s.peer.name() + " connected with " + h.software() + " (BlockCompanion protocol " + h.protocolVersion() + ")");
        s.ready = true;
        sendFeatures(s);
        if (!config.enabled || !s.peer.has(Permission.USE)) return;
        for (Message page : Chunks.pages(new ArrayList<>(store.schematics()), Message.SchematicList::new)) s.peer.send(Protocol.encode(page));
        for (Message page : Chunks.pages(new ArrayList<>(store.placements()), Message.PlacementList::new)) s.peer.send(Protocol.encode(page));
        s.chestsSent = List.of();
        if (chestsAllowed()) sendChests(s, true);
    }

    /** The features one player gets: the server's switches plus their own permissions and quota. */
    Features featuresFor(SyncPeer peer) {
        LockRules.Actor a = actor(peer);
        return new Features(config.enabled && a.has(Permission.USE), config.maxFileSize, a.admin() ? -1 : config.playerQuota,
                store.usedBy(peer.id()), Protocol.CHUNK_SIZE, Permission.mask(a.permissions()), config.allowAutoPlace,
                config.autoPlaceRange, config.autoPlaceRate, config.allowCreativeFill, chestsAllowed(),
                config.allowEasyPlace);
    }

    private void sendFeatures(Session s) {
        s.peer.send(Protocol.encode(new Message.ServerFeatures(featuresFor(s.peer))));
    }

    // ---- uploads --------------------------------------------------------------------------------------------------

    /**
     * Whether {@code actor} may add a file of {@code size} bytes: null if so, else why not.
     *
     * @param pendingOthers bytes of other unfinished uploads (they count against the total)
     */
    static String checkQuota(SyncConfig config, LockRules.Actor actor, long size, long usedByActor, long usedTotal, long pendingOthers) {
        if (size <= 0) return "Empty file";
        if (size > config.maxFileSize) return "Too large: " + kib(size) + " (the server allows " + kib(config.maxFileSize) + ")";
        if (!actor.admin() && usedByActor + size > config.playerQuota) {
            return "Over your quota: " + kib(usedByActor) + " of " + kib(config.playerQuota) + " used";
        }
        if (usedTotal + pendingOthers + size > config.totalQuota) return "The server's shared space is full";
        return null;
    }

    private static String kib(long bytes) {
        return (bytes + 1023) / 1024 + " KiB";
    }

    private void uploadBegin(Session s, LockRules.Actor actor, Message.UploadBegin u) {
        if (!actor.has(Permission.UPLOAD)) {
            status(s, u.transfer(), Message.UploadCode.REJECTED, "You may not upload schematics");
            return;
        }
        if (!SharedStore.allowedName(u.name())) {
            status(s, u.transfer(), Message.UploadCode.REJECTED, "Not a schematic file name: " + u.name());
            return;
        }
        if (store.schematic(u.hash()) != null) {
            status(s, u.transfer(), Message.UploadCode.ALREADY_HAVE, "Already shared as " + store.schematic(u.hash()).name());
            return;
        }
        PendingUpload pending = uploads.get(u.hash());
        if (pending != null && pending.assembler().size() != u.size()) {
            status(s, u.transfer(), Message.UploadCode.REJECTED, "Size does not match an upload of the same file in progress");
            return;
        }
        if (pending == null) {
            long pendingBytes = 0;
            for (PendingUpload p : uploads.values()) pendingBytes += p.assembler().size();
            String quota = checkQuota(config, actor, u.size(), store.usedBy(actor.id()), store.usedTotal(), pendingBytes);
            if (quota != null) {
                status(s, u.transfer(), Message.UploadCode.REJECTED, quota);
                return;
            }
            if (uploads.size() >= MAX_PENDING_UPLOADS) {
                status(s, u.transfer(), Message.UploadCode.REJECTED, "The server is busy with other uploads; try again soon");
                return;
            }
        }
        if (!s.uploadTransfers.containsValue(u.hash()) && s.uploadTransfers.size() >= MAX_UPLOADS_PER_PLAYER) {
            status(s, u.transfer(), Message.UploadCode.REJECTED, "Finish your other uploads first");
            return;
        }
        if (pending == null) {
            pending = new PendingUpload(new Chunks.Assembler(u.hash(), u.size(), Protocol.CHUNK_SIZE), u.name(), actor.id(), actor.name());
            uploads.put(u.hash(), pending);
        }
        pending.assembler().touch(clock.getAsLong());
        s.uploadTransfers.values().remove(u.hash());
        s.uploadTransfers.put(u.transfer(), u.hash());
        s.peer.send(Protocol.encode(new Message.UploadStatus(u.transfer(), Message.UploadCode.ACCEPTED, pending.assembler().have(), "")));
    }

    private void uploadChunk(Session s, Message.Chunk c) throws IOException {
        String hash = s.uploadTransfers.get(c.transfer());
        PendingUpload pending = hash == null ? null : uploads.get(hash);
        if (pending == null) return;
        Chunks.Assembler a = pending.assembler();
        a.touch(clock.getAsLong());
        if (a.accept(c.index(), c.data()) != Chunks.Assembler.Result.COMPLETE) return;
        byte[] data = a.finish();
        uploads.remove(hash);
        if (data == null) {
            finishTransfers(hash, Message.UploadCode.HASH_MISMATCH, "The file arrived damaged (SHA-256 mismatch); upload it again");
            return;
        }
        long pendingOthers = 0;
        for (PendingUpload p : uploads.values()) pendingOthers += p.assembler().size();
        if (store.usedTotal() + pendingOthers + data.length > config.totalQuota) {
            finishTransfers(hash, Message.UploadCode.REJECTED, "The server's shared space is full");
            return;
        }
        SchematicInfo info = new SchematicInfo(hash, pending.name(), data.length, pending.uploader(), pending.uploaderName(), clock.getAsLong());
        store.addSchematic(info, data);
        log.info(pending.uploaderName() + " shared " + info.name() + " (" + kib(info.size()) + ", " + Hashes.shortHash(hash) + ")");
        finishTransfers(hash, Message.UploadCode.DONE, "Shared " + info.name());
        broadcast(new Message.SchematicAdded(info));
        refreshFeatures(pending.uploader());
    }

    /** Tells everyone uploading {@code hash} how it ended and forgets their transfer. */
    private void finishTransfers(String hash, Message.UploadCode code, String message) {
        for (Session other : sessions.values()) {
            for (Iterator<Map.Entry<Integer, String>> it = other.uploadTransfers.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<Integer, String> e = it.next();
                if (!e.getValue().equals(hash)) continue;
                it.remove();
                status(other, e.getKey(), code, message);
            }
        }
    }

    private void status(Session s, int transfer, Message.UploadCode code, String message) {
        s.peer.send(Protocol.encode(new Message.UploadStatus(transfer, code, new BitSet(), message)));
    }

    // ---- downloads ------------------------------------------------------------------------------------------------

    private void download(Session s, Message.DownloadRequest d) throws IOException {
        SchematicInfo info = store.schematic(d.hash());
        if (info == null) {
            s.peer.send(Protocol.encode(new Message.DownloadBegin(d.transfer(), d.hash(), "", 0, false)));
            return;
        }
        if (s.downloads.size() >= MAX_DOWNLOADS_PER_PLAYER) {
            notice(s, true, "Too many downloads at once; wait for the others to finish");
            s.peer.send(Protocol.encode(new Message.DownloadBegin(d.transfer(), d.hash(), info.name(), 0, false)));
            return;
        }
        byte[] data = store.readFile(d.hash());
        s.peer.send(Protocol.encode(new Message.DownloadBegin(d.transfer(), d.hash(), info.name(), data.length, true)));
        s.downloads.add(new OutgoingDownload(d.transfer(), data));
    }

    // ---- schematics -----------------------------------------------------------------------------------------------

    private void deleteSchematic(Session s, LockRules.Actor actor, String hash) throws IOException {
        SchematicInfo info = store.schematic(hash);
        if (info == null) return;
        if (!actor.admin() && !info.uploader().equals(actor.id())) {
            notice(s, true, "Only " + info.uploaderName() + " or an admin can delete " + info.name());
            return;
        }
        List<SharedPlacement> using = new ArrayList<>();
        for (SharedPlacement p : store.placements()) if (p.hash().equals(hash)) using.add(p);
        if (!actor.admin()) {
            for (SharedPlacement p : using) {
                if (!p.owner().equals(actor.id())) {
                    notice(s, true, p.ownerName() + " still has a placement of " + info.name());
                    return;
                }
            }
        }
        for (SharedPlacement p : using) removePlacement(p.id());
        store.removeSchematic(hash);
        broadcast(new Message.SchematicRemoved(hash));
        refreshFeatures(info.uploader());
        notice(s, false, "Deleted " + info.name());
    }

    // ---- placements -----------------------------------------------------------------------------------------------

    private static boolean validPose(PlacementPose p) {
        return p.dimension() != null && !p.dimension().isBlank() && p.dimension().length() <= 128
                && Math.abs((long) p.x()) <= 30_000_000L && Math.abs((long) p.z()) <= 30_000_000L && Math.abs((long) p.y()) <= 20_000L;
    }

    private void createPlacement(Session s, LockRules.Actor actor, Message.PlacementCreate c) throws IOException {
        SchematicInfo info = store.schematic(c.hash());
        if (info == null) {
            notice(s, true, "Upload the schematic before sharing a placement of it");
            return;
        }
        String reason = LockRules.canCreate(actor, store.placementsOwnedBy(actor.id()), config.maxPlacementsPerPlayer);
        if (reason == null && !validPose(c.pose())) reason = "That position is outside the world";
        if (reason != null) {
            notice(s, true, reason);
            return;
        }
        PlacementPose pose = c.pose();
        SharedPlacement p = new SharedPlacement(UUID.randomUUID(), info.hash(), info.name(), pose.dimension(), pose.x(), pose.y(),
                pose.z(), pose.rotation(), pose.mirrored(), actor.id(), actor.name(), false, null, "", 0);
        store.putPlacement(p);
        s.peer.send(Protocol.encode(new Message.PlacementCreated(c.request(), p.id())));
        broadcast(new Message.PlacementUpdate(p));
    }

    private long expiry(UUID id) {
        return editExpiry.getOrDefault(id, 0L);
    }

    private void movePlacement(Session s, LockRules.Actor actor, Message.PlacementMove mv) throws IOException {
        SharedPlacement p = store.placement(mv.id());
        if (p == null) {
            s.peer.send(Protocol.encode(new Message.PlacementRemoved(mv.id())));
            return;
        }
        long now = clock.getAsLong();
        String reason = LockRules.canModify(p, actor, expiry(p.id()), now);
        if (reason == null && !validPose(mv.pose())) reason = "That position is outside the world";
        if (reason != null) {
            notice(s, true, reason);
            s.peer.send(Protocol.encode(new Message.PlacementUpdate(p)));
            return;
        }
        SharedPlacement moved = p.withPose(mv.pose());
        if (!actor.id().equals(p.editor())) moved = moved.withEditor(actor.id(), actor.name());
        editExpiry.put(p.id(), now + config.editLockSeconds * 1000L);
        putAndBroadcast(moved);
    }

    private void deletePlacement(Session s, LockRules.Actor actor, UUID id) throws IOException {
        SharedPlacement p = store.placement(id);
        if (p == null) return;
        String reason = LockRules.canDelete(p, actor, expiry(id), clock.getAsLong());
        if (reason != null) {
            notice(s, true, reason);
            return;
        }
        removePlacement(id);
    }

    private void removePlacement(UUID id) throws IOException {
        store.removePlacement(id);
        editExpiry.remove(id);
        broadcast(new Message.PlacementRemoved(id));
    }

    private void lock(Session s, LockRules.Actor actor, Message.Lock l) throws IOException {
        SharedPlacement p = store.placement(l.id());
        if (p == null) return;
        long now = clock.getAsLong();
        String reason;
        if (l.kind() == Message.LockKind.OWNER) {
            reason = LockRules.canOwnerLock(p, actor);
            if (reason == null && p.locked() != l.acquire()) putAndBroadcast(p.withLocked(l.acquire()));
        } else if (l.acquire()) {
            reason = LockRules.canModify(p, actor, expiry(p.id()), now);
            if (reason == null) {
                editExpiry.put(p.id(), now + config.editLockSeconds * 1000L);
                if (!actor.id().equals(p.editor())) putAndBroadcast(p.withEditor(actor.id(), actor.name()));
            }
        } else {
            reason = LockRules.canReleaseEdit(p, actor);
            if (reason == null && p.editor() != null) releaseEdit(p);
        }
        if (reason != null) notice(s, true, reason);
    }

    private void releaseEdit(SharedPlacement p) {
        editExpiry.remove(p.id());
        putAndBroadcast(p.withEditor(null, ""));
    }

    private void putAndBroadcast(SharedPlacement p) {
        try {
            store.putPlacement(p);
        } catch (IOException e) {
            log.warn("BlockCompanion could not save placements: " + e);
        }
        broadcast(new Message.PlacementUpdate(p));
    }

    // ---- linked chests -----------------------------------------------------------------------------------------

    private boolean chestsAllowed() {
        return config.allowChestBuild && chests != null;
    }

    private void chestLink(Session s, Message.ChestLink l) throws IOException {
        if (!chestsAllowed()) {
            notice(s, true, "This server doesn't allow building from linked chests");
            return;
        }
        LinkedChests.Pos pos = new LinkedChests.Pos(l.dimension(), l.x(), l.y(), l.z());
        UUID id = s.peer.id();
        if (l.link()) {
            if (!chests.isContainer(l.dimension(), l.x(), l.y(), l.z())) {
                notice(s, true, "There is no chest at " + pos);
                return;
            }
            if (!chestLinks.add(id, pos)) {
                notice(s, true, "You can link at most " + ChestLinkStore.MAX_PER_PLAYER + " chests");
                return;
            }
        } else {
            chestLinks.remove(id, pos);
        }
        chestLinks.save();
        sendChests(s, true);
    }

    private void chestRestock(Session s, Message.ChestRestock r) {
        if (!chestsAllowed()) return;
        int want = Math.max(1, Math.min(MAX_RESTOCK, r.count()));
        int moved = 0;
        for (LinkedChests.Pos p : chestLinks.of(s.peer.id())) {
            if (moved >= want) break;
            moved += chests.take(s.peer.id(), p.dimension(), p.x(), p.y(), p.z(), r.item(), want - moved);
        }
        if (moved == 0) notice(s, true, "No " + r.item() + " in your linked chests, or no room in your inventory");
        sendChests(s, false);
    }

    /** Sends the player's chests with their contents when they changed ({@code force}: always). */
    private void sendChests(Session s, boolean force) {
        if (chests == null || chestLinks == null) return;
        List<LinkedChests.Pos> mine = chestLinks.of(s.peer.id());
        if (mine.isEmpty() && s.chestsSent.isEmpty() && !force) return;
        List<Message.ChestEntry> now = new ArrayList<>();
        for (LinkedChests.Pos p : mine) {
            Map<String, Long> items = chests.contents(p.dimension(), p.x(), p.y(), p.z());
            if (items == null) {
                // Not loaded (or gone): keep what was sent before for this chest.
                Message.ChestEntry old = s.chestsSent.stream().filter(e -> e.dimension().equals(p.dimension()) && e.x() == p.x()
                        && e.y() == p.y() && e.z() == p.z()).findFirst().orElse(null);
                now.add(old != null ? old : new Message.ChestEntry(p.dimension(), p.x(), p.y(), p.z(), false, Map.of()));
            } else {
                now.add(new Message.ChestEntry(p.dimension(), p.x(), p.y(), p.z(), true, items));
            }
        }
        if (!force && now.equals(s.chestsSent)) return;
        s.chestsSent = List.copyOf(now);
        for (Message page : Chunks.pages(now, Message.ChestContents::new)) s.peer.send(Protocol.encode(page));
    }

    // ---- projects from BlockDesigner --------------------------------------------------------------------------------

    /** Who files from the BlockDesigner link belong to. */
    public static final UUID APP_UPLOADER = new UUID(0, 0);

    /**
     * Adds a file the BlockDesigner link sent straight to the shared space (no quota: it comes from the server's own
     * machine). Shared placements showing an earlier file of the same name switch to it, so everyone following them
     * sees the new version. Returns the file's hash.
     */
    public synchronized String addFromApp(String name, byte[] data, String appName) throws IOException {
        String hash = Hashes.sha256(data);
        if (store.schematic(hash) == null) {
            SchematicInfo info = new SchematicInfo(hash, name, data.length, APP_UPLOADER, appName, clock.getAsLong());
            store.addSchematic(info, data);
            broadcast(new Message.SchematicAdded(info));
            log.info(appName + " shared " + name + " (" + kib(data.length) + ", " + Hashes.shortHash(hash) + ")");
        }
        for (SharedPlacement p : List.copyOf(store.placements())) {
            SchematicInfo old = store.schematic(p.hash());
            if (p.hash().equals(hash) || old == null || !old.name().equalsIgnoreCase(name)) continue;
            putAndBroadcast(p.withHash(hash));
        }
        return hash;
    }

    // ---- sending --------------------------------------------------------------------------------------------------

    private void broadcast(Message m) {
        byte[] data = Protocol.encode(m);
        for (Session s : sessions.values()) if (s.ready && s.peer.has(Permission.USE)) s.peer.send(data);
    }

    private void notice(Session s, boolean error, String message) {
        s.peer.send(Protocol.encode(new Message.Notice(error, message)));
    }
}
