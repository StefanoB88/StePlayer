#!/bin/sh
# App Store screenshots from the iOS / tvOS simulators, using the DEBUG-only demo hooks of AppModel
# (STEPLAYER_DEMO_M3U loads the list, STEPLAYER_DEMO_TAB / STEPLAYER_DEMO_OPEN pick the screen).
# Needs the fictional dev catalogue with PNG artwork (iOS does not draw SVG):  cd tizenApp && PNG_ART=1 node dev/server.mjs 8080
# Usage: scripts/store/shots-apple.sh <simulator-udid> <out-dir> <prefix> [ios|tvos]
# WAIT_SCALE=3 multiplies every wait (slow or busy Mac, first boot of a new simulator).
# SHOT_DISPLAY=1 picks the screen on devices with two displays (iPhone Duo: `simctl io <udid> screenshot` lists them).
set -e
DEV="$1"; OUT="$2"; PREFIX="$3"; KIND="${4:-ios}"
cd "$(dirname "$0")/../.."
if [ "$KIND" = "tvos" ]; then SCHEME=StePlayerTV; PLATFORM="tvOS Simulator"; else SCHEME=StePlayer; PLATFORM="iOS Simulator"; fi
APP=$(xcodebuild -project iosApp/StePlayer.xcodeproj -scheme "$SCHEME" -destination "generic/platform=$PLATFORM" -showBuildSettings 2>/dev/null \
  | awk -F' = ' '/ BUILT_PRODUCTS_DIR /{d=$2} / FULL_PRODUCT_NAME /{n=$2} END{print d"/"n}')
BID=$(defaults read "$APP/Info.plist" CFBundleIdentifier)
mkdir -p "$OUT"

xcrun simctl boot "$DEV" 2>/dev/null || true
xcrun simctl bootstatus "$DEV" -b >/dev/null
[ "$KIND" = "ios" ] && xcrun simctl status_bar "$DEV" override --time 9:41 --dataNetwork wifi --wifiMode active --wifiBars 3 \
  --cellularMode active --cellularBars 4 --batteryState charged --batteryLevel 100
xcrun simctl uninstall "$DEV" "$BID" 2>/dev/null || true
xcrun simctl install "$DEV" "$APP"

shot() { # name, wait seconds, then env assignments
  NAME="$1"; WAIT="$2"; shift 2
  xcrun simctl terminate "$DEV" "$BID" 2>/dev/null || true
  env SIMCTL_CHILD_STEPLAYER_DEMO_M3U=http://127.0.0.1:8080/dev/list.m3u "$@" xcrun simctl launch "$DEV" "$BID" >/dev/null
  sleep $(( WAIT * ${WAIT_SCALE:-1} ))
  xcrun simctl io "$DEV" screenshot --type=png ${SHOT_DISPLAY:+--display=$SHOT_DISPLAY} "$OUT/$PREFIX-$NAME.png" >/dev/null 2>&1
  echo "$OUT/$PREFIX-$NAME.png"
}
shot sync 25 SIMCTL_CHILD_STEPLAYER_DEMO_TAB=home   # first launch: downloads the list (not kept)
rm -f "$OUT/$PREFIX-sync.png"
shot 1-home 8 SIMCTL_CHILD_STEPLAYER_DEMO_TAB=home
shot 2-live 8 SIMCTL_CHILD_STEPLAYER_DEMO_TAB=live
shot 3-guide 9 SIMCTL_CHILD_STEPLAYER_DEMO_OPEN=guide
shot 4-movies 8 SIMCTL_CHILD_STEPLAYER_DEMO_TAB=movies
shot 5-series 9 SIMCTL_CHILD_STEPLAYER_DEMO_OPEN=series
[ "$KIND" = "ios" ] && xcrun simctl status_bar "$DEV" clear
exit 0
