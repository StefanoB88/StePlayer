package com.steplayer.shared.playback

import com.steplayer.shared.data.SourceRepository
import com.steplayer.shared.model.SourceType
import com.steplayer.shared.xtream.XtreamClient
import com.steplayer.shared.xtream.XtreamException
import io.ktor.client.HttpClient

/**
 * Entry point for everything that keeps playback reliable:
 * connection leases, error translation, reconnection policy, stall detection, diagnosis.
 */
class PlaybackManager internal constructor(
    private val sources: SourceRepository,
    private val http: HttpClient,
    private val defaultUserAgent: String,
) {
    val connections = ConnectionGuard { sourceId -> maxConnections(sourceId) }

    private suspend fun maxConnections(sourceId: Long): Int {
        val source = sources.byId(sourceId) ?: return 1
        return when (source.type) {
            SourceType.XTREAM -> (source.maxConnections ?: 1).coerceAtLeast(1)
            SourceType.M3U -> 4 // unknown limit: be reasonable but don't block the user
        }
    }

    fun retryPolicy(isLive: Boolean) = RetryPolicy(isLive)
    fun stallWatchdog(isLive: Boolean) = StallWatchdog(isLive)

    /** Translates an HTTP status coming from the player into a user-facing error. */
    @Throws(Throwable::class)
    suspend fun errorForHttpStatus(sourceId: Long, status: Int): PlaybackError =
        PlaybackErrors.fromHttpStatus(status, sources.byId(sourceId)?.maxConnections)

    /**
     * Asks the Xtream server what is going on after an ambiguous failure (403, connection
     * dropped right away, unknown player error): expired account? all connections busy?
     * Returns null when nothing more specific is known (or for M3U lists).
     */
    @Throws(Throwable::class)
    suspend fun diagnose(sourceId: Long): PlaybackError? {
        val source = sources.byId(sourceId) ?: return null
        val creds = sources.xtreamCredentials(source) ?: return null
        return try {
            val account = XtreamClient(http, creds, source.userAgent ?: defaultUserAgent).login().account
            val max = account.maxConnections
            val active = account.activeConnections
            when {
                account.status != null && !account.status.equals("Active", ignoreCase = true) -> PlaybackErrors.expired()
                max != null && active != null && active >= max -> PlaybackErrors.maxConnections(max)
                else -> null
            }
        } catch (e: XtreamException) {
            when (e.kind) {
                XtreamException.Kind.ACCOUNT_EXPIRED -> PlaybackErrors.expired()
                XtreamException.Kind.INVALID_CREDENTIALS -> PlaybackErrors.fromHttpStatus(401)
                else -> if (e.kind.isConnectivity) PlaybackErrors.network() else null
            }
        }
    }
}
