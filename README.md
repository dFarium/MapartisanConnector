# MapArtisan Connector

A client-side Fabric mod that turns [MapArtisan](https://mapartisan.dfarium.com) into a live preview: while you edit a map art in the browser, the mod pastes the construction into your Minecraft world with real blocks, through Litematica.

Every revision is pasted with air where earlier revisions left blocks, then checked block by block against the world before MapArtisan reports it as applied. The structure is the same one MapArtisan exports, including heights and  supports.

> The connector is meant for a disposable creative test world. It only writes inside an area reserved for the session, but it does replace everything in that area.

## Requirements

The mod depends on internal Litematica classes, so the Litematica and MaLiLib versions are exact: the game refuses to load the mod with any other.

| Dependency | Version |
|---|---|
| Minecraft Java | 26.3 |
| Fabric Loader | 0.20.0 or newer |
| Fabric API | Any for 26.3 (tested with 0.163.0+26.3) |
| Litematica | 0.29.1 |
| MaLiLib | 0.30.2 |

The world must be singleplayer and you must be in creative mode.

## Installation

1. Install Fabric Loader for Minecraft 26.3.
2. Put Fabric API, Litematica 0.29.1, MaLiLib 0.30.2 and `MapartisanConnector-<version>.jar` in your `mods` folder.
3. Start the game and open a singleplayer creative world. A superflat world works well.

## Usage

### 1. Enable the connector in MapArtisan

The connector is hidden by default. Open the browser's DevTools console on MapArtisan and run:

```js
await mapartisan.connector.enable()
```

This is remembered for that site, so you only do it once. `mapartisan.connector.disable()` hides it again.

### 2. Start a session in the game

Stand where the map art should go and run:

```
/mapartisan start
```

The session reserves one map section from your position: 128 blocks along X, 129 along Z, from the origin's Y up to the build limit. The area must be empty; otherwise the command reports how many blocks it found and where the first one is. Chunks in the area must be loaded, so stay close.

To place the art on a Minecraft map, pick an origin aligned with the map grid. Scale 0 maps cover 128 × 128 blocks starting at X and Z values of the form `128·k − 64`, so the origin's X should be one of those values and the first pixel row (origin Z + 1) too.

The chat shows a **[copy command]** link. Click it and paste the command into the DevTools console:

```js
await mapartisan.connector.connect("<token>")
```

### 3. Edit

Once connected, a **Live Preview** panel appears at the end of *Construction Model* in MapArtisan. Every construction change (painting, undo and redo, palette, processing, heights, supports, *File Layout* and the selected section) is sent automatically after a short pause, and only the latest state is pasted.

The panel shows the sync state, the world and origin, the revision in the world and the last paste time. Its controls:

- **Automatic sync**: turn it off to keep changes in the editor.
- **Pause sending**: hold changes for a while without turning automatic sync off.
- **Resync**: send the current construction now, rewriting the whole area.
- **Disconnect**: close the session. Blocks already placed stay in the world.

### File Layout

| *File Layout* | Maps | What is pasted |
|---|---|---|
| *Single File* | 1 × 1 only | The whole map. |
| *Multiple Files* | Any size | The section chosen in *3D Preview Area*, one at a time, always at the same origin. |

A map larger than 1 × 1 with *Single File* cannot sync; the panel says so.

## Commands

| Command | What it does |
|---|---|
| `/mapartisan start [x y z]` | Reserves the area at your position, or at the given coordinates, and starts listening with a new token. |
| `/mapartisan start [x y z] force` | Same, but accepts an area that already has blocks. The first revision replaces them. |
| `/mapartisan stop` | Closes the connection and the session. Leaving the world or changing dimension does the same. |
| `/mapartisan status` | Shows the session, revisions and paste state. |
| `/mapartisan test …` | Diagnostic commands for the paste engine: `origin`, `paste <a\|b\|flat\|max>`, `verify`, `abort`, `status` and `suite`. Use them in an empty test area. |

## Browser console

| Function | What it does |
|---|---|
| `mapartisan.connector.enable()` / `disable()` | Shows or hides the connector. Disabled, nothing of it is loaded. |
| `mapartisan.connector.status()` | Connection, applied revision and sync state, asked to the game. |
| `mapartisan.connector.connect("<token>", { port })` | Connects to the game session. |
| `mapartisan.connector.send()` | Sends the current construction now. |
| `mapartisan.connector.disconnect()` | Closes the session. |

The token is kept for the browser tab, so reloading the page reconnects to the same game session.

## Configuration

`config/mapartisan_connector.json` is created on the first `/mapartisan start` and read on every start, so edits apply without restarting the game.

```json
{
  "port": 25590,
  "allowedOrigins": [
    "https://mapartisan.dfarium.com",
    "http://localhost:5173",
    "http://127.0.0.1:5173",
    "http://localhost:4173",
    "http://127.0.0.1:4173"
  ]
}
```

- `port`: the local port, between 1024 and 65535. If you change it, connect with `connect("<token>", { port: <port> })`.
- `allowedOrigins`: the pages allowed to connect, matched exactly. Add your own if you serve MapArtisan elsewhere. A file written by an older version keeps its list; edit it or delete it to get the new defaults.

## Security

- The mod listens only on `127.0.0.1`, and only while a session is running.
- A connection needs an allowed `Origin`, a loopback `Host` (which blocks DNS rebinding) and the token.
- The token is 128 random bits, new on every `/mapartisan start`. The chat and the game log only show its last 4 characters.
- The protocol accepts block structures only: no commands and no file paths. Block states are parsed without NBT.
- Nothing is written outside the reserved area. Structures larger than it are rejected before pasting.

Keep in mind:

- The **[copy command]** link puts the full token on the clipboard. Windows clipboard history (Win + V), and its cloud sync if enabled, keep it until the session ends. Use `/mapartisan stop` and `start` to get a new one.
- Anyone with the token can place any block in the reserved area. Fire or lava placed there can spread outside it with normal game ticks.
- Any page served on an allowed origin can try to connect; it still needs the token.

## Troubleshooting

| Symptom | Cause |
|---|---|
| The paste waits and the panel says *Game paused* | Minecraft pauses when it loses focus. Press F3 + P in the game to turn off pause on lost focus. |
| The paste waits for the area to load | You are too far from the reserved area. Go back to it. |
| `NOT_READY` | Not in creative mode, the structure does not fit in the world height, or the origin changed with `/mapartisan test origin`. |
| `OUT_OF_AREA` | The structure is larger than the reserved area. |
| `UNAUTHORIZED` | The token belongs to an earlier session. Run `/mapartisan start` again and paste the new command. |
| `BUSY` | Another tab holds the session. Close it, or disconnect it. |
| The connection fails right away | No session is running, the port is different, or the page's origin is not in `allowedOrigins`. |
| `start` says the area is not empty | Clear it, or use `/mapartisan start … force` to replace what is there. |

## Development

The project builds with Gradle 9.8 and Java 25:

```
./gradlew build       # compiles and runs the unit tests
./gradlew runClient   # starts a development client with the mod
```

- Unit tests (`src/test`) cover the content hash, transfer assembly and limits, the `Host` and `Origin` checks, and the reserved area.
- `/mapartisan test suite` runs the paste engine checks in a running game and verifies every paste against the world.
- MapArtisan has an end-to-end suite that drives the protocol against a running game. Start a session, then run in the MapArtisan repository:

  ```
  CONNECTOR_TOKEN=<token> pnpm connector:e2e                 # bash
  $env:CONNECTOR_TOKEN="<token>"; pnpm connector:e2e         # PowerShell
  ```

  It needs an empty area (or a `force` start) and the game must not pause on lost focus.

The design, protocol and test results are documented in MapArtisan's [`docs/litematica-live-preview-spec.md`](https://github.com/dFarium/MapArtisan/blob/main/docs/litematica-live-preview-spec.md).

Litematica updates can change the internal classes the mod uses. Before supporting a new version, update `litematica_version` and `malilib_version` in `gradle.properties` and run `/mapartisan test suite` again.

## License

[AGPL-3.0](LICENSE.txt).
