#!/bin/sh
# Installs the current build on the TV, launches it in debug mode and forwards the DevTools port.
# Usage: dev/tv-debug.sh <TV-IP>   → prints the local port for dev/tv.mjs
set -eu
cd "$(dirname "$0")/.."
IP="${1:?TV IP}"
EXT="$HOME/.tizen-extension-platform/server/sdktools"
SDB="$EXT/data/tools/sdb"
sh scripts/tv.sh "$IP" >/dev/null
SERIAL="$IP:26101"
"$SDB" -s "$SERIAL" shell 0 was_kill StPlyrTV01.StePlayer >/dev/null 2>&1 || true
PORT="$("$SDB" -s "$SERIAL" shell 0 debug StPlyrTV01.StePlayer 2>&1 | sed -n 's/.*port: \([0-9]*\).*/\1/p' | tr -d '\r')"
[ -n "$PORT" ] || { echo "debug launch failed"; exit 1; }
"$SDB" -s "$SERIAL" forward tcp:"$PORT" tcp:"$PORT" >/dev/null
echo "$PORT"
