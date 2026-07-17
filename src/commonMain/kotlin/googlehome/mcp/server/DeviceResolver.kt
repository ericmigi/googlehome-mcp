package googlehome.mcp.server

import googlehome.mcp.foyer.Device
import googlehome.mcp.foyer.GoogleHomeFoyerClient

/**
 * A natural-language device selector. Any combination of [name], [room] and [type] may be set; the
 * set fields are AND-combined by [DeviceResolver.resolve] ("kitchen lights" = `room=kitchen` +
 * `type=light`, "all lights" = `type=light`). At least one field must be non-blank.
 *
 * @param name matches a device's display name (case-insensitive, trimmed): an EXACT name match is
 *   preferred, falling back to substring only when no device matches exactly.
 * @param room matches a device's room name EXACTLY (case-insensitive, trimmed) — never a substring,
 *   so `room="bedroom"` does not also select "Master Bedroom".
 * @param type matches the device's Google `action.devices.types.*` **suffix** (e.g. `light`, `lock`,
 *   `speaker`, `thermostat`, `outlet`, `switch`), case-insensitive.
 */
data class Selector(
    val name: String? = null,
    val room: String? = null,
    val type: String? = null,
) {
    /** True when every field is null/blank (nothing to match on). */
    fun isEmpty(): Boolean = name.isNullOrBlank() && room.isNullOrBlank() && type.isNullOrBlank()

    /** Human-readable echo of the set fields, e.g. `name="lamp", type="light"`. */
    fun describe(): String =
        listOfNotNull(
            name?.takeIf { it.isNotBlank() }?.let { "name=\"$it\"" },
            room?.takeIf { it.isNotBlank() }?.let { "room=\"$it\"" },
            type?.takeIf { it.isNotBlank() }?.let { "type=\"$it\"" },
        ).joinToString(", ").ifEmpty { "(no filters)" }
}

/**
 * Thrown when a [Selector] matched no devices (or was empty). Carries an actionable [message] that
 * echoes the selector and lists nearby known device names so the caller can correct it.
 */
class NoDeviceMatchException(message: String) : Exception(message)

/**
 * Resolves [Selector]s against the live `GetHomeGraph` enumeration. Read-only: it only *reads* the
 * home graph and filters it; it never mutates, adds, or removes anything.
 *
 * Matching is case-insensitive and trimmed. [Selector.room] matches EXACTLY; [Selector.name] prefers
 * an exact match and only falls back to substring when nothing matches exactly; [Selector.type]
 * matches the device type suffix exactly (so `light` == `action.devices.types.LIGHT`). All set fields
 * are AND-combined.
 */
class DeviceResolver(private val foyer: GoogleHomeFoyerClient) {

    /**
     * Resolve [sel] to every matching device. Throws [IllegalArgumentException] when the selector is
     * empty (no field set) and [NoDeviceMatchException] when it matched nothing — the latter's message
     * lists near-matching known device names to guide correction.
     */
    suspend fun resolve(sel: Selector): List<Device> {
        require(!sel.isEmpty()) {
            "Provide at least one selector: name, room, and/or type (e.g. type=\"light\" for all lights, " +
                "or room=\"kitchen\" + type=\"light\" for the kitchen lights)."
        }
        val (_, devices) = foyer.getHomeGraph()

        val wantName = sel.name?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val wantRoom = sel.room?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val wantType = sel.type?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

        // Room + type are hard constraints. Room matches EXACTLY (a room is a discrete space; a
        // substring match would let room="bedroom" also hit "Master Bedroom"/"Guest Bedroom" and
        // control the wrong devices). Type matches the type-suffix exactly.
        val scoped = devices.filter { d ->
            (wantRoom == null || d.roomName?.trim()?.lowercase() == wantRoom) &&
                // Match the EFFECTIVE type (user-assigned overrides hardware), so a switch/outlet the
                // user marked as a Light is found by type="light" — as Google Home does.
                (wantType == null || typeMatches(d.effectiveType, wantType))
        }

        // Name: prefer EXACT name matches; only fall back to substring when there is no exact hit.
        // This keeps fuzzy convenience ("corner lamp" -> "Corner Lamp") without letting a short/
        // generic name (e.g. "light") silently fan out to every device that merely contains it.
        val matches = when (wantName) {
            null -> scoped
            else -> scoped.filter { it.name.trim().lowercase() == wantName }
                .ifEmpty { scoped.filter { it.name.trim().lowercase().contains(wantName) } }
        }

        if (matches.isEmpty()) {
            throw NoDeviceMatchException(noMatchMessage(sel, devices))
        }
        return matches
    }

    /** True if [deviceType]'s `action.devices.types.*` suffix (or the whole token) equals [want]. */
    private fun typeMatches(deviceType: String, want: String): Boolean {
        val suffix = deviceType.substringAfterLast('.').lowercase()
        return suffix == want || deviceType.lowercase() == want
    }

    /** Builds the actionable no-match error: echoes the selector and lists nearby device names. */
    private fun noMatchMessage(sel: Selector, devices: List<Device>): String {
        val near = nearNames(sel, devices)
        val known = if (near.isEmpty()) {
            "No devices are known." // e.g. empty home graph
        } else {
            "Closest known devices: " + near.joinToString(", ") { "\"$it\"" } + "."
        }
        return "No devices matched selector ${sel.describe()}. $known " +
            "Use list_devices to see every device with its room, type and capabilities."
    }

    /**
     * Best-effort "did you mean" list. If a room/type was given, prefer names in that room / of that
     * type; otherwise names sharing a token with the requested name. Falls back to all names. Capped.
     */
    private fun nearNames(sel: Selector, devices: List<Device>, limit: Int = 12): List<String> {
        val wantRoom = sel.room?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val wantType = sel.type?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val wantNameTokens = sel.name?.trim()?.lowercase()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }.orEmpty()

        // Relax the AND: keep devices that satisfy at least one of the given fields.
        val relaxed = devices.filter { d ->
            (wantRoom != null && d.roomName?.trim()?.lowercase()?.contains(wantRoom) == true) ||
                (wantType != null && typeMatches(d.effectiveType, wantType)) ||
                (wantNameTokens.isNotEmpty() && wantNameTokens.any { t -> d.name.lowercase().contains(t) })
        }
        val pool = relaxed.ifEmpty { devices }
        return pool.map { it.name }.distinct().take(limit)
    }
}
