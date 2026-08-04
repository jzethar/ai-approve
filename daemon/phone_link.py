"""PhoneLink: owns the long-lived connection(s) to the paired phone.

TcpPhoneLink listens on a plain TCP socket bound to all interfaces, so a
phone on the same local network can connect in. Bluetooth RFCOMM
(LinuxBtPhoneLink here, bt_backend_macos.MacBtPhoneLink on macOS) runs
alongside it as a second transport - not a replacement - so a phone that's
nearby but off the LAN (or vice versa) still gets through. Only one of
these links is ever "live" for a pairing at a time; see TransportArbiter.

Every connection is wrapped in secure_channel.EncryptedConn immediately
after accept() - see that module for why: neither TCP nor RFCOMM (once
bonded) has a byte-for-byte security guarantee equivalent to Bluetooth's
old physical-proximity-only model, so the link needs its own encryption
regardless of which transport carries it.
"""
import json
import socket
import threading
import time

import protocol
from secure_channel import EncryptedConn, HandshakeError

RECONNECT_DELAY = 2.0


class TransportArbiter:
    """Shared across all of one pairing's PhoneLink instances so that at
    most one transport's connection is ever treated as "live" - if TCP and
    Bluetooth both accept a connection from the same phone near-simultaneously
    (e.g. right after the daemon starts), whichever finishes its handshake
    first wins and the other is dropped, mirroring the phone-side race in
    DaemonLinkManager.kt. There's no per-phone identity beyond the shared
    pairing token, so this assumes (as the rest of the daemon already does)
    a single phone per pairing."""

    def __init__(self):
        self._lock = threading.Lock()
        self._holder = None

    def try_acquire(self, link) -> bool:
        with self._lock:
            if self._holder is not None and self._holder is not link:
                return False
            self._holder = link
            return True

    def release(self, link):
        with self._lock:
            if self._holder is link:
                self._holder = None


class _StreamPhoneLink:
    """Hello handshake + line-JSON read loop over an encrypted stream
    connection. Subclasses only implement _iter_incoming_conns(), which
    yields raw duck-typed connections (sendall/recv/settimeout/close) as
    they arrive - blocking accept() for TCP/Bluetooth-on-Linux, or a
    callback-fed queue for Bluetooth-on-macOS (see bt_backend_macos.py)."""

    def __init__(self, get_token, on_message, on_connect=None, on_disconnect=None,
                 log=lambda msg: None, arbiter=None):
        self._get_token = get_token
        self._on_message = on_message
        self._on_connect = on_connect
        self._on_disconnect = on_disconnect
        self._log = log
        self._arbiter = arbiter
        self._lock = threading.Lock()
        self._conn = None
        self._stop = False
        self._thread = None

    def _iter_incoming_conns(self):
        raise NotImplementedError

    def start(self):
        self._thread = threading.Thread(target=self._accept_loop, daemon=True)
        self._thread.start()

    def stop(self):
        self._stop = True
        with self._lock:
            if self._conn is not None:
                self._conn.close()

    def is_connected(self):
        with self._lock:
            return self._conn is not None

    def send(self, msg):
        with self._lock:
            conn = self._conn
        if conn is None:
            return False
        try:
            conn.send_line(json.dumps(msg).encode("utf-8"))
            return True
        except OSError:
            self._drop_connection()
            return False

    def _drop_connection(self):
        with self._lock:
            if self._conn is not None:
                self._conn.close()
                self._conn = None
        if self._arbiter is not None:
            self._arbiter.release(self)
        if self._on_disconnect:
            self._on_disconnect()

    def _accept_loop(self):
        for raw_conn in self._iter_incoming_conns():
            try:
                conn = EncryptedConn(raw_conn, self._get_token(), is_server=True)
            except HandshakeError as e:
                self._log(f"key exchange failed: {e!r}")
                try:
                    raw_conn.close()
                except OSError:
                    pass
                continue
            if not self._handshake(conn):
                conn.close()
                continue
            # Checked *after* a successful handshake (not on raw accept), so a
            # transport that loses the race still completes a clean hello/
            # hello_ack round trip before being turned away - same "decide by
            # full handshake, not just raw connect" rule the phone-side race
            # in DaemonLinkManager.kt relies on to land on the same winner.
            if self._arbiter is not None and not self._arbiter.try_acquire(self):
                self._log("handshake ok but another transport already holds the connection - dropping")
                conn.close()
                continue
            with self._lock:
                self._conn = conn
            if self._on_connect:
                self._on_connect()
            self._read_loop(conn)
            self._drop_connection()

    def _handshake(self, conn):
        try:
            line = conn.recv_line(timeout=15)
        except HandshakeError as e:
            self._log(f"handshake: decrypt failed: {e!r}")
            return False
        except OSError:
            line = None
        if line is None:
            self._log("handshake: no hello line received (read timed out or connection closed)")
            return False
        try:
            msg = json.loads(line)
        except json.JSONDecodeError:
            self._log(f"handshake: bad JSON: {line!r}")
            return False
        expected_tok = self._get_token()
        if msg.get("type") != "hello" or msg.get("tok") != expected_tok:
            self._log(f"handshake: hello mismatch type={msg.get('type')!r} "
                       f"tok={msg.get('tok')!r} expected_tok={expected_tok!r}")
            try:
                conn.send_line(json.dumps(protocol.build_hello_ack(False)).encode("utf-8"))
            except OSError:
                pass
            return False
        try:
            conn.send_line(json.dumps(protocol.build_hello_ack(True)).encode("utf-8"))
        except OSError as e:
            self._log(f"handshake: send hello_ack failed: {e!r}")
            return False
        return True

    def _read_loop(self, conn):
        while not self._stop:
            try:
                line = conn.recv_line(timeout=30)
            except socket.timeout:
                continue
            except HandshakeError as e:
                self._log(f"read loop: decrypt failed: {e!r}")
                return
            except OSError:
                return
            if line is None:
                return
            try:
                msg = json.loads(line.decode("utf-8"))
            except (json.JSONDecodeError, UnicodeDecodeError):
                continue
            self._on_message(msg)


class _ListenSocketPhoneLink(_StreamPhoneLink):
    """_iter_incoming_conns() via a plain listen()/accept() loop - what both
    TCP and Bluetooth-on-Linux do (their sockets differ only in
    _make_listen_socket()). macOS's Bluetooth backend can't use this: its
    RFCOMM channels arrive via IOBluetooth's async delegate callbacks
    instead of a blocking accept(), so it implements _iter_incoming_conns()
    directly against _StreamPhoneLink (see bt_backend_macos.py)."""

    def _make_listen_socket(self):
        raise NotImplementedError

    def _iter_incoming_conns(self):
        while not self._stop:
            try:
                listen_sock = self._make_listen_socket()
            except OSError as e:
                self._log(f"listen socket setup failed: {e!r}")
                time.sleep(RECONNECT_DELAY)
                continue
            try:
                while not self._stop:
                    try:
                        raw_conn, _addr = listen_sock.accept()
                        self._log(f"accepted raw connection from {_addr!r}")
                    except OSError as e:
                        self._log(f"accept() failed: {e!r}")
                        break
                    yield raw_conn
            finally:
                try:
                    listen_sock.close()
                except OSError:
                    pass


class TcpPhoneLink(_ListenSocketPhoneLink):
    def __init__(self, get_token, port, on_message, on_connect=None, on_disconnect=None,
                 log=lambda msg: None, arbiter=None):
        super().__init__(get_token, on_message, on_connect, on_disconnect, log, arbiter)
        self._port = port

    def _make_listen_socket(self):
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        # "" binds all interfaces (not just loopback), since the phone is a
        # separate device on the local network.
        sock.bind(("", self._port))
        sock.listen(1)
        return sock


class LinuxBtPhoneLink(_ListenSocketPhoneLink):
    """Bluetooth RFCOMM transport on Linux, via the kernel's built-in BlueZ
    RFCOMM socket support (socket.AF_BLUETOOTH/BTPROTO_RFCOMM - stdlib on
    Linux, no extra dependency). Binds a fixed channel (protocol.BT_CHANNEL)
    rather than doing dynamic allocation + SDP registration: the kernel's
    RFCOMM mux accepts connections by channel number alone, and the Android
    side connects directly to that same fixed channel (via a hidden
    createRfcommSocket(int) call - see BluetoothRfcomm.kt) rather than doing
    an SDP lookup, so no local SDP server is needed here at all."""

    def __init__(self, get_token, channel, on_message, on_connect=None, on_disconnect=None,
                 log=lambda msg: None, arbiter=None):
        super().__init__(get_token, on_message, on_connect, on_disconnect, log, arbiter)
        self._channel = channel

    def _make_listen_socket(self):
        sock = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_STREAM, socket.BTPROTO_RFCOMM)
        sock.bind((socket.BDADDR_ANY, self._channel))
        sock.listen(1)
        return sock


def make_bt_phone_link(get_token, channel, on_message, on_connect=None, on_disconnect=None,
                        log=lambda msg: None, arbiter=None):
    """Returns a platform-appropriate Bluetooth PhoneLink, or None (after
    logging why) if this platform/host has no usable Bluetooth backend -
    same soft-fail spirit as pairing.py falling back to 127.0.0.1 when no
    LAN IP can be found. Callers should treat a None return as "just run
    TCP", not as an error."""
    import sys

    kwargs = dict(get_token=get_token, channel=channel, on_message=on_message,
                  on_connect=on_connect, on_disconnect=on_disconnect, log=log, arbiter=arbiter)
    if sys.platform.startswith("linux"):
        if not hasattr(socket, "AF_BLUETOOTH"):
            log("Bluetooth unsupported: this Python build has no socket.AF_BLUETOOTH")
            return None
        return LinuxBtPhoneLink(**kwargs)
    if sys.platform == "darwin":
        try:
            import bt_backend_macos
        except ImportError as e:
            log(f"Bluetooth unavailable on macOS: {e!r} "
                f"(pip install 'pyobjc-framework-IOBluetooth' to enable it)")
            return None
        try:
            return bt_backend_macos.MacBtPhoneLink(**kwargs)
        except bt_backend_macos.BluetoothUnavailable as e:
            log(f"Bluetooth unavailable on macOS: {e!r}")
            return None
    log(f"Bluetooth not supported on platform {sys.platform!r} - TCP only")
    return None
