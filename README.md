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
BlockDesigner opens. It shares build progress with BlockDesigner's Resource Tracker plugin through a small file.

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

1. Download the jar for your loader and Minecraft version.
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
- **Library:** every schematic in `blockcompanion/schematics`, subfolders included. Press **B** for the list and click
  one to load it: it appears just in front of you. Projects and `.schem` files are listed first, marked green.
  **Unload** removes it.
- **Whole projects:** a `.bdproj` loads as one schematic: its visible layers merged, each with its own offset, rotation
  and mirror as in BlockDesigner. Hidden layers are left out. Hover a project in the list to see its layers.
- **Save from the world:** press **K** on two opposite corners (a cyan outline shows the box), then **O**, type a name
  and save. It is written as a Sponge v3 `.schem` in the schematic folder, with chest contents, signs and entities, ready
  to open in BlockDesigner. Without marked corners, **O** saves the loaded schematic's box: what you have built so far.
- **Saved per world:** where the schematic is, how it's turned and the layer you're on are kept for each world and
  server, and come back when you rejoin.

### A clean view

- Only the **bounding box** and what differs from the world: **near-solid ghosts** where a block is missing, **red**
  (tint and outline) over a different block (or the right block facing the wrong way), **orange** where something is
  in the way. Grass, flowers, water and snow don't count as being in the way.
- **The world replaces the ghost:** a cell with the right block shows nothing, and everything updates the moment a
  block is placed or broken, by you or anyone else.
- Ghosts look **almost like real blocks**: the game's own block models, lit by the world around them with ambient
  occlusion, at 85% opacity with a slight cool tint and a slow pulse that mark them as not built yet. Chests, signs,
  beds, banners, heads, shulker boxes, bells and lecterns show their real shapes, and water and lava their real
  surface.
- **Light on the game:** meshes are built per 16×16×16 section and rebuilt only when that part of the world or the
  placement changes, a few sections per frame, nearest first; sections out of view are skipped.

### Building

- **Easy place** (on by default, **H** switches it): look at a ghost and right-click with its block to place exactly
  that block there, turned the right way (stairs, slabs, logs, doors, trapdoors, furnaces, observers...), even in
  mid-air. It sends the same click a player would, so it works in survival on vanilla and Paper servers. With the
  wrong block in hand nothing is placed and a hint says what's needed. A BlockCompanion server can switch it off.
- **Pick block** (middle click) on a ghost selects its item from your hotbar or inventory (in creative it makes one).
- **Progress** in the top left corner: a bar with the percentage and blocks left, and the current layer's own bar while
  you step through layers. Chunks you walked away from keep their last known state.
- **Material helper:** holding a block gently marks the nearest ghosts that need it and shows how many are left.
- **A little celebration:** a soft chime (rising with a quick run of correct blocks) and a few sparkles for each
  correct block, a low note for a wrong one, a toast and sparkles when a layer is done (and on to the next layer), and
  fireworks with a summary (time, blocks, accuracy) when the whole build is finished. Sounds follow the Blocks volume
  slider; each part can be switched off in the config.

### Placing (Create-style)

- **Look at the box** (it turns yellow), then **Alt+scroll** moves it one block per notch along the axis of the face
  you're looking at: scrolling up pushes it away from you, down pulls it closer. **Ctrl+scroll** turns it 90°
  (up clockwise). The hotbar doesn't change while you do.
- **M** mirrors it. Stairs, doors, rails, fences, signs and other directional blocks turn and mirror with it.
- Every key can be rebound in **Options › Controls › BlockCompanion**. The scroll modifiers (Alt, Ctrl, Shift or none)
  are on the settings screen's Building tab, with the reach (see [Settings](#settings)).

### Layers

- **PgUp / PgDn** step through the levels, like BlockDesigner's slice view. By default levels **build up** (the current
  one and everything below it); **Insert** switches to **one level** at a time. While you're not seeing them all, the
  corner shows the current level and how far it is built. Finishing the current level steps to the next one.

### Resources

- **N** opens the list of every item the build needs (a loaded project counts its visible layers): how many, how many
  are **placed** already, how many you carry, and how many are **still to get** (needed − placed − carried), most still
  to get first. It follows the live progress, and a button switches between the whole build and the visible layers.
  Hover a row for the amount in stacks and shulker boxes. It counts items the way Resource Tracker does: a double slab
  is two slabs, a door one item, crops their seeds, wall torches torches.
- **Resource Tracker link (files):** the progress of each loaded schematic is written to
  `~/.blockcompanion/progress/` for BlockDesigner's Resource Tracker plugin. The [progress file format](docs/progress-format.md)
  describes it.

### Settings

The settings screen (its key, Mod Menu or NeoForge's mod list) has a tab for each part: **Ghosts**, **Building**,
**HUD**, **Effects**, **Colours**, **Link** and **Updates**. A change applies at once and is saved, **Reset tab** puts a
tab back to its defaults, and the screen opens on the tab you last used.

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
| `hud.progress` | true | the progress bar |
| `materialHelper.enabled`, `materialHelper.cells` | true, 48 | marks ghosts that need the held block, at most this many |
| `effects.particles`, `effects.sounds`, `effects.combo` | true | sparkles, sounds, the rising chime |
| `effects.layerCelebration`, `layers.autoAdvance` | true | layer toast, and stepping to the next layer |
| `effects.finishCelebration` | true | fireworks and the summary |
| `progress.file` | true | writes the progress file for Resource Tracker |
| `scroll.move.modifier`, `scroll.rotate.modifier`, `placement.reach` | ALT, CTRL, 96 | moving and turning the placement |
| `color.ghost`, `color.wrong`, `color.extra`, `color.helper`, `color.blockEntity`, `color.box`, `color.boxHover`, `color.boxLocked`, `color.selection` | see the Colours tab | the colours, as `#RRGGBB` |
| `updates.check`, `updates.autoDownload` | true, false | looking for updates, and downloading them without asking |

### Planned

- **Server sync** (milestone 2, built, waiting for a multiplayer check): one shared place per server to upload, share
  and lock schematics and BlockDesigner projects, on modded servers and through the Paper plugin.
- **Building help** (milestone 3): client-side auto-place where the server allows it, creative fill, and building from
  linked chests.
- **Resource Tracker link** (milestone 4): the shared progress file is written; a live link while BlockDesigner is open
  is still planned.

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
`:mc-26.3:fabric:runClient`, `:mc-26.3:neoforge:runClient`). `./gradlew :paper:runServer` starts a Paper 1.21.1
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
click planning and ghost ray, and the material helper.

## Project layout

| Path | What it does |
|---|---|
| `core/` | Plain Java 21, no Minecraft classes: formats (`formats`, `nbt`), BlockDesigner projects (`project`), the block model (`model`), transforms (`transform`), placement, layers, the save selection and ray picking (`placement`), comparing (`compare`), item counting (`items`), build progress and the progress file (`progress`), easy place planning (`easyplace`), the schematic folder (`library`) and the sync protocol (`sync`) |
| `mc-1.21.1/common` | Minecraft 1.21.1 client code shared by both loaders: rendering, input, screens, mixins |
| `mc-1.21.1/fabric`, `mc-1.21.1/neoforge` | 1.21.1 entry points and mod metadata |
| `mc-26.2/common`, `mc-26.2/fabric` | The same for Minecraft 26.2 (Fabric only: NeoForge made no 26.2 build) |
| `mc-26.3/common`, `mc-26.3/fabric`, `mc-26.3/neoforge` | The same for Minecraft 26.3 |
| `paper/` | The Bukkit-API server plugin |
| `docs/plan.md` | The milestones and their status |
| `docs/formats.md` | Which schematic formats are primary and why, and the `.bdproj` layout |
| `docs/sync-protocol.md` | The server sync protocol |
| `docs/progress-format.md` | The progress file Resource Tracker reads |

The `core` sources are compiled into each Minecraft version's `common` module, so every mod jar carries them; the
Minecraft modules are built with [Architectury Loom](https://github.com/architectury/architectury-loom).

## License

[MIT](LICENSE)
