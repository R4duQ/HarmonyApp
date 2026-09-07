package com.harmony.core.common

/**
 * Lightweight result wrapper for operations that can fail across layer
 * boundaries. Kept deliberately minimal; use cases return this instead of
 * throwing so the UI layer never needs try/catch.
 */
sealed interface HarmonyResult<out T> {
    data class Success<T>(val value: T) : HarmonyResult<T>
    data class Failure(val error: Throwable) : HarmonyResult<Nothing>

    fun <R> map(transform: (T) -> R): HarmonyResult<R> = when (this) {
        is Success -> Success(transform(value))
        is Failure -> this
    }
}

inline fun <T> runCatchingResult(block: () -> T): HarmonyResult<T> =
    try {
        HarmonyResult.Success(block())
    } catch (t: Throwable) {
        if (t is kotlinx.coroutines.CancellationException) throw t
        HarmonyResult.Failure(t)
    }
