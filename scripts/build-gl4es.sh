#!/usr/bin/env bash
# Cross-compile gl4es (GL 1.5 -> GLES translation) and ptitSeb/GLU for Android.
#
# Why this exists: Coin3D renders through the desktop OpenGL fixed-function pipeline
# (immediate mode glBegin/glVertex, the matrix stack, glLight/glMaterial/glTexEnv), which
# OpenGL ES removed entirely. Rewriting that renderer is a multi-month project. gl4es
# implements the fixed pipeline as GLES shaders and ships a drop-in libGL.so, so Coin can
# be built and run unmodified.
#
# Note the two layers gl4es provides:
#   * compile time -- include/GL/*.h declare the desktop GL API, so Coin's source compiles
#   * runtime      -- lib/libGL.so.1 IS libGL.so; Coin's glue layer dlopen()s exactly that name
set -euo pipefail
NDK="${ANDROID_NDK_HOME:-$HOME/Library/Android/sdk/ndk/28.2.13676358}"
WORK="$HOME/freecad-android"
cd "$WORK"
mkdir -p dl src build gl-stubs

[ -d src/gl4es-master ] || { [ -f dl/gl4es.tar.gz ] || curl -sL --fail -o dl/gl4es.tar.gz https://github.com/ptitSeb/gl4es/archive/refs/heads/master.tar.gz; tar -xzf dl/gl4es.tar.gz -C src; }
[ -d src/GLU-master ]    || { [ -f dl/glu.tar.gz ]    || curl -sL --fail -o dl/glu.tar.gz    https://github.com/ptitSeb/GLU/archive/refs/heads/master.tar.gz;    tar -xzf dl/glu.tar.gz    -C src; }

# 1) gl4es: NOX11 (no X11 on Android), GLES2 backend.
cmake -G Ninja -S src/gl4es-master -B build/gl4es-arm64-v8a \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-24 \
  -DCMAKE_BUILD_TYPE=Release -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
  -DNOX11=ON -DNO_GBM=ON -DDEFAULT_ES=2 >/dev/null
ninja -C build/gl4es-arm64-v8a -j6

# 2) GLU (Coin needs gluLookAt / gluProject / gluUnProject).
G=src/GLU-master; BIN="$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin"
SYS="$NDK/toolchains/llvm/prebuilt/darwin-x86_64/sysroot"
mkdir -p build/glu-arm64-v8a
for f in $(find "$G/src" -name '*.c' | sort); do
  o="build/glu-arm64-v8a/$(echo "$f" | tr '/' '_').o"
  "$BIN/aarch64-linux-android24-clang" --target=aarch64-none-linux-android24 --sysroot="$SYS" \
    -fPIC -O2 -std=gnu99 -fsigned-char -DLIBRARYBUILD -DHAVE_CONFIG_H \
    -I"$G/include" -I"$G/src/include" -I"$G/src/libutil" -I"$G/src/libtess" \
    -I"$G/src/libnurbs/internals" -I"$G/src/libnurbs/nurbtess" \
    -c "$f" -o "$o" 2>/dev/null || true
done
"$BIN/aarch64-linux-android24-clang" --target=aarch64-none-linux-android24 --sysroot="$SYS" \
  -shared -Wl,-soname,libGLU.so -o build/glu-arm64-v8a/libGLU.so build/glu-arm64-v8a/*.o

# 3) Stage both under gl-stubs so Coin's link finds -lGL / -lGLU.
cp src/gl4es-master/lib/libGL.so.1 gl-stubs/libGL.so
cp build/glu-arm64-v8a/libGLU.so  gl-stubs/libGLU.so
echo "gl4es : $(ls -la gl-stubs/libGL.so  | awk '{print $5}') bytes"
echo "GLU   : $(ls -la gl-stubs/libGLU.so | awk '{print $5}') bytes"
