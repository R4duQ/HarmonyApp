package com.harmony.feature.downloads

import org.junit.Assert.*
import org.junit.Test

class DownloadStatusCenterTest {
    @Test fun unrelatedIdleScreensCannotEraseTheAlbumBanner() {
        val center = DownloadStatusCenter()
        val album = center.forOwner(album = true)
        val manual = center.forOwner()
        val playlist = center.forOwner()
        val active = ActiveDownload("Album", "Soulseek", "1/8")
        album.publishActive(active)
        repeat(10) { manual.publishActive(null); playlist.clear(); center.clear() }
        assertEquals(active, center.active.value)
        album.publishActive(active.copy(detail = "Updating library"))
        assertNotNull(center.active.value)
        album.clear(); assertNull(center.active.value)
    }
    @Test fun aWorkerRemainsVisibleWhileAnotherScreenReportsActivity() {
        val center = DownloadStatusCenter()
        val album = center.forOwner(album = true)
        val screen = center.forOwner()
        album.publishActive(ActiveDownload("Album", "SpotiFLAC"))
        screen.publishActive(ActiveDownload("Other track", "Soulseek"))
        assertEquals("Album", center.active.value?.title)
        screen.clear(); assertEquals("Album", center.active.value?.title)
        album.clear(); assertNull(center.active.value)
    }
    @Test fun anOldDismissalDoesNotConsumeANewerOutcome() {
        val center = DownloadStatusCenter()
        center.publishOutcome("First", true)
        val first = center.outcome.value!!.id
        center.publishOutcome("Second", false)
        center.consumeOutcome(first)
        assertEquals("Second", center.outcome.value?.title)
        center.consumeOutcome(center.outcome.value!!.id)
        assertNull(center.outcome.value)
    }
}
