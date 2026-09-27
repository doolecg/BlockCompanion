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

These structures appear inside the message bodies:

- **SchematicInfo:** hash, name, varlong size, uploader uuid, uploader name, i64 upload time (epoch ms).
- **Pose:** dimension id, i32 x, i32 y, i32 z, varint rotation (0–3), bool mirrored.
  - **Position:** x, y and z are the minimum corner of the transformed box.
  - **Rotation:** clockwise quarter turns seen from above.
  - **Mirror:** across the schematic's own X axis, applied before rotating.

  These are the same numbers as the client's `Placement`, so every client that applies them draws the same blocks.
- **SharedPlacement:** uuid id, hash, name, then the Pose fields, owner uuid, owner name, bool locked, an optional
  editor uuid (a presence byte, then the uuid), editor name, varlong revision.
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
| `permissions` | bit mask: 1 `use`, 2 `upload`, 4 `place`, 8 `lock`, 16 `admin` |
| `auto_place`, `auto_place_range`, `auto_place_rate` | Milestone 3: whether the client-side printer may run, its range in blocks, and its blocks per second |
| `creative_fill`, `chest_build` | Milestone 3: building helpers the server allows |
| `easy_place` | 1 if easy place may be used (a right-click on a ghost places exactly its block, through normal placement packets). **A missing key means 1**, unlike the others: servers from before the key existed allowed it |

The server sends `FEATURES` after the hello, again after a config reload, and again after the player's quota use
changes. Easy place reads `easy_place` from `SyncClient.features()`; Milestone 3 will read the auto-place flag the
same way.

## Flows

### Join

1. The client sends `HELLO`. It sends it once the channel is announced, then retries every 3 seconds, up to 5 times.
2. The server answers with its own `HELLO`.
   - **Versions differ:** the server sends nothing more, and the client tells the player to update.
   - **Versions match:** the server follows with `FEATURES`, the `SCHEMATIC_LIST` pages, then the `PLACEMENT_LIST`
     pages. The first page of each list has `reset` set.

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

## Permissions

| Permission | Paper node | Mod default | Grants |
|---|---|---|---|
| USE | `blockcompanion.use` | everyone | see the lists, download |
| UPLOAD | `blockcompanion.upload` | everyone | upload within the quota |
| PLACE | `blockcompanion.place` | everyone | share placements, move or delete unlocked placements |
| LOCK | `blockcompanion.lock` | everyone | lock and unlock your own placements |
| ADMIN | `blockcompanion.admin` | op | bypass locks, delete anything, no per-player quota |

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
- `mc-1.21.1/common`
  - `network/SyncPayload`, `network/SyncNetwork`.
  - `server/ModSyncServer`.
  - Client only: `network/ClientSync`, `network/SyncKeys`, `client/screen/SharedScreen`.
- `mc-1.21.1/fabric`: `BlockCompanionFabricSync` (main entrypoint) and `BlockCompanionFabricSyncClient`.
- `mc-1.21.1/neoforge`: `BlockCompanionNeoForgeSync`, a second `@Mod` entrypoint for both sides, and
  `NeoForgeSyncClient`.
- `paper`: `BlockCompanionPlugin`.
- `mc-26.3`: the same classes, adapted to 26.3's API. There, NeoForge's client side is its own
  `@Mod(dist = CLIENT)` class, and the client payload handler is registered through `RegisterClientPayloadHandlersEvent`.
