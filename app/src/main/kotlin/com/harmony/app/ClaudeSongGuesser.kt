package com.harmony.app

import android.util.Log
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.beta.messages.BetaJsonOutputFormat
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.MessageCreateParams
import com.harmony.core.common.crash.DiagnosticsLog
import com.harmony.core.datastore.ClaudeKeyStore
import com.harmony.domain.online.SeedTrack
import com.harmony.domain.online.SongGuess
import com.harmony.domain.online.SongGuessPrompt
import com.harmony.domain.online.SongGuesser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.json.JSONObject
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [SongGuesser] on Claude, with the listener's own Anthropic API key (Settings):
 * the guess that works on every phone, for songs nothing on the phone found.
 *
 * Sent: the file's title, artist and album tags and its length, nothing else
 * (no audio, no other song). Claude answers in a fixed JSON form, so the
 * answer is read, never interpreted. A refusal is no guess (the request asks
 * the API to fall back to another model first); a rejected key, an empty
 * balance or a rate limit is reported once to Settings and logged.
 */
@Singleton
class ClaudeSongGuesser internal constructor(
    private val apiKey: () -> String?,
    private val reportProblem: (String?) -> Unit,
    /** Tests: a local server in place of the API. */
    private val baseUrl: String? = null,
) : SongGuesser {
    @Inject constructor(keys: ClaudeKeyStore) : this(keys::key, keys::reportProblem)

    private var client: AnthropicClient? = null
    private var clientKey: String? = null

    private val schema: BetaJsonOutputFormat.Schema = BetaJsonOutputFormat.Schema.builder()
        .putAllAdditionalProperties(SongGuessPrompt.SCHEMA.mapValues { JsonValue.from(it.value) })
        .build()

    override suspend fun guess(seed: SeedTrack): List<SongGuess> = runInterruptible(Dispatchers.IO) {
        val key = apiKey() ?: return@runInterruptible emptyList()
        val params = MessageCreateParams.builder()
            .model(MODEL)
            // Adaptive thinking is always on for this model; low effort keeps a name lookup quick and cheap.
            .maxTokens(4_000L)
            // A refusal falls back server-side to another model instead of ending with no answer.
            .addBeta("server-side-fallback-2026-07-01")
            .fallbacksDefault()
            .system(SongGuessPrompt.SYSTEM)
            .addUserMessage(SongGuessPrompt.describe(seed))
            .outputConfig(
                BetaOutputConfig.builder()
                    .effort(BetaOutputConfig.Effort.LOW)
                    .format(BetaJsonOutputFormat.builder().schema(schema).build())
                    .build(),
            )
            .build()
        try {
            val message = clientFor(key).beta().messages().create(params)
            reportProblem(null)
            if (message.stopReason().orElse(null) == BetaStopReason.REFUSAL) {
                note("declined")
                return@runInterruptible emptyList()
            }
            val text = message.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("")
            read(text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: UnauthorizedException) {
            reportProblem("Anthropic rejected the key")
            note("key rejected (401)")
            emptyList()
        } catch (e: PermissionDeniedException) {
            reportProblem("The key may not use this model")
            note("permission denied (403)")
            emptyList()
        } catch (e: RateLimitException) {
            note("rate limited (429)")
            emptyList()
        } catch (e: AnthropicServiceException) {
            // 400 with "credit balance is too low", overloaded (529), and other answers of the API.
            if (e.statusCode() == 400 && e.message.orEmpty().contains("credit", ignoreCase = true)) {
                reportProblem("No credit left on the Anthropic account")
            }
            note("error ${e.statusCode()}")
            emptyList()
        } catch (e: AnthropicIoException) {
            note("no connection (${e.javaClass.simpleName})")
            emptyList()
        }
    }

    /** {"guesses": [{"artist": ..., "title": ...}]} to guesses; anything else is no guess. */
    private fun read(text: String): List<SongGuess> = runCatching {
        val guesses = JSONObject(text).getJSONArray("guesses")
        val pairs = (0 until guesses.length()).map { i ->
            guesses.getJSONObject(i).let { it.optString("artist") to it.optString("title") }
        }
        SongGuessPrompt.fromPairs(pairs)
    }.getOrElse {
        note("answer not understood")
        emptyList()
    }

    @Synchronized
    private fun clientFor(key: String): AnthropicClient {
        client?.takeIf { clientKey == key }?.let { return it }
        client?.close()
        return AnthropicOkHttpClient.builder()
            .apiKey(key)
            .apply { this@ClaudeSongGuesser.baseUrl?.let { baseUrl(it) } }
            .timeout(Duration.ofSeconds(45))
            .maxRetries(1)
            .build()
            .also { client = it; clientKey = key }
    }

    private fun note(line: String) {
        Log.i("ONLINE_MIX", "claude $line")
        DiagnosticsLog.line("ONLINE_MIX", "claude $line")
    }

    private companion object {
        const val MODEL = "claude-opus-5-5"
    }
}
