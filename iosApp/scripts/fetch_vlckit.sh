#!/bin/sh
# Downloads the official VideoLAN VLCKit 3 binaries (same artifacts the CocoaPods specs use),
# verifies their SHA-256 and unpacks the xcframeworks into iosApp/Frameworks (git-ignored).
# VLCKit is LGPL 2.1: see "Note legali" in the app for the notice and source link.
set -eu
cd "$(dirname "$0")/.."
DEST="Frameworks"
mkdir -p "$DEST"

fetch() { # name url sha256
  name="$1"; url="$2"; sum="$3"
  if [ -d "$DEST/$name.xcframework" ]; then echo "$name: already present"; return; fi
  tmp="$(mktemp -d)"
  echo "$name: downloading…"
  curl -fL --retry 3 -o "$tmp/pkg.tar.xz" "$url"
  echo "$sum  $tmp/pkg.tar.xz" | shasum -a 256 -c -
  tar -xf "$tmp/pkg.tar.xz" -C "$tmp"
  found="$(find "$tmp" -maxdepth 3 -type d -name "$name.xcframework" | head -1)"
  [ -n "$found" ] || { echo "$name.xcframework not found in archive" >&2; exit 1; }
  mv "$found" "$DEST/"
  lic="$(find "$tmp" -maxdepth 3 -name 'COPYING*' | head -1)"; [ -n "$lic" ] && cp "$lic" "$DEST/$name-COPYING.txt"
  rm -rf "$tmp"
  echo "$name: ok"
}

fetch MobileVLCKit \
  https://download.videolan.org/pub/cocoapods/prod/MobileVLCKit-3.7.5-a213fd11-6de05adc.tar.xz \
  a76f7a6cfcde4209b56f943e6e18400659d8a7a93ff482f8b1d059468d11e6be
fetch TVVLCKit \
  https://download.videolan.org/cocoapods/prod/TVVLCKit-3.0-20260930-0854.tar.xz \
  07892cae8e59e95a23f1fcf11692f802eae106312aa5ad9782faa2556b2d65d5

# iOS 18 / tvOS 18 run on 64-bit devices only: drop 32-bit slices (armv7/armv7s), which also
# trip App Store validation and add ~65 MB to the app. Idempotent.
thin() { # xcframework
  xcf="$DEST/$1.xcframework"
  for bin in "$xcf"/*/"$1".framework/"$1"; do
    case "$bin" in *simulator*) continue ;; esac
    for arch in armv7 armv7s; do
      if lipo -archs "$bin" | tr ' ' '\n' | grep -qx "$arch"; then lipo -remove "$arch" "$bin" -output "$bin"; fi
    done
  done
  /usr/bin/python3 - "$xcf/Info.plist" <<'PY'
import plistlib, sys
p = sys.argv[1]
d = plistlib.load(open(p, "rb"))
for lib in d.get("AvailableLibraries", []):
    lib["SupportedArchitectures"] = [a for a in lib["SupportedArchitectures"] if a not in ("armv7", "armv7s")]
plistlib.dump(d, open(p, "wb"))
PY
}
thin MobileVLCKit
thin TVVLCKit
echo "VLCKit ready (64-bit only)."
