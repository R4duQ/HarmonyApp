package com.harmony.feature.player

import com.harmony.core.model.MixSource
import com.harmony.domain.online.CatalogKind
import com.harmony.domain.online.CatalogTrack
import com.harmony.domain.online.OnlineProgress
import com.harmony.domain.online.StopReason
import org.junit.Assert.assertEquals
import org.junit.Test

class MixStatusLineTest {
    private val track = CatalogTrack("abcdefghijk", "Numb", listOf("Linkin Park"), null, null, 185_000, CatalogKind.SONG, null, true)

    @Test
    fun theLineSaysWhatHappensToTheMusic() {
        assertEquals("Preparing “Numb” · 45%", statusLine(OnlineProgress.Preparing(track, 0.45f), MixSource.ONLINE, upcomingEmpty = false))
        assertEquals("Preparing “Numb” · 45% · playback starts by itself when it's ready",
            statusLine(OnlineProgress.Preparing(track, 0.45f), MixSource.ONLINE, upcomingEmpty = true))
        assertEquals("The mix continues from your library · a download service needs sign-in or verification (open Downloads)",
            statusLine(OnlineProgress.Stopped(StopReason.NEEDS_SIGN_IN), MixSource.ONLINE, upcomingEmpty = false))
        assertEquals("Your library plays on · this song isn't on YouTube Music",
            statusLine(OnlineProgress.Stopped(StopReason.NOT_FOUND, "NO_MATCH"), MixSource.COMBINED, upcomingEmpty = false))
    }
}
