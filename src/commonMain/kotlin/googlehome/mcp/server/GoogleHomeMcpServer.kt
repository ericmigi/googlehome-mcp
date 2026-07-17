package googlehome.mcp.server

import googlehome.mcp.foyer.Automation
import googlehome.mcp.foyer.Capability
import googlehome.mcp.foyer.Device
import googlehome.mcp.foyer.DeviceState
import googlehome.mcp.foyer.GoogleHomeFoyerClient
import googlehome.mcp.foyer.Home
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Defines the Google Home MCP tools and their handlers (see docs/CONTRACTS2.md).
 *
 * ## Read tools (unrestricted)
 * [listHomesTool], [listDevicesTool], [getDeviceStateTool] can enumerate and read every home/device.
 * [listDevicesTool] now reports each device's derived [Capability] set alongside its live state.
 *
 * ## Control tools (general — no device-id allowlist)
 * `turn_on`/`turn_off`/`set_brightness`/`set_color_temperature`/`set_volume`/`set_muted`/`lock`/
 * `unlock`/`set_thermostat`/`media_control` each take a [Selector] (`name`/`room`/`type`) plus their
 * control argument. Each resolves the selector to devices via [DeviceResolver], applies the control to
 * **every capable match**, skips incapable matches with a note, and returns a JSON array of per-device
 * `{id,name,ok,state|error}`.
 *
 * ## Safety invariant ("no add, no delete")
 * Upheld structurally: there is **no** tool that creates/adds or removes/deletes a device, room, or
 * structure, and no handler calls a create/delete RPC. Control is strictly *control of existing
 * devices*. There is no hard-coded device allowlist anymore — the previous Outside-Lights lock and its
 * guard are removed.
 *
 * Tools are exposed via [tools]; handlers return [JsonElement] results (wasmJs-safe). The JVM
 * entrypoint adapts each [GhTool] to a real MCP SDK `Tool`.
 */
class GoogleHomeMcpServer(private val foyer: GoogleHomeFoyerClient) {

    private val resolver = DeviceResolver(foyer)

    // ---------------------------------------------------------------------------------------------
    // Read-only tools
    // ---------------------------------------------------------------------------------------------

    val listHomesTool: GhTool = GhTool(
        name = "list_homes",
        description = "List all Google Home homes/structures the account can see. Read-only.",
        handler = { _ ->
            val (homes, _) = foyer.getHomeGraph()
            buildJsonArray { homes.forEach { add(it.toJson()) } }
        },
    )

    val listDevicesTool: GhTool = GhTool(
        name = "list_devices",
        description = "List every device across all homes with its room, type, supported traits, " +
            "derived capabilities (on_off, brightness, color_temperature, color, volume, lock, " +
            "thermostat, media_transport), and live online/onOff state. Read-only. Use this to see " +
            "what names/rooms/types the control tools' selectors can target.",
        handler = { _ ->
            val (_, devices) = foyer.getHomeGraph()
            // Merge in live state so the enumeration carries online/onOff, per the tool contract.
            val stateById: Map<String, DeviceState> = if (devices.isEmpty()) {
                emptyMap()
            } else {
                runCatching { foyer.getTraits(devices.map { it.id }) }
                    .getOrDefault(emptyList())
                    .associateBy { it.id }
            }
            buildJsonArray { devices.forEach { add(it.toJson(stateById[it.id])) } }
        },
    )

    val getDeviceStateTool: GhTool = GhTool(
        name = "get_device_state",
        description = "Get the live state (online, on_off, brightness, color temperature, volume, " +
            "mute, lock, thermostat) for one or more devices by id. Read-only.",
        inputProperties = mapOf(
            "device_ids" to GhProperty(
                type = "array",
                description = "Device ids to read state for.",
                itemsType = "string",
            ),
        ),
        requiredInputs = listOf("device_ids"),
        handler = { args ->
            val ids = (args["device_ids"] as? JsonArray)
                ?.map { it.jsonPrimitive.content }
                ?: emptyList()
            val states = foyer.getTraits(ids)
            buildJsonArray { states.forEach { add(it.toJson()) } }
        },
    )

    // ---------------------------------------------------------------------------------------------
    // Control tools (selector-based; apply to every capable match)
    // ---------------------------------------------------------------------------------------------

    val turnOnTool: GhTool = GhTool(
        name = "turn_on",
        description = "Turn on devices. Scope with name/room/type, e.g. name=\"Corner Lamp\" for one " +
            "device, type=\"light\" for all lights, or room=\"kitchen\" + type=\"light\" for the " +
            "kitchen lights. Applies to every matching on/off device.",
        inputProperties = selectorProps(),
        handler = { args -> applyControl(args, Capability.ON_OFF, "on/off") { d -> setOnOff(d, true) } },
    )

    val turnOffTool: GhTool = GhTool(
        name = "turn_off",
        description = "Turn off devices. Scope with name/room/type, e.g. type=\"light\" for all " +
            "lights, or room=\"kitchen\" + type=\"light\" for the kitchen lights. Applies to every " +
            "matching on/off device.",
        inputProperties = selectorProps(),
        handler = { args -> applyControl(args, Capability.ON_OFF, "on/off") { d -> setOnOff(d, false) } },
    )

    val setBrightnessTool: GhTool = GhTool(
        name = "set_brightness",
        description = "Set brightness (0-100%) on lights. Scope with name/room/type, e.g. " +
            "room=\"bedroom\" + type=\"light\" brightness_pct=40. Applies to every matching dimmable " +
            "device.",
        inputProperties = selectorProps(
            "brightness_pct" to GhProperty(type = "integer", description = "Brightness 0-100 (clamped)."),
        ),
        requiredInputs = listOf("brightness_pct"),
        handler = { args ->
            val pct = requireInt(args, "brightness_pct")
            applyControl(args, Capability.BRIGHTNESS, "brightness") { d -> setBrightness(d, pct) }
        },
    )

    val setColorTemperatureTool: GhTool = GhTool(
        name = "set_color_temperature",
        description = "Set white color temperature in Kelvin (warm ~2700, cool ~6500) on color/CCT " +
            "lights. Scope with name/room/type, e.g. name=\"Desk Light\" kelvin=4000.",
        inputProperties = selectorProps(
            "kelvin" to GhProperty(type = "integer", description = "Color temperature in Kelvin, e.g. 2700-6500."),
        ),
        requiredInputs = listOf("kelvin"),
        handler = { args ->
            val kelvin = requireInt(args, "kelvin")
            applyControl(args, Capability.COLOR_TEMPERATURE, "color temperature") { d -> setColorTemp(d, kelvin) }
        },
    )

    val setVolumeTool: GhTool = GhTool(
        name = "set_volume",
        description = "Set volume (0-100%) on speakers. Scope with name/room/type, e.g. " +
            "name=\"Living Room Speaker\" volume_pct=30, or type=\"speaker\" for all speakers.",
        inputProperties = selectorProps(
            "volume_pct" to GhProperty(type = "integer", description = "Volume 0-100 (clamped)."),
        ),
        requiredInputs = listOf("volume_pct"),
        handler = { args ->
            val pct = requireInt(args, "volume_pct")
            applyControl(args, Capability.VOLUME, "volume") { d -> setVolume(d, pct) }
        },
    )

    val setMutedTool: GhTool = GhTool(
        name = "set_muted",
        description = "Mute or unmute speakers. Scope with name/room/type, e.g. name=\"Bedroom " +
            "speaker\" muted=true, or type=\"speaker\" muted=true for all speakers.",
        inputProperties = selectorProps(
            "muted" to GhProperty(type = "boolean", description = "true = muted, false = unmuted."),
        ),
        requiredInputs = listOf("muted"),
        handler = { args ->
            val muted = requireBool(args, "muted")
            applyControl(args, Capability.VOLUME, "volume/mute") { d -> setMuted(d, muted) }
        },
    )

    val lockTool: GhTool = GhTool(
        name = "lock",
        description = "Lock smart locks. Scope with name/room/type, e.g. name=\"Front Door\", or " +
            "type=\"lock\" for all locks. Locking needs no PIN; unlocking uses the separate unlock tool.",
        inputProperties = selectorProps(),
        handler = { args -> applyControl(args, Capability.LOCK, "lock") { d -> setLocked(d, true, null) } },
    )

    val unlockTool: GhTool = GhTool(
        name = "unlock",
        description = "Unlock smart locks. Requires the lock PIN (unlock is PIN-gated). Scope with " +
            "name/room/type, e.g. name=\"Front Door\" pin=\"1234\". Best-effort: the PIN challenge " +
            "shape needs live confirmation.",
        inputProperties = selectorProps(
            "pin" to GhProperty(type = "string", description = "The lock's unlock PIN (required)."),
        ),
        requiredInputs = listOf("pin"),
        handler = { args ->
            val pin = requireString(args, "pin")
            applyControl(args, Capability.LOCK, "lock") { d -> setLocked(d, false, pin) }
        },
    )

    val setThermostatTool: GhTool = GhTool(
        name = "set_thermostat",
        description = "Set a thermostat's target temperature and/or mode. Provide temperature_c OR " +
            "temperature_f, and/or mode (heat/cool/eco/off). Scope with name/room/type, e.g. " +
            "name=\"Hallway\" temperature_f=70 mode=\"heat\". Best-effort: the Nest setpoint payload " +
            "needs live confirmation.",
        inputProperties = selectorProps(
            "temperature_c" to GhProperty(type = "number", description = "Target setpoint in Celsius."),
            "temperature_f" to GhProperty(type = "number", description = "Target setpoint in Fahrenheit (converted to C)."),
            "mode" to GhProperty(type = "string", description = "Thermostat mode: heat, cool, eco, or off."),
        ),
        handler = { args ->
            val setpointC = thermostatSetpointC(args)
            val mode = optString(args, "mode")
            require(setpointC != null || mode != null) {
                "set_thermostat needs at least one of temperature_c, temperature_f, or mode."
            }
            applyControl(args, Capability.THERMOSTAT, "thermostat") { d -> setThermostat(d, setpointC, mode) }
        },
    )

    val mediaControlTool: GhTool = GhTool(
        name = "media_control",
        description = "Control media playback on speakers/players. command is one of: play, pause, " +
            "stop. Scope with name/room/type, e.g. name=\"Hub\" command=\"pause\". (next/previous are " +
            "not supported by this playback field.)",
        inputProperties = selectorProps(
            "command" to GhProperty(type = "string", description = "Playback command: play, pause, or stop."),
        ),
        requiredInputs = listOf("command"),
        handler = { args ->
            val command = normalizeMediaCommand(requireString(args, "command"))
            applyControl(args, Capability.MEDIA_TRANSPORT, "media transport") { d -> mediaCommand(d, command) }
        },
    )

    // ---------------------------------------------------------------------------------------------
    // Automations / routines
    // ---------------------------------------------------------------------------------------------

    val listAutomationsTool: GhTool = GhTool(
        name = "list_automations",
        description = "List the home's automations/routines: name, whether it can be started on demand " +
            "(manually_runnable), and its starter/action summary. Read-only. Use before run_automation " +
            "to find a name.",
        handler = { _ ->
            buildJsonArray { foyer.listAutomations().forEach { add(it.toJson()) } }
        },
    )

    val runAutomationTool: GhTool = GhTool(
        name = "run_automation",
        description = "Start/run an automation by name, e.g. name=\"Movie Night\". Only " +
            "manually-runnable routines can be started (schedule/condition-only ones cannot). This " +
            "executes the automation's EXISTING actions; it never creates or deletes anything.",
        inputProperties = mapOf(
            "name" to GhProperty(type = "string", description = "The automation's name (case-insensitive; exact preferred)."),
        ),
        requiredInputs = listOf("name"),
        handler = { args ->
            val name = requireString(args, "name").trim()
            val autos = foyer.listAutomations()
            val match = autos.firstOrNull { it.name.trim().equals(name, ignoreCase = true) }
                ?: autos.firstOrNull { it.name.trim().lowercase().contains(name.lowercase()) }
                ?: throw IllegalArgumentException(
                    "No automation named '$name'. Available: " + autos.joinToString(", ") { "\"${it.name}\"" },
                )
            val ok = foyer.runAutomation(match)
            buildJsonObject {
                put("name", match.name)
                put("id", match.id)
                put("ok", ok)
            }
        },
    )

    /** All tools, in a stable order: read tools first, then control tools, then automations. */
    val tools: List<GhTool> = listOf(
        listHomesTool,
        listDevicesTool,
        getDeviceStateTool,
        turnOnTool,
        turnOffTool,
        setBrightnessTool,
        setColorTemperatureTool,
        setVolumeTool,
        setMutedTool,
        lockTool,
        unlockTool,
        setThermostatTool,
        mediaControlTool,
        listAutomationsTool,
        runAutomationTool,
    )

    private val toolsByName: Map<String, GhTool> = tools.associateBy { it.name }

    /** Look up a tool by MCP name, or null. */
    fun tool(name: String): GhTool? = toolsByName[name]

    /** Convenience dispatch used by transports/tests: decode-free call by tool name. */
    suspend fun call(name: String, args: JsonObject): JsonElement {
        val t = toolsByName[name] ?: throw IllegalArgumentException("Unknown tool: $name")
        return t.call(args)
    }

    // ---------------------------------------------------------------------------------------------
    // Control plumbing
    // ---------------------------------------------------------------------------------------------

    /**
     * Resolve the selector from [args], apply [action] to every match that has [requiredCap], and
     * return a JSON array of per-device `{id,name,ok,state|error}`. Matches lacking the capability are
     * included with `ok=false` and a "skipped: unsupported" note; a per-device control failure is
     * captured as `ok=false` + its error message (so one bad device does not fail the batch). If the
     * selector matches nothing, [DeviceResolver.resolve] throws with an actionable message.
     */
    private suspend fun applyControl(
        args: JsonObject,
        requiredCap: Capability,
        capLabel: String,
        action: suspend (Device) -> DeviceState,
    ): JsonArray {
        val devices = resolver.resolve(selectorFrom(args))
        val rows = ArrayList<JsonObject>(devices.size)
        for (d in devices) {
            if (requiredCap !in d.capabilities) {
                rows.add(resultRow(d.id, d.name, ok = false, error = "skipped: does not support $capLabel"))
                continue
            }
            rows.add(
                try {
                    resultRow(d.id, d.name, ok = true, state = action(d))
                } catch (e: Exception) {
                    resultRow(d.id, d.name, ok = false, error = e.message ?: e.toString())
                },
            )
        }
        return JsonArray(rows)
    }

    // Foyer calls with agent/partner ids resolved from the device record.
    private suspend fun setOnOff(d: Device, on: Boolean) = foyer.setOnOff(d.id, d.agent(), d.partner(), on)
    private suspend fun setBrightness(d: Device, pct: Int) = foyer.setBrightness(d.id, d.agent(), d.partner(), pct)
    private suspend fun setColorTemp(d: Device, kelvin: Int) = foyer.setColorTemperature(d.id, d.agent(), d.partner(), kelvin)
    private suspend fun setVolume(d: Device, pct: Int) = foyer.setVolume(d.id, d.agent(), d.partner(), pct)
    private suspend fun setMuted(d: Device, muted: Boolean) = foyer.setMuted(d.id, d.agent(), d.partner(), muted)
    private suspend fun setLocked(d: Device, locked: Boolean, pin: String?) = foyer.setLocked(d.id, d.agent(), d.partner(), locked, pin)
    private suspend fun setThermostat(d: Device, setpointC: Double?, mode: String?) = foyer.setThermostat(d.id, d.agent(), d.partner(), setpointC, mode)
    private suspend fun mediaCommand(d: Device, command: String) = foyer.mediaCommand(d.id, d.agent(), d.partner(), command)

    private fun Device.agent(): String = agentId ?: ""
    private fun Device.partner(): String = partnerDeviceId ?: ""

    // ---------------------------------------------------------------------------------------------
    // Argument decoding
    // ---------------------------------------------------------------------------------------------

    private fun selectorFrom(args: JsonObject) = Selector(
        name = optString(args, "name"),
        room = optString(args, "room"),
        type = optString(args, "type"),
    )

    private fun optString(args: JsonObject, key: String): String? =
        (args[key] as? JsonPrimitive)?.let { if (it is JsonNull) null else it.content }?.takeIf { it.isNotBlank() }

    private fun requireString(args: JsonObject, key: String): String =
        optString(args, key) ?: throw IllegalArgumentException("Missing required string argument `$key`.")

    private fun optInt(args: JsonObject, key: String): Int? {
        val p = args[key] as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        return p.intOrNull ?: p.content.trim().toIntOrNull()
    }

    private fun requireInt(args: JsonObject, key: String): Int =
        optInt(args, key) ?: throw IllegalArgumentException("Missing/invalid integer argument `$key`.")

    private fun requireBool(args: JsonObject, key: String): Boolean {
        val p = args[key] as? JsonPrimitive ?: throw IllegalArgumentException("Missing boolean argument `$key`.")
        return p.content.equals("true", ignoreCase = true) || p.content == "1"
    }

    private fun optDouble(args: JsonObject, key: String): Double? {
        val p = args[key] as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        return p.doubleOrNull ?: p.content.trim().toDoubleOrNull()
    }

    /** Celsius setpoint from `temperature_c`, or `temperature_f` converted, or null if neither given. */
    private fun thermostatSetpointC(args: JsonObject): Double? {
        optDouble(args, "temperature_c")?.let { return it }
        optDouble(args, "temperature_f")?.let { return (it - 32.0) * 5.0 / 9.0 }
        return null
    }

    /** Maps common aliases (play/pause/next/previous/stop) to the transport enum; passes through the rest. */
    private fun normalizeMediaCommand(raw: String): String = when (raw.trim().lowercase()) {
        "play", "resume", "start" -> "RESUME"
        "pause" -> "PAUSE"
        "next", "skip" -> "NEXT"
        "previous", "prev", "back" -> "PREVIOUS"
        "stop" -> "STOP"
        else -> raw.trim().uppercase()
    }

    // ---------------------------------------------------------------------------------------------
    // JSON projection
    // ---------------------------------------------------------------------------------------------

    private fun resultRow(id: String, name: String, ok: Boolean, state: DeviceState? = null, error: String? = null): JsonObject =
        buildJsonObject {
            put("id", id)
            put("name", name)
            put("ok", ok)
            if (state != null) put("state", state.toJson())
            if (error != null) put("error", error)
        }

    private fun Home.toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name)
    }

    private fun Automation.toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name)
        put("manually_runnable", manuallyRunnable)
        put("starters", starters?.let { JsonPrimitive(it) } ?: JsonNull)
        put("actions", actions?.let { JsonPrimitive(it) } ?: JsonNull)
    }

    private fun DeviceState.toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("online", online)
        put("on_off", onOff?.let { JsonPrimitive(it) } ?: JsonNull)
        put("brightness_pct", brightnessPct?.let { JsonPrimitive(it) } ?: JsonNull)
        put("color_temperature_k", colorTemperatureK?.let { JsonPrimitive(it) } ?: JsonNull)
        put("volume_pct", volumePct?.let { JsonPrimitive(it) } ?: JsonNull)
        put("muted", muted?.let { JsonPrimitive(it) } ?: JsonNull)
        put("locked", locked?.let { JsonPrimitive(it) } ?: JsonNull)
        put("jammed", jammed?.let { JsonPrimitive(it) } ?: JsonNull)
        put("thermostat_mode", thermostatMode?.let { JsonPrimitive(it) } ?: JsonNull)
        put("setpoint_c", setpointC?.let { JsonPrimitive(it) } ?: JsonNull)
        put("ambient_c", ambientC?.let { JsonPrimitive(it) } ?: JsonNull)
    }

    private fun Device.toJson(state: DeviceState?): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name)
        // Show the effective type (user-assigned overrides hardware) as `type`, so a switch/outlet
        // marked as a Light reads as a light. `hardware_type` exposes the raw partner type when it differs.
        put("type", effectiveType)
        if (assignedType != null && assignedType != type) put("hardware_type", type)
        put("traits", buildJsonArray { traits.forEach { add(JsonPrimitive(it)) } })
        put("capabilities", buildJsonArray { capabilities.forEach { add(JsonPrimitive(it.name.lowercase())) } })
        put("room_name", roomName?.let { JsonPrimitive(it) } ?: JsonNull)
        put("agent_id", agentId?.let { JsonPrimitive(it) } ?: JsonNull)
        put("partner_device_id", partnerDeviceId?.let { JsonPrimitive(it) } ?: JsonNull)
        put("online", state?.online?.let { JsonPrimitive(it) } ?: JsonNull)
        put("on_off", state?.onOff?.let { JsonPrimitive(it) } ?: JsonNull)
    }

    private companion object {
        /** The three shared selector properties, plus any tool-specific extras (appended, ordered). */
        fun selectorProps(vararg extra: Pair<String, GhProperty>): Map<String, GhProperty> = buildMap {
            put("name", GhProperty(type = "string", description = "Device name to match (case-insensitive substring)."))
            put("room", GhProperty(type = "string", description = "Room name to match (case-insensitive substring), e.g. \"kitchen\"."))
            put("type", GhProperty(type = "string", description = "Device type suffix: light, lock, speaker, thermostat, outlet, switch."))
            extra.forEach { (k, v) -> put(k, v) }
        }
    }
}
