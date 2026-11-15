#!/bin/sh
# Relaunches StePlayer on the TV in debug mode and forwards the DevTools port; prints the port.
IP="${1:?TV IP}"; S="$IP:26101"
SDB="$HOME/.tizen-extension-platform/server/sdktools/data/tools/sdb"
timeout 20 "$SDB" -s "$S" shell 0 was_kill StPlyrTV01.StePlayer >/dev/null 2>&1
P=$(timeout 40 "$SDB" -s "$S" shell 0 debug StPlyrTV01.StePlayer 2>&1 | sed -n 's/.*port: \([0-9]*\).*/\1/p' | tr -d '\r')
"$SDB" -s "$S" forward tcp:"$P" tcp:"$P" >/dev/null
sleep 5
echo "$P"
