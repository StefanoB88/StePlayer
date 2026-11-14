package com.steplayer.shared.playback

import com.steplayer.shared.util.CFlow
import com.steplayer.shared.util.wrap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class LeaseKind { PLAYBACK, DOWNLOAD }

/**
 * Proof that the app is allowed to hold ONE open stream connection to a provider.
 * Release it as soon as the stream is closed (stop, error, exit). Releasing twice is harmless.
 */
class ConnectionLease internal constructor(
    val id: Long,
    val sourceId: Long,
    val kind: LeaseKind,
    val label: String,
    internal val onRevoked: (() -> Unit)?,
    private val guard: ConnectionGuard,
) {
    var isReleased: Boolean = false
        internal set

    fun release() = guard.release(this)
}

data class LeaseInfo(val id: Long, val sourceId: Long, val kind: LeaseKind, val label: String)

sealed interface AcquireResult {
    data class Granted(val lease: ConnectionLease) : AcquireResult

    /** The provider's connection limit is used up by downloads. Offer to pause them, then retry. */
    data class Denied(
        val message: String,
        val maxConnections: Int,
        val busyWith: List<LeaseInfo>,
        val canPauseDownloads: Boolean,
    ) : AcquireResult
}

/**
 * Client-side bookkeeping of open stream connections, per list.
 *
 * Golden rules it enforces:
 * 1. At most ONE playback connection in the whole app. Starting a new one (zapping, opening a
 *    movie while PiP is running, …) first REVOKES the previous one: its `onRevoked` callback must
 *    stop the player and close the network connection synchronously.
 * 2. Playback + downloads never exceed the account's `max_connections`. Playback has priority:
 *    if downloads use the whole budget, the caller is told so it can offer to pause them.
 */
class ConnectionGuard internal constructor(
    private val maxConnectionsOf: suspend (sourceId: Long) -> Int,
) {
    /** Serialises acquisitions (they suspend to read the account's connection limit). */
    private val mutex = Mutex()
    /**
     * Guards [leases] and [nextId]. A plain lock, not [mutex]: [release] and [hasPlayback] are called
     * without suspending, from the main thread (players) and from download threads at the same time.
     */
    private val lock = SynchronizedObject()
    private var nextId = 1L
    private val leases = LinkedHashMap<Long, ConnectionLease>()
    private val _active = MutableStateFlow<List<LeaseInfo>>(emptyList())

    /** Currently open connections (for a debug overlay or the downloads engine). */
    val active: CFlow<List<LeaseInfo>> = _active.asStateFlow().wrap()

    /**
     * Must be called (from the main thread) BEFORE opening a stream.
     * @param onRevoked called if another playback takes over: stop + release the player there.
     */
    @Throws(Throwable::class)
    suspend fun acquirePlayback(sourceId: Long, label: String, onRevoked: () -> Unit): AcquireResult {
        val revoked: List<ConnectionLease>
        val result: AcquireResult
        mutex.withLock {
            revoked = synchronized(lock) {
                leases.values.filter { it.kind == LeaseKind.PLAYBACK }.onEach { removeLocked(it) }
            }
            val budget = maxConnectionsOf(sourceId)
            val downloads = synchronized(lock) {
                leases.values.filter { it.sourceId == sourceId && it.kind == LeaseKind.DOWNLOAD }
            }
            result = if (downloads.size >= budget) {
                AcquireResult.Denied(
                    message = if (budget == 1) {
                        "Il tuo abbonamento consente una sola connessione alla volta ed è occupata da un download."
                    } else {
                        "Hai raggiunto il limite di $budget connessioni del tuo abbonamento: sono tutte occupate dai download."
                    },
                    maxConnections = budget,
                    busyWith = downloads.map { it.info() },
                    canPauseDownloads = true,
                )
            } else {
                AcquireResult.Granted(synchronized(lock) { addLocked(sourceId, LeaseKind.PLAYBACK, label, onRevoked) })
            }
        }
        // Outside the lock: let the old player shut down its connection.
        revoked.forEach { runCatching { it.onRevoked?.invoke() } }
        return result
    }

    /**
     * Called by the download engine before each transfer. Downloads always leave one slot free
     * for playback when the account allows more than one connection.
     */
    @Throws(Throwable::class)
    suspend fun acquireDownload(sourceId: Long, label: String): ConnectionLease? = mutex.withLock {
        val budget = maxConnectionsOf(sourceId)
        synchronized(lock) {
            val used = leases.values.count { it.sourceId == sourceId }
            val downloads = leases.values.count { it.sourceId == sourceId && it.kind == LeaseKind.DOWNLOAD }
            val downloadBudget = if (budget > 1) budget - 1 else 1
            if (used >= budget || downloads >= downloadBudget) null
            else addLocked(sourceId, LeaseKind.DOWNLOAD, label, null)
        }
    }

    /** True if a playback lease is currently held (e.g. to keep a single download from starting). */
    fun hasPlayback(): Boolean = synchronized(lock) { leases.values.any { it.kind == LeaseKind.PLAYBACK } }

    internal fun release(lease: ConnectionLease) {
        // Non-suspending on purpose: callable from onStop/onDestroy/deinit.
        synchronized(lock) {
            if (lease.isReleased) return
            removeLocked(lease)
        }
    }

    private fun addLocked(sourceId: Long, kind: LeaseKind, label: String, onRevoked: (() -> Unit)?): ConnectionLease {
        val lease = ConnectionLease(nextId++, sourceId, kind, label, onRevoked, this)
        leases[lease.id] = lease
        publish()
        return lease
    }

    private fun removeLocked(lease: ConnectionLease) {
        lease.isReleased = true
        leases.remove(lease.id)
        publish()
    }

    private fun publish() {
        _active.value = leases.values.map { it.info() }
    }

    private fun ConnectionLease.info() = LeaseInfo(id, sourceId, kind, label)
}
