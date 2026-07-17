@file:OptIn(kotlin.js.ExperimentalJsExport::class, kotlin.js.ExperimentalWasmJsInterop::class)

package googlehome.mcp

import io.ktor.client.engine.js.Js
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.promise
import kotlin.js.JsAny
import kotlin.js.Promise
import kotlin.js.toJsString

/**
 * wasmJs entrypoint (browser / embedding-host).
 *
 * The MCP SDK server transport is JVM-only (no wasmJs artifact in 0.8.3), so this target does NOT
 * host the MCP protocol itself. Instead it exports the tool functions directly; an embedding host
 * (e.g. CoreApp, or a JS shim) wires them to whatever MCP integration it already runs.
 *
 * ## Selector convention across exports
 *
 * Kotlin/Wasm `@JsExport` parameters must be primitives/strings, so the three selector fields are
 * plain `String`s where **`""` means "unset"** (they are folded to `null` before dispatch). Numeric
 * control values are primitive `Int`/`Boolean`/`Double`. Every export returns a `Promise` resolving
 * to a `JsString` holding the same JSON text the JVM server hands back as MCP `TextContent`
 * (`{id,name,ok,state|error}` arrays for control tools).
 *
 * Usage from JS:
 * ```
 *   initGoogleHomeMcp(masterToken);
 *   const devices = JSON.parse(await listDevices());
 *   await turnOff("", "kitchen", "light");          // all kitchen lights off
 *   await setBrightness("Corner Lamp", "", "", 40); // one lamp to 40%
 * ```
 */

private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

private var instance: GoogleHomeMcp? = null

private fun mcp(): GoogleHomeMcp = instance
    ?: throw IllegalStateException("Call initGoogleHomeMcp(masterToken) before using the Google Home tools.")

/** `""` -> `null`; otherwise the string. Lets JS pass empty selector fields. */
private fun String.orNull(): String? = takeIf { it.isNotBlank() }

/**
 * Install the module-level [GoogleHomeMcp] built from the long-lived gpsoauth master token over the
 * browser/JS Ktor engine. Must be called once before any tool export. Safe to call again to swap the
 * token.
 */
@JsExport
fun initGoogleHomeMcp(masterToken: String) {
    instance = GoogleHomeMcp(masterToken = masterToken, engine = Js.create())
}

/**
 * Like [initGoogleHomeMcp] but supplies the 16-hex device id the master token was minted with.
 * gpsoauth ties sub-tokens to this id, so pass the real one (the default used by the single-arg
 * overload can make token minting fail or yield a foyer-rejected bearer). Usage:
 * `initGoogleHomeMcpWithDeviceId(masterToken, androidId)`.
 */
@JsExport
fun initGoogleHomeMcpWithDeviceId(masterToken: String, androidId: String) {
    instance = GoogleHomeMcp(masterToken = masterToken, engine = Js.create(), androidId = androidId)
}

// -------------------------------------------------------------------------------------------------
// Read-only
// -------------------------------------------------------------------------------------------------

/** JSON array of homes/structures. Read-only. */
@JsExport
fun listHomes(): Promise<JsAny?> = scope.promise { mcp().listHomes().toJsString() }

/** JSON array of devices with room, type, traits, capabilities, and online/onOff state. Read-only. */
@JsExport
fun listDevices(): Promise<JsAny?> = scope.promise { mcp().listDevices().toJsString() }

/**
 * Live state for the given device ids. Read-only.
 * @param deviceIdsCsv comma-separated device UUIDs.
 */
@JsExport
fun getDeviceState(deviceIdsCsv: String): Promise<JsAny?> = scope.promise {
    val ids = deviceIdsCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    mcp().getDeviceState(ids).toJsString()
}

// -------------------------------------------------------------------------------------------------
// Control (general; selector fields: "" = unset). Returns {id,name,ok,state|error} array JSON.
// -------------------------------------------------------------------------------------------------

/** Turn matching on/off devices ON. */
@JsExport
fun turnOn(name: String, room: String, type: String): Promise<JsAny?> =
    scope.promise { mcp().turnOn(name.orNull(), room.orNull(), type.orNull()).toJsString() }

/** Turn matching on/off devices OFF. */
@JsExport
fun turnOff(name: String, room: String, type: String): Promise<JsAny?> =
    scope.promise { mcp().turnOff(name.orNull(), room.orNull(), type.orNull()).toJsString() }

/** Set brightness (0-100, clamped) on matching dimmable devices. */
@JsExport
fun setBrightness(name: String, room: String, type: String, brightnessPct: Int): Promise<JsAny?> =
    scope.promise { mcp().setBrightness(brightnessPct, name.orNull(), room.orNull(), type.orNull()).toJsString() }

/** Set color temperature (Kelvin) on matching color/CCT devices. */
@JsExport
fun setColorTemperature(name: String, room: String, type: String, kelvin: Int): Promise<JsAny?> =
    scope.promise { mcp().setColorTemperature(kelvin, name.orNull(), room.orNull(), type.orNull()).toJsString() }

/** Set volume (0-100, clamped) on matching speakers. */
@JsExport
fun setVolume(name: String, room: String, type: String, volumePct: Int): Promise<JsAny?> =
    scope.promise { mcp().setVolume(volumePct, name.orNull(), room.orNull(), type.orNull()).toJsString() }

/** Mute/unmute matching speakers. */
@JsExport
fun setMuted(name: String, room: String, type: String, muted: Boolean): Promise<JsAny?> =
    scope.promise { mcp().setMuted(muted, name.orNull(), room.orNull(), type.orNull()).toJsString() }

/** Lock matching smart locks. */
@JsExport
fun lock(name: String, room: String, type: String): Promise<JsAny?> =
    scope.promise { mcp().lock(name.orNull(), room.orNull(), type.orNull()).toJsString() }

/** Unlock matching smart locks (PIN-gated — [pin] required). */
@JsExport
fun unlock(name: String, room: String, type: String, pin: String): Promise<JsAny?> =
    scope.promise { mcp().unlock(pin, name.orNull(), room.orNull(), type.orNull()).toJsString() }

/**
 * Set a thermostat's setpoint and/or mode on matching thermostats. Pass `NaN` for an unused
 * temperature and `""` for an unused mode. Supply Celsius or Fahrenheit (Fahrenheit is converted).
 */
@JsExport
fun setThermostat(
    name: String,
    room: String,
    type: String,
    temperatureC: Double,
    temperatureF: Double,
    mode: String,
): Promise<JsAny?> = scope.promise {
    mcp().setThermostat(
        temperatureC = temperatureC.takeIf { !it.isNaN() },
        temperatureF = temperatureF.takeIf { !it.isNaN() },
        mode = mode.orNull(),
        name = name.orNull(),
        room = room.orNull(),
        type = type.orNull(),
    ).toJsString()
}

/** Issue a media transport command (play/pause/next/previous/stop) on matching players. */
@JsExport
fun mediaControl(name: String, room: String, type: String, command: String): Promise<JsAny?> =
    scope.promise { mcp().mediaControl(command, name.orNull(), room.orNull(), type.orNull()).toJsString() }

/** JSON array of the home's automations/routines. Read-only. */
@JsExport
fun listAutomations(): Promise<JsAny?> = scope.promise { mcp().listAutomations().toJsString() }

/** Run/start a manually-runnable automation by name. */
@JsExport
fun runAutomation(name: String): Promise<JsAny?> = scope.promise { mcp().runAutomation(name).toJsString() }

/**
 * Required entrypoint for the `binaries.executable()` wasmJs module. Exports attach on load.
 *
 * It also installs the CoreApp plugin ABI (`globalThis.mcpPlugin`, see
 * [googlehome.mcp.plugin.installPlugin]) so the one binary serves both consumers: a plain browser
 * host calls the `@JsExport`s above after `initGoogleHomeMcp(masterToken)`, while CoreApp's wasm-MCP
 * runtime talks to `mcpPlugin` and never touches them. Installing is inert on its own — the plugin
 * only reaches for `coreHost` when a tool is actually called — so the browser build pays nothing.
 */
fun main() {
    googlehome.mcp.plugin.installPlugin()
}
