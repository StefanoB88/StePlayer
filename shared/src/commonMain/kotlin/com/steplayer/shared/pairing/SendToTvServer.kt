package com.steplayer.shared.pairing

import com.steplayer.shared.data.SourceRepository
import com.steplayer.shared.model.SourceInput
import com.steplayer.shared.util.CFlow
import com.steplayer.shared.util.nowMillis
import com.steplayer.shared.util.wrap
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readByteArray
import io.ktor.utils.io.readUTF8Line
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random

sealed interface SendToTvState {
    data object Idle : SendToTvState
    /** Show [code] big on screen; the TV finds the phone by itself ([address] is the manual fallback). */
    data class Waiting(val code: String, val address: String, val expiresAtMs: Long) : SendToTvState
    /** The TV received the lists. */
    data class Sent(val count: Int) : SendToTvState
    data class Failed(val message: String) : SendToTvState
}

/** One list as the TV receives it (the TV app adds it with its own "Aggiungi lista" logic). */
@Serializable
internal data class SentList(
    val kind: String,
    val name: String,
    val serverUrl: String? = null,
    val username: String? = null,
    val password: String? = null,
    val listUrl: String? = null,
    val epgUrl: String? = null,
    val userAgent: String? = null,
)

@Serializable
internal data class ClaimRequest(val code: String = "")

@Serializable
internal data class ClaimResponse(val lists: List<SentList>)

@Serializable
internal data class HelloResponse(val app: String, val v: Int, val device: String)

/**
 * "Invia una lista alla TV": for TVs whose apps cannot open a server (Samsung Tizen), the phone does it.
 * The TV scans the local network for this tiny HTTP endpoint and asks for the lists with the 4-digit code
 * shown on the phone. No cloud, no account.
 *
 * Security:
 * - only private/LAN client addresses are answered
 * - the lists are released only with the one-time code; 5 wrong codes stop the server
 * - single use; auto-stops after 10 minutes or after success
 */
class SendToTvServer internal constructor(private val sources: SourceRepository) {
    private val _state = MutableStateFlow<SendToTvState>(SendToTvState.Idle)
    val state: CFlow<SendToTvState> = _state.asStateFlow().wrap()

    private var scope: CoroutineScope? = null
    private var server: ServerSocket? = null
    private var code = ""
    private var wrongCodes = 0
    private var used = false
    private var selected: List<Long> = emptyList()
    private var deviceName = "Telefono"
    private val claimLock = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }

    /** Which saved lists the TV will receive (can change while waiting). */
    fun select(sourceIds: List<Long>) { selected = sourceIds }

    /** Starts (or restarts) the endpoint and returns the code to show, or null if offline. */
    @Throws(Throwable::class)
    suspend fun start(sourceIds: List<Long>, device: String = "Telefono"): String? {
        stop()
        selected = sourceIds
        deviceName = device.take(40)
        val ip = localIpAddress() ?: run {
            _state.value = SendToTvState.Failed("Collega il telefono alla stessa rete Wi-Fi del televisore.")
            return null
        }
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val selector = SelectorManager(Dispatchers.IO)
        var socket: ServerSocket? = null
        for (port in PORTS) {
            socket = runCatching { aSocket(selector).tcp().bind("0.0.0.0", port) }.getOrNull()
            if (socket != null) break
        }
        if (socket == null) {
            selector.close()
            _state.value = SendToTvState.Failed("Impossibile avviare l'invio: chiudi le altre app che lo stanno usando e riprova.")
            return null
        }
        val port = (socket.localAddress as InetSocketAddress).port
        code = newCode()
        wrongCodes = 0
        used = false
        server = socket
        scope = newScope
        _state.value = SendToTvState.Waiting(code, "$ip:$port", nowMillis() + TTL_MS)

        val accepting = socket
        newScope.launch {
            while (isActive) {
                val client = runCatching { accepting.accept() }.getOrNull() ?: break
                launch { handle(client) }
            }
        }
        newScope.launch {
            delay(TTL_MS)
            if (_state.value is SendToTvState.Waiting) _state.value = SendToTvState.Failed("Codice scaduto. Riprova.")
            stop(keepState = true)
        }
        newScope.coroutineContext[Job]?.invokeOnCompletion { selector.close() }
        return code
    }

    fun stop(keepState: Boolean = false) {
        runCatching { server?.close() }
        server = null
        scope?.cancel()
        scope = null
        if (!keepState) _state.value = SendToTvState.Idle
    }

    // ------------------------------------------------------------------ HTTP

    private suspend fun handle(socket: Socket) {
        val write = socket.openWriteChannel(autoFlush = false)
        try {
            val remote = socket.remoteAddress as? InetSocketAddress
            val bytes = remote?.resolveAddress()
            if (bytes == null || !PairingServer.isPrivateAddress(bytes)) return
            val request = withTimeout(15_000) { readRequest(socket.openReadChannel()) } ?: return
            write.writeStringUtf8(route(request))
            write.flush()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Malformed request or client gone.
        } finally {
            runCatching { write.flushAndClose() }
            runCatching { socket.close() }
        }
    }

    private data class Request(val method: String, val path: String, val body: String)

    private suspend fun readRequest(input: ByteReadChannel): Request? {
        val requestLine = input.readUTF8Line(MAX_LINE) ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) return null
        var contentLength = 0
        while (true) {
            val line = input.readUTF8Line(MAX_LINE) ?: break
            if (line.isEmpty()) break
            if (line.substringBefore(':').trim().equals("content-length", ignoreCase = true)) {
                contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
            }
        }
        if (contentLength !in 0..MAX_BODY) return null
        val body = if (contentLength > 0) input.readByteArray(contentLength).decodeToString() else ""
        return Request(parts[0].uppercase(), parts[1].substringBefore('?'), body)
    }

    private suspend fun route(req: Request): String = when {
        req.method == "OPTIONS" -> http(204, "")
        req.path == HELLO && req.method == "GET" ->
            http(200, json.encodeToString(HelloResponse.serializer(), HelloResponse("StePlayer", PROTOCOL, deviceName)))
        req.path == CLAIM && req.method == "POST" -> claim(req.body)
        else -> http(404, """{"error":"not found"}""")
    }

    private suspend fun claim(body: String): String = claimLock.withLock {
        if (used || _state.value !is SendToTvState.Waiting) return http(410, """{"error":"Invio già completato o scaduto."}""")
        val sent = runCatching { json.decodeFromString(ClaimRequest.serializer(), body).code.trim() }.getOrDefault("")
        if (sent != code) {
            if (++wrongCodes >= MAX_WRONG_CODES) {
                _state.value = SendToTvState.Failed("Troppi codici sbagliati: invio interrotto per sicurezza.")
                scope?.launch { delay(200); stop(keepState = true) }
            }
            return http(403, """{"error":"Codice errato"}""")
        }
        val lists = selected.mapNotNull { id -> sources.exportInput(id)?.toSent() }
        if (lists.isEmpty()) return http(404, """{"error":"Nessuna lista da inviare."}""")
        used = true
        _state.value = SendToTvState.Sent(lists.size)
        scope?.launch { delay(500); stop(keepState = true) }
        return http(200, json.encodeToString(ClaimResponse.serializer(), ClaimResponse(lists)))
    }

    private fun http(status: Int, body: String): String {
        val reason = when (status) {
            200 -> "OK"; 204 -> "No Content"; 403 -> "Forbidden"; 404 -> "Not Found"; 410 -> "Gone"; else -> "Bad Request"
        }
        return "HTTP/1.1 $status $reason\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${body.encodeToByteArray().size}\r\n" +
            "Cache-Control: no-store\r\n" +
            // The TV app (a local web app) calls this endpoint from its own origin.
            "Access-Control-Allow-Origin: *\r\n" +
            "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
            "Access-Control-Allow-Headers: Content-Type\r\n" +
            "Connection: close\r\n\r\n" + body
    }

    companion object {
        /** The TV scans these ports on the local network. */
        val PORTS = listOf(47620, 47621, 47622)
        const val HELLO = "/steplayer/v1/hello"
        const val CLAIM = "/steplayer/v1/claim"
        private const val PROTOCOL = 1
        private const val TTL_MS = 10 * 60 * 1000L
        private const val MAX_LINE = 8 * 1024
        private const val MAX_BODY = 4 * 1024
        private const val MAX_WRONG_CODES = 5

        private fun newCode(): String {
            while (true) {
                val c = (0 until 4).map { Random.Default.nextInt(10) }.joinToString("")
                if (c.toSet().size > 1) return c // never "0000", "1111"...
            }
        }

        private fun SourceInput.toSent(): SentList = when (this) {
            is SourceInput.Xtream -> SentList("xtream", name, serverUrl = serverUrl, username = username, password = password, userAgent = userAgent)
            is SourceInput.M3u -> SentList("m3u", name, listUrl = listUrl, epgUrl = epgUrl, userAgent = userAgent)
        }
    }
}
