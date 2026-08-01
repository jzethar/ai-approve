#!/usr/bin/env python3
"""Generates a pairing token + QR payload and persists it to
~/.phone-ai-approve/pairing.json. Run this once (and again any time you want
to re-pair/rotate the token) before scanning the code with the Android app.
"""
import json
import os
import secrets
import socket
import subprocess
import sys
import uuid

import protocol

DEFAULT_PORT = 8642


def local_lan_ip():
    """Return this machine's LAN-facing IP address.

    Opens a UDP socket "connected" to a public address - no packets are
    actually sent, connect() on a UDP socket just asks the kernel to pick
    the local route/address it would use, which we then read back via
    getsockname(). Pure stdlib, works identically on Linux/macOS/Windows -
    unlike the old `hcitool dev` approach, which was BlueZ/Linux-only.
    """
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        try:
            s.connect(("8.8.8.8", 80))
            return s.getsockname()[0]
        except OSError:
            return "127.0.0.1"


def pairing_path():
    return os.path.join(protocol.state_dir(), "pairing.json")


def load_pairing():
    path = pairing_path()
    if not os.path.exists(path):
        return None
    with open(path) as f:
        return json.load(f)


def generate_pairing(port=DEFAULT_PORT, name=None):
    host = local_lan_ip()
    if host == "127.0.0.1":
        print(
            "warning: couldn't determine a LAN IP address (no network route found) - "
            "using 127.0.0.1, which only works for testing on this same machine. "
            "Re-run pairing.py once this computer is on a network reachable by your phone.",
            file=sys.stderr,
        )
    existing = load_pairing()
    device_id = existing["id"] if existing and "id" in existing else uuid.uuid4().hex
    payload = {
        "v": protocol.PROTOCOL_VERSION,
        "id": device_id,
        "host": host,
        "port": port,
        "tok": secrets.token_hex(16),
        "name": name or socket.gethostname(),
    }
    with open(pairing_path(), "w") as f:
        json.dump(payload, f)
    return payload


def render_qr(payload):
    """Print a scannable QR to the terminal via `qrencode`, if installed.
    Always also prints the raw JSON, since the Android app accepts pasting
    it directly as a fallback when QR rendering isn't available.
    """
    text = json.dumps(payload)
    print(f"Pairing code (paste into the app if you can't scan the QR):\n{text}\n")
    try:
        subprocess.run(["qrencode", "-t", "ANSIUTF8", text], check=True)
    except (FileNotFoundError, subprocess.CalledProcessError):
        print("(qrencode not installed - use the pairing code above instead)")


def main():
    payload = generate_pairing()
    render_qr(payload)
    print("\nSaved to", pairing_path())
    print("Restart the daemon (or it will pick this up on next lazy-spawn) to use it.")


if __name__ == "__main__":
    main()
