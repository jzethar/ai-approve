#!/usr/bin/env python3
"""Claude Code PreToolUse hook: relays approval requests to a paired Android
phone over the local network (via approve_daemon.py) and blocks until you
tap a button on the phone, or times out.

Thin adapter over hooks/relay.py's shared daemon-relay logic - only this
file's stdin/stdout JSON shape is Claude-Code-specific. See
hooks/codex_permission_hook.py for the same relay wired up to Codex CLI's
differently-shaped hook contract instead. Codex has no equivalent of
AskUserQuestion below - as of writing, its PermissionRequest hook only fires
for shell/apply_patch/MCP tool calls, never a proposed-answer question - so
that adapter has nothing to mirror here.

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


def _question(tool_name, tool_input):
    """AskUserQuestion's tool_input is {"questions": [{"question", "header",
    "options": [{"label", "description"}, ...], "multiSelect"}, ...]} - none
    of Allow/Allow always/Deny is a real answer to that, so pull out the
    question text and proposed labels for relay_approval to show/offer
    instead (see its docstring). Returns (question_text, labels) or (None,
    None). Only handles the single-question, single-select case: with
    several questions (or multiSelect) one tap can't represent a full answer,
    so those fall back to the raw tool_input dump and a plain free-text
    "other" reply on the phone instead of pretending a button captured the
    whole thing.
    """
    if tool_name != "AskUserQuestion":
        return None, None
    questions = tool_input.get("questions") or []
    if len(questions) != 1 or questions[0].get("multiSelect"):
        return None, None
    labels = [o.get("label") for o in questions[0].get("options") or [] if o.get("label")]
    if not labels:
        return None, None
    return questions[0].get("question", ""), labels


def main():
    payload = json.loads(sys.stdin.read() or "{}")
    tool_name = payload.get("tool_name", "unknown")
    tool_input = payload.get("tool_input", {})
    session_id = payload.get("session_id", "unknown")
    cwd = payload.get("cwd", "")

    question, options = _question(tool_name, tool_input)
    display_input = question if question is not None else tool_input
    decision = relay_approval(tool_name, display_input, session_id, cwd, options=options)
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
