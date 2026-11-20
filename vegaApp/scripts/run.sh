#!/bin/sh
# Installs and launches the aarch64 build on the Vega Virtual Device (starts it if needed).
set -e
APP="$(cd "$(dirname "$0")/.." && pwd)"
. "$HOME/vega/env"
vega virtual-device status 2>/dev/null | grep -q '"running":true' || vega virtual-device start --timeout 240
vega run-app "$APP/dist/steplayer_aarch64.vpkg" com.steplayer.app.main -d VirtualDevice
