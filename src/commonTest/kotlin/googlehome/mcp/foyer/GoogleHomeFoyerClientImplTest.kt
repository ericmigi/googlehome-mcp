package googlehome.mcp.foyer

import googlehome.mcp.auth.FoyerAuth
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GoogleHomeFoyerClientImplTest {

    /** Fake auth that hands out bearers and counts invalidations. */
    private class FakeAuth(var token: String = "t1") : FoyerAuth {
        var invalidations = 0
        override suspend fun bearer(): String = token
        override suspend fun invalidate() { invalidations++; token = "t2" }
    }

    // A minimal valid GetTraits reply for device "d" (online, onOff=true).
    private val getTraitsOk =
        """[[[["d"],[["deviceStatus",[["online",[null,null,null,1]]]],["onOff",[["onOff",[null,null,null,1]]]]]]]]"""

    @Test
    fun stripsXssiPrefix() {
        assertEquals("[[1]]", GoogleHomeFoyerClientImpl.stripXssiPrefix(")]}'\n[[1]]"))
        assertEquals("[[1]]", GoogleHomeFoyerClientImpl.stripXssiPrefix(")]}'[[1]]"))
        assertEquals("[[1]]", GoogleHomeFoyerClientImpl.stripXssiPrefix("[[1]]")) // no prefix, untouched
    }

    @Test
    fun retriesOnceOn401AndInvalidatesAuth() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            if (calls == 1) {
                respond(content = "unauthorized", status = HttpStatusCode.Unauthorized)
            } else {
                respond(
                    content = getTraitsOk,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json+protobuf"),
                )
            }
        }
        val auth = FakeAuth()
        val client = GoogleHomeFoyerClientImpl(engine, auth)

        val states = client.getTraits(listOf("d"))

        assertEquals(1, auth.invalidations)          // 401 triggered exactly one invalidate
        assertEquals(2, calls)                        // original + one retry
        assertEquals("t2", auth.token)                // retry used the re-minted bearer
        assertEquals(true, states.single().onOff)
    }

    @Test
    fun throwsTypedErrorOnPersistentAuthFailure() = runTest {
        val engine = MockEngine { respond(content = "denied", status = HttpStatusCode.Forbidden) }
        val client = GoogleHomeFoyerClientImpl(engine, FakeAuth())

        val ex = assertFailsWith<FoyerHttpException> { client.getTraits(listOf("d")) }
        assertEquals(403, ex.status)
        assertTrue(ex.message!!.contains("403"))
    }
}
