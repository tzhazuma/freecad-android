package org.freecad.android;

import android.content.res.AssetManager;
import android.os.Bundle;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Host activity for FreeCAD on Android.
 *
 * <p>Extending Qt's {@code QtActivity} is what makes {@code QCoreApplication} usable at
 * all here: Qt's Android port routes platform integration through
 * {@code org.qtproject.qt.android.QtNative}, whose JNI implementation lives in the
 * Qt-for-Android platform plugin. A bare shell process has no JVM/JNI environment, so
 * that call dereferences null and the process dies inside libQt6Core. Qt's loader reads
 * the {@code android.app.lib_name} manifest value, appends the ABI, loads
 * {@code libFreeCADCmd_arm64-v8a.so} and calls its {@code main()}.
 *
 * <p>Before handing over we unpack the embedded CPython standard library into the app's
 * private files dir. FreeCAD's embedded interpreter otherwise searches the build
 * machine's prefix -- a path baked in at compile time that cannot exist on Android --
 * and aborts with {@code Base::RuntimeError("Failed to init from config")}
 * (src/Base/Interpreter.cpp:666). The native bridge sets PYTHONHOME to this same dir.
 */
public class MainActivity extends org.qtproject.qt.android.bindings.QtActivity {
    private static final String TAG = "FreeCAD";

    /** Must match the PYTHONHOME the native bridge sets. */
    private static final String PY_PREFIX_NAME = "python-prefix";
    private static final String PY_VERSION_DIR = "python3.11";

    /**
     * Bump whenever anything under assets/pylib changes.
     *
     * The extraction marker embeds this value, so an installed app re-unpacks the
     * payload on the next launch instead of silently keeping the previous copy. That
     * bit us once: site-packages/freecad was added to the assets after the first run,
     * the marker still matched, and the package was never written to disk, leaving
     * FreeCAD reporting "No module named 'freecad'" from a payload that looked fine
     * in the APK.
     *
     * The same trap hit again when freecad/PySide/lazy_loader were added while v3 was
     * still current: PySide6 and shiboken6 (extracted earlier) were present on disk,
     * the three new packages were not, and the app crashed in showMainWindow().
     */
    private static final String PY_ASSETS_VERSION = "4";

    /** Best-effort setenv; android.system.Os.setenv is available from API 21. */
    private static void setEnv(String name, String value) {
        try {
            android.system.Os.setenv(name, value, true);
            Log.i(TAG, "setenv " + name + "=" + value);
        }
        catch (Throwable t) {
            Log.w(TAG, "setenv " + name + " failed: " + t);
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        // These have to be in the environment BEFORE Qt's loader dlopen()s the app library.
        // That library is libFreeCADGui.so's consumer, and libFreeCADGui.so in turn lists
        // libGL.so (gl4es) -- so gl4es' ELF constructors run during that dlopen, long before
        // native main() gets a chance to call setenv().
        //
        // Why it matters: gl4es probes the GPU from its constructor by creating its own EGL
        // display, Pbuffer surface and context and then destroying them
        // (src/glx/hardext.c, GetHardwareExtensions). Run that early and the EGL display is
        // left in a state where Qt's own eglChooseConfig returns no configs, so Qt's Android
        // platform plugin aborts:
        //   Cannot find EGLConfig, returning null config
        //   EGL Error : Could not create the egl surface: error = 0x3000
        // LIBGL_NOTEST makes gl4es skip the probing and use conservative GLES2 defaults.
        setEnv("LIBGL_NOTEST", "1");

        Log.i(TAG, "MainActivity.onCreate - handing over to Qt's loader");
        try {
            extractPythonStdlib();
            // FreeCADCmd is handed a script path; it has to already be on disk.
            extractAssetDir("scripts", new File(getFilesDir(), "scripts"));
            // Workbench modules. FreeCADCmd is pointed at this directory with -M.
            // The assets carry each workbench's Python glue; the compiled extension modules
            // are copied from the APK's native library dir below.
            extractAssetDir("Mod", new File(getFilesDir(), "Mod"));
            linkWorkbenchLibraries();
            // FreeCAD's resource directory is <AppHomePath>/share, and it looks there for
            // both the workbench icons (share/Mod/<WB>/Resources/...) and the built-in
            // preference packs (share/Gui/PreferencePackTemplates/*.cfg). The assets store
            // Mod/ at the top level, so share/Mod is a symlink back to it; everything else
            // under share/ is unpacked for real from assets/share.
            extractAssetDir("share", new File(getFilesDir(), "share"));
            linkResourceDirectory();
        } catch (IOException e) {
            Log.e(TAG, "failed to unpack the Python standard library", e);
        }
        super.onCreate(savedInstanceState);
    }


    /**
     * Make {@code <filesDir>/share/Mod} resolve to {@code <filesDir>/Mod}.
     *
     * <p>FreeCAD locates every workbench icon through its <em>resource directory</em>, which
     * is built as {@code AppHomePath + RESOURCEDIR} -- and {@code RESOURCEDIR} is the
     * compile-time {@code CMAKE_INSTALL_DATADIR}, i.e. {@code "share"}. With the Android
     * branch of {@code ApplicationDirectories::findHomePath} pointing AppHomePath at
     * {@code <filesDir>}, icons are looked up under {@code <filesDir>/share/Mod/...} while the
     * assets actually unpack to {@code <filesDir>/Mod/...}.
     *
     * <p>A symlink bridges the two without duplicating ~16 MB of resources in the APK.
     */
    private void linkResourceDirectory() {
        File shareDir = new File(getFilesDir(), "share");
        File shareMod = new File(shareDir, "Mod");
        File realMod = new File(getFilesDir(), "Mod");

        if (!shareDir.isDirectory() && !shareDir.mkdirs()) {
            Log.w(TAG, "cannot create " + shareDir);
            return;
        }
        if (shareMod.exists() || !realMod.isDirectory()) {
            return;
        }
        try {
            android.system.Os.symlink(realMod.getAbsolutePath(), shareMod.getAbsolutePath());
            Log.i(TAG, "linked resource dir: " + shareMod + " -> " + realMod);
        }
        catch (Throwable t) {
            // Worst case the workbench icons stay missing; the application still starts.
            Log.w(TAG, "cannot symlink " + shareMod + " -> " + realMod + ": " + t);
        }
    }


    /**
     * Copy each workbench extension module from the APK's native library directory into
     * {@code <filesDir>/Mod/<workbench>/}.
     *
     * <p>The .so files have to live in two places at once and each copy serves a different
     * mechanism:
     * <ul>
     *   <li>{@code lib/arm64-v8a/} in the APK, because that is the only directory the Android
     *       dynamic linker searches. Workbench modules depend on each other through ELF
     *       (Inspection needs Part.so, Assembly needs _PartDesign.so, ...), so they must be
     *       resolvable there or {@code dlopen} fails with "library ... not found".</li>
     *   <li>{@code files/Mod/<workbench>/}, because that is where Python looks when FreeCAD
     *       puts each workbench directory on sys.path, and an extension module has to sit
     *       beside the package it belongs to.</li>
     * </ul>
     *
     * <p>Shipping the bytes twice in the APK wasted ~32 MB, so the assets now carry only the
     * Python side (Init.py and friends) plus {@code modmap.txt}, and this copies the modules
     * across at first launch.
     */
    private void linkWorkbenchLibraries() throws IOException {
        File modRoot = new File(getFilesDir(), "Mod");
        String nativeDir = getApplicationInfo().nativeLibraryDir;
        File marker = new File(modRoot, ".libs-linked-v" + PY_ASSETS_VERSION);
        // The marker is only trustworthy while the tree it describes still exists:
        // assets/Mod is re-extracted on every launch, which clears the marker along with
        // the directory, but a partially-failed copy could leave the marker behind.
        if (marker.exists() && modRoot.isDirectory() && modRoot.list() != null
                && modRoot.list().length > 1) {
            Log.i(TAG, "workbench libraries already linked");
            return;
        }

        int copied = 0;
        try (InputStream in = getAssets().open("modmap.txt")) {
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split("\t");
                if (parts.length != 2) {
                    Log.w(TAG, "bad modmap line: " + line);
                    continue;
                }
                String workbench = parts[0];
                String soname = parts[1];
                File src = new File(nativeDir, soname);
                if (!src.isFile()) {
                    Log.w(TAG, "missing native library for " + workbench + ": " + src);
                    continue;
                }
                File dir = new File(modRoot, workbench);
                if (!dir.isDirectory() && !dir.mkdirs()) {
                    Log.w(TAG, "cannot create " + dir);
                    continue;
                }
                File dst = new File(dir, soname);
                java.nio.file.Files.copy(src.toPath(), dst.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                copied++;
            }
        }
        if (!marker.createNewFile()) {
            Log.w(TAG, "could not create marker " + marker);
        }
        Log.i(TAG, "linked " + copied + " workbench libraries into " + modRoot);
    }

    /**
     * Copy {@code assets/pylib/lib/python3.13/**} to
     * {@code <filesDir>/python-prefix/lib/python3.13/**}.
     *
     * <p>Runs every launch but skips the copy when the version marker is already present,
     * so only the first start pays the cost.
     */
    private void extractPythonStdlib() throws IOException {
        File prefix = new File(getFilesDir(), PY_PREFIX_NAME);
        File marker = new File(prefix, ".extracted-" + PY_VERSION_DIR + "-v" + PY_ASSETS_VERSION);
        if (marker.exists()) {
            Log.i(TAG, "Python stdlib already unpacked at " + prefix);
            return;
        }

        Log.i(TAG, "unpacking Python stdlib into " + prefix);
        long start = System.currentTimeMillis();

        deleteRecursively(prefix);
        AssetManager assets = getAssets();
        int files = copyAssetTree(assets, "pylib", prefix);

        File parent = marker.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("cannot create " + parent);
        }
        if (!marker.createNewFile()) {
            Log.w(TAG, "could not create extraction marker at " + marker);
        }

        Log.i(TAG, "unpacked " + files + " files in "
                + (System.currentTimeMillis() - start) + " ms");
    }


    /** Copy one asset directory into an arbitrary destination. */
    private void extractAssetDir(String assetPath, File dest) throws IOException {
        deleteRecursively(dest);
        int n = copyAssetTree(getAssets(), assetPath, dest);
        Log.i(TAG, "unpacked " + n + " files into " + dest);
    }

    /** Depth-first copy of one asset directory into {@code dest}. */
    private int copyAssetTree(AssetManager assets, String assetPath, File dest)
            throws IOException {
        String[] children = assets.list(assetPath);
        if (children == null || children.length == 0) {
            // A leaf: copy the single file.
            if (dest.getParentFile() != null && !dest.getParentFile().exists()
                    && !dest.getParentFile().mkdirs()) {
                throw new IOException("cannot create " + dest.getParentFile());
            }
            try (InputStream in = assets.open(assetPath);
                 OutputStream out = new FileOutputStream(dest)) {
                byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                }
            }
            return 1;
        }

        if (!dest.exists() && !dest.mkdirs()) {
            throw new IOException("cannot create " + dest);
        }
        int count = 0;
        for (String child : children) {
            count += copyAssetTree(assets, assetPath + "/" + child, new File(dest, child));
        }
        return count;
    }

    private static void deleteRecursively(File f) {
        // Never follow a symlink. files/share/Mod points back at files/Mod, so a plain
        // isDirectory() walk of files/share would descend through that link and delete
        // the entire extracted workbench tree. That is exactly what happened here: the
        // first launch populated Mod, the second launch deleted files/share and wiped Mod
        // with it, and FreeCAD then failed with
        //   Bundled Pivy is enabled, but pivy was not found.
        //   Expected bundled Pivy at .../files/Mod/pivy
        // because files/Mod no longer contained anything at all. File.isDirectory()
        // follows links on Android, so the link test has to come from java.nio.
        try {
            if (java.nio.file.Files.isSymbolicLink(f.toPath())) {
                java.nio.file.Files.deleteIfExists(f.toPath());
                return;
            }
        }
        catch (IOException ignored) {
            // Fall through to the ordinary recursive delete.
        }
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) {
                    deleteRecursively(k);
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
