#!/usr/bin/env python3
"""Generates a pairing token + QR payload and persists it to
~/.phone-ai-approve/pairing.json. Run this once (and again any time you want
to re-pair/rotate the token) before scanning the code with the Android app.
"""
import argparse
import json
import os
import secrets
import socket
import subprocess
import sys
import uuid

import protocol

DEFAULT_PORT = 8642

# Interface name prefixes that are never the right address to hand a phone:
# VPN tunnels (which may hold the default route, fooling the UDP-connect
# trick below) and container/virtual bridges. Linux-only, best-effort.
_SKIP_IFACE_PREFIXES = (
    "lo", "tun", "tap", "wg", "ppp", "utun", "tailscale", "zt",
    "docker", "veth", "br-", "virbr",
)
# Prefixes of interfaces that are typically physical/Wi-Fi NICs - preferred
# first when more than one non-VPN candidate is found.
_PREFERRED_IFACE_PREFIXES = ("eth", "en", "wlan", "wlp", "enp", "wl")


def _linux_lan_candidates():
    """Best-effort list of (interface, ipv4) pairs from `ip -o -4 addr
    show`, skipping loopback/VPN/virtual interfaces and preferring
    physical-looking NIC names. Linux-only (relies on `ip` from
    iproute2); callers must catch failures and fall back."""
    out = subprocess.run(
        ["ip", "-o", "-4", "addr", "show"],
        capture_output=True, text=True, check=True, timeout=2,
    ).stdout
    candidates = []
    for line in out.splitlines():
        parts = line.split()
        iface, addr = parts[1], parts[3].split("/")[0]
        if iface.startswith(_SKIP_IFACE_PREFIXES):
            continue
        candidates.append((iface, addr))
    candidates.sort(key=lambda c: not c[0].startswith(_PREFERRED_IFACE_PREFIXES))
    return candidates


def local_lan_ip():
    """Return this machine's LAN-facing IP address.

    On Linux, first tries to enumerate real network interfaces via `ip
    addr` and picks a non-VPN one - a full-tunnel VPN (WireGuard,
    Tailscale, etc.) grabbing the default route would otherwise make the
    fallback below advertise a VPN-only address the phone can't reach.

    Falls back to the old trick: open a UDP socket "connected" to a
    public address - no packets are actually sent, connect() on a UDP
    socket just asks the kernel to pick the local route/address it would
    use, which we then read back via getsockname(). Pure stdlib, works
    identically on Linux/macOS/Windows - unlike the old `hcitool dev`
    approach, which was BlueZ/Linux-only.
    """
    try:
        candidates = _linux_lan_candidates()
        if candidates:
            return candidates[0][1]
    except (OSError, subprocess.SubprocessError):
        pass

    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        try:
            s.connect(("8.8.8.8", 80))
            return s.getsockname()[0]
        except OSError:
            return "127.0.0.1"


def local_bt_mac_address():
    """Best-effort local Bluetooth adapter address, or None if there isn't
    one / it can't be determined - callers should treat None as "omit
    Bluetooth from the pairing payload", not as an error, exactly like
    local_lan_ip() falling back to 127.0.0.1 above.

    Linux: /sys/class/bluetooth/hci0/address is the standard BlueZ-managed
    sysfs attribute for the first adapter - no subprocess/D-Bus needed for
    the common case. Falls back to parsing `bluetoothctl show`, same
    defensive subprocess pattern as _linux_lan_candidates() above, for
    machines where hci0 isn't the adapter name.

    macOS: goes through IOBluetooth (the same optional dependency the
    daemon's Bluetooth listener itself needs - see bt_backend_macos.py),
    so a missing/unpowered adapter or missing pyobjc install both just
    fall through to None here.
    """
    if sys.platform.startswith("linux"):
        try:
            with open("/sys/class/bluetooth/hci0/address") as f:
                addr = f.read().strip()
            if addr:
                return addr
        except OSError:
            pass
        try:
            out = subprocess.run(
                ["bluetoothctl", "show"], capture_output=True, text=True, timeout=2,
            ).stdout
            for line in out.splitlines():
                line = line.strip()
                if line.startswith("Controller "):
                    return line.split()[1]
        except (OSError, subprocess.SubprocessError):
            pass
        return None

    if sys.platform == "darwin":
        try:
            from IOBluetooth import IOBluetoothHostController
        except ImportError:
            return None
        try:
            controller = IOBluetoothHostController.defaultController()
            return controller.addressAsString() if controller else None
        except Exception:
            return None

    return None


def pairing_path():
    return os.path.join(protocol.state_dir(), "pairing.json")


def load_pairing():
    path = pairing_path()
    if not os.path.exists(path):
        return None
    with open(path) as f:
        return json.load(f)


def generate_pairing(port=DEFAULT_PORT, name=None, host=None):
    host = host or local_lan_ip()
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
    bt_mac = local_bt_mac_address()
    if bt_mac is None:
        print(
            "warning: no Bluetooth adapter found (or it's off) - the pairing code will "
            "be TCP-only; Bluetooth can still work later if you pair again once an "
            "adapter is available.",
            file=sys.stderr,
        )
    elif sys.platform == "darwin":
        # macOS runs a BLE peripheral/GATT server rather than classic RFCOMM
        # (see daemon/bt_backend_macos.py) - it doesn't need bt_mac/bt_channel
        # at all, since BLE central-side discovery is by service UUID (a
        # fixed constant, see protocol.BT_LE_SERVICE_UUID), not device
        # address. bt_mac's mere presence above is reused only as the
        # "there's a usable Bluetooth controller" signal.
        payload["bt_le"] = True
    else:
        payload["bt_mac"] = bt_mac
        payload["bt_channel"] = protocol.BT_CHANNEL
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
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--host",
        default=os.environ.get("PHONE_APPROVE_HOST"),
        help="override the auto-detected LAN IP (or set PHONE_APPROVE_HOST) - "
             "use this if auto-detection picks a VPN/virtual interface instead "
             "of the network your phone is actually on",
    )
    args = parser.parse_args()

    payload = generate_pairing(host=args.host)
    render_qr(payload)
    print("\nSaved to", pairing_path())
    print("Restart the daemon (or it will pick this up on next lazy-spawn) to use it.")


if __name__ == "__main__":
    main()
