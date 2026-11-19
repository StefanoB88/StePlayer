package com.steplayer.shared.net

import com.steplayer.shared.xtream.XtreamException
import com.steplayer.shared.xtream.XtreamException.Kind
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.http.Url

/** What the device knows about its own connection (from the OS, no extra requests). */
enum class NetworkStatus {
    /** Connected and the OS confirmed Internet works. */
    ONLINE,
    /** Connected to a network (e.g. Wi-Fi) but the OS says Internet doesn't work through it. */
    CONNECTED_NO_INTERNET,
    /** No network at all (airplane mode, Wi-Fi and mobile data off). */
    OFFLINE,
    /** The platform can't tell. */
    UNKNOWN,
}

/** Low-level reason a request failed, read from the platform exception. */
enum class NetworkCause { NO_INTERNET, HOST_NOT_FOUND, REFUSED, UNREACHABLE, TIMEOUT, SECURE_CONNECTION, CLEARTEXT_BLOCKED, INTERRUPTED }

/** Platform part: maps OkHttp/java.net (Android, JVM) or NSURLError (Apple) failures. */
internal expect fun platformNetworkCause(e: Throwable): NetworkCause?

/**
 * Turns any failure of a request to the provider into an [XtreamException] whose message says
 * exactly what is wrong and what to do: device offline, Wi-Fi without Internet, wrong address,
 * server down, port closed, no answer, invalid certificate, wrong path (404), too many requests,
 * provider-side failure (5xx)...
 */
object NetworkErrors {
    /** Set by StePlayerCore from the platform. */
    internal var status: () -> NetworkStatus = { NetworkStatus.UNKNOWN }

    /** Who we were talking to, for the wording. */
    enum class Target { SERVER, LIST, GUIDE }

    fun causeOf(e: Throwable): NetworkCause? {
        var t: Throwable? = e
        var depth = 0
        while (t != null && depth++ < 8) {
            when (t) {
                is ConnectTimeoutException, is SocketTimeoutException, is HttpRequestTimeoutException -> return NetworkCause.TIMEOUT
            }
            t = t.cause
        }
        return platformNetworkCause(e)
    }

    /** A failure before/while receiving the response (no HTTP status). */
    fun fromThrowable(e: Throwable, url: String, target: Target): XtreamException {
        if (e is XtreamException) return e
        val cause = causeOf(e)
        val status = runCatching { status() }.getOrDefault(NetworkStatus.UNKNOWN)
        val where = where(target)
        val host = if (url.isBlank()) "" else hostOf(url)
        /** " («host»)" or nothing when the address isn't known (e.g. errors from the player). */
        val at = if (host.isBlank()) "" else " («$host»)"
        val lowerWhere = where.replaceFirstChar { it.lowercase() }
        fun err(kind: Kind, message: String) = XtreamException(message, kind, e)

        if (status == NetworkStatus.OFFLINE || cause == NetworkCause.NO_INTERNET) {
            return err(Kind.NO_INTERNET, "Il dispositivo non è connesso a Internet. Controlla il Wi-Fi o la rete dati e riprova.")
        }
        val connectLevel = cause == null || cause == NetworkCause.HOST_NOT_FOUND || cause == NetworkCause.UNREACHABLE ||
            cause == NetworkCause.TIMEOUT || cause == NetworkCause.REFUSED
        if (status == NetworkStatus.CONNECTED_NO_INTERNET && connectLevel) {
            return err(Kind.NO_INTERNET, "Il dispositivo è collegato alla rete, ma Internet non sembra funzionare. Controlla il router o la rete dati e riprova.")
        }
        return when (cause) {
            NetworkCause.HOST_NOT_FOUND -> err(Kind.HOST_NOT_FOUND,
                if (host.isBlank()) "Indirizzo del server non trovato. Controlla di averlo scritto correttamente."
                else "Indirizzo non trovato: «$host» non esiste. Controlla di averlo scritto correttamente.")
            NetworkCause.REFUSED -> err(Kind.UNREACHABLE,
                "$where$at rifiuta la connessione: è spento oppure la porta è sbagliata. Controlla la porta o riprova più tardi.")
            NetworkCause.UNREACHABLE -> err(Kind.UNREACHABLE,
                "$where$at non è raggiungibile: potrebbe essere offline. Riprova più tardi o contatta il provider.")
            NetworkCause.TIMEOUT -> err(Kind.TIMEOUT,
                "$where non risponde (tempo scaduto): potrebbe essere sovraccarico o offline. Riprova tra qualche minuto.")
            NetworkCause.SECURE_CONNECTION -> err(Kind.SECURE_CONNECTION,
                "Connessione sicura non riuscita: il certificato di ${if (host.isBlank()) "questo server" else "«$host»"} non è valido o è scaduto. Se il provider lo consente, prova l'indirizzo con http:// invece di https://.")
            NetworkCause.CLEARTEXT_BLOCKED -> err(Kind.SECURE_CONNECTION,
                "Il sistema blocca le connessioni non protette (http) verso ${if (host.isBlank()) "questo server" else "«$host»"}. Prova l'indirizzo con https://.")
            NetworkCause.INTERRUPTED -> err(Kind.NETWORK,
                "La connessione con $lowerWhere si è interrotta. Riprova.")
            NetworkCause.NO_INTERNET -> error("handled above")
            null -> err(Kind.NETWORK,
                "Impossibile contattare $lowerWhere$at. Controlla l'indirizzo e la connessione.")
        }
    }

    /** The server answered with a non-2xx status. */
    fun fromStatus(code: Int, target: Target): XtreamException {
        fun err(kind: Kind, message: String) = XtreamException(message, kind, httpStatus = code)
        val where = where(target)
        return when {
            code == 401 || code == 403 -> when (target) {
                Target.SERVER -> err(Kind.INVALID_CREDENTIALS, "Username o password non validi.")
                else -> err(Kind.INVALID_CREDENTIALS,
                    "Accesso negato (errore $code): il link della ${if (target == Target.LIST) "lista" else "guida TV"} non è più valido o l'abbonamento è scaduto.")
            }
            code == 404 || code == 410 -> when (target) {
                Target.SERVER -> err(Kind.NOT_FOUND,
                    "Il server risponde, ma l'indirizzo non è quello giusto (errore $code). Controlla indirizzo e porta.")
                Target.LIST -> err(Kind.NOT_FOUND,
                    "La lista non si trova più a questo indirizzo (errore $code): il link è sbagliato o scaduto.")
                Target.GUIDE -> err(Kind.NOT_FOUND,
                    "La guida TV non si trova più a questo indirizzo (errore $code).")
            }
            code == 429 -> err(Kind.TOO_MANY_REQUESTS,
                "$where rifiuta le richieste perché sono troppe (errore 429). Aspetta qualche minuto e riprova.")
            code == 502 || code == 503 || code == 504 -> err(Kind.SERVER,
                "$where è temporaneamente fuori servizio (errore $code). Non dipende dall'app né dalla tua rete: riprova più tardi.")
            code in 500..599 -> err(Kind.SERVER,
                "$where ha un problema interno (errore $code). Non dipende dall'app né dalla tua rete: riprova più tardi.")
            else -> err(Kind.SERVER, "$where ha risposto con un errore inatteso (codice $code). Riprova più tardi.")
        }
    }

    private fun where(target: Target) = when (target) {
        Target.SERVER -> "Il server del provider"
        Target.LIST -> "Il server della lista"
        Target.GUIDE -> "Il server della guida TV"
    }

    /** "host" or "host:port" when the port isn't the scheme's default. */
    fun hostOf(url: String): String = runCatching {
        val u = Url(url)
        if (u.specifiedPort == 0 || u.specifiedPort == u.protocol.defaultPort) u.host else "${u.host}:${u.specifiedPort}"
    }.getOrDefault(url.substringAfter("://").substringBefore('/').substringBefore('?'))
}
