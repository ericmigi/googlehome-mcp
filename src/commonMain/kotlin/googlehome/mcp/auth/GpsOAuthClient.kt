package googlehome.mcp.auth

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.http.userAgent

/** Raised when the gpsoauth endpoint returns an error (or no `Auth=` line) instead of a token. */
class GpsOAuthException(message: String) : Exception(message)

/**
 * Ports the community `gpsoauth` `getAuthToken` flow (see CoreApp's `GoogleKeepAuthClient`) to mint
 * short-lived OAuth bearer tokens from a long-lived master token.
 *
 * The endpoint `https://android.clients.google.com/auth` speaks a picky, non-JSON protocol:
 * - `User-Agent: GoogleAuth/1.4`, `Accept-Encoding: identity` (no gzip).
 * - Request body is `application/x-www-form-urlencoded`.
 * - Response is newline-delimited `Key=Value` text — parsed by [parseAuthResponse].
 *
 * `EncryptedPasswd` carries the master token **verbatim** (the name is historical; nothing is
 * encrypted). The account is identified by the master token, so the `Email` field is a placeholder.
 *
 * The client takes an injected [HttpClientEngine] so `commonMain` stays platform-agnostic
 * (ktor-client-js on wasmJs, okhttp/cio on jvm). Do not hardcode a platform engine here.
 */
class GpsOAuthClient(engine: HttpClientEngine) {

    private val client = HttpClient(engine) {
        defaultRequest {
            userAgent("GoogleAuth/1.4")
        }
    }

    /** A minted bearer plus, when the server echoed one, the absolute `Expiry` (Unix epoch seconds). */
    data class AuthToken(val auth: String, val expiryEpochSeconds: Long?)

    /** The long-lived master token (starts with `aas_et/`) plus the account email the server echoed. */
    data class MasterTokenResult(val masterToken: String, val email: String?)

    /**
     * Step 0 of the bridge: exchange a fresh `oauth_token` (scraped from Google's `EmbeddedSetup`
     * sign-in — see the bootstrap tool) for a long-lived **master token** via the `ac2dm` service.
     *
     * The `oauth_token` is account-bound, so the server derives the account itself; [PLACEHOLDER_EMAIL]
     * only satisfies the required field, and the real email comes back in [MasterTokenResult.email].
     * [androidId] must be the SAME id later passed to [getAuthToken] — Google ties the master token
     * to it. Mint one with a stable per-account value and reuse it everywhere.
     */
    suspend fun exchangeOAuthToken(
        oauthToken: String,
        androidId: String = DEFAULT_ANDROID_ID,
    ): MasterTokenResult {
        val form = buildExchangeForm(oauthToken, androidId)
        val res = client.post(AUTH_URL) {
            header(HttpHeaders.AcceptEncoding, "identity")
            setBody(FormDataContent(Parameters.build { form.forEach { (k, v) -> append(k, v) } }))
        }
        val parsed = parseAuthResponse(res.bodyAsText())
        val token = parsed["Token"]
            ?: throw GpsOAuthException("gpsoauth oauth_token exchange failed: ${parsed["Error"] ?: "no Token in response"}")
        return MasterTokenResult(token, parsed["Email"]?.takeIf { it.isNotBlank() })
    }

    /**
     * Exchanges [masterToken] for a short-lived bearer scoped to [service].
     *
     * @param service e.g. `oauth2:https://www.googleapis.com/auth/homegraph`.
     * @param app the requesting Android package (identity of the "app" asking for the token).
     * @param clientSig the app's signing-cert SHA-1; Google validates the `(app, client_sig)` pair.
     */
    suspend fun getAuthToken(
        masterToken: String,
        androidId: String = DEFAULT_ANDROID_ID,
        service: String,
        app: String,
        clientSig: String,
        email: String = PLACEHOLDER_EMAIL,
    ): AuthToken {
        val form = buildAuthTokenForm(
            masterToken = masterToken,
            androidId = androidId,
            service = service,
            app = app,
            clientSig = clientSig,
            email = email,
        )
        val res = client.post(AUTH_URL) {
            header(HttpHeaders.AcceptEncoding, "identity")
            setBody(FormDataContent(Parameters.build { form.forEach { (k, v) -> append(k, v) } }))
        }
        val parsed = parseAuthResponse(res.bodyAsText())
        val auth = parsed["Auth"] ?: throw GpsOAuthException(errorMessage(parsed))
        return AuthToken(auth, parsed["Expiry"]?.toLongOrNull())
    }

    private fun errorMessage(response: Map<String, String>): String {
        val error = response["Error"] ?: "unknown"
        val detail = response["ErrorDetail"]?.let { " ($it)" } ?: ""
        return "gpsoauth getAuthToken failed: $error$detail"
    }

    companion object {
        const val DEFAULT_ANDROID_ID = "0123456789abcdef"

        internal const val AUTH_URL = "https://android.clients.google.com/auth"

        // The master token identifies the account, so any syntactically-valid Email works.
        internal const val PLACEHOLDER_EMAIL = "owner@example.com"

        // Matches the Play Services version glocaltokens/ha-google-home send; configurable if it
        // ever needs bumping, but Google is lenient about the exact value.
        internal const val PLAY_SERVICES_VERSION = "240913000"

        // The `ac2dm` master-token exchange is app-agnostic; it uses the GMS core signing cert
        // (not the per-app chromecast sig used for foyer bearers).
        internal const val AC2DM_SERVICE = "ac2dm"
        internal const val GMS_CLIENT_SIG = "38918a453d07199354f8b19af05ec6562ced5788"

        /**
         * Builds the `ac2dm` oauth_token -> master token exchange form. Exposed for unit-testing the
         * exact request shape (field names + oauth_token round-tripping through form encoding).
         */
        internal fun buildExchangeForm(
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
         * Builds the `getAuthToken` form body. Exposed for unit testing the exact request shape
         * (field names + master-token/scope round-tripping through form encoding).
         */
        internal fun buildAuthTokenForm(
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
        internal fun parseAuthResponse(body: String): Map<String, String> =
            body.lineSequence()
                .mapNotNull { rawLine ->
                    val line = rawLine.trim()
                    if (line.isEmpty()) return@mapNotNull null
                    val idx = line.indexOf('=')
                    if (idx < 0) null else line.substring(0, idx) to line.substring(idx + 1)
                }
                .toMap()
    }
}
