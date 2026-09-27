<p align="center">
  <img src="docs/images/logo.png" alt="BlockCompanion logo" width="128" height="128">
</p>

<h1 align="center">BlockCompanion</h1>

<p align="center">
  A light schematic mod for building in Minecraft: ghost blocks, Create-style placement, layers and a resource list,<br>
  with a clean view. The in-game companion to BlockDesigner.
</p>

<p align="center">
  <a href="https://github.com/doolecg/BlockCompanion/releases/latest"><img alt="Latest release" src="https://img.shields.io/github/v/release/doolecg/BlockCompanion?label=release"></a>
  <a href="https://github.com/doolecg/BlockCompanion/releases"><img alt="Downloads" src="https://img.shields.io/github/downloads/doolecg/BlockCompanion/total"></a>
  <a href="LICENSE"><img alt="License: MIT" src="https://img.shields.io/github/license/doolecg/BlockCompanion"></a>
  <img alt="Minecraft 1.21.1, 26.2 and 26.3" src="https://img.shields.io/badge/Minecraft-1.21.1%20%7C%2026.2%20%7C%2026.3-62B47A">
  <img alt="Fabric, NeoForge, Paper" src="https://img.shields.io/badge/loaders-Fabric%20%7C%20NeoForge%20%7C%20Paper-DBB36B">
</p>

---

BlockCompanion shows a schematic in your world as ghost blocks so you can build it. It does what
[Litematica](https://modrinth.com/mod/litematica) does for placing and checking a build, with less on screen and
simpler controls. It reads the schematics [BlockDesigner](https://github.com/doolecg/BlockDesigner) (the Windows
editor for Minecraft builds) saves, whole projects included, and saves builds from the world back as `.schem` files
BlockDesigner opens. With BlockDesigner's Resource Tracker plugin it links live: send the project you're working on
into the game, see the game follow your edits, and see in the app what is built and what your chests hold.

It is a client mod for **Fabric** and **NeoForge** on **Minecraft 1.21.1 and 26.3** (and Fabric on 26.2), plus a
**Paper/Spigot/Bukkit** plugin for servers without mods. It is in early development; the [plan](docs/plan.md) lists
what comes next.

**Contents:** [Download](#download-and-install) · [Features](#features) · [Building from source](#building-from-source) · [Project layout](#project-layout)

## Download and install

Get the latest version from the [releases page](https://github.com/doolecg/BlockCompanion/releases/latest). Every
release has one jar per loader and Minecraft version:

| File | For |
|---|---|
| `blockcompanion-fabric-1.21.1-<version>.jar` | Fabric, Minecraft 1.21.1 (needs [Fabric API](https://modrinth.com/mod/fabric-api)) |
| `blockcompanion-neoforge-1.21.1-<version>.jar` | NeoForge, Minecraft 1.21.1 |
| `blockcompanion-fabric-26.2-<version>.jar` | Fabric, Minecraft 26.2 (needs Fabric API) |
| `blockcompanion-fabric-26.3-<version>.jar` | Fabric, Minecraft 26.3 (needs Fabric API) |
| `blockcompanion-neoforge-26.3-<version>.jar` | NeoForge, Minecraft 26.3 |
| `blockcompanion-paper-<version>.jar` | Paper, Spigot or Bukkit servers (goes in `plugins`) |

1. Download the jar for your loader and Minecraft version (NeoForge made no 26.2 version, so 26.2 is Fabric only).
   Or let BlockDesigner do it: Resource Tracker's **Install mod…** puts the right jar into a game's `mods` folder.
2. Put it in your `mods` folder.
3. Put your BlockDesigner projects (`.bdproj`) and schematics in `<game folder>/blockcompanion/schematics` (the folder
   is made the first time you start the game, and the load screen has an **Open folder** button).

- **Updates:** BlockCompanion checks this repository for a newer release when the game starts and every few hours,
  says so in chat, and installs it from the settings screen's **Updates** tab when you quit the game. The check can be
  switched off there.

## Features

### Schematics

- **BlockDesigner's formats first:** BlockDesigner projects (`.bdproj`) and Sponge schematics (`.schem`, v2 and v3)
  are the main formats, so you don't need Litematica or Create files. Litematica (`.litematic`) and vanilla structure
  (`.nbt`) files still load. [Formats](docs/formats.md) explains the choice.
- **The schematic screen (B)** walks through four steps, each usable on its own:
  1. **Source:** every schematic in `blockcompanion/schematics` (subfolders included; projects and `.schem` files first,
     marked green), or on a BlockCompanion server the schematics and placements shared there. **Load** puts one just in
     front of you, **Unload** removes every copy of it from the world, **Delete** removes the file (after asking);
     **From BlockDesigner** asks the app for the project it has open.
  2. **Placement:** what is loaded in this world, where it is, how far it is built and whether it is locked, with lock
     presets (unlocked, position, in place, everything), show / hide, bring here, turn, mirror, follow BlockDesigner
     (Live) and **Unload**.
  3. **Resources:** what the build still needs against your inventory and linked chests (the same list as **N**).
  4. **BlockDesigner:** the live link: off, waiting or connected (and to which app), the linked project, Start / Stop,
     Get project and Send now.
- **Locking:** so a schematic can't be knocked out of place: **Position**, **In place** (position, turning and
  mirroring) or **Everything** (the layer view too), from the Placement step or with **Y** on the box you look at. A locked box
  has a blue outline, and scrolling or **M** on it says what is locked.
- **Whole projects:** a `.bdproj` loads as one schematic: its visible layers merged, each with its own offset, rotation
  and mirror as in BlockDesigner. Hidden layers are left out. Hover a project in the list to see its layers.
- **Save from the world:** hold a **stick** and left-click one corner and right-click the opposite one (or press **K**
  on each; a cyan outline shows the box), then **O**, type a name and save. The stick never breaks or uses the block
  you click while selecting; another item can be the tool in the settings. It is written as a Sponge v3 `.schem` in the schematic folder, with chest contents, signs and entities, ready
  to open in BlockDesigner. Without marked corners, **O** saves the loaded schematic's box: what you have built so far.
- **Saved per world:** every loaded schematic, where it is, how it's turned, the layer you're on and what's locked are
  kept for each world and server, and come back when you rejoin.

### A clean view

- Only what differs from the world: **near-solid ghosts** where a block is missing, **red**
  (tint and outline) over a different block (or the right block facing the wrong way), **orange** where something is
  in the way. Grass, flowers, water and snow don't count as being in the way.
- **Boxes only with the tool:** the schematic's bounding box and the save selection show while the selection tool (a
  stick by default) is in either hand, and fade out when you put it away. Each box has faint tinted faces that breathe
  slowly, the face you look at a little brighter, a crisp outline and light corner brackets; a locked box stays blue.
  **Show boxes** on the settings screen can keep them on always. Ghosts show either way.
- **The world replaces the ghost:** a cell with the right block shows nothing, and everything updates the moment a
  block is placed or broken, by you or anyone else.
- Ghosts look **almost like real blocks**: the game's own block models, lit by the world around them with ambient
  occlusion, at 85% opacity with a slight cool tint and a slow pulse that mark them as not built yet. Chests, signs,
  beds, banners, heads, shulker boxes, bells and lecterns show their real shapes, and water and lava their real
  surface. Your resource packs (and a server's) apply to the ghosts too, and they are rebuilt when the packs change.
- **Light on the game:** meshes are built per 16×16×16 section and rebuilt only when that part of the world or the
  placement changes, a few sections per frame, nearest first; sections out of view are skipped.

### Building

- **Easy place** (on by default, **H** switches it): look at a ghost and right-click with its block to place exactly
  that block there, turned the right way (stairs, slabs, logs, doors, trapdoors, furnaces, observers...), even in
  mid-air. It sends the same click a player would, so it works in survival on vanilla and Paper servers. Without the
  block in hand it takes it from your inventory; on a BlockCompanion server (and in singleplayer) it fetches it from
  your linked chests. With nothing to place from, nothing is placed and a hint says what's needed. A BlockCompanion
  server can switch it off.
- **Pick block** (middle click) on a ghost selects its item from your hotbar or inventory (in creative it makes one).
- **Info panel** in the bottom left, in the game's tooltip frame: the schematic, a bar that fills red, orange, yellow,
  green with how many blocks are left (and wrong), the current layer's own bar while you step through layers, the
  block in your hand with how many are left and how many your chests hold, and what is locked. Chunks you walked away
  from keep their last known state.
- **Crosshair hint:** small text just left of the crosshair, no background: what a wrong block should be ("Should be
  Oak Stairs · facing north"), or which block the ghost you look at is.
- **Move and size the HUD** like Xaero's minimap: the HUD editor (from the settings screen's HUD tab) lets you drag the
  info panel and the hint anywhere, scroll or slide to size them, and switch either off. They keep to the side of the
  screen you put them on.
- **Material helper:** holding a block gently marks the nearest ghosts that need it and shows how many are left.
- **A little celebration:** a soft chime (rising with a quick run of correct blocks) and a few sparkles for each
  correct block, a low note for a wrong one you placed yourself, a toast and sparkles when a layer is done (and on to the next layer), and
  fireworks with a summary (time, blocks, accuracy) when the whole build is finished. Sounds follow the Blocks volume
  slider; each part can be switched off in the config.

### Placing (Create-style)

- Moving, turning and mirroring need the **selection tool** (a stick by default) in your hand, so scrolling through the
  hotbar never knocks a build out of place. Without it the scroll wheel works as usual. (The schematic list's buttons,
  such as Bring here, work either way; the Building tab can switch the rule off.)
- **Look at a box** (it turns yellow; the selected one is white), then **Alt+scroll** moves it one block per notch along the axis of the face
  you're looking at: scrolling up pushes it away from you, down pulls it closer. **Ctrl+scroll** turns it 90°
  (up clockwise). The hotbar doesn't change while you do.
- **Plain scroll** with the stick does what its **mode** says, and **Shift+scroll** switches the mode (shown above the
  hotbar): **Move**, **Turn**, **Mirror**, **Layers** (step through the levels) or **Show / hide** (everything, layers
  up to here, this layer only, only this schematic, hidden). The mode is remembered.
- **Ctrl+Z** undoes and **Ctrl+Y** or **Ctrl+Shift+Z** redoes, like any program: one history across every loaded
  schematic, stepping back through moves, turns, mirroring, locks, layers and hiding in the order you made them, no
  stick needed. A new change clears what could be redone; a quick scroll counts as one step. Undo won't move a locked
  placement, and on a server a shared placement's undo is sent like any other move.
- **M** mirrors it (stick in hand). Stairs, doors, rails, fences, signs and other directional blocks turn and mirror with it.
- Every key can be rebound in **Options › Controls › BlockCompanion**. The scroll modifiers (Alt, Ctrl, Shift or none)
  are on the settings screen's Building tab, with the reach (see [Settings](#settings)).

### Layers

- **PgUp / PgDn** step through the levels of the box you look at (or the selected one), like BlockDesigner's slice view. By default levels **build up** (the current
  one and everything below it); **Insert** switches to **one level** at a time. While you're not seeing them all, the
  corner shows the current level and how far it is built. Finishing the current level steps to the next one.

### Resources

- **N** opens the list of every item the build needs (a loaded project counts its visible layers): how many, how many
  are **placed** already, how many you carry, how many your **linked chests** hold, and how many are **still to get**
  (needed − placed − carried − in chests), most still to get first. Each row has a bar that fills red to green as the
  item is covered. It follows the live progress; buttons pick the schematic, switch between the whole build and the
  visible layers, and **Refresh** counts your inventory and chests again. Hover a row for the amount in stacks and
  shulker boxes.
- **Linked chests:** hold the stick, sneak and right-click a chest (or barrel, shulker box...) to link it, again to
  unlink. On a BlockCompanion server, and in singleplayer, the server reports what's inside as it changes; elsewhere
  the game remembers what it held when you last opened it. They count as materials here, in the info panel and in
  Resource Tracker. It counts items the way Resource Tracker does: a double slab
  is two slabs, a door one item, crops their seeds, wall torches torches.
- **Live link to BlockDesigner:** while it runs, BlockDesigner's Resource Tracker finds the game, sends projects to it
  and follows your placements, progress and linked chests. Start and stop it on the schematic screen's BlockDesigner
  step (or the settings); **Start with the game** decides whether it starts by itself. See the
  [link protocol](docs/link-protocol.md).
- **Resource Tracker link (files):** the progress of each loaded schematic is written to
  `~/.blockcompanion/progress/` for BlockDesigner's Resource Tracker plugin. The [progress file format](docs/progress-format.md)
  describes it.

### Live link to BlockDesigner

With BlockDesigner open and its Resource Tracker plugin (1.2.0 or later) on, the game and the app find each other on
this computer; the [link protocol](docs/link-protocol.md) describes how.

- **Send to game:** the project open in BlockDesigner appears in front of you in the game (saved as
  `schematics/BlockDesigner/<name>.bdproj`). Games and servers are listed in Resource Tracker, active or disconnected;
  tick the ones projects go to.
- **Live:** with Live on in Resource Tracker, every change you make in BlockDesigner reaches the game a moment later and
  the ghosts follow. Each loaded project has its own **Live** switch in the schematic list.
- **Grab from BD** in the schematic list (or its key) asks BlockDesigner for the project it has open.
- **Back to the app:** what is loaded, how far each build is and what your linked chests hold show in Resource Tracker;
  chest contents count as gathered there.
- **Textures:** Resource Tracker's **Use its textures** shows blocks in BlockDesigner with the game's resource packs,
  and the server's (needs BlockDesigner 0.4.24).
- **Servers** on the same computer (Fabric, NeoForge or Paper) take projects straight into their shared schematics;
  shared placements of an earlier version switch to the new one for everyone following them.
- **Install mod…** in Resource Tracker puts the latest BlockCompanion into a game's `mods` folder (or a Paper server's
  `plugins`).

### Settings

The settings screen (its key, the schematic screen's **Settings** button, Mod Menu or NeoForge's mod list) lists its
sections down the left: **Ghosts**, **Building**, **HUD**, **Effects**, **Colours**, **Keys**, **BlockDesigner** and
**Updates**. Each section is one scrolling column with an option per row: its name and a short description on the left,
its control on the right. A change applies at once and is saved, **Reset** puts a section back to its defaults, and the
screen opens on the section you last used.

- **Keys:** every BlockCompanion key, rebindable right there: click it, press the new key or mouse button (Esc leaves it
  unbound); a key another action also uses shows red, with which one. **Controls...** opens the game's own screen.

- **Colours:** every colour in the world view can be changed: the ghost tint, wrong and in-the-way blocks, the
  material helper's marks, the chest and sign outline, the boxes (selected, looked at, locked) and the save selection.
  Click one for the colour editor (red, green and blue sliders, a hex field, quick swatches, before and after), or pick
  a preset: Default, Colour-blind safe (purple and blue instead of red and orange), Vivid or Soft.
- **Updates:** BlockCompanion looks for a new release on GitHub when the game starts and every few hours, says so once
  in chat, and on the Updates tab downloads the jar for your loader and Minecraft version. It installs when you quit the
  game. **Download automatically** skips the click; the check itself can be switched off.

Everything is also in `config/blockcompanion.properties` (written with the defaults on first start):

| Key | Default | What it does |
|---|---|---|
| `ghost.alpha` | 0.85 | ghost opacity, 0.3 to 1 |
| `ghost.shimmer` | true | the cool tint and slow pulse on ghosts |
| `ghost.blockEntities` | true | chests, signs, beds, banners, heads... drawn with their real shapes |
| `easyPlace.enabled` | true | easy place (also switched with **H**) |
| `pickBlock.ghosts` | true | middle click on a ghost picks its item |
| `hud.progress` | true | the info panel |
| `materialHelper.enabled`, `materialHelper.cells` | true, 48 | marks ghosts that need the held block, at most this many |
| `effects.particles`, `effects.sounds`, `effects.combo` | true | sparkles, sounds, the rising chime |
| `effects.layerCelebration`, `layers.autoAdvance` | true | layer toast, and stepping to the next layer |
| `effects.finishCelebration` | true | fireworks and the summary |
| `progress.file` | true | writes the progress file for Resource Tracker |
| `link.enabled` | true | starts the live link to BlockDesigner with the game |
| `scroll.move.modifier`, `scroll.rotate.modifier`, `placement.reach` | ALT, CTRL, 96 | moving and turning the placement |
| `tool.requiredToMove`, `tool.mode`, `scroll.mode.modifier` | true, MOVE, SHIFT | moving only with the tool in hand, what plain scrolling does with it, and the modifier that switches that |
| `boxes.show` | tool | `tool`: boxes only while the selection tool is held; `always` |
| `color.ghost`, `color.wrong`, `color.extra`, `color.helper`, `color.blockEntity`, `color.box`, `color.boxHover`, `color.boxLocked`, `color.selection` | see the Colours tab | the colours, as `#RRGGBB` |
| `updates.check`, `updates.autoDownload` | true, false | looking for updates, and downloading them without asking |
| `easyPlace.autoPick` | true | easy place takes the block from the inventory when it isn't in hand |
| `hud.hint` | true | the small hint left of the crosshair |
| `hud.panel.*`, `hud.hint.*` | bottom left, left of the crosshair | where the HUD editor put each piece, and its size |
| `tool.item` | minecraft:stick | the selection tool; empty switches it off |
| `chests.count`, `chests.restockCount` | true, 64 | linked chests count as materials; how many easy place fetches at once |
| `link.enabled` | true | the live link to BlockDesigner |

### Planned

- **Server sync** (milestone 2, built, waiting for a multiplayer check): one shared place per server to upload, share
  and lock schematics and BlockDesigner projects, on modded servers and through the Paper plugin.
- **Building help** (milestone 3): client-side auto-place where the server allows it, and creative fill. Building from
  linked chests is done.

## Building from source

You need a JDK to run Gradle (this repository points `org.gradle.java.home` in `gradle.properties` at a Temurin 26;
change it to yours). Gradle downloads the JDKs the modules compile with (21 for the core, the Paper plugin and
Minecraft 1.21.1, 25 for 26.3) through the Foojay toolchain resolver. Then:

```
./gradlew build
```

The jars end up in:

| Jar | What it is |
|---|---|
| `mc-1.21.1/fabric/build/libs/blockcompanion-fabric-1.21.1-<version>.jar` | Fabric, Minecraft 1.21.1 |
| `mc-1.21.1/neoforge/build/libs/blockcompanion-neoforge-1.21.1-<version>.jar` | NeoForge, Minecraft 1.21.1 |
| `mc-26.2/fabric/build/libs/blockcompanion-fabric-26.2-<version>.jar` | Fabric, Minecraft 26.2 |
| `mc-26.3/fabric/build/libs/blockcompanion-fabric-26.3-<version>.jar` | Fabric, Minecraft 26.3 |
| `mc-26.3/neoforge/build/libs/blockcompanion-neoforge-26.3-<version>.jar` | NeoForge, Minecraft 26.3 |
| `paper/build/libs/blockcompanion-paper-<version>.jar` | Paper/Spigot/Bukkit plugin |

To try it in a development game: `./gradlew :mc-1.21.1:fabric:runClient` (or `:mc-1.21.1:neoforge:runClient`,
`:mc-26.2:fabric:runClient`, `:mc-26.3:fabric:runClient`, `:mc-26.3:neoforge:runClient`). `./gradlew :paper:runServer` starts a Paper 1.21.1
test server with the plugin in `paper/run`. The version is `mod_version` in `gradle.properties`.

### Tests

```
./gradlew :core:test
```

The tests cover the core library: reading and writing every format (including round-trips of the real schematics in
BlockDesigner's `testdata` folder when that repository sits next to this one), BlockDesigner projects in formats 1 and 2
(a real format 1 project, and layers checked against BlockDesigner's own placement rule), saving `.schem` files with
block entities and entities, transforms and block-state rotation,
placement maths, the layer view, comparing, item counts that match Resource Tracker's own tests, the build progress
tracker (live changes, unloaded chunks, saving), the progress file (format, name, atomic write, timing), easy place's
click planning and ghost ray, the material helper, several placements per world and their locks, linked chests
(through the sync server too), the live link (instance files, the token, projects, status) and the HUD layout.

## Project layout

| Path | What it does |
|---|---|
| `core/` | Plain Java 21, no Minecraft classes: formats (`formats`, `nbt`), BlockDesigner projects (`project`), the block model (`model`), transforms (`transform`), placement, layers, locks, saved placements, the selection and ray picking (`placement`), comparing (`compare`), item counting (`items`), build progress and the progress file (`progress`), easy place planning (`easyplace`), the schematic folder (`library`), linked chests (`chests`), the HUD layout (`hud`), the live link (`link`) and the sync protocol (`sync`) |
| `mc-1.21.1/common` | Minecraft 1.21.1 client code shared by both loaders: rendering, input, screens, mixins |
| `mc-1.21.1/fabric`, `mc-1.21.1/neoforge` | 1.21.1 entry points and mod metadata |
| `mc-26.2/common`, `mc-26.2/fabric` | The same for Minecraft 26.2 (Fabric only: NeoForge made no 26.2 build) |
| `mc-26.3/common`, `mc-26.3/fabric`, `mc-26.3/neoforge` | The same for Minecraft 26.3 |
| `paper/` | The Bukkit-API server plugin |
| `docs/plan.md` | The milestones and their status |
| `docs/formats.md` | Which schematic formats are primary and why, and the `.bdproj` layout |
| `docs/sync-protocol.md` | The server sync protocol |
| `docs/progress-format.md` | The progress file Resource Tracker reads |
| `docs/link-protocol.md` | The live link to BlockDesigner |

The `core` sources are compiled into each Minecraft version's `common` module, so every mod jar carries them; the
Minecraft modules are built with [Architectury Loom](https://github.com/architectury/architectury-loom).

## License

[MIT](LICENSE)
