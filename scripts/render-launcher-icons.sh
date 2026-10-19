#!/usr/bin/env bash
# Regenerate the Android launcher icons from FreeCAD's upstream artwork.
#
# The source of truth is FreeCAD's own src/Gui/Icons/freecad.svg, not a hand-drawn
# placeholder. The script produces:
#   mipmap-<density>/ic_launcher.png           full-bleed square icon
#   mipmap-<density>/ic_launcher_round.png     white disc + the same artwork
#   drawable-<density>/ic_launcher_foreground.png  adaptive foreground at 2/3 of 108dp
#
# Usage:
#   FREECAD_SRC=~/FreeCAD ./scripts/render-launcher-icons.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="${FREECAD_SRC:-$HOME/FreeCAD}"
SVG="$SRC/src/Gui/Icons/freecad.svg"
RES="$ROOT/android-app/app/src/main/res"
PYTHON="${PYTHON:-/usr/bin/python3}"

[ -f "$SVG" ] || { echo "missing upstream artwork: $SVG" >&2; exit 1; }
command -v rsvg-convert >/dev/null || { echo "rsvg-convert not found" >&2; exit 1; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

"$PYTHON" - "$SVG" "$RES" "$TMP" <<'PY'
import subprocess
import sys
from PIL import Image, ImageDraw

svg, res, tmp = sys.argv[1], sys.argv[2], sys.argv[3]
densities = {'mdpi': 48, 'hdpi': 72, 'xhdpi': 96, 'xxhdpi': 144, 'xxxhdpi': 192}

for density, size in densities.items():
    art_path = f'{tmp}/art-{size}.png'
    subprocess.run(
        ['rsvg-convert', '-w', str(size), '-h', str(size), svg, '-o', art_path],
        check=True,
    )
    art = Image.open(art_path).convert('RGBA')
    art.save(f'{res}/mipmap-{density}/ic_launcher.png')

    # Round: the same artwork over an opaque white disc, clipped to the disc.
    disc = Image.new('L', (size * 4, size * 4), 0)
    ImageDraw.Draw(disc).ellipse((0, 0, size * 4 - 1, size * 4 - 1), fill=255)
    disc = disc.resize((size, size), Image.LANCZOS)
    round_icon = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    round_icon.paste(Image.new('RGBA', (size, size), (255, 255, 255, 255)), (0, 0), disc)
    inner = max(1, round(size * 0.75))
    offset = (size - inner) // 2
    round_icon.alpha_composite(art.resize((inner, inner), Image.LANCZOS), (offset, offset))
    round_icon.putalpha(disc)
    round_icon.save(f'{res}/mipmap-{density}/ic_launcher_round.png')

    # Adaptive foreground: 108dp canvas, 72dp safe zone -> artwork at 2/3.
    canvas_size = {'mdpi': 108, 'hdpi': 162, 'xhdpi': 216,
                   'xxhdpi': 324, 'xxxhdpi': 432}[density]
    canvas = Image.new('RGBA', (canvas_size, canvas_size), (0, 0, 0, 0))
    inner = max(1, round(canvas_size * 2 / 3))
    offset = (canvas_size - inner) // 2
    canvas.alpha_composite(art.resize((inner, inner), Image.LANCZOS), (offset, offset))
    canvas.save(f'{res}/drawable-{density}/ic_launcher_foreground.png')

    print(f'{density}: {size}px square, {size}px round, {canvas_size}px adaptive')
PY
