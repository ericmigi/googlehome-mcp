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

/**
 * Ktor-backed [GpsOAuth]: mints short-lived OAuth bearers (and does the one-time master-token
 * exchange) by POSTing the [GpsOAuthCodec] form bodies to `android.clients.google.com/auth`.
 *
 * The pure protocol (form field names, `Key=Value` parsing) lives in [GpsOAuthCodec] so the wasmWasi
 * `host_http_fetch` impl can share it; only the HTTP call is here. Takes an injected
 * [HttpClientEngine] so `ktorMain` stays platform-agnostic (okhttp on jvm, Js on wasmJs).
 */
class GpsOAuthClient(engine: HttpClientEngine) : GpsOAuth {

    private val client = HttpClient(engine) {
        defaultRequest {
            userAgent("GoogleAuth/1.4")
        }
    }

    override suspend fun exchangeOAuthToken(
        oauthToken: String,
        androidId: String,
    ): MasterTokenResult {
        val parsed = post(GpsOAuthCodec.buildExchangeForm(oauthToken, androidId))
        val token = parsed["Token"] ?: throw GpsOAuthException(GpsOAuthCodec.exchangeError(parsed))
        return MasterTokenResult(token, parsed["Email"]?.takeIf { it.isNotBlank() })
    }

    override suspend fun getAuthToken(
        masterToken: String,
        androidId: String,
        service: String,
        app: String,
        clientSig: String,
        email: String,
    ): AuthToken {
        val parsed = post(
            GpsOAuthCodec.buildAuthTokenForm(masterToken, androidId, service, app, clientSig, email),
        )
        val auth = parsed["Auth"] ?: throw GpsOAuthException(GpsOAuthCodec.authTokenError(parsed))
        return AuthToken(auth, parsed["Expiry"]?.toLongOrNull())
    }

    private suspend fun post(form: Map<String, String>): Map<String, String> {
        val res = client.post(GpsOAuthCodec.AUTH_URL) {
            header(HttpHeaders.AcceptEncoding, "identity")
            setBody(FormDataContent(Parameters.build { form.forEach { (k, v) -> append(k, v) } }))
        }
        return GpsOAuthCodec.parseAuthResponse(res.bodyAsText())
    }
}
