#!/usr/bin/env bash
#
# Cross-compile ICU4C for Android arm64-v8a and install into $PREFIX.
#
# Why this is needed at all: FreeCAD's CMakeLists.txt:94 does
#   find_package(ICU REQUIRED COMPONENTS uc i18n)
# and the NDK's own ICU is not usable for that -- it exposes only 32 headers in the
# sysroot (unistr.h, ucnv.h, udat.h, unum.h among the missing) and ships one combined
# libicu.so behind a restricted API, not the separate libicuuc/libicui18n that CMake's
# FindICU looks for.
#
# Why two builds: ICU uses autoconf, not CMake, and its cross-compile path needs ICU
# *host* tools (genccode, genrb, pkgdata, ...) of the same version to generate the data.
# So: build once for the host, then configure the target with --with-cross-build
# pointing at that host build tree.
set -euo pipefail

ABI="${ABI:-arm64-v8a}"
API="${API:-24}"
JOBS="${JOBS:-6}"
ICU_VER="${ICU_VER:-74.2}"
ICU_VER_US="${ICU_VER//./_}"          # 74.2 -> 74_2 (ICU's tarball directory naming)

WORK="${FREECAD_ANDROID_WORK:-$HOME/freecad-android}"
PREFIX="$WORK/prefix/$ABI"
DL="$WORK/dl"; SRC="$WORK/src"; BUILD="$WORK/build"
HOSTPFX="$BUILD/icu-host-$ICU_VER"
HOSTBUILD="$BUILD/icu-host-build-$ICU_VER"
XBUILD="$BUILD/icu-$ABI"
mkdir -p "$DL" "$SRC" "$BUILD" "$PREFIX"

ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK:-$(ls -d "$ANDROID_HOME"/ndk/* 2>/dev/null | sort -V | tail -1)}}"
HOSTTAG="darwin-x86_64"
TC="$NDK/toolchains/llvm/prebuilt/$HOSTTAG"
BIN="$TC/bin"

ICU_SRC="$SRC/icu"
if [ ! -d "$ICU_SRC/source" ]; then
    echo "== fetching ICU $ICU_VER =="
    # ICU's GitHub releases use a different tag layout per version; the release/ path is stable.
    curl -L --fail --retry 3 -o "$DL/icu4c.tgz" \
        "https://github.com/unicode-org/icu/releases/download/release-${ICU_VER//./-}/icu4c-${ICU_VER_US}-src.tgz"
    mkdir -p "$ICU_SRC"
    tar -xzf "$DL/icu4c.tgz" -C "$ICU_SRC" --strip-components=1
fi
[ -d "$ICU_SRC/source" ] || { echo "FAIL: ICU source not at $ICU_SRC/source"; exit 1; }

# ── 1. host build (provides the tools the cross build needs) ─────────────────
if [ ! -x "$HOSTPFX/bin/genccode" ]; then
    echo "== host build (tools for the cross build) =="
    rm -rf "$HOSTBUILD"; mkdir -p "$HOSTBUILD"
    cd "$HOSTBUILD"
    # Force Apple's clang for the host build. This box has the Arm Toolchain for
    # Embedded (ATfE) ahead of /usr/bin on PATH, and that clang targets bare-metal
    # aarch64 with no macOS C runtime -- configure fails with
    # "C compiler cannot create executables" (missing Scrt1.o / crti.o / -lgcc).
    env -u CC -u CXX -u CFLAGS -u CXXFLAGS -u LDFLAGS \
        PATH="/usr/bin:/bin:/usr/sbin:/sbin" \
        CC=/usr/bin/clang CXX=/usr/bin/clang++ \
        "$ICU_SRC/source/configure" --prefix="$HOSTPFX" --disable-samples --disable-tests \
        > "$BUILD/icu-host-configure.log" 2>&1
    make -j"$JOBS" > "$BUILD/icu-host-make.log" 2>&1
    make install > "$BUILD/icu-host-install.log" 2>&1
    echo "   host tools: $(ls "$HOSTPFX/bin" 2>/dev/null | tr '\n' ' ')"
fi

# ── 2. cross build for Android ──────────────────────────────────────────────
echo "== cross build ICU $ICU_VER for $ABI (API $API) =="
export PATH="$BIN:$PATH"
export CC="$BIN/aarch64-linux-android${API}-clang"
export CXX="$BIN/aarch64-linux-android${API}-clang++"
export AR="$BIN/llvm-ar"
export RANLIB="$BIN/llvm-ranlib"
export STRIP="$BIN/llvm-strip"
export CFLAGS="-O2 -fPIC -D__ANDROID_API__=$API"
export CXXFLAGS="$CFLAGS"

rm -rf "$XBUILD"; mkdir -p "$XBUILD"
cd "$XBUILD"
# NOTE: do NOT pass --disable-renaming here. ICU's installed headers declare
# U_ICU_NAMESPACE as icu_74 (renaming ON), so a library built with renaming
# disabled exports icu:: symbols and every consumer then fails to link with
#   undefined symbol: icu_74::Locale::createFromName(char const*)
# Keeping renaming ON also gives Xerces-C the ucnv_open_74 symbols it wants.
"$ICU_SRC/source/configure" \
    --host=aarch64-linux-android \
    --with-cross-build="$HOSTBUILD" \
    --prefix="$PREFIX" \
    --enable-static --disable-shared \
    --disable-samples --disable-tests --disable-extras --disable-layout \
    --disable-icuio --disable-tools \
    > "$BUILD/icu-x-configure.log" 2>&1
make -j"$JOBS" > "$BUILD/icu-x-make.log" 2>&1
make install > "$BUILD/icu-x-install.log" 2>&1

echo
echo "== result =="
echo "ICU libs  : $(ls "$PREFIX"/lib/libicu*.a 2>/dev/null | wc -l | tr -d ' ')"
ls "$PREFIX"/lib/libicu*.a 2>/dev/null | sed 's/^/  /'
echo "headers   : $(ls "$PREFIX"/include/unicode 2>/dev/null | wc -l | tr -d ' ')"
echo "prefix    : $PREFIX"
