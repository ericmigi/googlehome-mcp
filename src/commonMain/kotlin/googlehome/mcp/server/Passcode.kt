package googlehome.mcp.server

/**
 * Persistence for the smart-lock **unlock passcode** (the PIN Google Home requires to unlock a door).
 *
 * It is a per-account secret, so the concrete store is whatever secure storage the host provides — in
 * the CoreApp wasm plugin that is the namespaced `coreHost.secretGet/Set` store (`mcp:<installId>:*`),
 * so one plugin can never read another's PIN. Null (no store) means "this build can't persist a PIN";
 * [set_passcode][GoogleHomeMcpServer.setPasscodeTool] then reports that instead of silently dropping it.
 */
interface PasscodeStore {
    /** The saved unlock PIN, or null if none has been saved. */
    suspend fun get(): String?

    /** Save (overwrite) the unlock PIN. [pin] is already [normalized][normalizePasscode]. */
    suspend fun set(pin: String)
}

/**
 * A [PasscodeStore] kept only in memory, for entrypoints with no secure host store (the JVM webserver,
 * the standalone browser build). It survives for the life of the process/page — long enough that
 * "save my passcode" then "unlock" in the same session works — but not across restarts.
 *
 * ponytail: not persisted and not thread-safe; upgrade to a host-backed store (as the CoreApp plugin
 * does) if a build needs durability or concurrent tool calls.
 */
class InMemoryPasscodeStore(private var pin: String? = null) : PasscodeStore {
    override suspend fun get(): String? = pin
    override suspend fun set(pin: String) { this.pin = pin }
}

/**
 * Validate + canonicalize a user-supplied passcode: strip spaces/dashes a user might read it out with
 * ("11-22", "1 1 2 2") and require 4+ digits. Google Home lock PINs are numeric; rejecting non-digits
 * early turns a typo into a clear tool error rather than a silent unlock failure later.
 */
fun normalizePasscode(raw: String): String {
    val digits = raw.trim().replace(" ", "").replace("-", "")
    require(digits.length >= 4 && digits.all { it.isDigit() }) {
        "Passcode must be at least 4 digits (numbers only)."
    }
    return digits
}
