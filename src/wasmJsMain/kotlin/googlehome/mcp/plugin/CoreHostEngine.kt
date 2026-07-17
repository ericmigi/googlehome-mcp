@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class, io.ktor.utils.io.InternalAPI::class)

package googlehome.mcp.plugin

import io.ktor.client.engine.HttpClientEngineBase
import io.ktor.client.engine.HttpClientEngineCapability
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.callContext
import io.ktor.client.engine.mergeHeaders
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.js.JsString

/**
 * A Ktor [io.ktor.client.engine.HttpClientEngine] whose transport is the host's `coreHost.httpFetch`.
 *
 * This is the whole point of the plugin edge: `commonMain` (the foyer client and the gpsoauth bridge)
 * already takes an **injected** engine, so swapping `Js.create()` for `CoreHostEngine()` moves every
 * byte of egress behind the host's capability check — the manifest's `network.allow` — without
 * touching a line of protocol logic. The sandboxed engine has no `fetch` of its own, so there is no
 * way around this: it is enforced by the absence of an alternative, not by convention.
 *
 * ## Wire shape (host ABI)
 *
 * ```
 * request  -> {"method":"POST","url":"https://…","headers":{"K":"v"},"body":"…"}
 * response <- {"status":200,"headers":{"K":"v"},"body":"…"}
 * ```
 *
 * ## Text bodies only
 *
 * Bodies cross the bridge as JSON **strings**, which is exactly right for this plugin's two
 * endpoints — foyer-pa speaks `application/json+protobuf` (JSON text) and gpsoauth speaks
 * form-encoded requests with `Key=Value` text responses. A binary body would need a base64 field on
 * both sides of the ABI; nothing here needs one, so it is deliberately not implemented.
 *
 * Repeated headers are joined with `,` (HTTP's own list rule), since the ABI's `headers` is a flat
 * JSON object rather than a multimap.
 */
class CoreHostEngine(
    override val config: HttpClientEngineConfig = HttpClientEngineConfig(),
) : HttpClientEngineBase("core-host") {

    override val dispatcher: CoroutineDispatcher = Dispatchers.Default

    /**
     * Deliberately empty — in particular **not** `HttpTimeoutCapability`.
     *
     * The host owns the real socket, so this engine genuinely cannot honour a connect/socket
     * timeout. Declaring the capability anyway would make Ktor's `HttpTimeout` plugin defer to an
     * engine that then ignores it — a hung `coreHost.httpFetch` would hang the tool call forever,
     * silently. Leaving it undeclared means configuring `HttpTimeout` fails loudly instead, which is
     * the honest outcome; timeouts belong to the host side of the bridge.
     */
    override val supportedCapabilities: Set<HttpClientEngineCapability<*>> = emptySet()

    override suspend fun execute(data: HttpRequestData): HttpResponseData {
        val callContext = callContext()
        val requestTime = GMTDate()

        val reqJson = buildJsonObject {
            put("method", data.method.value)
            put("url", data.url.toString())
            put("headers", outgoingHeaders(data))
            put("body", bodyText(data.body))
        }.toString()

        val respText = coreHostHttpFetch(reqJson).await<JsString>().toString()
        val resp = JSON.parseToJsonElement(respText).jsonObject

        val status = resp["status"]?.jsonPrimitive?.int
            ?: error("coreHost.httpFetch returned no `status`.")
        val respBody = resp["body"]?.jsonPrimitive?.contentOrNull.orEmpty()

        return HttpResponseData(
            statusCode = HttpStatusCode.fromValue(status),
            requestTime = requestTime,
            headers = io.ktor.http.Headers.build {
                (resp["headers"] as? JsonObject)?.forEach { (k, v) ->
                    v.jsonPrimitive.contentOrNull?.let { append(k, it) }
                }
            },
            version = HttpProtocolVersion.HTTP_1_1,
            body = ByteReadChannel(respBody.encodeToByteArray()),
            callContext = callContext,
        )
    }

    /**
     * The request headers as a flat JSON object. Uses Ktor's [mergeHeaders] so `Content-Type` and
     * `Content-Length` — which live on the [OutgoingContent], not in `data.headers` — are included;
     * getting this wrong is how foyer's `application/json+protobuf` calls silently become `text/plain`.
     */
    private fun outgoingHeaders(data: HttpRequestData): JsonObject = buildJsonObject {
        val collected = LinkedHashMap<String, MutableList<String>>()
        mergeHeaders(data.headers, data.body) { key, value ->
            collected.getOrPut(key) { mutableListOf() }.add(value)
        }
        collected.forEach { (k, values) -> put(k, values.joinToString(",")) }
    }

    /** Flattens an [OutgoingContent] to text. See the class KDoc on why binary is out of scope. */
    private suspend fun bodyText(content: OutgoingContent): String = when (content) {
        is OutgoingContent.NoContent -> ""
        is OutgoingContent.ByteArrayContent -> content.bytes().decodeToString()
        is OutgoingContent.ReadChannelContent -> content.readFrom().readRemaining().readByteArray().decodeToString()
        is OutgoingContent.WriteChannelContent -> {
            val channel = io.ktor.utils.io.ByteChannel()
            content.writeTo(channel)
            channel.flushAndClose()
            channel.readRemaining().readByteArray().decodeToString()
        }
        is OutgoingContent.ProtocolUpgrade ->
            throw UnsupportedOperationException("CoreHostEngine does not support protocol upgrades.")
        else -> ""
    }

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
    }
}
