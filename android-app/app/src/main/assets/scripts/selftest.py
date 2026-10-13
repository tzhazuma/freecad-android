# End-to-end proof that FreeCAD works on Android.
#
#   Android app -> libFreeCADCmd -> libFreeCADApp/Base -> libpython3.13
#     -> workbench modules under Mod/ -> 32 cross-compiled OCCT toolkits
#
# Each workbench is reported individually so a missing or broken one is visible
# rather than aborting the whole run.

import sys, traceback

print("=== FreeCAD Android self-test ===", flush=True)
print("python  :", sys.version.split()[0], flush=True)
print("platform:", sys.platform, flush=True)

results = {}

def check(name, fn):
    try:
        fn()
        results[name] = "PASS"
        print(f"[PASS] {name}", flush=True)
    except Exception as exc:                       # noqa: BLE001
        results[name] = f"FAIL: {type(exc).__name__}: {exc}"
        print(f"[FAIL] {name}: {type(exc).__name__}: {exc}", flush=True)

def t_freecad():
    import FreeCAD
    print("  FreeCAD", FreeCAD.Version()[:3], flush=True)

def t_freecad_pkg():
    from freecad import utils
    print("  freecad.utils imported", flush=True)

def t_part():
    import Part
    box = Part.makeBox(10, 20, 30)
    assert abs(box.Volume - 6000.0) < 1e-6, box.Volume
    assert abs(box.Area - 2200.0) < 1e-6, box.Area
    print(f"  box volume={box.Volume} area={box.Area}", flush=True)

def t_boolean():
    import Part
    cut = Part.makeBox(10, 10, 10).cut(Part.makeCylinder(3, 10))
    assert 0 < cut.Volume < 1000, cut.Volume
    print(f"  boolean cut volume={cut.Volume}", flush=True)

def t_step():
    import Part
    box = Part.makeBox(5, 5, 5)
    path = "/data/data/org.freecad.android/files/box.step"
    box.exportStep(path)
    print(f"  STEP written to {path}", flush=True)

# Workbench modules that expose a plain importable extension module.
SIMPLE_MODULES = [
    "Sketcher", "Mesh", "Spreadsheet", "Materials",
    "Points", "ReverseEngineering", "Inspection", "Measure", "Robot",
    "Surface", "Web", "Start", "DraftUtils", "TechDraw",
]

def make_import(modname):
    def _fn():
        __import__(modname)
        print(f"  {modname} imported", flush=True)
    return _fn

check("FreeCAD core", t_freecad)
check("freecad package", t_freecad_pkg)
check("Part geometry", t_part)
check("OCCT boolean", t_boolean)
check("STEP export", t_step)
check("PartDesign module", make_import("PartDesign"))
check("Import module", make_import("Import"))

for m in SIMPLE_MODULES:
    check(f"{m} module", make_import(m))

print("\n=== SUMMARY ===", flush=True)
for k, v in results.items():
    print(f"  {k:<26} {v}", flush=True)
passed = sum(1 for v in results.values() if v == "PASS")
print(f"  {passed}/{len(results)} passed", flush=True)
print("SELFTEST DONE", flush=True)
