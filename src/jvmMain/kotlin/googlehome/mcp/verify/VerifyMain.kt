package googlehome.mcp.verify

import googlehome.mcp.GoogleHomeMcp
import googlehome.mcp.auth.MasterToken
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

/**
 * Live smoke test of the full master-token -> foyer path. Reads `GOOGLE_MASTER_TOKEN` /
 * `GOOGLE_ANDROID_ID`, then exercises the real facade:
 *   - no args: list every device (proves the bearer + scope + GetHomeGraph/GetTraits path);
 *   - `--device "<name>"`: read that device's state;
 *   - `--device "<name>" --on|--off`: toggle it, reading state before and after.
 *
 * Run: `./gradlew runJvmVerify --args="--device \"Corner Lamp\" --on"` with the bootstrap env sourced in.
 */
fun main(args: Array<String>) {
    val masterToken = System.getenv("GOOGLE_MASTER_TOKEN")?.takeIf { it.isNotBlank() }
        ?: run { System.err.println("FATAL: GOOGLE_MASTER_TOKEN not set (run runJvmBootstrap first)."); exitProcess(1) }
    val androidId = System.getenv("GOOGLE_ANDROID_ID")?.takeIf { it.isNotBlank() } ?: MasterToken.DEFAULT_ANDROID_ID

    var device: String? = null
    var action: String? = null
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--device" -> device = args.getOrNull(++i)
            "--on", "--off" -> action = args[i]
            else -> { System.err.println("Unknown arg: ${args[i]}"); exitProcess(2) }
        }
        i++
    }

    val mcp = GoogleHomeMcp(masterToken = masterToken, engine = OkHttp.create(), androidId = androidId)
    runBlocking {
        if (device == null) {
            println("[devices]"); println(mcp.listDevices())
            return@runBlocking
        }
        println("[read] '$device' before: " + mcp.getDeviceState(deviceIdsFor(mcp, device)))
        when (action) {
            "--on" -> println("[turn_on] " + mcp.turnOn(name = device))
            "--off" -> println("[turn_off] " + mcp.turnOff(name = device))
            null -> println("(read-only; pass --on or --off to toggle)")
        }
        if (action != null) println("[read] '$device' after: " + mcp.getDeviceState(deviceIdsFor(mcp, device)))
    }
}

/** Best-effort: resolve a device name to its id(s) via list_devices for the state read. */
private suspend fun deviceIdsFor(mcp: GoogleHomeMcp, name: String): List<String> {
    val json = mcp.listDevices()
    return Regex("\"id\":\"([^\"]+)\"[^}]*?\"name\":\"([^\"]*${Regex.escape(name)}[^\"]*)\"", RegexOption.IGNORE_CASE)
        .findAll(json).map { it.groupValues[1] }.toList()
}
