#!/usr/bin/env python3
"""Codex CLI Stop hook: pings a paired Android phone when a turn finishes,
so you know a response is ready without watching the terminal. Thin adapter
over hooks/relay.py's send_notification(), the fire-and-forget counterpart
to relay_approval() - mirrors hooks/stop_notify.py's role for Claude Code.

Unlike Claude Code's Stop event, Codex's Stop is decision-making: it can
extend a turn (e.g. `{"decision": "block", "reason": ...}`) instead of just
letting it end. This hook never wants that - it only observes the turn
ending, it must never keep it going. Per Codex's hook docs
(https://developers.openai.com/codex/hooks), exiting 0 with empty stdout is
fail-open ("continue normally"), the same convention Claude Code's own Stop
hook and this repo's other hooks already rely on, so this script prints
nothing and always exits 0.

Codex's Stop payload includes `last_assistant_message` directly, same as
Claude Code's own Stop event (see hooks/stop_notify.py) - so no transcript
reading needed here.
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from relay import send_notification  # noqa: E402
from relay import log  # noqa: E402

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

    log(f"codex Stop received session={session_id}")
    send_notification(session_id, cwd, message)


if __name__ == "__main__":
    main()
