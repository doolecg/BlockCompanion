# BlockCompanion 0.2.0

Moving a build now needs the stick in your hand, Ctrl+Z undoes like any program, and the schematic and settings
screens were rebuilt to be easier to follow. For Minecraft 1.21.1 and 26.3 on Fabric and NeoForge, 26.2 on Fabric, and
the Paper plugin.

## New
- **Stick-in-hand controls:** moving, turning and mirroring a schematic in the world now need the selection tool (a
  stick by default) in your hand, so scrolling through the hotbar never knocks a build out of place. Without it the
  scroll wheel works as usual. The Building settings can switch this off.
- **Tool modes:** with the stick, plain scrolling does what its mode says and **Shift+scroll** switches the mode, shown
  above the hotbar: **Move**, **Turn**, **Mirror**, **Layers** or **Show / hide** (everything, layers up to here, this
  layer only, only this schematic, hidden). The mode is remembered.
- **Undo and redo:** **Ctrl+Z** undoes and **Ctrl+Y** or **Ctrl+Shift+Z** redoes, with one history across every loaded
  schematic: moves, turns, mirroring, locks, layers and hiding, in the order you made them. No stick needed; a quick
  scroll counts as one step, a new change clears what could be redone, and a locked schematic is never moved by undo.
- **Boxes only with the tool:** the schematic boxes and the save selection show while the stick is in either hand and
  fade out when you put it away, with softly pulsing tinted faces, a brighter face where you look and corner brackets.
  A locked box stays blue. **Show boxes** in the settings keeps them on always; ghosts show either way.
- **Live link from the game:** start and stop the link to BlockDesigner in the game, see whether it is off, waiting or
  connected (and to which app), **Get project** from BlockDesigner and **Send now**. **Start with the game** decides
  whether it starts by itself.
- **Delete schematics** from the schematic screen's Source step; it asks first.

## Changed
- **The schematic screen (B)** is now four steps: **Source** (your schematic folder, or what a BlockCompanion server
  shares), **Placement** (what's loaded, where, how far built, locks, show / hide, bring here, turn, mirror, Live),
  **Resources** (the same list as **N**) and **BlockDesigner** (the live link). The server's shared schematics (**J**)
  open in the same screen.
- **New settings screen:** sections down the left (Ghosts, Building, HUD, Effects, Colours, Keys, BlockDesigner,
  Updates), one option per row with a short description, and **Reset** per section.
- **Keys can be rebound right in the settings:** click a key, press the new one (Esc leaves it unbound); a key another
  action also uses shows red, with which one.
- **Undo** and **Redo** are new keys in Controls (used with Ctrl). Redo's default Y is also Lock's key; both still work.

## Fixed
- **Dragging in the HUD editor** works again on Minecraft 26.3, which numbers mouse buttons differently.

---

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
