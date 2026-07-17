package googlehome.mcp.auth

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GpsOAuthClientTest {

    // ---- Key=Value parser ----

    @Test
    fun parsesKeyValueResponseIncludingValuesWithEquals() {
        val body = "Auth=ya29.abc==\nExpiry=1516883617\nservices=homegraph\n\n"
        val map = GpsOAuthClient.parseAuthResponse(body)

        assertEquals("ya29.abc==", map["Auth"])
        assertEquals("1516883617", map["Expiry"])
        assertEquals("homegraph", map["services"])
        assertEquals(3, map.size)
    }

    @Test
    fun parsesErrorResponse() {
        val body = "Error=BadAuthentication\nErrorDetail=InvalidSecondFactor\n"
        val map = GpsOAuthClient.parseAuthResponse(body)

        assertEquals("BadAuthentication", map["Error"])
        assertEquals("InvalidSecondFactor", map["ErrorDetail"])
    }

    // ---- request form shape ----

    @Test
    fun buildAuthTokenFormHasExpectedFields() {
        val form = GpsOAuthClient.buildAuthTokenForm(
            masterToken = "aas_et/master123",
            androidId = "0011223344556677",
            service = "oauth2:https://www.googleapis.com/auth/homegraph",
            app = "com.google.android.apps.chromecast.app",
            clientSig = "24bb24c05e47e0aefa68a58a766179d9b613a600",
        )

        // The master token rides verbatim in EncryptedPasswd (not encrypted, not in a password field).
        assertEquals("aas_et/master123", form["EncryptedPasswd"])
        assertEquals("oauth2:https://www.googleapis.com/auth/homegraph", form["service"])
        assertEquals("com.google.android.apps.chromecast.app", form["app"])
        assertEquals("24bb24c05e47e0aefa68a58a766179d9b613a600", form["client_sig"])
        assertEquals("0011223344556677", form["androidId"])
        assertEquals("HOSTED_OR_GOOGLE", form["accountType"])
        assertEquals("android", form["source"])
        assertEquals("1", form["has_permission"])
        // Placeholder email — the master token identifies the account.
        assertEquals("owner@example.com", form["Email"])
    }

    @Test
    fun getAuthTokenSendsMasterTokenAndReturnsBearer() = runTest {
        var captured: HttpRequestData? = null
        val client = GpsOAuthClient(
            MockEngine { request ->
                captured = request
                respond(content = "Auth=ya29.foyer_bearer\nExpiry=1516883617", status = HttpStatusCode.OK)
            },
        )

        val token = client.getAuthToken(
            masterToken = "aas_et/master123",
            service = "oauth2:https://www.googleapis.com/auth/homegraph",
            app = "com.google.android.apps.chromecast.app",
            clientSig = "24bb24c05e47e0aefa68a58a766179d9b613a600",
        )
        assertEquals("ya29.foyer_bearer", token.auth)
        assertEquals(1516883617L, token.expiryEpochSeconds)

        val request = assertNotNull(captured)
        assertEquals(GpsOAuthClient.AUTH_URL, request.url.toString())
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("GoogleAuth/1.4", request.headers[HttpHeaders.UserAgent])
        assertEquals("identity", request.headers[HttpHeaders.AcceptEncoding])

        val form = (request.body as FormDataContent).formData
        assertEquals("aas_et/master123", form["EncryptedPasswd"])
        assertEquals("com.google.android.apps.chromecast.app", form["app"])
        // scope contains colons + slashes and must round-trip through form encoding intact.
        assertEquals("oauth2:https://www.googleapis.com/auth/homegraph", form["service"])
        assertEquals(GpsOAuthClient.DEFAULT_ANDROID_ID, form["androidId"])
    }

    @Test
    fun getAuthTokenReturnsNullExpiryWhenAbsent() = runTest {
        val client = GpsOAuthClient(
            MockEngine { respond(content = "Auth=ya29.no_expiry", status = HttpStatusCode.OK) },
        )

        val token = client.getAuthToken(
            masterToken = "aas_et/m",
            service = "oauth2:scope",
            app = "app",
            clientSig = "sig",
        )
        assertEquals("ya29.no_expiry", token.auth)
        assertNull(token.expiryEpochSeconds)
    }

    // ---- oauth_token -> master token exchange (ac2dm) ----

    @Test
    fun buildExchangeFormHasExpectedFields() {
        val form = GpsOAuthClient.buildExchangeForm(
            oauthToken = "oauth2_4/abcDEF-token",
            androidId = "0011223344556677",
        )
        // The oauth_token rides in Token; the exchange targets the ac2dm service with the GMS sig.
        assertEquals("oauth2_4/abcDEF-token", form["Token"])
        assertEquals("ac2dm", form["service"])
        assertEquals("1", form["add_account"])
        assertEquals("1", form["ACCESS_TOKEN"])
        assertEquals(GpsOAuthClient.GMS_CLIENT_SIG, form["client_sig"])
        assertEquals(GpsOAuthClient.GMS_CLIENT_SIG, form["callerSig"])
        assertEquals("0011223344556677", form["androidId"])
        assertEquals("owner@example.com", form["Email"])
    }

    @Test
    fun exchangeOAuthTokenReturnsMasterTokenAndEmail() = runTest {
        var captured: HttpRequestData? = null
        val client = GpsOAuthClient(
            MockEngine { request ->
                captured = request
                respond(content = "Token=aas_et/masterXYZ\nEmail=owner@example.com\n", status = HttpStatusCode.OK)
            },
        )

        val res = client.exchangeOAuthToken(oauthToken = "oauth2_4/tok", androidId = "abcabcabcabcabc1")
        assertEquals("aas_et/masterXYZ", res.masterToken)
        assertEquals("owner@example.com", res.email)

        val form = (assertNotNull(captured).body as FormDataContent).formData
        assertEquals("oauth2_4/tok", form["Token"])
        assertEquals("ac2dm", form["service"])
        assertEquals("abcabcabcabcabc1", form["androidId"])
    }

    @Test
    fun exchangeOAuthTokenThrowsWhenNoToken() = runTest {
        val client = GpsOAuthClient(
            MockEngine { respond(content = "Error=BadAuthentication", status = HttpStatusCode.OK) },
        )
        val ex = assertFailsWith<GpsOAuthException> { client.exchangeOAuthToken("bad-oauth") }
        assertTrue(ex.message!!.contains("BadAuthentication"))
    }

    @Test
    fun throwsWhenAuthMissing() = runTest {
        val client = GpsOAuthClient(
            MockEngine { respond(content = "Error=BadAuthentication", status = HttpStatusCode.OK) },
        )

        val ex = assertFailsWith<GpsOAuthException> {
            client.getAuthToken(
                masterToken = "bad",
                service = "oauth2:scope",
                app = "app",
                clientSig = "sig",
            )
        }
        assertTrue(ex.message!!.contains("BadAuthentication"))
    }
}
