@file:OptIn(ExperimentalWasmInterop::class, UnsafeWasmMemoryApi::class)

package googlehome.mcp.plugin

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.wasm.ExperimentalWasmInterop
import kotlin.wasm.WasmImport
import kotlin.wasm.unsafe.MemoryAllocator
import kotlin.wasm.unsafe.Pointer
import kotlin.wasm.unsafe.UnsafeWasmMemoryApi
import kotlin.wasm.unsafe.withScopedMemoryAllocator

/**
 * The chasm host ABI (module `env`), bound by the host to Kotlin lambdas. Every import is synchronous
 * and blocking — the host blocks its worker thread on network/auth. Strings cross as (ptr, len) UTF-8
 * in linear memory; the variable-length results use the retry protocol (return actual len; if it
 * exceeds the buffer, reallocate and call again; -1 = null/denied). See scratchpad/chasm-abi.md.
 */

@WasmImport("env", "host_read_input")
private external fun host_read_input(slot: Int, bufPtr: Int, bufCap: Int): Int

@WasmImport("env", "host_write_output")
private external fun host_write_output(bufPtr: Int, len: Int)

@WasmImport("env", "host_http_fetch")
private external fun host_http_fetch(reqPtr: Int, reqLen: Int, respPtr: Int, respCap: Int): Int

@WasmImport("env", "host_secret_get")
private external fun host_secret_get(keyPtr: Int, keyLen: Int, valPtr: Int, valCap: Int): Int

@WasmImport("env", "host_secret_set")
private external fun host_secret_set(keyPtr: Int, keyLen: Int, valPtr: Int, valLen: Int)

@WasmImport("env", "host_browser_auth")
private external fun host_browser_auth(outPtr: Int, outCap: Int): Int

@WasmImport("env", "host_log")
private external fun host_log(level: Int, msgPtr: Int, msgLen: Int)

// -------------------------------------------------------------------------------------------------
// Memory marshalling helpers
// -------------------------------------------------------------------------------------------------

/** Allocate a copy of [s] as UTF-8 in the scope; returns (ptr, byteLen). Empty strings get 1 byte. */
private fun MemoryAllocator.utf8(s: String): Pair<Pointer, Int> {
    val bytes = s.encodeToByteArray()
    val ptr = allocate(if (bytes.isEmpty()) 1 else bytes.size)
    for (i in bytes.indices) (ptr + i).storeByte(bytes[i])
    return ptr to bytes.size
}

/** Decode [len] bytes at this pointer as UTF-8. */
private fun Pointer.readUtf8(len: Int): String {
    val bytes = ByteArray(len)
    for (i in 0 until len) bytes[i] = (this + i).loadByte()
    return bytes.decodeToString()
}

/** Decode a NUL-terminated (or full-buffer) UTF-8 error message the host wrote on a -1 return. */
private fun Pointer.readError(cap: Int): String {
    var n = 0
    while (n < cap && (this + n).loadByte() != 0.toByte()) n++
    return readUtf8(n)
}

private const val INITIAL_CAP = 4096

// -------------------------------------------------------------------------------------------------
// Typed host calls
// -------------------------------------------------------------------------------------------------

/** Read input #[slot] (0 = tool name, 1 = argsJson). Absent slot → "". */
internal fun hostReadInput(slot: Int): String {
    var cap = INITIAL_CAP
    while (true) {
        var out: String = ""
        var grow = -1
        withScopedMemoryAllocator { alloc ->
            val buf = alloc.allocate(cap)
            val n = host_read_input(slot, buf.address.toInt(), cap)
            when {
                n <= 0 -> out = ""            // -1 (absent) or 0 (empty) → empty string
                n <= cap -> out = buf.readUtf8(n)
                else -> grow = n
            }
        }
        if (grow >= 0) { cap = grow; continue }
        return out
    }
}

/** Write the single result string for this export. */
internal fun hostWriteOutput(s: String) {
    withScopedMemoryAllocator { alloc ->
        val (ptr, len) = alloc.utf8(s)
        host_write_output(ptr.address.toInt(), len)
    }
}

/**
 * BLOCKING allow-listed fetch. [reqJson] = `{method,url,headers,body}`; returns `{status,headers,body}`.
 * On host denial/error (-1) throws with the host's error message.
 */
internal fun hostHttpFetch(reqJson: String): String {
    var cap = INITIAL_CAP
    while (true) {
        var out: String? = null
        var grow = -1
        var errorMsg: String? = null
        withScopedMemoryAllocator { alloc ->
            val (reqP, reqLen) = alloc.utf8(reqJson)
            val buf = alloc.allocate(cap)
            val n = host_http_fetch(reqP.address.toInt(), reqLen, buf.address.toInt(), cap)
            when {
                n < 0 -> errorMsg = buf.readError(cap)
                n <= cap -> out = buf.readUtf8(n)
                else -> grow = n
            }
        }
        errorMsg?.let { throw RuntimeException("host_http_fetch denied/failed: $it") }
        if (grow >= 0) { cap = grow; continue }
        return out!!
    }
}

/** Namespaced secret read. Returns null if absent. */
internal fun hostSecretGet(key: String): String? {
    var cap = INITIAL_CAP
    while (true) {
        var out: String? = null
        var grow = -1
        var isNull = false
        withScopedMemoryAllocator { alloc ->
            val (keyP, keyLen) = alloc.utf8(key)
            val buf = alloc.allocate(cap)
            val n = host_secret_get(keyP.address.toInt(), keyLen, buf.address.toInt(), cap)
            when {
                n < 0 -> isNull = true
                n <= cap -> out = buf.readUtf8(n)
                else -> grow = n
            }
        }
        if (isNull) return null
        if (grow >= 0) { cap = grow; continue }
        return out
    }
}

/** Namespaced secret write. */
internal fun hostSecretSet(key: String, value: String) {
    withScopedMemoryAllocator { alloc ->
        val (keyP, keyLen) = alloc.utf8(key)
        val (valP, valLen) = alloc.utf8(value)
        host_secret_set(keyP.address.toInt(), keyLen, valP.address.toInt(), valLen)
    }
}

/** BLOCKING manifest-pinned sign-in. Returns the captured token, or null if cancelled. */
internal fun hostBrowserAuth(): String? {
    var cap = INITIAL_CAP
    while (true) {
        var out: String? = null
        var grow = -1
        var isNull = false
        withScopedMemoryAllocator { alloc ->
            val buf = alloc.allocate(cap)
            val n = host_browser_auth(buf.address.toInt(), cap)
            when {
                n < 0 -> isNull = true
                n <= cap -> out = buf.readUtf8(n)
                else -> grow = n
            }
        }
        if (isNull) return null
        if (grow >= 0) { cap = grow; continue }
        return out
    }
}

/** Structured log. level: 0=debug 1=info 2=warn 3=error. Never pass a secret. */
internal fun hostLog(level: Int, msg: String) {
    withScopedMemoryAllocator { alloc ->
        val (ptr, len) = alloc.utf8(msg)
        host_log(level, ptr.address.toInt(), len)
    }
}

// -------------------------------------------------------------------------------------------------
// suspend -> blocking driver
// -------------------------------------------------------------------------------------------------

/**
 * Drive a `suspend` block to completion synchronously. Valid here because every underlying IO is a
 * synchronous blocking host import, so the coroutine never actually suspends (nothing returns
 * COROUTINE_SUSPENDED) — it runs straight through and resumes the captured continuation inline.
 */
internal fun <T> runSync(block: suspend () -> T): T {
    var result: Result<T>? = null
    block.startCoroutine(Continuation(EmptyCoroutineContext) { r -> result = r })
    return (result ?: error("runSync: block did not complete synchronously (a real suspension occurred)"))
        .getOrThrow()
}
