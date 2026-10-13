#!/usr/bin/env bash
#
# Cross-compile the native prerequisites FreeCAD needs on Android arm64-v8a:
#   FreeType (static) + OpenCASCADE 7.9.3 (shared)  ->  $PREFIX
#
# Adapted from materializr-cad/materializr android/scripts/setup-deps.sh, which is a
# working, shipped Android+OCCT build. That project is the prior art this port leans
# on: it proved OCCT cross-compiles to Android and recorded the traps. Two of them
# matter enough to repeat here:
#
#   * OCCT must stay on 7.9.x for Android. 8.0 made several TKernel methods
#     header-inline with no out-of-line definition; on Windows Standard_EXPORT is
#     __declspec(dllexport) which forces them to be emitted, but on Android
#     Standard_EXPORT is empty, so -O2 inlines them away and no .so exports them --
#     yet OCCT's own toolkits still reference them, and the app dies at dlopen with
#     "cannot locate symbol _ZNK16Standard_Failure16GetMessageStringEv".
#   * Ship every OCCT .so 16 KB-page-aligned (-Wl,-z,max-page-size=16384), which
#     Android 15+ requires.
#
# Differences from the reference for FreeCAD's benefit: the visualization and data
# exchange modules are left ON (FreeCAD's 3D view and STEP/IGES import need
# TKV3d/TKOpenGl/TKDESTEP/TKDEIGES), and Draw/Tcl remain off.
set -euo pipefail

ABI="${ABI:-arm64-v8a}"
API="${API:-24}"
LDFLAGS_16K="-Wl,-z,max-page-size=16384"
JOBS="${JOBS:-6}"

FT_VER="2.13.3"
OCCT_TAG="V7_9_3"

FT_SHA256="5c3a8e78f7b24c20b25b54ee575d6daa40007a5f4eea2845861c3409b3021747"
OCCT_SHA256="5ecf094ec6b12d5413dfb851d8c3590c354058aee556e32e408bdfbf8c357d57"

ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK:-$(ls -d "$ANDROID_HOME"/ndk/* 2>/dev/null | sort -V | tail -1)}}"
TOOLCHAIN="$NDK/build/cmake/android.toolchain.cmake"
CMAKE_BIN="$(command -v cmake || ls "$ANDROID_HOME"/cmake/*/bin/cmake 2>/dev/null | sort -V | tail -1)"
GEN="${GEN:--G Ninja}"

WORK="${FREECAD_ANDROID_WORK:-$HOME/freecad-android}"
PREFIX="$WORK/prefix/$ABI"
DL="$WORK/dl"; SRC="$WORK/src"; BUILD="$WORK/build"
mkdir -p "$DL" "$SRC" "$BUILD" "$PREFIX"

echo "NDK:     $NDK"
echo "cmake:   $CMAKE_BIN"
echo "PREFIX:  $PREFIX"
[ -f "$TOOLCHAIN" ] || { echo "NDK toolchain not found: $TOOLCHAIN"; exit 1; }

# `sha256sum` is GNU coreutils; macOS ships `shasum`. Accept either.
if command -v sha256sum >/dev/null 2>&1; then SHA="sha256sum"; else SHA="shasum -a 256"; fi

fetch() { # url dest sha256
    [ -f "$2" ] || curl -L --fail --retry 3 -o "$2" "$1"
    if [ -n "${3:-}" ]; then
        echo "$3  $2" | $SHA -c - || {
            echo "ERROR: checksum mismatch for $2 - refusing to build." >&2
            rm -f "$2"; exit 1
        }
    fi
}

# ── FreeType (static) ────────────────────────────────────────────────────────
echo; echo "==== FreeType $FT_VER ($ABI) ===="
fetch "https://download.savannah.gnu.org/releases/freetype/freetype-$FT_VER.tar.gz" \
      "$DL/freetype.tar.gz" "$FT_SHA256"
[ -d "$SRC/freetype-$FT_VER" ] || tar -xzf "$DL/freetype.tar.gz" -C "$SRC"
rm -rf "$BUILD/freetype-$ABI"
"$CMAKE_BIN" $GEN -S "$SRC/freetype-$FT_VER" -B "$BUILD/freetype-$ABI" \
    -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" -DANDROID_ABI="$ABI" -DANDROID_PLATFORM="android-$API" \
    -DCMAKE_INSTALL_PREFIX="$PREFIX" -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF \
    -DFT_DISABLE_HARFBUZZ=ON -DFT_DISABLE_BROTLI=ON -DFT_DISABLE_BZIP2=ON \
    -DFT_DISABLE_PNG=ON -DFT_DISABLE_ZLIB=ON
"$CMAKE_BIN" --build "$BUILD/freetype-$ABI" --target install -j"$JOBS"
echo "FreeType installed: $(ls "$PREFIX/lib/libfreetype.a" 2>/dev/null || echo MISSING)"

# ── OpenCASCADE (shared) ─────────────────────────────────────────────────────
echo; echo "==== OpenCASCADE $OCCT_TAG ($ABI) ===="
fetch "https://github.com/Open-Cascade-SAS/OCCT/archive/refs/tags/$OCCT_TAG.tar.gz" \
      "$DL/occt.tar.gz" "$OCCT_SHA256"
OCCT_DIR="$SRC/OCCT-${OCCT_TAG#V}"
[ -d "$OCCT_DIR" ] || tar -xzf "$DL/occt.tar.gz" -C "$SRC"

rm -rf "$BUILD/occt-$ABI"
"$CMAKE_BIN" $GEN -S "$OCCT_DIR" -B "$BUILD/occt-$ABI" \
    -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" -DANDROID_ABI="$ABI" -DANDROID_PLATFORM="android-$API" \
    -DCMAKE_SHARED_LINKER_FLAGS="$LDFLAGS_16K" -DCMAKE_MODULE_LINKER_FLAGS="$LDFLAGS_16K" \
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$PREFIX" \
    -DCMAKE_FIND_ROOT_PATH="$PREFIX" -DCMAKE_FIND_ROOT_PATH_MODE_LIBRARY=BOTH \
    -DCMAKE_FIND_ROOT_PATH_MODE_INCLUDE=BOTH -DBUILD_LIBRARY_TYPE=Shared \
    -DBUILD_MODULE_Draw=OFF -DBUILD_DOC_Overview=OFF -DBUILD_SAMPLES_QT=OFF \
    -DUSE_FREETYPE=ON -D3RDPARTY_FREETYPE_DIR="$PREFIX" \
    -D3RDPARTY_FREETYPE_INCLUDE_DIR_freetype2="$PREFIX/include/freetype2" \
    -D3RDPARTY_FREETYPE_INCLUDE_DIR_ft2build="$PREFIX/include/freetype2" \
    -D3RDPARTY_FREETYPE_LIBRARY="$PREFIX/lib/libfreetype.a" \
    -D3RDPARTY_FREETYPE_LIBRARY_DIR="$PREFIX/lib" \
    -DUSE_TK=OFF -DUSE_TCL=OFF -DUSE_FREEIMAGE=OFF -DUSE_TBB=OFF -DUSE_VTK=OFF \
    -DUSE_RAPIDJSON=OFF -DUSE_OPENVR=OFF -DUSE_DRACO=OFF -DUSE_FFMPEG=OFF
"$CMAKE_BIN" --build "$BUILD/occt-$ABI" --target install -j"$JOBS"

echo; echo "==== Result ===="
echo "OCCT .so count : $(ls "$PREFIX"/lib/libTK*.so 2>/dev/null | wc -l | tr -d ' ')"
echo "prefix         : $PREFIX"
ls "$PREFIX"/lib/*.so 2>/dev/null | head -12 | sed 's/^/  /'
echo
echo "Next: point FreeCAD's CMake at -DCMAKE_PREFIX_PATH=$PREFIX"
