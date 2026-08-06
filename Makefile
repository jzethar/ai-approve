.DEFAULT_GOAL := help
.PHONY: help pair pair-web daemon service-install service-restart service-status service-logs stop test-client apk install app logs clean

PORT ?= 8765

help:
	@echo "phone-ai-approve"
	@echo ""
	@echo "  make pair          generate a pairing token/QR in the terminal (daemon/pairing.py)"
	@echo "  make pair-web      same, but serves the QR at http://127.0.0.1:\$$(PORT) (PORT=... to override)"
	@echo "  make daemon        run the approval daemon in the foreground"
	@echo "  make service-install install/start the daemon as a user service (launchd on macOS, systemd on Linux)"
	@echo "  make service-status show the daemon user-service status"
	@echo "  make service-logs  tail the daemon user-service logs"
	@echo "  make stop          kill a running daemon"
	@echo "  make test-client   run the fake-phone CLI against a running daemon"
	@echo "  make apk           build the Android app (gradlew assembleDebug)"
	@echo "  make install       install the built debug APK via adb"
	@echo "  make app           apk + install"
	@echo "  make logs          tail the daemon log"
	@echo "  make clean         gradle clean + remove daemon __pycache__"

pair:
	python3 daemon/pairing.py

pair-web:
	python3 daemon/pairing_web.py $(PORT)

daemon:
	python3 daemon/approve_daemon.py

service-install:
	sh scripts/install-user-service.sh

service-restart:
	sh scripts/service-ctl.sh restart

service-status:
	sh scripts/service-ctl.sh status

service-logs:
	sh scripts/service-ctl.sh logs

stop:
	pkill -f 'daemon/approve_daemon.py' || true
	sh scripts/service-ctl.sh stop

test-client:
	python3 test/fake_phone_client.py

apk:
	./gradlew assembleDebug

install:
	adb install -r app/build/outputs/apk/debug/app-debug.apk

app: apk install

logs:
	tail -f ~/.phone-ai-approve/daemon.log

clean:
	./gradlew clean
	find daemon -name '__pycache__' -type d -exec rm -rf {} +
