"""Shared daemon-relay logic for coding-agent approval hooks.

Talks to approve_daemon.py over its local AF_UNIX socket and blocks for a
decision. Every per-agent hook script (pretooluse_approve.py for Claude
Code, codex_permission_hook.py for Codex CLI, ...) is a thin adapter around
relay_approval() below - only each agent's own hook JSON shape is agent-
specific; the daemon, protocol, pairing, and encryption underneath don't
know or care which coding agent is asking.
"""
import json
import os
import socket
import sys
import time

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "daemon"))
import protocol       # noqa: E402
import session_allow   # noqa: E402
import spawn            # noqa: E402

SOCK_TIMEOUT = 115.0  # must stay comfortably under each agent's own hook deadline


def log(msg):
    try:
        with open(os.path.join(protocol.state_dir(), "daemon.log"), "a") as f:
            f.write(f"{time.time():.3f} pid={os.getpid()} hook {msg}\n")
    except OSError:
        pass


def relay_approval(tool_name, tool_input, session_id, cwd, options=None):
    """Blocks until the phone answers (or a session-allow marker short-
    circuits it). Returns ("allow"|"deny", reason), or None if the caller
    should fail open instead - no pairing, no daemon, phone unreachable,
    timeout, or any I/O error along the way all collapse to None here, so
    every adapter script has exactly one fallback branch to implement
    (mirroring their own agent's "let the normal approval prompt continue"
    contract).

    `options`, when given, is a list of proposed-answer labels (e.g. from
    Claude Code's AskUserQuestion tool - see pretooluse_approve.py) that the
    phone should offer as buttons instead of the default Allow/Allow always/
    Deny, since none of those three is a real answer to a question. Tapping
    one comes back as the existing "other" phone action (free-text reply)
    with that label as the reply, which resolves to a "deny" decision whose
    reason is the chosen answer - same mechanism a typed reply already uses,
    just pre-filled instead of requiring the phone's keyboard.
    """
    if session_allow.is_allowed(session_id, tool_name):
        return "allow", "Auto-approved via phone (Allow always, this session)"

    sock_path = spawn.ensure_running()
    if not sock_path:
        log(f"daemon not available for tool={tool_name} session={session_id}")
        return None

    req_id = f"{int(time.time() * 1e9)}_{os.getpid()}"
    req = protocol.build_local_request(req_id, session_id, tool_name, tool_input, cwd, options=options)

    try:
        conn = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        conn.settimeout(SOCK_TIMEOUT)
        conn.connect(sock_path)
        conn.sendall(protocol.encode(req))
        buf = b""
        while b"\n" not in buf:
            chunk = conn.recv(65536)
            if not chunk:
                break
            buf += chunk
        conn.close()
    except OSError as e:
        log(f"daemon socket connect/IO failed: {e!r}; trying file relay")
        buf = _relay_via_files(req_id, req)
        if not buf:
            return None

    if not buf:
        log("daemon closed connection with no response")
        return None

    try:
        result = json.loads(buf.split(b"\n", 1)[0].decode("utf-8"))
    except (json.JSONDecodeError, UnicodeDecodeError):
        return None

    action = result.get("action")
    reason = result.get("reason", "")
    log(f"RESOLVED req_id={req_id} tool={tool_name} action={action}")

    if action == "allow":
        return "allow", reason or "Approved via phone"
    if action == "deny":
        return "deny", reason or "Denied via phone"
    return None  # timeout / no_phone / anything else -> fail open


def send_notification(session_id, cwd, message):
    """Fire-and-forget session-finished ping - unlike relay_approval, never
    blocks waiting for a reply (there's nothing to wait on), so hooks that
    call this return immediately. Any failure (no pairing, no daemon, phone
    unreachable) is silently swallowed - same fail-open spirit as
    relay_approval, just with no fallback branch for the caller to handle.
    """
    sock_path = spawn.ensure_running()
    if not sock_path:
        log(f"daemon not available for notify session={session_id}")
        return

    msg = protocol.build_local_notify(session_id, cwd, message)
    try:
        conn = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        conn.settimeout(2.0)
        conn.connect(sock_path)
        conn.sendall(protocol.encode(msg))
        # Read (briefly) rather than closing straight away: the daemon's ack
        # is near-instant for notify (it never waits on the phone), and
        # reading it lets the daemon's own sendall() complete cleanly instead
        # of hitting a closed pipe and logging a spurious error every time.
        try:
            conn.recv(256)
        except OSError:
            pass
        conn.close()
    except OSError as e:
        log(f"notify socket connect/IO failed: {e!r}")


def _relay_via_files(req_id, req):
    """Fallback for sandboxes that deny AF_UNIX connect().

    Codex can allow hooks to write /tmp while denying socket connect(), even to
    a visible socket. The daemon watches this private runtime directory and
    writes the response beside the request.
    """
    try:
        relay_dir = protocol.file_relay_dir()
        req_path = os.path.join(relay_dir, f"{req_id}.req")
        tmp_path = os.path.join(relay_dir, f"{req_id}.{os.getpid()}.tmp")
        resp_path = os.path.join(relay_dir, f"{req_id}.resp")
        with open(tmp_path, "w") as f:
            json.dump(req, f)
            f.write("\n")
        os.replace(tmp_path, req_path)
    except OSError as e:
        log(f"file relay request write failed: {e!r}")
        return None

    deadline = time.time() + SOCK_TIMEOUT
    while time.time() < deadline:
        try:
            with open(resp_path, "rb") as f:
                buf = f.read()
            os.remove(resp_path)
            return buf
        except FileNotFoundError:
            time.sleep(0.1)
        except OSError as e:
            log(f"file relay response read failed: {e!r}")
            return None
    log(f"file relay timed out req_id={req_id}")
    return None
