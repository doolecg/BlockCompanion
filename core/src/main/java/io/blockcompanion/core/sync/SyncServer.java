package io.blockcompanion.core.sync;

import io.blockcompanion.core.autobuild.AutoBuildJob;
import io.blockcompanion.core.autobuild.AutoBuildOptions;
import io.blockcompanion.core.autobuild.AutoBuildPlan;
import io.blockcompanion.core.autobuild.BuildWorld;
import io.blockcompanion.core.chests.LinkedChests;
import io.blockcompanion.core.compare.Compare;
import io.blockcompanion.core.library.SchematicLibrary;
import io.blockcompanion.core.model.BlockPos;
import io.blockcompanion.core.model.BlockState;
import io.blockcompanion.core.model.Structure;
import io.blockcompanion.core.placement.Placement;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

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
    /**
     * What each linked chest held when the server last read it. A chest is read when it is linked, when a player closes
     * it ({@link #chestChanged}), when AutoBuild starts or resumes from it, and when taking from it finds less than
     * expected; what AutoBuild and restocks take is subtracted here without reading it again. Nothing polls the chests.
     */
    private final LinkedChests known = new LinkedChests();
    private int chestTicks;
    /** The world, for AutoBuild; null where the platform can't place blocks. */
    private BuildWorld buildWorld;
    /** Running AutoBuilds by job id. */
    private final Map<UUID, RunningBuild> autoBuilds = new LinkedHashMap<>();
    /** AutoBuilds one player may run at once. */
    static final int MAX_AUTOBUILDS_PER_PLAYER = 4;
    /** Ticks between two progress messages of a running AutoBuild. */
    static final int AUTOBUILD_STATUS_TICKS = 10;
    /** Ticks between two chest lists sent to a player while AutoBuild or restocks take from their chests. */
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
        /** Their chests' contents changed since the list was last sent. */
        boolean chestsDirty;

        Session(SyncPeer peer) {
            this.peer = peer;
        }
    }

    /** An AutoBuild with what its status messages carry. */
    private static final class RunningBuild {
        final AutoBuildJob job;
        final PlacementPose pose;
        final List<LinkedChests.Pos> chests;
        long sentVersion = -1;
        int ticksSinceSent;

        RunningBuild(AutoBuildJob job, PlacementPose pose, List<LinkedChests.Pos> chests) {
            this.job = job;
            this.pose = pose;
            this.chests = chests;
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

    /** Turns on AutoBuild (linked chests must be on too): {@code world} places blocks. Without it, AutoBuild is off. */
    public synchronized void setBuildWorld(BuildWorld world) {
        this.buildWorld = world;
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
        tickAutoBuilds();
        if (chests != null && ++chestTicks >= CHEST_REFRESH_TICKS) {
            chestTicks = 0;
            for (Session s : sessions.values()) {
                if (!s.chestsDirty) continue;
                s.chestsDirty = false;
                if (s.ready) sendChests(s, false);
            }
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
            case Message.AutoBuildStart b -> autoBuildStart(s, actor, b.hash(), b.pose(), AutoBuildOptions.ofRate(b.blocksPerSecond()), b.chests());
            case Message.AutoBuildBegin b -> autoBuildStart(s, actor, b.hash(), b.pose(), b.options(), b.chests());
            case Message.AutoBuildControl c -> autoBuildControl(s, actor, c);
            case Message.AutoBuildSetOptions o -> autoBuildOptions(s, actor, o);
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
        for (RunningBuild r : autoBuilds.values()) if (r.job.owner().equals(s.peer.id())) sendAutoBuild(s, r);
    }

    /** The features one player gets: the server's switches plus their own permissions and quota. */
    Features featuresFor(SyncPeer peer) {
        LockRules.Actor a = actor(peer);
        return new Features(config.enabled && a.has(Permission.USE), config.maxFileSize, a.admin() ? -1 : config.playerQuota,
                store.usedBy(peer.id()), Protocol.CHUNK_SIZE, Permission.mask(a.permissions()), config.allowAutoPlace,
                config.autoPlaceRange, config.autoPlaceRate, config.allowCreativeFill, chestsAllowed(),
                config.allowEasyPlace, config.allowEasyPlaceAuto, autoBuildAllowed(), config.autoBuildMaxRate, true,
                config.autoBuildReplace, config.autoBuildMaxRadius);
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
            Map<String, Long> items = chests.contents(l.dimension(), l.x(), l.y(), l.z());
            if (items == null) {
                notice(s, true, "There is no chest at " + pos);
                return;
            }
            if (!chestLinks.add(id, pos)) {
                notice(s, true, "You can link at most " + ChestLinkStore.MAX_PER_PLAYER + " chests");
                return;
            }
            known.link(pos);
            known.setContents(pos, items, clock.getAsLong(), LinkedChests.Source.LIVE);
        } else {
            chestLinks.remove(id, pos);
            if (!chestLinks.linked(pos)) known.unlink(pos);
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
            long had = has(p, r.item());
            if (had <= 0) continue;
            int n = chests.take(s.peer.id(), p.dimension(), p.x(), p.y(), p.z(), r.item(), want - moved);
            tookFrom(p, r.item(), n, Math.min(had, want - moved));
            moved += n;
        }
        if (moved == 0) notice(s, true, "No " + r.item() + " in your linked chests, or no room in your inventory");
        sendChests(s, false);
    }

    /**
     * The platform saw a container closed (or otherwise changed by a player) at the position: a linked chest there is
     * read again and its players get the new contents. Positions nobody linked are ignored, so the platform may pass
     * every container a closed screen showed.
     */
    public synchronized void chestChanged(String dimension, int x, int y, int z) {
        if (chests == null || chestLinks == null) return;
        LinkedChests.Pos pos = new LinkedChests.Pos(dimension, x, y, z);
        if (!chestLinks.linked(pos)) return;
        long before = known.version();
        read(pos);
        if (known.version() == before) return;
        for (Session s : sessions.values()) {
            if (s.ready && chestLinks.has(s.peer.id(), pos)) sendChests(s, false);
        }
    }

    /** Every chest any player linked, for the platform to match closed containers against. */
    public synchronized Set<LinkedChests.Pos> linkedChests() {
        return chestLinks == null ? Set.of() : chestLinks.all();
    }

    /** A linked chest's contents as last read, reading it now if it never was; null when unknown and not loaded. */
    private Map<String, Long> contents(LinkedChests.Pos p) {
        LinkedChests.Seen seen = known.seen(p);
        return known.isLinked(p) && seen.source() != LinkedChests.Source.UNKNOWN ? seen.items() : read(p);
    }

    /** Reads a chest from the world into {@link #known}. When it isn't loaded (or is gone), what was known stays. */
    private Map<String, Long> read(LinkedChests.Pos p) {
        Map<String, Long> items = chests.contents(p.dimension(), p.x(), p.y(), p.z());
        if (items != null) {
            known.link(p);
            known.setContents(p, items, clock.getAsLong(), LinkedChests.Source.LIVE);
        }
        LinkedChests.Seen seen = known.seen(p);
        return seen.source() == LinkedChests.Source.UNKNOWN ? null : seen.items();
    }

    /** How many of an item a linked chest holds, as last read. */
    private long has(LinkedChests.Pos p, String item) {
        Map<String, Long> items = contents(p);
        return items == null ? 0 : items.getOrDefault(item, 0L);
    }

    /**
     * {@code n} of an item came out of a chest where {@code expected} were thought to be: subtracts it from what is known
     * (reading the chest again only when it held less than thought), and marks the chest's players for a new list.
     */
    private void tookFrom(LinkedChests.Pos p, String item, int n, long expected) {
        if (!known.removed(p, item, n) || n < expected) read(p);
        for (Session s : sessions.values()) if (chestLinks.has(s.peer.id(), p)) s.chestsDirty = true;
    }

    /** Sends the player's chests with their contents when they changed ({@code force}: always). */
    private void sendChests(Session s, boolean force) {
        if (chests == null || chestLinks == null) return;
        List<LinkedChests.Pos> mine = chestLinks.of(s.peer.id());
        if (mine.isEmpty() && s.chestsSent.isEmpty() && !force) return;
        List<Message.ChestEntry> now = new ArrayList<>();
        for (LinkedChests.Pos p : mine) {
            Map<String, Long> items = contents(p);
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

    // ---- AutoBuild --------------------------------------------------------------------------------------------------

    private boolean autoBuildAllowed() {
        return config.enabled && config.allowAutoBuild && buildWorld != null && chests != null && chestLinks != null;
    }

    /** "Castle" from "builds/Castle.schem". */
    static String displayName(String fileName) {
        String n = fileName.substring(fileName.lastIndexOf('/') + 1);
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    /** Reads a shared schematic the way the client's library does (a project's visible layers, bounds at the origin). */
    private Structure loadShared(SchematicInfo info) throws IOException {
        Path dir = Files.createTempDirectory("blockcompanion-autobuild");
        String clean = info.name().substring(info.name().lastIndexOf('/') + 1).replaceAll("[\\\\/:*?\"<>|]", "_");
        Path file = dir.resolve(clean.isBlank() ? "schematic.schem" : clean);
        try {
            Files.copy(store.file(info.hash()), file);
            return SchematicLibrary.loadFile(file);
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(dir);
        }
    }

    /** The player's chests among {@code asked}: only chests they really linked count. */
    private List<LinkedChests.Pos> ownChests(UUID player, List<LinkedChests.Pos> asked) {
        List<LinkedChests.Pos> out = new ArrayList<>();
        for (LinkedChests.Pos p : chestLinks.of(player)) if (asked.contains(p)) out.add(p);
        return out;
    }

    /** What the chests hold, added up, as last read. */
    private Map<String, Long> chestTotals(List<LinkedChests.Pos> list) {
        Map<String, Long> have = new HashMap<>();
        for (LinkedChests.Pos p : list) {
            Map<String, Long> items = contents(p);
            if (items != null) items.forEach((k, v) -> have.merge(k, v, Long::sum));
        }
        return have;
    }

    /**
     * The linked chests as AutoBuild's item source: counts (from what the server last read, no world reads) and takes
     * (uses up) items across them in link order, subtracting what it took from what is known.
     */
    private AutoBuildJob.Supplies supplies(List<LinkedChests.Pos> list) {
        BuildWorld.Drops drops = new BuildWorld.Drops() {
            public List<LinkedChests.Pos> chests() {
                return list;
            }

            public void filled(LinkedChests.Pos p) {
                // Drops went in: read it again (rare next to taking, which only subtracts).
                read(p);
                for (Session s : sessions.values()) if (chestLinks.has(s.peer.id(), p)) s.chestsDirty = true;
            }
        };
        return new AutoBuildJob.Supplies() {
            public BuildWorld.Drops drops() {
                return drops;
            }

            public long count(String item) {
                long n = 0;
                for (LinkedChests.Pos p : list) n += has(p, item);
                return n;
            }

            public int take(String item, int count) {
                int got = 0;
                for (LinkedChests.Pos p : list) {
                    if (got >= count) break;
                    long had = has(p, item);
                    if (had <= 0) continue;
                    int n = chests.remove(p.dimension(), p.x(), p.y(), p.z(), item, count - got);
                    tookFrom(p, item, n, Math.min(had, count - got));
                    got += n;
                }
                return got;
            }
        };
    }

    /** The options as this server lets them run. */
    private AutoBuildOptions cap(AutoBuildOptions o) {
        return o.capped(config.autoBuildMaxRate, config.autoBuildReplace, config.autoBuildMaxRadius);
    }

    /** Plans a placement for AutoBuild: its blocks, and with air cleared the air cells that have something to clear. */
    private List<AutoBuildPlan.Step> planFor(Placement p, String dim, AutoBuildOptions options) {
        if (!options.clearsAir() || !options.onlyItem().isEmpty()) return AutoBuildPlan.plan(p);
        // Only air cells with something there now (or not loaded, so not known yet) become steps.
        return AutoBuildPlan.plan(p, c -> !buildWorld.isLoaded(dim, c.x(), c.y(), c.z())
                || Compare.classify(BlockState.AIR, buildWorld.get(dim, c.x(), c.y(), c.z())) == Compare.Result.EXTRA);
    }

    private Placement placementOf(SchematicInfo info, PlacementPose pose) throws IOException {
        Placement p = new Placement(info.name(), loadShared(info), new BlockPos(pose.x(), pose.y(), pose.z()));
        p.setOrientation(pose.rotation(), pose.mirrored());
        return p;
    }

    private void autoBuildStart(Session s, LockRules.Actor actor, String hash, PlacementPose pose, AutoBuildOptions asked,
                                List<LinkedChests.Pos> askedChests) {
        if (!autoBuildAllowed()) {
            notice(s, true, "This server has AutoBuild turned off");
            return;
        }
        if (!actor.has(Permission.AUTOBUILD)) {
            notice(s, true, "You may not use AutoBuild on this server");
            return;
        }
        SchematicInfo info = store.schematic(hash);
        if (info == null) {
            notice(s, true, "Upload the schematic before starting AutoBuild");
            return;
        }
        if (!validPose(pose) || !buildWorld.dimensionExists(pose.dimension())) {
            notice(s, true, "AutoBuild can't reach that placement's dimension");
            return;
        }
        int mine = 0;
        for (RunningBuild r : autoBuilds.values()) {
            if (r.job.hash().equals(hash) && r.pose.equals(pose)) {
                notice(s, true, "AutoBuild is already running on " + r.job.name());
                sendAutoBuild(s, r);
                return;
            }
            if (r.job.owner().equals(actor.id())) mine++;
        }
        if (mine >= MAX_AUTOBUILDS_PER_PLAYER) {
            notice(s, true, "You can run at most " + MAX_AUTOBUILDS_PER_PLAYER + " AutoBuilds at once");
            return;
        }
        AutoBuildOptions options = cap(asked);
        String dim = pose.dimension();
        List<AutoBuildPlan.Step> steps;
        try {
            steps = planFor(placementOf(info, pose), dim, options);
        } catch (IOException | RuntimeException e) {
            notice(s, true, "Could not read " + info.name() + ": " + e.getMessage());
            return;
        }
        Predicate<AutoBuildPlan.Step> toDo = st -> {
            AutoBuildPlan.Cell c = st.main();
            // Not loaded: count it, as the client's progress does for blocks it hasn't seen.
            if (!buildWorld.isLoaded(dim, c.x(), c.y(), c.z())) return AutoBuildPlan.inScope(st, options);
            return AutoBuildPlan.needsWork(st, buildWorld.get(dim, c.x(), c.y(), c.z()), options,
                    () -> buildWorld.removal(dim, c.x(), c.y(), c.z()));
        };
        long count = AutoBuildPlan.count(steps, toDo);
        long clears = AutoBuildPlan.countClears(steps, toDo);
        if (count == 0 && clears == 0) {
            notice(s, false, "Nothing left to " + (options.onlyItem().isEmpty() ? "place" : "place of that block") + " in "
                    + displayName(info.name()));
            return;
        }
        List<LinkedChests.Pos> own = ownChests(actor.id(), askedChests);
        // Starting is rare: read the chests once so the check below (and the run) starts from what is really there.
        for (LinkedChests.Pos p : own) read(p);
        if (!own.isEmpty()) sendChests(s, false);
        if (!buildWorld.isCreative(actor.id()) && count > 0) {
            // Survival and adventure build from the linked chests; checked here, whatever the client counted.
            if (own.isEmpty()) {
                notice(s, true, "Link chests with the materials before starting AutoBuild");
                return;
            }
            Map<String, Long> shortfall = AutoBuildPlan.shortfall(AutoBuildPlan.required(steps, toDo), chestTotals(own));
            if (!shortfall.isEmpty() && !options.skipMissing()) {
                notice(s, true, AutoBuildPlan.describeShort(shortfall, 4) + ": AutoBuild didn't start");
                return;
            }
        }
        AutoBuildJob job = new AutoBuildJob(UUID.randomUUID(), actor.id(), info.hash(), displayName(info.name()), dim, steps, options);
        RunningBuild r = new RunningBuild(job, pose, own);
        autoBuilds.put(job.id(), r);
        String what = String.format(Locale.ROOT, "%,d %s", count, count == 1 ? "block" : "blocks") + (clears > 0 ? String.format(Locale.ROOT, ", %,d to clear", clears) : "");
        log.info(actor.name() + " started AutoBuild of " + info.name() + " (" + what + ", " + options.describe() + ")");
        notice(s, false, "AutoBuild started: " + job.name() + " (" + what + ", " + options.describe() + ")");
        if (!options.equals(asked)) notice(s, false, "This server allows less: " + limits(asked, options));
        sendAutoBuild(s, r);
    }

    /** What the server capped, for the player: "at most 20 blocks per second, no breaking blocks". */
    private static String limits(AutoBuildOptions asked, AutoBuildOptions got) {
        List<String> parts = new ArrayList<>();
        if (got.blocksPerSecond() != asked.blocksPerSecond()) parts.add("at most " + got.blocksPerSecond() + " blocks per second");
        if (got.replace() != asked.replace()) {
            parts.add(got.replace() == AutoBuildOptions.Replace.KEEP ? "no breaking blocks" : "up to " + got.replace().label.toLowerCase(Locale.ROOT));
        }
        if (got.radius() != asked.radius()) parts.add("only within " + got.radius() + " blocks of you");
        return String.join(", ", parts);
    }

    private void autoBuildOptions(Session s, LockRules.Actor actor, Message.AutoBuildSetOptions m) {
        RunningBuild r = autoBuilds.get(m.job());
        if (r == null || (!r.job.owner().equals(actor.id()) && !actor.admin())) return;
        if (!autoBuildAllowed()) return;
        AutoBuildOptions options = cap(m.options());
        List<AutoBuildPlan.Step> air = List.of();
        if (options.clearsAir() && options.onlyItem().isEmpty() && !r.job.hasClearSteps()) {
            // Clearing switched on after the start: plan the schematic's air now.
            SchematicInfo info = store.schematic(r.job.hash());
            if (info != null) {
                try {
                    air = planFor(placementOf(info, r.pose), r.job.dimension(), options).stream()
                            .filter(st -> st.kind() == AutoBuildPlan.Kind.CLEAR).toList();
                } catch (IOException | RuntimeException e) {
                    notice(s, true, "Could not read " + info.name() + ": " + e.getMessage());
                }
            }
        }
        r.job.setOptions(options, air);
        notice(s, false, "AutoBuild: " + options.describe());
        if (!options.equals(m.options())) notice(s, false, "This server allows less: " + limits(m.options(), options));
        sendAutoBuild(s, r);
    }

    private void autoBuildControl(Session s, LockRules.Actor actor, Message.AutoBuildControl c) {
        RunningBuild r = autoBuilds.get(c.job());
        if (r == null || (!r.job.owner().equals(actor.id()) && !actor.admin())) return;
        switch (c.action()) {
            case PAUSE -> r.job.pause("Paused");
            case RESUME -> {
                // Often after refilling the chests (by hand, or a hopper): read them once again.
                for (LinkedChests.Pos p : r.chests) read(p);
                r.job.resume();
            }
            case STOP -> {
                r.job.stop("");
                notice(s, false, r.job.summary());
            }
        }
        sendAutoBuild(s, r);
        if (r.job.state().over()) autoBuilds.remove(c.job());
    }

    private void tickAutoBuilds() {
        if (autoBuilds.isEmpty()) return;
        if (buildWorld == null || chests == null) {
            autoBuilds.clear();
            return;
        }
        for (Iterator<RunningBuild> it = autoBuilds.values().iterator(); it.hasNext(); ) {
            RunningBuild r = it.next();
            AutoBuildJob job = r.job;
            AutoBuildJob.Event e;
            if (!config.allowAutoBuild || !config.enabled) {
                job.stop("AutoBuild was turned off on this server");
                e = AutoBuildJob.Event.STOPPED;
            } else {
                e = job.tick(buildWorld, supplies(r.chests));
            }
            Session s = sessions.get(job.owner());
            boolean online = s != null && s.ready;
            switch (e) {
                case PAUSED -> {
                    if (online) notice(s, true, "AutoBuild paused: " + job.message());
                }
                case FINISHED -> {
                    buildWorld.ding(job.owner());
                    if (online) notice(s, false, job.summary());
                    log.info("AutoBuild of " + job.name() + " finished: " + job.placed() + " placed, " + job.skipped() + " skipped");
                }
                case STOPPED -> {
                    if (online) notice(s, true, job.summary() + (job.message().isEmpty() ? "" : ": " + job.message()));
                }
                case NONE -> {
                }
            }
            r.ticksSinceSent++;
            boolean over = job.state().over();
            if (online && job.version() != r.sentVersion && (over || e != AutoBuildJob.Event.NONE || r.ticksSinceSent >= AUTOBUILD_STATUS_TICKS)) {
                sendAutoBuild(s, r);
            }
            if (over) it.remove();
        }
    }

    private void sendAutoBuild(Session s, RunningBuild r) {
        AutoBuildJob j = r.job;
        String message = j.state().over() ? j.summary() + (j.message().isEmpty() ? "" : ": " + j.message()) : j.message();
        s.peer.send(Protocol.encode(new Message.AutoBuildStatus(j.id(), j.hash(), r.pose, j.name(), j.state(), j.done(), j.total(), j.placed(),
                j.skipped(), message)));
        r.sentVersion = j.version();
        r.ticksSinceSent = 0;
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
