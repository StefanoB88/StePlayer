#!/bin/sh
# Generates the iOS and tvOS asset catalogs (icons, colors). Re-run after changing the icon design.
# The images come from the shared brand generator (scripts/brand/make-brand.mjs); this script writes the catalog JSON.
set -e
cd "$(dirname "$0")/.."
ICON=":"  # images rendered below by make-brand.mjs

# ---------- iOS ----------
A=Resources/iOS/Assets.xcassets
mkdir -p "$A/AppIcon.appiconset" "$A/AccentColor.colorset" "$A/LaunchBackground.colorset"
echo '{"info":{"author":"xcode","version":1}}' > "$A/Contents.json"
$ICON "$A/AppIcon.appiconset" icon-1024.png:1024x1024:full
cat > "$A/AppIcon.appiconset/Contents.json" <<'J'
{"images":[{"filename":"icon-1024.png","idiom":"universal","platform":"ios","size":"1024x1024"}],"info":{"author":"xcode","version":1}}
J

# ---------- tvOS ----------
T=Resources/tvOS/Assets.xcassets
B="$T/App Icon & Top Shelf Image.brandassets"
mkdir -p "$T/AccentColor.colorset" "$T/LaunchBackground.colorset"
echo '{"info":{"author":"xcode","version":1}}' > "$T/Contents.json"
for stack in "App Icon" "App Icon - App Store"; do
  if [ "$stack" = "App Icon" ]; then S1=400x240; S2=800x480; else S1=1280x768; S2=""; fi
  D="$B/$stack.imagestack"
  mkdir -p "$D"
  for layer in Back Front; do
    L=$(echo $layer | tr 'A-Z' 'a-z')
    I="$D/$layer.imagestacklayer/Content.imageset"
    mkdir -p "$I"
    $ICON "$I" "$L.png:$S1:$L"
    if [ -n "$S2" ]; then
      $ICON "$I" "$L@2x.png:$S2:$L"
      echo "{\"images\":[{\"filename\":\"$L.png\",\"idiom\":\"tv\",\"scale\":\"1x\"},{\"filename\":\"$L@2x.png\",\"idiom\":\"tv\",\"scale\":\"2x\"}],\"info\":{\"author\":\"xcode\",\"version\":1}}" > "$I/Contents.json"
    else
      echo "{\"images\":[{\"filename\":\"$L.png\",\"idiom\":\"tv\",\"scale\":\"1x\"}],\"info\":{\"author\":\"xcode\",\"version\":1}}" > "$I/Contents.json"
    fi
    echo '{"info":{"author":"xcode","version":1}}' > "$D/$layer.imagestacklayer/Contents.json"
  done
  echo '{"info":{"author":"xcode","version":1},"layers":[{"filename":"Front.imagestacklayer"},{"filename":"Back.imagestacklayer"}]}' > "$D/Contents.json"
done
for shelf in "Top Shelf Image:1920x720:3840x1440" "Top Shelf Image Wide:2320x720:4640x1440"; do
  N=$(echo "$shelf" | cut -d: -f1); S1=$(echo "$shelf" | cut -d: -f2); S2=$(echo "$shelf" | cut -d: -f3)
  I="$B/$N.imageset"; mkdir -p "$I"
  $ICON "$I" "shelf.png:$S1:full" "shelf@2x.png:$S2:full"
  echo '{"images":[{"filename":"shelf.png","idiom":"tv","scale":"1x"},{"filename":"shelf@2x.png","idiom":"tv","scale":"2x"}],"info":{"author":"xcode","version":1}}' > "$I/Contents.json"
done
cat > "$B/Contents.json" <<'J'
{"assets":[
 {"filename":"App Icon - App Store.imagestack","idiom":"tv","role":"primary-app-icon","size":"1280x768"},
 {"filename":"App Icon.imagestack","idiom":"tv","role":"primary-app-icon","size":"400x240"},
 {"filename":"Top Shelf Image Wide.imageset","idiom":"tv","role":"top-shelf-image-wide","size":"2320x720"},
 {"filename":"Top Shelf Image.imageset","idiom":"tv","role":"top-shelf-image","size":"1920x720"}
],"info":{"author":"xcode","version":1}}
J

# ---------- shared colors ----------
for C in "$A" "$T"; do
  cat > "$C/AccentColor.colorset/Contents.json" <<'J'
{"colors":[{"color":{"color-space":"srgb","components":{"alpha":"1.000","blue":"0x1F","green":"0xA2","red":"0xFF"}},"idiom":"universal"}],"info":{"author":"xcode","version":1}}
J
  cat > "$C/LaunchBackground.colorset/Contents.json" <<'J'
{"colors":[{"color":{"color-space":"srgb","components":{"alpha":"1.000","blue":"0x1F","green":"0x0E","red":"0x0A"}},"idiom":"universal"}],"info":{"author":"xcode","version":1}}
J
done
node ../scripts/brand/make-brand.mjs ios
echo "Assets generated."
