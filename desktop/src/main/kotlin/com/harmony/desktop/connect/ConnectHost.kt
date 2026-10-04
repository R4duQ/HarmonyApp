package com.harmony.desktop.connect

import com.harmony.core.remote.Connect
import com.harmony.core.remote.ConnectReceiver
import com.harmony.core.remote.DiscoveryResponder
import com.harmony.core.remote.MiniHttpServer
import com.harmony.core.remote.Pairing
import com.harmony.core.remote.PcInfo
import com.harmony.core.remote.RemoteRenderer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.NetworkInterface

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
 */
class ConnectHost(
    private val pcId: String,
    private val pcName: () -> String,
    private val renderer: RemoteRenderer,
    initialPhones: Map<String, String>,
    private val savePhones: (Map<String, String>) -> Unit,
    private val httpPort: Int = Connect.HTTP_PORT,
    private val discoveryPort: Int = Connect.DISCOVERY_PORT,
) : AutoCloseable {
    private val _state = MutableStateFlow(ConnectState())
    val state: StateFlow<ConnectState> = _state.asStateFlow()

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
        )
        // The usual port, or any free one if something else has it; discovery tells phones which.
        server = try {
            MiniHttpServer(httpPort, "connect", receiver::handle).start()
        } catch (_: Exception) {
            runCatching { MiniHttpServer(0, "connect", receiver::handle).start() }.getOrNull()
        }
        discovery = runCatching {
            DiscoveryResponder({ PcInfo(pcId, pcName(), server?.boundPort ?: httpPort) }, discoveryPort).start()
        }.getOrNull()
        refresh(error = when {
            server == null -> "Harmony couldn't listen for phones. Another program may be using the network port."
            discovery == null -> "Phones can't find this computer by themselves; type its address on the phone instead."
            else -> null
        })
    }

    fun forget(token: String) = pairing.forget(token)

    fun forgetAll() {
        pairing.forgetAll()
        refresh()
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
        discovery?.close()
        server?.close()
        discovery = null
        server = null
        refresh()
    }

    private fun localAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
    }.getOrDefault(emptyList())
}
