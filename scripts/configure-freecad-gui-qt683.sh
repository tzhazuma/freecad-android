#!/usr/bin/env bash
# Configure FreeCAD GUI for Android arm64-v8a against Qt 6.8.3 + Python 3.11.
#
# Why this configuration:
#   * Qt 6.8.3 pairs exactly with the official PySide6-6.8.3 / shiboken6-6.8.3
#     Android wheels, so there is no Qt/PySide version skew.
#   * The wheels are cp311-cp311, i.e. they hard-link libpython3.11.so
#     (verified with llvm-readelf -d). Hence the embedded Python must be 3.11.
#   * FREECAD_USE_SHIBOKEN=OFF / FREECAD_USE_PYSIDE=OFF avoids the hard
#     FATAL_ERROR in cMake/FindPySide6.cmake when Qt and PySide minors differ,
#     and FreeCAD needs neither at build time: no FreeCAD module is
#     shiboken-generated, and src/Gui/PythonWrapper.cpp has a complete
#     Python-mediated fallback that imports shiboken6 at runtime instead.
#     PySide6/shiboken6 are still shipped in the APK so the workbenches work.
set -euo pipefail

HOME_DIR="$HOME"
ROOT="$HOME_DIR/freecad-android"
SRC="$HOME_DIR/FreeCAD"
BUILD="$ROOT/build/freecad-gui-qt683"
NDK="$HOME_DIR/Library/Android/sdk/ndk/28.2.13676358"

QT="$ROOT/Qt/6.8.3/android_arm64_v8a"
PY_PREFIX="$ROOT/prefix/py311-arm64-v8a"
# Main dependency prefix: yaml-cpp, OpenCASCADE, Coin3D, Boost, xerces-c, ICU, ...
# The original working build had exactly this on CMAKE_PREFIX_PATH.
MAIN_PREFIX="$ROOT/prefix/arm64-v8a"

for p in "$QT/lib/cmake/Qt6/Qt6Config.cmake" \
         "$PY_PREFIX/lib/libpython3.11.so" \
         "$PY_PREFIX/include/python3.11/Python.h" \
         "$NDK/build/cmake/android.toolchain.cmake"; do
    [ -e "$p" ] || { echo "缺少必需路径: $p" >&2; exit 1; }
done

rm -rf "$BUILD"
cmake -S "$SRC" -B "$BUILD" -G Ninja \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_C_FLAGS="-I$ROOT/src/gl4es-master/include -I$ROOT/src/GLU-master/include" \
  -DCMAKE_CXX_FLAGS="-I$ROOT/src/gl4es-master/include -I$ROOT/src/GLU-master/include" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-24 \
  -DCMAKE_PREFIX_PATH="$PY_PREFIX;$MAIN_PREFIX;$QT" \
  -DCMAKE_FIND_ROOT_PATH="$PY_PREFIX;$MAIN_PREFIX;$QT" \
  -DQt6_DIR="$QT/lib/cmake/Qt6" \
  -DQT_HOST_PATH="$ROOT/Qt/6.8.3/macos" \
  -DPython3_EXECUTABLE="/opt/homebrew/opt/python@3.11/bin/python3.11" \
  -DPython3_INCLUDE_DIR="$PY_PREFIX/include/python3.11" \
  -DPython3_LIBRARY="$PY_PREFIX/lib/libpython3.11.so" \
  -DPython_EXECUTABLE="/opt/homebrew/opt/python@3.11/bin/python3.11" \
  -DPython_INCLUDE_DIR="$PY_PREFIX/include/python3.11" \
  -DPython_LIBRARY="$PY_PREFIX/lib/libpython3.11.so" \
  -DBUILD_GUI=ON \
  -DBUILD_FLAT_MESH=OFF \
  -DBUILD_CAM=OFF \
  -DBUILD_BIM=OFF \
  -DBUILD_FEM=OFF \
  -DBUILD_MESH_PART=OFF \
  -DBUILD_OPENSCAD=OFF \
  -DBUILD_ASSEMBLY=ON \
  -DBUILD_DRAFT=ON \
  -DBUILD_HELP=ON \
  -DBUILD_INSPECTION=ON \
  -DBUILD_MATERIAL=ON \
  -DBUILD_MEASURE=ON \
  -DBUILD_MESH=ON \
  -DBUILD_PART=ON \
  -DBUILD_PLOT=ON \
  -DBUILD_POINTS=ON \
  -DBUILD_ROBOT=ON \
  -DBUILD_SHOW=ON \
  -DBUILD_SKETCHER=ON \
  -DBUILD_SPREADSHEET=ON \
  -DBUILD_START=ON \
  -DBUILD_SURFACE=ON \
  -DBUILD_TECHDRAW=ON \
  -DBUILD_TEST=ON \
  -DBUILD_TUX=ON \
  -DBUILD_WEB=ON \
  -DFREECAD_USE_SHIBOKEN=OFF \
  -DFREECAD_USE_PYSIDE=OFF \
  -DFREECAD_USE_EXTERNAL_SMESH=OFF \
  -DFREECAD_USE_EXTERNAL_PYCXX=OFF \
  -DFREECAD_USE_EXTERNAL_COIN_PIVY=OFF \
  -DFREECAD_USE_EXTERNAL_ZIPIOS=OFF \
  -DFREECAD_USE_EXTERNAL_JSON=OFF \
  -DFREECAD_USE_EXTERNAL_KDL=OFF \
  -DFREECAD_USE_EXTERNAL_KDTREE=OFF \
  -DFREECAD_USE_EXTERNAL_CLIPPER2=OFF \
  -DFREECAD_USE_EXTERNAL_E57FORMAT=OFF \
  -DFREECAD_USE_EXTERNAL_ONDSELSOLVER=OFF \
  -DFREECAD_USE_PCL=OFF \
  -DFREECAD_USE_FREETYPE=ON \
  -DFREECAD_USE_QT_DIALOGS=OFF \
  -DFREECAD_USE_3DCONNEXION_LEGACY=ON \
  -DFREECAD_USE_CCACHE=ON \
  -DENABLE_DEVELOPER_TESTS=OFF \
  -DFREECAD_RCC_COMPRESSION_ALGO=zlib \
  -DFREECAD_RCC_COMPRESSION_LEVEL=9 \
  -DHAVE_3DS_IMPORT_CAPABILITIES=OFF \
  -DHAVE_SOUND=OFF \
  -DCOIN_BUILD_GLX=OFF \
  -DCOIN_BUILD_EGL=ON \
  -DCOIN_BUILD_SINGLE_LIB=OFF \
  -DCOIN_THREADSAFE=OFF \
  -DCOIN_USE_CPACK=OFF \
  -DCOIN_VERBOSE=OFF \
  -DCOIN_BUILD_DOCUMENTATION=OFF \
  -DCOIN_BUILD_AWESOME_DOCUMENTATION=OFF \
  -DCOIN_BUILD_EXAMPLES=OFF \
  -DCOIN_BUILD_TESTS=OFF \
  -DCOIN_BUILD_LEGACY_GL_RENDERER=ON \
  -DCOIN_HAVE_JAVASCRIPT=ON \
  -DCOIN_DEBUG_CHECK_THREAD=OFF \
  -DCOIN_STRICT_WARNINGS=OFF \
  -DCOIN_INSTALL=OFF

echo "配置完成: $BUILD"
