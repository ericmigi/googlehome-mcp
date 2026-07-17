package googlehome.mcp.auth

/**
 * Input to the whole system: the long-lived gpsoauth master token (starts with `aas_et/`).
 *
 * The master token is obtained once (out of band) by exchanging an `oauth_token` against
 * `service=ac2dm` — the MCP server never does that exchange, it receives the resulting master
 * token as configuration. [androidId] is the 16-hex-digit device id the token was minted for;
 * Google ties issued auth tokens to it, so it must stay stable for a given account.
 */
data class MasterToken(val value: String, val androidId: String = DEFAULT_ANDROID_ID) {
    companion object {
        /**
         * Fallback device id. **Prefer supplying the real id** the master token was minted with:
         * gpsoauth ties issued sub-tokens to the `androidId`, and CoreApp mints each master token
         * against a random, persisted id (see `GoogleKeepCredentials`). A mismatched id can make
         * `getAuthToken` fail or yield a bearer foyer rejects. Entrypoints read it from
         * `GOOGLE_ANDROID_ID` (jvm) / an explicit arg (wasm); this default is only a last resort.
         */
        const val DEFAULT_ANDROID_ID = "0123456789abcdef"
    }
}

/** Mints + caches short-lived foyer Bearer tokens from the master token. */
interface FoyerAuth {
    /** Returns a valid Bearer access token for foyer-pa, minting/refreshing as needed. */
    suspend fun bearer(): String

    /**
     * Drops the cached token and advances scope negotiation, so the next [bearer] mints a fresh
     * token with the next candidate scope. Called by the foyer client when foyer-pa itself rejects
     * a bearer (HTTP 401/403) — which is the only place that can tell whether a mintable scope is
     * actually *accepted*. gpsoauth minting success alone does not prove foyer acceptance.
     */
    suspend fun invalidate()
}
