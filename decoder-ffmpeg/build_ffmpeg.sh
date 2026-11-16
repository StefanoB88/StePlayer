#!/bin/bash
# Builds FFmpeg (LGPL 2.1 configuration: no --enable-gpl, no --enable-nonfree) as SHARED libraries for the
# Media3 FFmpeg audio decoder, for armeabi-v7a, arm64-v8a, x86 and x86_64, 16 KB page aligned (Play requirement).
# Shared libraries (libavcodec.so, libavutil.so, libswresample.so) keep FFmpeg replaceable, as the LGPL asks.
#
#   decoder-ffmpeg/build_ffmpeg.sh <ffmpeg-6.0-source-dir> [ndk-dir]
#
# Output: src/main/jniLibs/<abi>/*.so (packaged in the APK) and ffmpeg/include (headers for the JNI wrapper).
# The exact source revision and options are written to ffmpeg/BUILD_INFO.txt (LGPL "corresponding source").
set -eu
cd "$(dirname "$0")"
MODULE="$(pwd)"
FFMPEG_SRC="$(cd "$1" && pwd)"
NDK="${2:-$HOME/Library/Android/sdk/ndk/27.3.13750724}"
API=26   # = minSdk of the app
HOST=darwin-x86_64
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/$HOST/bin"
JOBS="$(sysctl -n hw.ncpu 2>/dev/null || echo 4)"

# Every audio format the Media3 FFmpeg renderer maps (FfmpegLibrary.getCodecName), all LGPL decoders in FFmpeg.
DECODERS=(aac aac_latm ac3 eac3 dca truehd mlp mp1 mp2 mp3 flac alac opus vorbis amrnb amrwb pcm_mulaw pcm_alaw)

COMMON="--target-os=android --enable-shared --disable-static --enable-pic
  --disable-doc --disable-programs --disable-everything --disable-avdevice --disable-avformat --disable-swscale
  --disable-postproc --disable-avfilter --disable-network --disable-symver --enable-swresample
  --disable-v4l2-m2m --disable-vulkan --disable-autodetect"
for d in "${DECODERS[@]}"; do COMMON="$COMMON --enable-decoder=$d"; done
PAGE="-Wl,-z,max-page-size=16384"

build() { # abi, arch, cpu, triple-prefix, extra configure options...
  local abi="$1" arch="$2" cpu="$3" prefix="$4"; shift 4
  local out="$MODULE/build/ffmpeg-install/$abi"
  echo "=== $abi"
  cd "$FFMPEG_SRC"
  make distclean >/dev/null 2>&1 || true
  ./configure --prefix="$out" --arch="$arch" --cpu="$cpu" \
    --cross-prefix="$TOOLCHAIN/$prefix-" --cc="$TOOLCHAIN/$prefix$API-clang" --cxx="$TOOLCHAIN/$prefix$API-clang++" \
    --nm="$TOOLCHAIN/llvm-nm" --ar="$TOOLCHAIN/llvm-ar" --ranlib="$TOOLCHAIN/llvm-ranlib" --strip="$TOOLCHAIN/llvm-strip" \
    $COMMON "$@" >/dev/null
  make -j"$JOBS" >/dev/null
  make install >/dev/null
  mkdir -p "$MODULE/src/main/jniLibs/$abi"
  for lib in avutil swresample avcodec; do
    cp "$out/lib/lib$lib.so" "$MODULE/src/main/jniLibs/$abi/"
    "$TOOLCHAIN/llvm-strip" --strip-unneeded "$MODULE/src/main/jniLibs/$abi/lib$lib.so"
  done
}

build armeabi-v7a arm armv7-a armv7a-linux-androideabi --extra-cflags="-march=armv7-a -mfloat-abi=softfp -mfpu=neon" --extra-ldflags="-Wl,--fix-cortex-a8 $PAGE"
build arm64-v8a aarch64 armv8-a aarch64-linux-android --extra-ldflags="$PAGE"
build x86 x86 i686 i686-linux-android --disable-asm --extra-ldflags="$PAGE"
build x86_64 x86_64 x86-64 x86_64-linux-android --disable-asm --extra-ldflags="$PAGE"

# Headers for the JNI wrapper (same for every ABI).
rm -rf "$MODULE/ffmpeg/include" && mkdir -p "$MODULE/ffmpeg"
cp -R "$MODULE/build/ffmpeg-install/arm64-v8a/include" "$MODULE/ffmpeg/include"
{
  echo "FFmpeg source: https://github.com/FFmpeg/FFmpeg (branch release/6.0)"
  echo "Revision: $(git -C "$FFMPEG_SRC" rev-parse HEAD)"
  echo "License: LGPL v2.1 or later (configured without --enable-gpl / --enable-nonfree)"
  echo "NDK: $(basename "$NDK"), API $API, 16 KB page alignment"
  echo "Configure options: $COMMON"
} > "$MODULE/ffmpeg/BUILD_INFO.txt"
cp "$FFMPEG_SRC/COPYING.LGPLv2.1" "$MODULE/ffmpeg/COPYING.LGPLv2.1"
# Shown in the app (Impostazioni → Licenze open source).
ASSETS="$MODULE/../androidApp/src/main/assets/licenses"
mkdir -p "$ASSETS"
cp "$MODULE/ffmpeg/BUILD_INFO.txt" "$ASSETS/ffmpeg-build.txt"
cp "$MODULE/ffmpeg/COPYING.LGPLv2.1" "$ASSETS/COPYING.LGPLv2.1"
echo "Done: $(ls "$MODULE"/src/main/jniLibs/*/*.so | wc -l | tr -d ' ') libraries"
