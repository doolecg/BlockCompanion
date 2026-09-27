# Live link protocol

How BlockDesigner (its Resource Tracker plugin) talks to running games and servers on the same computer: finding them,
sending projects, following progress and linked chests, and installing the mod. The game side is
`io.blockcompanion.core.link` (`GameLink`, `LinkServer`, `InstanceInfo`).

## Finding games: instance files

Every running BlockCompanion writes `<user home>/.blockcompanion/instances/<id>.json`:

- a game client (Fabric or NeoForge) while its live link runs (see [In the game](#in-the-game-start-stop-and-status));
- a dedicated Fabric or NeoForge server, and a Paper server with the plugin.

```json
{
  "format": 1, "id": "5c0f…", "kind": "client", "name": "Steve", "world": "My World",
  "minecraft": "1.21.1", "loader": "fabric", "modVersion": "0.2.0", "pid": 12345,
  "port": 53491, "token": "…48 hex…",
  "clientPacks": ["C:\\…\\resourcepacks\\Faithful.zip"], "serverPacks": [],
  "started": "2026-09-27T10:00:00Z", "updated": "2026-09-27T10:05:10Z", "closed": false,
  "gameDir": "C:\\…\\.minecraft"
}
```

| Field | Meaning |
|---|---|
| `kind` | `client` or `server` |
| `name` | the player's name (client) or the server's MOTD (server) |
| `world` | client: the singleplayer world folder or the server address; server: its world folder |
| `loader` | `fabric`, `neoforge` or `paper` |
| `port`, `token` | where the link listens (127.0.0.1 only) and the secret to send in the hello |
| `clientPacks` | a client's enabled resource packs that are files or folders, lowest priority first |
| `serverPacks` | packs a server pushes: on a client, the files downloaded for the current server; on a server, its pack URL |
| `gameDir` | the game or server folder (`mods` or `plugins` is inside) |

The file is rewritten about every 5 seconds (`updated`). An instance is **active** when `closed` is false and
`updated` is less than 20 seconds old; otherwise it is **disconnected** (closed cleanly, or crashed). Files are
removed a week after their last update. Only the user can read their home folder, so reading the token is the pairing.

## Connection

TCP to `127.0.0.1:<port>`, one JSON object per line (UTF-8, `\n`). The app speaks first:

```json
{"type": "hello", "token": "…", "app": "Resource Tracker", "version": "1.2.0"}
```

A wrong or missing token gets `{"type": "error", …}` and the connection closes. Otherwise the game answers
`{"type": "welcome", "protocol": 1, "instance": {…the instance fields without the token…}}`.

`{"type": "ping"}` is answered with `{"type": "pong"}` at any time.

## App to game

| Message | What the game does |
|---|---|
| `project` | `{"file": "Castle.bdproj", "name": "Castle", "open": true, "sha256": "…", "data": "<base64>"}`. The game checks the hash, writes the file to `schematics/BlockDesigner/<file>` (a server: into its shared space) and answers `{"type": "received", "file": "BlockDesigner/Castle.bdproj"}`. `open: true` (the user pressed Send): placements of that file reload, and if none is loaded it is loaded in front of the player. `open: false` (a live update): only placements of that file set to follow BlockDesigner reload. A server switches shared placements of an earlier file of that name to the new one, so every player following them gets it. |
| `project` with `link` | The answer to an `edit`: the same fields as `project`, plus `"link": <slot>`. The game writes the file as usual, then points the placement in that slot at it (keeping its position, rotation, mirroring, locks and progress where the blocks still match) and sets it to follow BlockDesigner, so later live updates of that project reload it. If the slot is gone it is handled as a plain `project` with `open: true`. Games that don't know `link` treat it as a plain `project`. |
| `error` | `{"message": …, "slot": <slot>}`: something went wrong on the app's side, `slot` when it is about an `edit`. The game shows it to the player. |
| `refresh` | Sends the status now. |
| `app-status` | Optional: `{"project": "Castle", "live": true}`, the project open in the app and whether its Live button is on. The game shows it on its BlockDesigner step; send it after the welcome and whenever either changes. Games that don't know it ignore it. |

## Game to app

| Message | Meaning |
|---|---|
| `status` | Sent after the welcome and then at most once a second while something changes. `instance` (as in the welcome) plus, from a client: `world`, `server`, `dimension`, `placements` (each: `slot`, `file`, `name`, `sha256`, `dimension`, `x`, `y`, `z`, `rotation`, `mirrored`, `visible`, `live`, `selected`, `locks`, `lockedInPlace`, `total`, `correct`, `wrong`, `missing`) and `chests` (`count`, `unknown`, `items`: item id to count in the linked chests); from a server: `players`, `schematics` (`name`, `sha256`, `size`, `by`) and `placements` (a count). |
| `grab` | The player pressed Grab from BlockDesigner: send the open project (`project` with `open: true`). |
| `edit` | The player pressed **Edit in BlockDesigner** on a loaded placement: `{"slot": 2, "file": "Castle.litematic", "name": "Castle", "sha256": "…", "data": "<base64 of the schematic file>"}`. The app checks the hash, opens the file in BlockDesigner as the current project (importing `.schem`/`.litematic`/`.nbt`), and answers with a `project` carrying `"link": <slot>` (see below). On failure it answers `{"type": "error", "message": …, "slot": <slot>}`. |
| `error` | `{"message": …}` about the last message. |

Unknown message types are ignored both ways, so either side can add types.

## In the game: start, stop and status

The game is the listening side, so "connecting" from the game means starting the link: the game opens its port and
writes (or revives) its instance file, and the app, which looks at the instance folder every two seconds, connects on
its own. Nothing in the protocol needs to change for that.

- **Start link** / **Stop link** (the schematic screen's BlockDesigner step, and the settings' BlockDesigner section):
  stopping closes the port, drops connected apps and marks the instance file `closed`; starting again keeps the same
  `id` and `token` for the rest of the session (one instance file, a new `port`), so the app shows the same game coming
  back rather than a new one.
- **Start with the game** (`link.enabled` in the config, on by default): whether the link starts when the game starts.
  Switching it doesn't start or stop the running link.
- **Status**: off, waiting for BlockDesigner (with the port), or connected to `<app> <version>` since when; the last
  project the app sent (sent or updated, by which app, how long ago) and how many placements follow it, or the project
  the app reports with `app-status`.
- **Get project** sends `grab`; **Send now** sends the `status` straight away instead of waiting for the next change.

What the app side (Resource Tracker) would need for the rest: it reconnects about 10 seconds after a connection closes
and every 2 seconds finds new games, so a game that is stopped and started again comes back by itself. To show the
project open in BlockDesigner in the game before anything is sent, the app has to send `app-status` (above); until it
does, the game shows the last project it received instead.

## Installing the mod

The app can put the mod into a game: `gameDir` of a running (or recently seen) instance, or a launcher instance it
finds on disk. It downloads the jar for the instance's loader and Minecraft version from the latest release of
`doolecg/BlockCompanion` (`blockcompanion-<loader>-<minecraft>-<version>.jar`, or `blockcompanion-paper-<version>.jar`
into a server's `plugins`), removes older `blockcompanion-*.jar` files there, and says to restart the game.
