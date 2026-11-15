#!/bin/sh
# Builds StePlayer, signs it with your Samsung certificate profile and installs it on your TV (developer mode).
# Usage: scripts/tv.sh <TV-IP> [certificate-profile]      (default profile: StePlayer)
# Uses the tools of the VS Code "Tizen Extension" (sdb + tz). See README.md.
set -eu
cd "$(dirname "$0")/.."
TV_IP="${1:?Uso: scripts/tv.sh <IP del televisore> [profilo certificato]}"
PROFILE="${2:-StePlayer}"
EXT="$HOME/.tizen-extension-platform/server/sdktools"
SDB="$EXT/data/tools/sdb"
TZ="$EXT/data/tools/tizen-core/tz"
PROFILES="$EXT/sdk-data/profile/profiles.xml"
[ -x "$SDB" ] && [ -x "$TZ" ] || { echo "Strumenti Tizen non trovati: installa l'estensione \"Tizen Extension\" in VS Code"; exit 1; }
"$SDB" version >/dev/null 2>&1 || { echo "sdb non parte: installa Rosetta con  softwareupdate --install-rosetta --agree-to-license"; exit 1; }

echo "1/4  Build…"
node scripts/build.mjs >/dev/null
rm -rf dist && mkdir -p dist
(cd build && zip -qr -X ../dist/StePlayer-unsigned.wgt config.xml index.html app.js styles.css icon.png)

echo "2/4  Firma con il certificato Samsung \"${PROFILE}\"…"
"$TZ" pack -t wgt -s "$PROFILE" -p "$PROFILES" -b dist/StePlayer-unsigned.wgt -o dist/StePlayer.wgt >/dev/null
rm -f dist/StePlayer-unsigned.wgt

echo "3/4  Collegamento al televisore ${TV_IP}…"
"$SDB" connect "$TV_IP" >/dev/null 2>&1 || true
SERIAL="$("$SDB" devices | awk -v ip="$TV_IP" 'index($1, ip) == 1 { print $1; exit }')"
[ -n "$SERIAL" ] || { echo "Televisore non trovato: è acceso, in modalità sviluppatore e con l'IP di questo Mac?"; exit 1; }
echo "     dispositivo: $SERIAL"

echo "4/4  Installazione e avvio…"
# vd_appinstall updates in place and KEEPS the app data (lists, favourites, progress);
# `tz install` uninstalls first and wipes everything.
REMOTE=/home/owner/share/tmp/sdk_tools/StePlayer.wgt
"$SDB" -s "$SERIAL" shell 0 mkdir -p /home/owner/share/tmp/sdk_tools >/dev/null 2>&1 || true
"$SDB" -s "$SERIAL" push dist/StePlayer.wgt "$REMOTE" >/dev/null
OUT="$("$SDB" -s "$SERIAL" shell 0 vd_appinstall StPlyrTV01.StePlayer "$REMOTE" 2>&1 | tr -d '\r')"
echo "$OUT" | grep -q 'install completed' || { echo "$OUT" | grep -v 'installing\[' | tail -3; echo "Installazione non riuscita"; exit 1; }
"$TZ" run -p StPlyrTV01.StePlayer -e "$SERIAL" >/dev/null 2>&1 || "$SDB" -s "$SERIAL" shell 0 execute StPlyrTV01.StePlayer >/dev/null 2>&1 || true
echo "Fatto: StePlayer è installato sul televisore."
