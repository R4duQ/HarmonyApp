package com.harmony.data.library

/**
 * Turns raw user input into a safe FTS4 MATCH expression for search-as-you-type.
 *
 * Every token becomes a PREFIX term: "the bea" -> "the* bea*", which matches
 * "The Beatles" from the fourth keystroke onwards.
 *
 * Why not quote the tokens (as this used to): in FTS4 the `*` prefix operator
 * only applies to a bare token. Wrapping it first turns `"bea"*` into the exact
 * phrase "bea" with a stray, ignored star, so it matched nothing until the user
 * had typed a COMPLETE word. That is precisely the "search only reacts once I
 * finish typing" bug — every intermediate keystroke returned zero FTS rows and
 * the screen fell back to the slow LIKE scan alone.
 *
 * Quoting is not needed for safety either: the operator characters are stripped
 * below, and because every token carries a trailing `*` the FTS keywords
 * (AND / OR / NOT / NEAR) parse as ordinary prefix terms rather than operators.
 */
object FtsQuery {

    // MATCH syntax characters. They must go before tokenising or SQLite throws.
    private val operators = Regex("""["'*^():{}\-]""")
    private val whitespace = Regex("""\s+""")

    fun sanitize(raw: String): String? {
        val tokens = raw
            .replace(operators, " ")
            .trim()
            .split(whitespace)
            .filter { it.isNotBlank() }
        if (tokens.isEmpty()) return null
        // All tokens are prefixes, not just the last: someone typing
        // "dra tei" is half-remembering two words, not finishing one.
        return tokens.joinToString(" ") { "$it*" }
    }
}
