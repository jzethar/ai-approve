"""Marker-file helpers for 'Allow always (this session)', ported from
tg-approve's telegram-approval.py ALLOW_DIR pattern: a session_id__tool_name
marker file means later calls to that tool in that session auto-approve
without contacting the daemon/phone again.
"""
import os

from protocol import state_dir


def _allow_dir():
    d = os.path.join(state_dir(), "session-allow")
    os.makedirs(d, exist_ok=True)
    return d


def _marker_path(session_id, tool_name):
    return os.path.join(_allow_dir(), f"{session_id}__{tool_name}")


def is_allowed(session_id, tool_name):
    return os.path.exists(_marker_path(session_id, tool_name))


def mark_allowed(session_id, tool_name):
    open(_marker_path(session_id, tool_name), "w").close()
