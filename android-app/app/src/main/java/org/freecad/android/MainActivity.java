package org.freecad.android;

import android.content.Intent;
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
    private static final String PY_ASSETS_VERSION = "7";   // 7: ship _socket under the importable name   // 6: Mesh/InitGui.py stops logging the absent flattening command

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
        disableQtAccessibilityBridge();
        ensureAllFilesAccess();
        scheduleQtWindowAudit();
    }

    /**
     * Watch what Qt does with the Java views that carry touch input.
     *
     * <p>Qt 6.8's Android plugin creates one {@code org.qtproject.qt.android.QtWindow} view per
     * top-level QWindow and routes every touch by that view's numeric Android id
     * ({@code QtInputDelegate.sendTouchEvent(event, getId())}). The C++ side turns the id back
     * into a QWindow by walking {@code QGuiApplication::allWindows()}
     * ({@code androidjnimain.cpp:windowFromId}); when it finds none it drops the event and warns
     * {@code Touch event received for non-existing window N}.
     *
     * <p>A view can outlive its QWindow: QtActivityDelegate deliberately keeps the last removed
     * top-level window's view as {@code m_dummyView} in the layout (it removes it only when the
     * next window is added). If that dummy is still visible it sits on top of the real window
     * and eats every touch. So: delete any QtWindow child that Qt's own
     * {@code m_topLevelWindows} map no longer tracks - but only while a tracked child remains,
     * so a legitimate dummy during the splash-screen handover is left alone.
     */
    private void scheduleQtWindowAudit() {
        android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        long[] delays = {6000, 12000, 20000, 35000, 60000};
        for (long delay : delays) {
            handler.postDelayed(this::auditQtWindows, delay);
        }
    }

    private void auditQtWindows() {
        try {
            Object delegate = findField(this, "m_delegate");
            Object layout = findField(delegate, "m_layout");
            Object top = findField(delegate, "m_topLevelWindows");
            Object dummy = findField(delegate, "m_dummyView");
            java.util.Map<?, ?> live = (top instanceof java.util.Map) ? (java.util.Map<?, ?>) top : null;

            Log.i(TAG, "QtWindow audit: layout=" + describe(layout)
                    + " liveIds=" + (live == null ? "?" : live.keySet())
                    + " dummy=" + describe(dummy));

            if (!(layout instanceof android.view.ViewGroup) || live == null) {
                return;
            }
            android.view.ViewGroup root = (android.view.ViewGroup) layout;
            boolean haveLiveChild = false;
            for (int i = 0; i < root.getChildCount(); i++) {
                android.view.View child = root.getChildAt(i);
                if (isQtWindow(child) && live.containsKey(child.getId())) {
                    haveLiveChild = true;
                }
            }
            if (!haveLiveChild) {
                return;
            }
            for (int i = root.getChildCount() - 1; i >= 0; i--) {
                android.view.View child = root.getChildAt(i);
                if (!isQtWindow(child) || live.containsKey(child.getId())) {
                    continue;
                }
                Log.w(TAG, "QtWindow audit: stale QtWindow view id=" + child.getId()
                        + " visibility=" + child.getVisibility() + " " + describe(child)
                        + " - removing (Qt has no such window; it would swallow touches)");
                root.removeView(child);
            }
        }
        catch (Throwable t) {
            Log.w(TAG, "QtWindow audit failed", t);
        }
    }

    private static boolean isQtWindow(android.view.View view) {
        return "org.qtproject.qt.android.QtWindow".equals(view.getClass().getName());
    }

    private static String describe(Object o) {
        if (!(o instanceof android.view.View)) {
            return String.valueOf(o);
        }
        android.view.View v = (android.view.View) o;
        return v.getClass().getSimpleName() + "{id=" + v.getId()
                + ", vis=" + v.getVisibility()
                + ", " + v.getWidth() + "x" + v.getHeight()
                + ", at " + v.getLeft() + "," + v.getTop() + "}";
    }

    private static Object findField(Object target, String name) {
        if (target == null) {
            return null;
        }
        for (Class<?> clazz = target.getClass(); clazz != null; clazz = clazz.getSuperclass()) {
            try {
                java.lang.reflect.Field field = clazz.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            }
            catch (NoSuchFieldException ignored) {
                // Keep walking up; m_topLevelWindows lives in QtActivityDelegateBase.
            }
            catch (Throwable t) {
                Log.w(TAG, "cannot read " + name + " from " + clazz.getName() + ": " + t);
                return null;
            }
        }
        return null;
    }

    /**
     * Stop Android's accessibility framework from calling into Qt's virtual view tree.
     *
     * <p>Qt ships an {@code AccessibilityDelegate} whose {@code getNodeForVirtualViewId()}
     * does a <em>synchronous</em> {@code QMetaObject::invokeMethod} into the Qt thread. When
     * an accessibility service (TalkBack, the automation harness, or even the system's own
     * post-launch accessibility events) asks for a node while the Qt thread is busy -- for
     * example while starting a file dialog -- the Android UI thread blocks in that JNI call.
     * Android then declares "Input dispatching timed out ... Waited 5000ms for
     * FocusEvent/MotionEvent" and kills the app. That is exactly the ANR this port hit
     * whenever Open was tapped.
     *
     * <p>A CAD viewport has no meaningful virtual accessibility tree anyway, so hide the
     * descendants from accessibility. System dialogs (the installer, the document picker)
     * are separate windows and are unaffected.
     */
    private void disableQtAccessibilityBridge() {
        try {
            android.view.View decor = getWindow().getDecorView();
            decor.setImportantForAccessibility(
                    android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            decor.setAccessibilityDelegate(null);
            Log.i(TAG, "disabled the Qt accessibility bridge");
        }
        catch (Throwable t) {
            Log.w(TAG, "could not disable accessibility: " + t);
        }
    }


    /**
     * Prove that the process really can read and write shared storage, and say so in the log.
     *
     * <p>{@code isExternalStorageManager()} being true is a statement about an app-op; it is
     * not evidence that this process's mount view of {@code /storage/emulated/0} actually
     * permits the calls FreeCAD makes. Those two can disagree -- they did here -- so the only
     * honest check is to perform the same plain {@code open()}/{@code write()} that FreeCAD's
     * C++ does and report the outcome.
     */
    private void probeSharedStorage() {
        File dir = android.os.Environment
                .getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS);
        File probe = new File(dir, ".freecad-storage-probe");
        try {
            String[] listing = dir.list();
            try (OutputStream out = new FileOutputStream(probe)) {
                out.write("freecad storage probe\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            boolean removed = probe.delete();
            Log.i(TAG, "shared storage OK: " + dir + " entries="
                    + (listing == null ? "unreadable" : Integer.toString(listing.length))
                    + " write=ok delete=" + removed);
        }
        catch (Throwable t) {
            Log.e(TAG, "shared storage NOT usable: " + dir, t);
        }
    }


    /**
     * Translate a Storage Access Framework {@code content://} URI into a plain filesystem
     * path that FreeCAD's C++ can open.
     *
     * <p>Qt 6.7+ ships a native Android file-dialog helper
     * ({@code QAndroidPlatformFileDialogHelper}) which launches the system picker with
     * {@code ACTION_OPEN_DOCUMENT} / {@code ACTION_CREATE_DOCUMENT}. That is exactly the
     * dialog the vivo ROM routes to {@code com.android.filemanager}, and it returns a
     * {@code content://} URI. FreeCAD, however, opens documents by path: its C++ uses
     * {@code std::ifstream}, OCCT and its Python API all take a filename. So the URI is
     * resolved back to the real file behind it -- possible because this app also holds
     * {@code MANAGE_EXTERNAL_STORAGE} -- and FreeCAD is handed the path.
     *
     * <p>Resolution order: the provider's {@code _data} column (covers Downloads, media and
     * most vendor providers), then {@code DocumentsContract} ids ({@code primary:Download/x}
     * from the external-storage provider, {@code raw:...} and {@code msf:<id>} from Downloads),
     * and finally a cache copy for read-only Opens from a provider that exposes no path.
     * Saves that cannot be resolved get a cache path plus a watcher that copies the finished
     * document back to the URI.
     */
    public String resolveContentUri(String uriString, boolean forWrite) {
        if (uriString == null) {
            return null;
        }
        android.net.Uri uri;
        try {
            uri = android.net.Uri.parse(uriString);
        }
        catch (Throwable t) {
            return uriString;
        }
        if (!"content".equals(uri.getScheme())) {
            return uriString;
        }

        String path = queryDataColumn(uri);
        if (path == null || path.isEmpty()) {
            path = documentsContractPath(uri);
        }
        if (path != null && !path.isEmpty()) {
            File file = new File(path);
            if (file.exists() || forWrite) {
                Log.i(TAG, "SAF resolved " + uriString + " -> " + path
                        + " (exists=" + file.exists() + ", forWrite=" + forWrite + ")");
                return path;
            }
        }

        File cached = copyContentUriToCache(uri);
        if (cached == null) {
            Log.e(TAG, "SAF cannot resolve " + uriString);
            return "";
        }
        Log.w(TAG, "SAF has no filesystem path for " + uriString
                + "; using cache copy " + cached + " (forWrite=" + forWrite + ")");
        if (forWrite) {
            watchSaveBack(cached, uri);
        }
        return cached.getAbsolutePath();
    }

    /** Query the provider's _data column, the direct path to the document. */
    private String queryDataColumn(android.net.Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(
                uri,
                new String[] {android.provider.MediaStore.MediaColumns.DATA},
                null,
                null,
                null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(android.provider.MediaStore.MediaColumns.DATA);
                if (idx >= 0 && !c.isNull(idx)) {
                    return c.getString(idx);
                }
            }
        }
        catch (Throwable t) {
            Log.w(TAG, "SAF _data query failed for " + uri + ": " + t);
        }
        return null;
    }

    /** Map the document ids used by the platform storage providers back to paths. */
    private String documentsContractPath(android.net.Uri uri) {
        String authority = uri.getAuthority();
        try {
            String docId = android.provider.DocumentsContract.getDocumentId(uri);
            if (docId == null) {
                return null;
            }
            if (docId.startsWith("raw:")) {
                return docId.substring(4);
            }
            if ("com.android.externalstorage.documents".equals(authority)) {
                int colon = docId.indexOf(':');
                if (colon > 0) {
                    String volume = docId.substring(0, colon);
                    String relative = docId.substring(colon + 1);
                    if ("primary".equalsIgnoreCase(volume)) {
                        return "/storage/emulated/0/" + relative;
                    }
                    return "/storage/" + volume + "/" + relative;
                }
            }
            if ("com.android.providers.downloads.documents".equals(authority)
                    && docId.startsWith("msf:")) {
                long id = Long.parseLong(docId.substring(4));
                android.net.Uri media = android.content.ContentUris.withAppendedId(
                        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, id);
                return queryDataColumn(media);
            }
        }
        catch (Throwable t) {
            Log.w(TAG, "SAF document id lookup failed for " + uri + ": " + t);
        }
        return null;
    }

    /** Last resort for Open: materialise the document into the app cache. */
    private File copyContentUriToCache(android.net.Uri uri) {
        String name = null;
        try (android.database.Cursor c = getContentResolver().query(
                uri,
                new String[] {android.provider.OpenableColumns.DISPLAY_NAME},
                null,
                null,
                null)) {
            if (c != null && c.moveToFirst()) {
                name = c.getString(0);
            }
        }
        catch (Throwable ignored) {
            // Fall through to a generic name.
        }
        if (name == null || name.isEmpty()) {
            name = "document";
        }
        name = name.replaceAll("[^A-Za-z0-9._-]", "_");

        File dir = new File(getCacheDir(), "saf");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            return null;
        }
        File out = new File(dir, name);
        if (out.exists() && !out.delete()) {
            out = new File(dir, System.currentTimeMillis() + "-" + name);
        }
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream os = new FileOutputStream(out)) {
            if (in == null) {
                return null;
            }
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) {
                os.write(buffer, 0, n);
            }
            return out;
        }
        catch (Throwable t) {
            Log.e(TAG, "SAF cache copy failed for " + uri, t);
            return null;
        }
    }

    /**
     * For a Save whose URI cannot be mapped to a path: FreeCAD writes the cache file, and
     * this daemon waits for the write to settle before copying it into the document.
     */
    private void watchSaveBack(File file, android.net.Uri uri) {
        Thread thread = new Thread(() -> {
            long lastSize = -1;
            long lastModified = -1;
            long stableSince = 0;
            long deadline = System.currentTimeMillis() + 5 * 60 * 1000L;
            while (System.currentTimeMillis() < deadline) {
                long size = file.length();
                long modified = file.lastModified();
                long now = System.currentTimeMillis();
                if (size > 0 && size == lastSize && modified == lastModified) {
                    if (stableSince == 0) {
                        stableSince = now;
                    }
                    else if (now - stableSince > 1500) {
                        break;
                    }
                }
                else {
                    stableSince = 0;
                    lastSize = size;
                    lastModified = modified;
                }
                try {
                    Thread.sleep(250);
                }
                catch (InterruptedException e) {
                    return;
                }
            }
            try (InputStream in = new java.io.FileInputStream(file);
                 OutputStream os = getContentResolver().openOutputStream(uri, "wt")) {
                byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    os.write(buffer, 0, n);
                }
                Log.i(TAG, "SAF copied " + file + " back to " + uri);
            }
            catch (Throwable t) {
                Log.e(TAG, "SAF save-back failed for " + uri, t);
            }
        }, "saf-save-back");
        thread.setDaemon(true);
        thread.start();
    }


    /**
     * Ask for "All files access" so FreeCAD's own Open/Save dialogs can reach shared
     * storage.
     *
     * <p>Every other port in this project routes file I/O through the Storage Access
     * Framework. That works for a small app whose only file entry point is a single
     * "Open"/"Save" button, because SAF hands back one URI that the app can read itself.
     * FreeCAD is not that shape: its file dialogs live deep inside Qt, its Python API opens
     * documents by path ({@code FreeCAD.open("/storage/emulated/0/x.FCStd")}), and the
     * workbenches read and write sidecar files next to the document. Threading content://
     * URIs through all of that is not possible.
     *
     * <p>{@code MANAGE_EXTERNAL_STORAGE} is the permission Android provides for exactly this
     * case. It cannot be granted from a runtime dialog -- only from a settings screen -- so
     * the check is {@code Environment.isExternalStorageManager()} and the prompt is an
     * Intent. The app stays usable if the user declines; only shared storage is out of
     * reach, and we re-ask on the next launch rather than silently doing nothing.
     */
    private void ensureAllFilesAccess() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            return;   // pre-R: the legacy READ/WRITE permissions in the manifest suffice
        }
        if (android.os.Environment.isExternalStorageManager()) {
            Log.i(TAG, "all-files access already granted");
            probeSharedStorage();
            return;
        }
        Log.i(TAG, "requesting all-files access");
        try {
            startActivity(new Intent(
                    android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    android.net.Uri.parse("package:" + getPackageName())));
        }
        catch (Throwable t) {
            // Some vendors strip the per-app screen; the global list always exists.
            Log.w(TAG, "per-app all-files screen unavailable: " + t);
            try {
                startActivity(new Intent(
                        android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
            catch (Throwable t2) {
                Log.w(TAG, "no all-files settings screen at all: " + t2);
            }
        }
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


    /**
     * Copy one asset directory into an arbitrary destination, once per asset version.
     *
     * <p>These trees used to be deleted and recopied on <em>every</em> launch. {@code Mod}
     * alone is ~80 MB of Python plus the workbench .so files copied in by
     * {@link #linkWorkbenchLibraries()}, and {@code share} is another ~140 MB; doing that
     * on the main thread in {@code onCreate} kept the UI unresponsive for well over
     * Android's 5 s input-dispatch budget. The system then declared an ANR
     * ("Input dispatching timed out ... MainActivity is not responding") and killed the
     * port before the Qt window ever appeared.
     *
     * <p>The marker makes the extraction a first-launch cost again. Bumping
     * {@link #PY_ASSETS_VERSION} invalidates it along with the Python payload.
     */
    private void extractAssetDir(String assetPath, File dest) throws IOException {
        File marker = new File(dest, ".assets-" + PY_ASSETS_VERSION);
        if (marker.isFile() && dest.isDirectory()) {
            Log.i(TAG, assetPath + " already unpacked at " + dest);
            return;
        }
        deleteRecursively(dest);
        int n = copyAssetTree(getAssets(), assetPath, dest);
        if (!dest.isDirectory() && !dest.mkdirs()) {
            throw new IOException("cannot create " + dest);
        }
        if (!marker.createNewFile()) {
            Log.w(TAG, "could not create asset marker " + marker);
        }
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
