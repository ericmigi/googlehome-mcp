package googlehome.mcp.foyer

import googlehome.mcp.auth.FoyerAuth
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent

/**
 * [GoogleHomeFoyerClient] over the real foyer-pa HTTP endpoint, on an injected Ktor [HttpClientEngine]
 * (okhttp on jvm, Js on wasmJs). All protocol logic lives in the shared [FoyerRpcClient]; this class
 * is just the Ktor transport for it — one POST with the fixed header set the web/Android app send,
 * except the cookie `SAPISIDHASH` is replaced by `Authorization: Bearer <token>`.
 *
 * `X-Goog-Api-Key` is intentionally omitted: verified live (2026-07-16) that pairing the web api key
 * with our Android-minted Bearer makes foyer reject the call ("API Key and the authentication
 * credential are from different projects"). A Bearer alone identifies the project.
 */
class GoogleHomeFoyerClientImpl(
    engine: HttpClientEngine,
    auth: FoyerAuth,
) : GoogleHomeFoyerClient by FoyerRpcClient(KtorTransport(engine), auth) {

    private class KtorTransport(engine: HttpClientEngine) : FoyerHttpTransport {
        private val client = HttpClient(engine)
        private val contentType = ContentType.parse(FoyerRpcClient.CONTENT_TYPE)

        override suspend fun post(url: String, bodyJson: String, bearer: String): FoyerResponse {
            val resp = client.post(url) {
                header(HttpHeaders.Authorization, "Bearer $bearer")
                header("X-User-Agent", "grpc-web-javascript/0.1")
                setBody(TextContent(bodyJson, contentType))
            }
            return FoyerResponse(resp.status.value, resp.bodyAsText())
        }
    }

    companion object {
        /** Forwards to [FoyerRpcClient.stripXssiPrefix] (kept for the existing unit test). */
        fun stripXssiPrefix(body: String): String = FoyerRpcClient.stripXssiPrefix(body)
    }
}
