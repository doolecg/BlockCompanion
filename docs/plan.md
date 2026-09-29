# BlockCompanion: plan

Status (2026-09-27): **Milestone 1 built, waiting for the in-game check by hand.** The core library, Minecraft 1.21.1
and 26.3 (Fabric + NeoForge each), 26.2 (Fabric, and NeoForge since 0.3.2) and the Paper plugin build; every
client was launched into a world with saved placements and passed the easy-place self-test. The building experience,
several placements at once, the new HUD and screens, linked chests and the live link to BlockDesigner are built too.
See each milestone's checklist and [Round 2](#round-2-several-placements-hud-chests-and-the-live-link).

BlockCompanion is an in-game companion to [BlockDesigner](https://github.com/doolecg/BlockDesigner): a Litematica-style
schematic mod that is lighter and easier to use, for Fabric, NeoForge and Paper servers, on Minecraft
1.21.1 and 26.3.

## Architecture

- `core`: plain Java 21, no Minecraft classes. Schematic IO (`.bdproj` read, `.schem` read/write, `.litematic` and
  `.nbt` read, ported from BlockDesigner),
  block model, transforms with block-state rotation, the placement and slice model, world-vs-schematic compare, block to
  item counting (ported from Resource Tracker), and the sync protocol messages.
- `mc-1.21.1/{common,fabric,neoforge}`, `mc-26.2/{common,fabric}` and `mc-26.3/{common,fabric,neoforge}`: Architectury
  multiloader, one group per Minecraft version. Rendering, input and screens only; the logic is in `core`, whose sources are compiled into each
  group's `common` module. The two groups are separate copies of a thin layer (26.3 changed rendering, input, GUI and
  key mappings too much to share source).
- `paper`: Bukkit-API plugin speaking the same protocol over plugin messaging on `blockcompanion:main`.

## Milestone 1: client core (1.21.1 Fabric + NeoForge first, then 26.3)

- [x] Core: formats (read/write `.litematic`, `.schem` v2/v3, `.nbt`), model, transforms incl. block-state rotation
- [x] Core: placement (offset, 90° rotations, mirror), layers (build-up / single level, step up/down), compare
  (missing / wrong / extra), item counting, placement save format, ray vs box
- [x] Core tests: format round-trips on BlockDesigner's `testdata` files, transforms, slices, compare, Resource Tracker's
  item expectations
- [x] Schematic library folder `<game dir>/blockcompanion/schematics` and load screen (key B); loads in front of you
- [x] Ghost rendering per 16³ world section, rebuilt only for changed sections, frustum-culled, game block models
- [x] Clean viewport: bounding box, faint missing ghosts, red wrong, orange extra; nothing else
- [x] Placement controls: Alt+scroll moves along the looked-at face's axis, Ctrl+scroll rotates 90°, mirror key (M);
  modifiers set in `config/blockcompanion.properties`; keys rebindable in Controls
- [x] Layers: PgUp / PgDn step the level, Insert toggles single level / build-up; HUD line only while slicing
- [x] Resource list screen (key N): needed, in inventory, missing; most missing first
- [x] Placement saved per world/server and restored on rejoin
- [x] 1.21.1 Fabric and NeoForge `runClient`: start, join a world, restore a saved placement, no errors in the log
- [x] 26.3 Fabric and NeoForge port (same features; `runClient` joins a world and restores a saved placement with no
  errors in the log; the 26.3 Fabric mod also starts on a dedicated server)
- [ ] In-game check by hand (loading, moving, rotating, layers, resource list) on every loader/version

Milestone 1 notes:
- Versions: Architectury Loom 1.17.493 (`loom-no-remap` for 26.3), Architectury plugin 3.5.170, Gradle 9.7.1, Fabric
  Loader 0.19.5; 1.21.1: Fabric API 0.116.17+1.21.1, NeoForge 21.1.252; 26.3: Fabric API 0.161.0+26.3, NeoForge
  26.3.0.23-beta. No Architectury API runtime dependency: the loaders' own events feed a small shared client class.
- 1.21.1 keeps each section's ghosts in GPU vertex buffers. 26.3 renders through extracted state and submit nodes, so
  there the recorded quads are handed to the submit collector every frame (meshing is still per section and only on
  change). Moving 26.3 to GPU-resident buffers is a possible later optimisation.
- Fluids, block entity shapes and quad sorting came later: see [Building experience](#building-experience-ghosts-easy-place-progress).
- Scroll direction: scrolling up pushes the box away from you along the looked-at face's axis. Mirror flips the
  schematic across its own X axis.
- The resource list counts the whole schematic (not the slice) and only the player's inventory (not shulker boxes or
  chests); unloaded chunks count as not built.

## Formats: BlockDesigner's own first

Details in [`docs/formats.md`](formats.md). BlockDesigner projects (`.bdproj`) and Sponge Schematic v3 (`.schem`) are
the main formats between the game and the app; `.litematic` and `.nbt` still load, for compatibility only.

- [x] Core `project.BdProject`: reads `.bdproj` format 1 (`layers/<id>.litematic`) and format 2 (`layers/<id>.schem`);
  refuses a newer format with a clear message; ignores other zip entries; layer name, visibility, lock, ghost, colour,
  offset, rotation and mirror; `readInfo` reads only `project.json`
- [x] Merging the visible layers in project coordinates, a port of BlockDesigner's `Scene.flatten`: block states turn
  with the layer, block entities follow, entities turn about block centres (position, yaw, hanging facing)
- [x] Library: `.bdproj` and `.schem` listed first and marked green; a project loads as one placement (visible layers
  merged); hovering it lists its layers; the resource list, compare, rotate/mirror/move and the saved placement work
  for it as for any file
- [x] Saving from the game as Sponge v3 `.schem` in the library: corner key (K) twice, save key (O) with a name prompt;
  without corners the loaded schematic's box is saved. Block entities from the integrated server in singleplayer
  (chest contents), from the client otherwise; entities except players; unloaded chunks left out; 8M-block limit;
  replacing a file needs a second click
- [x] Sync: `.bdproj` accepted by the shared space (the server's name check), shared and downloaded byte for byte by
  hash like the other formats
- [x] Core tests: a real format-1 project (copied from a BlockDesigner save), formats 1 and 2 written the way
  `ProjectFile` writes them, rotation/mirror/offset checked by hand and against BlockDesigner's own placement rule,
  hidden layers, later layers winning, a format 3 error, extra entries, the library order, `.schem` save round trip
  with block entities and entities, file-name cleaning, the selection, and share/download of `.bdproj` and `.schem`
- [x] 1.21.1 and 26.3, Fabric and NeoForge build; 1.21.1 Fabric `runClient` with `BLOCKCOMPANION_FORMATS_SELFTEST=1`
  saves a box through the save path and loads every `.bdproj`/`.schem` in the library with no errors in the log
- [ ] In-game check by hand of the save screen, the corner outline and the load screen on every loader/version

Formats notes:
- BlockCompanion only reads `.bdproj`; it never writes one. Saving always writes `.schem`.
- A saved schematic is trimmed to the blocks' bounds (air at the edges of the box is dropped).
- The load screen shows each project's layers on hover; there is no per-layer toggle in the game yet (a project's
  hidden layers stay hidden).
- `BLOCKCOMPANION_FORMATS_SELFTEST=1` (dev check) saves `blockcompanion-selftest.schem` next to the player and logs
  every preferred file it loads; it doesn't touch the placement.

## Milestone 2: server sync (like Syncmatica)

The protocol is described in [`docs/sync-protocol.md`](sync-protocol.md).

- [x] Protocol in `core` (`io.blockcompanion.core.sync`):
  - versioned envelope and 20 message types;
  - every message at or under 30000 bytes, which is under the Bukkit plugin-message and serverbound payload limits;
  - 16 KiB file chunks and paged lists.
- [x] Upload and download:
  - any library file: `.bdproj`, `.schem`, `.litematic`, `.nbt` (and `.schematic`);
  - chunked, SHA-256 checked, resumable and deduplicated by hash;
  - chunks may arrive out of order or twice;
  - per-player and total quotas and a file size limit, from the server config.
- [x] Shared placements:
  - file hash, position, rotation, mirror, owner and name;
  - owner lock (only the owner or an admin can move or delete);
  - a temporary editing lock while someone moves a placement, broadcast to everyone, which lapses after
    `editLockSeconds`.
- [x] Permissions:
  - Paper: nodes `blockcompanion.use/upload/place/lock/admin`, with the defaults in `plugin.yml`;
  - Fabric and NeoForge: config (`everyone`, `op` or `nobody`, op = permission level 2).
- [x] Feature announcement on join: sync on/off, maximum size, quota, permissions, and the Milestone 3 flags
  (`auto_place` with its range and rate, `creative_fill`, `chest_build`).
- [x] Server storage per world, which survives restarts:
  - mods: `<world>/blockcompanion/`;
  - Paper: `plugins/BlockCompanion/worlds/<main world>/`.
- [x] Server side:
  - Fabric and NeoForge (dedicated, and integrated or LAN) through `ModSyncServer`;
  - Paper through `BlockCompanionPlugin` (plugin messaging, Bukkit API only, `/blockcompanion
    info|reload`).
- [x] Client side:
  - the shared-space screen (key J): share your loaded placement, load a shared one (downloaded into
    `schematics/shared/`), lock/unlock, delete, unlink;
  - the linked placement follows other players' moves, and your moves are sent (with a local lock check that snaps
    back if the move isn't allowed);
  - `ClientPlacementModel` in `core` is the interface to the client placement.
- [x] Core tests (48): message round trips, malformed and cross-version input, chunking and reassembly (out of order,
  duplicates, hash mismatch, resume), paging, lock rules, quota, permission config, server flows (upload, dedupe,
  quota, resume, expiry, download streaming, placements, locks, delete rules, restart persistence), and client and
  server over a loopback (share, load, follow both ways, snap back, unlink).
- [x] 1.21.1 and 26.3, Fabric and NeoForge: the jars build; `runServer` logs "shared space ready" with no errors;
  `runClient` into a singleplayer world with `BLOCKCOMPANION_SYNC_SELFTEST=1` completes hello, a 40 KiB upload
  (3 chunks, hash-checked) and a shared placement over the real payload.
- [x] Paper jar builds; `plugin.yml` parsed with Bukkit's `PluginDescriptionFile`.
- [ ] Multiplayer check by hand with two clients: on a dedicated mod server and on Paper, share, load, move, lock
  (including a NeoForge client on Paper, which relies on NeoForge announcing the optional channel to non-NeoForge
  servers).

Milestone 2 notes:
- Hooks in shared files, each a single line:
  - both `fabric.mod.json`: a `main` entrypoint (`BlockCompanionFabricSync`) and a second `client` entrypoint
    (`BlockCompanionFabricSyncClient`);
  - both `en_us.json`: the shared-screen strings.
  NeoForge needs no hook: `BlockCompanionNeoForgeSync` is a second `@Mod` class, which FML supports. On 26.3 there is
  also a client `@Mod(dist = CLIENT)` class, `NeoForgeSyncClient`.
- Not done yet:
  - no in-game command on mod servers to reload the config (`ModSyncServer.reloadConfig()` exists);
  - a player's permission changes are only picked up on the next message or rejoin.
- Following a shared placement:
  - the player loads a placement in the dimension it lives in (other dimensions are refused with a message);
  - the client has one placement, so it follows one shared placement at a time;
  - other shared placements are listed, not drawn.
- Finding a local copy before downloading hashes the library files once, then caches the hashes by size and
  modification time. A huge library makes the first lookup slow.
- Dev runs created `eula.txt` in `mc-1.21.1/neoforge/run` and `mc-26.3/neoforge/run` (the Fabric run folders already
  had one). The self-test left one shared schematic and placement in each dev world's `blockcompanion/` folder.

## Building experience: ghosts, easy place, progress

Near-solid ghosts that the world replaces, placing straight into ghost cells, and live progress in the game and for the
app.

- [x] Ghosts drawn with the game's block models at high opacity (`ghost.alpha`, default 0.85, 0.3 to 1), lit by the
  world at their cell with ambient occlusion, with a slight cool tint and a slow pulse (`ghost.shimmer`)
- [x] Translucency without see-through artefacts: 1.21.1 sorts each section's ghost quads back to front (re-sorted when
  the camera moves a block, nearest sections first) and draws sections far to near; 26.3 draws them through the game's
  translucent moving-block type, which sorts on upload every frame
- [x] Real shapes: fluids (water and lava sources) through the fluid renderer; 1.21.1 chests, signs, hanging signs,
  beds, banners, heads, shulker boxes, decorated pots, bells, lecterns, conduits and enchanting tables through their
  block entity renderers (sign text from the schematic when it is in the current format); 26.3 chests, heads, banners,
  shulker boxes, bells, pots, conduits, enchanting tables and the rest through the game's special block models, signs,
  hanging signs and lecterns through their renderers (beds are ordinary block models in 26.3)
- [x] The world replaces the ghost: correct cells show nothing, wrong blocks get a red tint and outline, extra blocks
  orange; single block changes (any player's, and the client's own prediction) update the progress at once, and the
  section re-meshes as before
- [x] Easy place (key H, `easyPlace.enabled`): ray against the missing cells of the visible layers (nearer than the real
  block looked at), plan the click in core (`EasyPlacePlanner`), check each candidate against the real item's placement
  rule, send a rotation packet when the block needs a facing, the normal use-item-on packet, and the rotation back;
  a second click on a single slab makes a double one; wrong block in hand: nothing placed, a "Needs ..." hint
- [x] Server switch: `allowEasyPlace` in the server config, announced as `easy_place` (a server that doesn't send the
  key allows it); with it off, easy place turns off with a message
- [x] Pick block (middle click) on a ghost: hotbar slot, swap from the inventory, or (creative) a new stack
- [x] Core progress tracker (`progress.ProgressTracker`): totals and per item, per level range and whole, updated per
  block change or section rescan; unloaded chunks keep their last known state ("last seen"); saved per world in
  `placements/<world>.progress` and restored for the same schematic and pose
- [x] In game: progress HUD (top left: bar, percentage, blocks left, wrong count; the current layer's own bar while
  slicing), the resource list with "placed" and "still to get" (needed − placed − carried) and a whole/visible-layers
  switch, a "Should be ..." hint on a wrong block, and the material helper (nearest ghosts needing the held block,
  "N left")
- [x] To the app: `~/.blockcompanion/progress/<name>-<hash12>.json` ([format](progress-format.md)), atomic write
  (`.json.tmp` then move), at most every 2 s while changing, a heartbeat every minute, and on unload/quit
- [x] Effects (all in the config): chime rising with a combo and sparkles per correct block, a low note for a wrong
  one, layer toast with sparkles along the level and auto-advance, fireworks and a summary (time, blocks, accuracy)
  when finished; sounds in the Blocks category
- [x] Core tests: progress tracker (incremental updates, extras, levels and completion, unloaded chunks, saving),
  progress file (fields, names, escaping, atomic write, throttle and heartbeat), easy-place planning (stairs, slabs and
  double slabs, logs, doors and hinges, trapdoors, facing blocks, pistons, observers, hoppers, signs, heads, buttons,
  wall torches), the ghost ray, and the material helper query
- [x] `runClient` on all four (1.21.1 and 26.3, Fabric and NeoForge) with `BLOCKCOMPANION_PLACE_SELFTEST=1`: a test
  schematic is filled through easy place (11 of 11 placeable cells correct on each), the tracker and the progress file agree, a wrong block
  is counted and highlighted, and in-game screenshots (`run/screenshots/bc-selftest-*.png`) show the ghosts, the red
  highlight, the layer toast and the HUD
- [ ] In-game check by hand (easy place in survival on a vanilla and a Paper server, the look at night and underground,
  the sounds, which the dev runs keep muted)

Notes:
- Still approximated: block entity ghosts and 26.3 special-model ghosts draw opaque and untinted (their render types
  don't blend; a faint cyan outline marks them as not built); beacons, spawners, end gateways and the like show their
  block model only; flowing fluids aren't drawn (only sources count as needed); blocks nothing can draw (barriers,
  light blocks, unknown modded blocks) are a faint box. 1.21.1 loads sign text from the schematic only in the current
  format (older formats would make the game log an error per sign); 26.3 draws ghosts with default block entity data.
- Easy place needs the block's own placement rule to be able to produce the state from one click on an empty cell.
  Properties that follow from neighbours (stairs shape, fence and wall connections, chest pairing, door hinges next to
  other doors) come out as they would for a player. Blocks that need support (torches, doors, buttons) still need it.
  Signs open the sign editor as usual.
- Normal placement against ghost faces when easy place is off is not done: vanilla placement is unchanged then.
- 1.21.1 and 26.3 differences: 1.21.1 keeps ghost meshes in GPU buffers and re-sorts them itself; 26.3 replays recorded
  quads into the submit collector each frame and the game sorts them. In 26.3 the bounding box and outlines draw
  before the translucent ghosts, so edges behind a ghost stay faintly visible; in 1.21.1 they are hidden. 26.3 cannot
  tint special-model ghosts (chests, heads, banners), 1.21.1 tints block entity ghosts through their vertex colours.
- The dev self-test backs up and restores the world's saved placement, flies the player 20 blocks up to work away
  from any build, switches the player to creative for the test (and back), and deletes its schematic and progress file
  afterwards. `BLOCKCOMPANION_SELFTEST_QUIT=1` closes the
  game after it. On 26.3 the dev client's shutdown watchdog reports two lingering thread pools after closing; the
  world has saved by then, and BlockCompanion starts no threads.

## Milestone 3: building help

- [ ] Auto-place (client-side printer), limited by range and speed, only where the server allows it (server announces
  permitted features)
- [ ] Creative fill
- [x] Building from linked chests: link chests with the selection tool (Ctrl+right-click); a BlockCompanion server
  reads them and moves items into the player's inventory when easy place needs a block they don't carry (see Round 2)

## Milestone 4: Resource Tracker link

- [x] Shared file, game to app: `~/.blockcompanion/progress/<name>-<hash12>.json` with the live progress of each loaded
  schematic ([format](progress-format.md)); Resource Tracker reads it (updated in its own repo)
- [ ] Shared files, app to game: read Resource Tracker's per-project `projects/<hash>.json`
- [x] Live: loopback connection with a token from the instance file ([link protocol](link-protocol.md)); the app sends
  its open project (on request, on the game's Grab button, and after every change while Live is on); the game sends its
  placements, progress and linked chests
- [x] App side: Resource Tracker 1.2.0 (game list, Send, Live, Refresh, Install mod, Use its textures) in its own repo;
  plugin API 6 in BlockDesigner 0.4.24 (read and set the resource packs)

## Round 2: several placements, HUD, chests and the live link

Asked for on 2026-09-27. Built and checked in dev runs; the hand check in the game is still to do.

- [x] **Selection tool:** a stick (`tool.item`, any item or off): left-click corner 1, right-click corner 2, sneak +
  right-click a container links or unlinks it; it never mines or uses blocks while aimed at one (mixins on
  `startAttack`, `continueAttack`, `startUseItem`)
- [x] **Several placements:** `LoadedPlacement` per schematic (its own layers, locks, visibility, ghosts and progress);
  Load adds one, Unload removes one; the keys act on the looked-at box, else the selected one; saved per world in
  `placements/<world>/<slot>.properties` and `<slot>.progress` (`SavedPlacements`, the old single file becomes slot 1)
- [x] **Locks:** position, rotation, mirror and layers (`PlacementLock`), presets in the list (Unlocked, Position, In
  place, Everything) and **Y** for in place; a locked box has a blue outline
- [x] **Schematic list** in the game's list style: library and loaded side by side, Load / Hide / Lock / Bring here /
  Unload, Live, Grab from BD, Resources, Settings
- [x] **HUD:** an info panel in the game's tooltip frame (Jade-like), bottom left, with bars drawn like the game's that
  fill red, orange, yellow, green; a small hint without background left of the crosshair; both movable and sizeable in
  the HUD editor (`HudLayout`: anchor, pivot, offset, scale)
- [x] **Settings screen** (vanilla option list) with every option, applied and saved at once; Key binds and the HUD
  editor from it; Mod Menu (Fabric) and NeoForge's config button open it
- [x] **Resource list:** Chests column, a red-to-green bar per row, a schematic picker, Refresh
- [x] **Linked chests** (`LinkedChests`, `ChestTracker`): contents from the server (`CHEST_LINK`, `CHEST_CONTENTS`,
  `CHEST_RESTOCK` sync messages, `ChestAccess` on mod servers and Paper, links kept in `chests.json`) or from opening
  the chest; restock asks for `chests.restockCount` items and easy place places once they arrive
- [x] **Live link** (`GameLink`, `LinkServer`, `InstanceInfo`): clients and dedicated/Paper servers write an instance
  file with port, token, packs and game folder; projects land in `schematics/BlockDesigner/` (servers: the shared space,
  where shared placements of an older file of the same name switch to the new one and followers reload it)
- [x] **Resource packs:** the game reports its enabled packs and the server's; ghosts rebuild when the game re-meshes
  everything (new packs, F3+T)
- [x] **Wrong-block note:** only for changes next to a block the player clicked in the last 1.5 s (`OwnPlacements`)
- [x] **Minecraft 26.2** (Fabric): a copy of the 26.3 group with the few API differences
- [x] Core tests (link server and instance files, projects, several placements and migration, locks, linked chests,
  chest sync and paging, files from the app, HUD layout, gradient, own placements): 187 tests in the core in all
- [x] Dev runs: easy-place self-test 11 of 11 on 1.21.1 Fabric and NeoForge, 26.2 Fabric, 26.3 Fabric and NeoForge;
  `BLOCKCOMPANION_CHEST_SELFTEST=1` links a chest, reads it through the integrated server and fetches 20 stone (1.21.1
  and 26.3 Fabric); a scripted app sent a project to a running 1.21.1 game, which loaded it next to the saved placement
- [ ] In-game check by hand: the tool, several placements and locks, the HUD editor at different GUI scales, the
  settings screen, chests on a Paper server, Resource Tracker's Send / Live / Install / Use its textures with
  BlockDesigner 0.4.24

Round 2 notes:
- The instance file lives in the user's home folder; only that user can read the token, which is the pairing.
- Chest contents without a BlockCompanion server are what the chest held when last opened.
- Resource packs of a server that only has a URL (a dedicated server) are downloaded by Resource Tracker into its data
  folder before use.
- The 26.3 NeoForge dev client can hang in the game's shutdown watchdog after closing (as before this round).

## Round 3: the schematic screen workflow, settings and the link from the game

Asked for on 2026-09-27: "an option to live link from the mod too", "an easy workflow from loading (server or
locally) > placement status, locked/unlocked > resources needed / chest links > BlockDesigner options", and "an easier
settings menu, more modern but Minecraft, easy to read".

- [x] **Shared look** (`client.screen.Ui`, `OptionList`): dark bevelled panels, a title band, gold section headings with
  a rule, status lights, one option per row (label and a short description left, control right; the full text on
  hover), colours from `core.hud.Colors`. Used by the settings, the schematic screen, the resource list, the save and
  colour screens.
- [x] **Schematic screen (B) in four steps** (Source, Placement, Resources, BlockDesigner) with a step bar showing each
  step's state and Back / Next. The shared-space screen is merged into Source (its **Server** side; the J key is gone);
  the resource list is shared with the N screen (`ResourceList`).
- [x] **Settings** as sections down the left (a single stepping button on small screens), a scrolling column of rows,
  a **Keys** section that rebinds BlockCompanion's keys in place, and the BlockDesigner section.
- [x] **Live link from the game** (`LinkPanel`, `ClientLink.state()`): start / stop, status (off, waiting, connected
  to which app, since when), the linked project, Get project, Send now; `link.enabled` now only means "start with the
  game". `GameLink` can stop and start again keeping its id and token, reports connections and the last project, and
  understands an optional `app-status` from the app ([link protocol](link-protocol.md)).
- [x] Core tests for restarting the link, connection details, `app-status` and sending the status now.
- [x] 1.21.1, 26.2 and 26.3 build; `BLOCKCOMPANION_UI_SELFTEST=1` opens every step and section and saves screenshots
  (`screenshots/bc-ui-*.png`); checked at GUI scale 2 in a small window and scale 4 at 1080p on 26.3, and on 1.21.1.
- [ ] Resource Tracker sending `app-status` (a BlockDesigner-side change) so the game can name the open project before
  anything is sent.
- [ ] In-game check by hand of rebinding keys and the step screen on NeoForge.

## Round 4: the stick's controls and the tool panel

Asked for on 2026-09-27: move the schematic only with a modifier held, set corners with Alt, and show the tool's mode
in a panel like the info panel instead of a line above the hotbar.

- [x] **Scrolling with the stick:** Shift+scroll does the tool's mode on the looked-at box, Ctrl+scroll turns it 90°,
  Ctrl+Shift+scroll switches the mode; plain scroll is the hotbar again. The modes are cut to **Move** and **Mirror**
  (`ToolMode`; old saved ROTATE, LAYER and VISIBILITY load as MOVE). The selection only moves.
- [x] **View key (V):** cycles the looked-at schematic's view: everything, layers up to here, this layer only, only this
  schematic, hidden. It replaces the Layers and Show / hide modes; undo steps through it like before.
- [x] **Clicks:** Alt+left / right-click set corners 1 / 2 (`tool.corner.modifier`), Shift+right-click clears the
  selection (`tool.clear.modifier`), Ctrl+right-click a chest links or unlinks it (`tool.link.modifier`, replaces
  sneak + right-click). `scroll.mode.modifier` is gone; config version 3 moves `scroll.move.modifier` from ALT to SHIFT.
- [x] **Tool panel** (`HudLayout.Element.TOOL`, `hud.toolPanel`): bottom right in the tooltip frame while the stick is
  in hand, with the mode, its one-line hint and the controls; movable and sizable in the HUD editor. A line above the
  hotbar says the new mode only when the panel is off.
- [x] Settings rows for each control (Building tab) and the Tool panel switch (HUD tab), in 1.21.1, 26.2 and 26.3.
- [ ] In-game check by hand of the new controls and the tool panel.
