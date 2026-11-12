package com.harmony.domain.online

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Android compiles regular expressions with ICU, not with the JVM's engine
 * these tests run on. ICU refuses some JVM-only syntax: build 160/161 closed
 * every online mix with `PatternSyntaxException` on `(?U)` (an
 * ExceptionInInitializerError of YtmParsers), which passed every JVM test.
 * This keeps that syntax out of the module's sources.
 */
class AndroidRegexCompatibilityTest {
    /** JVM-only: inline UNICODE_CHARACTER_CLASS / UNICODE_CASE / UNIX_LINES flags, and java character classes. */
    private val jvmOnly = listOf("(?U", "(?u", "(?d", "\\\\p{java", "\\\\p{Is")

    @Test
    fun noJvmOnlyRegexSyntaxInTheModule() {
        val sources = File("src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("sources found", sources.isNotEmpty())
        val offending = sources.flatMap { file ->
            file.readLines().mapIndexedNotNull { i, line ->
                val regexLine = "Regex(" in line || "Pattern.compile(" in line || "toRegex(" in line
                if (regexLine && jvmOnly.any { it in line }) "${file.name}:${i + 1}: ${line.trim()}" else null
            }
        }
        assertTrue("ICU (Android) rejects these: $offending", offending.isEmpty())
    }

    @Test
    fun theViewCountRuleStillReadsAsYtmusicapiReadsIt() {
        // Without (?U): ICU's \s and \D are Unicode already; the JVM's are ASCII, which the
        // non-Latin view counts below still match (they use ASCII spaces and colons).
        org.junit.Assert.assertEquals("1.4M", YtmParsers.parseViews("1.4M views"))
        org.junit.Assert.assertEquals(null, YtmParsers.parseViews("2Pac"))
        org.junit.Assert.assertEquals("4505", YtmParsers.parseViews("播放次數：4505"))
    }
}
