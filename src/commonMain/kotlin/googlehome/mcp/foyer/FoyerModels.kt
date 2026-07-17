package googlehome.mcp.foyer

/**
 * Domain models for the Google Home *foyer-pa* API, decoded out of the positional
 * `application/json+protobuf` arrays the service actually speaks (see [FoyerCodec] and
 * docs/PROTOCOL.md). These are the clean, index-free types the rest of the module works with.
 */

/** A home / structure (foyer "structure"). [id] is the structure UUID, [name] its label. */
data class Home(val id: String, val name: String)

/**
 * A room within a home.
 *
 * @param id foyer room id (`<structureId>.<roomUuid>`).
 * @param name display name, e.g. "Outside".
 * @param type room type token, e.g. `KITCHEN`, `BEDROOM`, `OTHER` (empty string if absent).
 * @param deviceIds device UUIDs assigned to this room.
 */
data class Room(val id: String, val name: String, val type: String, val deviceIds: List<String>)

/**
 * A Google Home automation / routine (foyer `AutomationService`).
 *
 * @param id automation id (`structure_<s>.<uuid>`, `routine_00000N`, or a bare uuid).
 * @param name display name, e.g. "Movie Night".
 * @param manuallyRunnable true when the automation can be started on demand (foyer flag `1` —
 *   "household routines"); false for purely condition/schedule-triggered ones (flag `3`).
 * @param starters human-readable starter summary, e.g. "1 starter".
 * @param actions human-readable action summary, e.g. "6 actions".
 * @param triggerInput the base64 `WorkflowTriggerInput` blob foyer returns for this automation,
 *   used to run it (see the run path).
 */
data class Automation(
    val id: String,
    val name: String,
    val manuallyRunnable: Boolean,
    val starters: String?,
    val actions: String?,
    val triggerInput: String?,
)

/**
 * A control capability a [Device] supports, derived from its Google `action.devices.traits.*` list
 * (see [FoyerCodec.deriveCapabilities]). This is the abstraction the tool layer resolves selectors
 * against ("all lights" → devices with [ON_OFF], "unlock the door" → [LOCK]).
 */
enum class Capability {
    ON_OFF,
    BRIGHTNESS,
    COLOR_TEMPERATURE,
    COLOR,
    VOLUME,
    LOCK,
    THERMOSTAT,
    MEDIA_TRANSPORT,
}

/**
 * A controllable/observable device as enumerated by `GetHomeGraph`.
 *
 * @param id device UUID (the id used for `GetTraits` / `UpdateTraits`).
 * @param name display name.
 * @param type the partner/hardware-reported Google device type, e.g. `action.devices.types.SWITCH`.
 * @param assignedType the user-assigned device type set in the Home app (e.g. a smart switch marked
 *   as a "Light"), or null. When present it overrides [type] for intent purposes — this is how
 *   Google Home treats a switch/outlet as a light. Prefer [effectiveType] for matching.
 * @param traits supported traits, e.g. `action.devices.traits.OnOff`.
 * @param roomName the room this device belongs to, or null if unassigned.
 * @param agentId partner/agent cloud id (e.g. `acme-partner`); needed to issue `UpdateTraits`.
 * @param partnerDeviceId the partner's own device id (e.g. `AA11BB22CC33DD44EE55`).
 * @param capabilities the [Capability]s derived from [traits]. Defaults to empty for hand-built
 *   instances; [FoyerCodec.parseGetHomeGraph] always populates it.
 */
data class Device(
    val id: String,
    val name: String,
    val type: String,
    val assignedType: String? = null,
    val traits: List<String>,
    val roomName: String?,
    val agentId: String?,
    val partnerDeviceId: String?,
    val capabilities: Set<Capability> = emptySet(),
) {
    /**
     * The type used for matching: the user-assigned type when set, else the hardware type. So a
     * switch/outlet the user marked as a Light matches `type="light"` — exactly how Google Home's
     * "turn off all the lights" behaves.
     */
    val effectiveType: String get() = assignedType?.takeIf { it.isNotBlank() } ?: type
}

/**
 * Live state read back from `GetTraits` (and echoed by `UpdateTraits`).
 *
 * Every trait-backed field is nullable: it is `null` when the device does not expose that trait, or
 * when the wire payload had a `null` hole where the value should be. Only [id] and [online] are
 * always present.
 *
 * @param online whether the device reports itself online (`deviceStatus.online`).
 * @param onOff current on/off (`onOff.onOff`), or null when the device exposes no readable value.
 * @param brightnessPct `brightness.brightness`, 0..100.
 * @param colorTemperatureK `color.colorTemperature` in Kelvin.
 * @param volumePct `volume.currentVolume`, 0..100.
 * @param muted `volume.isMuted`.
 * @param locked `lockUnlock.isLocked`.
 * @param jammed `lockUnlock.isJammed`.
 * @param thermostatMode `temperatureSetting.mode` (`heat`/`eco`/`off`/`cool`).
 * @param setpointC `temperatureSetting.thermostatTemperatureSetpointC` in Celsius.
 * @param ambientC `temperatureSetting.ambientAirTemperatureC` in Celsius.
 */
data class DeviceState(
    val id: String,
    val online: Boolean,
    val onOff: Boolean? = null,
    val brightnessPct: Int? = null,
    val colorTemperatureK: Int? = null,
    val volumePct: Int? = null,
    val muted: Boolean? = null,
    val locked: Boolean? = null,
    val jammed: Boolean? = null,
    val thermostatMode: String? = null,
    val setpointC: Double? = null,
    val setpointF: Double? = null,
    val ambientC: Double? = null,
    val temperatureUnit: String? = null,   // "F" or "C" — the thermostat's display unit
)

/**
 * Everything parsed out of a single `GetHomeGraph` response: homes, their devices, and their rooms.
 * [GoogleHomeFoyerClient.getHomeGraph] projects this to `(homes, devices)` and
 * [GoogleHomeFoyerClient.listRooms] to `rooms`.
 */
data class HomeGraph(
    val homes: List<Home>,
    val devices: List<Device>,
    val rooms: List<Room>,
)
