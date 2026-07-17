# googlehome-mcp

An MCP server for controlling Google Home devices: lights, plugs, switches, locks, thermostats,
speakers, and routines. It talks to Google's undocumented Home "foyer" API (the one the
[home.google.com](https://home.google.com) web app uses), authenticating with a gpsoauth master
token, so it needs no Google Cloud project, Device Access, or partner API.

It runs as a JVM server (MCP over HTTP+SSE) and also compiles to a wasmJs module for embedding.

## What it can do

Every tool below is verified live against real devices.

Read (all homes/devices, unrestricted):
- `list_homes`, `list_devices` (name, room, type, capabilities, online/on state), `get_device_state`

Control (any device; selectors are `name` and/or `room` and/or `type`):
- `turn_on` / `turn_off` : single device, a room, or a group ("all lights" = `type=light`)
- `set_brightness`, `set_color_temperature`
- `set_volume`, `set_muted`
- `lock`, `unlock` (PIN-gated: `unlock` takes a `pin`)
- `set_thermostat` (temperature in C or F, and/or mode)
- `media_control` (play / pause / stop)
- `list_automations`, `run_automation` (start a household routine by name)

"Turn off all the lights" matches Google's own behavior: a smart switch or plug you marked as a
"Light" in the Home app counts as a light, an unrelated plug does not.

There is no add, delete, or device-management surface. The server only reads and controls existing
devices, and only ever calls read/control RPCs.

## Install and use

Prereqs: JDK 17+, and Google Chrome (for the one-time token step).

### 1. Get a master token

The server needs a long-lived Google `master token` (`aas_et/...`). A bundled tool obtains one: it
opens Chrome to Google's sign-in, you log in, and it captures + exchanges the token.

```bash
./gradlew runJvmBootstrap > token.env      # sign in when Chrome opens
source token.env                           # sets GOOGLE_MASTER_TOKEN + GOOGLE_ANDROID_ID
```

The master token grants full account access. Keep it secret. `token.env` is gitignored; store it
somewhere private.

### 2. Run the server

```bash
source token.env
./gradlew runJvm                           # serves MCP on http://127.0.0.1:8765/sse
curl localhost:8765/health                 # -> ok
```

### 3. Add it to your MCP client

Claude Code:

```bash
claude mcp add --transport sse googlehome http://127.0.0.1:8765/sse
```

Any MCP client that speaks the SSE transport can connect to `http://127.0.0.1:8765/sse` (Codex,
Cursor, custom clients, etc.). Restart the client so it loads the server, then ask things like
"turn off all the lights" or "set the thermostat to 68".

## Development

```bash
./gradlew jvmTest                          # unit tests (payload codecs, selectors, auth)
./gradlew compileKotlinWasmJs              # wasm target
./gradlew installJvmDist                   # build a runnable distribution under build/install/
./gradlew runJvmVerify --args="--device \"Corner Lamp\" --on"   # live smoke test (needs token.env)
```

## Architecture

Kotlin Multiplatform. All protocol logic is in `commonMain`; each target injects its own Ktor engine.

- `auth/` : the gpsoauth bridge. Exchanges the master token for a short-lived Bearer scoped to
  `homegraph`, cached with expiry. On a 401/403 it invalidates and re-mints.
- `foyer/` : the `foyer-pa` client and codec. Requests/responses are positional
  `application/json+protobuf` arrays; the codec builds/parses them into clean models.
- `server/` : the MCP tool definitions and a `DeviceResolver` that maps `name`/`room`/`type`
  selectors to devices (room matches exactly, name prefers exact then substring).
- `jvmMain/Main.kt` : hosts the MCP protocol over HTTP+SSE using the `io.modelcontextprotocol`
  Kotlin SDK. `wasmJsMain/` exports the same operations as `@JsExport` functions.

See `docs/PROTOCOL.md` (auth + wire format) and `docs/TRAITS.md` (per-trait payloads) for the
reverse-engineered details.

## How auth works, briefly

The web app authenticates with cookie-derived `SAPISIDHASH`. This server has no cookies, so it mints
a Bearer from the master token via gpsoauth and sends `Authorization: Bearer <token>` with no
`X-Goog-Api-Key` (the Bearer alone identifies the Android Home app's project; adding the web api key
makes foyer reject the call). Read `docs/PROTOCOL.md` for the full story.

## Status and limits

Verified: on/off, brightness, color temperature, volume/mute, lock, unlock (PIN), thermostat, media
play/pause/stop, group ops, list/run automations.

Not supported by this API path: full RGB color on tunable-white bulbs, and media next/previous (the
web app exposes neither; skipping tracks lives in the Cast/Assistant protocol, not foyer).

## Disclaimer

This uses an undocumented, unofficial Google API and may break if Google changes it. Not affiliated
with or endorsed by Google. Use with an account you control, at your own risk.
