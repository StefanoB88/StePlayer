package com.steplayer.shared.pairing

import com.steplayer.shared.data.SourceRepository
import com.steplayer.shared.model.SourceInput
import com.steplayer.shared.util.CFlow
import com.steplayer.shared.util.nowMillis
import com.steplayer.shared.util.wrap
import com.steplayer.shared.xtream.XtreamException
import io.ktor.http.parseQueryString
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
import kotlin.random.Random

/** Device's LAN IPv4 address (Wi-Fi / Ethernet), or null if offline. */
internal expect fun localIpAddress(): String?

sealed interface PairingState {
    data object Idle : PairingState
    /** Show [url] as a QR code (and as text for manual typing). */
    data class Waiting(val url: String, val expiresAtMs: Long) : PairingState
    /** The phone submitted the form; credentials are being verified with the provider. */
    data object Verifying : PairingState
    /** List saved and made active: start the sync screen. */
    data class Added(val sourceId: Long) : PairingState
    data class Failed(val message: String) : PairingState
}

/**
 * "Configura la TV col telefono": a tiny HTTP server on the local network that serves a form
 * the user opens by scanning a QR code with their phone. No cloud, no account.
 *
 * Security:
 * - unguessable one-time token in the URL (128 bit); single use
 * - only private/LAN client addresses are answered
 * - auto-stops after 10 minutes, after success, or after too many bad requests
 * - strict headers (no-store, CSP without scripts, no referrer)
 */
class PairingServer internal constructor(private val sources: SourceRepository) {
    private val _state = MutableStateFlow<PairingState>(PairingState.Idle)
    val state: CFlow<PairingState> = _state.asStateFlow().wrap()

    private var scope: CoroutineScope? = null
    private var server: ServerSocket? = null
    private var token: String = ""
    private var used = false
    private var badRequests = 0
    private val submitLock = Mutex()

    /** Starts (or restarts) the server. Returns the URL to encode in the QR code, or null if offline. */
    @Throws(Throwable::class)
    suspend fun start(preferredPort: Int = 8765): String? {
        stop()
        val ip = localIpAddress() ?: run {
            _state.value = PairingState.Failed("Collega la TV alla rete Wi-Fi o via cavo per usare questa funzione.")
            return null
        }
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val selector = SelectorManager(Dispatchers.IO)
        val socket = runCatching { aSocket(selector).tcp().bind("0.0.0.0", preferredPort) }
            .getOrElse { aSocket(selector).tcp().bind("0.0.0.0", 0) }
        val port = (socket.localAddress as InetSocketAddress).port
        token = randomToken()
        used = false
        badRequests = 0
        server = socket
        scope = newScope
        val url = "http://$ip:$port/p/$token"
        val expiresAt = nowMillis() + TTL_MS
        _state.value = PairingState.Waiting(url, expiresAt)

        newScope.launch {
            while (isActive) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                launch { handle(client) }
            }
        }
        newScope.launch {
            delay(TTL_MS)
            if (_state.value is PairingState.Waiting) _state.value = PairingState.Failed("Codice scaduto. Generane uno nuovo.")
            stop(keepState = true)
        }
        newScope.coroutineContext[Job]?.invokeOnCompletion { selector.close() }
        return url
    }

    fun stop(keepState: Boolean = false) {
        runCatching { server?.close() }
        server = null
        scope?.cancel()
        scope = null
        if (!keepState) _state.value = PairingState.Idle
    }

    // ------------------------------------------------------------------ HTTP

    private suspend fun handle(socket: Socket) {
        val write = socket.openWriteChannel(autoFlush = false)
        try {
            if (!isPrivate(socket.remoteAddress)) return
            val request = withTimeout(15_000) { readRequest(socket.openReadChannel()) } ?: return
            val response = route(request)
            write.writeStringUtf8(response)
            write.flush()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Malformed request or client gone: just drop it.
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
            val name = line.substringBefore(':').trim().lowercase()
            if (name == "content-length") contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
        }
        if (contentLength !in 0..MAX_BODY) return null
        val body = if (contentLength > 0) input.readByteArray(contentLength).decodeToString() else ""
        return Request(parts[0].uppercase(), parts[1].substringBefore('?'), body)
    }

    private suspend fun route(req: Request): String {
        val validPath = req.path == "/p/$token" && !used
        if (!validPath) {
            if (req.path != "/favicon.ico" && ++badRequests > MAX_BAD_REQUESTS) {
                _state.value = PairingState.Failed("Troppi tentativi non validi: configurazione interrotta per sicurezza.")
                scope?.launch { delay(200); stop(keepState = true) }
            }
            return http(404, PairingPage.message("Link non valido o scaduto", "Genera un nuovo codice QR dalla TV."))
        }
        return when (req.method) {
            "GET" -> http(200, PairingPage.form(action = "/p/$token"))
            "POST" -> submit(req.body)
            else -> http(405, PairingPage.message("Richiesta non valida", ""))
        }
    }

    private suspend fun submit(body: String): String = submitLock.withLock {
        if (used) return http(404, PairingPage.message("Link già usato", "La lista è già stata aggiunta alla TV."))
        val form = parseQueryString(body)
        val input = PairingPage.toInput(form.entries().associate { it.key to it.value.firstOrNull().orEmpty() })
            ?: return http(400, PairingPage.form("/p/$token", error = "Compila tutti i campi obbligatori."))
        _state.value = PairingState.Verifying
        return try {
            val id = sources.add(input)
            used = true
            _state.value = PairingState.Added(id)
            scope?.launch { delay(500); stop(keepState = true) }
            http(200, PairingPage.message("Fatto!", "La lista è stata aggiunta. Puoi chiudere questa pagina e continuare sulla TV."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val message = (e as? XtreamException)?.message ?: e.message ?: "Impossibile aggiungere la lista."
            _state.value = PairingState.Waiting(currentUrl(), (nowMillis() + TTL_MS))
            http(200, PairingPage.form("/p/$token", error = message, previous = input))
        }
    }

    private fun currentUrl(): String = (_state.value as? PairingState.Waiting)?.url ?: ""

    private fun http(status: Int, html: String): String {
        val reason = when (status) { 200 -> "OK"; 400 -> "Bad Request"; 404 -> "Not Found"; else -> "Method Not Allowed" }
        val bytes = html.encodeToByteArray().size
        return "HTTP/1.1 $status $reason\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: $bytes\r\n" +
            "Cache-Control: no-store\r\n" +
            "Referrer-Policy: no-referrer\r\n" +
            "X-Content-Type-Options: nosniff\r\n" +
            "X-Frame-Options: DENY\r\n" +
            "Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; base-uri 'none'\r\n" +
            "Connection: close\r\n\r\n" + html
    }

    private fun isPrivate(address: io.ktor.network.sockets.SocketAddress): Boolean {
        val inet = address as? InetSocketAddress ?: return false
        val bytes = inet.resolveAddress() ?: return false
        return isPrivateAddress(bytes)
    }

    companion object {
        private const val TTL_MS = 10 * 60 * 1000L
        private const val MAX_LINE = 8 * 1024
        private const val MAX_BODY = 16 * 1024
        private const val MAX_BAD_REQUESTS = 20

        private fun randomToken(): String {
            val alphabet = "abcdefghijkmnpqrstuvwxyz23456789"
            return (1..26).map { alphabet[Random.Default.nextInt(alphabet.length)] }.joinToString("")
        }

        internal fun isPrivateAddress(b: ByteArray): Boolean {
            fun u(i: Int) = b[i].toInt() and 0xff
            return when (b.size) {
                4 -> u(0) == 10 || u(0) == 127 ||
                    (u(0) == 172 && u(1) in 16..31) ||
                    (u(0) == 192 && u(1) == 168) ||
                    (u(0) == 169 && u(1) == 254)
                16 -> {
                    val v4mapped = (0..9).all { u(it) == 0 } && u(10) == 0xff && u(11) == 0xff
                    when {
                        v4mapped -> isPrivateAddress(b.copyOfRange(12, 16))
                        (0..14).all { u(it) == 0 } && u(15) == 1 -> true // ::1
                        (u(0) and 0xfe) == 0xfc -> true // fc00::/7 unique local
                        u(0) == 0xfe && (u(1) and 0xc0) == 0x80 -> true // fe80::/10 link local
                        else -> false
                    }
                }
                else -> false
            }
        }
    }
}
