package googlehome.mcp.auth

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FoyerAuthImplTest {

    private fun serviceOf(body: Any?): String? =
        (body as? FormDataContent)?.formData?.get("service")

    @Test
    fun cachesBearerAcrossCalls() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            respond(content = "Auth=ya29.cached\nExpiry=2000000000", status = HttpStatusCode.OK)
        }
        val auth = FoyerAuthImpl(
            masterToken = MasterToken("aas_et/m"),
            oauth = GpsOAuthClient(engine),
            nowEpochSeconds = { 1_000L },
        )

        assertEquals("ya29.cached", auth.bearer())
        assertEquals("ya29.cached", auth.bearer())
        assertEquals(1, calls) // second call served from cache, no new HTTP request
    }

    @Test
    fun fallsBackToNextScopeWhenFirstIsRejected() = runTest {
        val requestedScopes = mutableListOf<String?>()
        val engine = MockEngine { request ->
            val scope = serviceOf(request.body)
            requestedScopes += scope
            if (scope == FoyerAuthConfig.SCOPE_CANDIDATES.first()) {
                respond(content = "Error=BadAuthentication", status = HttpStatusCode.OK)
            } else {
                respond(content = "Auth=ya29.second_scope\nExpiry=2000000000", status = HttpStatusCode.OK)
            }
        }
        val auth = FoyerAuthImpl(
            masterToken = MasterToken("aas_et/m"),
            oauth = GpsOAuthClient(engine),
            nowEpochSeconds = { 1_000L },
        )

        assertEquals("ya29.second_scope", auth.bearer())
        assertEquals(FoyerAuthConfig.SCOPE_CANDIDATES, requestedScopes.toList())
    }

    @Test
    fun refreshesAfterExpiry() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            // Relative-looking expiry is ignored (not a future absolute epoch): fallback TTL applies.
            respond(content = "Auth=ya29.token$calls", status = HttpStatusCode.OK)
        }
        var now = 1_000L
        val auth = FoyerAuthImpl(
            masterToken = MasterToken("aas_et/m"),
            oauth = GpsOAuthClient(engine),
            fallbackTtlSeconds = 100,
            expirySkewSeconds = 10,
            nowEpochSeconds = { now },
        )

        assertEquals("ya29.token1", auth.bearer())
        now += 50 // still within TTL (deadline = 1000 + 100 - 10 = 1090)
        assertEquals("ya29.token1", auth.bearer())
        now += 100 // past deadline
        assertEquals("ya29.token2", auth.bearer())
        assertEquals(2, calls)
    }

    @Test
    fun throwsWhenAllScopesRejected() = runTest {
        val engine = MockEngine {
            respond(content = "Error=BadAuthentication", status = HttpStatusCode.OK)
        }
        val auth = FoyerAuthImpl(
            masterToken = MasterToken("aas_et/m"),
            oauth = GpsOAuthClient(engine),
            nowEpochSeconds = { 1_000L },
        )

        val ex = assertFailsWith<GpsOAuthException> { auth.bearer() }
        assertTrue(ex.message!!.contains("BadAuthentication"))
    }

    @Test
    fun honoursFutureAbsoluteExpiry() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            respond(content = "Auth=ya29.abs$calls\nExpiry=5000", status = HttpStatusCode.OK)
        }
        var now = 1_000L
        val auth = FoyerAuthImpl(
            masterToken = MasterToken("aas_et/m"),
            oauth = GpsOAuthClient(engine),
            fallbackTtlSeconds = 10_000, // cap (now+ttl=11000) well above Expiry, so it is honoured
            expirySkewSeconds = 60,
            nowEpochSeconds = { now },
        )

        assertEquals("ya29.abs1", auth.bearer())
        now = 4_900L // still before deadline (5000 - 60 = 4940)
        assertEquals("ya29.abs1", auth.bearer())
        now = 4_950L // past deadline
        assertEquals("ya29.abs2", auth.bearer())
        assertEquals(2, calls)
    }

    @Test
    fun clampsOversizedExpiryToTtlCap() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            // Bogus huge absolute Expiry — must NOT be trusted past the real lifetime.
            respond(content = "Auth=ya29.clamp$calls\nExpiry=9999999999", status = HttpStatusCode.OK)
        }
        var now = 1_000L
        val auth = FoyerAuthImpl(
            masterToken = MasterToken("aas_et/m"),
            oauth = GpsOAuthClient(engine),
            fallbackTtlSeconds = 100, // cap = now + 100
            expirySkewSeconds = 10,   // deadline = min(Expiry, 1100) - 10 = 1090
            nowEpochSeconds = { now },
        )

        assertEquals("ya29.clamp1", auth.bearer())
        now = 1_089L // just before the clamped deadline
        assertEquals("ya29.clamp1", auth.bearer())
        now = 1_091L // past the clamped deadline -> re-mint despite the huge Expiry
        assertEquals("ya29.clamp2", auth.bearer())
        assertEquals(2, calls)
    }

    @Test
    fun invalidateAdvancesToNextScopeAndForcesRemint() = runTest {
        val requestedScopes = mutableListOf<String?>()
        val engine = MockEngine { request ->
            val scope = serviceOf(request.body)
            requestedScopes += scope
            // Both scopes mint fine at gpsoauth; the caller distinguishes them by foyer acceptance.
            val tag = if (scope == FoyerAuthConfig.SCOPE_CANDIDATES.first()) "first" else "second"
            respond(content = "Auth=ya29.$tag\nExpiry=2000000000", status = HttpStatusCode.OK)
        }
        val auth = FoyerAuthImpl(
            masterToken = MasterToken("aas_et/m"),
            oauth = GpsOAuthClient(engine),
            nowEpochSeconds = { 1_000L },
        )

        // First mint pins scope[0] even though gpsoauth would happily mint either.
        assertEquals("ya29.first", auth.bearer())
        // Simulate foyer rejecting that bearer (401/403): the client calls invalidate().
        auth.invalidate()
        // Next bearer must re-mint with the NEXT scope candidate.
        assertEquals("ya29.second", auth.bearer())
        assertEquals(
            listOf(FoyerAuthConfig.SCOPE_CANDIDATES[0], FoyerAuthConfig.SCOPE_CANDIDATES[1]),
            requestedScopes.toList(),
        )
    }
}
