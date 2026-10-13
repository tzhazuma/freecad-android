#!/usr/bin/env bash
#
# Cross-compile the last two "external, must build it ourselves" dependencies that
# FreeCAD's configure demands, into $PREFIX:
#
#   * Boost    -- components program_options, regex, thread, date_time
#                 (SetupBoost.cmake:6 lists exactly these; the rest of FreeCAD's Boost
#                  use is header-only)
#   * Xerces-C -- REQUIRED by SetupXercesC.cmake:4
#
# Both were resolving to the macOS host libraries before this (CMake found
# /opt/homebrew/lib/libxerces-c.a), which would silently link host code into an
# Android binary. Building them for arm64-v8a removes that trap.
set -euo pipefail

ABI="${ABI:-arm64-v8a}"
API="${API:-24}"
JOBS="${JOBS:-6}"
BOOST_VER="${BOOST_VER:-1.86.0}"
BOOST_VER_US="${BOOST_VER//./_}"
XERCES_VER="${XERCES_VER:-3.3.0}"

WORK="${FREECAD_ANDROID_WORK:-$HOME/freecad-android}"
PREFIX="$WORK/prefix/$ABI"
DL="$WORK/dl"; SRC="$WORK/src"; BUILD="$WORK/build"
mkdir -p "$DL" "$SRC" "$BUILD" "$PREFIX"

ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK:-$(ls -d "$ANDROID_HOME"/ndk/* 2>/dev/null | sort -V | tail -1)}}"
TOOLCHAIN="$NDK/build/cmake/android.toolchain.cmake"
CMAKE_BIN="$(command -v cmake || ls "$ANDROID_HOME"/cmake/*/bin/cmake 2>/dev/null | sort -V | tail -1)"
export PATH="/opt/homebrew/bin:/usr/bin:/bin"

# ── Boost ────────────────────────────────────────────────────────────────────
echo "==== Boost $BOOST_VER ($ABI) ===="
if [ ! -d "$SRC/boost-$BOOST_VER_US" ]; then
    [ -f "$DL/boost.tar.gz" ] || curl -L --fail --retry 3 -o "$DL/boost.tar.gz" \
        "https://github.com/boostorg/boost/releases/download/boost-${BOOST_VER}/boost-${BOOST_VER}-cmake.tar.gz"
    tar -xzf "$DL/boost.tar.gz" -C "$SRC"
fi
BOOST_SRC="$(ls -d "$SRC"/boost-* 2>/dev/null | head -1)"
echo "  source: $BOOST_SRC"

rm -rf "$BUILD/boost-$ABI"
"$CMAKE_BIN" -G Ninja -S "$BOOST_SRC" -B "$BUILD/boost-$ABI" \
    -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" -DANDROID_ABI="$ABI" -DANDROID_PLATFORM="android-$API" \
    -DCMAKE_INSTALL_PREFIX="$PREFIX" -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
    -DBUILD_SHARED_LIBS=OFF \
    -DBOOST_INCLUDE_LIBRARIES="program_options;regex;thread;date_time" \
    -DBOOST_RUNTIME_LINK=ON \
    > "$BUILD/boost-configure.log" 2>&1 || { echo "FAIL: boost configure"; tail -15 "$BUILD/boost-configure.log"; exit 1; }
"$CMAKE_BIN" --build "$BUILD/boost-$ABI" --target install -j"$JOBS" \
    > "$BUILD/boost-make.log" 2>&1 || { echo "FAIL: boost build"; tail -20 "$BUILD/boost-make.log"; exit 1; }
echo "  boost libs: $(ls "$PREFIX"/lib/libboost_*.a 2>/dev/null | wc -l | tr -d ' ')"

# ── Xerces-C ─────────────────────────────────────────────────────────────────
echo "==== Xerces-C $XERCES_VER ($ABI) ===="
if [ ! -d "$SRC/xerces-c-$XERCES_VER" ]; then
    [ -f "$DL/xerces.tar.gz" ] || curl -L --fail --retry 3 -o "$DL/xerces.tar.gz" \
        "https://github.com/apache/xerces-c/archive/refs/tags/v${XERCES_VER}.tar.gz"
    tar -xzf "$DL/xerces.tar.gz" -C "$SRC"
fi
rm -rf "$BUILD/xerces-$ABI"
"$CMAKE_BIN" -G Ninja -S "$SRC/xerces-c-$XERCES_VER" -B "$BUILD/xerces-$ABI" \
    -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" -DANDROID_ABI="$ABI" -DANDROID_PLATFORM="android-$API" \
    -DCMAKE_INSTALL_PREFIX="$PREFIX" -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
    -DBUILD_SHARED_LIBS=OFF \
    -Dnetwork=OFF \
    -Dtranscoder=gnuiconv \
    -Dthreads=ON \
    > "$BUILD/xerces-configure.log" 2>&1 || { echo "FAIL: xerces configure"; tail -15 "$BUILD/xerces-configure.log"; exit 1; }
"$CMAKE_BIN" --build "$BUILD/xerces-$ABI" --target install -j"$JOBS" \
    > "$BUILD/xerces-make.log" 2>&1 || { echo "FAIL: xerces build"; tail -20 "$BUILD/xerces-make.log"; exit 1; }
echo "  xerces: $(ls "$PREFIX"/lib/libxerces-c* 2>/dev/null | head -2)"

echo
echo "==== done ===="
ls "$PREFIX"/lib/libboost_*.a 2>/dev/null | sed 's/^/  /'
ls "$PREFIX"/lib/libxerces-c* 2>/dev/null | sed 's/^/  /'
