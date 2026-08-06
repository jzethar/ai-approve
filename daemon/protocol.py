"""Shared message schemas for the approval protocol.

Two independent wire protocols use these builders:
- The local-network TCP link between approve_daemon.py and the phone
  (hello/hello_ack/request/response).
- The local AF_UNIX link between hooks/pretooluse_approve.py (the hook) and
  approve_daemon.py.

Framing for both is one JSON object per line: json.dumps(msg) + "\\n". Never
pass indent= to json.dumps here - it must stay single-line, since embedded
newlines in string values are escaped by json.dumps and that's what makes
newline-delimited framing safe.

The Android app's model/Protocol.kt mirrors the TCP link messages by hand;
keep the two in sync when changing field names here.
"""
import json
import os

# v2: pairing payload switched from Bluetooth mac/channel to host/port
# (RFCOMM replaced with local-network TCP so the daemon works on macOS too,
# not just Linux/BlueZ). The field rename alone already makes an old QR
# code decode-fail cleanly against the new app (and vice versa); this bump
# just keeps the marker meaningful for anyone diffing payloads by hand.
#
# v3: the link is now encrypted (see daemon/secure_channel.py) - every
# connection does an ECDH key exchange before the hello/hello_ack/request/
# response messages below, which now travel AES-GCM-encrypted instead of
# in the clear. An old plaintext-only app/daemon talking to a new encrypted
# one fails the handshake outright (it never sends/expects the initial kex
# message), rather than silently working unencrypted.
#
# v4: Bluetooth RFCOMM is back as a second transport, running alongside TCP
# rather than replacing it (see daemon/phone_link.py) - the pairing payload
# now optionally carries bt_mac/bt_channel so the phone can race a Bluetooth
# connection attempt against the TCP one and use whichever completes its
# handshake first. Both new fields are optional/additive (unlike the v1->v2
# rename), so a v3 app or daemon still talks TCP-only with a v4 peer.
#
# v5: classic Bluetooth RFCOMM *server* mode isn't reliably available to
# third-party apps on macOS (bluetoothd owns the RFCOMM mux and doesn't
# expose server-mode registration the way BlueZ does on Linux) - see
# daemon/bt_backend_macos.py. macOS now runs a BLE peripheral/GATT server
# instead and signals this via the optional bt_le flag; Linux is unaffected
# and keeps emitting bt_mac/bt_channel exactly as in v4. Also additive, so a
# v4 peer just doesn't see the new field and stays TCP-only against a macOS
# v5 daemon (which is a no-op change from today, since macOS Bluetooth
# effectively never worked before this).
PROTOCOL_VERSION = 5
TOOL_INPUT_TRUNCATE = 1200
STATE_DIR_NAME = ".phone-ai-approve"
RUNTIME_DIR_NAME = "phone-ai-approve"

# Fixed RFCOMM channel the daemon's Bluetooth listener binds to and the app
# connects to directly (by channel number, not SDP lookup) - see
# daemon/phone_link.py's LinuxBtPhoneLink and daemon/bt_backend_macos.py for
# why a fixed channel sidesteps needing a full SDP server on Linux. Kept here
# as the single source of truth so payload generation (pairing.py) and the
# actual bind/publish (phone_link.py / bt_backend_macos.py) never drift apart.
BT_CHANNEL = 4

# BLE GATT UUIDs for the macOS Bluetooth transport (see
# daemon/bt_backend_macos.py's CBPeripheralManager-based server and
# BluetoothLeClient.kt's matching central). Fixed constants, same spirit as
# BT_CHANNEL above - both sides know them ahead of time, no discovery/
# negotiation needed. RX is phone->daemon (Write, with response - Write-
# without-response requests never reach peripheralManager:didReceiveWriteRequests:,
# so it's deliberately not offered); TX is daemon->phone (Notify). A peer
# that happens to advertise/scan for the same service UUID without knowing
# our pairing token just fails the handshake and gets retried, exactly like
# a stray connection to TCP_PORT already does today - this is not a new
# class of exposure.
BT_LE_SERVICE_UUID = "7f3a2b1a-0001-4c7a-9c1f-6f3f2b6a8e11"
BT_LE_RX_CHAR_UUID = "7f3a2b1a-0002-4c7a-9c1f-6f3f2b6a8e11"
BT_LE_TX_CHAR_UUID = "7f3a2b1a-0003-4c7a-9c1f-6f3f2b6a8e11"

# Actions a phone response (or a local daemon->hook response) may carry.
PHONE_ACTIONS = {"allow", "allow_always", "deny", "other"}
# Local daemon->hook responses additionally cover daemon-side failure modes;
# both mean the hook must print nothing and fail open.
LOCAL_ACTIONS = PHONE_ACTIONS | {"timeout", "no_phone"}


def state_dir() -> str:
    d = os.path.join(os.path.expanduser("~"), STATE_DIR_NAME)
    os.makedirs(d, exist_ok=True)
    return d


def runtime_dir() -> str:
    # Codex can run hooks in a sandbox where ~/.phone-ai-approve is readable but
    # AF_UNIX connect() to a socket there is denied. /tmp is intentionally
    # writable/visible to those hooks, so keep only the local relay socket here.
    d = os.path.join("/tmp", f"{RUNTIME_DIR_NAME}-{os.getuid()}")
    os.makedirs(d, mode=0o700, exist_ok=True)
    try:
        os.chmod(d, 0o700)
    except OSError:
        pass
    return d


def daemon_sock_path() -> str:
    return os.path.join(runtime_dir(), "daemon.sock")


def file_relay_dir() -> str:
    d = os.path.join(runtime_dir(), "requests")
    os.makedirs(d, mode=0o700, exist_ok=True)
    try:
        os.chmod(d, 0o700)
    except OSError:
        pass
    return d


def encode(msg: dict) -> bytes:
    return (json.dumps(msg) + "\n").encode("utf-8")


def summarize_tool_input(tool_input) -> str:
    if isinstance(tool_input, dict):
        text = "\n".join(f"{k}: {v}" for k, v in tool_input.items())
    else:
        text = str(tool_input)
    return text[:TOOL_INPUT_TRUNCATE]


# ---- TCP link messages (daemon <-> phone) ----

def build_hello(token: str) -> dict:
    return {"type": "hello", "tok": token}


def build_hello_ack(ok: bool) -> dict:
    return {"type": "hello_ack", "ok": ok}


def build_request(req_id, session_id, tool_name, tool_input, cwd, ts) -> dict:
    return {
        "type": "request",
        "req_id": req_id,
        "session_id": session_id,
        "tool_name": tool_name,
        "tool_input": summarize_tool_input(tool_input),
        "cwd": cwd,
        "ts": ts,
    }


def build_phone_response(req_id, action, reply=None) -> dict:
    if action not in PHONE_ACTIONS:
        raise ValueError(f"invalid phone action: {action!r}")
    msg = {"type": "response", "req_id": req_id, "action": action}
    if reply is not None:
        msg["reply"] = reply
    return msg


def build_notify(session_id, cwd, message, ts) -> dict:
    return {
        "type": "notify",
        "session_id": session_id,
        "cwd": cwd,
        "message": message,
        "ts": ts,
    }


# ---- Local AF_UNIX messages (hook <-> daemon) ----

def build_local_request(req_id, session_id, tool_name, tool_input, cwd) -> dict:
    return {
        "type": "request",
        "req_id": req_id,
        "session_id": session_id,
        "tool_name": tool_name,
        "tool_input": tool_input,
        "cwd": cwd,
    }


def build_local_notify(session_id, cwd, message) -> dict:
    return {
        "type": "notify",
        "session_id": session_id,
        "cwd": cwd,
        "message": message,
    }


def build_local_response(action, reason="") -> dict:
    if action not in LOCAL_ACTIONS:
        raise ValueError(f"invalid local action: {action!r}")
    return {"action": action, "reason": reason}
