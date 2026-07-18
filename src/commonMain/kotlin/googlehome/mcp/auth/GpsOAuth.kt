package googlehome.mcp.auth

/** Raised when the gpsoauth endpoint returns an error (or no `Auth=` line) instead of a token. */
class GpsOAuthException(message: String) : Exception(message)

/** A minted bearer plus, when the server echoed one, the absolute `Expiry` (Unix epoch seconds). */
data class AuthToken(val auth: String, val expiryEpochSeconds: Long?)

/** The long-lived master token (starts with `aas_et/`) plus the account email the server echoed. */
data class MasterTokenResult(val masterToken: String, val email: String?)

/**
 * The gpsoauth surface [FoyerAuthImpl] (and the bootstrap flows) code against. Two platform impls:
 * `GpsOAuthClient` (Ktor, in `ktorMain`) and the wasmWasi `host_http_fetch` impl. Both share the pure
 * request/response codec in [GpsOAuthCodec] — only the HTTP call differs.
 */
interface GpsOAuth {
    /** Exchange [masterToken] for a short-lived bearer scoped to [service]. */
    suspend fun getAuthToken(
        masterToken: String,
        androidId: String = GpsOAuthCodec.DEFAULT_ANDROID_ID,
        service: String,
        app: String,
        clientSig: String,
        email: String = GpsOAuthCodec.PLACEHOLDER_EMAIL,
    ): AuthToken

    /** Exchange a fresh `oauth_token` for a long-lived master token via the `ac2dm` service. */
    suspend fun exchangeOAuthToken(
        oauthToken: String,
        androidId: String = GpsOAuthCodec.DEFAULT_ANDROID_ID,
    ): MasterTokenResult
}

/**
 * The pure (transport-free) gpsoauth protocol: the two form bodies and the `Key=Value` response
 * parser. Ports the community `gpsoauth` flow.
 *
 * The endpoint `https://android.clients.google.com/auth` speaks a picky, non-JSON protocol:
 * - `User-Agent: GoogleAuth/1.4`, `Accept-Encoding: identity` (no gzip).
 * - Request body is `application/x-www-form-urlencoded`.
 * - Response is newline-delimited `Key=Value` text — parsed by [parseAuthResponse].
 *
 * `EncryptedPasswd` carries the master token **verbatim** (the name is historical; nothing is
 * encrypted). The account is identified by the master token, so the `Email` field is a placeholder.
 */
object GpsOAuthCodec {
    const val DEFAULT_ANDROID_ID = "0123456789abcdef"

    const val AUTH_URL = "https://android.clients.google.com/auth"

    // The master token identifies the account, so any syntactically-valid Email works.
    const val PLACEHOLDER_EMAIL = "owner@example.com"

    // Matches the Play Services version glocaltokens/ha-google-home send; configurable if it
    // ever needs bumping, but Google is lenient about the exact value.
    const val PLAY_SERVICES_VERSION = "240913000"

    // The `ac2dm` master-token exchange is app-agnostic; it uses the GMS core signing cert
    // (not the per-app chromecast sig used for foyer bearers).
    const val AC2DM_SERVICE = "ac2dm"
    const val GMS_CLIENT_SIG = "38918a453d07199354f8b19af05ec6562ced5788"

    /**
     * Builds the `ac2dm` oauth_token -> master token exchange form. The `oauth_token` is account-bound,
     * so the server derives the account; [email] only satisfies the required field. [androidId] must be
     * the SAME id later passed to [buildAuthTokenForm] — Google ties the master token to it.
     */
    fun buildExchangeForm(
        oauthToken: String,
        androidId: String,
        email: String = PLACEHOLDER_EMAIL,
    ): Map<String, String> = mapOf(
        "accountType" to "HOSTED_OR_GOOGLE",
        "Email" to email,
        "has_permission" to "1",
        "add_account" to "1",
        "ACCESS_TOKEN" to "1",
        "Token" to oauthToken,
        "service" to AC2DM_SERVICE,
        "source" to "android",
        "androidId" to androidId,
        "device_country" to "us",
        "operatorCountry" to "us",
        "lang" to "en",
        "sdk_version" to "17",
        "google_play_services_version" to PLAY_SERVICES_VERSION,
        "client_sig" to GMS_CLIENT_SIG,
        "callerSig" to GMS_CLIENT_SIG,
        "droidguard_results" to "dummy123",
    )

    /**
     * Builds the `getAuthToken` form body.
     *
     * @param service e.g. `oauth2:https://www.googleapis.com/auth/homegraph`.
     * @param app the requesting Android package (identity of the "app" asking for the token).
     * @param clientSig the app's signing-cert SHA-1; Google validates the `(app, client_sig)` pair.
     */
    fun buildAuthTokenForm(
        masterToken: String,
        androidId: String,
        service: String,
        app: String,
        clientSig: String,
        email: String = PLACEHOLDER_EMAIL,
    ): Map<String, String> = mapOf(
        "accountType" to "HOSTED_OR_GOOGLE",
        "Email" to email,
        "has_permission" to "1",
        // Despite the name, this carries the master token verbatim (no encryption).
        "EncryptedPasswd" to masterToken,
        "service" to service,
        "source" to "android",
        "androidId" to androidId,
        "app" to app,
        "client_sig" to clientSig,
        "device_country" to "us",
        "operatorCountry" to "us",
        "lang" to "en",
        "sdk_version" to "17",
        "google_play_services_version" to PLAY_SERVICES_VERSION,
    )

    /** Parses the `Key=Value` newline-delimited auth response. Values may themselves contain `=`. */
    fun parseAuthResponse(body: String): Map<String, String> =
        body.lineSequence()
            .mapNotNull { rawLine ->
                val line = rawLine.trim()
                if (line.isEmpty()) return@mapNotNull null
                val idx = line.indexOf('=')
                if (idx < 0) null else line.substring(0, idx) to line.substring(idx + 1)
            }
            .toMap()

    /** The exception message for a failed `getAuthToken` (no `Auth=` line). */
    fun authTokenError(response: Map<String, String>): String {
        val error = response["Error"] ?: "unknown"
        val detail = response["ErrorDetail"]?.let { " ($it)" } ?: ""
        return "gpsoauth getAuthToken failed: $error$detail"
    }

    /** The exception message for a failed oauth_token -> master token exchange (no `Token=` line). */
    fun exchangeError(response: Map<String, String>): String =
        "gpsoauth oauth_token exchange failed: ${response["Error"] ?: "no Token in response"}"
}
