#!/bin/sh
set -eu

repo_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
service_dir="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"
service_file="$service_dir/phone-ai-approve.service"

mkdir -p "$service_dir" "$HOME/.phone-ai-approve"

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

