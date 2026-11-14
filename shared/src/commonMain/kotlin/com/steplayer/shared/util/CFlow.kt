package com.steplayer.shared.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen

/** Cancellation handle returned to Swift. */
fun interface Closeable {
    fun close()
}

/**
 * A [Flow] that Swift can observe with a simple callback API while Kotlin keeps using it as a
 * regular Flow. Values are delivered on the main thread.
 */
class CFlow<T : Any>(private val origin: Flow<T>) : Flow<T> by origin {
    fun watch(onEach: (T) -> Unit): Closeable {
        // A throwing callback must never take the app down: log it and keep the subscription alive.
        val handler = CoroutineExceptionHandler { _, e -> logCore("CFlow.watch callback failed", e) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + handler)
        origin.onEach { value ->
            try {
                onEach(value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logCore("CFlow.watch callback failed", e)
            }
        }.launchIn(scope)
        return Closeable { scope.cancel() }
    }
}

/**
 * Exposes a flow to the apps. Everything upstream (SQL query, row → model mapping, grouping)
 * runs on [Dispatchers.Default], never in the collector's context (Main on both apps), so
 * mapping thousands of rows can't jank scrolling. Equal consecutive values are dropped (SQLDelight
 * re-runs a query on every write to its tables, even when the result didn't change).
 *
 * Failures upstream are retried briefly, then logged: the flow completes and collectors keep the
 * last value they received instead of crashing (an uncaught exception in viewModelScope or in
 * [CFlow.watch] would kill the app).
 */
fun <T : Any> Flow<T>.wrap(): CFlow<T> = CFlow(offMain())

/** Same as [wrap], but emits [fallback] if the upstream ultimately fails. */
internal fun <T : Any> Flow<T>.wrapOr(fallback: () -> T): CFlow<T> = CFlow(offMain(fallback))

internal fun <T : Any> Flow<T>.offMain(fallback: (() -> T)? = null): Flow<T> = this
    .retryWhen { cause, attempt ->
        if (cause is CancellationException || attempt >= FLOW_RETRIES) return@retryWhen false
        logCore("Flow failed, retrying (${attempt + 1}/$FLOW_RETRIES)", cause)
        delay(FLOW_RETRY_DELAY_MS * (attempt + 1))
        true
    }
    .catch { e ->
        logCore("Flow failed, giving up", e)
        if (fallback != null) emit(fallback())
    }
    .distinctUntilChanged()
    .flowOn(Dispatchers.Default)

private const val FLOW_RETRIES = 2L
private const val FLOW_RETRY_DELAY_MS = 250L
