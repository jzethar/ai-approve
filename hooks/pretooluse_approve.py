#!/usr/bin/env python3
"""Claude Code PreToolUse hook: relays approval requests to a paired Android
phone over the local network (via approve_daemon.py) and blocks until you
tap a button on the phone, or times out.

Thin adapter over hooks/relay.py's shared daemon-relay logic - only this
file's stdin/stdout JSON shape is Claude-Code-specific. See
hooks/codex_permission_hook.py for the same relay wired up to Codex CLI's
differently-shaped hook contract instead.

Mirrors tg-approve/telegram-approval.py's contract: print the
hookSpecificOutput JSON to allow/deny, or print nothing to fall back to
Claude Code's normal terminal prompt. That fallback path - no pairing, no
daemon, phone unreachable, timeout, any error - must always fail open.
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from relay import relay_approval  # noqa: E402


def main():
    payload = json.loads(sys.stdin.read() or "{}")
    tool_name = payload.get("tool_name", "unknown")
    tool_input = payload.get("tool_input", {})
    session_id = payload.get("session_id", "unknown")
    cwd = payload.get("cwd", "")

    decision = relay_approval(tool_name, tool_input, session_id, cwd)
    if decision is None:
        return  # fail open: fall back to Claude Code's normal terminal prompt

    action, reason = decision
    print(json.dumps({"hookSpecificOutput": {
        "hookEventName": "PreToolUse",
        "permissionDecision": action,
        "permissionDecisionReason": reason,
    }}))


if __name__ == "__main__":
    main()
