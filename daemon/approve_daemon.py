#!/usr/bin/env python3
"""Persistent daemon owning the TCP link to the paired phone and the
local AF_UNIX socket that hooks/pretooluse_approve.py talks to.

Unlike tg-approve's file-polling fan-out (needed there because Telegram only
allows one outstanding getUpdates long-poll per bot token), this daemon
holds one long-lived phone connection and keeps each hook's AF_UNIX
connection open for the duration of its request, delivering the phone's
answer straight back down that same socket - no result files, no polling
directory needed.
"""
import json
import os
import socket
import sys
import threading
import time

import pairing
import phone_link
import protocol
import session_allow

# Must stay comfortably under the hook's own deadline (relay.py's
# SOCK_TIMEOUT). Shared with the phone side (which mirrors this value by
# hand as DaemonLinkManager.kt's REQUEST_TIMEOUT_SECONDS) via the "cancel"
# message sent below when this fires - see protocol.REQUEST_TIMEOUT_SECONDS.
LOCAL_TIMEOUT = protocol.REQUEST_TIMEOUT_SECONDS


def log(msg):
    try:
        with open(os.path.join(protocol.state_dir(), "daemon.log"), "a") as f:
            f.write(f"{time.time():.3f} pid={os.getpid()} {msg}\n")
    except OSError:
        pass


class Daemon:
    def __init__(self):
        self._pending_lock = threading.Lock()
        self._pending = {}  # req_id -> dict(event, result, session_id, tool_name)
        self._links = []

        info = pairing.load_pairing()
        if info is None:
            log("no pairing.json found yet - run daemon/pairing.py to pair a phone")
            return

        # Read pairing.json fresh on every handshake attempt (instead of caching
        # the token from this one-time load) so re-pairing - which rotates the
        # token - takes effect on the phone's next connection attempt without
        # needing to restart the daemon.
        def current_token():
            info = pairing.load_pairing()
            return info["tok"] if info else None

        # Shared across both transports so that if TCP and Bluetooth both
        # accept a connection from the same phone (e.g. right after startup),
        # only whichever finishes its handshake first is treated as live -
        # see phone_link.TransportArbiter.
        arbiter = phone_link.TransportArbiter()

        self._links.append(phone_link.TcpPhoneLink(
            get_token=current_token, port=info["port"],
            on_message=self._on_phone_message,
            on_connect=lambda: log("phone connected (tcp)"),
            on_disconnect=self._on_phone_disconnect,
            log=log, arbiter=arbiter,
        ))

        bt_link = phone_link.make_bt_phone_link(
            get_token=current_token, channel=protocol.BT_CHANNEL,
            on_message=self._on_phone_message,
            on_connect=lambda: log("phone connected (bt)"),
            on_disconnect=self._on_phone_disconnect,
            log=log, arbiter=arbiter,
        )
        if bt_link is not None:
            self._links.append(bt_link)

    def start(self):
        for link in self._links:
            link.start()
        threading.Thread(target=self._serve_file_relay, daemon=True).start()
        self._serve_local()

    def _active_link(self):
        # Safe to return the first connected link found: the shared
        # TransportArbiter guarantees at most one of self._links is ever
        # connected at a time, so there's no ordering-dependent ambiguity.
        return next((link for link in self._links if link.is_connected()), None)

    def _on_phone_disconnect(self):
        log("phone disconnected")
        with self._pending_lock:
            pending = list(self._pending.values())
        for entry in pending:
            entry["result"] = protocol.build_local_response("no_phone", "phone disconnected")
            entry["event"].set()

    def _on_phone_message(self, msg):
        if msg.get("type") != "response":
            return
        req_id = msg.get("req_id")
        action = msg.get("action")
        with self._pending_lock:
            entry = self._pending.get(req_id)
        if entry is None:
            log(f"response for unknown/expired req_id={req_id}")
            return

        if action == "allow_always":
            session_allow.mark_allowed(entry["session_id"], entry["tool_name"])
            local_action, reason = "allow", "Approved via phone (Allow always, this session)"
        elif action == "allow":
            local_action, reason = "allow", "Approved via phone"
        elif action == "deny":
            local_action, reason = "deny", "Denied via phone"
        elif action == "other":
            local_action, reason = "deny", f"User instructions via phone: {msg.get('reply', '')}"
        else:
            log(f"unknown action {action!r} for req_id={req_id}")
            return

        entry["result"] = protocol.build_local_response(local_action, reason)
        entry["event"].set()

    def handle_local_request(self, req):
        if req.get("type") == "notify":
            return self.handle_local_notify(req)

        req_id = req["req_id"]
        session_id = req["session_id"]
        tool_name = req["tool_name"]

        link = self._active_link()
        if link is None:
            return protocol.build_local_response("no_phone", "no phone paired/connected")

        event = threading.Event()
        entry = {"event": event, "result": None, "session_id": session_id, "tool_name": tool_name}
        with self._pending_lock:
            self._pending[req_id] = entry

        sent = link.send(protocol.build_request(
            req_id, session_id, tool_name, req["tool_input"], req["cwd"], time.time(),
            options=req.get("options")))
        if not sent:
            with self._pending_lock:
                self._pending.pop(req_id, None)
            return protocol.build_local_response("no_phone", "phone connection dropped")

        got_it = event.wait(LOCAL_TIMEOUT)
        with self._pending_lock:
            entry = self._pending.pop(req_id, entry)
        if not got_it:
            # Tell the phone to drop this card/notification now rather than
            # leaving it stuck - best-effort: if the link has since dropped,
            # the phone's own self-expiry timer (mirroring LOCAL_TIMEOUT)
            # is the backstop, see DaemonLinkManager.kt.
            still_active = self._active_link()
            if still_active is not None:
                still_active.send(protocol.build_cancel(req_id))
            return protocol.build_local_response("timeout", "no response from phone")
        return entry["result"]

    def handle_local_notify(self, req):
        """Fire-and-forget session-finished ping: best-effort forward to the
        phone if connected, but never blocks waiting for it - there's no
        Allow/Deny to wait on, unlike handle_local_request's approval flow."""
        link = self._active_link()
        if link is not None:
            link.send(protocol.build_notify(
                req["session_id"], req["cwd"], req["message"], time.time()))
        return {"ok": True}

    def _serve_local(self):
        sock_path = protocol.daemon_sock_path()
        _claim_socket_or_exit(sock_path)

        server_sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        server_sock.bind(sock_path)
        server_sock.listen(16)
        log(f"listening on {sock_path}")
        try:
            while True:
                conn, _ = server_sock.accept()
                threading.Thread(target=self._handle_local_conn, args=(conn,), daemon=True).start()
        finally:
            server_sock.close()
            try:
                os.remove(sock_path)
            except FileNotFoundError:
                pass

    def _handle_local_conn(self, conn):
        try:
            conn.settimeout(LOCAL_TIMEOUT + 10)
            buf = b""
            while b"\n" not in buf:
                chunk = conn.recv(65536)
                if not chunk:
                    return
                buf += chunk
            line = buf.split(b"\n", 1)[0]
            req = json.loads(line.decode("utf-8"))
            resp = self.handle_local_request(req)
            conn.sendall(protocol.encode(resp))
        except (OSError, json.JSONDecodeError, KeyError) as e:
            log(f"local conn error: {e!r}")
        finally:
            conn.close()

    def _serve_file_relay(self):
        relay_dir = protocol.file_relay_dir()
        log(f"watching file relay in {relay_dir}")
        while True:
            try:
                names = [name for name in os.listdir(relay_dir) if name.endswith(".req")]
            except OSError as e:
                log(f"file relay list failed: {e!r}")
                time.sleep(1)
                continue
            for name in names:
                req_path = os.path.join(relay_dir, name)
                inflight_path = req_path + ".inflight"
                try:
                    os.replace(req_path, inflight_path)
                except FileNotFoundError:
                    continue
                except OSError as e:
                    log(f"file relay claim failed for {name}: {e!r}")
                    continue
                threading.Thread(
                    target=self._handle_file_relay_request,
                    args=(inflight_path,),
                    daemon=True,
                ).start()
            time.sleep(0.1)

    def _handle_file_relay_request(self, inflight_path):
        try:
            with open(inflight_path, "r") as f:
                req = json.load(f)
            resp = self.handle_local_request(req)
            resp_path = inflight_path[:-len(".req.inflight")] + ".resp"
            tmp_path = resp_path + ".tmp"
            with open(tmp_path, "wb") as f:
                f.write(protocol.encode(resp))
            os.replace(tmp_path, resp_path)
        except (OSError, json.JSONDecodeError, KeyError) as e:
            log(f"file relay request error: {e!r}")
        finally:
            try:
                os.remove(inflight_path)
            except FileNotFoundError:
                pass


def _claim_socket_or_exit(sock_path):
    if not os.path.exists(sock_path):
        return
    probe = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    try:
        probe.settimeout(1)
        probe.connect(sock_path)
        probe.close()
        log("another daemon instance is already listening - exiting")
        sys.exit(0)
    except OSError:
        probe.close()
        os.remove(sock_path)  # stale socket from a crashed previous run


def main():
    Daemon().start()


if __name__ == "__main__":
    main()
