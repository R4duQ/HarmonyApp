package com.harmony.app

import android.util.Log
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.harmony.core.common.crash.DiagnosticsLog
import com.harmony.domain.online.SeedTrack
import com.harmony.domain.online.SongGuess
import com.harmony.domain.online.SongGuessPrompt
import com.harmony.domain.online.SongGuesser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [SongGuesser] on Gemini Nano, the model Android keeps on the phone
 * (AICore, through ML Kit's Prompt API): the names of a file go in, a guess
 * of the song comes out, and nothing leaves the phone.
 *
 * Only some phones have it (recent Pixels, Galaxy S24 and later, a few
 * others). Elsewhere, or while the system is still downloading the model,
 * there is simply no guess and the mix goes on to the artist's radio. When
 * the model can be downloaded, asking once starts the download (the system's,
 * once for every app); the next mixes can use it.
 */
@Singleton
class GeminiNanoGuesser @Inject constructor() : SongGuesser {
    private val lock = Mutex()
    private var model: GenerativeModel? = null
    private var unavailable = false
    private val downloadStarted = AtomicBoolean(false)
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun guess(seed: SeedTrack): List<SongGuess> = lock.withLock {
        if (unavailable) return@withLock emptyList()
        val m = model ?: runCatching { Generation.getClient() }.getOrNull()?.also { model = it }
        if (m == null) {
            unavailable = true
            note("no client")
            return@withLock emptyList()
        }
        when (val status = runCatching { m.checkStatus() }.getOrDefault(FeatureStatus.UNAVAILABLE)) {
            FeatureStatus.AVAILABLE -> Unit
            FeatureStatus.DOWNLOADABLE -> {
                startDownload(m)
                return@withLock emptyList()
            }
            FeatureStatus.DOWNLOADING -> {
                note("model downloading")
                return@withLock emptyList()
            }
            else -> {
                // This phone has no Gemini Nano: not asked again until the app restarts.
                unavailable = true
                note("unavailable (status $status)")
                return@withLock emptyList()
            }
        }
        val request = generateContentRequest(TextPart(SongGuessPrompt.build(seed))) {
            temperature = 0.1f
            topK = 8
            candidateCount = 1
            maxOutputTokens = 80
        }
        val answer = try {
            withTimeoutOrNull(TIMEOUT_MS) { m.generateContent(request) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Busy, over quota, or the background limits: no guess this time.
            note("no answer (${e.javaClass.simpleName})")
            null
        }
        val text = answer?.candidates?.firstOrNull()?.text.orEmpty()
        SongGuessPrompt.parse(text)
    }

    private fun startDownload(m: GenerativeModel) {
        if (!downloadStarted.compareAndSet(false, true)) return
        note("model downloadable: downloading")
        background.launch {
            runCatching { m.download().collect { } }
                .onFailure { downloadStarted.set(false); note("download failed (${it.javaClass.simpleName})") }
        }
    }

    private fun note(line: String) {
        Log.i(TAG, line)
        DiagnosticsLog.line("ONLINE_MIX", "ai $line")
    }

    private companion object {
        const val TAG = "ONLINE_MIX"
        const val TIMEOUT_MS = 15_000L
    }
}
