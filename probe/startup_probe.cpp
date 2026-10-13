// Minimal isolation probe for the FreeCADCmd-on-Android startup crash.
//
// FreeCADCmd reaches main() and then dies with SIGSEGV, but FreeCAD's own
// CrashReporter (src/Base/CrashReporter/Writer.cpp) installs a SIGSEGV handler that
// tries to use backtrace_symbols(), which bionic does not provide -- so the real
// crash site is hidden behind "Cannot print the stacktrace...".
//
// Rather than guess, this probe walks the same startup steps FreeCADCmd takes
// (setlocale -> QCoreApplication -> Py_Initialize) one at a time and prints which
// step it reached. Whichever step is last before the crash owns the bug.
//
// Build (arm64-v8a, API 24) and run on the emulator; every step prints and flushes
// before it executes, so the last visible line names the failing step.

#include <cstdio>
#include <cstdlib>
#include <clocale>

#include <QCoreApplication>
#include <QString>

#include <Python.h>

static void step(const char* what)
{
    std::fprintf(stderr, "[probe] %s\n", what);
    std::fflush(stderr);
}

int main(int argc, char** argv)
{
    step("start");

    // Step 1: FreeCADCmd's first real statement (MainCmd.cpp:60)
    step("setlocale(LC_ALL, \"\") ...");
    setlocale(LC_ALL, "");
    step("  setlocale ok");

    // Step 2: Qt. FreeCADCmd includes <QString> and App::Application pulls in QtCore.
    // On Android, Qt normally requires a real app lifecycle (QtActivity/QtLoader) and a
    // JNI environment; a bare shell executable never sets that up. If this step is the
    // last one printed, that is the root cause.
    step("QCoreApplication ...");
    {
        QCoreApplication app(argc, argv);
        step("  QCoreApplication ok");
        QString s = QStringLiteral("FreeCAD probe");
        step("  QString ok");
    }

    // Step 3: embedded Python.
    step("Py_Initialize ...");
    Py_Initialize();
    step("  Py_Initialize ok");

    if (Py_IsInitialized()) {
        const char* code = "import sys; sys.stdout.write('python works: %s\\n' % sys.version.split()[0])";
        PyRun_SimpleString(code);
        step("  PyRun_SimpleString ok");
    }

    step("ALL STEPS PASSED");
    return 0;
}
