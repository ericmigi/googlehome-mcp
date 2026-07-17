@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package googlehome.mcp.plugin

import kotlin.js.JsAny
import kotlin.js.JsString
import kotlin.js.Promise

/**
 * Bindings for the CoreApp wasm-MCP **host ABI** (`coreHost.*`), injected into the JS engine that
 * hosts this module (iOS: in-process `JSContext`; Android: `WebView`).
 *
 * See `docs/wasm-mcp/ARCHITECTURE.md` in CoreApp:
 *
 * ```
 * coreHost.httpFetch(reqJson) -> Promise<respJson>    // allow-listed by the manifest's network.allow
 * coreHost.secretGet(key)     -> Promise<string|null> // namespaced to this MCP by the bridge
 * coreHost.secretSet(key,val) -> Promise<void>
 * coreHost.browserAuth()      -> Promise<string|null> // runs the manifest-pinned auth strategy
 * coreHost.log(level,msg)     -> void
 * ```
 *
 * These are the module's **only** capabilities: the engine has no ambient network, fs, or DOM, so
 * everything this plugin can reach is what the host chose to hand it. In particular there is no
 * `fetch`, which is why [CoreHostEngine] exists.
 *
 * The wrappers below normalise the host's return values (object-or-string, `undefined`-or-`null`) so
 * the Kotlin side sees one shape.
 */

/** `coreHost.httpFetch`. Resolves to the response JSON **text**, whatever shape the host returns. */
@JsFun(
    """(reqJson) => globalThis.coreHost.httpFetch(reqJson)
        .then((r) => (typeof r === 'string' ? r : JSON.stringify(r)))""",
)
internal external fun coreHostHttpFetch(reqJson: String): Promise<JsString>

/** `coreHost.secretGet`. Resolves to the value, or `null` when this MCP has never stored [key]. */
@JsFun(
    """(key) => globalThis.coreHost.secretGet(key)
        .then((v) => (v === null || v === undefined ? null : String(v)))""",
)
internal external fun coreHostSecretGet(key: String): Promise<JsString?>

/** `coreHost.secretSet`. The bridge namespaces [key] to `mcp:<installId>:<key>`; we never do. */
@JsFun("""(key, value) => globalThis.coreHost.secretSet(key, value).then(() => null)""")
internal external fun coreHostSecretSet(key: String, value: String): Promise<JsAny?>

/**
 * `coreHost.browserAuth`. Runs the auth strategy **pinned in the manifest** (for this plugin: a
 * cookie-capture of `oauth_token` on `accounts.google.com/EmbeddedSetup`) in a separate, real,
 * user-visible browser context, and resolves to the captured value — or `null` if the user cancelled.
 *
 * The plugin cannot choose the URL, domain, or cookie: the host refuses anything but the manifest's.
 */
@JsFun(
    """() => globalThis.coreHost.browserAuth()
        .then((v) => (v === null || v === undefined ? null : String(v)))""",
)
internal external fun coreHostBrowserAuth(): Promise<JsString?>

/** `coreHost.log`. Never pass a secret to this. */
@JsFun("""(level, msg) => { globalThis.coreHost.log(level, msg); }""")
internal external fun coreHostLog(level: String, msg: String)
