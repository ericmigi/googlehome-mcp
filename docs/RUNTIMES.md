# Runtimes, the JVM/wasmJs split

`googlehome-mcp` ships one shared facade (`GoogleHomeMcp`, commonMain) and two per-target
entrypoints. Each entrypoint's only job is to **inject the platform Ktor HTTP engine** and expose
the facade to its host. All foyer/auth/resolution/control logic lives in commonMain and is identical
on both targets. Control is general (any device, via `name`/`room`/`type` selectors) but strictly
*control of existing devices*: there is no add/create or remove/delete surface anywhere.

## Why the split

The MCP Kotlin SDK (`io.modelcontextprotocol:kotlin-sdk` 0.8.3) publishes **jvm / js / iosArm64 /
iosSimulatorArm64** artifacts but **no wasmJs artifact**. So the full MCP protocol server can only
be hosted on the JVM. Consequences:

| | JVM (`jvmMain/Main.kt`) | wasmJs (`wasmJsMain/WasmEntry.kt`) |
|---|---|---|
| Ktor engine injected | `OkHttp.create()` | `Js.create()` |
| MCP protocol | **Full**: hosts an MCP SDK `Server` over HTTP + SSE | **None**: exports tool functions for an embedding host to wire to its own MCP integration |
| Master token source | `GOOGLE_MASTER_TOKEN` env var (fail-fast) | passed to `initGoogleHomeMcp(masterToken)` |
| Tool surface | 13 MCP tools (below) | 13 exported promise-returning functions |

The JVM hosts the wire protocol; wasmJs exports the same operations as plain async functions so an
embedder (e.g. a small JS shim) can bridge them to whatever MCP transport it already runs.

## Tool set (both targets)

Read-only (unrestricted, all homes/rooms/devices):

- `list_homes`
- `list_devices`, each device with room, type, traits, derived **capabilities**, and live online/onOff state
- `get_device_state(device_ids: string[])`

Control (general; every control tool takes a `name?`/`room?`/`type?` selector and applies to every
capable match, returning a per-device `{id,name,ok,state|error}` array):

- `turn_on` / `turn_off`
- `set_brightness(brightness_pct)`
- `set_color_temperature(kelvin)`
- `set_volume(volume_pct)` / `set_muted(muted)`
- `lock` / `unlock(pin)`, unlock is PIN-gated
- `set_thermostat(temperature_c? / temperature_f? / mode?)`, best-effort, needs live confirm
- `media_control(command)`, NEXT/PAUSE/PREVIOUS/RESUME/STOP, best-effort, needs live confirm

No tool creates or deletes a device, room, or structure.

## JVM entrypoint, `src/jvmMain/kotlin/googlehome/mcp/Main.kt`

- Reads `GOOGLE_MASTER_TOKEN` from the environment; exits with a fatal message if missing/blank.
- Reads `PORT` (default `8765`).
- Constructs `GoogleHomeMcp(masterToken, engine = OkHttp.create())`.
- Builds an MCP SDK `Server`, registers every tool by iterating the facade's own `tools()` registry
  (each delegates to `mcp.server().call(name, args)`), and serves it with a Ktor CIO server:
  - `GET  /health`  → `ok`
  - `GET  /sse`     → opens the MCP SSE stream (`SseServerTransport("/message", …)`)
  - `POST /message` → MCP client→server messages, keyed by `?sessionId=`
- Binds `0.0.0.0`.

Run:

```bash
export GOOGLE_MASTER_TOKEN='aas_et/...'
export PORT=8765            # optional
./gradlew jvmRun            # or run the googlehome.mcp.MainKt main class
curl localhost:8765/health  # -> ok
```

## wasmJs entrypoint, `src/wasmJsMain/kotlin/googlehome/mcp/WasmEntry.kt`

Exported (via `@JsExport`) surface, a module-state singleton (Kotlin/Wasm `@JsExport` is
function-only) plus top-level async functions. Selector fields are plain strings where `""` = unset:

```js
initGoogleHomeMcp(masterToken);                 // install once (or initGoogleHomeMcpWithDeviceId)
await listHomes();                              // Promise<string>, JSON array of homes
await listDevices();                            // Promise<string>, devices w/ capabilities + state
await getDeviceState("id1,id2");                // Promise<string>, comma-separated ids in, JSON out
await turnOff("", "kitchen", "light");          // all kitchen lights off
await setBrightness("Corner Lamp", "", "", 40); // one lamp to 40%
await unlock("Door lock", "", "", "1234");     // PIN-gated unlock
```

Full control surface (all return `Promise<string>` of the `{id,name,ok,state|error}` array):
`turnOn`, `turnOff`, `setBrightness`, `setColorTemperature`, `setVolume`, `setMuted`, `lock`,
`unlock`, `setThermostat`, `mediaControl`.

- `initGoogleHomeMcp` injects `Js.create()` as the engine; `initGoogleHomeMcpWithDeviceId` also pins
  the 16-hex `androidId` the master token was minted with.
- Each function returns a JS `Promise<string>` (a `kotlinx.coroutines.promise` over the facade's
  suspend calls) resolving to the same JSON text the JVM server returns as MCP `TextContent`.
- `getDeviceState` takes a comma-separated id string (kept simple to avoid JS-array interop).
- Control is **general** (any device via selectors) but strictly control of existing devices, there
  is no add/create or remove/delete export.

## Facade seam both entrypoints require (owned by the mcp/server agent)

Both `Main.kt` and `WasmEntry.kt` depend ONLY on this small, stable surface of `GoogleHomeMcp`
(package `googlehome.mcp`). Engine injection is mandatory because commonMain cannot create a Ktor
engine on its own:

```kotlin
class GoogleHomeMcp(
    masterToken: String,
    engine: io.ktor.client.engine.HttpClientEngine,   // OkHttp.create() on jvm, Js.create() on wasmJs
    androidId: String = MasterToken.DEFAULT_ANDROID_ID,
) {
    fun tools(): List<GhTool>                             // the tool registry (JVM adapts each to an SDK Tool)
    fun server(): GoogleHomeMcpServer                     // dispatch by tool name: server().call(name, args)

    suspend fun listHomes(): String                       // JSON array of homes
    suspend fun listDevices(): String                     // JSON array of devices (+ capabilities/state)
    suspend fun getDeviceState(deviceIds: List<String>): String   // JSON array of DeviceState
    // Selector-based control convenience methods (name/room/type nullable), each returns the
    // per-device {id,name,ok,state|error} JSON array:
    suspend fun turnOn(name, room, type): String
    suspend fun turnOff(name, room, type): String
    suspend fun setBrightness(brightnessPct, name, room, type): String
    suspend fun setColorTemperature(kelvin, name, room, type): String
    suspend fun setVolume(volumePct, name, room, type): String
    suspend fun setMuted(muted, name, room, type): String
    suspend fun lock(name, room, type): String
    suspend fun unlock(pin, name, room, type): String
    suspend fun setThermostat(temperatureC, temperatureF, mode, name, room, type): String
    suspend fun mediaControl(command, name, room, type): String
}
```

Notes:
- Every method returns a **JSON string** (a text payload), so they map 1:1 to MCP `TextContent` on
  JVM and to JS strings on wasmJs. `Main.kt` prefers the transport-agnostic `tools()` registry +
  `server().call(name, args)` so wire schemas stay in lock-step with handlers (single source of
  truth in `GoogleHomeMcpServer`); `WasmEntry.kt` uses the typed convenience methods.
- There is **no** create/add or remove/delete method on the facade or anywhere in the stack, and no
  create/delete RPC is ever called, the safety invariant is upheld structurally.
- The `HttpClientEngine`-injecting constructor (plus optional `androidId`) is the required shape so
  wasmJs and jvm can each supply their own engine.

## Getting a master token, `runJvmBootstrap`

The server needs a gpsoauth **master token** (`aas_et/…`). It is minted from a fresh `oauth_token`
that Google issues at the end of its Android **EmbeddedSetup** sign-in. That sign-in is interactive
(email + password + any 2FA) and must be done by a human, so a bundled tool hosts a browser for it
and does the rest automatically (`src/jvmMain/kotlin/googlehome/mcp/bootstrap/`):

1. Launches **real Chrome** (`channel=chrome`, persistent profile, headed) at
   `accounts.google.com/EmbeddedSetup`. Real Chrome + a persisted profile avoids the "this browser
   may not be secure" bot-block and keeps you signed in across runs.
2. Polls for the `oauth_token` cookie once you finish signing in.
3. Exchanges it for a master token via `ac2dm` (`GpsOAuthClient.exchangeOAuthToken`).

```bash
# Interactive: Chrome opens, you sign in, token is captured + exchanged.
# The master token is a full-account secret, redirect stdout to a private file.
./gradlew runJvmBootstrap > ~/.googlehome-mcp/token.env
source ~/.googlehome-mcp/token.env        # sets GOOGLE_MASTER_TOKEN + GOOGLE_ANDROID_ID
./gradlew runJvm                          # server now has a token

# Or, if you captured the oauth_token yourself (e.g. devtools on EmbeddedSetup):
./gradlew runJvmBootstrap --args="--oauth-token <oauth_token>" > ~/.googlehome-mcp/token.env
```

**`androidId` matters.** gpsoauth ties the master token to the `androidId` it was minted with, and
that same id must be reused for every later foyer-bearer mint. The tool generates one and prints it
as `GOOGLE_ANDROID_ID`; keep it with the token and pass both to the server.

**Security.** The master token grants full account access. It is written only to stdout (redirect to
a private file), never logged; the raw browser captures and the Chrome profile stay under
`~/.googlehome-mcp/` and are never committed.
