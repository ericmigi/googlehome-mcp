@file:OptIn(ExperimentalWasmInterop::class)

package googlehome.mcp.plugin

import googlehome.mcp.auth.FoyerAuthImpl
import googlehome.mcp.auth.MasterToken
import googlehome.mcp.foyer.Automation
import googlehome.mcp.foyer.Device
import googlehome.mcp.foyer.DeviceState
import googlehome.mcp.foyer.FoyerRpcClient
import googlehome.mcp.foyer.GoogleHomeFoyerClient
import googlehome.mcp.foyer.Home
import googlehome.mcp.foyer.Room
import googlehome.mcp.server.GhTool
import googlehome.mcp.server.GoogleHomeMcpServer
import googlehome.mcp.server.PasscodeStore
import googlehome.mcp.server.normalizePasscode
import kotlin.random.Random
import kotlin.wasm.ExperimentalWasmInterop
import kotlin.wasm.WasmExport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * The chasm wasm-MCP **plugin entrypoint** for the JS-free `wasmWasi` module. Same logic as the
 * `wasmJs` `PluginEntry`, but the transport is the `env::host_*` ABI (see [HostAbi]) instead of a JS
 * `coreHost` bridge, and the shared `suspend` server is driven synchronously via [runSync].
 *
 * Exports (`@WasmExport`): `plugin_list_tools`, `plugin_call_tool`, `plugin_get_context`. `main()` is
 * the reactor init hook (`binaries.executable()` requires it). This is a WASI **reactor**: the host
 * must invoke the exported `_initialize` once after instantiation (running module init / top-level
 * property initializers) before calling any `plugin_*` export — `ChasmPluginHost` does exactly that.
 *
 * ## Secrets (`master_token`, `android_id`, `lock_pin`)
 * Read/written via `host_secret_get/set`, namespaced to this MCP by the host. Never logged or returned.
 * Auth is deferred: a cold [plugin_list_tools]/[plugin_get_context] makes zero host calls beyond
 * `host_write_output`; sign-in happens lazily on the first [plugin_call_tool] that needs the network.
 */

private const val KEY_MASTER_TOKEN = "master_token"
private const val KEY_ANDROID_ID = "android_id"
private const val KEY_LOCK_PIN = "lock_pin"

private val JSON = Json { ignoreUnknownKeys = true }

/** Reactor init hook. Kotlin/Wasm runs module init (top-level property initializers) at instantiation. */
fun main() {}

// -------------------------------------------------------------------------------------------------
// Static tool registry (zero host I/O — the defer-auth design)
// -------------------------------------------------------------------------------------------------

/**
 * A [GoogleHomeFoyerClient] with no transport: every RPC throws. Lets the **static** tool definitions
 * (name/description/inputSchema) be read from the one true [GoogleHomeMcpServer] registry without an
 * authenticated client, so a cold `plugin_list_tools` provably performs no `host_secret_get` /
 * `host_browser_auth` / `host_http_fetch`.
 */
private object NoNetworkFoyerClient : GoogleHomeFoyerClient {
    private fun no(): Nothing =
        error("listTools/getContext must not perform network I/O; only callTool builds a live client.")

    override suspend fun getHomeGraph(): Pair<List<Home>, List<Device>> = no()
    override suspend fun listRooms(): List<Room> = no()
    override suspend fun getTraits(deviceIds: List<String>): List<DeviceState> = no()
    override suspend fun setOnOff(deviceId: String, agentId: String, partnerDeviceId: String, on: Boolean): DeviceState = no()
    override suspend fun setBrightness(deviceId: String, agentId: String, partnerDeviceId: String, pct: Int): DeviceState = no()
    override suspend fun setColorTemperature(deviceId: String, agentId: String, partnerDeviceId: String, kelvin: Int): DeviceState = no()
    override suspend fun setVolume(deviceId: String, agentId: String, partnerDeviceId: String, pct: Int): DeviceState = no()
    override suspend fun setMuted(deviceId: String, agentId: String, partnerDeviceId: String, muted: Boolean): DeviceState = no()
    override suspend fun setLocked(deviceId: String, agentId: String, partnerDeviceId: String, locked: Boolean, pin: String?): DeviceState = no()
    override suspend fun setThermostat(deviceId: String, agentId: String, partnerDeviceId: String, setpointC: Double?, mode: String?): DeviceState = no()
    override suspend fun mediaCommand(deviceId: String, agentId: String, partnerDeviceId: String, command: String): DeviceState = no()
    override suspend fun listAutomations(): List<Automation> = no()
    override suspend fun runAutomation(automation: Automation): Boolean = no()
}

/** Single source of truth for `plugin_list_tools`. Built once; reading its metadata does zero host I/O. */
private val toolDefs: List<GhTool> = GoogleHomeMcpServer(NoNetworkFoyerClient).tools

/** The unlock PIN store, backed by this MCP's namespaced secret store (`lock_pin`). */
private object WasiPasscodeStore : PasscodeStore {
    override suspend fun get(): String? = hostSecretGet(KEY_LOCK_PIN)?.takeIf { it.isNotBlank() }
    override suspend fun set(pin: String) = hostSecretSet(KEY_LOCK_PIN, pin)
}

// -------------------------------------------------------------------------------------------------
// Lazy authenticated server
// -------------------------------------------------------------------------------------------------

/** The lazily-bootstrapped authenticated server, built at most once. Single-threaded, so no lock. */
private var cachedServer: GoogleHomeMcpServer? = null

private suspend fun mcp(): GoogleHomeMcpServer = cachedServer ?: bootstrap().also { cachedServer = it }

/**
 * Resolve credentials, doing the one-time sign-in only if no master token is stored yet. Every run
 * after the first is two `host_secret_get`s and no network. On the first run: `host_browser_auth`
 * captures the `oauth_token`, then the `ac2dm` exchange (over `host_http_fetch`) mints the master token.
 */
private suspend fun bootstrap(): GoogleHomeMcpServer {
    val storedMaster = hostSecretGet(KEY_MASTER_TOKEN)?.takeIf { it.isNotBlank() }
    val storedAndroidId = hostSecretGet(KEY_ANDROID_ID)?.takeIf { it.isNotBlank() }

    if (storedMaster != null) {
        return serverFor(storedMaster, storedAndroidId ?: MasterToken.DEFAULT_ANDROID_ID)
    }

    // Persist the android id BEFORE minting, so a crash mid-exchange cannot orphan the master token.
    val androidId = storedAndroidId ?: newAndroidId().also { hostSecretSet(KEY_ANDROID_ID, it) }

    hostLog(1, "No Google master token stored; starting sign-in.")
    val oauthToken = hostBrowserAuth()?.takeIf { it.isNotBlank() }
        ?: throw IllegalStateException("Google sign-in was cancelled or captured no token.")

    val result = WasiGpsOAuth.exchangeOAuthToken(oauthToken, androidId)
    hostSecretSet(KEY_MASTER_TOKEN, result.masterToken)
    hostLog(1, "Google master token obtained and stored.")

    return serverFor(result.masterToken, androidId)
}

private fun serverFor(masterToken: String, androidId: String): GoogleHomeMcpServer {
    val auth = FoyerAuthImpl(MasterToken(masterToken, androidId), WasiGpsOAuth)
    val foyer = FoyerRpcClient(HostFoyerTransport, auth)
    return GoogleHomeMcpServer(foyer, WasiPasscodeStore)
}

/** A fresh 16-hex-char device id (uses `random_get` via kotlin.random). */
private fun newAndroidId(): String =
    (0 until 16).joinToString("") { Random.nextInt(16).toString(16) }

// -------------------------------------------------------------------------------------------------
// ABI exports
// -------------------------------------------------------------------------------------------------

/**
 * `[ {name, description, inputSchemaJson} ]` from the static registry. Zero host I/O beyond the write.
 * `inputSchemaJson` is the schema pre-stringified (the host decodes straight into `WasmToolDef`); with
 * no JS glue to `JSON.stringify` it, the wasm side does it.
 */
@WasmExport
fun plugin_list_tools(): Int {
    val json = buildJsonArray {
        toolDefs.forEach { t ->
            add(
                buildJsonObject {
                    put("name", t.name)
                    put("description", t.description)
                    put("inputSchemaJson", t.inputSchema().toString())
                },
            )
        }
    }.toString()
    hostWriteOutput(json)
    return 0
}

/**
 * Read the tool name (slot 0) + argsJson (slot 1), dispatch, deliver the JSON result. Handler errors
 * come back as `{"ok":false,"error":"…"}` output (not a status code), so a bad argument reads as a
 * tool result the model can correct. `set_passcode` is intercepted as pure secret storage — no sign-in.
 */
@WasmExport
fun plugin_call_tool(): Int {
    val name = hostReadInput(0)
    val argsJson = hostReadInput(1)
    val result = try {
        runSync { callTool(name, argsJson) }
    } catch (e: Throwable) {
        buildJsonObject {
            put("ok", false)
            // Redact token-shaped substrings: an error surfaced from the host (or a stack/message that
            // happens to carry a request) must never hand the model a bearer/master token.
            put("error", redactSecrets(e.message ?: e.toString()))
        }.toString()
    }
    hostWriteOutput(result)
    return 0
}

/** Mask credential-shaped substrings so a tool error can't leak a token back to the model. */
private fun redactSecrets(s: String): String {
    var out = s
    out = out.replace(Regex("""aas_et/[\w./+=-]+"""), "<redacted>")           // gpsoauth master token
    out = out.replace(Regex("""ya29\.[\w./+=-]+"""), "<redacted>")            // google access token
    out = out.replace(Regex("""(?i)bearer\s+[\w./+=-]+"""), "Bearer <redacted>")
    out = out.replace(
        Regex("""(?i)(EncryptedPasswd|oauth_token|Token|ACCESS_TOKEN|Authorization|Auth)=[^&\s"]+"""),
    ) { "${it.groupValues[1]}=<redacted>" }
    return out
}

private suspend fun callTool(name: String, argsJson: String): String {
    val args: JsonObject = if (argsJson.isBlank()) JsonObject(emptyMap()) else JSON.parseToJsonElement(argsJson).jsonObject
    // Saving the passcode is pure secret storage — handle it before mcp()/bootstrap so it never
    // triggers a Google sign-in (a user may save a PIN before ever signing in).
    if (name == "set_passcode") {
        val pin = normalizePasscode((args["passcode"] as? JsonPrimitive)?.content.orEmpty())
        WasiPasscodeStore.set(pin)
        return buildJsonObject {
            put("ok", true)
            put("saved", true)
            put("length", pin.length)
        }.toString()
    }
    // Reject an unknown/typo'd tool name BEFORE mcp(): bootstrapping would read secrets, persist an
    // android id, and open Google sign-in for a tool that doesn't exist. Validate against the static
    // registry (the same list plugin_list_tools advertises) first — zero host calls for a bad name.
    require(toolDefs.any { it.name == name }) { "Unknown tool: $name" }
    return mcp().call(name, args).toString()
}

/** Static orientation for the model. No device enumeration (that would force a sign-in every session). */
@WasmExport
fun plugin_get_context(): Int {
    hostWriteOutput(
        "Google Home device control. Call list_devices first to discover the names, rooms, and types " +
            "the control tools' selectors can target. Reads are unrestricted; control acts on every " +
            "matching capable device. There is no add or delete surface.",
    )
    return 0
}
