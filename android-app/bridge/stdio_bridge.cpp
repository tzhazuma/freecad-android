// stdio bridge for FreeCADCmd on Android.
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
//   3. Calls FreeCADCmd's real main(), which was renamed to freecad_cmd_main by
//      `llvm-objcopy --redefine-sym main=freecad_cmd_main` so that this file can own
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

extern "C" int freecad_cmd_main(int argc, char** argv);

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
        __android_log_print(ANDROID_LOG_INFO, "FreeCAD.bridge",
                            "PYTHONHOME=%s", home);
    }

    __android_log_print(ANDROID_LOG_INFO, "FreeCAD.bridge",
                        "entry: argc=%d, forwarding stdout/stderr to logcat", argc);

    redirect(STDOUT_FILENO, stdout, TAG_OUT, 0);
    redirect(STDERR_FILENO, stderr, TAG_ERR, 0);

    // Rebind the C++ streams to the redirected stdio so std::cout/std::cerr follow.
    std::ios::sync_with_stdio();

    __android_log_print(ANDROID_LOG_INFO, "FreeCAD.bridge",
                        "calling freecad_cmd_main()");

    int rc = freecad_cmd_main(argc, argv);

    __android_log_print(ANDROID_LOG_INFO, "FreeCAD.bridge",
                        "freecad_cmd_main() returned %d", rc);

    // Flush the pumps before the process disappears.
    ::fflush(stdout);
    ::fflush(stderr);
    ::usleep(300 * 1000);

    return rc;
}
