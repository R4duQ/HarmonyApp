package com.harmony.desktop.connect

import com.harmony.core.remote.Connect
import com.harmony.core.remote.ConnectReceiver
import com.harmony.core.remote.DiscoveryResponder
import com.harmony.core.remote.MiniHttpServer
import com.harmony.core.remote.Pairing
import com.harmony.core.remote.PcInfo
import com.harmony.core.remote.PhoneLink
import com.harmony.core.remote.PhoneSessions
import com.harmony.core.remote.RemoteRenderer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

/** What the Connect screen shows. */
data class ConnectState(
    val running: Boolean = false,
    val code: String = "----",
    val port: Int = 0,
    val addresses: List<String> = emptyList(),
    /** token -> phone name. */
    val phones: Map<String, String> = emptyMap(),
    val error: String? = null,
)

/**
 * Makes this computer a Harmony Connect speaker: listens for phones on the
 * Wi-Fi (discovery), lets them pair with the code on screen, and hands what
 * they send to [renderer].
 *
 * [link] is the phone session as it really is: connected only once the
 * phone's requests arrive, "reconnecting" when they stop for a few seconds,
 * gone when they stay away (see [PhoneSessions]). One watch thread checks
 * that once a [tickMs] for as long as the host runs; nothing is started per
 * session, so nothing is left behind after one.
 */
class ConnectHost(
    private val pcId: String,
    private val pcName: () -> String,
    private val renderer: RemoteRenderer,
    initialPhones: Map<String, String>,
    private val savePhones: (Map<String, String>) -> Unit,
    private val httpPort: Int = Connect.HTTP_PORT,
    private val discoveryPort: Int = Connect.DISCOVERY_PORT,
    private val suspectAfterMs: Long = PhoneSessions.SUSPECT_AFTER_MS,
    private val lostAfterMs: Long = PhoneSessions.LOST_AFTER_MS,
    private val tickMs: Long = TICK_MS,
    private val log: (String) -> Unit = { LOG.info(it) },
) : AutoCloseable {
    private val _state = MutableStateFlow(ConnectState())
    val state: StateFlow<ConnectState> = _state.asStateFlow()

    private val _link = MutableStateFlow<PhoneLink>(PhoneLink.None)

    /** The phone session: none, connected, or reconnecting. Set from network threads; Compose reads it on its own. */
    val link: StateFlow<PhoneLink> = _link.asStateFlow()

    @Volatile private var receiver: ConnectReceiver? = null
    private var watch: ScheduledExecutorService? = null

    private val pairing = Pairing(initialPhones) { phones ->
        savePhones(phones)
        refresh()
    }
    private var server: MiniHttpServer? = null
    private var discovery: DiscoveryResponder? = null

    fun start() {
        if (server != null) return
        val receiver = ConnectReceiver(
            info = { PcInfo(pcId, pcName(), server?.boundPort ?: httpPort) },
            pairing = pairing,
            renderer = renderer,
            onPhone = { refresh() },
            onLink = { _link.value = it },
            log = log,
            suspectAfterMs = suspectAfterMs,
            lostAfterMs = lostAfterMs,
        )
        this.receiver = receiver
        // The usual port, or any free one if something else has it; discovery tells phones which.
        server = try {
            MiniHttpServer(httpPort, "connect", receiver::handle).start()
        } catch (_: Exception) {
            runCatching { MiniHttpServer(0, "connect", receiver::handle).start() }.getOrNull()
        }
        discovery = runCatching {
            DiscoveryResponder({ PcInfo(pcId, pcName(), server?.boundPort ?: httpPort) }, discoveryPort).start()
        }.getOrNull()
        watch = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "harmony-connect-watch").apply { isDaemon = true } }.also {
            it.scheduleWithFixedDelay({ runCatching { receiver.tick() } }, tickMs, tickMs, TimeUnit.MILLISECONDS)
        }
        refresh(error = when {
            server == null -> "Harmony couldn't listen for phones. Another program may be using the network port."
            discovery == null -> "Phones can't find this computer by themselves; type its address on the phone instead."
            else -> null
        })
    }

    fun forget(token: String) {
        receiver?.forgotten(token)
        pairing.forget(token)
    }

    fun forgetAll() {
        pairing.phones.keys.forEach { receiver?.forgotten(it) }
        pairing.forgetAll()
        refresh()
    }

    /**
     * Stop pressed on the computer: the phone's session ends here, and the
     * phone is told so the next time it asks (it then ends its side too).
     */
    fun stopPhone() {
        receiver?.stopFromComputer() ?: renderer.disconnect(null)
    }

    /** Re-reads the pairing code and phones (the code changes after each pairing). */
    fun refresh(error: String? = _state.value.error) {
        _state.value = ConnectState(
            running = server != null,
            code = pairing.code,
            port = server?.boundPort ?: 0,
            addresses = localAddresses(),
            phones = pairing.phones,
            error = error,
        )
    }

    override fun close() {
        watch?.shutdownNow()
        watch = null
        discovery?.close()
        server?.close()
        discovery = null
        server = null
        receiver = null
        _link.value = PhoneLink.None
        refresh()
    }

    private companion object {
        /** How often the watch looks: the "reconnecting" and "lost" moments are this precise. */
        const val TICK_MS = 1_000L
        val LOG: Logger = Logger.getLogger("com.harmony.connect")
    }

    private fun localAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
    }.getOrDefault(emptyList())
}
