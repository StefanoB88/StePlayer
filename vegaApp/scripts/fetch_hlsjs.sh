#!/bin/sh
# Prepares src/vendor/hls (git-ignored): Amazon's hls.js integration for Vega. The wrapper and polyfills are Amazon
# proprietary (not redistributed in this repository); hls.js 1.5.11 comes from GitHub with Amazon's patches.
#   1. downloads hls-rel-v1.5.11-r1.7 from Amazon and checks its SHA-256
#   2. clones hls.js v1.5.11, applies the patches, installs and builds it (runs hls.js's own npm scripts)
#   3. copies the wrapper, the polyfills and dist/hls.mjs into src/vendor/hls
set -e
APP="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${TMPDIR:-/tmp}/steplayer-hlsjs"
URL=https://amzndevresources.com/vega/media-player/hls-rel-v1.5.11-r1.7-devices_scope.tar.gz
SHA=cc652c54357e071a2a9af9031bc8963e6c4ef3b234f7e729e411c7bf43a1e44e
rm -rf "$WORK" && mkdir -p "$WORK" && cd "$WORK"
curl -fsSL -o hls-rel.tar.gz "$URL"
echo "$SHA  hls-rel.tar.gz" | shasum -a 256 -c -
tar -xzf hls-rel.tar.gz
git clone -q --depth 50 --branch v1.5.11 https://github.com/video-dev/hls.js.git hls.js
( cd hls.js && git checkout -q -b amz_1.5.11 && git apply -3 ../hls-rel/hls-patch/*.patch && npm install --no-audit --no-fund && npm run build )
V="$APP/src/vendor/hls"
rm -rf "$V" && mkdir -p "$V/hlsjsplayer/dist" "$V/hlsjsplayer/polyfills"
cp hls-rel/src/PlayerBase.ts hls-rel/src/PlayerInterface.ts "$V/"
cp hls-rel/src/hlsjsplayer/HlsJsPlayer.ts "$V/hlsjsplayer/"
cp hls-rel/polyfills/*.ts "$V/hlsjsplayer/polyfills/"
cp hls.js/dist/hls.mjs "$V/hlsjsplayer/dist/"
echo "hls.js ready in $V"
