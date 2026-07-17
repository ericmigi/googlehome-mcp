@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package googlehome.mcp.plugin

import googlehome.mcp.GoogleHomeMcp
import googlehome.mcp.auth.GpsOAuthClient
import googlehome.mcp.auth.MasterToken
import googlehome.mcp.foyer.Automation
import googlehome.mcp.foyer.Device
import googlehome.mcp.foyer.DeviceState
import googlehome.mcp.foyer.GoogleHomeFoyerClient
import googlehome.mcp.foyer.Home
import googlehome.mcp.foyer.Room
import googlehome.mcp.server.GhTool
import googlehome.mcp.server.GoogleHomeMcpServer
import googlehome.mcp.server.PasscodeStore
import googlehome.mcp.server.normalizePasscode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.await
import kotlinx.coroutines.promise
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.js.JsAny
import kotlin.js.JsString
import kotlin.js.Promise
import kotlin.js.toJsString
import kotlin.random.Random

/**
 * The CoreApp wasm-MCP **plugin entrypoint**: installs `globalThis.mcpPlugin` per the plugin ABI in
 * CoreApp's `docs/wasm-mcp/ARCHITECTURE.md`.
 *
 * ```
 * globalThis.mcpPlugin = {
 *   listTools:  async () => [ { name, description, inputSchema } ],
 *   callTool:   async (name, argsJson) => resultJson,
 *   getContext: async () => string | null,
 * }
 * ```
 *
 * Everything below the ABI is the **unchanged** `commonMain` stack. Only two edges differ from the
 * jvm entrypoint (`Main.kt`):
 *
 * 1. The Ktor engine is [CoreHostEngine], so all egress is allow-listed by the host.
 * 2. Credentials come from `coreHost.secretGet` / `coreHost.browserAuth` instead of env vars.
 *
 * This file coexists with `WasmEntry.kt`: that one is the standalone browser build (`Js` engine,
 * caller-supplied token), this one is the sandboxed-plugin build. They share the same facade.
 *
 * ## Secrets
 *
 * Two keys, both namespaced to this MCP by the bridge (`mcp:<installId>:*`) — the plugin cannot name
 * another MCP's key even if it tried:
 * - `master_token` — the long-lived gpsoauth master token (`aas_et/…`). Full account access.
 * - `android_id` — the 16-hex device id the master token was minted against. gpsoauth ties issued
 *   sub-tokens to it, so it must be persisted **before** the exchange and reused forever after; a
 *   mismatch yields a bearer foyer rejects.
 *
 * Neither is ever passed to `coreHost.log`, returned from a tool, or put in an error message.
 */

private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

private val initLock = Mutex()
private var cached: GoogleHomeMcp? = null

private val JSON = Json { ignoreUnknownKeys = true }

private const val KEY_MASTER_TOKEN = "master_token"
private const val KEY_ANDROID_ID = "android_id"
private const val KEY_LOCK_PIN = "lock_pin"

/**
 * The unlock passcode, stored in this MCP's namespaced secret store (`mcp:<installId>:lock_pin`) — the
 * same secure store as the master token, so no other plugin can read it. Kept independent of the
 * authenticated facade so saving a PIN never forces a Google sign-in (see [callToolJson]).
 */
private object CoreHostPasscodeStore : PasscodeStore {
    override suspend fun get(): String? =
        coreHostSecretGet(KEY_LOCK_PIN).await<JsString?>()?.toString()?.takeIf { it.isNotBlank() }

    override suspend fun set(pin: String) {
        coreHostSecretSet(KEY_LOCK_PIN, pin).await<JsAny?>()
    }
}

/**
 * A [GoogleHomeFoyerClient] that has no transport at all: every RPC throws. It exists solely so the
 * **static** tool definitions can be read from the one true [GoogleHomeMcpServer] registry
 * (`name` / `description` / `inputSchema`) **without** constructing an authenticated facade.
 *
 * This is what makes a cold `listTools()` provably network- and auth-free: enumeration reads
 * [GoogleHomeMcpServer.tools], which is populated at construction time and never touches the foyer
 * client — so a stub that throws on use is safe, and no `coreHost.secretGet` / `coreHost.browserAuth`
 * / `coreHost.httpFetch` can be reached. Auth is deferred to [mcp], which only [callToolJson] calls.
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

/**
 * The static tool registry — the single source of truth for `listTools()`. Built once from a server
 * over [NoNetworkFoyerClient]; reading tool metadata off it does zero host I/O (see that stub's KDoc).
 */
private val toolDefs: List<GhTool> = GoogleHomeMcpServer(NoNetworkFoyerClient).tools

/**
 * The lazily-bootstrapped **authenticated** facade, built at most once (concurrent tool calls share
 * one bootstrap rather than each opening their own sign-in browser).
 *
 * Only [callToolJson] reaches this. `listTools()` and `getContext()` are static and never call it, so
 * a cold enumeration performs no `secretGet` / `browserAuth` / `httpFetch`.
 */
private suspend fun mcp(): GoogleHomeMcp = initLock.withLock {
    cached ?: bootstrap().also { cached = it }
}

/**
 * Resolve credentials, doing the one-time sign-in only if we have no master token yet.
 *
 * The happy path on every run after the first is two `secretGet`s and no network at all. On the
 * first run: the host opens the manifest-pinned `accounts.google.com/EmbeddedSetup` in a real
 * browser, hands back the captured `oauth_token`, and we run the **existing**
 * [GpsOAuthClient.exchangeOAuthToken] `ac2dm` exchange over [CoreHostEngine] (so even the exchange
 * is allow-listed — hence `android.clients.google.com` in the manifest).
 */
private suspend fun bootstrap(): GoogleHomeMcp {
    val engine = CoreHostEngine()
    val storedMaster = coreHostSecretGet(KEY_MASTER_TOKEN).await<JsString?>()?.toString()?.takeIf { it.isNotBlank() }
    val storedAndroidId = coreHostSecretGet(KEY_ANDROID_ID).await<JsString?>()?.toString()?.takeIf { it.isNotBlank() }

    if (storedMaster != null) {
        // Already provisioned. If the id is somehow missing, the default is the only guess we have —
        // it will likely fail, and re-provisioning is the user-visible fix.
        return GoogleHomeMcp(
            masterToken = storedMaster,
            engine = engine,
            androidId = storedAndroidId ?: MasterToken.DEFAULT_ANDROID_ID,
            passcodes = CoreHostPasscodeStore,
        )
    }

    // Persist the android id BEFORE minting, so a crash mid-exchange cannot orphan the master token
    // from the id it was tied to.
    val androidId = storedAndroidId ?: newAndroidId().also { coreHostSecretSet(KEY_ANDROID_ID, it).await<JsAny?>() }

    coreHostLog("info", "No Google master token stored; starting sign-in.")
    val oauthToken = coreHostBrowserAuth().await<JsString?>()?.toString()?.takeIf { it.isNotBlank() }
        ?: throw IllegalStateException("Google sign-in was cancelled or captured no token.")

    val result = GpsOAuthClient(engine).exchangeOAuthToken(oauthToken, androidId)
    coreHostSecretSet(KEY_MASTER_TOKEN, result.masterToken).await<JsAny?>()
    coreHostLog("info", "Google master token obtained and stored.")

    return GoogleHomeMcp(masterToken = result.masterToken, engine = engine, androidId = androidId, passcodes = CoreHostPasscodeStore)
}

/** A fresh 16-hex-char device id, matching the shape gpsoauth expects (see `MasterTokenBootstrap`). */
private fun newAndroidId(): String =
    (0 until 16).joinToString("") { Random.nextInt(16).toString(16) }

// -------------------------------------------------------------------------------------------------
// ABI implementations
// -------------------------------------------------------------------------------------------------

/**
 * `[ {name, description, inputSchema} ]` straight from the single source of truth — the
 * [googlehome.mcp.server.GoogleHomeMcpServer] tool registry. No second list to drift out of sync.
 *
 * Reads the **static** [toolDefs] registry, deliberately **not** the authenticated [mcp] facade: tool
 * metadata is a compile-time constant, so a cold `listTools()` must — and here provably does — make
 * zero `coreHost.secretGet` / `coreHost.browserAuth` / `coreHost.httpFetch` calls. Sign-in is deferred
 * to the first [callToolJson] that actually needs the network.
 */
private fun listToolsJson(): Promise<JsAny?> = scope.promise {
    buildJsonArray {
        toolDefs.forEach { t ->
            add(
                buildJsonObject {
                    put("name", t.name)
                    put("description", t.description)
                    put("inputSchema", t.inputSchema())
                },
            )
        }
    }.toString().toJsString()
}

/**
 * Dispatch `name` into the facade's registry with the decoded `argsJson` and return the tool's JSON
 * result — the same text the JVM server hands back as MCP `TextContent`.
 *
 * Failures come back as `{"ok":false,"error":"…"}` rather than a rejected promise, so a bad argument
 * reads as a tool result the model can correct, not an engine crash. Messages are the handler's own
 * (selector misses, unsupported capabilities, gpsoauth server errors) and never carry a token.
 */
private fun callToolJson(name: String, argsJson: String): Promise<JsAny?> = scope.promise {
    try {
        val args: JsonObject = if (argsJson.isBlank()) {
            JsonObject(emptyMap())
        } else {
            JSON.parseToJsonElement(argsJson).jsonObject
        }
        // Saving the passcode is pure secret storage — handle it here, before mcp()/bootstrap, so it
        // never triggers a Google sign-in (a user may save a PIN before ever signing in).
        if (name == "set_passcode") {
            val pin = normalizePasscode((args["passcode"] as? JsonPrimitive)?.content.orEmpty())
            CoreHostPasscodeStore.set(pin)
            buildJsonObject {
                put("ok", true)
                put("saved", true)
                put("length", pin.length)
            }.toString().toJsString()
        } else {
            mcp().server().call(name, args).toString().toJsString()
        }
    } catch (e: Exception) {
        buildJsonObject {
            put("ok", false)
            put("error", e.message ?: e.toString())
        }.toString().toJsString()
    }
}

/**
 * Static orientation for the model. Deliberately does **not** enumerate devices: `getContext` runs on
 * every session, and a network round trip (plus a forced sign-in) is not something a context probe
 * should trigger. `list_devices` is the tool for that.
 */
private fun getContextJson(): Promise<JsAny?> = scope.promise {
    ("Google Home device control. Call list_devices first to discover the names, rooms, and types " +
        "the control tools' selectors can target. Reads are unrestricted; control acts on every " +
        "matching capable device. There is no add or delete surface.").toJsString()
}

/**
 * Installs the three ABI functions on `globalThis.mcpPlugin`.
 *
 * The glue unwraps `listTools` from JSON text into a real array so the JS-visible ABI matches the
 * spec exactly (`listTools` -> array of objects; `callTool` -> `resultJson` string).
 */
@JsFun(
    """(listTools, callTool, getContext) => {
        globalThis.mcpPlugin = {
            listTools:  async () => JSON.parse(await listTools()),
            callTool:   async (name, argsJson) => await callTool(name, argsJson || '{}'),
            getContext: async () => await getContext(),
        };
    }""",
)
private external fun installMcpPlugin(
    listTools: () -> Promise<JsAny?>,
    callTool: (String, String) -> Promise<JsAny?>,
    getContext: () -> Promise<JsAny?>,
)

/**
 * Module entrypoint. The host loads `glue.js`, which instantiates `module.wasm` and runs this; from
 * then on `globalThis.mcpPlugin` is live and the host's `WasmMcpRuntime` can dispatch into it.
 */
fun installPlugin() {
    installMcpPlugin(::listToolsJson, ::callToolJson, ::getContextJson)
}
