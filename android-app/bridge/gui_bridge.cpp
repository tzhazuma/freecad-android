// GUI bridge for FreeCAD on Android.
//
// Why this exists: Android does not forward a native process's stdout/stderr to
// logcat, and `log.redirect-stdio` cannot be set on a user build. Without this we
// are blind to everything FreeCAD prints while starting up -- its banner, Python
// initialisation, and the first real error.
//
// What it does:
//   1. Splits stdout/stderr into pipes.
//   2. Starts a reader thread per pipe that forwards each line to logcat under the
//      tags "FreeCAD.out" / "FreeCAD.err", so `adb logcat` shows FreeCAD's output
//      live and interleaved with Qt's.
//   3. Calls FreeCAD.s real main(), which was renamed to freecad_gui_main by
//      `llvm-objcopy --redefine-sym main=freecad_gui_main` so that this file can own
//      the symbol Qt's loader calls.
//
// Qt's Android loader loads lib<android.app.lib_name>_<abi>.so and calls its main()
// (QtLoader.java:169 -- "main library contains main() function"), so this main() is
// the process entry point on Android.

#include <pthread.h>
#include <unistd.h>
#include <cstdio>
#include <cstring>
#include <cstdlib>
#include <iostream>

#include <android/log.h>

#define TAG_OUT "FreeCAD.out"
#define TAG_ERR "FreeCAD.err"

namespace {

struct Pump {
    int fd;
    const char* tag;
};

void* pumpThread(void* arg)
{
    auto* p = static_cast<Pump*>(arg);
    char buf[4096];
    char line[8192];
    size_t used = 0;

    for (;;) {
        ssize_t n = ::read(p->fd, buf, sizeof(buf));
        if (n <= 0) {
            break;
        }
        for (ssize_t i = 0; i < n; ++i) {
            char c = buf[i];
            if (c == '\n' || used >= sizeof(line) - 1) {
                line[used] = '\0';
                if (used > 0) {
                    __android_log_write(ANDROID_LOG_INFO, p->tag, line);
                }
                used = 0;
            }
            else if (c != '\r') {
                line[used++] = c;
            }
        }
    }

    if (used > 0) {
        line[used] = '\0';
        __android_log_write(ANDROID_LOG_INFO, p->tag, line);
    }
    return nullptr;
}

/// Duplicate the write end of a pipe onto the given stdio stream.
void redirect(int targetFd, FILE* stream, const char* tag, int flags)
{
    int fds[2];
    if (::pipe(fds) != 0) {
        __android_log_print(ANDROID_LOG_ERROR, "FreeCAD.bridge",
                            "pipe() failed for %s", tag);
        return;
    }

    static Pump pumps[2];
    static int next = 0;
    Pump* p = &pumps[next++ % 2];
    p->fd = fds[0];
    p->tag = tag;

    pthread_t tid;
    if (::pthread_create(&tid, nullptr, pumpThread, p) == 0) {
        ::pthread_detach(tid);
    }

    // Point the stdio stream at the pipe's write end.
    ::dup2(fds[1], targetFd);
    ::close(fds[1]);

    // Unbuffer so lines appear immediately rather than at exit.
    if (stream) {
        ::setvbuf(stream, nullptr, _IONBF, 0);
    }
    (void)flags;
}

} // namespace


// ---------------------------------------------------------------------------
// Abort diagnostics.
//
// The GUI aborts with signal 6 and leaves NO tombstone, no libc abort message and
// nothing in logcat's crash buffer -- so there is no way to tell which component
// called abort(). Installing our own handler and dumping a backtrace to stderr
// (which this file already forwards to logcat) is the only way to see the stack.
#include <signal.h>
#include <unistd.h>

// backtrace()/backtrace_symbols_fd() are __INTRODUCED_IN(33) and we link against
// android-24, so there is no link-time symbol. Resolve them from libc at runtime
// instead; on a pre-33 device they simply stay null and we fall back to a bare marker.
#include <dlfcn.h>
typedef int  (*bt_fn)(void**, int);
typedef void (*bt_fd_fn)(void* const*, int, int);
static bt_fn    g_backtrace    = nullptr;
static bt_fd_fn g_backtrace_fd = nullptr;

static void resolveBacktrace()
{
    if (g_backtrace) {
        return;
    }
    void* libc = ::dlopen("libc.so", RTLD_NOW);
    if (!libc) {
        return;
    }
    g_backtrace    = reinterpret_cast<bt_fn>(::dlsym(libc, "backtrace"));
    g_backtrace_fd = reinterpret_cast<bt_fd_fn>(::dlsym(libc, "backtrace_symbols_fd"));
}

namespace {

void abortHandler(int sig, siginfo_t* info, void* /*ctx*/)
{
    __android_log_print(ANDROID_LOG_ERROR, "FreeCAD.abort",
                        "=== caught signal %d (si_code=%d si_pid=%d) ===",
                        sig, info ? info->si_code : -1, info ? info->si_pid : -1);

    resolveBacktrace();
    if (g_backtrace && g_backtrace_fd) {
        void* frames[96];
        int n = g_backtrace(frames, 96);
        // Writes to the fd directly; our stderr is a pipe into the logcat pump, so the
        // frames arrive as FreeCAD.err lines.
        g_backtrace_fd(frames, n, STDERR_FILENO);
    }
    else {
        __android_log_print(ANDROID_LOG_ERROR, "FreeCAD.abort",
                            "(backtrace unavailable on this API level)");
    }

    ::signal(sig, SIG_DFL);
    ::raise(sig);
}

void installAbortHandler()
{
    struct sigaction sa {};
    sa.sa_sigaction = abortHandler;
    sa.sa_flags = SA_SIGINFO | SA_RESETHAND;
    ::sigemptyset(&sa.sa_mask);
    ::sigaction(SIGABRT, &sa, nullptr);
    ::sigaction(SIGSEGV, &sa, nullptr);
    ::sigaction(SIGBUS,  &sa, nullptr);
    ::sigaction(SIGILL,  &sa, nullptr);
}

} // namespace
// ---------------------------------------------------------------------------

extern "C" int freecad_gui_main(int argc, char** argv);

extern "C" int main(int argc, char** argv)
{
    // Python's stdlib is unpacked from the APK into the app's files dir by
    // MainActivity (see extractPythonStdlib). FreeCAD's embedded interpreter would
    // otherwise look under the build machine's prefix, a path baked in at compile
    // time that cannot exist on Android, and fail with
    //   Base::RuntimeError("Failed to init from config")  (src/Base/Interpreter.cpp:666)
    //
    // PYTHONHOME must be the prefix that contains lib/python3.13/.
    {
        const char* home = "/data/data/org.freecad.android/files/python-prefix";
        ::setenv("PYTHONHOME", home, 1);
        // FreeCAD's interpreter uses an isolated PyConfig, so PYTHONHOME alone is ignored;
        // this is the variable FreeCAD itself reads (src/Base/Interpreter.cpp).
        ::setenv("FREECAD_PYTHON_HOME", home, 1);
        // FreeCAD discovers workbenches itself under <userAppData>/Mod and loads each
        // one as a real extension module. Do NOT put Mod/ on the module search path
        // instead: Python would then import the Mod/Part DIRECTORY as a namespace
        // package and Part would come out with no attributes at all
        //   AttributeError: module 'Part' has no attribute 'makeBox'
        ::setenv("FREECAD_USER_HOME", "/data/data/org.freecad.android/files", 1);
        // Keep FreeCAD's own per-user state inside the app sandbox too.
        ::setenv("FREECAD_USER_HOME", home, 1);

        // FreeCAD derives its install prefix (and from it the resource directory that
        // holds every workbench icon) from argv[0] / /proc/self/exe. On Android both
        // give "/system", so it would look under "/system/share/Mod/...", which cannot
        // exist in an APK install. ApplicationDirectories::findHomePath has an Android
        // branch that reads this variable instead.
        ::setenv("FREECAD_ANDROID_HOME", "/data/data/org.freecad.android/files/", 1);

        // gl4es probes the GPU at library-constructor time by creating its own EGL
        // display, Pbuffer surface and context, then destroying them (see
        // src/glx/hardext.c, GetHardwareExtensions). That runs while libGL.so is being
        // loaded -- i.e. BEFORE Qt's Android platform plugin initialises -- and it leaves
        // the EGL display in a state where Qt's own eglChooseConfig returns no configs:
        //   Cannot find EGLConfig, returning null config
        //   EGL Error : Could not create the egl surface
        // followed by abort(). LIBGL_NOTEST makes gl4es skip that probing entirely and
        // fall back to conservative GLES2 defaults (npot/fbo/blend funcs enabled), which
        // is what the hardware does anyway.
        // LIBGL_NOTEST / LIBGL_NOBANNER are set from MainActivity.onCreate instead: gl4es'
        // ELF constructors run during the loader's dlopen of this library, which is earlier
        // than main(), so setting them here would be too late.
        __android_log_print(ANDROID_LOG_INFO, "FreeCAD.bridge",
                            "PYTHONHOME=%s", home);
    }

    __android_log_print(ANDROID_LOG_INFO, "FreeCAD.bridge",
                        "entry: argc=%d, forwarding stdout/stderr to logcat", argc);

    redirect(STDOUT_FILENO, stdout, TAG_OUT, 0);
    redirect(STDERR_FILENO, stderr, TAG_ERR, 0);

    // Rebind the C++ streams to the redirected stdio so std::cout/std::cerr follow.
    std::ios::sync_with_stdio();

    installAbortHandler();

    __android_log_print(ANDROID_LOG_INFO, "FreeCAD.bridge",
                        "calling freecad_gui_main()");

    int rc = freecad_gui_main(argc, argv);

    __android_log_print(ANDROID_LOG_INFO, "FreeCAD.bridge",
                        "freecad_gui_main() returned %d", rc);

    // Flush the pumps before the process disappears.
    ::fflush(stdout);
    ::fflush(stderr);
    ::usleep(300 * 1000);

    return rc;
}
