#!/usr/bin/env python3
"""Codex CLI PermissionRequest hook: relays approval requests to a paired
Android phone over the local network (via approve_daemon.py) and blocks
until you tap a button on the phone, or times out.

Thin adapter over hooks/relay.py's shared daemon-relay logic - see that
file and hooks/pretooluse_approve.py's docstring for the shared design.
Codex's PermissionRequest hook (unlike Claude Code's identically-named but
differently-timed one - see that file's docstring) fires *before* Codex's
own approval prompt and can actually block the call, which is what makes
it the right integration point here:
https://doc.jarvisuni.com/openai/codex/hooks.html

NOTE: built from Codex's documented hook JSON schema, not verified against
a live Codex CLI install (not available in the environment this was
developed in) - test this for real before relying on it. In particular,
the docs don't spell out the exact signal for "decline to decide, let
Codex's normal approval prompt continue" beyond "if no hook decides, the
normal approval flow runs"; printing nothing (this file's fail-open
branch) mirrors Claude Code's own convention and is the most conservative
reading, but if Codex expects something else there (e.g. valid JSON with
the decision omitted), adjust that branch below.
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
        return  # fail open: fall back to Codex's normal approval prompt

    action, reason = decision
    print(json.dumps({"hookSpecificOutput": {
        "hookEventName": "PermissionRequest",
        "decision": {"behavior": action, "message": reason},
    }}))


if __name__ == "__main__":
    main()
