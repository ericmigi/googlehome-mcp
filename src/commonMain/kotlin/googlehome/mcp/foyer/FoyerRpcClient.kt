package googlehome.mcp.foyer

import googlehome.mcp.auth.FoyerAuth
import kotlin.math.roundToInt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/** Raised when foyer-pa returns a non-success HTTP status (auth failure, quota, server error). */
class FoyerHttpException(val status: Int, message: String) : Exception(message)

/** A foyer-pa HTTP reply, reduced to what [FoyerRpcClient] needs (status + text body). */
class FoyerResponse(val status: Int, val body: String)

/**
 * The transport under [FoyerRpcClient]: one POST of a `application/json+protobuf` [bodyJson] to [url]
 * with `Authorization: Bearer <bearer>`. Ktor supplies one impl (`GoogleHomeFoyerClientImpl`), the
 * wasmWasi plugin another (`host_http_fetch`) — the RPC/retry/parse logic below is shared by both.
 */
fun interface FoyerHttpTransport {
    suspend fun post(url: String, bodyJson: String, bearer: String): FoyerResponse
}

/**
 * [GoogleHomeFoyerClient] over an injected [FoyerHttpTransport], holding all the protocol logic that
 * used to live in `GoogleHomeFoyerClientImpl`: build a positional `application/json+protobuf` array
 * (via [FoyerCodec]), POST it to `<BASE>/<Service>/<Method>`, mint the bearer from [auth] (replacing
 * the web app's cookie `SAPISIDHASH`), and on a 401/403 [FoyerAuth.invalidate] + retry once.
 *
 * **Safety:** control is general (no device-id allowlist), but strictly *control of existing
 * devices* — there is no add/create or remove/delete path here. Each control method takes the
 * already-resolved `(deviceId, agentId, partnerDeviceId)` and issues exactly one `UpdateTraits`.
 */
class FoyerRpcClient(
    private val transport: FoyerHttpTransport,
    private val auth: FoyerAuth,
) : GoogleHomeFoyerClient {

    private val json = Json { ignoreUnknownKeys = true }

    // ---------------------------------------------------------------------------------------------
    // Reads (unrestricted)
    // ---------------------------------------------------------------------------------------------

    override suspend fun getHomeGraph(): Pair<List<Home>, List<Device>> {
        val graph = fetchHomeGraph()
        return graph.homes to graph.devices
    }

    override suspend fun listRooms(): List<Room> = fetchHomeGraph().rooms

    override suspend fun getTraits(deviceIds: List<String>): List<DeviceState> {
        if (deviceIds.isEmpty()) return emptyList()
        val body = FoyerCodec.buildGetTraitsRequest(deviceIds)
        val resp = rpc(HOME_CONTROL_SERVICE, "GetTraits", body)
        return FoyerCodec.parseGetTraits(resp)
    }

    // ---------------------------------------------------------------------------------------------
    // Control (general — no allowlist; control of existing devices only, never add/delete)
    // ---------------------------------------------------------------------------------------------

    override suspend fun setOnOff(deviceId: String, agentId: String, partnerDeviceId: String, on: Boolean): DeviceState =
        update(deviceId, FoyerCodec.buildOnOff(deviceId, agentId, partnerDeviceId, on), fallbackOnOff = on)

    override suspend fun setBrightness(deviceId: String, agentId: String, partnerDeviceId: String, pct: Int): DeviceState {
        val clamped = pct.coerceIn(0, 100)
        return update(deviceId, FoyerCodec.buildBrightness(deviceId, agentId, partnerDeviceId, clamped))
    }

    override suspend fun setColorTemperature(deviceId: String, agentId: String, partnerDeviceId: String, kelvin: Int): DeviceState =
        update(deviceId, FoyerCodec.buildColorTemperature(deviceId, agentId, partnerDeviceId, kelvin))

    override suspend fun setVolume(deviceId: String, agentId: String, partnerDeviceId: String, pct: Int): DeviceState {
        val clamped = pct.coerceIn(0, 100)
        return update(deviceId, FoyerCodec.buildVolume(deviceId, agentId, partnerDeviceId, clamped))
    }

    override suspend fun setMuted(deviceId: String, agentId: String, partnerDeviceId: String, muted: Boolean): DeviceState =
        update(deviceId, FoyerCodec.buildMuted(deviceId, agentId, partnerDeviceId, muted))

    override suspend fun setLocked(
        deviceId: String,
        agentId: String,
        partnerDeviceId: String,
        locked: Boolean,
        pin: String?,
    ): DeviceState {
        val resp = rpc(HOME_CONTROL_SERVICE, "UpdateTraits", FoyerCodec.buildLock(deviceId, agentId, partnerDeviceId, locked))
        val challenge = FoyerCodec.parseChallenge(resp)
        if (challenge != null) {
            // Unlock is PIN-gated. Without a PIN we cannot proceed.
            if (pin == null) throw ChallengeRequiredException(deviceId, challenge)
            // TODO(needs-live-confirm): the exact PIN challenge-response shape must be captured from a
            // real web-app unlock; buildLockWithPin is a best-effort structure until then.
            val resp2 = rpc(
                HOME_CONTROL_SERVICE,
                "UpdateTraits",
                FoyerCodec.buildLockWithPin(deviceId, agentId, partnerDeviceId, locked, pin),
            )
            return stateFrom(resp2, deviceId, fallbackLocked = locked)
        }
        return stateFrom(resp, deviceId, fallbackLocked = locked)
    }

    override suspend fun setThermostat(
        deviceId: String,
        agentId: String,
        partnerDeviceId: String,
        setpointC: Double?,
        mode: String?,
    ): DeviceState {
        // Nest requires `mode` sent with the setpoint, and the setpoint value must be in the device's
        // DISPLAY unit (verified: thermostatTemperatureSetpoint, °F for this device). Read current
        // state to learn the unit + current mode, convert the Celsius target, and send both together.
        val current = getTraits(listOf(deviceId)).firstOrNull { it.id == deviceId }
        val unit = current?.temperatureUnit ?: "F"
        val effectiveMode = mode ?: current?.thermostatMode ?: "heat"
        val setpointDisplay = setpointC?.let { c ->
            if (unit.equals("F", ignoreCase = true)) (c * 9.0 / 5.0 + 32.0).roundToInt().toDouble()
            else (c * 2.0).roundToInt() / 2.0   // nearest 0.5 °C
        }
        return update(
            deviceId,
            FoyerCodec.buildThermostatSetpoint(deviceId, agentId, partnerDeviceId, setpointDisplay, effectiveMode),
        )
    }

    override suspend fun mediaCommand(deviceId: String, agentId: String, partnerDeviceId: String, command: String): DeviceState =
        update(deviceId, FoyerCodec.buildMediaCommand(deviceId, agentId, partnerDeviceId, command))

    override suspend fun listAutomations(): List<Automation> {
        val structureId = fetchHomeGraph().homes.firstOrNull()?.id
            ?: throw IllegalStateException("No structure/home found; cannot list automations.")
        val resp = rpc(AUTOMATION_SERVICE, "ListAutomations", FoyerCodec.buildListAutomationsRequest(structureId))
        return FoyerCodec.parseListAutomations(resp)
    }

    override suspend fun runAutomation(automation: Automation): Boolean {
        require(automation.manuallyRunnable) {
            "Automation '${automation.name}' is condition/schedule-triggered and can't be started on demand."
        }
        val structureId = fetchHomeGraph().homes.firstOrNull()?.id
            ?: throw IllegalStateException("No structure/home found; cannot run automation.")
        // Verified: AutomationService/ExecuteAutomation, body [structureId, automationId, null, 2],
        // response [1] on success.
        val resp = rpc(AUTOMATION_SERVICE, "ExecuteAutomation", FoyerCodec.buildExecuteAutomationRequest(structureId, automation.id))
        return (resp as? JsonArray)?.firstOrNull()?.let { (it as? JsonPrimitive)?.content == "1" } ?: false
    }

    // ---------------------------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------------------------

    /** Issues one `UpdateTraits` and resolves the resulting [DeviceState] (echo → readback → fallback). */
    private suspend fun update(deviceId: String, body: JsonArray, fallbackOnOff: Boolean? = null): DeviceState {
        val resp = rpc(HOME_CONTROL_SERVICE, "UpdateTraits", body)
        return stateFrom(resp, deviceId, fallbackOnOff = fallbackOnOff)
    }

    /**
     * Resolves a [DeviceState] for [deviceId] from an `UpdateTraits` echo. The echo carries the same
     * per-device shape `GetTraits` does but may omit the field just written, so if the echo lacks the
     * device (or is empty) we read it back explicitly. As a last resort a minimal state is returned.
     */
    private suspend fun stateFrom(
        resp: JsonElement,
        deviceId: String,
        fallbackOnOff: Boolean? = null,
        fallbackLocked: Boolean? = null,
    ): DeviceState {
        FoyerCodec.parseGetTraits(resp).firstOrNull { it.id == deviceId }?.let { return it }
        return getTraits(listOf(deviceId)).firstOrNull { it.id == deviceId }
            ?: DeviceState(id = deviceId, online = true, onOff = fallbackOnOff, locked = fallbackLocked)
    }

    private suspend fun fetchHomeGraph(): HomeGraph {
        val resp = rpc(STRUCTURES_SERVICE, "GetHomeGraph", EMPTY_BODY)
        return FoyerCodec.parseGetHomeGraph(resp)
    }

    /**
     * POSTs [body] to `<BASE><service>/<method>` with foyer auth headers and returns the parsed
     * reply. On an auth rejection (401/403) it [FoyerAuth.invalidate]s the cached bearer — which
     * also advances scope negotiation — and retries **once** with a freshly minted token. Any other
     * non-2xx status throws [FoyerHttpException] instead of feeding an HTML error page to the parser.
     */
    private suspend fun rpc(service: String, method: String, body: JsonArray): JsonElement {
        val url = "$BASE$service/$method"

        var response = transport.post(url, body.toString(), auth.bearer())
        if (response.status == 401 || response.status == 403) {
            auth.invalidate()
            response = transport.post(url, body.toString(), auth.bearer())
        }
        if (response.status !in 200..299) {
            val snippet = response.body.take(300)
            throw FoyerHttpException(response.status, "foyer $service/$method -> HTTP ${response.status}: $snippet")
        }
        return json.parseToJsonElement(stripXssiPrefix(response.body))
    }

    companion object {
        /** foyer-pa `$rpc` base — service + method are appended. */
        const val BASE =
            "https://googlehomefoyer-pa.clients6.google.com/\$rpc/google.internal.home.foyer.v1."
        const val HOME_CONTROL_SERVICE = "HomeControlService"
        const val STRUCTURES_SERVICE = "StructuresService"
        const val AUTOMATION_SERVICE = "AutomationService"

        /** foyer speaks `application/json+protobuf` (JSON text); transports set this Content-Type. */
        const val CONTENT_TYPE = "application/json+protobuf"

        /** `GetHomeGraph` takes an empty positional array. */
        private val EMPTY_BODY = JsonArray(emptyList())

        /**
         * Strips a leading XSSI guard line (`)]}'` and friends) that many `clients6.google.com`
         * endpoints prepend to JSON responses. The captured foyer bodies had no such prefix, but
         * stripping one defensively costs nothing and avoids a parse failure if the live wire adds it.
         */
        fun stripXssiPrefix(body: String): String {
            val trimmed = body.trimStart()
            if (trimmed.startsWith(")]}'")) {
                return trimmed.removePrefix(")]}'").trimStart('\r', '\n')
            }
            return body
        }
    }
}
