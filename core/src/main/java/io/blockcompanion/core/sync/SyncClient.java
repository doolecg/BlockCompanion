package io.blockcompanion.core.sync;

import io.blockcompanion.core.chests.LinkedChests;

import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The client side of the shared space, free of Minecraft classes: it keeps the server's lists, runs uploads and
 * downloads, and links the player's placement to a shared one so moves go both ways. The mod feeds it messages
 * ({@link #receive}), calls {@link #tick()} every client tick, and gives it a sender, file storage and a
 * {@link ClientPlacementModel}. Calls are expected on the client thread.
 */
public final class SyncClient {
    /** Upload chunks sent per tick (16 KiB each, so about 1.3 MB/s). */
    static final int CHUNKS_PER_TICK = 4;
    /** Ticks between two move messages while the player keeps moving a linked placement. */
    static final int MOVE_INTERVAL_TICKS = 4;

    /** Where downloaded schematics go and uploaded ones come from: the client's schematic library. */
    public interface Storage {
        /** The library name of a local file with this SHA-256, or null. */
        String findLocal(String hash);

        /** Saves a downloaded file (e.g. under {@code shared/}) and returns its library name. */
        String saveDownloaded(String name, String hash, byte[] data) throws IOException;

        byte[] read(String libraryName) throws IOException;
    }

    /** What the screen and the chat need to hear. */
    public interface Listener {
        /** Lists, features, transfers or the link changed: redraw. */
        default void changed() {
        }

        /** A message for the player. */
        default void notice(boolean error, String message) {
        }

        /** News about one of the player's AutoBuilds (progress, paused, finished). */
        default void autoBuild(Message.AutoBuildStatus status) {
        }
    }

    private final Consumer<byte[]> sender;
    private final Storage storage;
    private final ClientPlacementModel model;
    private final Listener listener;
    private final String software;
    private final UUID self;

    private boolean serverPresent;
    private String serverSoftware = "";
    private Features features = Features.NONE;
    private final Map<String, SchematicInfo> schematics = new LinkedHashMap<>();
    private final Map<UUID, SharedPlacement> placements = new LinkedHashMap<>();

    private int nextTransfer = 1;
    private final Map<Integer, Upload> uploads = new LinkedHashMap<>();
    private final Map<Integer, Download> downloads = new LinkedHashMap<>();
    /** Placement shares waiting for their upload, by file hash. */
    private final Map<String, Integer> sharesWaiting = new HashMap<>();
    /** Placement create requests waiting for their id: request number to hash. */
    private final Map<Integer, String> createRequests = new HashMap<>();

    /** The player's linked chests as the server last reported them, and a counter that changes with them. */
    private final List<Message.ChestEntry> chests = new ArrayList<>();
    private long chestsVersion;

    /** The player's AutoBuilds as the server last reported them, by job id, and starts waiting for their upload. */
    private final Map<UUID, Message.AutoBuildStatus> autoBuilds = new LinkedHashMap<>();
    private final Map<String, Message.AutoBuildStart> autoBuildsWaiting = new HashMap<>();

    // The link between the player's placement and a shared one.
    private UUID linked;
    private String linkedLibraryName;
    private long seenVersion;
    private boolean moveDirty;
    private int ticksSinceMove;

    private static final class Upload {
        final String hash, name;
        final byte[] data;
        final int count;
        BitSet have;
        boolean sending;
        int next;
        boolean retried;

        Upload(String hash, String name, byte[] data) {
            this.hash = hash;
            this.name = name;
            this.data = data;
            this.count = Chunks.count(data.length, Protocol.CHUNK_SIZE);
        }
    }

    private static final class Download {
        final String hash;
        final List<Consumer<String>> then = new ArrayList<>();
        Chunks.Assembler assembler;
        String name = "";

        Download(String hash) {
            this.hash = hash;
        }
    }

    public SyncClient(Consumer<byte[]> sender, Storage storage, ClientPlacementModel model, Listener listener, String software, UUID self) {
        this.sender = sender;
        this.storage = storage;
        this.model = model;
        this.listener = listener;
        this.software = software;
        this.self = self;
    }

    // ---- state ----------------------------------------------------------------------------------------------------

    /** True once a BlockCompanion server answered the hello with the same protocol version. */
    public boolean serverPresent() {
        return serverPresent;
    }

    public String serverSoftware() {
        return serverSoftware;
    }

    /** What the server allows ({@link Features#NONE} until it says). Milestone 3 reads the auto-place flags here. */
    public Features features() {
        return features;
    }

    public List<SchematicInfo> schematics() {
        List<SchematicInfo> list = new ArrayList<>(schematics.values());
        list.sort(Comparator.comparing(s -> s.name().toLowerCase(Locale.ROOT)));
        return list;
    }

    public SchematicInfo schematic(String hash) {
        return schematics.get(hash);
    }

    public Collection<SharedPlacement> placements() {
        return placements.values();
    }

    public SharedPlacement placement(UUID id) {
        return placements.get(id);
    }

    /** The player's linked chests with their contents, as the server last reported them. */
    public List<Message.ChestEntry> chests() {
        return List.copyOf(chests);
    }

    /** Changes whenever {@link #chests()} does. */
    public long chestsVersion() {
        return chestsVersion;
    }

    /** Links or unlinks a chest on the server (only when it allows building from chests). */
    public void linkChest(String dimension, int x, int y, int z, boolean link) {
        if (serverPresent && features.chestBuildAllowed()) send(new Message.ChestLink(dimension, x, y, z, link));
    }

    /** Asks the server to move up to {@code count} of an item from the linked chests into the inventory. */
    public void restock(String item, int count) {
        if (serverPresent && features.chestBuildAllowed()) send(new Message.ChestRestock(item, count));
    }

    // ---- AutoBuild --------------------------------------------------------------------------------------------------

    /** True when this server runs AutoBuild and lets this player start it. */
    public boolean autoBuildAllowed() {
        return serverPresent && features.syncEnabled() && features.autoBuildAllowed()
                && (features.can(Permission.AUTOBUILD) || features.can(Permission.ADMIN));
    }

    /**
     * Starts AutoBuild on a placement: uploads its schematic if the server lacks it, then asks the server to build it
     * from {@code chests}. Returns false (after telling the player) when it can't even ask.
     */
    public boolean startAutoBuild(String libraryName, PlacementPose pose, int blocksPerSecond, List<LinkedChests.Pos> chests) {
        if (!autoBuildAllowed()) {
            listener.notice(true, "This server doesn't let you use AutoBuild");
            return false;
        }
        String hash = upload(libraryName);
        if (hash == null) return false;
        Message.AutoBuildStart start = new Message.AutoBuildStart(hash, pose, blocksPerSecond, chests);
        if (schematics.containsKey(hash)) send(start);
        else autoBuildsWaiting.put(hash, start);
        return true;
    }

    /** Pauses, resumes or stops one of the player's AutoBuilds. */
    public void controlAutoBuild(UUID job, Message.AutoBuildAction action) {
        if (serverPresent) send(new Message.AutoBuildControl(job, action));
    }

    /** The latest AutoBuild news for the placement at {@code pose}, or null. */
    public Message.AutoBuildStatus autoBuild(PlacementPose pose) {
        Message.AutoBuildStatus found = null;
        for (Message.AutoBuildStatus st : autoBuilds.values()) if (st.pose().equals(pose)) found = st;
        return found;
    }

    /** True while an AutoBuild start for the placement at {@code pose} waits for its upload. */
    public boolean autoBuildStarting(PlacementPose pose) {
        for (Message.AutoBuildStart st : autoBuildsWaiting.values()) if (st.pose().equals(pose)) return true;
        return false;
    }

    /** The shared placement the player's placement follows, or null. */
    public UUID linked() {
        return linked;
    }

    public UUID self() {
        return self;
    }

    /** One line per running transfer, for the screen. */
    public List<String> activity() {
        List<String> out = new ArrayList<>();
        for (Upload u : uploads.values()) {
            int pct = u.count == 0 ? 0 : Math.min(100, u.next * 100 / u.count);
            out.add("Uploading " + u.name + (u.sending ? " " + pct + "%" : "..."));
        }
        for (Download d : downloads.values()) {
            int pct = d.assembler == null ? 0 : d.assembler.received() * 100 / d.assembler.count();
            out.add("Downloading " + (d.name.isEmpty() ? Hashes.shortHash(d.hash) : d.name) + " " + pct + "%");
        }
        return out;
    }

    private LockRules.Actor actor() {
        return new LockRules.Actor(self, "", features.permissionSet());
    }

    // ---- connection -----------------------------------------------------------------------------------------------

    public void sendHello() {
        send(new Message.Hello(Protocol.VERSION, software, 0L));
    }

    /** Disconnected: forget the server's state (the player's own placement stays loaded, unlinked). */
    public void reset() {
        serverPresent = false;
        serverSoftware = "";
        features = Features.NONE;
        schematics.clear();
        placements.clear();
        uploads.clear();
        downloads.clear();
        sharesWaiting.clear();
        createRequests.clear();
        autoBuilds.clear();
        autoBuildsWaiting.clear();
        if (!chests.isEmpty()) chestsVersion++;
        chests.clear();
        unlink();
    }

    private void send(Message m) {
        sender.accept(Protocol.encode(m));
    }

    /** One message from the server; malformed ones are dropped. */
    public void receive(byte[] data) {
        Message m;
        try {
            m = Protocol.decode(data);
        } catch (IOException e) {
            return;
        }
        switch (m) {
            case Message.Hello h -> {
                serverSoftware = h.software();
                serverPresent = h.protocolVersion() == Protocol.VERSION;
                if (!serverPresent) {
                    listener.notice(true, "This server's BlockCompanion speaks protocol " + h.protocolVersion() + ", yours speaks "
                            + Protocol.VERSION + ": update to share schematics here");
                }
            }
            case Message.ServerFeatures f -> features = f.features();
            case Message.Notice n -> listener.notice(n.error(), n.message());
            case Message.SchematicList l -> {
                if (l.reset()) schematics.clear();
                l.entries().forEach(s -> schematics.put(s.hash(), s));
            }
            case Message.SchematicAdded a -> schematics.put(a.info().hash(), a.info());
            case Message.SchematicRemoved r -> schematics.remove(r.hash());
            case Message.PlacementList l -> {
                if (l.reset()) placements.clear();
                l.entries().forEach(p -> placements.put(p.id(), p));
                if (linked != null && !placements.containsKey(linked)) unlink();
            }
            case Message.PlacementUpdate u -> placementUpdated(u.placement());
            case Message.PlacementRemoved r -> {
                SharedPlacement gone = placements.remove(r.id());
                if (r.id().equals(linked)) {
                    unlink();
                    listener.notice(false, "The shared placement" + (gone == null ? "" : " of " + gone.name()) + " was removed; yours stays as a local copy");
                }
            }
            case Message.PlacementCreated c -> {
                String hash = createRequests.remove(c.request());
                ClientPlacementModel.Loaded cur = model.current();
                if (hash != null && cur != null) link(c.id(), cur);
            }
            case Message.UploadStatus s -> uploadStatus(s);
            case Message.DownloadBegin b -> downloadBegin(b);
            case Message.Chunk c -> downloadChunk(c);
            case Message.ChestContents c -> {
                if (c.reset()) chests.clear();
                for (Message.ChestEntry e : c.entries()) {
                    chests.removeIf(o -> o.dimension().equals(e.dimension()) && o.x() == e.x() && o.y() == e.y() && o.z() == e.z());
                    chests.add(e);
                }
                chestsVersion++;
            }
            case Message.AutoBuildStatus st -> {
                // One entry per placement: a new run replaces the last one's news.
                autoBuilds.values().removeIf(o -> !o.job().equals(st.job()) && o.pose().equals(st.pose()));
                autoBuilds.put(st.job(), st);
                listener.autoBuild(st);
            }
            default -> {
                // Client-to-server types: ignore.
            }
        }
        listener.changed();
    }

    // ---- ticking --------------------------------------------------------------------------------------------------

    public void tick() {
        pumpUploads();
        followLocal();
    }

    private void pumpUploads() {
        int budget = CHUNKS_PER_TICK;
        for (Map.Entry<Integer, Upload> e : uploads.entrySet()) {
            Upload u = e.getValue();
            if (!u.sending) continue;
            while (budget > 0 && u.next < u.count) {
                int i = u.next++;
                if (u.have.get(i)) continue;
                send(new Message.Chunk(e.getKey(), i, Chunks.slice(u.data, i, Protocol.CHUNK_SIZE)));
                budget--;
            }
            if (budget == 0) break;
        }
    }

    /** Notices the player moving a linked placement, checks the locks locally, and sends the move (throttled). */
    private void followLocal() {
        ticksSinceMove++;
        if (linked == null) return;
        SharedPlacement shared = placements.get(linked);
        ClientPlacementModel.Loaded cur = model.current();
        if (shared == null || cur == null || !cur.libraryName().equals(linkedLibraryName)) {
            unlink();
            listener.changed();
            return;
        }
        if (cur.version() != seenVersion) {
            seenVersion = cur.version();
            if (!cur.pose().equals(shared.pose())) {
                String reason = LockRules.canModify(shared, actor(), Long.MAX_VALUE, 0);
                if (reason != null) {
                    // Not ours to move: put it back where everyone else sees it.
                    model.apply(shared.pose());
                    ClientPlacementModel.Loaded after = model.current();
                    if (after != null) seenVersion = after.version();
                    listener.notice(true, reason);
                    moveDirty = false;
                    return;
                }
                moveDirty = true;
            }
        }
        if (moveDirty && ticksSinceMove >= MOVE_INTERVAL_TICKS) {
            send(new Message.PlacementMove(linked, cur.pose()));
            moveDirty = false;
            ticksSinceMove = 0;
        }
    }

    // ---- placements -----------------------------------------------------------------------------------------------

    private void placementUpdated(SharedPlacement p) {
        SharedPlacement before = placements.put(p.id(), p);
        if (!p.id().equals(linked)) return;
        if (before != null && !before.hash().equals(p.hash())) {
            // A new version of the file (sent from BlockDesigner, say): fetch it and swap it in where the placement is.
            fetch(p.hash(), libraryName -> {
                String error = model.reload(libraryName);
                if (error != null) {
                    listener.notice(true, error);
                    return;
                }
                linkedLibraryName = libraryName;
                ClientPlacementModel.Loaded cur = model.current();
                if (cur != null) seenVersion = cur.version();
                listener.notice(false, "Updated " + p.name() + " to the new version");
                listener.changed();
            });
            return;
        }
        // While we are the one moving it, our own copy is at least as new as the echo.
        if (self.equals(p.editor()) && (moveDirty || ticksSinceMove < MOVE_INTERVAL_TICKS * 5)) return;
        ClientPlacementModel.Loaded cur = model.current();
        if (cur == null || cur.pose().equals(p.pose())) return;
        model.apply(p.pose());
        ClientPlacementModel.Loaded after = model.current();
        if (after != null) seenVersion = after.version();
        moveDirty = false;
    }

    private void link(UUID id, ClientPlacementModel.Loaded cur) {
        linked = id;
        linkedLibraryName = cur.libraryName();
        seenVersion = cur.version();
        moveDirty = false;
    }

    /** Stops following; the player's placement stays as a local one. */
    public void unlink() {
        linked = null;
        linkedLibraryName = null;
        moveDirty = false;
    }

    /**
     * Loads a shared placement: fetches the file if the library does not have it, loads it where the placement is, and
     * links it so moves go both ways.
     */
    public void load(UUID placementId) {
        SharedPlacement p = placements.get(placementId);
        if (p == null) return;
        fetch(p.hash(), libraryName -> {
            SharedPlacement now = placements.get(placementId);
            if (now == null) return;
            String error = model.load(libraryName, now.pose());
            if (error != null) {
                listener.notice(true, error);
                return;
            }
            ClientPlacementModel.Loaded cur = model.current();
            if (cur != null) link(now.id(), cur);
            listener.notice(false, "Loaded the shared placement of " + now.name());
            listener.changed();
        });
    }

    /** Makes sure the library has the file with this hash (downloading it if needed), then calls {@code then} with its name. */
    public void fetch(String hash, Consumer<String> then) {
        String local = storage.findLocal(hash);
        if (local != null) {
            then.accept(local);
            return;
        }
        for (Download d : downloads.values()) {
            if (d.hash.equals(hash)) {
                d.then.add(then);
                return;
            }
        }
        int transfer = nextTransfer++;
        Download d = new Download(hash);
        d.then.add(then);
        downloads.put(transfer, d);
        send(new Message.DownloadRequest(transfer, hash));
        listener.changed();
    }

    /**
     * Shares the player's current placement: uploads its schematic if the server lacks it, then creates the shared
     * placement and links to it.
     */
    public void shareCurrent() {
        ClientPlacementModel.Loaded cur = model.current();
        if (cur == null) {
            listener.notice(true, "Load a schematic first");
            return;
        }
        if (!features.can(Permission.PLACE) && !features.can(Permission.ADMIN)) {
            listener.notice(true, "This server does not let you share placements");
            return;
        }
        String hash = upload(cur.libraryName());
        if (hash == null) return;
        if (schematics.containsKey(hash)) create(hash, cur.pose());
        else sharesWaiting.put(hash, 1);
    }

    private void create(String hash, PlacementPose pose) {
        int request = nextTransfer++;
        createRequests.put(request, hash);
        send(new Message.PlacementCreate(request, hash, pose));
    }

    /**
     * Uploads a library file unless the server has it already. Returns its hash, or null if it could not be read or is
     * not allowed.
     */
    public String upload(String libraryName) {
        if (!features.syncEnabled()) {
            listener.notice(true, "This server does not share schematics");
            return null;
        }
        byte[] data;
        try {
            data = storage.read(libraryName);
        } catch (IOException e) {
            listener.notice(true, "Could not read " + libraryName + ": " + e.getMessage());
            return null;
        }
        String hash = Hashes.sha256(data);
        if (schematics.containsKey(hash)) return hash;
        if (!features.can(Permission.UPLOAD) && !features.can(Permission.ADMIN)) {
            listener.notice(true, "This server does not let you upload schematics");
            return null;
        }
        if (data.length > features.maxFileSize()) {
            listener.notice(true, libraryName + " is too large for this server (" + data.length / 1024 + " KiB, limit "
                    + features.maxFileSize() / 1024 + " KiB)");
            return null;
        }
        for (Upload u : uploads.values()) if (u.hash.equals(hash)) return hash;
        String name = libraryName.substring(libraryName.lastIndexOf('/') + 1);
        int transfer = nextTransfer++;
        uploads.put(transfer, new Upload(hash, name, data));
        send(new Message.UploadBegin(transfer, hash, name, data.length));
        listener.changed();
        return hash;
    }

    private void uploadStatus(Message.UploadStatus s) {
        Upload u = uploads.get(s.transfer());
        if (u == null) return;
        switch (s.code()) {
            case ACCEPTED -> {
                u.have = s.have();
                u.sending = true;
                u.next = 0;
            }
            case ALREADY_HAVE, DONE -> {
                uploads.remove(s.transfer());
                if (s.code() == Message.UploadCode.DONE) listener.notice(false, s.message());
                ClientPlacementModel.Loaded cur = model.current();
                if (sharesWaiting.remove(u.hash) != null && cur != null) create(u.hash, cur.pose());
                Message.AutoBuildStart start = autoBuildsWaiting.remove(u.hash);
                if (start != null) send(start);
            }
            case HASH_MISMATCH -> {
                uploads.remove(s.transfer());
                if (!u.retried) {
                    // Try once more from scratch.
                    int transfer = nextTransfer++;
                    Upload again = new Upload(u.hash, u.name, u.data);
                    again.retried = true;
                    uploads.put(transfer, again);
                    send(new Message.UploadBegin(transfer, u.hash, u.name, u.data.length));
                } else {
                    sharesWaiting.remove(u.hash);
                    autoBuildsWaiting.remove(u.hash);
                    listener.notice(true, s.message());
                }
            }
            case REJECTED -> {
                uploads.remove(s.transfer());
                sharesWaiting.remove(u.hash);
                autoBuildsWaiting.remove(u.hash);
                listener.notice(true, "Upload of " + u.name + " refused: " + s.message());
            }
        }
    }

    private void downloadBegin(Message.DownloadBegin b) {
        Download d = downloads.get(b.transfer());
        if (d == null) return;
        if (!b.found() || b.size() <= 0) {
            downloads.remove(b.transfer());
            listener.notice(true, "The server no longer has that schematic");
            return;
        }
        d.name = b.name();
        d.assembler = new Chunks.Assembler(d.hash, b.size(), Protocol.CHUNK_SIZE);
    }

    private void downloadChunk(Message.Chunk c) {
        Download d = downloads.get(c.transfer());
        if (d == null || d.assembler == null) return;
        if (d.assembler.accept(c.index(), c.data()) != Chunks.Assembler.Result.COMPLETE) return;
        downloads.remove(c.transfer());
        byte[] data = d.assembler.finish();
        if (data == null) {
            listener.notice(true, "Download of " + d.name + " arrived damaged (SHA-256 mismatch)");
            return;
        }
        String libraryName;
        try {
            libraryName = storage.saveDownloaded(d.name, d.hash, data);
        } catch (IOException e) {
            listener.notice(true, "Could not save " + d.name + ": " + e.getMessage());
            return;
        }
        for (Consumer<String> then : d.then) then.accept(libraryName);
    }

    // ---- other requests -------------------------------------------------------------------------------------------

    public void deletePlacement(UUID id) {
        send(new Message.PlacementDelete(id));
    }

    /** Owner lock on or off. */
    public void setLocked(UUID id, boolean locked) {
        send(new Message.Lock(id, Message.LockKind.OWNER, locked));
    }

    /** Drops the editing lock on the linked placement (the player is done moving it). */
    public void releaseEditLock() {
        if (linked != null) send(new Message.Lock(linked, Message.LockKind.EDIT, false));
    }

    public void deleteSchematic(String hash) {
        send(new Message.SchematicDelete(hash));
    }
}
