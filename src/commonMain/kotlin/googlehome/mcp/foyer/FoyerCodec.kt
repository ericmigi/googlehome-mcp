package googlehome.mcp.foyer

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Builds and parses the foyer-pa `application/json+protobuf` wire format.
 *
 * The service does **not** use JSON objects — every request/response body is a *positional* JSON
 * array where protobuf field numbers map to array indices (1-based, with `null` holes for absent
 * fields). This object hand-assembles those arrays with [JsonArray]/[JsonElement] and reads them
 * back defensively (any missing/`null`/short slot degrades to a sensible default rather than
 * throwing), because the real payloads are riddled with holes. Field indices below were derived by
 * dissecting the captured samples under `captures/` and the live-verified reference in
 * docs/TRAITS.md; see the comments on each method.
 *
 * ## Value encoding (the scalar wrapper `[i0,i1,i2,i3,…]`)
 * A written/read value sits at a **type-specific** index (the protobuf oneof slot):
 * - **int / float** → index 1: `[null, <num>]`
 * - **string / enum** → index 2: `[null, null, "<str>"]`
 * - **bool** → index 3: `[null, null, null, <0|1>]`
 */
object FoyerCodec {

    // ---------------------------------------------------------------------------------------------
    // Small positional-array accessors. All null-safe: an out-of-range or wrong-typed slot is null.
    // ---------------------------------------------------------------------------------------------

    /** This element as a [JsonArray], or null if it is not one (covers JsonNull and primitives). */
    private fun JsonElement?.arr(): JsonArray? = this as? JsonArray

    /** The [i]-th child as a [JsonArray], or null. */
    private fun JsonArray?.arrAt(i: Int): JsonArray? = this?.getOrNull(i) as? JsonArray

    /** The [i]-th child, or null (never throws on out-of-range). */
    private fun JsonArray?.at(i: Int): JsonElement? = this?.getOrNull(i)

    /** This element's string content, or null when it is JsonNull / not a string primitive. */
    private fun JsonElement?.str(): String? {
        val p = this as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        return if (p.isString) p.content else null
    }

    /**
     * Reads a protobuf-json scalar-wrapper value at array index [i] as a boolean.
     * `1`/`true` → true, everything else (incl. `0`, null, absent) → false.
     */
    private fun JsonArray?.boolAt(i: Int): Boolean {
        val e = this?.getOrNull(i) as? JsonPrimitive ?: return false
        e.booleanOrNull?.let { return it }
        return e.intOrNull == 1
    }

    /** Reads the scalar-wrapper int at index [i], or null on a hole / non-numeric slot. */
    private fun JsonArray?.intAt(i: Int): Int? {
        val e = this?.getOrNull(i) as? JsonPrimitive ?: return null
        if (e is JsonNull) return null
        return e.intOrNull
    }

    /** Reads the scalar-wrapper double at index [i], or null on a hole / non-numeric slot. */
    private fun JsonArray?.doubleAt(i: Int): Double? {
        val e = this?.getOrNull(i) as? JsonPrimitive ?: return null
        if (e is JsonNull) return null
        return e.doubleOrNull
    }

    /** Reads the scalar-wrapper string at index [i], or null on a hole / non-string slot. */
    private fun JsonArray?.strAt(i: Int): String? = this.at(i).str()

    // ---------------------------------------------------------------------------------------------
    // Scalar wrappers (per docs/TRAITS.md "value encoding")
    // ---------------------------------------------------------------------------------------------

    /** `[null, <n>]` — int/float value at index 1. */
    private fun intWrapper(n: Int): JsonArray = JsonArray(listOf(JsonNull, JsonPrimitive(n)))

    /** `[null, <n>]` — int/float value at index 1. */
    private fun floatWrapper(n: Double): JsonArray = JsonArray(listOf(JsonNull, JsonPrimitive(n)))

    /** Like [floatWrapper] but serializes a whole number as an int (matches captured `[null,66]`). */
    private fun numberWrapper(n: Double): JsonArray =
        JsonArray(listOf(JsonNull, if (n % 1.0 == 0.0) JsonPrimitive(n.toInt()) else JsonPrimitive(n)))

    /** `[null, null, "<s>"]` — string/enum value at index 2. */
    private fun stringWrapper(s: String): JsonArray =
        JsonArray(listOf(JsonNull, JsonNull, JsonPrimitive(s)))

    /** `[null, null, null, <0|1>]` — bool value at index 3. */
    private fun boolWrapper(b: Boolean): JsonArray =
        JsonArray(listOf(JsonNull, JsonNull, JsonNull, JsonPrimitive(if (b) 1 else 0)))

    // ---------------------------------------------------------------------------------------------
    // GetTraits
    // ---------------------------------------------------------------------------------------------

    /**
     * Request body for `HomeControlService/GetTraits`: `[[["id1"],["id2"],…]]`.
     * Each id is wrapped in its own single-element array (the "device key" message).
     */
    fun buildGetTraitsRequest(ids: List<String>): JsonArray =
        JsonArray(
            listOf(
                JsonArray(ids.map { JsonArray(listOf(JsonPrimitive(it))) }),
            ),
        )

    /**
     * Flattens a trait *body* (`[[fieldName, <wrapper>], …]`) into a `fieldName -> wrapper` map. The
     * wrapper is the scalar `[i0,i1,i2,i3,…]` array; absent fields simply do not appear in the map,
     * which lets callers distinguish "field absent" (null) from "field present but false/zero".
     */
    private fun JsonArray?.fieldWrappers(): Map<String, JsonArray?> {
        val body = this ?: return emptyMap()
        val out = LinkedHashMap<String, JsonArray?>()
        for (fieldEl in body) {
            val field = fieldEl.arr() ?: continue
            val name = field.at(0).str() ?: continue
            out[name] = field.arrAt(1)
        }
        return out
    }

    /**
     * Parses a `GetTraits` (or `UpdateTraits` echo) response into rich [DeviceState]s.
     *
     * Wire shape (per device): `result[0][0]` = device id; `result[1]` = list of trait entries, each
     * `[traitName, traitBody, …]` where `traitBody = [[fieldName, <wrapper>], …]`. Traits parsed
     * (all optional, all defensive about `null` holes):
     * - `deviceStatus.online` (bool@3) → [DeviceState.online]
     * - `onOff.onOff` (bool@3) → onOff
     * - `brightness.brightness` (int@1) → brightnessPct
     * - `color.colorTemperature` (int@1) → colorTemperatureK
     * - `volume.currentVolume` (int@1) / `volume.isMuted` (bool@3) → volumePct / muted
     * - `lockUnlock.isLocked` / `isJammed` (bool@3) → locked / jammed
     * - `temperatureSetting.mode` (str@2), `thermostatTemperatureSetpointC` (float@1),
     *   `ambientAirTemperatureC` (float@1) → thermostatMode / setpointC / ambientC
     */
    fun parseGetTraits(json: JsonElement): List<DeviceState> {
        val results = json.arr().arrAt(0) ?: return emptyList()
        val out = ArrayList<DeviceState>(results.size)
        for (result in results) {
            val r = result.arr() ?: continue
            val id = r.arrAt(0).at(0).str() ?: continue
            val traits = r.arrAt(1)

            var online = false
            var onOff: Boolean? = null
            var brightnessPct: Int? = null
            var colorTemperatureK: Int? = null
            var volumePct: Int? = null
            var muted: Boolean? = null
            var locked: Boolean? = null
            var jammed: Boolean? = null
            var thermostatMode: String? = null
            var setpointC: Double? = null
            var setpointF: Double? = null
            var ambientC: Double? = null
            var temperatureUnit: String? = null

            if (traits != null) {
                for (entry in traits) {
                    val e = entry.arr() ?: continue
                    val fields = e.arrAt(1).fieldWrappers()
                    when (e.at(0).str()) {
                        "deviceStatus" -> if ("online" in fields) online = fields["online"].boolAt(3)
                        "onOff" -> if ("onOff" in fields) onOff = fields["onOff"].boolAt(3)
                        "brightness" -> brightnessPct = fields["brightness"].intAt(1)
                        "color" -> colorTemperatureK = fields["colorTemperature"].intAt(1)
                        "volume" -> {
                            volumePct = fields["currentVolume"].intAt(1)
                            if ("isMuted" in fields) muted = fields["isMuted"].boolAt(3)
                        }
                        "lockUnlock" -> {
                            if ("isLocked" in fields) locked = fields["isLocked"].boolAt(3)
                            if ("isJammed" in fields) jammed = fields["isJammed"].boolAt(3)
                        }
                        "temperatureSetting" -> {
                            thermostatMode = fields["mode"].strAt(2)
                            setpointC = fields["thermostatTemperatureSetpointC"].doubleAt(1)
                            setpointF = fields["thermostatTemperatureSetpointF"].doubleAt(1)
                            ambientC = fields["ambientAirTemperatureC"].doubleAt(1)
                            // temperatureUnit lives in the trait's params block (index 2), nested one
                            // array deeper than the state fields. Best-effort: pull it if present.
                            temperatureUnit = e.arrAt(2)?.arrAt(0)?.fieldWrappers()?.get("temperatureUnit").strAt(2)
                        }
                    }
                }
            }

            out.add(
                DeviceState(
                    id = id,
                    online = online,
                    onOff = onOff,
                    brightnessPct = brightnessPct,
                    colorTemperatureK = colorTemperatureK,
                    volumePct = volumePct,
                    muted = muted,
                    locked = locked,
                    jammed = jammed,
                    thermostatMode = thermostatMode,
                    setpointC = setpointC,
                    setpointF = setpointF,
                    ambientC = ambientC,
                    temperatureUnit = temperatureUnit,
                ),
            )
        }
        return out
    }

    /**
     * Scans a `GetTraits`/`UpdateTraits` response for a `deviceStatus.challenge` token (e.g. an
     * unlock returning `["challenge",[null,null,"pinNeeded"]]`), returning the challenge string (e.g.
     * `"pinNeeded"`) if present, else null. Used by the client to detect a PIN-gated unlock.
     */
    fun parseChallenge(json: JsonElement): String? {
        val results = json.arr().arrAt(0) ?: return null
        for (result in results) {
            val traits = result.arr().arrAt(1) ?: continue
            for (entry in traits) {
                val e = entry.arr() ?: continue
                if (e.at(0).str() == "deviceStatus") {
                    val challenge = e.arrAt(1).fieldWrappers()["challenge"]
                    challenge.strAt(2)?.let { return it }
                }
            }
        }
        return null
    }

    // ---------------------------------------------------------------------------------------------
    // UpdateTraits (control) — generic builder + typed helpers
    // ---------------------------------------------------------------------------------------------

    /**
     * Generic `HomeControlService/UpdateTraits` body builder. Reproduces the live-verified shape:
     * ```
     * [[[ [deviceId,[agentId,partnerDeviceId]], [[ traitName, [[ fieldName, <wrapper> ]] ]] ]]]
     * ```
     * where `<wrapper>` is the type-specific scalar array (see class docs / docs/TRAITS.md). The
     * typed `build*` helpers below all funnel through here.
     */
    fun buildUpdate(
        deviceId: String,
        agentId: String,
        partnerDeviceId: String,
        traitName: String,
        fieldName: String,
        wrapper: JsonArray,
    ): JsonArray = buildUpdateFields(
        deviceId, agentId, partnerDeviceId, traitName,
        listOf(fieldName to wrapper),
    )

    /**
     * Multi-field variant of [buildUpdate]: writes several fields of the same trait in one command
     * (`[[ traitName, [[f1,w1],[f2,w2],…] ]]`). Used for the thermostat, whose setpoint write must be
     * mode-aware (see [buildThermostatSetpoint]).
     */
    fun buildUpdateFields(
        deviceId: String,
        agentId: String,
        partnerDeviceId: String,
        traitName: String,
        fields: List<Pair<String, JsonArray>>,
    ): JsonArray {
        val fieldArr = JsonArray(
            fields.map { (name, wrapper) -> JsonArray(listOf(JsonPrimitive(name), wrapper)) },
        )
        val trait = JsonArray(listOf(JsonPrimitive(traitName), fieldArr))
        val deviceKey = JsonArray(
            listOf(
                JsonPrimitive(deviceId),
                JsonArray(listOf(JsonPrimitive(agentId), JsonPrimitive(partnerDeviceId))),
            ),
        )
        val command = JsonArray(listOf(deviceKey, JsonArray(listOf(trait))))
        return JsonArray(listOf(JsonArray(listOf(command))))
    }

    /** `onOff.onOff = [null,null,null,0|1]` — live-verified. */
    fun buildOnOff(deviceId: String, agentId: String, partnerDeviceId: String, on: Boolean): JsonArray =
        buildUpdate(deviceId, agentId, partnerDeviceId, "onOff", "onOff", boolWrapper(on))

    /** `brightness.brightness = [null,0..100]` — live-verified. */
    fun buildBrightness(deviceId: String, agentId: String, partnerDeviceId: String, pct: Int): JsonArray =
        buildUpdate(deviceId, agentId, partnerDeviceId, "brightness", "brightness", intWrapper(pct))

    /** `color.colorTemperature = [null,<kelvin>]` — live-verified. */
    fun buildColorTemperature(deviceId: String, agentId: String, partnerDeviceId: String, kelvin: Int): JsonArray =
        buildUpdate(deviceId, agentId, partnerDeviceId, "color", "colorTemperature", intWrapper(kelvin))

    /** `volume.currentVolume = [null,0..100]` — live-verified. */
    fun buildVolume(deviceId: String, agentId: String, partnerDeviceId: String, pct: Int): JsonArray =
        buildUpdate(deviceId, agentId, partnerDeviceId, "volume", "currentVolume", intWrapper(pct))

    /** `volume.isMuted = [null,null,null,0|1]` — structure-inferred (docs/TRAITS.md). */
    fun buildMuted(deviceId: String, agentId: String, partnerDeviceId: String, muted: Boolean): JsonArray =
        buildUpdate(deviceId, agentId, partnerDeviceId, "volume", "isMuted", boolWrapper(muted))

    /** `lockUnlock.isLocked = [null,null,null,0|1]` — lock verified; unlock is PIN-gated (see client). */
    fun buildLock(deviceId: String, agentId: String, partnerDeviceId: String, locked: Boolean): JsonArray =
        buildUpdate(deviceId, agentId, partnerDeviceId, "lockUnlock", "isLocked", boolWrapper(locked))

    /**
     * A PIN-gated unlock. After the bare [buildLock] with `locked=false` returns a `pinNeeded`
     * challenge, the command is re-sent with the PIN. **Live-verified 2026-07-16**: the PIN rides as a
     * `pin` string field on the same `lockUnlock` trait, i.e.
     * `["lockUnlock",[["isLocked",[null,null,null,0]],["pin",[null,null,"<pin>"]]]]`.
     */
    fun buildLockWithPin(
        deviceId: String,
        agentId: String,
        partnerDeviceId: String,
        locked: Boolean,
        pin: String,
    ): JsonArray = buildUpdateFields(
        deviceId, agentId, partnerDeviceId, "lockUnlock",
        listOf(
            "isLocked" to boolWrapper(locked),
            "pin" to stringWrapper(pin),
        ),
    )

    /**
     * Thermostat setpoint/mode write. **Live-verified 2026-07-16**: the field is
     * `thermostatTemperatureSetpoint` carrying the value in the device's **display unit** (e.g. °F),
     * and the `mode` must be sent alongside — a bare setpoint (or the `…SetpointC` field) is rejected
     * with `FAILED_PRECONDITION`. [setpointDisplayUnit] is therefore already in the device's unit
     * (the client converts using the parsed `temperatureUnit`), and whole values serialize as ints to
     * match the captured payload (`…,[null,66]`).
     */
    fun buildThermostatSetpoint(
        deviceId: String,
        agentId: String,
        partnerDeviceId: String,
        setpointDisplayUnit: Double?,
        mode: String?,
    ): JsonArray {
        val fields = ArrayList<Pair<String, JsonArray>>()
        if (mode != null) fields += "mode" to stringWrapper(mode)
        if (setpointDisplayUnit != null) {
            fields += "thermostatTemperatureSetpoint" to numberWrapper(setpointDisplayUnit)
        }
        return buildUpdateFields(deviceId, agentId, partnerDeviceId, "temperatureSetting", fields)
    }

    /** The playback-state values [buildMediaCommand] accepts (play/pause verified; stop inferred). */
    val MEDIA_COMMANDS: Set<String> get() = setOf("play", "pause", "stop")

    /**
     * Media play/pause/stop. **Live-verified 2026-07-16**: the web app drives playback via
     * `mediaState.playbackState` (a string), NOT the `transportControl` trait — e.g. pause is
     * `["mediaState",[["playbackState",[null,null,"paused"]]]]`, play is `"playing"`. `stop` maps to
     * `"stopped"` (inferred). `next`/`previous` are not exposed by this field and are rejected.
     *
     * @throws IllegalArgumentException for an unsupported [command].
     */
    fun buildMediaCommand(
        deviceId: String,
        agentId: String,
        partnerDeviceId: String,
        command: String,
    ): JsonArray {
        val playbackState = when (command.trim().lowercase()) {
            "play", "resume", "playing" -> "playing"
            "pause" -> "paused"
            "stop" -> "stopped"
            else -> throw IllegalArgumentException(
                "Unsupported media command '$command'. Supported: play, pause, stop.",
            )
        }
        return buildUpdate(deviceId, agentId, partnerDeviceId, "mediaState", "playbackState", stringWrapper(playbackState))
    }

    // ---------------------------------------------------------------------------------------------
    // Back-compat alias
    // ---------------------------------------------------------------------------------------------

    /** @deprecated use [buildOnOff]; kept so existing call sites/tests keep compiling. */
    fun buildUpdateOnOff(deviceId: String, agentId: String, partnerDeviceId: String, on: Boolean): JsonArray =
        buildOnOff(deviceId, agentId, partnerDeviceId, on)

    // ---------------------------------------------------------------------------------------------
    // Capability derivation
    // ---------------------------------------------------------------------------------------------

    /**
     * Maps a device's `action.devices.traits.*` list to the [Capability] set the tool layer resolves
     * against. Matching is on the trait suffix (case-insensitive), so a fully-qualified or bare trait
     * name both resolve. `ColorSetting` yields both [Capability.COLOR_TEMPERATURE] and
     * [Capability.COLOR] (only colorTemperature is verified writable; full spectrum is best-effort).
     */
    // ---------------------------------------------------------------------------------------------
    // AutomationService (routines)
    // ---------------------------------------------------------------------------------------------

    /** `ListAutomations` request body: `[<structureId>]`. */
    fun buildListAutomationsRequest(structureId: String): JsonArray =
        JsonArray(listOf(JsonPrimitive(structureId)))

    /**
     * `ExecuteAutomation` request body: `[<structureId>, <automationId>, null, 2]` (live-verified
     * 2026-07-16 — response is `[1]` on success). The trailing `2` is the execution-source enum the
     * web app sends.
     */
    fun buildExecuteAutomationRequest(structureId: String, automationId: String): JsonArray =
        JsonArray(listOf(JsonPrimitive(structureId), JsonPrimitive(automationId), JsonNull, JsonPrimitive(2)))

    /**
     * Parses a `ListAutomations` response into [Automation]s. Layout (per record): `[0]`=id, `[2]`=flag
     * (`1`=manually-runnable household routine, `3`=condition/schedule-only), `[3]`=name, `[4]`=starter
     * summary, `[5]`=action summary, `[10]`=base64 `WorkflowTriggerInput` blob used to run it. The
     * automation list is at the top-level `[0]`.
     */
    fun parseListAutomations(json: JsonElement): List<Automation> {
        val list = json.arr().arrAt(0) ?: return emptyList()
        val out = ArrayList<Automation>()
        for (recEl in list) {
            val rec = recEl.arr()
            val id = rec.strAt(0) ?: continue
            val name = rec.strAt(3) ?: continue
            out.add(
                Automation(
                    id = id,
                    name = name,
                    manuallyRunnable = rec.intAt(2) == 1,
                    starters = rec.strAt(4),
                    actions = rec.strAt(5),
                    triggerInput = rec.strAt(10),
                ),
            )
        }
        return out
    }

    fun deriveCapabilities(traits: List<String>): Set<Capability> {
        val caps = LinkedHashSet<Capability>()
        for (raw in traits) {
            when (raw.substringAfterLast('.').lowercase()) {
                "onoff" -> caps += Capability.ON_OFF
                "brightness" -> caps += Capability.BRIGHTNESS
                "colorsetting" -> {
                    caps += Capability.COLOR_TEMPERATURE
                    caps += Capability.COLOR
                }
                "volume" -> caps += Capability.VOLUME
                "lockunlock" -> caps += Capability.LOCK
                "temperaturesetting", "temperaturecontrol" -> caps += Capability.THERMOSTAT
                // Play/pause/stop is driven via mediaState.playbackState (verified); transportControl
                // devices also count. Either trait grants the media capability.
                "transportcontrol", "mediastate" -> caps += Capability.MEDIA_TRANSPORT
            }
        }
        return caps
    }

    // ---------------------------------------------------------------------------------------------
    // GetHomeGraph (enumeration)
    // ---------------------------------------------------------------------------------------------

    /**
     * Parses a `GetHomeGraph` response into homes, devices and rooms.
     *
     * Top-level is a 14-slot array; `root[1]` is the list of homes. Each home record:
     * - `[0]` structure id, `[1]` name
     * - `[5]` rooms:   each `[roomId, null, roomName, [ROOM_TYPE], members]`
     *                   where members = `[ [[deviceId,[agentId,partnerId]]], … ]`
     * - `[6]` devices: each `[[deviceId,[agentId,partnerId]], null, null, "Name", null,
     *                         "action.devices.types.X", ["trait", …], … ]`
     *
     * Room membership (from `[5]`) is folded into a deviceId→roomName map and stamped onto devices;
     * each device's [Capability] set is derived from its traits via [deriveCapabilities].
     */
    fun parseGetHomeGraph(json: JsonElement): HomeGraph {
        val homesArr = json.arr().arrAt(1) ?: return HomeGraph(emptyList(), emptyList(), emptyList())

        val homes = ArrayList<Home>()
        val rooms = ArrayList<Room>()
        val devices = ArrayList<Device>()
        val deviceRoom = HashMap<String, String>()

        for (homeEl in homesArr) {
            val home = homeEl.arr() ?: continue
            val homeId = home.at(0).str() ?: continue
            val homeName = home.at(1).str() ?: ""
            homes.add(Home(id = homeId, name = homeName))

            // Rooms: [5]
            val roomsArr = home.arrAt(5)
            if (roomsArr != null) {
                for (roomEl in roomsArr) {
                    val room = roomEl.arr() ?: continue
                    val roomId = room.at(0).str() ?: continue
                    val roomName = room.at(2).str() ?: ""
                    val roomType = room.arrAt(3).at(0).str() ?: ""
                    val memberIds = ArrayList<String>()
                    val members = room.arrAt(4)
                    if (members != null) {
                        for (memberEl in members) {
                            // member = [[deviceId,[agentId,partnerId]]]
                            val devId = memberEl.arr().arrAt(0).at(0).str() ?: continue
                            memberIds.add(devId)
                            deviceRoom[devId] = roomName
                        }
                    }
                    rooms.add(Room(id = roomId, name = roomName, type = roomType, deviceIds = memberIds))
                }
            }

            // Devices: [6]
            val devsArr = home.arrAt(6)
            if (devsArr != null) {
                for (devEl in devsArr) {
                    val rec = devEl.arr() ?: continue
                    val key = rec.arrAt(0) ?: continue      // [deviceId,[agentId,partnerId]]
                    val devId = key.at(0).str() ?: continue
                    val agentPair = key.arrAt(1)
                    val agentId = agentPair.at(0).str()
                    val partnerDeviceId = agentPair.at(1).str()
                    val name = rec.at(3).str() ?: ""
                    val type = rec.at(5).str() ?: ""
                    // Field [20] is the USER-ASSIGNED device type (the category set in the Home app),
                    // which overrides the partner/hardware type [5] for intent purposes. This is how
                    // Google Home knows a switch/outlet is actually a "light" (e.g. "Kitchen light" is
                    // hardware SWITCH but assigned LIGHT) — "turn off all the lights" uses the effective
                    // type. See Device.effectiveType.
                    val assignedType = rec.arrAt(20)?.at(0).str()
                    val traits = rec.arrAt(6)?.mapNotNull { it.str() } ?: emptyList()
                    devices.add(
                        Device(
                            id = devId,
                            name = name,
                            type = type,
                            assignedType = assignedType,
                            traits = traits,
                            roomName = deviceRoom[devId],
                            agentId = agentId,
                            partnerDeviceId = partnerDeviceId,
                            capabilities = deriveCapabilities(traits),
                        ),
                    )
                }
            }
        }

        // A device's room membership can appear after its record within the same home; stamp again.
        val stamped = devices.map { d -> if (d.roomName == null) d.copy(roomName = deviceRoom[d.id]) else d }
        return HomeGraph(homes = homes, devices = stamped, rooms = rooms)
    }
}
