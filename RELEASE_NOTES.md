# BlockCompanion 0.4.4

Keys for switching the HUD and ghost effects on and off without opening the settings. For Minecraft 1.21.1, 26.2 and
26.3 on Fabric and NeoForge, and the Paper plugin.

## New
- **Toggle keys:** show or hide the HUD (progress panel, crosshair hint and tool panel together), ghost shimmer on or
  off, block entity ghosts on or off, boxes always shown or only with the tool, and the material helper on or off. None
  has a key until you set one in **Options › Controls › BlockCompanion** or on the settings screen's Keys tab. Each
  one says what it did above the hotbar and is remembered like the setting it flips.

## Changed
- **Server plugin:** listed for Paper 1.21.1 only, the server it is tested on.

---

# BlockCompanion 0.4.3

A hands-on tutorial: its own world, opened from the title screen, where you learn BlockCompanion by doing each thing
once. Building is quieter too. For Minecraft 1.21.1, 26.2 and 26.3 on Fabric and NeoForge, and the Paper plugin.

## New
- **Tutorial world:** the title screen's **BlockCompanion tutorial** button (under Minecraft Realms) opens a world made
  for it. Each step asks you to do one thing, says "Good! You did it" when you have, and takes you on to the next area.
  - **Chapter 1, the basics:** open the menu, hold the stick, move a floating hut onto its floor (from outside, or from
    inside, where it moves the way you look), lock it, fix a red (wrong) block, break an orange (in the way) block,
    build a porch with easy place, step through layers and open the resource list.
  - **Chapter 2, your own schematics:** load one from the menu, turn it, mirror it, lock it in place, pick a ghost's
    block with the middle mouse button, link a chest, AutoBuild from it, share it on the server, and the live link to
    BlockDesigner: one friend designs in the app while another builds in the game.
  - Every step stays up at least 20 seconds, with a bar counting down to the next one. **Enter** goes on, **Backspace**
    goes back. The game mode follows the lesson (adventure, survival, creative), and chat and other panels stay out of
    the way. Key names are the ones you have bound.
- **Show me in the world:** the guide's first page has a short camera tour with a demo schematic in front of you, in any
  world. Nothing in the world changes.
- **Guide in the menu:** a **Guide** button next to **Settings** in the BlockCompanion menu.

## Changed
- **Quieter building:** only a wrong block makes a sound now (a low note). Finishing a layer or a whole schematic still
  shows its toast, sparkles and fireworks, without the chimes. The tutorial keeps its chimes.

---

# BlockCompanion 0.4.2

A short guide walks new players through the mod, and many wrong or in-the-way blocks no longer drag the frame rate
down. For Minecraft 1.21.1, 26.2 and 26.3 on Fabric and NeoForge, and the Paper plugin.

## New
- **Guide:** a few pages on loading a schematic, moving it into place, reading the ghosts, building, materials and
  AutoBuild, and saving. It opens by itself the first time you are in a world, and the settings screen's **Guide**
  button opens it again. Key names in it are the ones you have bound.
- **Welcome line:** joining a world says in chat which key opens the BlockCompanion menu.

## Fixed
- **Warning outlines:** red (wrong) and orange (in the way) blocks that touch now share one outline and one tint around
  the whole group, instead of a box around every block. A schematic loaded into a hillside or over an old build stays
  smooth, and the tint no longer shows darker seams between blocks.

---

# BlockCompanion 0.4.1

BlockDesigner's Resource Tracker plugin is now called BlockCompanion Plugin, and the game says so. For Minecraft
1.21.1, 26.2 and 26.3 on Fabric and NeoForge, and the Paper plugin.

## Changed
- **BlockCompanion Plugin:** the link panel, the settings, tooltips and the messages on the action bar name
  BlockDesigner's BlockCompanion Plugin (formerly Resource Tracker). Linking works as before.

---

# BlockCompanion 0.4.0

AutoBuild gets options like Create's Schematicannon: speed, order, what it may replace, a radius around you and one
block type at a time. Building with it is also much lighter on your frame rate. For Minecraft 1.21.1, 26.2 and 26.3 on
Fabric and NeoForge, and the Paper plugin.

## New
- **AutoBuild options:** an **Options** button next to **Start AutoBuild** (also while a build runs) and an
  **AutoBuild** section in the settings for the defaults:
  - **Speed:** 1 to 200 blocks a second (5 by default).
  - **Order:** bottom up (as before), top down (sand, torches and anything else that needs support go last), nearest to
    you first, or one block type at a time.
  - **Replace:** keep what is there (as before), replace solid blocks, replace everything, or also clear blocks where
    the schematic has air. Bedrock, barriers and portals are never touched. In survival, what comes out goes into your
    linked chests, and onto the ground when they are full.
  - **Ignore air** (on by default) and **Skip missing**: go past blocks you have no items for instead of pausing.
  - **Radius:** build only what is within 8 to 128 blocks of you, following you as you move.
  - **Only build:** everything, or only the blocks that match the item in your hand.
  - Changes reach a build that is already running: speed at once, the rest re-plans what is left.
- **Server limits:** server owners can cap the speed, the radius and what AutoBuild may break (`autoBuildReplace`,
  `autoBuildMaxRadius`). Breaking blocks is off on servers until the owner allows it; singleplayer allows everything.
- **Ghost distance** (Settings, Ghosts): ghosts are drawn up to 64 blocks away by default, up to 256 or unlimited. Big
  schematics stay smooth; the build is still counted everywhere.
- **Overlap warning:** two schematics on top of each other (the same one loaded twice, say) are drawn and built twice.
  A line in chat now says so once, so you can move or unload one.

## Changed
- **Smoother AutoBuild:** its blocks go down without sparkles, and the ghosts and the build count around them catch up
  a few times a second instead of on every block, which kept the frame rate low while it ran.
- **AutoBuild places like the Schematicannon:** without updating the blocks next to it, so water doesn't flow into a
  gap and redstone beside the build stays quiet while it works. It is lighter on the server too.

---

# BlockCompanion 0.3.2

BlockCompanion now runs on NeoForge for Minecraft 26.2 too. For Minecraft 1.21.1, 26.2 and 26.3 on Fabric and NeoForge,
and the Paper plugin.

## New
- **NeoForge on Minecraft 26.2:** a new `blockcompanion-neoforge-26.2` jar. It has everything the other versions have,
  and the in-game updater keeps it up to date like the rest.

---

# BlockCompanion 0.3.1

For Minecraft 1.21.1 and 26.3 on Fabric and NeoForge, 26.2 on Fabric, and the Paper plugin.

## Changed
- **Update check:** BlockCompanion looks for a new release only when the game starts, no longer every few hours
  while you play. **Check now** on the settings screen's Updates tab still checks straight away.

---

# BlockCompanion 0.3.0

The server can now build a schematic for you from your linked chests, easy place can place the blocks around you by
itself, and the stick's controls are simpler, with a panel that shows them. For Minecraft 1.21.1 and 26.3 on Fabric and
NeoForge, 26.2 on Fabric, and the Paper plugin.

## New
- **AutoBuild:** when your linked chests hold everything a placement still needs, **Start AutoBuild** in the schematic
  screen's Resources step has the server build it, block by block and layer by layer from the bottom, taking each
  block's item out of your chests as it goes. Hover the button to see what is short. It never breaks a block (a wrong
  one is skipped and counted), pauses when an item runs out (refill and press **Resume**), and shows its progress on
  the action bar. In creative it needs no chests. It runs on the server: always in singleplayer, and with the mod or
  the Paper plugin on a server, where the owner can switch it off (`allowAutoBuild`) and decide who may use it.
- **Auto place** (off by default, in the settings or on a key you choose): the missing blocks within reach place
  themselves from what you carry, bottom layer first and nearest first, turned the right way. 4 blocks a second by
  default, up to 20; a BlockCompanion server can cap or switch it off.
- **Tool panel:** while the stick is in hand, a panel like the info panel (bottom right) shows the tool's mode and its
  controls. Move or resize it in the HUD editor, or switch it off in the HUD settings.
- **View key (V):** cycles the schematic you look at through everything, layers up to here, this layer only, only this
  schematic and hidden. Undo steps back through it.
- **Move the selection:** the cyan save box moves with the same scroll as a schematic's box.
- **Edit in BlockDesigner** on a placement opens it in BlockDesigner as its project; the placement then follows your
  changes live where it stands.

## Changed
- **New stick controls**, with the stick in hand while you look at a box:
  - **Shift+scroll** does the mode: **Move** or **Mirror** (the selection only moves).
  - **Ctrl+scroll** turns it 90°.
  - **Ctrl+Shift+scroll** switches between Move and Mirror.
  - **Alt+left-click** and **Alt+right-click** set the selection's corners.
  - **Shift+right-click** clears the selection.
  - **Ctrl+right-click** a chest links it; again unlinks it.
  - Plain scrolling changes the hotbar as usual. All of these can be changed in the settings.
- **Fewer modes:** only Move and Mirror are left; layers and show / hide moved to the view key.
- **Quieter building:** no more chime for correct blocks, only a few sparkles. The low note for a wrong block plays at
  most every few seconds.
- **Linked chests** are read when you close them and kept until you open them again; what AutoBuild and easy place
  take out comes off by itself. On a BlockCompanion server the server reads them when you close them.
- **Easy place** never clicks a block that is already there or the same spot twice in a row, places doors and beds
  from their lower half, and no longer keeps flapping doors or opening chests inside a schematic while you hold
  right-click.
- The J key is gone: the server's shared schematics are on the schematic screen's Source step.

## Fixed
- **The Resources page no longer drops the frame rate** with linked chests: the list, the chest totals and the
  AutoBuild check are worked out only when something changes, not on every frame.
- Linked chests on a server now update after you close them (before, only linking, restocking and AutoBuild did).

---

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
