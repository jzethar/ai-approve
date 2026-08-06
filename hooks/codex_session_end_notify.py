#!/usr/bin/env python3
"""Codex CLI SessionEnd hook: pings a paired Android phone when the main
Codex thread actually ends.

This is distinct from hooks/codex_stop_notify.py. Codex's Stop event fires at
the end of each assistant turn; SessionEnd fires when the session itself is
closed, archived/deleted while open, or expires after being idle with no
connected clients.

SessionEnd hooks are advisory. This script prints nothing, exits 0, and never
tries to steer Codex.
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from relay import send_notification  # noqa: E402
from relay import log  # noqa: E402


def main():
    payload = json.loads(sys.stdin.read() or "{}")
    session_id = payload.get("session_id", "unknown")
    cwd = payload.get("cwd", "")
    reason = payload.get("reason", "other")

    if reason and reason != "other":
        message = f"Session ended ({reason})"
    else:
        message = "Session ended"

    log(f"codex SessionEnd received session={session_id} reason={reason}")
    send_notification(session_id, cwd, message)


if __name__ == "__main__":
    main()
