package com.steplayer.shared.util

import kotlinx.coroutines.CancellationException

/** Minimal logging for failures the core swallows on purpose (stdout → logcat / Xcode console). */
internal fun logCore(message: String, error: Throwable? = null) {
    println("StePlayerCore: $message" + (error?.let { " — ${it::class.simpleName}: ${it.message}" } ?: ""))
}

/**
 * [runCatching] that never swallows coroutine cancellation: a cancelled caller must stop, not
 * carry on with a fallback value (and, e.g., report a sync as "done").
 */
internal inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}
