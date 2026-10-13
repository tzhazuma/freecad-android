# FreeCAD for Android (unofficial port)

An **unofficial, experimental Android port of [FreeCAD](https://github.com/FreeCAD/FreeCAD)**,
built from source for **arm64-v8a** devices running **Android 12 / API 31 or newer**.

This is a community effort and is **not affiliated with, endorsed by, or supported by the
FreeCAD project**. Bug reports belong here, not on the upstream tracker.

![FreeCAD GUI running on an Android arm64 device](screenshots/freecad-gui-final.png)

---

## Status

Honest snapshot of where the port stands today:

| Area | State |
| --- | --- |
| **GUI** | ✅ Launches and renders on a real arm64 device (Coin3D + gl4es on top of GLES). |
| **Headless `FreeCADCmd`** | ✅ Fully working. 18 workbenches load and run in a terminal/ADB shell. |
| **Opening a document in the GUI** | ❌ Currently **segfaults**. Under active investigation. |
| **PySide / PySide6** | ❌ Not available on Android — blocks full workbench loading (see limitations). |

The GUI path is usable for viewing and for the built-in workbenches that do not need a full
Python binding layer; anything that opens a document through the GUI will crash until the
document-loading path is fixed.

## Requirements

- **CPU:** `arm64-v8a` **only** (no `armeabi-v7a`, no `x86_64` emulator support).
- **OS:** **Android 12 (API 31) or newer.**
- A device with a working OpenGL ES driver; a GPU that supports GLES 3.x is recommended.
- Enough free storage for the APK (~302 MB debug build) plus unpacked native libraries.

## Install (sideload)

1. Download the APK from the **Releases** page of this repository (release assets are *not*
   committed to git — see [Release plan](#release-plan)).
2. Enable **Install unknown apps** for your file manager or `adb`:
   ```bash
   adb install -r FreeCAD-android-arm64-v8a-<version>.apk
   ```
   or copy the APK to the device and open it from the file manager.
3. Launch **FreeCAD** from the app drawer.

The APK published from CI is currently **debug-signed**; see the release plan for how to
produce a release-signed build.

## Build from source

The build is a cross-compilation of Qt, Coin3D, OpenCASCADE and FreeCAD for Android. It is
long (hours) and disk-hungry (tens of GB).

### Toolchain

- **Qt 6.6.3 for Android** (`android_arm64_v8a`), installed with `aqtinstall`.
- **Android NDK 28.2.13676358** and a matching SDK / `build-tools`.
- **CMake** and **Ninja** from the Android SDK/NDK bundle.
- **gl4es** — the GL→GLES translation layer FreeCAD's Coin3D needs on Android.
- **Coin3D** built against gl4es and Qt 6.
- **OpenCASCADE (OCCT) 7.9.3**, plus Boost, FreeType, HarfBuzz, Xerces-C, yaml-cpp, GLU and
  a CPython 3.13 cross-build for the embedded interpreter.

### Steps

```bash
# 1. Dependencies — cross-compile for arm64-v8a into a common prefix
#    (gl4es, Coin3D, OCCT, Boost, FreeType, HarfBuzz, Xerces-C, yaml-cpp, GLU, CPython 3.13)
#    see scripts/ for the per-library invocations used to produce prefix/arm64-v8a/

# 2. FreeCAD itself, with the Android patches applied
cd /path/to/FreeCAD
git apply /path/to/freecad-android/patches/freecad-gui/*.patch
git apply /path/to/freecad-android/patches/coin-android/*.patch

cmake -S . -B build-android \
  -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-31 \
  -DCMAKE_PREFIX_PATH=$PREFIX/arm64-v8a \
  -DCMAKE_BUILD_TYPE=Release
cmake --build build-android -j"$(nproc)"

# 3. Gradle — package the native libraries and the Python runtime
cd /path/to/freecad-android/android-app
./gradlew assembleDebug
```

The Gradle project picks up the cross-built `.so` files from `jniLibs/arm64-v8a/` and the
Python runtime from `runtime-bundle/`.

## Known limitations

- **PySide / PySide6 are unavailable on Android.** FreeCAD's Python workbenches depend on
  them heavily — **567 Python files in the tree import PySide**. Without a working PySide,
  many workbenches cannot fully load or run their Python-side tooling. This is the single
  biggest blocker for feature parity.
- **Disabled workbenches:** `Fem`, `CAM`, `BIM` and `OpenSCAD` are currently disabled in this
  port.
- **arm64-v8a only.** No 32-bit ARM, no x86/x86_64 emulator builds.
- **Document loading through the GUI segfaults** (see status above).
- Python-side and GUI-side features that require Qt WebEngine, multimedia, or a full desktop
  windowing stack are not expected to work.

## Repository layout

| Path | Contents |
| --- | --- |
| `patches/` | `freecad-gui/` and `coin-android/` patches applied to FreeCAD and Coin3D. |
| `android-app/` | Gradle project (Java/Kotlin shell, `jniLibs/`, packaging). |
| `scripts/` | Build helper scripts used for the cross-compilation. |
| `screenshots/` | Screenshots from real-device runs (committed). |
| `gl-stubs/` | Stub `libGL.so` / `libGLU.so` for link-time use. |
| `ref/`, `probe/` | Reference material and probe harnesses. |

Build trees, the Qt installation, downloaded tarballs, the dependency prefix and generated
APKs are **not** committed — see `.gitignore`.

## Credits

This port stands on the work of others:

- **[FreeCAD](https://github.com/FreeCAD/FreeCAD)** — the application itself.
- **[Coin3D](https://github.com/coin3d/coin)** — the 3D scene graph FreeCAD's GUI is built on.
- **[gl4es](https://github.com/ptitSeb/gl4es)** — OpenGL 1.x/2.x → OpenGL ES translation.
- **[Qt](https://www.qt.io/)** — application framework (Qt 6.6.3).
- **[OpenCASCADE (OCCT)](https://dev.opencascade.org/)** — the CAD kernel.
- Boost, FreeType, HarfBuzz, Xerces-C, yaml-cpp, GLU, CPython and the Android NDK toolchain.

## Licensing

FreeCAD is distributed under the **GNU Lesser General Public License, version 2.1 or later
(LGPL-2.1-or-later)**, with some components under other compatible licences (see FreeCAD's
`LICENSE` file and per-file headers). This repository redistributes FreeCAD code and links
against LGPL components, so:

- The repository **must ship the licence texts** — at minimum `LGPL-2.1` and `GPL-2.0`
  (FreeCAD's licence file references both), plus the licence texts of the bundled
  third-party libraries (Qt — LGPL-3.0/GPL-3.0/commercial, OCCT — LGPL-2.1 with exception,
  Coin3D — BSD-3-Clause/GPL, gl4es — MIT).
- **LGPL components' source must be available.** For every LGPL-licensed library that is
  linked into the APK (FreeCAD, Qt, OCCT, and any other LGPL dependency), the repo must
  either ship the **corresponding source**, including the modifications made for this port
  (the `patches/` directory covers the FreeCAD and Coin3D changes), **or** provide a valid
  **written offer** to supply that source to anyone who receives the binary.
- Because the APK statically links or bundles these libraries, keeping the patches and a
  build recipe in-tree is the practical way to satisfy the source obligation.

Add a `LICENSE` file (or `LICENSES/` directory) with the full texts before publishing.

## Contributing

Issues and pull requests are welcome, especially around:

- the GUI document-loading segfault,
- a PySide/PySide6 replacement or stub layer for Android,
- re-enabling Fem / CAM / BIM / OpenSCAD,
- additional ABIs.

Please include device model, Android version, and a `logcat` capture with any bug report.
