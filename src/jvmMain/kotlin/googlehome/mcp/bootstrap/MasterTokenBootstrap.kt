package googlehome.mcp.bootstrap

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.options.Cookie
import googlehome.mcp.auth.GpsOAuthClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.delay
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The "easy way" for the MCP server to get a master token.
 *
 * The master token can only be minted from a fresh `oauth_token`, which Google issues at the end of
 * its Android `EmbeddedSetup` sign-in. That sign-in is interactive (email + password + any 2FA) and
 * must be done by a human — this tool hosts a browser for that, then does the rest automatically.
 *
 * ## Why not just `Playwright.launch()`
 *
 * Google's `EmbeddedSetup` refuses any browser that advertises automation ("this browser or app may
 * not be secure"). A Playwright-launched Chrome carries `--enable-automation`, so `navigator.webdriver`
 * is true and the "controlled by automated test software" banner shows — Google blocks it.
 *
 * So instead we launch **real Chrome as an ordinary OS process** (with `--remote-debugging-port` and
 * a dedicated `--user-data-dir`, which Chrome 136+ requires for remote debugging) and **attach over
 * CDP** with [Browser.connectOverCDP]. A normally-launched Chrome has none of the automation
 * fingerprints, so `navigator.webdriver` is false and the sign-in works — the same reason a stealth
 * browser works. The [profileDir] persists the sign-in across runs.
 *
 * The returned [Result] carries the master token (treat as a full-account secret — never log it) and
 * the `androidId` it was minted with, which the caller MUST reuse for every later foyer-bearer mint.
 */
object MasterTokenBootstrap {

    data class Result(val masterToken: String, val androidId: String, val email: String?)

    private const val EMBEDDED_SETUP_URL = "https://accounts.google.com/EmbeddedSetup"
    private const val COOKIE_DOMAIN_URL = "https://accounts.google.com"
    private const val OAUTH_TOKEN_COOKIE = "oauth_token"
    private const val DEBUG_PORT = 9222

    /** Default persistent-profile dir (dedicated; NOT your normal Chrome profile). */
    fun defaultProfileDir(): Path =
        Paths.get(System.getProperty("user.home"), ".googlehome-mcp", "chrome-profile")

    /**
     * Drives the interactive sign-in and returns a fresh master token.
     *
     * @param androidId the stable per-account device id to tie the token to (generate once with
     *   [newAndroidId] and persist it — reuse it here and for every foyer-bearer mint).
     * @param profileDir dedicated Chrome profile dir (kept signed-in across runs).
     * @param timeout how long to wait for the human to finish signing in.
     * @param port the CDP remote-debugging port to launch Chrome on.
     */
    suspend fun obtain(
        androidId: String = newAndroidId(),
        profileDir: Path = defaultProfileDir(),
        timeout: Duration = 5.minutes,
        port: Int = DEBUG_PORT,
    ): Result {
        val oauthToken = scrapeOAuthToken(profileDir, timeout, port)
        return exchange(oauthToken, androidId)
    }

    /** Exchange an already-captured `oauth_token` for a master token (no browser). */
    suspend fun exchange(oauthToken: String, androidId: String = newAndroidId()): Result {
        val client = GpsOAuthClient(OkHttp.create())
        val res = client.exchangeOAuthToken(oauthToken, androidId)
        return Result(res.masterToken, androidId, res.email)
    }

    /**
     * Launches real Chrome as a normal process, attaches over CDP, waits for the human to complete
     * EmbeddedSetup, and returns the captured `oauth_token`.
     */
    suspend fun scrapeOAuthToken(
        profileDir: Path = defaultProfileDir(),
        timeout: Duration = 5.minutes,
        port: Int = DEBUG_PORT,
    ): String {
        profileDir.toFile().mkdirs()
        val chrome = launchRealChrome(port, profileDir)
        try {
            waitForCdp(port, 20.seconds)
            Playwright.create().use { pw ->
                val browser = pw.chromium().connectOverCDP("http://127.0.0.1:$port")
                val context = browser.contexts().firstOrNull() ?: error("Chrome exposed no CDP context")
                val page = context.pages().firstOrNull() ?: context.newPage()

                // Avoid capturing a stale token from a prior run.
                runCatching {
                    context.clearCookies(BrowserContext.ClearCookiesOptions().setName(OAUTH_TOKEN_COOKIE))
                }
                page.navigate(EMBEDDED_SETUP_URL)

                val deadline = System.nanoTime() + timeout.inWholeNanoseconds
                while (System.nanoTime() < deadline) {
                    tokenFrom(context.cookies(COOKIE_DOMAIN_URL))?.let { return it }
                    delay(POLL_INTERVAL)
                }
                throw BootstrapException(
                    "Timed out after $timeout waiting for the oauth_token cookie. Did the sign-in complete?",
                )
            }
        } finally {
            chrome.destroy()
            if (chrome.isAlive) { chrome.destroyForcibly() }
        }
    }

    /** Starts Chrome directly (not via Playwright) so it carries no automation fingerprints. */
    private fun launchRealChrome(port: Int, profileDir: Path): Process {
        val binary = findChromeBinary()
            ?: throw BootstrapException(
                "Could not find Google Chrome. Install it, or capture the oauth_token yourself and use --oauth-token.",
            )
        val cmd = listOf(
            binary,
            "--remote-debugging-port=$port",
            // Chrome 136+ ignores remote-debugging on the default profile — a dedicated dir is required.
            "--user-data-dir=${profileDir.toAbsolutePath()}",
            "--no-first-run",
            "--no-default-browser-check",
            "--remote-allow-origins=*",
            EMBEDDED_SETUP_URL,
        )
        return ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
    }

    private fun findChromeBinary(): String? {
        val candidates = when {
            System.getProperty("os.name").startsWith("Mac", ignoreCase = true) -> listOf(
                "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
                "${System.getProperty("user.home")}/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
            )
            System.getProperty("os.name").startsWith("Windows", ignoreCase = true) -> listOf(
                "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
                "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe",
            )
            else -> listOf(
                "/usr/bin/google-chrome",
                "/usr/bin/google-chrome-stable",
                "/opt/google/chrome/chrome",
                "/snap/bin/chromium",
            )
        }
        return candidates.firstOrNull { File(it).canExecute() }
    }

    /** Polls the CDP `/json/version` endpoint until Chrome's debugger is ready. */
    private suspend fun waitForCdp(port: Int, timeout: Duration) {
        val deadline = System.nanoTime() + timeout.inWholeNanoseconds
        while (System.nanoTime() < deadline) {
            try {
                val conn = URL("http://127.0.0.1:$port/json/version").openConnection() as HttpURLConnection
                conn.connectTimeout = 1000
                conn.readTimeout = 1000
                if (conn.responseCode == 200) return
            } catch (_: Exception) { /* not up yet */ }
            delay(300.milliseconds)
        }
        throw BootstrapException("Chrome's remote-debugging port $port never came up.")
    }

    private fun tokenFrom(cookies: List<Cookie>): String? =
        cookies.firstOrNull { it.name == OAUTH_TOKEN_COOKIE }?.value?.takeIf { it.isNotBlank() }

    /** A fresh random 16-hex-digit device id. Mint once per account and persist it. */
    fun newAndroidId(): String {
        val bytes = ByteArray(8)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    private val POLL_INTERVAL = 1_000.milliseconds
}

class BootstrapException(message: String) : Exception(message)
