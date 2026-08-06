#!/bin/sh
set -eu

if [ "$#" -lt 1 ]; then
  echo "usage: service-ctl.sh (restart|status|logs|stop)" >&2
  exit 1
fi
action="$1"
label="com.phone-ai-approve.daemon"
log_file="$HOME/.phone-ai-approve/daemon.log"

case "$(uname -s)" in
  Darwin)
    uid=$(id -u)
    target="gui/$uid/$label"
    case "$action" in
      restart)
        launchctl kickstart -k "$target"
        ;;
      status)
        launchctl print "$target" 2>/dev/null || echo "$label is not loaded"
        ;;
      logs)
        touch "$log_file"
        tail -f "$log_file"
        ;;
      stop)
        launchctl bootout "$target" 2>/dev/null || true
        ;;
      *)
        echo "unknown action: $action" >&2
        exit 1
        ;;
    esac
    ;;
  *)
    case "$action" in
      restart) systemctl --user restart phone-ai-approve.service ;;
      status) systemctl --user status phone-ai-approve.service ;;
      logs) journalctl --user -u phone-ai-approve.service -f ;;
      stop) systemctl --user stop phone-ai-approve.service || true ;;
      *)
        echo "unknown action: $action" >&2
        exit 1
        ;;
    esac
    ;;
esac
