"""macOS Bluetooth backend, via pyobjc-framework-CoreBluetooth: a BLE
peripheral/GATT server, not classic RFCOMM.

Classic Bluetooth's RFCOMM mux on macOS is owned by userspace (IOBluetooth/
bluetoothd), and there's no supported way for a regular third-party app to
register as an RFCOMM *server* on it the way BlueZ lets Linux do (see
daemon/phone_link.py's LinuxBtPhoneLink) - bluetoothd's server-side model
is built around Apple-signed system services, not arbitrary apps publishing
SDP records and accepting inbound channels. BLE peripheral/GATT-server mode
(CBPeripheralManager), by contrast, *is* fully supported for third-party
apps - it's the standard way iOS/macOS apps talk to custom accessories - so
that's what this module uses instead.

Everything downstream of the raw transport is unchanged: _BLEConnShim below
just duck-types the sendall/recv/settimeout/close surface EncryptedConn
needs (same contract the old RFCOMM shim had), chunking bytes into
MTU-sized GATT writes/notifications on the way out and concatenating
incoming write requests on the way back in. The line-JSON framing above it
(secure_channel.py's hex-encoded lines) already establishes message
boundaries, so chunking underneath it is invisible to everything above this
module - no wire-protocol change, no length-prefixing needed. BLE
notifications/writes ride a single ordered, connection-oriented ATT
channel (retransmission is handled by the Bluetooth Link Layer itself), so
once transmit-queue backpressure is respected, treating the chunk stream as
a reliable ordered byte stream is the same assumption every BLE "UART over
GATT" library makes.

Threading model: CBPeripheralManager delegate callbacks run on a GCD
dispatch queue. Passing queue=None binds to the *main* dispatch queue,
which per Apple's docs is only serviced by the process's actual main
thread's run loop - but approve_daemon.py's main thread is permanently
blocked in its own AF_UNIX accept loop, so that pattern (which the old
IOBluetooth backend used, spinning CFRunLoopRun() on a dedicated non-main
thread) would NOT reliably pump a main-queue-bound CBPeripheralManager here.
Instead, this module creates its own background dispatch queue via pyobjc's
`dispatch.dispatch_queue_create` (pyobjc-core >= 4.1) - GCD then drives
delegate callbacks from its own thread pool with no run-loop pumping
required at all. If those bindings aren't available (older pyobjc-core),
this logs and simply doesn't start the BLE listener rather than guessing at
an unreliable fallback.

This module hasn't been verified against a real macOS/CoreBluetooth
install - the delegate method selectors and property names below are
assembled from Apple's documented CoreBluetooth API, but pyobjc's
auto-generated bindings are thin on documentation. Treat this as
best-effort: if it doesn't work as expected, the daemon simply logs a
startup failure and falls back to TCP-only (see make_bt_phone_link() in
phone_link.py) - nothing else regresses, since TCP already covers macOS
fully on its own.
"""
import queue
import socket
import threading
import time

import protocol
from phone_link import _StreamPhoneLink

_SERVICE_NAME = "phone-ai-approve"
_DEFAULT_CHUNK_SIZE = 20  # ATT MTU floor (23) minus the 3-byte ATT header
_WRITE_BACKPRESSURE_TIMEOUT = 30.0


class BluetoothUnavailable(Exception):
    pass


class _BLEConnShim:
    """Duck-types the sendall/recv/settimeout/close surface EncryptedConn
    needs, over a CBPeripheralManager's async notify calls and a queue of
    incoming write-request bytes - bridging CoreBluetooth's callback-based,
    chunked I/O into the blocking read/write semantics the rest of the
    daemon (originally written for plain sockets) expects."""

    def __init__(self, link, central):
        self._link = link
        self._central = central
        self._buf = b""
        self._inbox = queue.Queue()
        self._closed = False
        self._timeout = None
        self._send_lock = threading.Lock()
        self._ready_event = threading.Event()
        self._ready_event.set()

    # -- delegate callbacks (invoked on the CB dispatch queue) --

    def _on_data(self, data: bytes):
        self._inbox.put(data)

    def _on_closed(self):
        self._closed = True
        self._inbox.put(b"")  # unblocks a pending recv() with an EOF-like read

    def _on_ready(self):
        self._ready_event.set()

    # -- socket-like interface used by EncryptedConn / _StreamPhoneLink --

    def sendall(self, data: bytes):
        if self._closed:
            raise OSError("BLE central unsubscribed/disconnected")
        # Locked so a concurrent sendall() from another thread can't
        # interleave chunks of two different messages on the wire.
        with self._send_lock:
            chunk_size = self._chunk_size()
            for i in range(0, len(data), chunk_size):
                self._write_chunk(data[i:i + chunk_size])

    def _chunk_size(self):
        try:
            n = int(self._central.maximumUpdateValueLength())
            return n if n > 0 else _DEFAULT_CHUNK_SIZE
        except Exception:
            return _DEFAULT_CHUNK_SIZE

    def _write_chunk(self, chunk: bytes):
        while True:
            if self._closed:
                raise OSError("BLE central unsubscribed/disconnected")
            self._ready_event.clear()
            ok = self._link._manager.updateValue_forCharacteristic_onSubscribedCentrals_(
                chunk, self._link._tx_char, [self._central])
            if ok:
                return
            # Peripheral's transmit queue is full - wait for
            # peripheralManagerIsReadyToUpdateSubscribers: to fire, then
            # retry this *same* chunk (it was never actually sent).
            if not self._ready_event.wait(timeout=self._timeout or _WRITE_BACKPRESSURE_TIMEOUT):
                raise OSError("BLE write backpressure timed out")

    def settimeout(self, t):
        self._timeout = t

    def recv(self, n):
        # Mirrors real socket.recv(n) semantics - see the old RFCOMM shim
        # this replaces for why (EncryptedConn.recv_line loops calling this
        # until it sees a newline; accumulating to exactly n here would
        # block indefinitely instead of returning a short line immediately).
        if not self._buf:
            try:
                chunk = self._inbox.get(timeout=self._timeout)
            except queue.Empty:
                raise socket.timeout("BLE read timed out")
            if chunk == b"" and self._closed:
                return b""
            self._buf = chunk
        take, self._buf = self._buf[:n], self._buf[n:]
        return take

    def close(self):
        if self._closed:
            return
        self._closed = True
        self._inbox.put(b"")
        self._ready_event.set()


class MacBtPhoneLink(_StreamPhoneLink):
    def __init__(self, get_token, channel, on_message, on_connect=None, on_disconnect=None,
                 log=lambda msg: None, arbiter=None):
        super().__init__(get_token, on_message, on_connect, on_disconnect, log, arbiter)
        # Unused: BLE has no RFCOMM channel number, but make_bt_phone_link()
        # passes the same kwargs to both platforms' link classes for
        # uniformity - see phone_link.py.
        del channel
        self._incoming = queue.Queue()
        self._centrals = {}  # central.identifier().UUIDString() -> _BLEConnShim
        self._manager = None
        self._rx_char = None
        self._tx_char = None
        self._delegate = None  # kept alive for the daemon's lifetime; see start()

        try:
            import CoreBluetooth  # noqa: F401
        except ImportError as e:
            raise BluetoothUnavailable(f"pyobjc-framework-CoreBluetooth not installed: {e!r}") from e

    def start(self):
        # Overrides _StreamPhoneLink.start(): CBPeripheralManager setup and
        # its delegate callbacks run on their own GCD dispatch queue (see
        # module docstring), not the _accept_loop thread that drains
        # _iter_incoming_conns() - so that queue's setup gets its own
        # dedicated thread here, same shape as the old IOBluetooth backend.
        threading.Thread(target=self._setup_thread, daemon=True).start()
        super().start()

    def _setup_thread(self):
        import CoreBluetooth

        try:
            import dispatch
        except ImportError as e:
            self._log(f"bt(mac): pyobjc dispatch bindings unavailable: {e!r} - listener not "
                      f"starting (need pyobjc-core >= 4.1 for CoreBluetooth's dispatch-queue "
                      f"delegate model)")
            return

        gcd_queue = dispatch.dispatch_queue_create(b"phone-ai-approve-ble", None)
        self._delegate = _PeripheralDelegate(self)
        self._manager = CoreBluetooth.CBPeripheralManager.alloc().initWithDelegate_queue_(
            self._delegate, gcd_queue)

        # Delegate callbacks now arrive on gcd_queue, driven by GCD's own
        # thread pool - nothing to pump on this thread. It just blocks to
        # keep strong references to _manager/_delegate alive for the
        # daemon's lifetime (pyobjc objects are refcounted like anything
        # else; letting this thread exit could free them mid-callback).
        while not self._stop:
            time.sleep(1)

    def _add_service_and_advertise(self):
        import CoreBluetooth

        self._rx_char = CoreBluetooth.CBMutableCharacteristic.alloc().initWithType_properties_value_permissions_(
            CoreBluetooth.CBUUID.UUIDWithString_(protocol.BT_LE_RX_CHAR_UUID),
            CoreBluetooth.CBCharacteristicPropertyWrite,
            None,
            CoreBluetooth.CBAttributePermissionsWriteable,
        )
        self._tx_char = CoreBluetooth.CBMutableCharacteristic.alloc().initWithType_properties_value_permissions_(
            CoreBluetooth.CBUUID.UUIDWithString_(protocol.BT_LE_TX_CHAR_UUID),
            CoreBluetooth.CBCharacteristicPropertyNotify,
            None,
            CoreBluetooth.CBAttributePermissionsReadable,
        )
        service = CoreBluetooth.CBMutableService.alloc().initWithType_primary_(
            CoreBluetooth.CBUUID.UUIDWithString_(protocol.BT_LE_SERVICE_UUID), True)
        service.setCharacteristics_([self._rx_char, self._tx_char])
        self._manager.addService_(service)

    def _start_advertising(self):
        import CoreBluetooth

        self._manager.startAdvertising_({
            CoreBluetooth.CBAdvertisementDataServiceUUIDsKey: [
                CoreBluetooth.CBUUID.UUIDWithString_(protocol.BT_LE_SERVICE_UUID)],
            CoreBluetooth.CBAdvertisementDataLocalNameKey: _SERVICE_NAME,
        })
        self._log("bt(mac): advertising BLE GATT service")

    def _on_subscribe(self, central, characteristic):
        if characteristic.UUID().UUIDString().lower() != protocol.BT_LE_TX_CHAR_UUID.lower():
            return
        shim = _BLEConnShim(self, central)
        self._centrals[central.identifier().UUIDString()] = shim
        self._incoming.put(shim)

    def _on_unsubscribe(self, central, characteristic):
        del characteristic
        shim = self._centrals.pop(central.identifier().UUIDString(), None)
        if shim is not None:
            shim._on_closed()

    def _on_write_requests(self, requests):
        for req in requests:
            shim = self._centrals.get(req.central().identifier().UUIDString())
            if shim is not None:
                shim._on_data(bytes(req.value()))
        # Per CBPeripheralManagerDelegate docs: respond once, on the first
        # request in the array, to ack/nack the whole batch.
        self._manager.respondToRequest_withResult_(requests[0], 0)  # CBATTErrorSuccess

    def _on_ready_to_update(self):
        # Global "transmit queue has room again" signal (not per-central) -
        # wake every shim that might be waiting on it.
        for shim in list(self._centrals.values()):
            shim._on_ready()

    def _iter_incoming_conns(self):
        while not self._stop:
            try:
                shim = self._incoming.get(timeout=1.0)
            except queue.Empty:
                continue
            yield shim


class _PeripheralDelegate:
    """Plain Python object handed to CoreBluetooth as the delegate - pyobjc
    lets a regular object receive Objective-C callbacks as long as the
    selector name matches, no explicit NSObject subclass required."""

    def __init__(self, link: MacBtPhoneLink):
        self._link = link

    def peripheralManagerDidUpdateState_(self, manager):
        import CoreBluetooth

        if manager.state() != CoreBluetooth.CBManagerStatePoweredOn:
            return
        try:
            self._link._add_service_and_advertise()
        except Exception as e:
            self._link._log(f"bt(mac): failed to add GATT service: {e!r} - listener not starting")

    def peripheralManager_didAddService_error_(self, manager, service, error):
        del manager, service
        if error is not None:
            self._link._log(f"bt(mac): failed to add GATT service: {error!r} - listener not starting")
            return
        try:
            self._link._start_advertising()
        except Exception as e:
            self._link._log(f"bt(mac): failed to start advertising: {e!r} - listener not starting")

    def peripheralManager_central_didSubscribeToCharacteristic_(self, manager, central, characteristic):
        del manager
        self._link._on_subscribe(central, characteristic)

    def peripheralManager_central_didUnsubscribeFromCharacteristic_(self, manager, central, characteristic):
        del manager
        self._link._on_unsubscribe(central, characteristic)

    def peripheralManager_didReceiveWriteRequests_(self, manager, requests):
        del manager
        self._link._on_write_requests(requests)

    def peripheralManagerIsReadyToUpdateSubscribers_(self, manager):
        del manager
        self._link._on_ready_to_update()
