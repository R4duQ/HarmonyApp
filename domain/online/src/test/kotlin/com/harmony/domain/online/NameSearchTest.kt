package com.harmony.domain.online

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NameSearchTest {
    private val untagged = SeedTrack("03 - In The End (Linkin Park) [HQ]", "<unknown>", null, 216_500)

    @Test
    fun theQueryIsTheCleanedNameWithAKnownArtist() {
        assertEquals("In The End (Linkin Park)", NameSearch.query(untagged))
        assertEquals("Adele Hello", NameSearch.query(SeedTrack("Hello (Official Video)", "Adele")))
        assertEquals("the artist is not said twice", "Adele - Hello", NameSearch.query(SeedTrack("Adele - Hello", "Adele")))
        assertNull(NameSearch.query(SeedTrack("01", "<unknown>")))
    }

    @Test
    fun aResultOfTheSameLengthWhoseWordsAreInTheNameIsAGuess() {
        val results = listOf(
            NamedRecording("Linkin Park", "In the End (Live)", 230_000),       // another length
            NamedRecording("Mellen Gi", "In the End", 216_000),                // the artist is not named, still a lead
            NamedRecording("Linkin Park", "In the End", 216_000),              // the one
            NamedRecording("Linkin Park", "In the End of Days", 216_000),      // words the name hasn't
        )
        assertEquals(
            listOf(SongGuess("Linkin Park", "In the End"), SongGuess("Mellen Gi", "In the End")),
            NameSearch.pick(untagged, results),
        )
    }

    @Test
    fun withoutALengthTheArtistMustBeNamedToo() {
        val noLength = untagged.copy(durationMs = null)
        val results = listOf(NamedRecording("Mellen Gi", "In the End", 216_000), NamedRecording("Linkin Park", "In the End", null))
        assertEquals(listOf(SongGuess("Linkin Park", "In the End")), NameSearch.pick(noLength, results))
    }

    @Test
    fun deezersAnswerIsReadAndAnythingElseIsNothing() = runBlocking {
        val body = """{"data":[{"title":"In the End","duration":216,"artist":{"name":"Linkin Park"}},{"title":"x"}],"total":2}"""
        assertEquals(listOf(NamedRecording("Linkin Park", "In the End", 216_000)), NameSearch.parseDeezer(body))
        assertTrue(NameSearch.parseDeezer("<html>").isEmpty())
        assertTrue(NameSearch.parseDeezer("""{"error":{"code":4}}""").isEmpty())

        val asked = mutableListOf<String>()
        val deezer = NameSearch.deezer { request -> asked += request.url; HttpReply(200, emptyMap(), body) }
        assertEquals(listOf(SongGuess("Linkin Park", "In the End")), deezer.guess(untagged))
        assertTrue(asked.single().startsWith("https://api.deezer.com/search?limit=25&q=In+The+End"))
    }

    @Test
    fun aFailingSearchIsSkippedForTheNext() = runBlocking {
        val failing = NameSearch.deezer { throw java.io.IOException("offline") }
        val next = SongGuesser { listOf(SongGuess("A", "B")) }
        assertEquals(listOf(SongGuess("A", "B")), SongGuesser.firstOf(failing, next).guess(untagged))
    }
}
