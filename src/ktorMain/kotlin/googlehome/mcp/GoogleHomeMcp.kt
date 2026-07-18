package googlehome.mcp

import googlehome.mcp.auth.FoyerAuthImpl
import googlehome.mcp.auth.GpsOAuthClient
import googlehome.mcp.auth.MasterToken
import googlehome.mcp.foyer.GoogleHomeFoyerClient
import googlehome.mcp.foyer.GoogleHomeFoyerClientImpl
import googlehome.mcp.server.GhTool
import googlehome.mcp.server.GoogleHomeMcpServer
import googlehome.mcp.server.PasscodeStore
import io.ktor.client.engine.HttpClientEngine
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Public facade. Platform entrypoints (jvm webserver, wasmJs browser) construct this with the
 * long-lived gpsoauth master token + their Ktor engine, then expose its tools to an MCP transport.
 *
 * ## Construction
 *
 * - `GoogleHomeMcp(masterToken, engine)` — the entrypoint path. Wires the gpsoauth auth bridge
 *   ([FoyerAuthImpl]) and the foyer HTTP client ([GoogleHomeFoyerClientImpl]) over the injected
 *   [HttpClientEngine], so each target (`OkHttp` on jvm, `Js` on wasmJs) supplies its own engine and
 *   commonMain stays platform-agnostic.
 * - `GoogleHomeMcp(foyer)` — DI/test path: inject an already-built [GoogleHomeFoyerClient].
 *
 * ## Two ways to consume it
 *
 * - [server] / [tools]: the [GoogleHomeMcpServer] and its [GhTool] registry — the single source of
 *   tool definitions + handlers.
 * - The `list*` / `getDeviceState` / control convenience methods: run the same handlers and hand back
 *   the JSON **string** an MCP `TextContent` carries. The JVM MCP SDK transport and the wasmJs JS
 *   bridge both call these (or [server] directly).
 *
 * ## Safety
 *
 * Reads are unrestricted. Control is general (any device), via selector-based tools — but strictly
 * *control of existing devices*: there is no create/add or remove/delete method here or anywhere in
 * the stack, and no create/delete RPC is ever called.
 */
class GoogleHomeMcp private constructor(
    private val srv: GoogleHomeMcpServer,
) {
    /** DI/test constructor: use an already-constructed foyer client. */
    constructor(
        foyer: GoogleHomeFoyerClient,
        passcodes: PasscodeStore? = null,
    ) : this(GoogleHomeMcpServer(foyer, passcodes))

    /**
     * Entrypoint constructor: build the full stack from the master token over [engine].
     *
     * @param masterToken the gpsoauth master token (starts with `aas_et/`).
     * @param engine the platform Ktor [HttpClientEngine] (`OkHttp.create()` on jvm, `Js.create()` on
     *   wasmJs). Used for both the gpsoauth auth calls and the foyer-pa RPC calls.
     * @param androidId the 16-hex device id the master token was minted with (see
     *   [MasterToken.DEFAULT_ANDROID_ID]); supply the real one, do not rely on the default.
     */
    constructor(
        masterToken: String,
        engine: HttpClientEngine,
        androidId: String = MasterToken.DEFAULT_ANDROID_ID,
        passcodes: PasscodeStore? = null,
    ) : this(
        GoogleHomeFoyerClientImpl(
            engine = engine,
            auth = FoyerAuthImpl(MasterToken(masterToken, androidId), GpsOAuthClient(engine)),
        ),
        passcodes,
    )

    /** The configured server holding the tool definitions + handlers. */
    fun server(): GoogleHomeMcpServer = srv

    /** The tool registry (read tools first, then the selector-based control tools). */
    fun tools(): List<GhTool> = srv.tools

    // ---------------------------------------------------------------------------------------------
    // Read convenience methods — run a tool handler and return its JSON result as a string.
    // ---------------------------------------------------------------------------------------------

    /** JSON array of homes/structures. Read-only. */
    suspend fun listHomes(): String = srv.call("list_homes", EMPTY_ARGS).toString()

    /** JSON array of devices with room, type, traits, capabilities, and live online/onOff state. Read-only. */
    suspend fun listDevices(): String = srv.call("list_devices", EMPTY_ARGS).toString()

    /** JSON array of live states for [deviceIds]. Read-only. */
    suspend fun getDeviceState(deviceIds: List<String>): String {
        val args = buildJsonObject {
            put("device_ids", buildJsonArray { deviceIds.forEach { add(JsonPrimitive(it)) } })
        }
        return srv.call("get_device_state", args).toString()
    }

    // ---------------------------------------------------------------------------------------------
    // Control convenience methods — resolve a selector, apply to every capable match, return the
    // per-device JSON result array as a string. `null`/blank selector fields are simply omitted.
    // ---------------------------------------------------------------------------------------------

    /** Turn matching on/off devices ON. */
    suspend fun turnOn(name: String? = null, room: String? = null, type: String? = null): String =
        srv.call("turn_on", selectorArgs(name, room, type)).toString()

    /** Turn matching on/off devices OFF. */
    suspend fun turnOff(name: String? = null, room: String? = null, type: String? = null): String =
        srv.call("turn_off", selectorArgs(name, room, type)).toString()

    /** Set brightness (0-100, clamped) on matching dimmable devices. */
    suspend fun setBrightness(brightnessPct: Int, name: String? = null, room: String? = null, type: String? = null): String =
        srv.call("set_brightness", selectorArgs(name, room, type) { put("brightness_pct", brightnessPct) }).toString()

    /** Set color temperature (Kelvin) on matching color/CCT devices. */
    suspend fun setColorTemperature(kelvin: Int, name: String? = null, room: String? = null, type: String? = null): String =
        srv.call("set_color_temperature", selectorArgs(name, room, type) { put("kelvin", kelvin) }).toString()

    /** Set volume (0-100, clamped) on matching speakers. */
    suspend fun setVolume(volumePct: Int, name: String? = null, room: String? = null, type: String? = null): String =
        srv.call("set_volume", selectorArgs(name, room, type) { put("volume_pct", volumePct) }).toString()

    /** Mute/unmute matching speakers. */
    suspend fun setMuted(muted: Boolean, name: String? = null, room: String? = null, type: String? = null): String =
        srv.call("set_muted", selectorArgs(name, room, type) { put("muted", muted) }).toString()

    /** Lock matching smart locks. */
    suspend fun lock(name: String? = null, room: String? = null, type: String? = null): String =
        srv.call("lock", selectorArgs(name, room, type)).toString()

    /** Unlock matching smart locks (PIN-gated). Omit [pin] to use the saved passcode ([setPasscode]). */
    suspend fun unlock(pin: String? = null, name: String? = null, room: String? = null, type: String? = null): String =
        srv.call("unlock", selectorArgs(name, room, type) { if (!pin.isNullOrBlank()) put("pin", pin) }).toString()

    /** Save the smart-lock unlock passcode so [unlock] can be called without it. */
    suspend fun setPasscode(passcode: String): String =
        srv.call("set_passcode", buildJsonObject { put("passcode", JsonPrimitive(passcode)) }).toString()

    /** Set thermostat setpoint (Celsius and/or Fahrenheit) and/or mode on matching thermostats. */
    suspend fun setThermostat(
        temperatureC: Double? = null,
        temperatureF: Double? = null,
        mode: String? = null,
        name: String? = null,
        room: String? = null,
        type: String? = null,
    ): String = srv.call(
        "set_thermostat",
        selectorArgs(name, room, type) {
            if (temperatureC != null) put("temperature_c", temperatureC)
            if (temperatureF != null) put("temperature_f", temperatureF)
            if (mode != null && mode.isNotBlank()) put("mode", mode)
        },
    ).toString()

    /** Issue a media transport command (play/pause/next/previous/stop) on matching players. */
    suspend fun mediaControl(command: String, name: String? = null, room: String? = null, type: String? = null): String =
        srv.call("media_control", selectorArgs(name, room, type) { put("command", command) }).toString()

    /** JSON array of the home's automations/routines. Read-only. */
    suspend fun listAutomations(): String = srv.call("list_automations", EMPTY_ARGS).toString()

    /** Run/start a manually-runnable automation by name. Returns `{name,id,ok}`. */
    suspend fun runAutomation(name: String): String =
        srv.call("run_automation", buildJsonObject { put("name", JsonPrimitive(name)) }).toString()

    // ---------------------------------------------------------------------------------------------

    private inline fun selectorArgs(
        name: String?,
        room: String?,
        type: String?,
        extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {},
    ): JsonObject = buildJsonObject {
        if (!name.isNullOrBlank()) put("name", name)
        if (!room.isNullOrBlank()) put("room", room)
        if (!type.isNullOrBlank()) put("type", type)
        extra()
    }

    private companion object {
        val EMPTY_ARGS = JsonObject(emptyMap())
    }
}
