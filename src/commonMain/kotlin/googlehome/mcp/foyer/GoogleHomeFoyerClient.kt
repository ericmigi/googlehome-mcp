package googlehome.mcp.foyer

/** Raised when an unlock (or other trait write) is gated behind a challenge the caller did not
 *  satisfy — currently the lock `pinNeeded` challenge with no PIN supplied. */
class ChallengeRequiredException(
    val deviceId: String,
    val challenge: String,
    message: String = "Device $deviceId requires a '$challenge' challenge response (e.g. a lock PIN).",
) : Exception(message)

/**
 * The foyer-pa client surface the MCP tools code against (see docs/CONTRACTS2.md).
 *
 * Enumeration and reads are unrestricted across all homes/rooms/devices. **Control is now general**:
 * there is no device-id allowlist. The safety invariant is upheld structurally — this interface
 * exposes only *control* of existing devices; it has no create/add or remove/delete method, and no
 * implementation may call a create/delete RPC.
 *
 * Every control method takes the already-resolved `(deviceId, agentId, partnerDeviceId)` triple (the
 * caller resolves these from `GetHomeGraph`); implementations issue exactly one `UpdateTraits` and do
 * **not** re-fetch the home graph per call.
 */
interface GoogleHomeFoyerClient {
    /** Enumerate homes + devices (read-only). */
    suspend fun getHomeGraph(): Pair<List<Home>, List<Device>>

    /** Enumerate rooms (read-only). */
    suspend fun listRooms(): List<Room>

    /** Read live state for the given device ids (read-only). */
    suspend fun getTraits(deviceIds: List<String>): List<DeviceState>

    /** Turn a device on/off (`onOff.onOff`). */
    suspend fun setOnOff(deviceId: String, agentId: String, partnerDeviceId: String, on: Boolean): DeviceState

    /** Set brightness (`brightness.brightness`), clamped to 0..100. */
    suspend fun setBrightness(deviceId: String, agentId: String, partnerDeviceId: String, pct: Int): DeviceState

    /** Set color temperature in Kelvin (`color.colorTemperature`). */
    suspend fun setColorTemperature(deviceId: String, agentId: String, partnerDeviceId: String, kelvin: Int): DeviceState

    /** Set volume (`volume.currentVolume`), clamped to 0..100. */
    suspend fun setVolume(deviceId: String, agentId: String, partnerDeviceId: String, pct: Int): DeviceState

    /** Mute/unmute (`volume.isMuted`). */
    suspend fun setMuted(deviceId: String, agentId: String, partnerDeviceId: String, muted: Boolean): DeviceState

    /**
     * Lock (`locked=true`) or unlock (`locked=false`) a lock. Unlock is PIN-gated: on a `pinNeeded`
     * challenge, if [pin] is non-null the write is re-sent with the PIN (best-effort shape — needs
     * live confirm); if [pin] is null a [ChallengeRequiredException] is thrown.
     */
    suspend fun setLocked(deviceId: String, agentId: String, partnerDeviceId: String, locked: Boolean, pin: String? = null): DeviceState

    /** Set thermostat setpoint (Celsius) and/or mode (`temperatureSetting`). Best-effort — needs live confirm. */
    suspend fun setThermostat(deviceId: String, agentId: String, partnerDeviceId: String, setpointC: Double?, mode: String?): DeviceState

    /** Issue a media transport command (`NEXT`/`PAUSE`/`PREVIOUS`/`RESUME`/`STOP`). Best-effort — needs live confirm. */
    suspend fun mediaCommand(deviceId: String, agentId: String, partnerDeviceId: String, command: String): DeviceState

    /** Enumerate the home's automations/routines (read-only), via `AutomationService/ListAutomations`. */
    suspend fun listAutomations(): List<Automation>

    /**
     * Manually run an [automation]. Only [Automation.manuallyRunnable] ones can be started on demand.
     * (Running an automation executes its existing actions; it never adds or deletes anything.)
     */
    suspend fun runAutomation(automation: Automation): Boolean
}
