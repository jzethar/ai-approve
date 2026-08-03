# phone-ai-approve

A coding-agent approval hook - currently [Claude Code](https://claude.com/claude-code)
and [Codex CLI](https://developers.openai.com/codex/cli) - that relays
tool-permission requests directly to an Android phone over your local
network, instead of through a third-party service. Sibling project to
[tg-approve](../tg-approve), which does the same thing via Telegram; this one
exists because relaying approvals through Telegram's servers isn't private
enough for some workflows.

The daemon, protocol, encryption, pairing, and Android app don't know or
care which coding agent is asking - only a thin per-agent hook script
(`hooks/pretooluse_approve.py` for Claude Code, `hooks/codex_permission_hook.py`
for Codex) translates each agent's own hook JSON shape into one shared call.
Adding support for another agent with an equivalent hook is mostly a matter
of writing one more small adapter script - see `hooks/relay.py`.

## How it works

- `hooks/pretooluse_approve.py` (Claude Code) and `hooks/codex_permission_hook.py`
  (Codex CLI) are the actual hook scripts each agent invokes - thin adapters
  that translate their own agent's hook JSON shape to/from
  `hooks/relay.py`'s shared `relay_approval()`, which first checks
  `~/.phone-ai-approve/session-allow/` for an "Allow always (this session)"
  marker; if absent, it asks the local `approve_daemon.py` daemon
  (auto-starting it if needed) for a decision, and blocks until it gets one
  or times out (~100s).
- `daemon/approve_daemon.py` is a small persistent background process. It
  owns the long-lived TCP connection(s) to your paired phone(s), and local
  relay endpoints under `/tmp/phone-ai-approve-$UID/` that hook invocations
  talk to. It prefers a Unix-domain socket (`daemon.sock`), but also watches
  a file relay directory (`requests/`) for sandboxes that deny socket
  `connect()`. Because it holds each hook's request open for the duration of
  the approval, it can push the phone's answer straight back to that hook.
- The phone connects to the computer (not the other way around) by scanning
  a QR code / pairing code shown by `daemon/pairing.py`, which encodes the
  computer's local IP address and a port, plus a random pre-shared token
  used to authenticate the link. Phone and computer just need to be on the
  same local network - no Bluetooth pairing/bonding involved.
- Every connection is encrypted: right after connecting, both sides run an
  ephemeral ECDH key exchange (P-256) and derive AES-256-GCM session keys
  via HKDF, with the pre-shared token mixed into the derivation. Nothing
  about the tool calls, replies, or the token itself travels in the clear -
  see `daemon/secure_channel.py` for the full rationale.
- The Android app (`app/`) can pair with **multiple computers at once** (one
  entry per paired daemon, managed from the Devices dialog) and shows each
  incoming request as a card with **Allow / Allow always / Deny** buttons,
  tagged with which computer it came from. A foreground service
  (`ConnectionService`) keeps all connections alive in the background; an
  optional (off by default) setting lets you respond straight from the
  notification instead of opening the app.
- If there's no pairing, no daemon, the phone isn't connected, or anything
  times out, the hook prints nothing and the agent falls back to its own
  normal terminal permission prompt - this fail-open behavior is
  non-negotiable, matching tg-approve's fallback design.
- `hooks/stop_notify.py` (Claude Code) and `hooks/codex_stop_notify.py`
  (Codex CLI) are a second, simpler kind of hook: instead of blocking on a
  phone decision, they fire a fire-and-forget `notify` message
  (`hooks/relay.py`'s `send_notification()`) whenever each agent's `Stop`
  event fires, so the phone gets a plain "session finished" push with a
  snippet of the last reply. They reuse the same pairing, daemon, and
  encrypted link as approval requests, just without the Allow/Deny round
  trip - the app shows it as a normal notification, not a request card.

## Setup

### 1. Pair a phone

```bash
python3 daemon/pairing.py
```

This prints a pairing code (and a QR rendering if `qrencode` is installed)
containing your computer's local IP address, a port, and a fresh token, and
saves it to `~/.phone-ai-approve/pairing.json`. Re-run any time to rotate the
token or refresh the IP (you'll need to re-pair the phone after - re-scan
the same computer's QR again and it updates in place rather than adding a
duplicate).

Prefer a browser over the terminal? `python3 daemon/pairing_web.py` does the
same thing but serves the QR as a local web page at `http://127.0.0.1:8765`
(binds to loopback only, since the page shows the pairing token in the
clear). Pass a port number as an argument to use a different one.

### 2. Start the daemon

Recommended on Linux, especially for Codex:

```bash
make service-install
```

This installs and starts a `systemd --user` service named
`phone-ai-approve.service`, so the daemon is already running outside Codex's
sandbox when an approval request appears. Useful follow-ups:

```bash
make service-status
make service-logs
make service-restart
```

Foreground/manual mode is still useful for debugging:

```bash
python3 daemon/approve_daemon.py &
```

Or let `hooks/pretooluse_approve.py` lazy-start it on first use once it's
wired into Claude Code (step 4). Don't rely on lazy-start for Codex.

For Codex, start the daemon yourself before relying on phone approvals. Codex
can run hooks inside a filesystem/network sandbox, where the hook may be
unable to create `~/.phone-ai-approve/daemon.lock`, write daemon logs, start a
listening TCP daemon process, or even connect to an AF_UNIX socket. The daemon
service handles this by watching `/tmp/phone-ai-approve-$UID/requests/` as a
file-relay fallback.

### 3. Install and pair the Android app

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Open the app, scan the QR code (or paste the pairing code manually - the
app has a fallback text field for when scanning isn't convenient), grant
the camera/notification permissions it asks for, and confirm it shows
**Connected**. To pair a second computer later, use the "Devices" → "+ Add
device" flow without losing the first pairing.

### 4. Wire the hook into your coding agent

**Claude Code**: add the hook under the **`PreToolUse`** event in
`~/.claude/settings.json` (same wiring style as tg-approve - not
`PermissionRequest`, which fires too late to actually gate the tool call):

```json
{
  "hooks": {
    "PreToolUse": [
      {
        "matcher": ".*",
        "hooks": [
          {
            "type": "command",
            "command": "/absolute/path/to/phone-ai-approve/hooks/pretooluse_approve.py"
          }
        ]
      }
    ]
  }
}
```

Restart/reload your Claude Code session afterward - hook config isn't
hot-reloaded mid-session.

Optionally, also add `hooks/stop_notify.py` under **`Stop`** to get a
plain push notification (with a snippet of Claude's last reply) whenever
a turn finishes - no Allow/Deny involved, just an FYI ping:

```json
{
  "hooks": {
    "Stop": [
      {
        "matcher": ".*",
        "hooks": [
          {
            "type": "command",
            "command": "/absolute/path/to/phone-ai-approve/hooks/stop_notify.py"
          }
        ]
      }
    ]
  }
}
```

**Codex CLI**: add the hook under **`PermissionRequest`** in
`~/.codex/hooks.json` (or the equivalent inline `[hooks]` table in
`~/.codex/config.toml`) - here, unlike Claude Code, `PermissionRequest` *is*
the right event, since Codex's version fires before its own approval prompt:

```json
{
  "hooks": {
    "PermissionRequest": [
      {
        "matcher": ".*",
        "hooks": [
          {
            "type": "command",
            "command": "/absolute/path/to/phone-ai-approve/hooks/codex_permission_hook.py",
            "timeout": 120
          }
        ]
      }
    ]
  }
}
```

Codex prompts to explicitly trust a new non-managed hook the first time it
runs (via its `/hooks` command) - accept that once. This adapter hasn't
been verified against a real Codex install; see its docstring before
relying on it.

Optionally, also add `hooks/codex_stop_notify.py` under **`Stop`** to get
the same "session finished" push as Claude Code. Codex's `Stop` event is
decision-making (it can extend a turn), unlike Claude Code's, but this
adapter only ever observes it - it prints nothing and exits 0, which Codex
treats as fail-open/continue, so it never affects whether the turn
actually stops:

```json
{
  "hooks": {
    "Stop": [
      {
        "hooks": [
          {
            "type": "command",
            "command": "/absolute/path/to/phone-ai-approve/hooks/codex_stop_notify.py",
            "timeout": 30
          }
        ]
      }
    ]
  }
}
```

Note `Stop` doesn't support `matcher` in Codex - any matcher there is
ignored. This adapter is unverified against a real Codex install for the
same reason as `codex_permission_hook.py` above.

## Requirements

- Python 3 with `pip install -r daemon/requirements.txt` (just the
  `cryptography` package, for the encrypted link), plus `daemon/pairing.py`'s
  optional use of the external `qrencode` binary for terminal QR rendering.
- Linux or macOS, with the computer and phone on the same local network (the
  daemon binds a plain TCP socket - no Bluetooth stack needed on either
  side).
- An Android phone (API 26+) with a camera for QR scanning; exempt the app
  from battery optimization if you want the connection to survive the phone
  being idle for a long time.

## Testing without the Android app

`test/fake_phone_client.py` is a CLI fake-phone that speaks the exact same
line-JSON protocol over the same TCP transport:

```bash
python3 daemon/pairing.py
python3 daemon/approve_daemon.py &
python3 test/fake_phone_client.py
# in another terminal, trigger a hook invocation manually:
echo '{"tool_name":"Bash","tool_input":{"command":"echo hi"},"session_id":"test","cwd":"/tmp"}' \
  | python3 hooks/pretooluse_approve.py
```

Type `allow` / `allow_always` / `deny` / `other <text>` in the test-client
terminal when a request appears.

## Files

Runtime state lives mostly under `~/.phone-ai-approve/` (gitignored, not in
this repo): `daemon.log`, `pairing.json`, `session-allow/`. The local hook
relay endpoints live under `/tmp/phone-ai-approve-$UID/`: `daemon.sock` for the
normal AF_UNIX relay and `requests/` for sandboxed Codex hooks that need the
file-relay fallback.

**Linux/macOS side:**
- `hooks/pretooluse_approve.py` - the Claude Code `PreToolUse` hook adapter.
- `hooks/stop_notify.py` - the Claude Code `Stop` hook adapter; sends a
  fire-and-forget "session finished" push instead of an approval request.
- `hooks/codex_permission_hook.py` - the Codex CLI `PermissionRequest` hook adapter.
- `hooks/codex_stop_notify.py` - the Codex CLI `Stop` hook adapter; sends a
  fire-and-forget "session finished" push instead of an approval request.
- `hooks/relay.py` - shared daemon-relay logic both adapters call into.
- `daemon/approve_daemon.py` - persistent daemon owning the phone link(s).
- `daemon/phone_link.py` - `TcpPhoneLink`: accept loop, hello handshake, line-JSON read loop.
- `daemon/secure_channel.py` - `EncryptedConn`: ECDH key exchange + AES-GCM encryption for every connection.
- `daemon/pairing.py` - generates the pairing token + QR code (host/port + token).
- `daemon/pairing_web.py` - same, but serves the QR as a local web page instead of terminal ANSI art.
- `daemon/session_allow.py` - "Allow always" marker-file helpers.
- `daemon/protocol.py` - shared message schemas for both wire protocols.
- `daemon/spawn.py` - lazy-spawn helper used by the hook.
- `daemon/requirements.txt` - the one dependency (`cryptography`) the daemon needs.
- `test/fake_phone_client.py` - fake-phone CLI for testing without the Android app.
- `Makefile` - shortcuts for the commands above (`make help` to list them).

**Android app** (`app/`, package `com.phoneapprove.app`):
- `MainActivity.kt` - switches between the Pairing and Requests screens based on saved pairings; applies the selected theme.
- `ui/PairingScreen.kt` - CameraX + ML Kit QR scanning, with a manual-paste fallback; supports adding a device alongside existing ones.
- `ui/RequestsScreen.kt` - Compose list of pending requests, the Devices management dialog, and the Settings dialog.
- `service/ConnectionService.kt` - foreground service keeping all paired connections alive in the background.
- `service/ApprovalActionReceiver.kt` - handles Allow/Allow always/Deny taps from notifications.
- `data/DaemonLinkManager.kt` - owns one TCP connection per paired computer, connect/reconnect loop, and message routing.
- `data/SecureChannel.kt` - Kotlin mirror of `daemon/secure_channel.py`'s ECDH + AES-GCM handshake.
- `data/PairingRepository.kt` - `EncryptedSharedPreferences`-backed storage for the list of paired computers.
- `data/SettingsRepository.kt` - theme and notification-actions preferences.
- `model/Protocol.kt` - `kotlinx.serialization` mirror of `daemon/protocol.py`'s schemas.

## Security notes

- `~/.phone-ai-approve/pairing.json` contains the shared secret token that
  authenticates the link - treat it like any other credential. It's never
  sent over the network itself (only used out-of-band, via the QR/pairing
  code, and mixed into the encryption key derivation) - see "How it works".
- The link is encrypted end-to-end (ECDH + AES-GCM, a fresh key per
  connection), so a passive eavesdropper on the same LAN can't read your
  tool calls, replies, or the token. It's also resistant to an active
  MITM: an attacker who doesn't know the token ends up deriving different
  session keys than the real endpoints even if they intercept and
  substitute the key-exchange messages, so tampering just breaks the
  connection (AES-GCM auth failure) rather than silently succeeding.
- That said, there's no physical-proximity requirement anymore the way
  Bluetooth had: **anyone who can reach the daemon's TCP port on your local
  network and has the token can still act as your phone.** The token is a
  128-bit random secret, so guessing it isn't practical - but don't let the
  pairing QR/code leak (screenshots, screen-sharing, a shoulder-surfed
  terminal), and be more cautious pairing over untrusted/shared networks
  than you'd need to be with Bluetooth's shorter range.
- Anyone who taps a button on your paired phone can approve/deny tool calls
  in your Claude Code sessions - same trust model as [tg-approve's](https://github.com/jzethar/claude-telegram-approvals) Telegram
  chat, just narrowed to "whoever holds this specific phone." Turning on
  "Approve from notification" (off by default, in the app's Settings)
  widens that slightly to "whoever can see/reach your phone's notifications
  while it's unlocked" - notifications with actions are hidden on the lock
  screen for this reason.
