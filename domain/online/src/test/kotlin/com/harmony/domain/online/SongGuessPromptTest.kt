package com.harmony.domain.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SongGuessPromptTest {
    @Test
    fun theAnswerIsReadLineByLine() {
        val answer = """
            1. Linkin Park ||| In the End
            - "Linkin Park" ||| "Numb"
            Linkin Park ||| In the End
            Some chatter the model added
        """.trimIndent()
        assertEquals(listOf(SongGuess("Linkin Park", "In the End"), SongGuess("Linkin Park", "Numb")), SongGuessPrompt.parse(answer))
    }

    @Test
    fun noGuessIsNoGuess() {
        assertTrue(SongGuessPrompt.parse("NONE").isEmpty())
        assertTrue(SongGuessPrompt.parse("").isEmpty())
        assertTrue("an unknown artist is no guess", SongGuessPrompt.parse("Unknown Artist ||| Track 1").isEmpty())
        assertTrue(SongGuessPrompt.parse(" ||| In the End").isEmpty())
        assertEquals(SongGuessPrompt.MAX_GUESSES, SongGuessPrompt.parse("A ||| 1\nB ||| 2\nC ||| 3").size)
    }

    @Test
    fun thePromptCarriesTheNamesAndTheLength() {
        val p = SongGuessPrompt.build(SeedTrack("lp_itend_final_v2", "<unknown>", "Hybrid", 216_500))
        assertTrue(p.contains("Title tag: lp_itend_final_v2"))
        assertTrue(p.contains("Album tag: Hybrid"))
        assertTrue(p.contains("Length: 3:36"))
        assertTrue(p.contains("Artist ||| Title"))
    }

    @Test
    fun aJsonAnswerIsCheckedAsATextOne() {
        assertEquals(
            listOf(SongGuess("Linkin Park", "In the End")),
            SongGuessPrompt.fromPairs(listOf("Linkin Park" to "In the End", "Unknown" to "x", "" to "y", "Linkin Park" to "In the End")),
        )
        assertTrue("the file's names, never the format instructions", !SongGuessPrompt.describe(SeedTrack("a", "b")).contains("|||"))
    }

    @Test
    fun theFirstGuesserWithAGuessAnswers() = kotlinx.coroutines.runBlocking {
        var second = 0
        val chain = SongGuesser.firstOf(SongGuesser { emptyList() }, SongGuesser { second++; listOf(SongGuess("A", "B")) })
        assertEquals(listOf(SongGuess("A", "B")), chain.guess(SeedTrack("x", "y")))
        val first = SongGuesser.firstOf(SongGuesser { listOf(SongGuess("C", "D")) }, SongGuesser { second++; emptyList() })
        assertEquals(listOf(SongGuess("C", "D")), first.guess(SeedTrack("x", "y")))
        assertEquals("the second is asked only when the first has nothing", 1, second)
    }
}
