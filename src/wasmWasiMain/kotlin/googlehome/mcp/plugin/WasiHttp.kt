package googlehome.mcp.plugin

import googlehome.mcp.auth.AuthToken
import googlehome.mcp.auth.GpsOAuth
import googlehome.mcp.auth.GpsOAuthCodec
import googlehome.mcp.auth.GpsOAuthException
import googlehome.mcp.auth.MasterTokenResult
import googlehome.mcp.foyer.FoyerHttpTransport
import googlehome.mcp.foyer.FoyerResponse
import googlehome.mcp.foyer.FoyerRpcClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private val JSON = Json { ignoreUnknownKeys = true }

/**
 * One blocking HTTP POST over the host's `host_http_fetch` import. Request/response cross the ABI as
 * JSON text: `{method,url,headers,body}` → `{status,headers,body}`. Bodies are text only (foyer speaks
 * `application/json+protobuf` JSON text; gpsoauth speaks form-encoded requests with `Key=Value` text
 * replies) — no binary path is needed, so none exists.
 */
private fun httpPost(url: String, headers: Map<String, String>, contentType: String, body: String): Pair<Int, String> {
    val reqJson = buildJsonObject {
        put("method", "POST")
        put("url", url)
        put("headers", buildJsonObject {
            headers.forEach { (k, v) -> put(k, v) }
            put("Content-Type", contentType)
        })
        put("body", body)
    }.toString()

    val resp = JSON.parseToJsonElement(hostHttpFetch(reqJson)).jsonObject
    val status = resp["status"]?.jsonPrimitive?.int ?: error("host_http_fetch returned no `status`.")
    val respBody = resp["body"]?.jsonPrimitive?.contentOrNull.orEmpty()
    return status to respBody
}

/** [FoyerHttpTransport] over `host_http_fetch` — the foyer-pa transport for the shared [FoyerRpcClient]. */
internal object HostFoyerTransport : FoyerHttpTransport {
    override suspend fun post(url: String, bodyJson: String, bearer: String): FoyerResponse {
        val (status, body) = httpPost(
            url = url,
            headers = mapOf(
                "Authorization" to "Bearer $bearer",
                "X-User-Agent" to "grpc-web-javascript/0.1",
            ),
            contentType = FoyerRpcClient.CONTENT_TYPE,
            body = bodyJson,
        )
        return FoyerResponse(status, body)
    }
}

/**
 * [GpsOAuth] over `host_http_fetch`. Reuses the pure [GpsOAuthCodec] (form bodies + `Key=Value`
 * parsing) — only the HTTP call differs from the Ktor `GpsOAuthClient`.
 */
internal object WasiGpsOAuth : GpsOAuth {
    override suspend fun getAuthToken(
        masterToken: String,
        androidId: String,
        service: String,
        app: String,
        clientSig: String,
        email: String,
    ): AuthToken {
        val parsed = post(GpsOAuthCodec.buildAuthTokenForm(masterToken, androidId, service, app, clientSig, email))
        val auth = parsed["Auth"] ?: throw GpsOAuthException(GpsOAuthCodec.authTokenError(parsed))
        return AuthToken(auth, parsed["Expiry"]?.toLongOrNull())
    }

    override suspend fun exchangeOAuthToken(oauthToken: String, androidId: String): MasterTokenResult {
        val parsed = post(GpsOAuthCodec.buildExchangeForm(oauthToken, androidId))
        val token = parsed["Token"] ?: throw GpsOAuthException(GpsOAuthCodec.exchangeError(parsed))
        return MasterTokenResult(token, parsed["Email"]?.takeIf { it.isNotBlank() })
    }

    private fun post(form: Map<String, String>): Map<String, String> {
        val (_, body) = httpPost(
            url = GpsOAuthCodec.AUTH_URL,
            headers = mapOf(
                "User-Agent" to "GoogleAuth/1.4",
                "Accept-Encoding" to "identity",
            ),
            contentType = "application/x-www-form-urlencoded",
            body = formEncode(form),
        )
        return GpsOAuthCodec.parseAuthResponse(body)
    }
}

/** `application/x-www-form-urlencoded` body: unreserved chars kept, space → `+`, else `%XX` per byte. */
private fun formEncode(form: Map<String, String>): String =
    form.entries.joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" }

private fun encode(s: String): String {
    val sb = StringBuilder()
    for (byte in s.encodeToByteArray()) {
        val c = byte.toInt() and 0xFF
        when {
            c == ' '.code -> sb.append('+')
            c.toChar() in 'A'..'Z' || c.toChar() in 'a'..'z' || c.toChar() in '0'..'9' ||
                c.toChar() in "-_.*" -> sb.append(c.toChar())
            else -> sb.append('%').append(HEX[c shr 4]).append(HEX[c and 0x0F])
        }
    }
    return sb.toString()
}

private const val HEX = "0123456789ABCDEF"
