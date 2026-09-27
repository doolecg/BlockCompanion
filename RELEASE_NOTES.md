# BlockCompanion 0.1.0

The first release: a light schematic mod for building in Minecraft, and the in-game companion to BlockDesigner. For
Minecraft 1.21.1 and 26.3 on Fabric and NeoForge, 26.2 on Fabric (NeoForge has no 26.2 version), and a Paper plugin
for servers.

## New
- **Schematic library:** BlockDesigner projects (`.bdproj`) and `.schem` files first, `.litematic` and `.nbt` too, in
  `<game folder>/blockcompanion/schematics`. **B** opens the list: **Load** adds a schematic in front of you, and as
  many as you like can be loaded at once. The list next to it shows what's loaded, and each one can be shown or hidden,
  locked, moved to where you stand or unloaded.
- **Locking:** Unlocked, Position, In place (position, turning and mirroring) or Everything (the layer view too), from
  the list or with **Y** on the schematic you look at. A locked box has a blue outline.
- **Selection tool:** hold a stick, left-click a block for corner 1 and right-click for corner 2; **O** saves the box
  as a `.schem`. The stick never breaks blocks while you select. Another item can be the tool in the settings.
- **Linked chests:** sneak and right-click a chest with the stick to link it. What's inside counts in the resource
  list, the info panel and Resource Tracker. On a BlockCompanion server (and in singleplayer), easy place fetches the
  block from your linked chests when you don't carry it.
- **Ghost blocks** that look almost real: the game's own models (so your resource packs, and a server's, apply),
  lit by the world, with a slight tint and pulse; chests, signs, beds, banners and heads in their real shapes; water
  and lava. The real block replaces its ghost the moment it is placed; wrong blocks are red, extra ones orange.
- **Easy place** (**H**): right-click a ghost to place exactly that block there, turned the right way, even in mid-air.
  It takes the block from your inventory when you don't hold it. Works in survival on vanilla and Paper servers.
- **Info panel** in the bottom left, in the game's tooltip style: the schematic, a progress bar that fills red to
  green, the current layer, the block in your hand with how many are left and how many your chests hold, and what's
  locked. A small hint left of the crosshair says what a wrong block should be.
- **HUD editor:** drag the info panel and the hint wherever you want and set their size, like Xaero's minimap.
- **Settings screen** with every option on tabs (Ghosts, Building, HUD, Effects, Colours, Link, Updates), also from Mod
  Menu and NeoForge's mod list. Changes apply at once, each tab can be reset, and it reopens on your last tab. Keys can
  be rebound in Controls.
- **Custom colours:** change the ghost tint, wrong and in-the-way blocks, the material helper, outlines and boxes in a
  colour editor with sliders, a hex field and swatches, or pick a preset (Default, Colour-blind safe, Vivid, Soft).
- **Updates:** checks GitHub for a new release at start and every few hours, tells you in chat, and downloads it from
  the Updates tab (or by itself if you like). It installs when you quit the game.
- **Resource list** (**N**): needed, placed, in your inventory, in your chests and still to get, each with a bar that
  fills red to green; **Refresh** counts your inventory and chests again.
- **Live link to BlockDesigner:** Resource Tracker in BlockDesigner finds the game (and servers on the same computer),
  sends the open project into it, and with **Live** on sends every change, so the ghosts follow what you build in the
  app. **Grab from BD** in the schematic list asks BlockDesigner for the project it has open. Progress and chest
  contents go back to the app.
- **Placement:** look at a box (it turns yellow), **Alt+scroll** moves it, **Ctrl+scroll** turns it, **M** mirrors it.
- **Layers:** **PgUp / PgDn** step through the levels, **Insert** switches between one level and building up.
- **Shared schematics on servers** (**J**): share, load, lock and follow placements on Fabric, NeoForge and Paper
  servers. A server also accepts projects sent straight from BlockDesigner on the same computer.
- **Small celebrations:** a chime and sparkles for correct blocks, a toast when a layer is done, fireworks and a
  summary when the build is finished. Each can be switched off.

## Fixed
- The low "wrong block" note only plays for blocks you placed yourself. Grass spreading, crops growing, a furnace
  lighting up or someone else building nearby no longer set it off.
