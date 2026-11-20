#!/bin/sh
# Builds, packages and installs StePlayer on an LG TV in Developer Mode, then launches it.
# One-time setup: install "Developer Mode" from the LG Content Store on the TV, sign in with the LG developer account,
# turn on Dev Mode Status and Key Server, then register the TV:
#   npx ares-setup-device --add tv --info "{'host':'<TV IP>','port':'9922','username':'prisoner'}"
#   npx ares-novacom --device tv --getkey      (passphrase shown in the Developer Mode app)
# Usage: scripts/webos.sh [device name, default "tv"]
set -e
DEVICE="${1:-tv}"
cd "$(dirname "$0")/.."
npm run package:webos
IPK="$(ls -t dist/com.steplayer.app_*_all.ipk | head -1)"
npx ares-install --device "$DEVICE" "$IPK"
npx ares-launch --device "$DEVICE" com.steplayer.app
echo "Installed $IPK on $DEVICE"
