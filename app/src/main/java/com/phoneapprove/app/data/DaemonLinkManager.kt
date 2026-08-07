package com.phoneapprove.app.data

import android.bluetooth.BluetoothSocket
import android.content.Context
import com.phoneapprove.app.model.ApprovalRequest
import com.phoneapprove.app.model.HelloMessage
import com.phoneapprove.app.model.IncomingMessage
import com.phoneapprove.app.model.NotifyMessage
import com.phoneapprove.app.model.RequestMessage
import com.phoneapprove.app.model.ResponseMessage
import com.phoneapprove.app.model.SessionNotify
import com.phoneapprove.app.model.parseIncoming
import com.phoneapprove.app.model.protocolJson
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED }

/** Which transport a device's active connection is currently using - null
 * when [ConnectionState] isn't CONNECTED. Purely informational (surfaced in
 * the Devices UI); nothing in the protocol depends on this. */
enum class Transport { TCP, BLUETOOTH }

/**
 * Process-wide singleton owning one connection per paired computer (a phone
 * can be paired with several at once) - a plain `object` rather than a
 * DI-managed class, since the foreground service and Compose UI both need
 * to observe/drive the same set of links without extra wiring.
 *
 * Each paired computer gets *two* transports racing each other on every
 * (re)connect attempt - a plain TCP socket to the daemon's host:port, and
 * (when the pairing carries Bluetooth info) a Bluetooth RFCOMM socket to its
 * bonded device - so a phone that's nearby but off the LAN, or on the LAN
 * but with Bluetooth off, still gets through either way; see [DeviceLink].
 * Every connection, on either transport, is wrapped in a [SecureChannel]
 * (ephemeral ECDH + AES-GCM, token mixed into the key derivation) since
 * neither transport has an equivalent of Bluetooth's old physical-proximity-
 * only security model on its own - anyone on the LAN, or anyone who was ever
 * bonded, could otherwise read every tool call and reply in the clear.
 */
object DaemonLinkManager {
    private const val MAX_SESSION_HISTORY = 20

    // Mirrors daemon/protocol.py's REQUEST_TIMEOUT_SECONDS by hand (the phone
    // has no way to read that file) - the daemon gives up waiting on the
    // phone and sends an explicit "cancel" for the req_id at that point (see
    // DeviceLink's onCancel below), so this is purely a backstop: if that
    // message never arrives (link dropped, daemon crashed, app was backgrounded
    // through a doze cycle...), the sweep in the init block below still drops
    // the card/notification on its own instead of leaving it stuck forever.
    private const val REQUEST_TIMEOUT_SECONDS = 100.0
    private const val SWEEP_INTERVAL_MS = 5000L

    private val links = mutableMapOf<String, DeviceLink>() // keyed by pairing id

    private val _connectionStates = MutableStateFlow<Map<String, ConnectionState>>(emptyMap())
    val connectionStates: StateFlow<Map<String, ConnectionState>> = _connectionStates.asStateFlow()

    private val _activeTransports = MutableStateFlow<Map<String, Transport>>(emptyMap())
    val activeTransports: StateFlow<Map<String, Transport>> = _activeTransports.asStateFlow()

    private val _requests = MutableStateFlow<List<ApprovalRequest>>(emptyList())
    val requests: StateFlow<List<ApprovalRequest>> = _requests.asStateFlow()

    // Transient events, not durable pending state like _requests - there's
    // nothing to "resolve" for a notify, so a SharedFlow (not StateFlow) is
    // the right shape here. extraBufferCapacity so a notify that arrives a
    // moment before a collector subscribes isn't dropped.
    private val _notifications = MutableSharedFlow<SessionNotify>(extraBufferCapacity = 8)
    val notifications: SharedFlow<SessionNotify> = _notifications.asSharedFlow()

    // Durable, bounded history of the same notifies, newest first - unlike
    // _notifications above (a one-shot event ConnectionService turns into an
    // OS push and forgets), this backs an in-app list so a session-finished
    // ping is visible in RequestsScreen too, not just the notification shade.
    private val _sessionHistory = MutableStateFlow<List<SessionNotify>>(emptyList())
    val sessionHistory: StateFlow<List<SessionNotify>> = _sessionHistory.asStateFlow()

    /** Starts (or restarts, e.g. after re-pairing rotated the token or the
     * host's IP changed) the link for this device. Always tears down and
     * recreates the link, so callers doing a cold-start restore should
     * check [isManaging] first - restarting an already-healthy link is
     * wasteful and briefly races the old/new connections against each
     * other (confirmed via a double connect/disconnect in daemon logs). */
    @Synchronized
    fun start(context: Context, pairing: PairingInfo) {
        links.remove(pairing.id)?.stop()
        val link = DeviceLink(
            context = context.applicationContext,
            pairing = pairing,
            onState = { state -> _connectionStates.update { it + (pairing.id to state) } },
            onTransport = { transport ->
                _activeTransports.update { if (transport != null) it + (pairing.id to transport) else it - pairing.id }
            },
            onRequest = { request ->
                _requests.update { current ->
                    if (current.any { it.reqId == request.reqId }) current else current + request
                }
            },
            onNotify = { notify ->
                _notifications.tryEmit(notify)
                _sessionHistory.update { current -> (listOf(notify) + current).take(MAX_SESSION_HISTORY) }
            },
            onCancel = { reqId ->
                _requests.update { current -> current.filterNot { it.reqId == reqId } }
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
        _activeTransports.update { it - id }
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

    /** sessionId+ts together identify one notify, since a session can (and
     * with Codex's Stop event, actually is expected to) finish more than
     * once and re-send the same sessionId. */
    fun dismissSessionNotification(sessionId: String, ts: Double) {
        _sessionHistory.update { current -> current.filterNot { it.sessionId == sessionId && it.ts == ts } }
    }

    fun clearSessionHistory() {
        _sessionHistory.value = emptyList()
    }

    init {
        // Backstop sweep for stuck request cards - see REQUEST_TIMEOUT_SECONDS
        // above for why this exists alongside the daemon's own "cancel" push.
        Thread {
            while (true) {
                Thread.sleep(SWEEP_INTERVAL_MS)
                val now = System.currentTimeMillis() / 1000.0
                _requests.update { current -> current.filterNot { now - it.ts > REQUEST_TIMEOUT_SECONDS } }
            }
        }.apply {
            isDaemon = true
            start()
        }
    }
}

/** One paired computer's connection: races a TCP attempt and (if the
 * pairing carries Bluetooth info) a Bluetooth RFCOMM attempt concurrently on
 * every (re)connect cycle, converges on whichever completes its full
 * hello/hello_ack handshake first, and runs that transport's read loop
 * until it drops - then the cycle repeats. Deciding the winner by full
 * handshake success (not just raw socket connect) matters: it keeps this
 * race consistent with the daemon's own TransportArbiter (daemon/
 * phone_link.py), which also only commits to a transport after a successful
 * handshake, so both sides land on the same winner even when the daemon
 * rejects a raw-connected loser a moment later. */
private class DeviceLink(
    private val context: Context,
    private val pairing: PairingInfo,
    private val onState: (ConnectionState) -> Unit,
    private val onTransport: (Transport?) -> Unit,
    private val onRequest: (ApprovalRequest) -> Unit,
    private val onNotify: (SessionNotify) -> Unit,
    private val onCancel: (String) -> Unit,
) {
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
        closeChannel()
    }

    private fun closeChannel() {
        channel = null
        onTransport(null)
        onState(ConnectionState.DISCONNECTED)
    }

    private fun runLoop() {
        while (shouldRun) {
            try {
                raceConnect()
            } catch (_: Exception) {
                // fall through to the reconnect delay below
            }
            closeChannel()
            if (!shouldRun) return
            Thread.sleep(3000)
        }
    }

    /** Launches a TCP attempt and (if Bluetooth info is present on this
     * pairing) a Bluetooth attempt on their own threads, and blocks until
     * both have finished - which, for the winner, means its full read loop
     * has also finished (see [race], which runs the read loop on the
     * winning attempt's own thread rather than handing back to this one). */
    private fun raceConnect() {
        onState(ConnectionState.CONNECTING)
        val winner = AtomicReference<Transport?>(null)
        val tcpAttempt = AtomicReference<Closeable?>(null)
        val btAttempt = AtomicReference<Closeable?>(null)

        val threads = mutableListOf<Thread>()
        threads += Thread {
            race(Transport.TCP, winner, tcpAttempt, btAttempt) { connectOnceTcp(tcpAttempt) }
        }
        // BLE (macOS daemons) and classic RFCOMM (Linux daemons) are
        // mutually exclusive in practice - a given daemon's pairing payload
        // only ever carries one or the other, see PairingInfo - but both
        // report as Transport.BLUETOOTH to the UI either way.
        val btMac = pairing.btMac
        val btChannelNum = pairing.btChannel
        if (pairing.bleAvailable) {
            threads += Thread {
                race(Transport.BLUETOOTH, winner, btAttempt, tcpAttempt) { connectOnceBle(btAttempt) }
            }
        } else if (btMac != null && btChannelNum != null) {
            threads += Thread {
                race(Transport.BLUETOOTH, winner, btAttempt, tcpAttempt) { connectOnceBt(btMac, btChannelNum, btAttempt) }
            }
        }
        threads.forEach { it.isDaemon = true; it.start() }
        threads.forEach { it.join() }
    }

    /** Runs [attempt] (a connect + hello/hello_ack handshake that returns a
     * ready [SecureChannel] on success, or null/throws on failure). The
     * first attempt to succeed claims [winner] via compare-and-set and takes
     * over as this device's active link - including running its read loop,
     * blocking, right here on its own thread - so [raceConnect]'s
     * `threads.forEach { it.join() }` naturally waits out the whole
     * connection's lifetime, not just the initial handshake. A losing
     * attempt (failed outright, or beaten to the punch) just closes its own
     * raw socket and returns.
     *
     * A watchdog bounds [attempt] itself: [SecureChannel]'s handshake reads
     * (readKexLine/recvLine) block on a plain BufferedReader.readLine() with
     * no timeout at all, and a BLE [GattStreamSocket]'s stream is backed by
     * a PipedInputStream that has no read-timeout concept either. If the
     * daemon side is ever in a half-broken state (confirmed after a macOS
     * sleep/wake cycle - the peripheral kept accepting GATT connections but
     * never actually replied), that read blocks forever, `attempt()` never
     * returns, and this whole device's reconnect loop freezes permanently -
     * only a full app restart recovered. The watchdog force-closes [mine]
     * if `attempt()` hasn't finished within CONNECT_ATTEMPT_TIMEOUT_MS,
     * which unblocks the stuck read with an IOException. It only ever
     * touches the connect+handshake phase - attemptDone flips true (and the
     * watchdog is interrupted) the moment attempt() returns, so a winning,
     * already-connected transport's long-lived readLoop below is never
     * affected by this ceiling. */
    private fun race(
        transport: Transport,
        winner: AtomicReference<Transport?>,
        mine: AtomicReference<Closeable?>,
        other: AtomicReference<Closeable?>,
        attempt: () -> SecureChannel?,
    ) {
        val attemptDone = AtomicBoolean(false)
        val watchdog = Thread {
            try {
                Thread.sleep(CONNECT_ATTEMPT_TIMEOUT_MS)
            } catch (_: InterruptedException) {
                return@Thread
            }
            if (!attemptDone.get()) closeQuietly(mine.get())
        }.apply { isDaemon = true }
        watchdog.start()

        val secureChannel = try {
            attempt()
        } catch (_: Exception) {
            null
        } finally {
            attemptDone.set(true)
            watchdog.interrupt()
        }
        if (secureChannel == null || !winner.compareAndSet(null, transport)) {
            closeQuietly(mine.get())
            return
        }
        // Won: proactively close the loser's raw socket to unblock it if
        // it's still mid-connect, rather than making it wait out its own
        // connect timeout before this cycle's threads.forEach{ join() }
        // returns - best-effort only (the other side may not have created
        // its socket yet), not a correctness requirement.
        closeQuietly(other.get())
        channel = secureChannel
        onTransport(transport)
        onState(ConnectionState.CONNECTED)
        readLoop(secureChannel)
    }

    private fun closeQuietly(closeable: Closeable?) {
        try {
            closeable?.close()
        } catch (_: Exception) {
        }
    }

    private fun connectOnceTcp(attempt: AtomicReference<Closeable?>): SecureChannel? {
        val sock = Socket()
        attempt.set(sock)
        sock.connect(InetSocketAddress(pairing.host, pairing.port), CONNECT_TIMEOUT_MS)
        return handshake(sock.inputStream, sock.outputStream)
    }

    private fun connectOnceBt(macAddress: String, btChannelNum: Int, attempt: AtomicReference<Closeable?>): SecureChannel? {
        if (!BluetoothRfcomm.hasRuntimePermission(context)) return null
        val device = BluetoothRfcomm.findBondedDevice(context, macAddress) ?: return null
        val socket: BluetoothSocket = BluetoothRfcomm.createFixedChannelSocket(device, btChannelNum)
        attempt.set(socket)
        socket.connect()
        return handshake(socket.inputStream, socket.outputStream)
    }

    private fun connectOnceBle(attempt: AtomicReference<Closeable?>): SecureChannel? {
        val socket = BluetoothLeClient.connect(context, attempt) ?: return null
        return handshake(socket.inputStream, socket.outputStream)
    }

    private fun handshake(input: InputStream, output: OutputStream): SecureChannel? {
        val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
        val secureChannel = SecureChannel.clientHandshake(reader, output, pairing.token)

        secureChannel.sendLine(
            protocolJson.encodeToString(HelloMessage.serializer(), HelloMessage(tok = pairing.token))
                .toByteArray(Charsets.UTF_8)
        )

        val ackLine = secureChannel.recvLine() ?: return null
        val ack = parseIncoming(String(ackLine, Charsets.UTF_8))
        if (ack !is IncomingMessage.Ack || !ack.ok) return null
        return secureChannel
    }

    private fun readLoop(secureChannel: SecureChannel) {
        while (shouldRun) {
            val line = secureChannel.recvLine() ?: break
            when (val msg = parseIncoming(String(line, Charsets.UTF_8))) {
                is IncomingMessage.Req -> onRequest(toApprovalRequest(msg.message))
                is IncomingMessage.Notify -> onNotify(toSessionNotify(msg.message))
                is IncomingMessage.Cancel -> onCancel(msg.reqId)
                else -> {}
            }
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
        options = m.options,
    )

    private fun toSessionNotify(m: NotifyMessage) = SessionNotify(
        sessionId = m.session_id,
        cwd = m.cwd,
        message = m.message,
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
        // Generous ceiling over the whole connect+handshake sequence (BLE's
        // own scan/GATT-setup timeouts in BluetoothLeClient already bound
        // themselves to 8s each; this mainly exists to bound the otherwise-
        // unbounded handshake reads in SecureChannel - see race()'s doc).
        private const val CONNECT_ATTEMPT_TIMEOUT_MS = 30_000L
    }
}
