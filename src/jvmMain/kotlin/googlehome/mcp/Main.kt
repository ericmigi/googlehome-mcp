package googlehome.mcp

import googlehome.mcp.auth.MasterToken
import googlehome.mcp.server.InMemoryPasscodeStore
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.sse.sse
import io.ktor.util.collections.ConcurrentMap
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.SseServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.awaitCancellation
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.system.exitProcess

/**
 * JVM webserver entrypoint.
 *
 * Reads the long-lived gpsoauth master token from the `GOOGLE_MASTER_TOKEN` env var (fail-fast if
 * absent), constructs [GoogleHomeMcp] with the JVM OkHttp Ktor engine, and serves the MCP server
 * over HTTP + SSE using the io.modelcontextprotocol kotlin-sdk server transport.
 *
 * Endpoints:
 *   GET  /health   -> "ok"
 *   GET  /sse      -> opens the MCP SSE stream (server -> client)
 *   POST /message  -> MCP client -> server messages (?sessionId=...)
 *
 * Env:
 *   GOOGLE_MASTER_TOKEN  (required) master token, starts with "aas_et/"
 *   GOOGLE_ANDROID_ID    (optional) 16-hex device id the master token was minted with
 *   PORT                 (optional) listen port, default 8765
 */
fun main() {
    val masterToken = System.getenv("GOOGLE_MASTER_TOKEN")?.takeIf { it.isNotBlank() }
        ?: run {
            System.err.println(
                "FATAL: GOOGLE_MASTER_TOKEN is required (the gpsoauth master token, starts with \"aas_et/\").",
            )
            exitProcess(1)
        }
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8765
    // The device id the master token was minted with. gpsoauth ties sub-tokens to it; supply the
    // real one (CoreApp persists a random per-account id). Falls back to the shared default.
    val androidId = System.getenv("GOOGLE_ANDROID_ID")?.takeIf { it.isNotBlank() }
        ?: MasterToken.DEFAULT_ANDROID_ID

    // Inject the JVM engine into the shared facade. The facade owns all foyer/auth logic;
    // this entrypoint only supplies the platform HTTP engine + the transport.
    val mcp = GoogleHomeMcp(
        masterToken = masterToken,
        engine = OkHttp.create(),
        androidId = androidId,
        passcodes = InMemoryPasscodeStore(),
    )

    val serverTransports = ConcurrentMap<String, SseServerTransport>()

    embeddedServer(CIO, host = "0.0.0.0", port = port) {
        install(SSE)
        routing {
            get("/health") { call.respondText("ok") }

            sse("/sse") {
                val transport = SseServerTransport("/message", this)
                // Register the transport BEFORE createSession() starts it and emits the `endpoint`
                // event — otherwise a fast client can POST /message before the session is stored and
                // get a spurious 404.
                serverTransports[transport.sessionId] = transport
                val session = configureServer(mcp).createSession(transport)
                session.onClose {
                    serverTransports.remove(transport.sessionId)
                }
                awaitCancellation()
            }

            post("/message") {
                val sessionId = call.request.queryParameters["sessionId"]
                if (sessionId == null) {
                    call.respond(HttpStatusCode.BadRequest, "Missing sessionId parameter")
                    return@post
                }
                val transport = serverTransports[sessionId]
                if (transport == null) {
                    call.respond(HttpStatusCode.NotFound, "Session not found")
                    return@post
                }
                transport.handlePostMessage(call)
            }
        }
    }.start(wait = true)
}

/**
 * Builds an MCP SDK [Server] and registers the full Google Home tool set (read tools + the
 * selector-based control tools) by adapting each commonMain [GhTool] to a real SDK `Tool`. Iterating
 * the server's own registry keeps the wire schemas in lock-step with the handler definitions — the
 * single source of truth lives in [GoogleHomeMcpServer], not duplicated here. The MCP SDK types are
 * JVM-only (no wasmJs artifact in 0.8.3), which is why this adaptation lives in jvmMain.
 *
 * Every handler simply dispatches to `mcp.server().call(name, args)` and wraps the resulting JSON in
 * a `TextContent`. Control tools return a per-device `{id,name,ok,state|error}` array; a resolution
 * or argument error is surfaced as an `isError` result rather than crashing the transport.
 */
private fun configureServer(mcp: GoogleHomeMcp): Server {
    val server = Server(
        Implementation(name = "googlehome-mcp", version = "0.1.0"),
        ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = false),
            ),
        ),
    )

    for (tool in mcp.tools()) {
        val name = tool.name
        server.addTool(
            name = name,
            description = tool.description,
            inputSchema = ToolSchema(
                properties = tool.inputSchema()["properties"]?.jsonObject ?: JsonObject(emptyMap()),
                required = tool.requiredInputs,
            ),
        ) { request ->
            val args = request.arguments ?: JsonObject(emptyMap())
            try {
                CallToolResult(content = listOf(TextContent(mcp.server().call(name, args).toString())))
            } catch (e: Exception) {
                CallToolResult(
                    content = listOf(TextContent(e.message ?: "Tool `$name` failed: $e")),
                    isError = true,
                )
            }
        }
    }

    return server
}
