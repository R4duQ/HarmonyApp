package com.harmony.domain.online

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The matcher against the versioned corpus (match-corpus.v1.json). Two
 * numbers decide: wrong matches accepted (a wrong song would be prepared and
 * played) and right matches recovered. Their thresholds are in the corpus
 * file, fixed before the matcher was tuned; this test fails the build when
 * either is missed.
 */
class SongMatcherCorpusTest {
    private data class Case(val id: String, val tags: List<String>, val seed: SeedTrack, val results: List<CatalogTrack>, val expect: String?)

    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull

    private fun load(): Pair<JsonObject, List<Case>> {
        val doc = fixture("match-corpus.v1.json")
        assertEquals(1, (doc["version"] as JsonPrimitive).intOrNull)
        val cases = doc["cases"]!!.jsonArray.map { it.jsonObject }.map { c ->
            val seed = c["seed"]!!.jsonObject
            Case(
                id = c.s("id")!!,
                tags = c["tags"]!!.jsonArray.map { (it as JsonPrimitive).content },
                seed = SeedTrack(seed.s("title")!!, seed.s("artist")!!, seed.s("album"),
                    (seed["durationMs"] as? JsonPrimitive)?.longOrNull, (seed["explicit"] as? JsonPrimitive)?.booleanOrNull),
                results = c["results"]!!.jsonArray.map { it.jsonObject }.map { r ->
                    CatalogTrack(
                        videoId = r.s("videoId")!!,
                        title = r.s("title")!!,
                        artists = r["artists"]!!.jsonArray.map { (it as JsonPrimitive).content },
                        album = r.s("album"),
                        albumId = null,
                        durationMs = (r["durationMs"] as? JsonPrimitive)?.longOrNull,
                        kind = CatalogKind.valueOf(r.s("kind")!!),
                        explicit = (r["explicit"] as? JsonPrimitive)?.booleanOrNull,
                        playable = (r["playable"] as? JsonPrimitive)?.booleanOrNull,
                    )
                },
                expect = c.s("expect"),
            )
        }
        return doc to cases
    }

    @Test
    fun corpusMeetsItsThresholds() {
        val (doc, cases) = load()
        val thresholds = doc["thresholds"]!!.jsonObject
        val maxFalseAccepts = (thresholds["maxFalseAccepts"] as JsonPrimitive).intOrNull!!
        val minRecall = (thresholds["minRecall"] as JsonPrimitive).doubleOrNull!!
        // The matcher's own constant must not be looser than the corpus' rule.
        assertTrue(SongMatcher.MIN_RECALL >= minRecall)

        val falseAccepts = mutableListOf<String>()
        val missed = mutableListOf<String>()
        var positives = 0
        var recovered = 0
        for (c in cases) {
            val result = SongMatcher.match(c.seed, c.results)
            val chosen = result.track?.videoId
            if (c.expect != null) {
                positives++
                if (chosen == c.expect) recovered++ else missed += "${c.id} (${result.verdict}, ${result.score})"
            }
            if (chosen != null && chosen != c.expect) falseAccepts += "${c.id}: chose $chosen, expected ${c.expect}"
        }
        val recall = recovered.toDouble() / positives
        val romanian = cases.filter { "romanian" in it.tags }
        val romanianOk = romanian.count { SongMatcher.match(it.seed, it.results).track?.videoId == it.expect }
        println("CORPUS v1: cases=${cases.size} positives=$positives recovered=$recovered recall=${"%.3f".format(recall)} " +
            "falseAccepts=${falseAccepts.size} romanian=$romanianOk/${romanian.size}")
        missed.forEach { println("CORPUS missed: $it") }
        assertTrue("wrong matches accepted: $falseAccepts", falseAccepts.size <= maxFalseAccepts)
        assertTrue("recall $recall below $minRecall; missed: $missed", recall >= minRecall)
    }

    @Test
    fun everyNegativeIsRejectedForTheRightReason() {
        val (_, cases) = load()
        val c = cases.first { it.id == "only-videos" }
        val r = SongMatcher.match(c.seed, c.results)
        assertEquals(MatchVerdict.NO_MATCH, r.verdict)
        assertTrue(r.assessments.all { it.reasons.single().startsWith("not a song") })
        val live = cases.first { it.id == "live-only" }
        assertEquals("other version: live", SongMatcher.match(live.seed, live.results).assessments.single().reasons.last())
        val amb = cases.first { it.id == "two-recordings-close-scores" }
        assertEquals(MatchVerdict.AMBIGUOUS, SongMatcher.match(amb.seed, amb.results).verdict)
        val thin = cases.first { it.id == "two-recordings-no-evidence" }
        assertTrue(SongMatcher.match(thin.seed, thin.results).verdict in setOf(MatchVerdict.AMBIGUOUS, MatchVerdict.UNCERTAIN))
    }
}
