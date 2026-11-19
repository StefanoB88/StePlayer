package com.steplayer.shared

import com.steplayer.shared.net.NetworkErrors
import com.steplayer.shared.net.NetworkErrors.Target
import com.steplayer.shared.net.NetworkStatus
import com.steplayer.shared.platform.JvmPlatform
import com.steplayer.shared.platform.stePlayerDefaults
import com.steplayer.shared.model.SourceInput
import com.steplayer.shared.xtream.XtreamException.Kind
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.UnknownServiceException
import javax.net.ssl.SSLHandshakeException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Every failure must tell the user exactly what is wrong, not "errore del server". */
class NetworkErrorsTest {
    private val url = "http://panel.example:8080"

    @AfterTest fun resetStatus() { NetworkErrors.status = { NetworkStatus.UNKNOWN } }

    private fun classify(e: Throwable, target: Target = Target.SERVER) = NetworkErrors.fromThrowable(e, url, target)

    @Test fun deviceOfflineWinsOverEverything() {
        NetworkErrors.status = { NetworkStatus.OFFLINE }
        val e = classify(UnknownHostException("panel.example"))
        assertEquals(Kind.NO_INTERNET, e.kind)
        assertTrue(e.message!!.contains("non è connesso a Internet"))
    }

    @Test fun wifiWithoutInternetIsNotBlamedOnTheProvider() {
        NetworkErrors.status = { NetworkStatus.CONNECTED_NO_INTERNET }
        val e = classify(SocketTimeoutException("connect timed out"))
        assertEquals(Kind.NO_INTERNET, e.kind)
        assertTrue(e.message!!.contains("Internet non sembra funzionare"))
    }

    @Test fun eachCauseHasItsOwnKindAndMessage() {
        NetworkErrors.status = { NetworkStatus.ONLINE }
        val cases = listOf(
            UnknownHostException("panel.example") to Kind.HOST_NOT_FOUND,
            ConnectException("failed to connect to panel.example/1.2.3.4 (port 8080): connect failed: ECONNREFUSED (Connection refused)") to Kind.UNREACHABLE,
            NoRouteToHostException("No route to host") to Kind.UNREACHABLE,
            SocketTimeoutException("timeout") to Kind.TIMEOUT,
            io.ktor.client.network.sockets.ConnectTimeoutException("Connect timeout has expired") to Kind.TIMEOUT,
            SSLHandshakeException("Trust anchor for certification path not found.") to Kind.SECURE_CONNECTION,
            UnknownServiceException("CLEARTEXT communication to panel.example not permitted by network security policy") to Kind.SECURE_CONNECTION,
            EOFException("\\n not found: limit=0") to Kind.NETWORK,
            ConnectException("connect failed: ENETUNREACH (Network is unreachable)") to Kind.NO_INTERNET,
        )
        for ((error, kind) in cases) {
            val e = classify(IOException("wrapped", error))
            assertEquals(kind, e.kind, "${error::class.simpleName}: ${error.message}")
        }
    }

    @Test fun messagesNameTheServerAndTheAction() {
        assertEquals("Indirizzo non trovato: «panel.example:8080» non esiste. Controlla di averlo scritto correttamente.",
            classify(UnknownHostException()).message)
        assertTrue(classify(ConnectException("Connection refused")).message!!.contains("porta"))
        assertTrue(classify(SocketTimeoutException(), Target.LIST).message!!.startsWith("Il server della lista non risponde"))
        assertEquals("panel.example", NetworkErrors.hostOf("http://panel.example/get.php?u=1"))
        assertEquals("panel.example:8080", NetworkErrors.hostOf("http://panel.example:8080"))
        assertEquals("panel.example", NetworkErrors.hostOf("https://panel.example:443/x"))
    }

    @Test fun unknownAddressMessagesStillReadWell() {
        val e = NetworkErrors.fromThrowable(ConnectException("Connection refused"), url = "", Target.SERVER)
        assertEquals("Il server del provider rifiuta la connessione: è spento oppure la porta è sbagliata. Controlla la porta o riprova più tardi.", e.message)
    }

    @Test fun httpStatusesAreExplained() {
        assertEquals(Kind.INVALID_CREDENTIALS, NetworkErrors.fromStatus(401, Target.SERVER).kind)
        assertTrue(NetworkErrors.fromStatus(403, Target.LIST).message!!.contains("link della lista"))
        assertEquals(Kind.NOT_FOUND, NetworkErrors.fromStatus(404, Target.LIST).kind)
        assertTrue(NetworkErrors.fromStatus(404, Target.LIST).message!!.contains("link è sbagliato o scaduto"))
        assertEquals(Kind.TOO_MANY_REQUESTS, NetworkErrors.fromStatus(429, Target.SERVER).kind)
        assertTrue(NetworkErrors.fromStatus(503, Target.SERVER).message!!.contains("temporaneamente fuori servizio (errore 503)"))
        assertTrue(NetworkErrors.fromStatus(500, Target.GUIDE).message!!.startsWith("Il server della guida TV ha un problema interno"))
        assertTrue(NetworkErrors.fromStatus(418, Target.SERVER).message!!.contains("codice 418"))
    }

    @Test fun m3uListErrorsAreExplainedToo() = runBlocking {
        for ((status, expected) in listOf(404 to "La lista non si trova più", 403 to "Accesso negato", 503 to "Il server della lista è temporaneamente")) {
            val http = HttpClient(MockEngine { respond("", HttpStatusCode.fromValue(status)) }) { stePlayerDefaults() }
            val core = StePlayerCore(JvmPlatform(http))
            val id = core.sources.add(SourceInput.M3u(name = "Lista", listUrl = "http://lists.example/my.m3u"))
            val e = assertFailsWith<com.steplayer.shared.xtream.XtreamException> { core.sync.sync(id) }
            assertTrue(e.message!!.startsWith(expected), "HTTP $status: ${e.message}")
        }
    }
}
