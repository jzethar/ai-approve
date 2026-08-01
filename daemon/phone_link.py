"""PhoneLink: owns the long-lived connection to the paired phone.

TcpPhoneLink listens on a plain TCP socket bound to all interfaces, so a
phone on the same local network can connect in. This used to be Bluetooth
RFCOMM (BlueZ raw sockets, Linux-only); switched to TCP so the daemon also
works on macOS (and anywhere else) without needing a native Bluetooth
bridge, at the cost of requiring phone and computer to share a network
instead of just being nearby.

Every connection is wrapped in secure_channel.EncryptedConn immediately
after accept() - see that module for why: TCP has no equivalent of
Bluetooth's physical-proximity barrier, so the link needs its own
encryption now that anyone on the LAN can otherwise sniff it in the clear.
"""
import json
import socket
import threading
import time

import protocol
from secure_channel import EncryptedConn, HandshakeError

RECONNECT_DELAY = 2.0


class _StreamPhoneLink:
    """Accept loop + hello handshake + line-JSON read loop over an
    encrypted stream socket. Subclasses only implement _make_listen_socket()."""

    def __init__(self, get_token, on_message, on_connect=None, on_disconnect=None, log=lambda msg: None):
        self._get_token = get_token
        self._on_message = on_message
        self._on_connect = on_connect
        self._on_disconnect = on_disconnect
        self._log = log
        self._lock = threading.Lock()
        self._conn = None
        self._stop = False
        self._thread = None

    def _make_listen_socket(self):
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
        if self._on_disconnect:
            self._on_disconnect()

    def _accept_loop(self):
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
                    try:
                        conn = EncryptedConn(raw_conn, self._get_token(), is_server=True)
                    except HandshakeError as e:
                        self._log(f"key exchange failed: {e!r}")
                        try:
                            raw_conn.close()
                        except OSError:
                            pass
                        continue
                    if self._handshake(conn):
                        with self._lock:
                            self._conn = conn
                        if self._on_connect:
                            self._on_connect()
                        self._read_loop(conn)
                        self._drop_connection()
                    else:
                        conn.close()
            finally:
                try:
                    listen_sock.close()
                except OSError:
                    pass

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


class TcpPhoneLink(_StreamPhoneLink):
    def __init__(self, get_token, port, on_message, on_connect=None, on_disconnect=None,
                 log=lambda msg: None):
        super().__init__(get_token, on_message, on_connect, on_disconnect, log)
        self._port = port

    def _make_listen_socket(self):
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        # "" binds all interfaces (not just loopback), since the phone is a
        # separate device on the local network.
        sock.bind(("", self._port))
        sock.listen(1)
        return sock
