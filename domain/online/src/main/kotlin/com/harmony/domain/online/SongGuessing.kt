package com.harmony.domain.online

/** A song an AI thinks a file is: who by, and its name. */
data class SongGuess(val artist: String, val title: String)

/**
 * Guesses which song a file is from what its names say (title and artist
 * tags, album, the file's name when it has no tags), for when neither the
 * tags, nor a cleaned reading of them, nor its sound found it. On the phone:
 * nothing is sent anywhere. A guess is only a lead: it is searched for and
 * [SongMatcher] must confirm it (artist, title and length), as for the tags.
 */
fun interface SongGuesser {
    /** The most likely first, at most [SongGuessPrompt.MAX_GUESSES]; empty when it can't tell or isn't available. */
    suspend fun guess(seed: SeedTrack): List<SongGuess>

    companion object {
        val NONE = SongGuesser { emptyList() }
    }
}

/** What the model is asked, and how its answer is read. Plain text both ways, so it can be tested off the phone. */
object SongGuessPrompt {
    const val MAX_GUESSES = 2
    private const val SEPARATOR = "|||"

    fun build(seed: SeedTrack): String = buildString {
        appendLine("You identify songs from the messy metadata of a music file.")
        appendLine("Reply with up to $MAX_GUESSES guesses of which published song this is, the most likely first,")
        appendLine("one per line, exactly in this form: Artist $SEPARATOR Title")
        appendLine("Use the official artist name and song title. Keep a version (Live, Remix, Acoustic) only if the file names one.")
        appendLine("If you cannot tell, reply NONE. Write nothing else.")
        appendLine()
        appendLine("Title tag: ${seed.title.trim().take(200)}")
        appendLine("Artist tag: ${seed.artist.trim().take(120).ifEmpty { "(none)" }}")
        seed.album?.trim()?.takeIf { it.isNotEmpty() }?.let { appendLine("Album tag: ${it.take(120)}") }
        seed.knownDurationMs?.let { ms -> appendLine("Length: ${ms / 60_000}:${((ms / 1000) % 60).toString().padStart(2, '0')}") }
    }

    /**
     * The guesses in [answer]: lines "Artist ||| Title", with stray quotes,
     * numbering and bullets taken off. A line that names no artist, an
     * unknown one, or that is too long to be a name is not a guess.
     */
    fun parse(answer: String): List<SongGuess> = answer.lineSequence()
        .map { it.trim().trimStart('-', '*', '•', ' ').replace(Regex("^\\d+[.)]\\s*"), "") }
        .filter { SEPARATOR in it }
        .mapNotNull { line ->
            val artist = clean(line.substringBefore(SEPARATOR))
            val title = clean(line.substringAfter(SEPARATOR))
            SongGuess(artist, title).takeIf {
                artist.isNotEmpty() && title.isNotEmpty() && artist.length <= 120 && title.length <= 200 &&
                    !SeedCleaner.isUnknownArtist(artist) && !title.equals("none", ignoreCase = true)
            }
        }
        .distinctBy { it.artist.lowercase() + "|" + it.title.lowercase() }
        .take(MAX_GUESSES)
        .toList()

    private fun clean(s: String) = s.trim().trim('"', '\'', '“', '”', '`').trim()
}
