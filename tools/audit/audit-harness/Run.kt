import android.content.Context
import com.harmony.core.model.Song
import com.harmony.domain.library.repository.*
import com.harmony.feature.settings.TagEditorViewModel
import com.harmony.feature.downloads.SpotifyPlaylistClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import java.lang.reflect.Proxy
import java.net.*
import java.io.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private fun song(id: Long) = Song(id, "content://song/$id", "Original $id", "Artist", "Album", 1,
    null,null,null,null,null,null,120_000,null,null,null,null,null,null,null,null)

private class Edits : SongEditRepository {
    val values = mutableMapOf<Long, SongEditRepository.SongEdit>()
    var readDelay: (Long) -> Long = { 0 }
    var saveDelay = 0L
    var failSave = false
    override fun observe(songId: Long) = flow { delay(readDelay(songId)); emit(values[songId]) }
    override suspend fun save(songId: Long, edit: SongEditRepository.SongEdit) {
        delay(saveDelay); if (failSave) error("Storage unavailable"); values[songId] = edit
    }
    override suspend fun revert(songId: Long) { values.remove(songId) }
    override suspend fun saveArtwork(songId: Long, bytes: ByteArray) = "file:/$songId-user.img"
}

@OptIn(ExperimentalCoroutinesApi::class)
private fun tags() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
        val edits = Edits()
        val repository = Proxy.newProxyInstance(LibraryRepository::class.java.classLoader, arrayOf(LibraryRepository::class.java)) { _, method, args ->
            when (method.name) {
                "originalSongById" -> song(args[0] as Long)
                "songById" -> song(args[0] as Long).let { it.copy(title = edits.values[it.id]?.title ?: it.title) }
                "search" -> flow { delay(if(args[0] == "old") 500 else 10); emit(SearchResults(songs=listOf(song(if(args[0] == "old") 1 else 2)))) }
                else -> error("Unexpected repository call ${method.name}")
            }
        } as LibraryRepository
        val vm = TagEditorViewModel(Context(), repository, edits)
        vm.onQueryChange("old"); advanceTimeBy(220); runCurrent()
        vm.onQueryChange("new"); advanceUntilIdle()
        check(vm.state.value.results.single().id == 2L)
        edits.readDelay = { if (it == 1L) 500 else 5 }
        vm.select(song(1)); runCurrent(); vm.select(song(2)); advanceUntilIdle()
        check(vm.state.value.selected?.id == 2L)
        vm.select(song(1)); runCurrent(); vm.back(); advanceUntilIdle()
        check(vm.state.value.selected == null)
        edits.readDelay = { 0 }
        edits.values[1] = SongEditRepository.SongEdit(title = "Edited")
        vm.select(song(1).copy(title="Edited")); advanceUntilIdle(); vm.revert(); advanceUntilIdle()
        check(vm.state.value.title == "Original 1")
        vm.onTitleChange("First edit"); vm.save(); advanceUntilIdle()
        vm.onArtistChange("New artist"); vm.save(); advanceUntilIdle()
        check(edits.values[1]?.title == "First edit")
        edits.saveDelay = 500
        vm.onTitleChange("Saved in background"); vm.save(); runCurrent()
        vm.select(song(2)); advanceUntilIdle()
        check(vm.state.value.selected?.id == 2L && !vm.state.value.saved)
        edits.failSave = true
        vm.onTitleChange("Fails"); vm.save(); advanceUntilIdle()
        check(!vm.state.value.busy && vm.state.value.error != null && !vm.state.value.saved)
        println("PASS: tag editor (7 scenarios: stale search/selection, back, revert, repeat save, navigation during save, storage failure)")
    } finally { Dispatchers.resetMain() }
}

private object HttpFixture {
    var entered = CountDownLatch(1)
    var release = CountDownLatch(1)
    var blockTokens = false
    var repeatedPage = false
    var requests = 0
    fun reset(block: Boolean = false, repeated: Boolean = false) {
        entered = CountDownLatch(1); release = CountDownLatch(1); blockTokens = block; repeatedPage = repeated; requests = 0
    }
}

private fun oauth() = runBlocking {
    URL.setURLStreamHandlerFactory { protocol -> if (protocol != "https") null else object : URLStreamHandler() {
        override fun openConnection(url: URL) = object : HttpURLConnection(url) {
            override fun connect() {}
            override fun disconnect() {}
            override fun usingProxy() = false
            override fun getOutputStream() = ByteArrayOutputStream()
            override fun getResponseCode(): Int {
                HttpFixture.requests++
                check(!instanceFollowRedirects)
                if (url.host == "accounts.spotify.com" && HttpFixture.blockTokens) {
                    HttpFixture.entered.countDown()
                    check(HttpFixture.release.await(5, TimeUnit.SECONDS))
                }
                return 200
            }
            override fun getInputStream(): InputStream = (when {
                url.host == "accounts.spotify.com" -> """{"access_token":"new-access","refresh_token":"new-refresh","expires_in":3600}"""
                url.path.endsWith("/playlists") && HttpFixture.repeatedPage -> """{"items":[],"next":"https://api.spotify.com/v1/me/playlists?limit=50&offset=0"}"""
                url.path.endsWith("/playlists") -> """{"items":[],"next":null}"""
                else -> """{"id":"user"}"""
            }).byteInputStream()
        }
    } }
    suspend fun refreshRace(changeClient: Boolean) {
        HttpFixture.reset(block = true)
        val context = Context()
        val client = SpotifyPlaylistClient(context)
        client.saveClientId("a".repeat(32))
        context.preferences.edit().putString("refresh_token", "old-refresh").commit()
        val pending = async(Dispatchers.IO) { runCatching { client.loadPlaylists() } }
        check(HttpFixture.entered.await(5, TimeUnit.SECONDS))
        if (changeClient) client.saveClientId("b".repeat(32)) else client.disconnect()
        HttpFixture.release.countDown()
        check(pending.await().isFailure)
        check(!client.hasSession())
        check(HttpFixture.requests == 1)
    }
    refreshRace(false); refreshRace(true)
    HttpFixture.reset()
    val context = Context(); val client = SpotifyPlaylistClient(context)
    client.saveClientId("a".repeat(32)); context.preferences.edit().putString("refresh_token", "old-refresh").commit()
    check(client.loadPlaylists().isEmpty() && client.hasSession())
    HttpFixture.reset(repeated = true)
    check(runCatching { client.loadPlaylists() }.isFailure)
    check(HttpFixture.requests == 2)
    println("PASS: Spotify client (4 scenarios: refresh/disconnect race, change-client race, successful refresh, repeated pagination)")
}

fun main() { tags(); oauth() }
