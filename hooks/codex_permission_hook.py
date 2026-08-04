#!/usr/bin/env python3
"""Codex CLI PermissionRequest hook: relays approval requests to a paired
Android phone over the local network (via approve_daemon.py) and blocks
until you tap a button on the phone, or times out.

Thin adapter over hooks/relay.py's shared daemon-relay logic - see that
file and hooks/pretooluse_approve.py's docstring for the shared design.
Codex's PermissionRequest hook (unlike Claude Code's identically-named but
differently-timed one - see that file's docstring) fires when Codex is about
to ask for approval and can allow, deny, or decline to decide. That makes it
the right integration point here:
https://developers.openai.com/codex/hooks

The official Codex hook contract treats exit 0 with empty stdout as success
with no decision, so the fail-open branch below prints nothing to let Codex's
normal approval flow continue.
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from relay import relay_approval  # noqa: E402
from relay import log  # noqa: E402


def main():
    payload = json.loads(sys.stdin.read() or "{}")
    tool_name = payload.get("tool_name", "unknown")
    tool_input = payload.get("tool_input", {})
    session_id = payload.get("session_id", "unknown")
    cwd = payload.get("cwd", "")

    log(f"codex PermissionRequest received tool={tool_name} session={session_id}")
    decision = relay_approval(tool_name, tool_input, session_id, cwd)
    if decision is None:
        return  # fail open: fall back to Codex's normal approval prompt

    action, reason = decision
    print(json.dumps({"hookSpecificOutput": {
        "hookEventName": "PermissionRequest",
        "decision": {"behavior": action, "message": reason},
    }}))


if __name__ == "__main__":
    main()
