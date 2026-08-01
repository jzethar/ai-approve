"""Lockfile-guarded lazy-spawn of approve_daemon.py, used by the hooks/
adapter scripts so the first hook invocation in a while starts the daemon,
and concurrent invocations (multiple coding-agent sessions, whether Claude
Code, Codex, or both at once) don't race to start multiple copies.
"""
import fcntl
import os
import subprocess
import sys
import time

import protocol

DAEMON_SCRIPT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "approve_daemon.py")


def _sock_path():
    return protocol.daemon_sock_path()


def _spawn_lock_path():
    return os.path.join(protocol.state_dir(), "daemon.lock")


def ensure_running(startup_wait=3.0):
    """Make sure the daemon is running and its socket exists. Returns the
    socket path, or None if it couldn't be started/found in time."""
    sock_path = _sock_path()
    if os.path.exists(sock_path):
        return sock_path

    try:
        lock_fd = open(_spawn_lock_path(), "a+")
    except OSError:
        # Some agents run hooks inside a filesystem/network sandbox. In that
        # case a hook can use an already-running daemon socket, but it cannot
        # safely lazy-start the daemon itself.
        return None
    try:
        fcntl.flock(lock_fd, fcntl.LOCK_EX)
        if os.path.exists(sock_path):
            return sock_path
        log_path = os.path.join(protocol.state_dir(), "daemon.log")
        try:
            with open(log_path, "a") as log_f:
                subprocess.Popen(
                    [sys.executable, DAEMON_SCRIPT],
                    stdout=log_f, stderr=log_f,
                    stdin=subprocess.DEVNULL,
                    start_new_session=True,
                )
        except OSError:
            return None
        deadline = time.time() + startup_wait
        while time.time() < deadline:
            if os.path.exists(sock_path):
                return sock_path
            time.sleep(0.1)
        return sock_path if os.path.exists(sock_path) else None
    finally:
        fcntl.flock(lock_fd, fcntl.LOCK_UN)
        lock_fd.close()
