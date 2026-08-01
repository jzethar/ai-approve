"""Wraps a raw stream socket with a per-connection encrypted channel:
ephemeral P-256 ECDH key exchange, HKDF-SHA256 key derivation with the
pre-shared pairing token mixed in, and AES-256-GCM for everything after.

Why this exists: the daemon<->phone link used to be Bluetooth RFCOMM, where
physical proximity was the main barrier to eavesdropping. Switching to plain
TCP over the local network removed that - anyone on the same LAN can now see
raw packets, and previously that meant they'd see every tool_name/tool_input/
cwd/reply in the clear, plus the pre-shared token itself in the "hello"
message. This makes that traffic unreadable to a passive eavesdropper and
resistant to an active MITM.

Security note on the MITM resistance specifically: the pairing token is
never sent over the network (it only ever travels out-of-band, via the QR
code/pairing text). Mixing it into the key derivation means an attacker who
intercepts and substitutes the ECDH public keys ends up deriving different
session keys than the two real endpoints do - so a tampered handshake just
produces AES-GCM authentication failures on the first real message, not a
silently-compromised channel.

P-256 (not X25519) specifically because it's natively supported by both
this library (`cryptography`) and Android's built-in java.security/
javax.crypto - no extra dependency needed on the Android side.
"""
import json
import struct
import threading

from cryptography.hazmat.primitives import hashes, hmac, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

_CURVE = ec.SECP256R1()
_KEX_TIMEOUT = 15


class HandshakeError(Exception):
    pass


def _hkdf_one_block(prk: bytes, info: bytes) -> bytes:
    """RFC 5869 HKDF-Expand for exactly one 32-byte block (all we need,
    since our output length equals SHA-256's block size) - T(1) =
    HMAC-SHA256(PRK, info || 0x01)."""
    h = hmac.HMAC(prk, hashes.SHA256())
    h.update(info + b"\x01")
    return h.finalize()


def _derive_keys(shared_secret: bytes, token: str):
    """PRK = HMAC-SHA256(key=token, msg=shared_secret) - i.e. the pre-shared
    token is HKDF's "salt", the ECDH output is the input keying material.
    Then two independent 32-byte AES keys, one per direction, so each side
    can maintain its own send/recv nonce counter without ever colliding."""
    prk = hmac.HMAC(token.encode("utf-8"), hashes.SHA256())
    prk.update(shared_secret)
    prk = prk.finalize()
    return _hkdf_one_block(prk, b"c2s"), _hkdf_one_block(prk, b"s2c")


def _encode_point(public_key) -> bytes:
    return public_key.public_bytes(
        serialization.Encoding.X962, serialization.PublicFormat.UncompressedPoint
    )


def _decode_point(data: bytes):
    return ec.EllipticCurvePublicKey.from_encoded_point(_CURVE, data)


def _send_line(sock, obj: dict):
    sock.sendall((json.dumps(obj) + "\n").encode("utf-8"))


def _recv_line(sock, timeout) -> bytes:
    sock.settimeout(timeout)
    buf = b""
    try:
        while b"\n" not in buf:
            chunk = sock.recv(4096)
            if not chunk:
                raise HandshakeError("connection closed during key exchange")
            buf += chunk
    except OSError as e:
        raise HandshakeError(f"read failed during key exchange: {e!r}") from e
    finally:
        sock.settimeout(None)
    return buf.split(b"\n", 1)[0]


class EncryptedConn:
    """Drop-in replacement for the raw-socket send/recv-line calls
    _StreamPhoneLink used to make directly - same blocking, line-oriented
    semantics, but everything after construction is AES-GCM encrypted.
    Building one performs the kex handshake, so construction blocks until
    that completes or fails.
    """

    def __init__(self, sock, token: str, is_server: bool):
        self._sock = sock
        self._send_counter = 0
        self._recv_counter = 0
        self._send_lock = threading.Lock()

        own_key = ec.generate_private_key(_CURVE)
        own_pub = _encode_point(own_key.public_key()).hex()

        if is_server:
            # Server speaks second: read the client's kex line, then reply.
            peer_line = _recv_line(sock, _KEX_TIMEOUT)
            peer_pub = _parse_kex(peer_line)
            _send_line(sock, {"type": "kex", "pub": own_pub})
        else:
            _send_line(sock, {"type": "kex", "pub": own_pub})
            peer_line = _recv_line(sock, _KEX_TIMEOUT)
            peer_pub = _parse_kex(peer_line)

        shared_secret = own_key.exchange(ec.ECDH(), _decode_point(bytes.fromhex(peer_pub)))
        c2s_key, s2c_key = _derive_keys(shared_secret, token)
        if is_server:
            self._recv_aead, self._send_aead = AESGCM(c2s_key), AESGCM(s2c_key)
        else:
            self._send_aead, self._recv_aead = AESGCM(c2s_key), AESGCM(s2c_key)

    def send_line(self, data: bytes):
        # Locked end-to-end (not just the counter increment): multiple
        # threads can call send() concurrently (each pending hook request
        # runs on its own thread), and interleaving the encrypt+write of two
        # messages would both scramble the framing and reuse a nonce out of
        # order - either one alone is bad, both together is worse.
        with self._send_lock:
            nonce = struct.pack(">Q", self._send_counter).rjust(12, b"\x00")
            self._send_counter += 1
            ct = self._send_aead.encrypt(nonce, data, None)
            self._sock.sendall(ct.hex().encode("ascii") + b"\n")

    def close(self):
        try:
            self._sock.close()
        except OSError:
            pass

    def recv_line(self, timeout=None):
        """Returns decrypted bytes, or None on a clean EOF (peer closed).
        socket.timeout/OSError propagate rather than collapsing to None, so
        a caller looping on this can tell "still connected, just quiet" from
        "connection is actually gone" apart - same distinction the raw
        socket recv() this replaces required its callers to make."""
        self._sock.settimeout(timeout)
        try:
            buf = b""
            while b"\n" not in buf:
                chunk = self._sock.recv(4096)
                if not chunk:
                    return None
                buf += chunk
        finally:
            self._sock.settimeout(None)
        line = buf.split(b"\n", 1)[0]
        nonce = struct.pack(">Q", self._recv_counter).rjust(12, b"\x00")
        self._recv_counter += 1
        try:
            return self._recv_aead.decrypt(nonce, bytes.fromhex(line.decode("ascii")), None)
        except Exception as e:
            raise HandshakeError(f"decrypt failed (wrong token, or tampering): {e!r}") from e


def _parse_kex(line: bytes) -> str:
    try:
        obj = json.loads(line)
    except json.JSONDecodeError as e:
        raise HandshakeError(f"bad kex JSON: {line!r}") from e
    if obj.get("type") != "kex" or "pub" not in obj:
        raise HandshakeError(f"expected kex message, got: {obj!r}")
    return obj["pub"]
