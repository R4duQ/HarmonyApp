package com.harmony.core.dsp

import com.harmony.core.model.AuditCheckKind
import com.harmony.core.model.AuditCheckResult
import com.harmony.core.model.AuditStatus
import com.harmony.core.model.BrainAuditReport
import com.harmony.core.model.ReasonTag
import com.harmony.core.model.SessionEvent
import com.harmony.core.model.TrackDecisionTrace
import com.harmony.core.model.VectorQuality
import java.io.Closeable

/**
 * The JNI surface of SmartShuffleEngine (shuffle_jni.cpp registers these by
 * name in JNI_OnLoad). Use [NativeShuffleEngine] and [NativeAcousticExtractor];
 * this object only holds the raw calls.
 */
object SmartShuffleNative {
    const val DIMS = 24
    const val ZONES = 10
    internal const val TRACE_FLOATS = 8
    internal const val TRACE_INTS = 3
    internal const val AUDIT_FLOATS = 5 * 2 + 7
    internal const val AUDIT_INTS = 5 * 2 + 6

    /** False when the library could not be loaded (unit tests on the JVM, a broken install). */
    val available: Boolean = runCatching { System.loadLibrary("harmonyshuffle") }.isSuccess

    @JvmStatic external fun nativeCreate(): Long
    @JvmStatic external fun nativeDestroy(handle: Long)
    @JvmStatic external fun nativeSetLibrary(
        handle: Long,
        ids: LongArray,
        raw: FloatArray,
        quality: ByteArray,
        artists: IntArray,
        albums: IntArray,
    ): Int
    @JvmStatic external fun nativeSetTrackStats(
        handle: Long,
        completed: IntArray,
        skipped: IntArray,
        lastPlayed: LongArray,
        favorite: ByteArray,
    )
    @JvmStatic external fun nativeSetSteering(handle: Long, perTrack: FloatArray?, weight: Float)
    @JvmStatic external fun nativeAddTransitions(handle: Long, from: LongArray, to: LongArray, weight: FloatArray)
    @JvmStatic external fun nativeClearTransitions(handle: Long)
    @JvmStatic external fun nativeMarkovProbability(handle: Long, from: Long, to: Long): Float
    @JvmStatic external fun nativeSetZoneBandit(handle: Long, pulls: FloatArray, rewards: FloatArray)
    @JvmStatic external fun nativeGetZoneBandit(handle: Long, pulls: FloatArray, rewards: FloatArray)
    @JvmStatic external fun nativeSetWeights(handle: Long, values: FloatArray, minArtistSpacing: Int)
    @JvmStatic external fun nativeRepairedInputs(handle: Long): Int
    @JvmStatic external fun nativeOnTrackStarted(handle: Long, id: Long): Int
    @JvmStatic external fun nativeOnPlaybackOutcome(handle: Long, id: Long, fraction: Float, nowMs: Long, pick: Boolean): Int
    @JvmStatic external fun nativeOnManualSelect(handle: Long, id: Long): Int
    @JvmStatic external fun nativeResetSession(handle: Long)
    @JvmStatic external fun nativeSessionVector(handle: Long, out: FloatArray): Boolean
    @JvmStatic external fun nativeLastLearnedTransition(handle: Long, ids: LongArray): Float
    @JvmStatic external fun nativeLastBanditUpdate(handle: Long, out: FloatArray): Boolean
    @JvmStatic external fun nativeGenerateQueue(
        handle: Long,
        currentId: Long,
        prefix: LongArray,
        exclude: LongArray,
        replaced: LongArray,
        count: Int,
        nowMs: Long,
        seed: Long,
        out: LongArray,
    ): Int
    @JvmStatic external fun nativeJourneyStep(
        handle: Long,
        fromId: Long,
        toId: Long,
        t: Float,
        previousId: Long,
        exclude: LongArray,
        nowMs: Long,
        seed: Long,
    ): Long
    @JvmStatic external fun nativeTraceCount(handle: Long): Int
    @JvmStatic external fun nativeTraces(handle: Long, ids: LongArray, floats: FloatArray, ints: IntArray): Array<String>
    @JvmStatic external fun nativeAudit(handle: Long, floats: FloatArray, ints: IntArray): Array<String>
    @JvmStatic external fun nativeRunSyntheticSimulation(): String
    @JvmStatic external fun nativeExtractorCreate(sampleRate: Int): Long
    @JvmStatic external fun nativeExtractorProcess(handle: Long, samples: FloatArray, count: Int)
    @JvmStatic external fun nativeExtractorFinish(handle: Long, out: FloatArray): Boolean
    @JvmStatic external fun nativeExtractorDestroy(handle: Long)
}

/** The scorer's knobs, mirrored from the C++ Weights. */
data class ShuffleWeights(
    val similarity: Float = 1.00f,
    val markov: Float = 0.30f,
    val affinity: Float = 0.30f,
    val explore: Float = 0.20f,
    /** Efraimidis-Spirakis softness: weight = exp((score - best) / temperature). */
    val temperature: Float = 0.12f,
    val jerkThreshold: Float = 1.25f,
    val echoThreshold: Float = 0.97f,
    val minArtistSpacing: Int = 3,
)

/**
 * One Smart Shuffle engine. Thread-safe (the native side locks); [close]
 * frees it. Ids are library song ids.
 */
class NativeShuffleEngine : Closeable {
    private var handle: Long = SmartShuffleNative.nativeCreate()
    private val lock = Any()

    private inline fun <T> withHandle(block: (Long) -> T): T = synchronized(lock) {
        check(handle != 0L) { "Smart Shuffle engine already closed" }
        block(handle)
    }

    /**
     * [raw] is 24 floats per track (NaN = unknown), [quality] one
     * [VectorQuality] ordinal per track. Artist and album ids are interned by
     * the caller; negative means unknown.
     */
    fun setLibrary(ids: LongArray, raw: FloatArray, quality: ByteArray, artists: IntArray, albums: IntArray): Int =
        withHandle { SmartShuffleNative.nativeSetLibrary(it, ids, raw, quality, artists, albums) }

    /** Aligned with the order given to [setLibrary]. */
    fun setTrackStats(completed: IntArray, skipped: IntArray, lastPlayed: LongArray, favorite: ByteArray) =
        withHandle { SmartShuffleNative.nativeSetTrackStats(it, completed, skipped, lastPlayed, favorite) }

    /** 0..1 per track (energy target, mood), or null to clear. */
    fun setSteering(perTrack: FloatArray?, weight: Float) =
        withHandle { SmartShuffleNative.nativeSetSteering(it, perTrack, weight) }

    fun addTransitions(from: LongArray, to: LongArray, weight: FloatArray) =
        withHandle { SmartShuffleNative.nativeAddTransitions(it, from, to, weight) }

    fun clearTransitions() = withHandle { SmartShuffleNative.nativeClearTransitions(it) }

    fun markovProbability(from: Long, to: Long): Float =
        withHandle { SmartShuffleNative.nativeMarkovProbability(it, from, to) }

    /** 10 x 10, row = the session's zone (context), column = the pick's zone (arm). */
    fun setZoneBandit(pulls: FloatArray, rewards: FloatArray) =
        withHandle { SmartShuffleNative.nativeSetZoneBandit(it, pulls, rewards) }

    fun zoneBandit(): Pair<FloatArray, FloatArray> = withHandle {
        val pulls = FloatArray(SmartShuffleNative.ZONES * SmartShuffleNative.ZONES)
        val rewards = FloatArray(pulls.size)
        SmartShuffleNative.nativeGetZoneBandit(it, pulls, rewards)
        pulls to rewards
    }

    fun setWeights(w: ShuffleWeights) = withHandle {
        SmartShuffleNative.nativeSetWeights(
            it,
            floatArrayOf(w.similarity, w.markov, w.affinity, w.explore, w.temperature, w.jerkThreshold, w.echoThreshold),
            w.minArtistSpacing,
        )
    }

    fun repairedInputs(): Int = withHandle { SmartShuffleNative.nativeRepairedInputs(it) }

    fun onTrackStarted(id: Long): SessionEvent =
        event(withHandle { SmartShuffleNative.nativeOnTrackStarted(it, id) })

    fun onPlaybackOutcome(id: Long, fraction: Float, nowMs: Long, shufflePick: Boolean): SessionEvent =
        event(withHandle { SmartShuffleNative.nativeOnPlaybackOutcome(it, id, fraction, nowMs, shufflePick) })

    fun onManualSelect(id: Long): SessionEvent =
        event(withHandle { SmartShuffleNative.nativeOnManualSelect(it, id) })

    fun resetSession() = withHandle { SmartShuffleNative.nativeResetSession(it) }

    /** S_t, or null before the first song. */
    fun sessionVector(): FloatArray? = withHandle {
        val out = FloatArray(SmartShuffleNative.DIMS)
        if (SmartShuffleNative.nativeSessionVector(it, out)) out else null
    }

    /** The Markov transition the last outcome taught (from, to, weight), for persistence. */
    fun lastLearnedTransition(): Triple<Long, Long, Float>? = withHandle {
        val ids = LongArray(2)
        val delta = SmartShuffleNative.nativeLastLearnedTransition(it, ids)
        if (delta > 0f) Triple(ids[0], ids[1], delta) else null
    }

    /** The bandit cell the last outcome rewarded (context, arm, reward), for persistence. */
    fun lastBanditUpdate(): Triple<Int, Int, Float>? = withHandle {
        val out = FloatArray(3)
        if (SmartShuffleNative.nativeLastBanditUpdate(it, out)) Triple(out[0].toInt(), out[1].toInt(), out[2]) else null
    }

    /** Builds [count] tracks after [currentId] and [prefix]; see SmartShuffleEngine::generateQueue. */
    fun generateQueue(
        currentId: Long,
        prefix: LongArray,
        exclude: LongArray,
        replacedTail: LongArray,
        count: Int,
        nowMs: Long,
        seed: Long,
    ): LongArray = withHandle {
        val out = LongArray(count.coerceAtLeast(0))
        val written = SmartShuffleNative.nativeGenerateQueue(it, currentId, prefix, exclude, replacedTail, count, nowMs, seed, out)
        out.copyOf(written)
    }

    /** One Sonic Adventure step, or null. */
    fun journeyStep(fromId: Long, toId: Long, t: Float, previousId: Long, exclude: LongArray, nowMs: Long, seed: Long): Long? =
        withHandle { SmartShuffleNative.nativeJourneyStep(it, fromId, toId, t, previousId, exclude, nowMs, seed) }
            .takeIf { it >= 0 }

    /** The TrackDecisionTraces of the last queue or journey step. */
    fun lastTraces(): List<TrackDecisionTrace> = withHandle { h ->
        val count = SmartShuffleNative.nativeTraceCount(h)
        if (count == 0) return@withHandle emptyList()
        val ids = LongArray(count)
        val floats = FloatArray(count * SmartShuffleNative.TRACE_FLOATS)
        val ints = IntArray(count * SmartShuffleNative.TRACE_INTS)
        val reasons = SmartShuffleNative.nativeTraces(h, ids, floats, ints)
        List(minOf(count, reasons.size)) { i ->
            val f = i * SmartShuffleNative.TRACE_FLOATS
            val n = i * SmartShuffleNative.TRACE_INTS
            TrackDecisionTrace(
                trackId = ids[i],
                rawScore = floats[f],
                vibeSimilarityPct = floats[f + 1],
                markovHabitPct = floats[f + 2],
                userAffinityPct = floats[f + 3],
                explorationBonusPct = floats[f + 4],
                recencyPenaltyMultiplier = floats[f + 5],
                transitionSmoothnessScore = floats[f + 6],
                cosineSimilarity = floats[f + 7],
                zone = ints[n],
                quality = VectorQuality.entries.getOrElse(ints[n + 1]) { VectorQuality.NONE },
                reason = ReasonTag.entries.getOrElse(ints[n + 2]) { ReasonTag.VIBE_MATCH },
                primaryReasonTag = reasons[i],
            )
        }
    }

    /** The BrainAuditReport of the last queue or journey step. */
    fun lastAudit(): BrainAuditReport = withHandle { h ->
        val floats = FloatArray(SmartShuffleNative.AUDIT_FLOATS)
        val ints = IntArray(SmartShuffleNative.AUDIT_INTS)
        val details = SmartShuffleNative.nativeAudit(h, floats, ints)
        val checks = AuditCheckKind.entries.mapIndexed { c, kind ->
            AuditCheckResult(
                kind = kind,
                status = AuditStatus.entries.getOrElse(ints[c * 2]) { AuditStatus.FAILED },
                metric = floats[c * 2],
                threshold = floats[c * 2 + 1],
                corrections = ints[c * 2 + 1],
                detail = details.getOrElse(c) { "" },
            )
        }
        val f = 10
        val i = 10
        BrainAuditReport(
            generation = ints[i],
            checks = checks,
            afterFastSkip = ints[i + 1] != 0,
            averagePairwiseSimilarity = floats[f],
            artistEntropyBits = floats[f + 1],
            zoneEntropyBits = floats[f + 2],
            sessionNorm = floats[f + 3],
            nonFiniteValues = ints[i + 2],
            realVectorPct = floats[f + 4],
            realTracks = ints[i + 3],
            fallbackTracks = ints[i + 4],
            placeholderTracks = ints[i + 5],
            exploreBoost = floats[f + 5],
            elapsedMs = floats[f + 6],
        )
    }

    override fun close() = synchronized(lock) {
        if (handle != 0L) {
            SmartShuffleNative.nativeDestroy(handle)
            handle = 0L
        }
    }

    companion object {
        /** The built-in self-test (100 virtual tracks); the report ends with "RESULT: PASS" or "RESULT: FAIL". */
        fun runSyntheticBrainSimulation(): String = SmartShuffleNative.nativeRunSyntheticSimulation()

        private fun event(code: Int): SessionEvent = SessionEvent.entries.getOrElse(code) { SessionEvent.NONE }
    }
}

/**
 * The background indexer's 24-D extractor. Not thread-safe: one per song,
 * fed from the same decode loop as [NativeAnalyzer].
 */
class NativeAcousticExtractor(sampleRate: Int) : Closeable {
    private var handle: Long = SmartShuffleNative.nativeExtractorCreate(sampleRate)

    init {
        require(handle != 0L) { "Unsupported sample rate: $sampleRate" }
    }

    /** Mono float PCM in [-1, 1]; [count] may be less than the array size. */
    fun process(monoSamples: FloatArray, count: Int = monoSamples.size) {
        check(handle != 0L) { "Extractor already closed" }
        SmartShuffleNative.nativeExtractorProcess(handle, monoSamples, count)
    }

    /** The 24 raw features, or null when there were under two seconds of sound. */
    fun finish(): FloatArray? {
        check(handle != 0L) { "Extractor already closed" }
        val out = FloatArray(SmartShuffleNative.DIMS)
        return if (SmartShuffleNative.nativeExtractorFinish(handle, out)) out else null
    }

    override fun close() {
        if (handle != 0L) {
            SmartShuffleNative.nativeExtractorDestroy(handle)
            handle = 0L
        }
    }
}
