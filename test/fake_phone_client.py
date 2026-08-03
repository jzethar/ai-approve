#!/usr/bin/env python3
"""Fake-phone dev tool for exercising approve_daemon.py without the Android
app. Speaks the exact same line-JSON protocol the real app will, over the
same TCP transport (connects to 127.0.0.1 by default, which reaches a
daemon bound to all interfaces just fine).

Usage:
    python3 daemon/pairing.py       # once, to create pairing.json
    python3 daemon/approve_daemon.py &
    python3 test/fake_phone_client.py

For each incoming request it prints the details and prompts for a response:
    allow / allow_always / deny / other <free text>
"""
import argparse
import json
import os
import socket
import sys
import threading

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "daemon"))
import pairing         # noqa: E402
import protocol        # noqa: E402
from secure_channel import EncryptedConn  # noqa: E402

_pending_req_id = [None]


def _reader_thread(conn):
    while True:
        line = conn.recv_line()
        if line is None:
            print("\n[connection closed by daemon]")
            os._exit(0)
        msg = json.loads(line.decode("utf-8"))
        if msg.get("type") == "hello_ack":
            print(f"[hello_ack ok={msg.get('ok')}]")
        elif msg.get("type") == "request":
            _pending_req_id[0] = msg["req_id"]
            print(
                f"\n=== approval request ===\n"
                f"req_id: {msg['req_id']}\n"
                f"tool:   {msg['tool_name']}\n"
                f"cwd:    {msg['cwd']}\n"
                f"input:  {msg['tool_input']}\n"
                f"respond with: allow / allow_always / deny / other <text>\n> ",
                end="", flush=True,
            )
        elif msg.get("type") == "notify":
            print(
                f"\n=== session notify ===\n"
                f"session: {msg['session_id']}\n"
                f"cwd:     {msg['cwd']}\n"
                f"message: {msg['message']}\n"
            )


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int)
    args = ap.parse_args()

    info = pairing.load_pairing()
    if info is None:
        sys.exit("no pairing.json found - run `python3 daemon/pairing.py` first")

    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.connect((args.host, args.port or info["port"]))
    conn = EncryptedConn(sock, info["tok"], is_server=False)

    conn.send_line(json.dumps(protocol.build_hello(info["tok"])).encode("utf-8"))
    threading.Thread(target=_reader_thread, args=(conn,), daemon=True).start()

    print("Connected. Waiting for approval requests (Ctrl-C to quit)...")
    for line in sys.stdin:
        parts = line.strip().split(maxsplit=1)
        if not parts:
            continue
        action = parts[0]
        reply = parts[1] if len(parts) > 1 else None
        req_id = _pending_req_id[0]
        if req_id is None:
            print("(no pending request)")
            continue
        if action not in ("allow", "allow_always", "deny", "other"):
            print("action must be one of: allow / allow_always / deny / other")
            continue
        conn.send_line(json.dumps(protocol.build_phone_response(req_id, action, reply)).encode("utf-8"))
        _pending_req_id[0] = None


if __name__ == "__main__":
    main()
