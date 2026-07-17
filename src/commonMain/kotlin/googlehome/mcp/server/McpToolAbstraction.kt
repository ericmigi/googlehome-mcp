package googlehome.mcp.server

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A thin, wasmJs-safe MCP tool abstraction living in commonMain.
 *
 * The `io.modelcontextprotocol:kotlin-sdk` 0.8.3 publishes no wasmJs artifact (see
 * `gradle/libs.versions.toml`), so common code cannot depend on its `Tool`/`ToolSchema` types.
 * Instead the server defines tools with [GhTool] here, and the JVM entrypoint adapts each [GhTool]
 * into a real SDK `Tool` (see the JVM adapter). The shapes are deliberately isomorphic — name,
 * description, a JSON-Schema `object` input schema, and an async handler — so adaptation is a
 * one-liner per field.
 */

/** One property of a tool's input JSON-Schema (all our inputs are flat primitives/arrays). */
data class GhProperty(
    /** JSON-Schema type: "string" | "boolean" | "number" | "array" | "object". */
    val type: String,
    val description: String,
    /** For `type == "array"`, the item type (e.g. "string"). Null for scalars. */
    val itemsType: String? = null,
)

/**
 * A single MCP tool: its schema plus an async handler that takes decoded JSON args and returns a
 * JSON result. Handlers are pure functions of their arguments (plus the captured foyer client) — no
 * hidden device-id inputs. See [GoogleHomeMcpServer] for the concrete tools.
 */
class GhTool(
    val name: String,
    val description: String,
    /** Ordered map of input property name -> schema. May be empty (e.g. `list_homes`). */
    val inputProperties: Map<String, GhProperty> = emptyMap(),
    /** Which input properties are required. */
    val requiredInputs: List<String> = emptyList(),
    private val handler: suspend (args: JsonObject) -> JsonElement,
) {
    /** The tool's input schema as a JSON-Schema `object`, matching MCP `inputSchema`. */
    fun inputSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            for ((propName, prop) in inputProperties) {
                put(propName, buildJsonObject {
                    put("type", prop.type)
                    put("description", prop.description)
                    if (prop.itemsType != null) {
                        put("items", buildJsonObject { put("type", prop.itemsType) })
                    }
                })
            }
        })
        put("required", buildJsonArray { requiredInputs.forEach { add(JsonPrimitive(it)) } })
    }

    /** The set of accepted input property names — used by tests to prove the control lock. */
    fun inputPropertyNames(): Set<String> = inputProperties.keys

    /** Invoke the tool. [args] is the decoded MCP `arguments` object ({} when the tool takes none). */
    suspend fun call(args: JsonObject): JsonElement = handler(args)
}
