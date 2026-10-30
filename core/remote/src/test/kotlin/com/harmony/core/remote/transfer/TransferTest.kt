package com.harmony.core.remote.transfer

import com.harmony.core.model.PartialHash
import com.harmony.core.remote.ConnectClient
import com.harmony.core.remote.ConnectReceiver
import com.harmony.core.remote.ControlRequest
import com.harmony.core.remote.HttpResponse
import com.harmony.core.remote.MiniHttpServer
import com.harmony.core.remote.Pairing
import com.harmony.core.remote.PcInfo
import com.harmony.core.remote.PlayRequest
import com.harmony.core.remote.RemoteRenderer
import com.harmony.core.remote.RemoteState
import com.harmony.core.remote.RemoteStatus
import com.harmony.core.remote.RemoteTrack
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Collections
import kotlin.random.Random

/**
 * "Send to phone" end to end on real sockets: the computer's outbox and
 * Connect server, the phone's runner with a stand-in for its library.
 */
class TransferTest {
    @get:Rule val tmp = TemporaryFolder()
    private val closing = mutableListOf<AutoCloseable>()
    @After fun tearDown() = closing.asReversed().forEach { runCatching { it.close() } }

    private object Renderer : RemoteRenderer {
        override fun play(request: PlayRequest) {}
        override fun control(request: ControlRequest) {}
        override fun setVolume(volume: Float) {}
        override fun status() = RemoteStatus(RemoteState.PLAYING)
        override fun disconnect(phoneName: String?) {}
    }

    /** The phone's library: files saved in a folder, what it already had by content. */
    private inner class Library : PhoneLibrary {
        val dir = tmp.newFolder("library")
        var free = 100L shl 30
        val known = HashSet<String>()
        var publishes = 0
        var failWith: TransferFailure? = null
        override fun freeBytes() = free
        override fun findInLibrary(item: TransferItem): String? = if (item.libraryHash in known) "library:${item.libraryHash}" else null
        override fun stillThere(address: String) = File(address).isFile
        override fun publish(file: File, item: TransferItem): String {
            failWith?.let { throw it }
            var target = File(dir, item.name)
            var n = 2
            while (target.exists()) {
                if (target.length() == file.length() && sha(target) == sha(file)) return target.path
                target = File(dir, item.name.substringBeforeLast('.') + " ($n)." + item.name.substringAfterLast('.'))
                n++
            }
            file.copyTo(target)
            publishes++
            return target.path
        }
    }

    /** The computer: its Connect server with the outbox, and ways to break the wire. */
    private inner class Pc {
        val pairing = Pairing(mapOf("phone-token" to "Pixel", "other-token" to "Other phone"))
        val outbox = TransferOutbox(tmp.newFile("sends.json").also { it.delete() }).also { closing += it }
        val receiver = ConnectReceiver({ PcInfo("pc-1", "PC", 0) }, pairing, Renderer, transfers = outbox)
        val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
        /** The next file transfer stops after this many bytes (once). */
        @Volatile var cutAfter = -1L
        /** The next file transfer has one byte changed at this position (once each). */
        @Volatile var corruptAt = -1L
        @Volatile var corruptTimes = 0
        /** Called after this many bytes of a file have gone out (once). */
        @Volatile var after: Pair<Long, () -> Unit>? = null
        val server = MiniHttpServer(0, "test-pc") { req ->
            if (req.path.startsWith(TransferPaths.FILE)) requests += "range=${req.header("Range")} if-range=${req.header("If-Range")}"
            val r = receiver.handle(req)
            if (!req.path.startsWith(TransferPaths.FILE) || r.status !in 200..206) r
            else HttpResponse(r.status, r.contentType, r.length, r.headers) { out -> r.writeBody(Wire(out)) }
        }.start().also { closing += it }
        val port get() = server.boundPort

        private inner class Wire(private val out: OutputStream) : OutputStream() {
            var sent = 0L
            val cut = cutAfter.also { cutAfter = -1 }
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) {
                if (cut in 0..sent) throw IOException("cable pulled")
                val n = if (cut >= 0) minOf(len.toLong(), cut - sent).toInt() else len
                val chunk = b.copyOfRange(off, off + n)
                if (corruptTimes > 0 && corruptAt in sent until sent + n) {
                    chunk[(corruptAt - sent).toInt()] = (chunk[(corruptAt - sent).toInt()] + 1).toByte()
                    corruptTimes--
                }
                out.write(chunk)
                sent += n
                after?.let { (at, action) -> if (sent >= at) { after = null; action() } }
                if (n < len) throw IOException("cable pulled")
            }
            override fun flush() = out.flush()
        }

        fun send(vararg files: File, token: String = "phone-token", playable: Boolean = true): String {
            val id = outbox.create(token, "Pixel", "pc-1", "PC", files.map { OutgoingFile(it.path, it.nameWithoutExtension, "Artist", "Album", 1000, "audio/flac", playable) })
            val end = System.currentTimeMillis() + 10_000
            while (outbox.entries().first { it.offer.id == id }.preparing) {
                check(System.currentTimeMillis() < end) { "not prepared" }
                Thread.sleep(10)
            }
            return id
        }

        fun status(id: String, item: Int) = outbox.entries().first { it.offer.id == id }.offer.let { it.statusOf(it.items[item].id) }
    }

    private fun song(name: String, size: Int, seed: Int = name.hashCode()): File =
        File(tmp.root, name).apply { writeBytes(Random(seed).nextBytes(size)) }

    private fun sha(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

    private fun runner(pc: Pc, lib: Library, token: String = "phone-token", staging: File = File(tmp.root, "staging"),
                       ledger: TransferLedger = TransferLedger(File(tmp.root, "ledger.json")), cancelled: () -> Boolean = { false },
                       progress: (RunProgress) -> Unit = {}) =
        TransferRunner(TransferClient("127.0.0.1", pc.port, token), staging, ledger, lib, bytesPerSecond = { 0 },
            cancelled = cancelled, onProgress = progress, sleep = { Thread.sleep(minOf(it, 50)) }, log = { println(it) })

    @Test fun aFileArrivesWholeVerifiedAndSavedOnce() {
        val pc = Pc()
        val lib = Library()
        val src = song("Îngerul – Ediție.flac", 3_000_000)
        val id = pc.send(src)
        val seen = mutableListOf<RunProgress>()
        val result = runner(pc, lib, progress = { seen += it }).run(id)
        assertEquals(RunOutcome.FINISHED, result.outcome)
        val saved = File(lib.dir, "Îngerul – Ediție.flac")
        assertArrayEquals("the same bytes, the name kept with its diacritics", src.readBytes(), saved.readBytes())
        assertEquals(ItemState.DONE, pc.status(id, 0).state)
        assertEquals(1, lib.publishes)
        assertTrue("progress shown", seen.any { it.itemBytes in 1 until src.length() })
        assertTrue("nothing left behind", File(tmp.root, "staging").walk().none { it.isFile })
        // Run again (as after a restart): nothing saved twice.
        assertEquals(RunOutcome.FINISHED, runner(pc, lib).run(id).outcome)
        assertEquals(1, lib.publishes)
    }

    @Test fun aCutTransferResumesFromWhereItStoppedEvenAfterARestart() {
        val pc = Pc()
        val lib = Library()
        val src = song("big.flac", 5_000_000)
        val id = pc.send(src)
        pc.cutAfter = 2_000_000
        // The phone gives up after its retries (the computer unreachable): the part is kept.
        val unreachable = runner(pc, lib).let { r ->
            TransferRunner(TransferClient("127.0.0.1", pc.port, "phone-token"), File(tmp.root, "staging"), TransferLedger(File(tmp.root, "ledger.json")), lib,
                bytesPerSecond = { 0 }, sleep = { }, maxNetworkRetries = 0, log = { println(it) })
        }.run(id)
        assertEquals(RunOutcome.RETRY_LATER, unreachable.outcome)
        assertEquals(ItemState.INTERRUPTED, pc.status(id, 0).state)
        val kept = File(tmp.root, "staging").walk().first { it.name.endsWith(".part") }.length()
        assertTrue("a part kept: $kept", kept in 1_000_000..2_000_000)
        // The app starts again: a new runner carries on from the part.
        val before = pc.requests.size
        assertEquals(RunOutcome.FINISHED, runner(pc, lib).run(id).outcome)
        val again = pc.requests.drop(before)
        assertEquals("resumed from the part, with the validator: $again", "range=bytes=$kept-", again.first().substringBefore(' '))
        assertTrue(again.first().contains("if-range=\""))
        assertEquals(sha(src), sha(File(lib.dir, "big.flac")))
    }

    @Test fun aFileChangedOnTheComputerMidTransferIsTakenAgainWholeAndRight() {
        val pc = Pc()
        val lib = Library()
        val src = song("changing.flac", 3_000_000, seed = 1)
        val id = pc.send(src)
        pc.cutAfter = 1_000_000
        // Re-tagged on the computer while the phone was taking it (another size, other bytes).
        pc.after = 900_000L to { Thread.sleep(20); src.writeBytes(Random(2).nextBytes(3_000_100)); src.setLastModified(System.currentTimeMillis() + 5_000) }
        assertEquals(RunOutcome.FINISHED, runner(pc, lib).run(id).outcome)
        val saved = File(lib.dir, "changing.flac")
        assertEquals("the new version, whole", sha(src), sha(saved))
        assertEquals(2, pc.outbox.entries().first().offer.items[0].version)
    }

    @Test fun aDamagedTransferIsTakenAgainOnceThenFails() {
        val pc = Pc()
        val lib = Library()
        val a = song("a.flac", 500_000)
        val id = pc.send(a)
        pc.corruptAt = 123_456
        pc.corruptTimes = 1
        assertEquals(RunOutcome.FINISHED, runner(pc, lib).run(id).outcome)
        assertEquals("taken again, right", sha(a), sha(File(lib.dir, "a.flac")))

        val b = song("b.flac", 500_000)
        val id2 = pc.send(b)
        pc.corruptAt = 10
        pc.corruptTimes = 100
        runner(pc, lib).run(id2)
        assertEquals(ItemState.FAILED, pc.status(id2, 0).state)
        assertEquals(FailReason.CHECKSUM, pc.status(id2, 0).reason)
        assertFalse("never saved damaged", File(lib.dir, "b.flac").exists())
    }

    @Test fun aBatchKeepsWhatArrivedAndReportsEachFailure() {
        val pc = Pc()
        val lib = Library()
        val one = song("1.flac", 200_000)
        val two = song("2.flac", 200_000)
        val three = song("3.flac", 200_000)
        val id = pc.send(one, two, three)
        two.delete() // gone from the computer before the phone got to it
        lib.failWith = null
        val result = runner(pc, lib).run(id)
        assertEquals(RunOutcome.FINISHED, result.outcome)
        assertEquals(listOf(ItemState.DONE, ItemState.FAILED, ItemState.DONE), (0..2).map { pc.status(id, it).state })
        assertEquals(FailReason.SOURCE_MISSING, pc.status(id, 1).reason)
        assertTrue(File(lib.dir, "1.flac").exists() && File(lib.dir, "3.flac").exists())
        // Put back and retried from the computer: only that one is sent, as a new attempt.
        two.writeBytes(Random(9).nextBytes(200_000))
        pc.outbox.retry(id)
        assertEquals(2, pc.status(id, 1).attempt)
        runner(pc, lib).run(id)
        assertEquals(ItemState.DONE, pc.status(id, 1).state)
        assertEquals(3, lib.publishes)
    }

    @Test fun cancelledOnTheComputerTheTransferStopsAndLeavesNothing() {
        val pc = Pc()
        val lib = Library()
        val id = pc.send(song("long.flac", 6_000_000))
        pc.after = 1_000_000L to { pc.outbox.cancel(id) }
        val result = TransferRunner(TransferClient("127.0.0.1", pc.port, "phone-token"), File(tmp.root, "staging"), TransferLedger(File(tmp.root, "ledger.json")), lib,
            bytesPerSecond = { 2_000_000 }, log = { println(it) }).run(id)
        assertEquals(RunOutcome.CANCELLED, result.outcome)
        assertEquals(0, lib.publishes)
        assertTrue("no partial file left", File(tmp.root, "staging").walk().none { it.isFile })
        // A late report for it changes nothing.
        val late = TransferClient("127.0.0.1", pc.port, "phone-token").report(id, listOf(ItemReport(pc.outbox.entries().first().offer.items[0].id, ItemStatus(ItemState.DONE))))
        assertFalse(late)
        assertEquals(ItemState.CANCELLED, pc.status(id, 0).state)
    }

    @Test fun cancelledOnThePhoneTheComputerIsTold() {
        val pc = Pc()
        val lib = Library()
        val id = pc.send(song("long.flac", 6_000_000), song("next.flac", 100_000))
        var stop = false
        pc.after = 1_500_000L to { stop = true }
        val result = runner(pc, lib, cancelled = { stop }).run(id)
        assertEquals(RunOutcome.CANCELLED, result.outcome)
        assertEquals(listOf(ItemState.CANCELLED, ItemState.CANCELLED), (0..1).map { pc.status(id, it).state })
        assertTrue(File(tmp.root, "staging").walk().none { it.isFile })
        assertEquals(0, lib.publishes)
    }

    @Test fun onlyThePhoneASendIsForSeesIt() {
        val pc = Pc()
        val id = pc.send(song("private.flac", 10_000))
        val item = pc.outbox.entries().first().offer.items[0].id
        assertEquals(1, TransferClient("127.0.0.1", pc.port, "phone-token").offers().size)
        val other = TransferClient("127.0.0.1", pc.port, "other-token")
        assertTrue("another paired phone sees nothing", other.offers().isEmpty())
        assertNull(other.offer(id))
        assertEquals(404, other.open(id, item, 0, null).use { it.status })
        assertEquals(404, runCatching { other.report(id, emptyList()) }.exceptionOrNull().let { (it as TransferHttpException).code })
        val stranger = TransferClient("127.0.0.1", pc.port, "not-paired")
        assertEquals(403, (runCatching { stranger.offers() }.exceptionOrNull() as TransferHttpException).code)
        assertEquals(403, stranger.open(id, item, 0, null).use { it.status })
        // Nothing but offered files, by id: no path gets a file.
        assertEquals(404, TransferClient("127.0.0.1", pc.port, "phone-token").open(id, "..%2F..%2Fsecret", 0, null).use { it.status })
    }

    @Test fun theSameFileTwiceOrOneTheLibraryHasIsNotSavedAgainButAnotherVersionIs() {
        val pc = Pc()
        val lib = Library()
        val src = song("Song.flac", 300_000, seed = 5)
        runner(pc, lib).run(pc.send(src))
        assertEquals(1, lib.publishes)
        // Sent again in a new send: recognised by its content.
        val again = pc.send(src)
        runner(pc, lib).run(again)
        assertEquals(1, lib.publishes)
        assertEquals(ItemNote.ALREADY_ON_PHONE, pc.status(again, 0).note)
        // One the library has (its partial hash): not saved.
        val owned = song("Owned.flac", 300_000, seed = 6)
        lib.known += owned.inputStream().use { PartialHash.of(it, owned.length()) }
        val third = pc.send(owned)
        runner(pc, lib).run(third)
        assertEquals(ItemNote.IN_LIBRARY, pc.status(third, 0).note)
        assertEquals(1, lib.publishes)
        // Same name and title, other bytes (another master): saved beside it.
        val remaster = File(tmp.newFolder("remaster"), "Song.flac").apply { writeBytes(Random(7).nextBytes(310_000)) }
        runner(pc, lib).run(pc.send(remaster))
        assertEquals(2, lib.publishes)
        assertTrue(File(lib.dir, "Song (2).flac").exists())
    }

    @Test fun noRoomOrNoPermissionFailsClearlyAndKeepsNothing() {
        val pc = Pc()
        val lib = Library()
        lib.free = 1_000_000
        val id = pc.send(song("big.flac", 2_000_000))
        runner(pc, lib).run(id)
        assertEquals(FailReason.NO_SPACE, pc.status(id, 0).reason)
        lib.free = 100L shl 30
        lib.failWith = TransferFailure(FailReason.PERMISSION, "The folder can't be written.")
        val id2 = pc.send(song("other.flac", 100_000))
        runner(pc, lib).run(id2)
        assertEquals(FailReason.PERMISSION, pc.status(id2, 0).reason)
        assertEquals("The folder can't be written.", pc.status(id2, 0).message)
        assertEquals(0, lib.publishes)
    }

    @Test fun aReportForAnOlderAttemptIsIgnored() {
        val pc = Pc()
        val id = pc.send(song("x.flac", 1000))
        val item = pc.outbox.entries().first().offer.items[0].id
        val phone = TransferClient("127.0.0.1", pc.port, "phone-token")
        phone.report(id, listOf(ItemReport(item, ItemStatus(ItemState.FAILED, attempt = 1, reason = FailReason.OTHER))))
        pc.outbox.retry(id)
        assertTrue(phone.report(id, listOf(ItemReport(item, ItemStatus(ItemState.FAILED, attempt = 1, reason = FailReason.OTHER)))))
        assertEquals("the retry stands", ItemState.PENDING, pc.status(id, 0).state)
        assertEquals(2, pc.status(id, 0).attempt)
    }

    @Test fun sendsSurviveTheComputerRestarting() {
        val store = tmp.newFile("kept.json").also { it.delete() }
        val first = TransferOutbox(store)
        val id = first.create("phone-token", "Pixel", "pc-1", "PC", listOf(OutgoingFile(song("k.flac", 50_000).path, "K", "A", "B", 1, "audio/flac", true)))
        val end = System.currentTimeMillis() + 5_000
        while (first.entries().first().preparing && System.currentTimeMillis() < end) Thread.sleep(10)
        first.close()
        val second = TransferOutbox(store).also { closing += it }
        assertEquals(id, second.offersFor("phone-token").single().id)
        assertEquals(64, second.offersFor("phone-token").single().items[0].sha256.length)
    }

    @Test fun playbackCommandsStayQuickWhileAFileGoesAcross() {
        val pc = Pc()
        val lib = Library()
        val token = "phone-token"
        val control = ConnectClient("127.0.0.1", pc.port, token, session = "s1")
        control.play(PlayRequest(RemoteTrack("1", "T", "A", "B", 1000, "http://127.0.0.1:1/x"), 0, true, "Pixel"))
        val id = pc.send(song("huge.flac", 40_000_000))
        val worst = java.util.concurrent.atomic.AtomicLong()
        val done = java.util.concurrent.atomic.AtomicBoolean()
        val t = Thread {
            while (!done.get()) {
                val s = System.nanoTime()
                control.status()
                worst.set(maxOf(worst.get(), (System.nanoTime() - s) / 1_000_000))
                Thread.sleep(20)
            }
        }.apply { start() }
        assertEquals(RunOutcome.FINISHED, runner(pc, lib).run(id).outcome)
        done.set(true); t.join()
        assertTrue("status answered within ${worst.get()} ms during the transfer", worst.get() < 300)
        assertTrue("the session is still the phone's", control.status().state == RemoteState.PLAYING)
    }

    @Test fun namesAreSafeOnAPhone() {
        assertEquals("a_b_c.flac", TransferOutbox.safeName("a/b\\c.flac"))
        assertEquals("Ce-a fost – ȘȚĂÎÂ.flac", TransferOutbox.safeName("Ce-a fost – ȘȚĂÎÂ.flac"))
        assertEquals("song.mp3", TransferOutbox.safeName("  .mp3"))
        assertTrue(TransferOutbox.safeName("x".repeat(400) + ".flac").length <= 150)
    }

    @Test fun leftoversOfOldTransfersAreCleanedButNotCurrentOnes() {
        val staging = tmp.newFolder("st")
        File(staging, "old/x.part").apply { parentFile.mkdirs(); writeText("x"); setLastModified(0) }
        File(staging, "current/y.part").apply { parentFile.mkdirs(); writeText("y"); setLastModified(0) }
        File(staging, "recent/z.part").apply { parentFile.mkdirs(); writeText("z") }
        TransferRunner.cleanStaging(staging, keep = setOf("current"), maxAgeMs = 24 * 3600_000L)
        assertEquals(setOf("current", "recent"), staging.list()!!.toSet())
    }
}
