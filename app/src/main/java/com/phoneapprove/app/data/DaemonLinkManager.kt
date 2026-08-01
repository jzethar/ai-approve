package com.phoneapprove.app.data

import com.phoneapprove.app.model.ApprovalRequest
import com.phoneapprove.app.model.HelloMessage
import com.phoneapprove.app.model.IncomingMessage
import com.phoneapprove.app.model.RequestMessage
import com.phoneapprove.app.model.ResponseMessage
import com.phoneapprove.app.model.parseIncoming
import com.phoneapprove.app.model.protocolJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED }

/**
 * Process-wide singleton owning one TCP connection per paired computer (a
 * phone can be paired with several at once) - a plain `object` rather than
 * a DI-managed class, since the foreground service and Compose UI both need
 * to observe/drive the same set of links without extra wiring.
 *
 * Connects via a plain client TCP socket to the daemon's host:port. Used to
 * be Bluetooth RFCOMM; switched to local-network TCP so the daemon side
 * works on macOS too, not just Linux/BlueZ (see daemon/phone_link.py) - at
 * the cost of requiring phone and computer to share a network instead of
 * just being nearby. Every connection is wrapped in a [SecureChannel]
 * (ephemeral ECDH + AES-GCM, token mixed into the key derivation) since TCP
 * has no equivalent of Bluetooth's physical-proximity barrier - anyone on
 * the LAN could otherwise sniff every tool call and reply in the clear.
 */
object DaemonLinkManager {
    private val links = mutableMapOf<String, DeviceLink>() // keyed by pairing id

    private val _connectionStates = MutableStateFlow<Map<String, ConnectionState>>(emptyMap())
    val connectionStates: StateFlow<Map<String, ConnectionState>> = _connectionStates.asStateFlow()

    private val _requests = MutableStateFlow<List<ApprovalRequest>>(emptyList())
    val requests: StateFlow<List<ApprovalRequest>> = _requests.asStateFlow()

    /** Starts (or restarts, e.g. after re-pairing rotated the token or the
     * host's IP changed) the link for this device. Always tears down and
     * recreates the link, so callers doing a cold-start restore should
     * check [isManaging] first - restarting an already-healthy link is
     * wasteful and briefly races the old/new connections against each
     * other (confirmed via a double connect/disconnect in daemon logs). */
    @Synchronized
    fun start(pairing: PairingInfo) {
        links.remove(pairing.id)?.stop()
        val link = DeviceLink(
            pairing = pairing,
            onState = { state -> _connectionStates.update { it + (pairing.id to state) } },
            onRequest = { request ->
                _requests.update { current ->
                    if (current.any { it.reqId == request.reqId }) current else current + request
                }
            },
        )
        links[pairing.id] = link
        link.start()
    }

    @Synchronized
    fun isManaging(id: String): Boolean = links.containsKey(id)

    @Synchronized
    fun stop(id: String) {
        links.remove(id)?.stop()
        _connectionStates.update { it - id }
        _requests.update { current -> current.filterNot { it.deviceId == id } }
    }

    @Synchronized
    fun stopAll() {
        for (id in links.keys.toList()) stop(id)
    }

    fun respond(reqId: String, action: String, reply: String? = null) {
        val request = _requests.value.find { it.reqId == reqId } ?: return
        val link = synchronized(this) { links[request.deviceId] } ?: return
        if (link.respond(reqId, action, reply)) {
            _requests.update { current -> current.filterNot { it.reqId == reqId } }
        }
    }
}

/** One paired computer's TCP connection: connect/reconnect loop, hello
 * handshake, line-JSON read loop, and outbound responses. */
private class DeviceLink(
    private val pairing: PairingInfo,
    private val onState: (ConnectionState) -> Unit,
    private val onRequest: (ApprovalRequest) -> Unit,
) {
    @Volatile private var socket: Socket? = null
    @Volatile private var channel: SecureChannel? = null
    @Volatile private var shouldRun = false

    fun start() {
        shouldRun = true
        Thread { runLoop() }.apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        shouldRun = false
        closeSocket()
    }

    private fun closeSocket() {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        channel = null
        onState(ConnectionState.DISCONNECTED)
    }

    private fun runLoop() {
        while (shouldRun) {
            try {
                connectOnce()
            } catch (_: Exception) {
                // fall through to the reconnect delay below
            }
            closeSocket()
            if (!shouldRun) return
            Thread.sleep(3000)
        }
    }

    private fun connectOnce() {
        onState(ConnectionState.CONNECTING)
        val sock = Socket()
        socket = sock
        sock.connect(InetSocketAddress(pairing.host, pairing.port), CONNECT_TIMEOUT_MS)

        val reader = BufferedReader(InputStreamReader(sock.inputStream, Charsets.UTF_8))
        val secureChannel = SecureChannel.clientHandshake(reader, sock.outputStream, pairing.token)
        channel = secureChannel

        secureChannel.sendLine(
            protocolJson.encodeToString(HelloMessage.serializer(), HelloMessage(tok = pairing.token))
                .toByteArray(Charsets.UTF_8)
        )

        val ackLine = secureChannel.recvLine() ?: return
        val ack = parseIncoming(String(ackLine, Charsets.UTF_8))
        if (ack !is IncomingMessage.Ack || !ack.ok) return
        onState(ConnectionState.CONNECTED)

        while (shouldRun) {
            val line = secureChannel.recvLine() ?: break
            val msg = parseIncoming(String(line, Charsets.UTF_8))
            if (msg is IncomingMessage.Req) onRequest(toApprovalRequest(msg.message))
        }
    }

    private fun toApprovalRequest(m: RequestMessage) = ApprovalRequest(
        reqId = m.req_id,
        sessionId = m.session_id,
        toolName = m.tool_name,
        toolInput = m.tool_input,
        cwd = m.cwd,
        ts = m.ts,
        deviceId = pairing.id,
        deviceName = pairing.name,
    )

    /** Callers (Compose onClick handlers, the notification-action
     * BroadcastReceiver) are always on the main thread. Unlike the old
     * BluetoothSocket, writing to a plain java.net.Socket from the main
     * thread throws NetworkOnMainThreadException - confirmed the hard way,
     * since it was getting silently caught below and just looked like Allow/
     * Deny taps doing nothing. Bounce the write onto a background thread and
     * join it, so the call stays synchronous for callers that immediately
     * remove the request from the list on success. */
    fun respond(reqId: String, action: String, reply: String?): Boolean {
        val secureChannel = channel ?: return false
        val payload = protocolJson.encodeToString(
            ResponseMessage.serializer(), ResponseMessage(req_id = reqId, action = action, reply = reply)
        )
        var ok = false
        val writer = Thread {
            ok = try {
                secureChannel.sendLine(payload.toByteArray(Charsets.UTF_8))
                true
            } catch (_: Exception) {
                false
            }
        }
        writer.start()
        writer.join()
        return ok
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 5000
    }
}
