package googlehome.mcp.bootstrap

import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

/**
 * CLI to obtain a Google master token for the MCP server.
 *
 * Modes:
 *  - (default) launch real Chrome, you sign in, it scrapes `oauth_token` and exchanges it.
 *  - `--oauth-token <tok>` skip the browser and exchange a token you captured yourself.
 *  - `--android-id <id>` reuse a previously-minted device id (otherwise a fresh one is generated).
 *
 * Output is written as shell-friendly `export` lines to **stdout**; the token is a full-account
 * secret, so redirect it to a private file, e.g.:
 *
 *   ./gradlew runBootstrap --args="" > ~/.googlehome-mcp/token.env   # then: source it
 *
 * then run the server with `GOOGLE_MASTER_TOKEN` / `GOOGLE_ANDROID_ID` from that file.
 */
fun main(args: Array<String>) {
    var oauthToken: String? = null
    var androidId: String? = null
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--oauth-token" -> oauthToken = args.getOrNull(++i)
            "--android-id" -> androidId = args.getOrNull(++i)
            "-h", "--help" -> { printUsage(); return }
            else -> { System.err.println("Unknown arg: ${args[i]}"); printUsage(); exitProcess(2) }
        }
        i++
    }

    val id = androidId ?: MasterTokenBootstrap.newAndroidId()

    val result = try {
        runBlocking {
            if (oauthToken != null) {
                System.err.println("Exchanging provided oauth_token for a master token…")
                MasterTokenBootstrap.exchange(oauthToken!!, id)
            } else {
                System.err.println(
                    "Launching Chrome at accounts.google.com/EmbeddedSetup.\n" +
                        "Sign in with the Google account that owns your home, then wait — the token is captured automatically.",
                )
                MasterTokenBootstrap.obtain(androidId = id)
            }
        }
    } catch (e: Exception) {
        System.err.println("FAILED: ${e.message}")
        exitProcess(1)
    }

    // Secret goes to stdout (redirect to a file); human-readable status to stderr.
    System.err.println("Success. Account: ${result.email ?: "(unknown)"}. Master token captured (${result.masterToken.length} chars).")
    println("export GOOGLE_MASTER_TOKEN='${result.masterToken}'")
    println("export GOOGLE_ANDROID_ID='${result.androidId}'")
}

private fun printUsage() {
    System.err.println(
        """
        Obtain a Google master token for the Google Home MCP server.

        Usage:
          runBootstrap                          Launch Chrome, sign in, auto-capture + exchange.
          runBootstrap --oauth-token <tok>      Exchange an oauth_token you captured yourself.
          runBootstrap --android-id <id>        Reuse a previously-minted 16-hex device id.

        Writes `export GOOGLE_MASTER_TOKEN=…` / `export GOOGLE_ANDROID_ID=…` to stdout.
        The master token grants full account access — keep it secret; redirect stdout to a private file.
        """.trimIndent(),
    )
}
