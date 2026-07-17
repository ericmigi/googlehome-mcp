package googlehome.mcp.auth

import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * The `(app, client_sig, scope)` triple that yields a bearer **foyer-pa accepts**.
 *
 * This is the one genuinely unknown piece of the auth bridge. gpsoauth will happily mint a token
 * for many scopes; the question is which one `googlehomefoyer-pa` honours on a real `GetTraits`.
 * The values below are the strongest community-known candidates (from `glocaltokens` and
 * `ha-google-home`). They are plain constants so live verification can swap them **without a code
 * change** — override [FoyerAuthImpl]'s `scopes` / `app` / `clientSig` params, or edit here.
 *
 * TODO(live-verify): confirm against a real master token by running a read-only
 * `HomeControlService/GetTraits` for the Outside Lights device and keeping whichever scope returns
 * HTTP 200. Only after auth is proven this way should any control (`UpdateTraits`) call run.
 */
object FoyerAuthConfig {
    /** The Google Home / Chromecast Android app package. */
    const val APP = "com.google.android.apps.chromecast.app"

    /** SHA-1 of that app's signing certificate. Google validates the `(APP, CLIENT_SIG)` pair. */
    const val CLIENT_SIG = "24bb24c05e47e0aefa68a58a766179d9b613a600"

    /**
     * Scopes to try, in order; the first that gpsoauth mints a token for is used. Ordered per the
     * AUTH-BRIDGE task: HomeGraph first (most specific to foyer), then the broad OAuthLogin master
     * scope as a fallback.
     */
    val SCOPE_CANDIDATES: List<String> = listOf(
        "oauth2:https://www.googleapis.com/auth/homegraph",
        "oauth2:https://www.google.com/accounts/OAuthLogin",
    )
}

/**
 * [FoyerAuth] backed by [GpsOAuthClient]. Mints a foyer bearer from the master token and caches it
 * until shortly before expiry. Thread-safe: all minting is serialized behind a [Mutex] so concurrent
 * callers share one in-flight refresh rather than hammering the auth endpoint.
 *
 * Expiry handling: Google's `getAuthToken` response usually carries `Expiry=<absolute-unix-seconds>`.
 * When present and in the future it is honoured (minus [expirySkewSeconds] of slack); otherwise the
 * token is cached for [fallbackTtlSeconds] (~55 min, safely under the typical ~1 h lifetime).
 */
@OptIn(ExperimentalTime::class)
class FoyerAuthImpl internal constructor(
    private val masterToken: MasterToken,
    private val oauth: GpsOAuthClient,
    private val scopes: List<String> = FoyerAuthConfig.SCOPE_CANDIDATES,
    private val app: String = FoyerAuthConfig.APP,
    private val clientSig: String = FoyerAuthConfig.CLIENT_SIG,
    private val fallbackTtlSeconds: Long = 55 * 60,
    private val expirySkewSeconds: Long = 60,
    private val nowEpochSeconds: () -> Long = { Clock.System.now().epochSeconds },
) : FoyerAuth {

    /** Convenience constructor: injects only the Ktor engine, using the community-default config. */
    constructor(masterToken: MasterToken, engine: HttpClientEngine) :
        this(masterToken, GpsOAuthClient(engine))

    private val mutex = Mutex()
    private var cachedToken: String? = null
    private var expiresAtEpoch: Long = 0L

    /**
     * Index into [scopes] of the scope to start minting from. Advanced by [invalidate] when foyer
     * rejects a bearer, so scope negotiation is ultimately decided by foyer acceptance (see
     * [FoyerAuth.invalidate]), not by gpsoauth minting success.
     */
    private var scopeIndex: Int = 0

    override suspend fun bearer(): String = mutex.withLock {
        val now = nowEpochSeconds()
        cachedToken?.let { token ->
            if (now < expiresAtEpoch) return@withLock token
        }
        mint(now)
    }

    override suspend fun invalidate() = mutex.withLock {
        cachedToken = null
        expiresAtEpoch = 0L
        // Advance past the scope foyer just rejected. If we've exhausted the list, wrap to 0: the
        // failure may be an expired/revoked token rather than a wrong scope, so a fresh mint from
        // the top is the right retry (and a genuinely-unaccepted set will surface as a foyer error).
        scopeIndex = if (scopes.isEmpty()) 0 else (scopeIndex + 1) % scopes.size
    }

    /**
     * Tries candidate scopes starting at [scopeIndex]; the first that gpsoauth mints wins and is
     * pinned (so a later cache-miss re-mints the same proven scope rather than re-probing). Must be
     * called holding [mutex].
     */
    private suspend fun mint(now: Long): String {
        var lastError: Exception? = null
        if (scopes.isEmpty()) {
            throw GpsOAuthException("No foyer scope candidates configured; cannot mint bearer")
        }
        for (offset in scopes.indices) {
            val i = (scopeIndex + offset) % scopes.size
            try {
                val token = oauth.getAuthToken(
                    masterToken = masterToken.value,
                    androidId = masterToken.androidId,
                    service = scopes[i],
                    app = app,
                    clientSig = clientSig,
                )
                scopeIndex = i          // pin the scope that minted
                cachedToken = token.auth
                expiresAtEpoch = deadlineFor(token.expiryEpochSeconds, now)
                return token.auth
            } catch (e: GpsOAuthException) {
                lastError = e
            }
        }
        throw lastError
            ?: GpsOAuthException("No foyer scope candidates could be minted")
    }

    /**
     * Computes the cache deadline. A future absolute `Expiry` is honoured but **clamped** to
     * `now + fallbackTtlSeconds`: a bogus/oversized `Expiry` must not cache a bearer well past its
     * real (~1 h) lifetime, which — combined with 401-driven [invalidate] — would otherwise stall.
     */
    private fun deadlineFor(expiryEpochSeconds: Long?, now: Long): Long {
        val cap = now + fallbackTtlSeconds
        val base = if (expiryEpochSeconds != null && expiryEpochSeconds > now) {
            minOf(expiryEpochSeconds, cap)
        } else {
            cap
        }
        return base - expirySkewSeconds
    }
}
