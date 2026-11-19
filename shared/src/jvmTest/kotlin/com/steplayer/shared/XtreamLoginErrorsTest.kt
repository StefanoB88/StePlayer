package com.steplayer.shared

import com.steplayer.shared.platform.stePlayerDefaults
import com.steplayer.shared.xtream.XtreamClient
import com.steplayer.shared.xtream.XtreamCredentials
import com.steplayer.shared.xtream.XtreamException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** How the many panel flavours say "wrong username or password", and what must stay a server error. */
class XtreamLoginErrorsTest {
    private fun loginError(status: Int, body: String = "", action: String? = null): XtreamException = runBlocking {
        val http = HttpClient(MockEngine { respond(body, HttpStatusCode.fromValue(status)) }) { stePlayerDefaults() }
        val client = XtreamClient(http, XtreamCredentials("http://panel:8080", "user", "wrong"), null)
        assertFailsWith<XtreamException> { if (action == null) client.login() else client.liveCategories() }
    }

    private fun assertRefused(status: Int, body: String = "") =
        assertEquals(XtreamException.Kind.INVALID_CREDENTIALS, loginError(status, body).kind, "HTTP $status «$body»")

    @Test fun classicAnswersAreStillRecognised() {
        assertRefused(401)
        assertRefused(403)
        assertRefused(200, """{"user_info":{"auth":0}}""")
    }

    @Test fun other4xxOnLoginMeanWrongCredentials() {
        assertRefused(400)
        assertRefused(406)
        assertRefused(410)
        assertRefused(461)
    }

    @Test fun errorBodiesThatSaySoAreRecognisedWhateverTheStatus() {
        assertRefused(404, """{"user_info":{"auth":0}}""")
        assertRefused(500, "INVALID_CREDENTIALS")
        assertRefused(512, "Invalid username or password")
        assertRefused(503, """{"error":"Authentication failed"}""")
        assertRefused(404, "User not found")
    }

    @Test fun emptyLoginAnswersMeanWrongCredentials() {
        assertRefused(200, "")
        assertRefused(200, "[]")
        assertRefused(200, "null")
    }

    @Test fun realServerProblemsStayServerErrorsWithTheirCode() {
        for (status in listOf(500, 502, 503, 404, 429)) {
            val e = loginError(status, "<html>Service unavailable</html>")
            assertEquals(XtreamException.Kind.SERVER, e.kind, "HTTP $status")
            assertEquals(status, e.httpStatus)
        }
    }

    @Test fun catalogRequestsDoNotTreatEvery4xxAsCredentials() {
        // Only login depends just on the credentials; a 400 on a list request is a server problem.
        assertEquals(XtreamException.Kind.SERVER, loginError(400, action = "get_live_categories").kind)
        assertEquals(XtreamException.Kind.INVALID_CREDENTIALS, loginError(401, action = "get_live_categories").kind)
    }

    @Test fun htmlPageOnLoginIsStillAWrongAddress() {
        assertEquals(XtreamException.Kind.PARSE, loginError(200, "<html><body>Welcome to nginx</body></html>").kind)
    }
}
