#!/bin/sh
# Builds the Vega app. The Vega tools break on paths with spaces, so the sources (this folder plus the shared core
# in ../tizenApp/src/core) are copied to a folder without spaces and built there; the .vpkg files come back to dist/.
# Usage: scripts/build.sh [Release|Debug]        then: scripts/run.sh (Vega Virtual Device)
set -e
TYPE="${1:-Release}"
APP="$(cd "$(dirname "$0")/.." && pwd)"
REPO="$(dirname "$APP")"
B="${VEGA_BUILD_DIR:-$HOME/steplayer-vega-build}"
. "$HOME/vega/env"
mkdir -p "$B/vegaApp" "$B/tizenApp/src"
rsync -a --delete --exclude /node_modules --exclude /build --exclude /dist "$APP/" "$B/vegaApp/"
rsync -a --delete "$REPO/tizenApp/src/core/" "$B/tizenApp/src/core/"
cd "$B/vegaApp"
if [ ! -d node_modules ] || [ package.json -nt node_modules/.package-lock.json ]; then npm install --no-audit --no-fund; fi
npx react-native build-vega --build-type "$TYPE"
mkdir -p "$APP/dist"
LOWER="$(echo "$TYPE" | tr '[:upper:]' '[:lower:]')"
for arch in aarch64 armv7 x86_64; do
  f="build/$arch-$LOWER/steplayer_$arch.vpkg"
  [ -f "$f" ] && cp "$f" "$APP/dist/"
done
ls -la "$APP/dist"
