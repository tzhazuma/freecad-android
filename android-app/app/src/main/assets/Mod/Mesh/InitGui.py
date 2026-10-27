# SPDX-License-Identifier: LGPL-2.1-or-later

# Mesh gui init module
# (c) 2004 Werner Mayer
#
# Gathering all the information to start FreeCAD
# This is the second one of three init scripts, the third one
# runs when the gui is up

# ***************************************************************************
# *   Copyright (c) 2004 Werner Mayer <werner.wm.mayer@gmx.de>              *
# *                                                                         *
# *   This file is part of the FreeCAD CAx development system.              *
# *                                                                         *
# *   This program is free software; you can redistribute it and/or modify  *
# *   it under the terms of the GNU Lesser General Public License (LGPL)    *
# *   as published by the Free Software Foundation; either version 2 of     *
# *   the License, or (at your option) any later version.                   *
# *   for detail see the LICENCE text file.                                 *
# *                                                                         *
# *   FreeCAD is distributed in the hope that it will be useful,            *
# *   but WITHOUT ANY WARRANTY; without even the implied warranty of        *
# *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the         *
# *   GNU Lesser General Public License for more details.                   *
# *                                                                         *
# *   You should have received a copy of the GNU Library General Public     *
# *   License along with FreeCAD; if not, write to the Free Software        *
# *   Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  *
# *   USA                                                                   *
# *                                                                         *
# ***************************************************************************/


class MeshWorkbench(Workbench):
    "Mesh workbench object"

    def __init__(self):
        self.__class__.Icon = (
            FreeCAD.getResourceDir() + "Mod/Mesh/Resources/icons/MeshWorkbench.svg"
        )
        self.__class__.MenuText = "Mesh"
        self.__class__.ToolTip = "Mesh workbench"

    def Initialize(self):
        import Mesh
        import MeshGui

        # The flattening command needs two things this port does not ship: `flatmesh`, a pybind11
        # native library built from Mod/MeshPart, and `MeshPartGui`. Upstream wraps the import in
        # try/except and prints the failure, which surfaces in the Report view as a red error on
        # every workbench switch even though nothing is broken -- the rest of the Mesh workbench
        # loads fine. The check below establishes which of the two is missing and only logs when the
        # command *should* have been available, so an Android build stays quiet while a desktop build
        # that is genuinely missing a module still says so.
        try:
            import flatmesh
            import MeshFlatteningCommand
        except ImportError as e:
            import FreeCAD

            _missing = str(e)
            _expected_absent = ("flatmesh" in _missing or "MeshPartGui" in _missing
                                or "MeshFlatteningCommand" in _missing)
            if not _expected_absent:
                FreeCAD.Console.PrintError(_missing)

    def GetClassName(self):
        return "MeshGui::Workbench"


Gui.addWorkbench(MeshWorkbench())

FreeCAD.__unit_test__ += ["MeshTestsGui"]
