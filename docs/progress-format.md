# Progress file format

BlockCompanion writes how far each loaded schematic is built to a small JSON file. BlockDesigner's Resource Tracker
plugin reads these files to show in-game progress next to its own counts. This is the file link of
[Milestone 4](plan.md#milestone-4-resource-tracker-link); a live localhost link is still planned.

## Where

- **Folder:** `<user home>/.blockcompanion/progress/` (for example `C:\Users\<name>\.blockcompanion\progress\`). The
  game creates it the first time it writes a file.
- **File name:** `<name>-<hash12>.json`, where:
  - `<name>` is the schematic's file name without its extension, with every character outside `[A-Za-z0-9._-]`
    replaced by `_` (`My Castle (v2).schem` gives `My_Castle__v2_`);
  - `<hash12>` is the first 12 hex characters (lowercase) of the SHA-256 of the schematic file's bytes.

  One file per schematic file and content: editing the schematic gives a new hash and so a new file.

## When

- At most every 2 seconds while the progress changes.
- About once a minute while the schematic stays loaded, even with no changes (a heartbeat: the app marks a build
  "not seen" after 5 minutes without an update).
- When the schematic is unloaded, when the player leaves the world, and when the game closes.

Every write goes to `<name>-<hash12>.json.tmp` first and is then moved over the real file in one step (an atomic move
where the file system supports it). The temporary name does not end in `.json`, so a reader that picks up `*.json`
never sees a half-written file.

## Content

```json
{
  "format": 1,
  "schematic": "Watchtower.bdproj",
  "sha256": "<full hex>",
  "projectName": "Watchtower",
  "world": "bctest",
  "updated": "2026-09-27T14:03:12Z",
  "total": 14445, "correct": 5120, "wrong": 12, "missing": 9313,
  "items": { "minecraft:oak_planks": { "needed": 640, "placed": 210 } }
}
```

| Field | Meaning |
|---|---|
| `format` | `1`. A reader should skip files with a format it doesn't know. |
| `schematic` | The schematic's file name only (no folder), as it is in the game's schematic library. |
| `sha256` | The full lowercase hex SHA-256 of the schematic file's bytes. |
| `projectName` | For a BlockDesigner project (`.bdproj`), the `name` in its `project.json`; for other formats, the file name without its extension. |
| `world` | The singleplayer world's folder name, or the server address (as typed in the server list) in multiplayer. |
| `updated` | When the file was written: UTC, ISO-8601 with whole seconds and a trailing `Z` (Java's `Instant.parse` reads it). |
| `total` | Blocks in the schematic (every non-air cell of the loaded schematic). |
| `correct` | Blocks placed correctly, with the same leniency as the in-game compare: the right block, facing and half; connections, waterlogging and the like don't matter, and grass, flowers, water and snow count as empty. |
| `wrong` | Cells holding a different block (or the right block turned the wrong way). |
| `missing` | `total - correct - wrong`: cells still empty, including cells in chunks the game has never loaded. |
| `items` | Per item id: `needed`, how many items the whole schematic takes, and `placed`, how many of those are represented by correctly placed blocks. Counted with the same rules as the in-game resource list and Resource Tracker: a double slab is two slabs, a door or bed one item for both halves, crops their seeds, wall torches torches, and so on. |

`items` covers blocks only. The in-game resource list also shows the entities a schematic needs (item frames,
armour stands, paintings); they are not in the file.

## Matching the app's counts

The game loads a project with its **visible layers merged** (hidden layers are left out), and counts that. The app's
`needed` numbers line up with the file's when Resource Tracker counts **"All visible layers"**.

## How the game keeps it up to date

- Each block change in the world (by any player) updates its cell at once; a chunk that loads is checked section by
  section. Nothing rescans the whole schematic each tick.
- Only loaded chunks can be checked. When a chunk unloads, its cells keep their last known state, so the counts don't
  drop when the player walks away. The game also keeps that state per world in
  `<game folder>/blockcompanion/placements/<world>.progress`, so progress survives leaving and rejoining (as long as
  the schematic stays in the same place).
- Moving, turning or mirroring the placement starts the count again for the new position (a different spot in the
  world has different blocks).
- The file is written with `progress.file=true` in `config/blockcompanion.properties` (the default).
