"""macOS Bluetooth RFCOMM backend, via pyobjc-framework-IOBluetooth.

Unlike Linux (daemon/phone_link.py's LinuxBtPhoneLink), there's no
AF_BLUETOOTH-style raw socket API on macOS - classic Bluetooth's RFCOMM mux
is owned by userspace (IOBluetooth/bluetoothd), and its server-side model is
built around SDP: publishing an IOBluetoothSDPServiceRecord for a channel is
what makes bluetoothd start accepting inbound connections on it at all, even
though (per daemon/protocol.py's BT_CHANNEL) the Android/Linux side connects
by fixed channel number rather than doing its own SDP lookup. So this module
still needs to publish one local SDP record - just not for peer discovery.

This module hasn't been verified against a real macOS/IOBluetooth install -
the specific SDP-record shape and channel-open notification selectors below
are assembled from IOBluetooth's documented Objective-C API, but IOBluetooth
delegate/notification signatures have shifted across SDK versions and PyObjC's
auto-generated bindings are thin on documentation. Treat this as best-effort:
if it doesn't work as expected, the daemon simply logs a startup failure and
falls back to TCP-only (see make_bt_phone_link() in phone_link.py) - nothing
else regresses, since TCP already covers macOS fully on its own.

IOBluetooth's delegate callbacks (channel-open notifications, incoming data)
only fire while a CFRunLoop is pumping on the thread that registered for
them, so this backend's accept "loop" is really: register for channel-open
notifications, then block in CFRunLoopRun() forever on a dedicated thread,
feeding accepted channels into a queue that _iter_incoming_conns() drains -
everything downstream of that (handshake, arbiter, read loop) is the same
_StreamPhoneLink machinery TCP and Linux Bluetooth already use.
"""
import queue
import socket
import threading

from phone_link import _StreamPhoneLink

_SERVICE_NAME = "phone-ai-approve"


class BluetoothUnavailable(Exception):
    pass


class _RFCOMMSocketShim:
    """Duck-types the sendall/recv/settimeout/close surface EncryptedConn
    needs, over an IOBluetoothRFCOMMChannel's async write call and a data
    delegate that's fed into an internal queue - bridging IOBluetooth's
    callback-based I/O into the blocking read semantics the rest of the
    daemon (originally written for plain sockets) expects."""

    def __init__(self, channel):
        self._channel = channel
        self._buf = b""
        self._inbox = queue.Queue()
        self._closed = False
        self._timeout = None

    # -- delegate callbacks (invoked on the IOBluetooth run-loop thread) --

    def _on_data(self, data: bytes):
        self._inbox.put(data)

    def _on_closed(self):
        self._closed = True
        self._inbox.put(b"")  # unblocks a pending recv() with an EOF-like read

    # -- socket-like interface used by EncryptedConn / _StreamPhoneLink --

    def sendall(self, data: bytes):
        if self._closed:
            raise OSError("RFCOMM channel closed")
        # writeSync_length_ blocks until the write completes (or raises on
        # failure), which is exactly the semantics sendall() needs - no
        # extra synchronization required on top of it.
        result = self._channel.writeSync_length_(data, len(data))
        if result != 0:  # kIOReturnSuccess
            raise OSError(f"RFCOMM write failed: IOReturn {result!r}")

    def settimeout(self, t):
        self._timeout = t

    def recv(self, n):
        # Mirrors real socket.recv(n) semantics - return up to n bytes of
        # whatever's currently available, blocking only when nothing is -
        # rather than accumulating until exactly n bytes have arrived.
        # EncryptedConn.recv_line loops calling this itself until it sees a
        # newline, same as it would over a real socket; accumulating to n
        # here would make it block indefinitely waiting to fill a 4096-byte
        # read even after a short line has already fully arrived.
        if not self._buf:
            try:
                chunk = self._inbox.get(timeout=self._timeout)
            except queue.Empty:
                # socket.timeout specifically (not the builtin TimeoutError it's
                # aliased to only on Python 3.10+) - _StreamPhoneLink._read_loop
                # catches socket.timeout by name, and EncryptedConn.recv_line's
                # sock.settimeout()/recv() dance expects the same on any
                # duck-typed conn, regardless of Python version.
                raise socket.timeout("RFCOMM read timed out")
            if chunk == b"" and self._closed:
                return b""
            self._buf = chunk
        take, self._buf = self._buf[:n], self._buf[n:]
        return take

    def close(self):
        if self._closed:
            return
        self._closed = True
        try:
            self._channel.closeChannel()
        except Exception:
            pass
        self._inbox.put(b"")


class MacBtPhoneLink(_StreamPhoneLink):
    def __init__(self, get_token, channel, on_message, on_connect=None, on_disconnect=None,
                 log=lambda msg: None, arbiter=None):
        super().__init__(get_token, on_message, on_connect, on_disconnect, log, arbiter)
        self._bt_channel_id = channel
        self._incoming = queue.Queue()
        self._delegate = None  # kept alive for the daemon's lifetime; see start()

        try:
            import IOBluetooth  # noqa: F401
        except ImportError as e:
            raise BluetoothUnavailable(f"pyobjc-framework-IOBluetooth not installed: {e!r}") from e

    def start(self):
        # Overrides _StreamPhoneLink.start(): channel acquisition here is
        # callback-driven (via CFRunLoop), not a blocking accept() loop, so
        # the run-loop pump needs its own dedicated thread in addition to
        # the _accept_loop thread that drains _iter_incoming_conns().
        threading.Thread(target=self._run_loop_thread, daemon=True).start()
        super().start()

    def _run_loop_thread(self):
        from CoreFoundation import CFRunLoopRun
        import IOBluetooth

        controller = IOBluetooth.IOBluetoothHostController.defaultController()
        if controller is None or not controller.addressAsString():
            self._log("bt(mac): no Bluetooth controller available - listener not starting")
            return

        try:
            self._publish_sdp_record()
        except Exception as e:
            self._log(f"bt(mac): SDP publish failed: {e!r} - listener not starting")
            return

        self._delegate = _ChannelOpenDelegate(self)
        try:
            IOBluetooth.IOBluetoothRFCOMMChannel.registerForChannelOpenNotifications_selector_withChannelID_direction_(
                self._delegate, "channelOpened:channel:",
                self._bt_channel_id, IOBluetooth.kIOBluetoothUserNotificationChannelDirectionIncoming,
            )
        except Exception as e:
            self._log(f"bt(mac): failed to register for channel-open notifications: {e!r}")
            return

        self._log(f"bt(mac): listening on RFCOMM channel {self._bt_channel_id}")
        CFRunLoopRun()  # never returns in practice; this thread is daemon=True

    def _publish_sdp_record(self):
        import IOBluetooth

        service_dict = {
            "0100": _SERVICE_NAME,  # kIOBluetoothServiceDictServiceName-equivalent attribute id
            "0001": [_service_class_uuid()],  # kIOBluetoothServiceDictAttributeServiceClassIDList
            "0004": [  # ProtocolDescriptorList: L2CAP -> RFCOMM(channel)
                [IOBluetooth.kBluetoothL2CAPPSMRFCOMM],
                [IOBluetooth.kBluetoothRFCOMMChannelID, self._bt_channel_id],
            ],
        }
        record = IOBluetooth.IOBluetoothSDPServiceRecord.publishedServiceRecordWithDict_(service_dict)
        if record is None:
            raise BluetoothUnavailable("IOBluetoothSDPServiceRecord publish returned nil")
        self._sdp_record = record  # keep a reference; unpublishing happens implicitly at process exit

    def _iter_incoming_conns(self):
        while not self._stop:
            try:
                shim = self._incoming.get(timeout=1.0)
            except queue.Empty:
                continue
            yield shim


class _ChannelOpenDelegate:
    """Plain Python object handed to IOBluetooth as the notification
    target - PyObjC lets a regular object receive Objective-C callbacks as
    long as the selector name matches, no explicit NSObject subclass
    required for this simple a use."""

    def __init__(self, link: MacBtPhoneLink):
        self._link = link

    def channelOpened_channel_(self, _notification, channel):
        shim = _RFCOMMSocketShim(channel)
        data_delegate = _ChannelDataDelegate(shim)
        try:
            channel.setDelegate_(data_delegate)
        except Exception as e:
            self._link._log(f"bt(mac): failed to attach data delegate to incoming channel: {e!r}")
            return
        shim._data_delegate = data_delegate  # keep alive for the channel's lifetime
        self._link._incoming.put(shim)


class _ChannelDataDelegate:
    def __init__(self, shim: _RFCOMMSocketShim):
        self._shim = shim

    def rfcommChannelData_data_length_(self, _channel, data, length):
        self._shim._on_data(bytes(data[:length]))

    def rfcommChannelClosed_(self, _channel):
        self._shim._on_closed()


def _service_class_uuid():
    # A fixed, private UUID identifying this service in its SDP record.
    # Never looked up by the connecting side (which dials BT_CHANNEL
    # directly - see phone_link.py) - only needed because IOBluetooth
    # requires *some* ServiceClassIDList entry to publish a record at all.
    import IOBluetooth

    return IOBluetooth.IOBluetoothSDPUUID.uuidWithString_("7f3a2b1a-7a3e-4c7a-9c1f-6f3f2b6a8e11")
