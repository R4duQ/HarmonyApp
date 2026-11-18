package com.harmony.domain.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineMixProgressTest {
    private val found = SeedStatus.Identified("seedvideo01", 100)
    private fun c(id: String, stage: CandidateStage, detail: String? = null, progress: Float? = null, retryAt: Long? = null) =
        OnlineCandidate(song(id, "Title $id", "Artist"), "key$id", 80, emptyList(), stage = stage, detail = detail,
            progress = progress, retryAtMillis = retryAt)

    @Test
    fun whatIsHappeningIsOneAnswer() {
        assertEquals(OnlineProgress.Finding, OnlineMixState(seed = SeedStatus.Identifying).progress())
        val preparing = OnlineMixState(seed = found, candidates = listOf(c("a", CandidateStage.QUEUED), c("b", CandidateStage.PREPARING, progress = 0.45f)))
        assertEquals(0.45f, (preparing.progress() as OnlineProgress.Preparing).progress)
        assertTrue(OnlineMixState(seed = found, candidates = listOf(c("a", CandidateStage.READY))).progress() is OnlineProgress.Ready)
        assertEquals(OnlineProgress.Working, OnlineMixState(seed = found).progress())
        assertEquals(OnlineProgress.Working, OnlineMixState(seed = found, candidates = listOf(c("a", CandidateStage.RETRY_LATER, retryAt = 5))).progress())
    }

    @Test
    fun whenNothingCanComeTheReasonIsTheRealOne() {
        fun stopped(vararg cs: OnlineCandidate) = (OnlineMixState(seed = found, candidates = cs.toList()).progress() as OnlineProgress.Stopped).reason
        assertEquals(StopReason.NEEDS_SIGN_IN, stopped(c("a", CandidateStage.NOT_VERIFIED), c("b", CandidateStage.NOT_AVAILABLE)))
        assertEquals(StopReason.WAITING_FOR_WIFI, stopped(c("a", CandidateStage.HELD, detail = "waiting for Wi-Fi")))
        assertEquals(StopReason.HELD, stopped(c("a", CandidateStage.HELD, detail = "not enough free space")))
        assertEquals(StopReason.NONE_LEFT, stopped(c("a", CandidateStage.NOT_AVAILABLE), c("b", CandidateStage.RETRY_LATER)))
        assertEquals(StopReason.NOT_FOUND,
            (OnlineMixState(seed = SeedStatus.NotIdentified(MatchVerdict.NO_MATCH, 10, 0)).progress() as OnlineProgress.Stopped).reason)
        assertEquals(StopReason.NO_CONNECTION,
            (OnlineMixState(seed = SeedStatus.Failed(CatalogFailure.Offline)).progress() as OnlineProgress.Stopped).reason)
        assertFalse(OnlineMixState(seed = SeedStatus.Failed(CatalogFailure.Timeout)).canDeliver())
        assertTrue(OnlineMixState(seed = found).canDeliver())
    }
}
