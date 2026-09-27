# Live link protocol

How BlockDesigner (its Resource Tracker plugin) talks to running games and servers on the same computer: finding them,
sending projects, following progress and linked chests, and installing the mod. The game side is
`io.blockcompanion.core.link` (`GameLink`, `LinkServer`, `InstanceInfo`).

## Finding games: instance files

Every running BlockCompanion writes `<user home>/.blockcompanion/instances/<id>.json`:

- a game client (Fabric or NeoForge) always, while the live link is on in its settings;
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
| `refresh` | Sends the status now. |

## Game to app

| Message | Meaning |
|---|---|
| `status` | Sent after the welcome and then at most once a second while something changes. `instance` (as in the welcome) plus, from a client: `world`, `server`, `dimension`, `placements` (each: `slot`, `file`, `name`, `sha256`, `dimension`, `x`, `y`, `z`, `rotation`, `mirrored`, `visible`, `live`, `selected`, `locks`, `lockedInPlace`, `total`, `correct`, `wrong`, `missing`) and `chests` (`count`, `unknown`, `items`: item id to count in the linked chests); from a server: `players`, `schematics` (`name`, `sha256`, `size`, `by`) and `placements` (a count). |
| `grab` | The player pressed Grab from BlockDesigner: send the open project (`project` with `open: true`). |
| `error` | `{"message": …}` about the last message. |

Unknown message types are ignored both ways, so either side can add types.

## Installing the mod

The app can put the mod into a game: `gameDir` of a running (or recently seen) instance, or a launcher instance it
finds on disk. It downloads the jar for the instance's loader and Minecraft version from the latest release of
`doolecg/BlockCompanion` (`blockcompanion-<loader>-<minecraft>-<version>.jar`, or `blockcompanion-paper-<version>.jar`
into a server's `plugins`), removes older `blockcompanion-*.jar` files there, and says to restart the game.
