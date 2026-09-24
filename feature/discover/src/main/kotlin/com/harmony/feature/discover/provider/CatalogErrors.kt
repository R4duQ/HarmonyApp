package com.harmony.feature.discover.provider

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** What went wrong with a source, in the terms the user can act on. */
enum class SourceProblem {
    /** No route to the internet: DNS or connection failed before any answer. */
    OFFLINE,
    TIMEOUT,
    /** The service asked us to slow down (HTTP 429, Deezer quota, Apple 403). */
    RATE_LIMITED,
    /** A login, verification or token is no longer valid. */
    AUTH_EXPIRED,
    /** The service answered with a server error or said it is busy. */
    UNAVAILABLE,
    /** The answer could not be understood. */
    BAD_RESPONSE,
}

class SourceException(
    val source: String,
    val problem: SourceProblem,
    val retryAfterMs: Long? = null,
    cause: Throwable? = null,
) : IOException(SourceErrors.message(source, problem), cause)

object SourceErrors {
    fun message(source: String, problem: SourceProblem): String = when (problem) {
        SourceProblem.OFFLINE -> "$source can't be reached. Check your internet connection."
        SourceProblem.TIMEOUT -> "$source is taking too long to answer."
        SourceProblem.RATE_LIMITED -> "$source asked Harmony to slow down. Try again in a minute."
        SourceProblem.AUTH_EXPIRED -> "$source needs you to sign in or verify again."
        SourceProblem.UNAVAILABLE -> "$source is unavailable right now."
        SourceProblem.BAD_RESPONSE -> "$source sent an answer Harmony couldn't read."
    }

    fun fromHttp(source: String, host: String, code: Int, retryAfter: String?): SourceException {
        val wait = retryAfter?.trim()?.toLongOrNull()?.times(1_000)
        val problem = when {
            code == 429 -> SourceProblem.RATE_LIMITED
            // The iTunes Search API answers 403 when a client exceeds its request budget.
            code == 403 && host == "itunes.apple.com" -> SourceProblem.RATE_LIMITED
            code == 401 || code == 403 -> SourceProblem.AUTH_EXPIRED
            code in 500..599 -> SourceProblem.UNAVAILABLE
            else -> SourceProblem.BAD_RESPONSE
        }
        return SourceException(source, problem, wait)
    }

    /**
     * Deezer reports errors with HTTP 200 and an `error` object:
     * code 4 = quota exceeded, 700 = service busy, 200/300 = OAuth.
     * 800 ("no data") is not an error for us: it means an empty result.
     */
    fun fromDeezerBody(json: JSONObject): SourceException? {
        val error = json.optJSONObject("error") ?: return null
        val problem = when (error.optInt("code", -1)) {
            800 -> return null
            4 -> SourceProblem.RATE_LIMITED
            700 -> SourceProblem.UNAVAILABLE
            200, 300 -> SourceProblem.AUTH_EXPIRED
            else -> SourceProblem.BAD_RESPONSE
        }
        return SourceException("Deezer", problem, if (problem == SourceProblem.RATE_LIMITED) 5_000 else null)
    }

    fun fromThrowable(source: String, error: Throwable): SourceException = when (error) {
        is SourceException -> error
        is UnknownHostException, is ConnectException, is NoRouteToHostException -> SourceException(source, SourceProblem.OFFLINE, cause = error)
        is SocketTimeoutException, is InterruptedIOException -> SourceException(source, SourceProblem.TIMEOUT, cause = error)
        is SSLException -> SourceException(source, SourceProblem.UNAVAILABLE, cause = error)
        is org.json.JSONException -> SourceException(source, SourceProblem.BAD_RESPONSE, cause = error)
        is IOException -> SourceException(source, SourceProblem.UNAVAILABLE, cause = error)
        else -> SourceException(source, SourceProblem.BAD_RESPONSE, cause = error)
    }
}

/**
 * Limited retry with exponential backoff and jitter. Only problems that can
 * clear by themselves are retried; a "Retry-After" longer than we are willing
 * to block is reported instead of waited out.
 */
data class RetryPolicy(
    val attempts: Int = 3,
    val baseDelayMs: Long = 600,
    val factor: Double = 2.5,
    val maxWaitMs: Long = 8_000,
) {
    fun shouldRetry(e: SourceException, attempt: Int): Boolean = attempt < attempts && when (e.problem) {
        SourceProblem.TIMEOUT, SourceProblem.UNAVAILABLE -> true
        SourceProblem.RATE_LIMITED -> (e.retryAfterMs ?: 0) <= maxWaitMs
        else -> false
    }

    fun delayFor(e: SourceException, attempt: Int, jitter: Double): Long {
        val backoff = (baseDelayMs * Math.pow(factor, (attempt - 1).toDouble()) * (0.8 + 0.4 * jitter)).toLong()
        return maxOf(backoff, e.retryAfterMs ?: 0).coerceAtMost(maxWaitMs)
    }

    suspend fun <T> run(
        source: String,
        wait: suspend (Long) -> Unit = { delay(it) },
        jitter: () -> Double = { Math.random() },
        block: suspend () -> T,
    ): T {
        var attempt = 1
        while (true) {
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val failure = SourceErrors.fromThrowable(source, e)
                if (!shouldRetry(failure, attempt)) throw failure
                wait(delayFor(failure, attempt, jitter()))
                attempt++
            }
        }
    }
}

enum class SourceState { READY, LIMITED, DOWN, NOT_USED }

/** What the Preferences step shows per source. */
data class SourceStatus(val name: String, val role: String, val state: SourceState, val note: String = "")
