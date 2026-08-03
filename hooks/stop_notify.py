#!/usr/bin/env python3
"""Claude Code Stop hook: pings a paired Android phone when a turn finishes,
so you know a response is ready without watching the terminal. Thin adapter
over hooks/relay.py's send_notification(), the fire-and-forget counterpart
to relay_approval() - there's no Allow/Deny here, so unlike
pretooluse_approve.py this never blocks or prints hookSpecificOutput; it
must never affect whether the session actually stops.

Uses the payload's `last_assistant_message` directly rather than reading
transcript_path - Claude Code's own hooks docs warn the transcript file is
written asynchronously and may still lag the in-memory conversation when
Stop fires, so parsing it here raced the write and could grab the
*previous* turn's reply instead of the one that just finished (confirmed:
that's exactly what happened before this fix).
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from relay import send_notification  # noqa: E402

SNIPPET_LEN = 200


def main():
    payload = json.loads(sys.stdin.read() or "{}")
    if payload.get("stop_hook_active"):
        return  # already inside a recursive Stop-hook re-fire; don't double-notify

    session_id = payload.get("session_id", "unknown")
    cwd = payload.get("cwd", "")

    text = (payload.get("last_assistant_message") or "").strip()
    if text:
        message = text[:SNIPPET_LEN]
    else:
        message = f"Finished responding in {cwd}" if cwd else "Finished responding"

    send_notification(session_id, cwd, message)


if __name__ == "__main__":
    main()
