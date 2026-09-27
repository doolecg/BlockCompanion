# BlockCompanion: schematic formats

BlockCompanion moves builds between Minecraft and the [BlockDesigner](https://github.com/doolecg/BlockDesigner) desktop
app. It uses BlockDesigner's own project format and the open Sponge schematic format for that, so a player doesn't need
Litematica or Create files.

| Format | Extension | Load | Save | Role |
|---|---|---|---|---|
| BlockDesigner project | `.bdproj` | yes | no | **Primary**: a whole BlockDesigner project, layers and all |
| Sponge Schematic v3 | `.schem` | yes (v2 and v3) | yes (always v3) | **Primary**: the one save format, and what BlockDesigner stores each layer as |
| Litematica | `.litematic` | yes | no | Compatibility |
| Vanilla structure | `.nbt` | yes | no | Compatibility |

The load screen lists `.bdproj` files first, then `.schem`, both marked green, then the compatibility formats. The
shared space on a server accepts all of them; a file is shared by its hash, whatever its type.

## Why these two

- **Sponge Schematic v3** has an open specification and no single mod owns it. WorldEdit, FAWE and many other tools
  read and write it. It stores blocks, block entities (chest contents, sign text) and entities.
- **`.bdproj`** is what BlockDesigner saves. Loading it directly means no export step in the app, and the layer
  structure comes along. Since format 2 its layers are Sponge `.schem` files too.
- **Litematica** and **vanilla structure** files still load because people have plenty of them. BlockCompanion never
  writes them.

## `.bdproj`

A zip holding `project.json` and one block file per layer. BlockCompanion reads it; only BlockDesigner writes it. The
layout follows BlockDesigner's `ProjectFile`.

```json
{
  "format": 2,
  "name": "Watchtower",
  "targetVersion": "26.3",
  "dataVersion": 5023,
  "activeLayer": "<id>",
  "layers": [ {
    "id": "<id>", "name": "Walls",
    "offset": [1, 0, 0], "rotation": 0, "mirror": "NONE",
    "visible": true, "locked": false, "ghost": false, "color": "#7C9CFF",
    "source": "litematica",
    "file": "layers/<id>.schem",
    "origin": [24, 16, 7]
  } ]
}
```

- **Format 2** (current) stores each layer as `layers/<id>.schem` (Sponge v3). **Format 1** used
  `layers/<id>.litematic`. BlockCompanion reads both. A `format` above 2 is refused with a message that the project is
  newer than this BlockCompanion.
- A layer's blocks are stored from their own min corner; `origin` puts them back into the layer's local coordinates.
- Placing a layer: `world = transform(local) + offset`. The transform mirrors first (`X` flips x, east and west swap;
  `Z` flips z, north and south swap), then turns `rotation` times 90° clockwise seen from above, around the local
  origin. Stairs, doors, rails, signs and other directional blocks turn with the layer, and so do entities (position,
  yaw, a hanging entity's facing).
- Other zip entries belong to BlockDesigner plugins and are ignored.

**Loading in the game.** The visible layers are merged in project coordinates, later layers over earlier ones, and
placed as one schematic. Hidden layers are skipped; hovering a project in the load screen lists all its layers and marks
the hidden ones. The merged build then works like any other placement: moving, rotating, mirroring, layers, the
resource list, and comparing with the world.

## Saving from the game

1. Press **K** while looking at a block to mark the first corner, then **K** at the opposite corner. A cyan outline
   shows the box. Without a block in view, the corner is the block you stand in.
2. Press **O**, type a name and click **Save** (or Enter). Without marked corners, the loaded schematic's box is saved
   instead, which records what has been built so far.
3. The file is written as `<name>.schem` (Sponge v3) in the schematic folder, ready to open in BlockDesigner. An
   existing file is only replaced after a second click on Save.

What gets saved:
- blocks, trimmed to the blocks inside the box;
- block entities, like chest contents and sign text;
- entities, like armor stands, item frames, paintings and mobs (players are left out).

In singleplayer the blocks come from the integrated server, so chest contents are complete. On a multiplayer server
only what the client knows is saved: blocks and entities, but containers are usually empty. Chunks that aren't loaded
are left out (the action bar says how many). One save can hold at most 8,388,608 blocks (for example 256 × 128 × 256).

Both keys can be rebound in **Options › Controls › BlockCompanion**.
