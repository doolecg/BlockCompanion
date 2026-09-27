# BlockCompanion sync protocol

This is how the client mod talks to a server that runs BlockCompanion: the Fabric or NeoForge mod on a dedicated
server (or an integrated server opened to LAN), or the Paper/Spigot/Bukkit plugin. Every side runs the same code,
`io.blockcompanion.core.sync` in `core`, which is plain Java 21 with no Minecraft classes.

## Transport

- **Channel:** `blockcompanion:main`.
  - On Fabric and NeoForge it is one custom payload (`SyncPayload`).
  - On Bukkit it is a plugin message.
- **Body:** the payload body is the raw message bytes, with no extra framing. A mod client therefore talks to the mod
  server and to the Paper plugin in the same way.
- **Channel checks:**
  - NeoForge registers the payload as *optional*, so its clients can still join vanilla and Paper servers.
  - The client sends nothing until the server has announced the channel (`ClientPlayNetworking.canSend` on Fabric,
    `hasChannel` on NeoForge).
- **Size limits:**

  | Path | Limit |
  |---|---|
  | Serverbound custom payload (vanilla, NeoForge) | under 32 KiB |
  | Bukkit plugin message | at most 32766 bytes |
  | Clientbound mod payload | up to 1 MiB |

  The protocol keeps every message in both directions at or under `Protocol.MAX_MESSAGE` = 30000 bytes:
  - file data goes in `CHUNK_SIZE` = 16 KiB pieces;
  - long lists are split into pages (`Chunks.pages`).

## Envelope and encoding

```
message = varint protocolVersion, varint typeId, body
```

- **Byte order:** big-endian.
- **Strings:** varint length, then UTF-8, at most 1024 bytes.
- **UUIDs:** two 64-bit longs.
- **Hashes:** the 32 raw bytes of a SHA-256. In the API they are 64 lowercase hex characters.
- **Byte arrays:** varint length, then the bytes.
- **Booleans:** one byte, 0 or 1. Any other value is an error.
- **Lists:** a varint count, then the items. A count larger than the bytes left is rejected.
- **Version rules:**
  - A message that isn't `HELLO` and carries another protocol version is refused.
  - `HELLO` is always readable, and its layout never changes, so two versions can still recognise each other.
- **Validation:** trailing bytes after a known body are an error. So are unknown type ids and truncated data.
- **Current version:** `Protocol.VERSION` = 1.

## Message types

Ids are part of the wire format. They are never renumbered; new ones are only appended. C means the client sends it, S
means the server does.

| Id | Type | Dir | Body |
|---:|---|---|---|
| 0 | `HELLO` | C↔S | varint version, string software, i64 feature bits |
| 1 | `FEATURES` | S→C | key/value list (below) |
| 2 | `NOTICE` | S→C | bool error, string text (at most 256 characters) |
| 3 | `SCHEMATIC_LIST` | S→C | bool reset, list of SchematicInfo |
| 4 | `SCHEMATIC_ADDED` | S→C | SchematicInfo |
| 5 | `SCHEMATIC_REMOVED` | S→C | hash |
| 6 | `SCHEMATIC_DELETE` | C→S | hash |
| 7 | `UPLOAD_BEGIN` | C→S | varint transfer, hash, string name, varlong size |
| 8 | `UPLOAD_STATUS` | S→C | varint transfer, varint code, bytes have-bitset, string message |
| 9 | `CHUNK` | C↔S | varint transfer, varint index, bytes data (at most 16 KiB) |
| 10 | `DOWNLOAD_REQUEST` | C→S | varint transfer, hash |
| 11 | `DOWNLOAD_BEGIN` | S→C | varint transfer, hash, string name, varlong size, bool found |
| 12 | `PLACEMENT_LIST` | S→C | bool reset, list of SharedPlacement |
| 13 | `PLACEMENT_UPDATE` | S→C | SharedPlacement |
| 14 | `PLACEMENT_REMOVED` | S→C | uuid |
| 15 | `PLACEMENT_CREATE` | C→S | varint request, hash, Pose |
| 16 | `PLACEMENT_CREATED` | S→C | varint request, uuid |
| 17 | `PLACEMENT_MOVE` | C→S | uuid, Pose |
| 18 | `PLACEMENT_DELETE` | C→S | uuid |
| 19 | `LOCK` | C→S | uuid, varint kind (0 = OWNER, 1 = EDIT), bool acquire |
| 20 | `CHEST_LINK` | C→S | dimension id, i32 x, i32 y, i32 z, bool link |
| 21 | `CHEST_CONTENTS` | S→C | bool reset, list of ChestEntry |
| 22 | `CHEST_RESTOCK` | C→S | string item id, varint count |
| 23 | `AUTOBUILD_START` | C→S | hash, Pose, varint blocks per second, list of ChestPos |
| 24 | `AUTOBUILD_CONTROL` | C→S | uuid job, varint action (0 = PAUSE, 1 = RESUME, 2 = STOP) |
| 25 | `AUTOBUILD_STATUS` | S→C | uuid job, hash, Pose, string name, varint state, varlong done, varlong total, varlong placed, varlong skipped, string message |
| 26 | `AUTOBUILD_BEGIN` | C→S | hash, Pose, AutoBuildOptions, list of ChestPos (sent only to servers that announce `auto_build_options`; older ones get `AUTOBUILD_START`) |
| 27 | `AUTOBUILD_SET_OPTIONS` | C→S | uuid job, AutoBuildOptions (changes a running AutoBuild; the owner, or an admin) |

These structures appear inside the message bodies:

- **SchematicInfo:** hash, name, varlong size, uploader uuid, uploader name, i64 upload time (epoch ms).
- **Pose:** dimension id, i32 x, i32 y, i32 z, varint rotation (0–3), bool mirrored.
  - **Position:** x, y and z are the minimum corner of the transformed box.
  - **Rotation:** clockwise quarter turns seen from above.
  - **Mirror:** across the schematic's own X axis, applied before rotating.

  These are the same numbers as the client's `Placement`, so every client that applies them draws the same blocks.
- **SharedPlacement:** uuid id, hash, name, then the Pose fields, owner uuid, owner name, bool locked, an optional
  editor uuid (a presence byte, then the uuid), editor name, varlong revision.
- **ChestEntry:** dimension id, i32 x, i32 y, i32 z, bool valid, then a list of (string item id, varlong count).
- **ChestPos:** dimension id, i32 x, i32 y, i32 z.
- **AutoBuild states:** 0 `RUNNING`, 1 `WAITING` (for chunks to load, or for the player to come within the radius),
  2 `PAUSED`, 3 `FINISHED`, 4 `STOPPED`.
- **AutoBuildOptions:** a list of `(string key, i64 value)` pairs, then a list of `(string key, string value)` pairs.
  As with `FEATURES`, a side skips keys it doesn't know and reads a missing key as its default, so options can be added
  without a new protocol version.

  | Key | Default | Meaning |
  |---|---|---|
  | `rate` | 5 | blocks per second, 1 to 1000 (the server caps it) |
  | `replace` | 0 | what it may break: 0 `KEEP` (nothing), 1 `SOLID` (solid blocks in the way), 2 `ALL` (any block in the way), 3 `CLEAR` (as `ALL`, and blocks where the schematic has air when `ignore_air` is 0) |
  | `order` | 0 | 0 `BOTTOM_UP`, 1 `TOP_DOWN`, 2 `NEAREST` (to the player, following them), 3 `BY_BLOCK` |
  | `ignore_air` | 1 | 1: the schematic's air is never touched |
  | `skip_missing` | 0 | 1: a block without items is skipped instead of pausing, and a start isn't refused for what is short |
  | `radius` | 0 | only blocks within this many blocks of the player (0: the whole schematic) |
  | `only_item` (text) | empty | only the blocks placed with this item ("build all of these") |
- **Upload codes:**

  | Value | Code |
  |---:|---|
  | 0 | `ACCEPTED` |
  | 1 | `ALREADY_HAVE` |
  | 2 | `REJECTED` |
  | 3 | `DONE` |
  | 4 | `HASH_MISMATCH` |

### Features (the feature announcement)

`FEATURES` is a list of `(string key, i64 value)` pairs.
- **Unknown keys:** a client skips keys it doesn't know. That way a newer server can add keys without breaking older
  clients.
- **Missing keys:** a client reads a key that isn't in the list as 0 (`chunk_size` defaults to 16 KiB).

| Key | Meaning |
|---|---|
| `sync` | 1 if the shared space is on for this player (switched on, and they have `use`) |
| `max_file_size` | largest upload, in bytes |
| `player_quota` / `player_used` | bytes this player may keep uploaded (-1 means unlimited) / bytes they have uploaded |
| `chunk_size` | file data per chunk |
| `permissions` | bit mask: 1 `use`, 2 `upload`, 4 `place`, 8 `lock`, 16 `admin`, 32 `autobuild` |
| `auto_place`, `auto_place_range`, `auto_place_rate` | Milestone 3: whether the client-side printer may run, its range in blocks, and its blocks per second |
| `creative_fill`, `chest_build` | Milestone 3: building helpers the server allows |
| `easy_place` | 1 if easy place may be used (a right-click on a ghost places exactly its block, through normal placement packets). **A missing key means 1**, unlike the others: servers from before the key existed allowed it |
| `easy_place_auto` | 1 if easy place's auto mode may be used (the missing blocks in reach placed by themselves). Never 1 while `easy_place` is 0. **A missing key means the same as `easy_place`** |
| `auto_build`, `auto_build_rate` | 1 if this server runs AutoBuild (switched on, and the platform can place blocks and read chests), and the fastest it places in blocks per second. Who may start it is the `autobuild` permission bit |
| `auto_build_options` | 1 if the server takes `AUTOBUILD_BEGIN` and `AUTOBUILD_SET_OPTIONS`. Without it a client sends `AUTOBUILD_START` with the speed only |
| `auto_build_replace` | the most AutoBuild may break here, as the `replace` option (0: nothing) |
| `auto_build_max_radius` | AutoBuild builds only within this many blocks of the player here; 0: no limit |

The server sends `FEATURES` after the hello, again after a config reload, and again after the player's quota use
changes. Easy place reads `easy_place` from `SyncClient.features()`; Milestone 3 will read the auto-place flag the
same way.

## Flows

### Join

1. The client sends `HELLO`. It sends it once the channel is announced, then retries every 3 seconds, up to 5 times.
2. The server answers with its own `HELLO`.
   - **Versions differ:** the server sends nothing more, and the client tells the player to update.
   - **Versions match:** the server follows with `FEATURES`, the `SCHEMATIC_LIST` pages, then the `PLACEMENT_LIST`
     pages. The first page of each list has `reset` set. When the server allows building from chests, the player's
     linked chests follow as `CHEST_CONTENTS` pages (`reset` set on the first).

The server ignores any other message that arrives before a matching hello, while sharing is off, or from a player
without `use`.

### Upload (chunked, hash-checked, resumable, deduplicated)

1. The client computes the file's SHA-256 and sends `UPLOAD_BEGIN(transfer, hash, name, size)`. The transfer number is
   the client's own.
2. The server answers with `UPLOAD_STATUS`:
   - **`ALREADY_HAVE`:** it already has this hash. Nothing is sent and nothing is charged to the quota.
   - **`REJECTED` and a reason:** the player lacks `upload`, the name isn't a `.bdproj`/`.schem`/`.schematic`/`.litematic`/`.nbt`
     file name, the file is too large, it would go over the player's quota or the total quota, or too many uploads are
     running.
   - **`ACCEPTED` and a bitset:** the bitset marks the chunks the server already holds. Unfinished uploads are kept by
     hash for `uploadTimeoutSeconds`, so a reconnect, or a second player uploading the same file, resumes the same
     upload.
3. The client sends `CHUNK(transfer, index, data)` for every chunk not in the bitset, 4 per tick. Chunks may arrive in
   any order and more than once.
4. When the last chunk arrives, the server checks the SHA-256.
   - **It matches:** the server stores the file and sends `DONE` to every player uploading that hash. It then
     broadcasts `SCHEMATIC_ADDED` and re-sends the uploader's `FEATURES` (their quota use changed).
   - **It doesn't match:** the server drops the upload and sends `HASH_MISMATCH`. The client retries once from scratch.

### Download

1. The client sends `DOWNLOAD_REQUEST(transfer, hash)`.
2. The server answers with `DOWNLOAD_BEGIN`. If `found` is false, nothing follows.
3. The server streams `CHUNK`s, `chunksPerTick` per player per tick. Each player can have at most 4 downloads queued.
4. The client puts the chunks back together, checks the SHA-256 and saves the file to
   `<library>/shared/<name>`. If a different file already has that name, it saves `<name>-<hash8>.<ext>` instead.

Before downloading, the client looks for a local file with the same hash and uses it if there is one.

### Shared placements

- **Create:** `PLACEMENT_CREATE(request, hash, pose)`.
  - Needs `place`, a schematic that has already been uploaded, and the player being under `maxPlacementsPerPlayer`
    (admins are exempt).
  - The creator gets `PLACEMENT_CREATED(request, id)`, and every player gets `PLACEMENT_UPDATE`.
- **Move:** `PLACEMENT_MOVE(id, pose)`.
  - If allowed, the move takes or renews the sender's **editing lock** for `editLockSeconds`, and the server
    broadcasts `PLACEMENT_UPDATE` with the editor set.
  - If refused, the server sends a `NOTICE` plus the placement as it stands, and the client snaps back.
  - `tick()` lets editing locks lapse and broadcasts the release. So does a `LOCK(EDIT, false)`, and so does the editor
    logging out.
- **Owner lock:** `LOCK(OWNER, true/false)`. Once locked, only the owner or an admin can move or delete the placement.
- **Delete:** `PLACEMENT_DELETE` removes the placement and broadcasts `PLACEMENT_REMOVED`.
- **Deleting a schematic:** `SCHEMATIC_DELETE` removes a schematic. The uploader can do it once no one else has a
  placement of it; an admin can do it at any time, and that also removes the placements.

The lock rules live in `LockRules`. Each rule returns null when the action is allowed, otherwise the reason:

| Action | Who may do it |
|---|---|
| Move or delete | Needs `place` (or `admin`). Refused if someone else holds an unexpired editing lock, admins included; they can drop the lock first. Refused if the placement is owner-locked and the player is neither the owner nor an admin. |
| Lock or unlock | The owner, with `lock`; or an admin. |
| Drop an editing lock | The holder, or an admin. |

### Following a placement on the client

- **Linking:** `SyncClient` links the player's placement to a shared one, either after **Load** or after **Share
  mine**.
- **Watching:** it watches the placement through `ClientPlacementModel`, which gives a library name, a pose and a
  version counter.
- **Local moves:**
  - When the player moves a linked placement, the client first checks the lock rules locally. If the move isn't
    allowed, it puts the placement back at once.
  - Otherwise it sends `PLACEMENT_MOVE`, at most once every 4 ticks.
- **Remote moves:** when someone else moves the placement, the client applies the update.
- **Echoes:** while the client holds the editing lock itself, it ignores the server's echoes of its own moves.
- **Unlinking:** if the shared placement is removed, the player's copy stays loaded as a local, unlinked placement.

### Linked chests

The server keeps what each linked chest holds and sends a player their chests as `CHEST_CONTENTS` (the whole list,
paged; an entry is `valid` when its contents are known, else the client keeps what it had). Nothing polls the chests.
A chest is read from the world only:

- **When it is linked** (`CHEST_LINK`); the list goes out at once.
- **When a player closes it:** the platform reports every block container a closed screen showed (the mod watches each
  player's open menu once a tick, the Paper plugin listens for inventory closes), and the server reads the ones someone
  linked. The list goes to their players only if the contents changed.
- **When it isn't known yet** and AutoBuild or a restock needs it, or taking from it found less than expected.

What the server itself takes (a `CHEST_RESTOCK`, AutoBuild's items) is subtracted from the kept contents without
reading the chest. After a restock the list goes out at once; while AutoBuild takes, at most every 2 seconds.

The client shows the server's contents. Without a BlockCompanion server it records a linked chest's contents from its
screen when the player closes it, and keeps them until the chest is opened again.

### AutoBuild

The server builds a placement from the player's linked chests (or for free in creative), block by block, in the order
and with the options the player picked (`AutoBuildOptions`). It runs in `SyncServer` with the planning in
`core/autobuild` (`AutoBuildPlan`, `AutoBuildJob`, `AutoBuildOptions`); the platform only supplies a `BuildWorld`
(read, check, set and break blocks, where the player is, game mode, the ding).

1. **Start:** the client uploads the schematic if the server lacks it (the usual upload), then sends
   `AUTOBUILD_BEGIN` with the hash, the placement's Pose, the options, and the linked chests to take from
   (`AUTOBUILD_START`, with the speed only, to a server without `auto_build_options`). The server caps the options
   (`autoBuildMaxBlocksPerSecond`, `autoBuildReplace`, `autoBuildMaxRadius`) and says in a `NOTICE` what it capped.
2. **The server checks again:** AutoBuild on and the `autobuild` permission; the schematic is in the shared space; the
   dimension exists; no other AutoBuild runs on the same placement (hash and Pose) and the player runs fewer than 4.
   It reads the schematic and plans it (with air cleared, also the schematic's air cells that have something in them).
   Unless the player is in creative on the server (the client's word isn't taken), only chests the player really
   linked count, and they must hold every item the blocks still to place take (unless `skip_missing`); otherwise a
   `NOTICE` says what is short ("Short: 12 oak planks, 3 glass").
3. **Building:** each tick up to the speed (capped by `autoBuildMaxBlocksPerSecond`):
   - Positions that are already right are passed over (a door or trapdoor open or shut, powered or not, counts as
     right). A different block is skipped and counted, unless the replace mode may break it: `SOLID` breaks full solid
     blocks without a block entity, `ALL` and `CLEAR` anything breakable. Unbreakable blocks (bedrock, barriers,
     portals, command blocks) are never broken. What breaks drops as if broken by hand, and a container's contents
     come out too: into the linked chests in order (the server reads those chests again), the rest on the ground; in
     creative only a container's contents drop.
   - With `CLEAR` and `ignore_air` 0, blocks where the schematic has air are broken the same way.
   - The order: bottom up, top down, nearest to the player (sorted again when they move) or by kind of block. With a
     radius, blocks farther from the player wait (state `WAITING`) until they come near.
   - A door, bed or tall plant is one step: one item, both halves set together. The other half's position is not a
     step of its own.
   - Each block's items come out of the chests as it is placed (nothing in creative). Blocks are set directly, never
     used, so no door flips and no container opens.
   - A block without support yet goes to the end of its layer (or kind) and is tried once more; bottom up it is then
     skipped, in the other orders it gets one last bottom-up pass at the end. Fluids and blocks no item places are
     skipped.
   - It waits while a step's chunk isn't loaded, pauses when an item runs out (or skips the block with
     `skip_missing`) or the player leaves, and stops when the dimension goes away.
4. **Status:** `AUTOBUILD_STATUS` goes to the player when it starts, about twice a second while it runs, and when it
   pauses, finishes or stops. `AUTOBUILD_CONTROL` pauses, resumes or stops it and `AUTOBUILD_SET_OPTIONS` changes its
   options (the owner, or an admin): the speed at once, anything else puts what is left in order again and gives
   blocks skipped as in the way or without items another go.
5. **Done:** the server plays a note-block bell for the player, at the player, and sends
   `NOTICE` "AutoBuild finished: Castle (N placed, M skipped)".

## Permissions

| Permission | Paper node | Mod default | Grants |
|---|---|---|---|
| USE | `blockcompanion.use` | everyone | see the lists, download |
| UPLOAD | `blockcompanion.upload` | everyone | upload within the quota |
| PLACE | `blockcompanion.place` | everyone | share placements, move or delete unlocked placements |
| LOCK | `blockcompanion.lock` | everyone | lock and unlock your own placements |
| ADMIN | `blockcompanion.admin` | op | bypass locks, delete anything, no per-player quota |
| AUTOBUILD | `blockcompanion.autobuild` (default op) | everyone in singleplayer and on LAN, op on a dedicated server | start AutoBuild |

- **Paper, Spigot, Bukkit:** permissions use Bukkit permission nodes. `plugin.yml` sets the defaults, and `admin` grants
  the others as children.
- **Fabric, NeoForge:** permissions come from `permission.<name>` in the config, set to `everyone`, `op` or `nobody`.
  Operator means permission level 2 or higher.

## Server storage and config

| | Fabric / NeoForge | Paper |
|---|---|---|
| Data | `<world>/blockcompanion/` | `plugins/BlockCompanion/worlds/<main world>/` |
| Config | `config/blockcompanion-server.properties` | `plugins/BlockCompanion/config.properties` |

The data folder holds these files:
- `files/<sha256>.bin`
- `schematics.properties`
- `placements.properties`

The index files are rewritten through a temporary file on every change. Editing locks are not saved. Everything else
(including owner locks) survives a restart.

Config keys, with their defaults:

| Key | Default | Meaning |
|---|---|---|
| `enabled` | true | the shared space is on |
| `maxFileSizeKb` | 8192 | largest upload |
| `playerQuotaKb` | 65536 | bytes each player may keep uploaded |
| `totalQuotaKb` | 1048576 | bytes the whole shared space may hold |
| `maxPlacementsPerPlayer` | 32 | shared placements per player |
| `editLockSeconds` | 10 | an editing lock lapses this long after the last move |
| `uploadTimeoutSeconds` | 600 | how long an unfinished upload is kept |
| `chunksPerTick` | 4 | download chunks sent to each player per tick |
| `allowAutoPlace` | true | announced to clients (Milestone 3) |
| `autoPlaceRange` | 5 | announced to clients (Milestone 3) |
| `autoPlaceBlocksPerSecond` | 20 | announced to clients (Milestone 3) |
| `allowCreativeFill` | true | announced to clients (Milestone 3) |
| `allowChestBuild` | true | announced to clients (Milestone 3) |
| `allowEasyPlace` | true | announced to clients; with false, clients switch easy place off |
| `allowEasyPlaceAuto` | true | announced to clients; with false, easy place's auto mode is off (easy place itself stays) |
| `allowAutoBuild` | true | AutoBuild on or off (running ones stop when it goes off) |
| `autoBuildMaxBlocksPerSecond` | 20 | the fastest AutoBuild may place; players pick their speed up to this |
| `autoBuildReplace` | keep on a server, clear in singleplayer and on LAN | the most AutoBuild may break: `keep` (never), `solid` (solid blocks in the way), `all` (any block in the way, chests too) or `clear` (also where the schematic has air) |
| `autoBuildMaxRadius` | 0 | above 0, AutoBuild builds only that near the player (0: no limit) |
| `permission.*` | see Permissions | mod servers only |

- **Missing keys:** they are written back with their defaults.
- **Reloading on Paper:** `/blockcompanion reload` re-reads the config and re-sends everyone's features.
- **Reloading on a mod server:** `ModSyncServer.reloadConfig()` exists, but no command is wired to it yet.

## Where the code is

- `core/src/main/java/io/blockcompanion/core/sync/`
  - `Protocol`: the envelope, type ids and limits.
  - `Message`: every message record.
  - `Wire`: the encoding.
  - `Hashes`, `Chunks`: splitting, reassembly and paging.
  - `Features`, `SchematicInfo`, `SharedPlacement`, `PlacementPose`, `Permission`, `LockRules`, `SyncConfig`,
    `SharedStore`, `SyncServer`, `SyncPeer`, `SyncLog`.
  - `SyncClient` and `ClientPlacementModel`: the client side.
- `core/src/main/java/io/blockcompanion/core/autobuild/`: `AutoBuildPlan` (order, steps, items, the chest check),
  `AutoBuildJob` (running one), `AutoBuildOptions` (one build's options) and `BuildWorld` (what the platform provides: `server/ModBuildWorld` on the mods,
  `PaperBuildWorld` on Paper). The client side is `client/autobuild/AutoBuildClient`, the schematic screen's
  Resources step and its options screen (`client/screen/AutoBuildScreen`, rows shared with Settings in `AutoBuildRows`).
- `mc-1.21.1/common`
  - `network/SyncPayload`, `network/SyncNetwork`.
  - `server/ModSyncServer`.
  - Client only: `network/ClientSync`, and the schematic screen's Source step on the Server side (`client/screen/LibraryScreen`).
- `mc-1.21.1/fabric`: `BlockCompanionFabricSync` (main entrypoint) and `BlockCompanionFabricSyncClient`.
- `mc-1.21.1/neoforge`: `BlockCompanionNeoForgeSync`, a second `@Mod` entrypoint for both sides, and
  `NeoForgeSyncClient`.
- `paper`: `BlockCompanionPlugin`.
- `mc-26.3`: the same classes, adapted to 26.3's API. There, NeoForge's client side is its own
  `@Mod(dist = CLIENT)` class, and the client payload handler is registered through `RegisterClientPayloadHandlersEvent`.
