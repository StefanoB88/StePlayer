package com.steplayer.shared.playback

enum class PlaybackErrorKind {
    MAX_CONNECTIONS, ACCOUNT_EXPIRED, UNAUTHORIZED, NOT_FOUND, SERVER, NETWORK, TIMEOUT, STALLED,
    UNSUPPORTED_FORMAT, UNKNOWN,
}

/** A playback failure translated into something the user understands, plus what to do next. */
data class PlaybackError(
    val kind: PlaybackErrorKind,
    val title: String,
    val message: String,
    val retryable: Boolean,
)

/** Maps raw player/HTTP failures to [PlaybackError]. Platform players report what they know. */
object PlaybackErrors {

    fun fromHttpStatus(code: Int, maxConnections: Int? = null): PlaybackError = when (code) {
        // Xtream servers answer 403/458/509 (some 406/429/503) when the connection limit is reached.
        403, 406, 429, 458, 509 -> maxConnections(maxConnections)
        401 -> PlaybackError(PlaybackErrorKind.UNAUTHORIZED, "Accesso negato",
            "Il server non accetta più le credenziali. Controlla username e password nelle impostazioni.", retryable = false)
        404, 410 -> PlaybackError(PlaybackErrorKind.NOT_FOUND, "Contenuto non disponibile",
            "Il provider non trova questo contenuto. Prova ad aggiornare la lista.", retryable = false)
        in 500..599 -> PlaybackError(PlaybackErrorKind.SERVER, "Problema del provider",
            "Il server del provider non risponde correttamente ($code).", retryable = true)
        else -> unknown("HTTP $code")
    }

    fun maxConnections(max: Int?) = PlaybackError(
        PlaybackErrorKind.MAX_CONNECTIONS, "Troppe connessioni",
        buildString {
            append("Il provider ha rifiutato la connessione: ")
            append(if (max != null) "il tuo abbonamento consente $max ${if (max == 1) "connessione" else "connessioni"} " else "hai raggiunto il limite di connessioni ")
            append("e risulta già in uso (su questo o un altro dispositivo). ")
            append("Il server può impiegare qualche secondo a liberarla: riprovo automaticamente.")
        },
        retryable = true,
    )

    fun network() = PlaybackError(PlaybackErrorKind.NETWORK, "Connessione assente",
        "Controlla la connessione a Internet. Riprovo appena torna disponibile.", retryable = true)

    fun timeout() = PlaybackError(PlaybackErrorKind.TIMEOUT, "Il server non risponde",
        "Il provider è lento a rispondere. Riprovo automaticamente.", retryable = true)

    fun stalled() = PlaybackError(PlaybackErrorKind.STALLED, "Riproduzione bloccata",
        "Lo stream si è fermato. Riconnessione in corso…", retryable = true)

    fun unsupported(container: String?) = PlaybackError(PlaybackErrorKind.UNSUPPORTED_FORMAT, "Formato non supportato",
        "Questo dispositivo non riesce a riprodurre il formato${container?.let { " .$it" } ?: ""}.", retryable = false)

    fun expired() = PlaybackError(PlaybackErrorKind.ACCOUNT_EXPIRED, "Abbonamento scaduto",
        "Il tuo abbonamento risulta scaduto o disattivato dal provider.", retryable = false)

    fun unknown(detail: String? = null) = PlaybackError(PlaybackErrorKind.UNKNOWN, "Impossibile riprodurre",
        "Si è verificato un errore${detail?.let { " ($it)" } ?: ""}. Riprova tra poco.", retryable = true)
}

/**
 * Backoff for automatic reconnection. One instance per playback attempt; call [reset] once
 * the stream plays fine for a while. Returns null when it's time to stop and show the error UI.
 */
class RetryPolicy(private val isLive: Boolean) {
    private var attempt = 0

    fun nextDelayMs(error: PlaybackError): Long? {
        if (!error.retryable) return null
        val schedule = when (error.kind) {
            // The provider needs time to notice the old connection is gone.
            PlaybackErrorKind.MAX_CONNECTIONS -> longArrayOf(4_000, 10_000, 20_000)
            PlaybackErrorKind.NETWORK -> longArrayOf(2_000, 4_000, 8_000, 15_000, 30_000)
            else -> if (isLive) longArrayOf(1_000, 2_000, 4_000, 8_000, 15_000) else longArrayOf(1_500, 4_000)
        }
        return schedule.getOrNull(attempt++)
    }

    val attempts: Int get() = attempt

    fun reset() { attempt = 0 }
}

/**
 * Detects a frozen stream: buffering (or position not advancing while "playing") for too long.
 * Feed it from a 1-second ticker; when [tick] returns true, treat it as [PlaybackErrors.stalled].
 */
class StallWatchdog(
    private val isLive: Boolean,
    private val stallTimeoutMs: Long = if (isLive) 12_000 else 25_000,
) {
    private var lastPosition = -1L
    private var lastAdvanceAt = -1L

    /** @return true if the stream must be considered stalled. */
    fun tick(nowMs: Long, positionMs: Long, wantsToPlay: Boolean): Boolean {
        if (!wantsToPlay) { lastAdvanceAt = nowMs; lastPosition = positionMs; return false }
        if (lastAdvanceAt < 0 || positionMs != lastPosition) {
            lastPosition = positionMs
            lastAdvanceAt = nowMs
            return false
        }
        return nowMs - lastAdvanceAt >= stallTimeoutMs
    }

    fun reset() { lastPosition = -1; lastAdvanceAt = -1 }
}
