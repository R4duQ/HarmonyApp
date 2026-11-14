package com.harmony.app

import com.harmony.domain.online.SeedTrack
import com.harmony.domain.online.SongGuess
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Claude guess against a local stand-in for the Messages API: what is
 * sent (and nothing more), and how each answer and error is read.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ClaudeSongGuesserTest {
    private lateinit var server: MockWebServer
    private val problems = mutableListOf<String?>()
    private var key: String? = "sk-ant-test-0000000000000000"

    private val seed = SeedTrack("01 - lp_itend (Official Video)", "<unknown>", "Hybrid", 216_000, uri = "content://media/1")

    @Before fun start() { server = MockWebServer().apply { start() } }
    @After fun stop() = server.shutdown()

    private fun guesser() = ClaudeSongGuesser({ key }, { problems += it }, server.url("/").toString().trimEnd('/'))

    private fun answer(text: String, stop: String = "end_turn") = MockResponse()
        .setHeader("content-type", "application/json")
        .setBody(
            JSONObject()
                .put("id", "msg_1").put("type", "message").put("role", "assistant").put("model", "claude-opus-5-5")
                .put("content", org.json.JSONArray().put(JSONObject().put("type", "text").put("text", text)))
                .put("stop_reason", stop).put("stop_sequence", JSONObject.NULL)
                .put("usage", JSONObject().put("input_tokens", 120).put("output_tokens", 30))
                .toString(),
        )

    @Test
    fun aGuessIsAskedForWithTheFilesNamesOnlyAndRead() = runBlocking {
        server.enqueue(answer("""{"guesses":[{"artist":"Linkin Park","title":"In the End"}]}"""))
        assertEquals(listOf(SongGuess("Linkin Park", "In the End")), guesser().guess(seed))

        val request = server.takeRequest()
        assertEquals("/v1/messages", request.requestUrl!!.encodedPath)
        // The SDK sends the key as a bearer credential (API version 2026-10-21).
        assertTrue((request.getHeader("Authorization") ?: request.getHeader("x-api-key")).orEmpty().contains("sk-ant-test-0000000000000000"))
        assertTrue(request.getHeader("anthropic-beta").orEmpty().contains("server-side-fallback-2026-07-01"))
        val body = JSONObject(request.body.readUtf8())
        assertEquals("claude-opus-5-5", body.getString("model"))
        assertEquals("default", body.getString("fallbacks"))
        val output = body.getJSONObject("output_config")
        assertEquals("low", output.getString("effort"))
        assertEquals("json_schema", output.getJSONObject("format").getString("type"))
        assertEquals("object", output.getJSONObject("format").getJSONObject("schema").getString("type"))
        val sent = body.getJSONArray("messages").getJSONObject(0).toString()
        assertTrue(sent.contains("lp_itend") && sent.contains("Hybrid") && sent.contains("3:36"))
        assertTrue("the file's address is not sent", !sent.contains("content://"))
        assertEquals(listOf<String?>(null), problems)
    }

    @Test
    fun noKeyMeansNoRequest() = runBlocking {
        key = null
        assertTrue(guesser().guess(seed).isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun aRejectedKeyIsReportedToSettings() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setHeader("content-type", "application/json")
            .setBody("""{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"""))
        assertTrue(guesser().guess(seed).isEmpty())
        assertEquals(listOf<String?>("Anthropic rejected the key"), problems)
    }

    @Test
    fun aRefusalOrAnAnswerOutOfShapeIsNoGuess() = runBlocking {
        server.enqueue(answer("", stop = "refusal"))
        assertTrue(guesser().guess(seed).isEmpty())
        server.enqueue(answer("not json"))
        assertTrue(guesser().guess(seed).isEmpty())
        server.enqueue(answer("""{"guesses":[]}"""))
        assertTrue(guesser().guess(seed).isEmpty())
        assertNull(problems.lastOrNull())
    }
}
