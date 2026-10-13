#!/usr/bin/env bash
# Configure + build Coin3D for Android. Requires scripts/build-gl4es.sh to have run.
set -euo pipefail
NDK="${ANDROID_NDK_HOME:-$HOME/Library/Android/sdk/ndk/28.2.13676358}"
WORK="$HOME/freecad-android"; PREFIX="$WORK/prefix/arm64-v8a"
GI="$WORK/src/gl4es-master/include"; UI="$WORK/src/GLU-master/include"
cmake -G Ninja -S "$HOME/FreeCAD/src/3rdParty/coin" -B "$WORK/build/coin-arm64-v8a" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-24 \
  -DCMAKE_BUILD_TYPE=Release -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
  -DCMAKE_INSTALL_PREFIX="$PREFIX" -DCMAKE_FIND_ROOT_PATH="$PREFIX" -DCMAKE_PREFIX_PATH="$PREFIX" \
  -DCOIN_BUILD_SHARED_LIBS=ON -DCOIN_BUILD_TESTS=OFF -DCOIN_BUILD_EXAMPLES=OFF \
  -DCOIN_BUILD_DOCUMENTATION=OFF -DHAVE_SOUND=OFF -DHAVE_3DS_IMPORT_CAPABILITIES=OFF \
  -DCOIN_BUILD_EGL=ON -DCOIN_BUILD_GLX=OFF \
  -DCMAKE_CXX_FLAGS="-I$GI -I$UI" -DCMAKE_C_FLAGS="-I$GI -I$UI" \
  -DCMAKE_SHARED_LINKER_FLAGS="-L$WORK/gl-stubs -lGL -lGLU" \
  -DCMAKE_EXE_LINKER_FLAGS="-L$WORK/gl-stubs -lGL -lGLU"
ninja -C "$WORK/build/coin-arm64-v8a" -j6
ninja -C "$WORK/build/coin-arm64-v8a" install
