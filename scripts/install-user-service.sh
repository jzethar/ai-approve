#!/bin/sh
set -eu

repo_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
log_file="$HOME/.phone-ai-approve/daemon.log"

mkdir -p "$HOME/.phone-ai-approve"

case "$(uname -s)" in
  Darwin)
    label="com.phone-ai-approve.daemon"
    agents_dir="$HOME/Library/LaunchAgents"
    plist="$agents_dir/$label.plist"
    python_bin=$(command -v python3)

    mkdir -p "$agents_dir"

    cat > "$plist" <<EOF_PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>Label</key>
	<string>$label</string>
	<key>ProgramArguments</key>
	<array>
		<string>$python_bin</string>
		<string>$repo_dir/daemon/approve_daemon.py</string>
	</array>
	<key>WorkingDirectory</key>
	<string>$repo_dir</string>
	<key>RunAtLoad</key>
	<true/>
	<key>KeepAlive</key>
	<dict>
		<key>SuccessfulExit</key>
		<false/>
	</dict>
	<key>StandardOutPath</key>
	<string>$log_file</string>
	<key>StandardErrorPath</key>
	<string>$log_file</string>
</dict>
</plist>
EOF_PLIST

    uid=$(id -u)
    launchctl bootout "gui/$uid/$label" 2>/dev/null || true
    launchctl bootstrap "gui/$uid" "$plist"
    launchctl enable "gui/$uid/$label"
    launchctl kickstart -k "gui/$uid/$label"
    launchctl print "gui/$uid/$label" | head -n 20
    ;;
  *)
    service_dir="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"
    service_file="$service_dir/phone-ai-approve.service"

    mkdir -p "$service_dir"

    cat > "$service_file" <<EOF_SERVICE
[Unit]
Description=Phone Approve daemon
After=network-online.target

[Service]
Type=simple
WorkingDirectory=$repo_dir
ExecStart=/usr/bin/env python3 $repo_dir/daemon/approve_daemon.py
Restart=on-failure
RestartSec=2

[Install]
WantedBy=default.target
EOF_SERVICE

    systemctl --user daemon-reload
    systemctl --user enable --now phone-ai-approve.service
    systemctl --user status phone-ai-approve.service --no-pager
    ;;
esac
