package com.harmony.feature.downloads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class YouTubeAudioTest {

    @Test
    fun musicUploadsUseTrackArtistAlbumAndReleaseYear() {
        val tags = YouTubeAudio.tags(
            fallbackTitle = "Mira Sol - Night Ferry (Official Audio)",
            title = "Night Ferry (Official Audio)",
            track = "Night Ferry",
            artist = "Mira Sol",
            uploader = "Mira Sol - Topic",
            album = "Harbour Lights",
            releaseYear = 2019,
            uploadDate = "20230105",
        )
        assertEquals(YouTubeAudioTags("Night Ferry", "Mira Sol", "Harbour Lights", "2019"), tags)
    }

    @Test
    fun ordinaryVideosFallBackToTitleUploaderAndUploadYear() {
        val tags = YouTubeAudio.tags(
            fallbackTitle = "ignored",
            title = "Live at the Harbour",
            uploader = "Juno Park - Topic",
            uploadDate = "20210817",
        )
        assertEquals(YouTubeAudioTags("Live at the Harbour", "Juno Park", "", "2021"), tags)
    }

    @Test
    fun missingInfoKeepsTheIdentifiedName() {
        val tags = YouTubeAudio.tags(fallbackTitle = " Oda Rivers - Driftwood ", uploadDate = "NA")
        assertEquals(YouTubeAudioTags("Oda Rivers - Driftwood"), tags)
    }

    @Test
    fun ffmpegTakesAudioOnlyWritesFlacAndSkipsBlankTags() {
        val args = YouTubeAudio.ffmpegArguments(
            File("/work/source.webm"),
            File("/work/out.flac"),
            YouTubeAudioTags(title = "Night Ferry", artist = "Mira Sol"),
        )
        assertEquals(listOf("-i", "/work/source.webm"), args.subList(args.indexOf("-i"), args.indexOf("-i") + 2))
        assertEquals("0:a:0", args[args.indexOf("-map") + 1])
        assertTrue("-vn" in args)
        assertEquals("flac", args[args.indexOf("-c:a") + 1])
        assertEquals(listOf("title=Night Ferry", "artist=Mira Sol"), args.withIndex().filter { (i, _) -> i > 0 && args[i - 1] == "-metadata" }.map { it.value })
        assertEquals(listOf("-f", "flac", "/work/out.flac"), args.takeLast(3))
    }

    @Test
    fun theBotCheckSaysNoAccountIsNeeded() {
        val message = YouTubeAudio.failureMessage(
            "ERROR: [youtube] abc: Sign in to confirm you’re not a bot. Use --cookies-from-browser",
        )
        assertTrue(message.contains("isn't a bot"))
        assertTrue(message.contains("No YouTube account or Premium is needed"))
    }

    @Test
    fun accountGatedVideosAreNamedPrecisely() {
        assertTrue(YouTubeAudio.failureMessage("Sign in to confirm your age").contains("age-restricted"))
        assertTrue(YouTubeAudio.failureMessage("Join this channel to get access to members-only content").contains("members"))
        assertTrue(YouTubeAudio.failureMessage("ERROR: Private video. Sign in if you've been granted access").contains("private"))
        assertTrue(YouTubeAudio.failureMessage("The uploader has not made this video available in your country").contains("country"))
    }

    @Test
    fun protocolChangesAndNetworkErrorsKeepTheirMessages() {
        assertTrue(YouTubeAudio.failureMessage("HTTP Error 403: Forbidden").contains("updated yt-dlp"))
        assertTrue(YouTubeAudio.failureMessage("Unable to connect to host").contains("couldn't reach YouTube"))
        assertTrue(YouTubeAudio.failureMessage("something unexpected").contains("Details"))
    }

    @Test
    fun storageErrorsAreRecognised() {
        assertTrue(YouTubeAudio.isOutOfSpace("java.io.IOException: write failed: ENOSPC (No space left on device)"))
        assertTrue(YouTubeAudio.failureMessage("OSError: [Errno 28] No space left on device").contains("storage"))
        assertFalse(YouTubeAudio.isOutOfSpace("HTTP Error 403"))
    }
}
