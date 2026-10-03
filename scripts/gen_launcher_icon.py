#!/usr/bin/env python3
"""Generate the Manager adaptive launcher icon layers from the logo image.

Usage: python scripts/gen_launcher_icon.py [src]
Default src: manager/icon/launcher-src.png. Requires Pillow.
"""
import sys
from pathlib import Path

from PIL import Image, ImageFilter

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "manager/app/src/main/res"
# Foreground canvas is 108dp; px per density.
DENSITIES = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}
# The triangle emblem only (the text row is unreadable at icon size): apex and base corners in
# source pixels. It is placed so its circumscribed circle sits inside the launcher safe zone.
TRIANGLE = ((625, 60), (200, 800), (1055, 800))
CROP = (180, 40, 1075, 815)
SAFE_RADIUS_DP = 31.0  # safe zone is a circle of radius 33dp on the 108dp canvas; keep a margin
CANVAS_DP = 108.0
MONO_THRESHOLD = 90


def circumcircle(a, b, c):
    (ax, ay), (bx, by), (cx, cy) = a, b, c
    d = 2 * (ax * (by - cy) + bx * (cy - ay) + cx * (ay - by))
    ux = ((ax * ax + ay * ay) * (by - cy) + (bx * bx + by * by) * (cy - ay) + (cx * cx + cy * cy) * (ay - by)) / d
    uy = ((ax * ax + ay * ay) * (cx - bx) + (bx * bx + by * by) * (ax - cx) + (cx * cx + cy * cy) * (bx - ax)) / d
    return ux, uy, ((ax - ux) ** 2 + (ay - uy) ** 2) ** 0.5


def fit(logo: Image.Image, size: int) -> Image.Image:
    """Scales the cropped emblem so the circumcircle has SAFE_RADIUS_DP and centers it."""
    ux, uy, radius = circumcircle(*TRIANGLE)
    scale = (SAFE_RADIUS_DP / CANVAS_DP * size) / radius
    scaled = logo.resize((round(logo.width * scale), round(logo.height * scale)), Image.LANCZOS)
    cx = (ux - CROP[0]) * scale
    cy = (uy - CROP[1]) * scale
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    canvas.paste(scaled, (round(size / 2 - cx), round(size / 2 - cy)), scaled)
    return canvas


def mono(logo: Image.Image) -> Image.Image:
    luma = logo.convert("L")
    mask = luma.point(lambda v: 255 if v > MONO_THRESHOLD else 0).filter(ImageFilter.MaxFilter(3))
    white = Image.new("RGBA", logo.size, (255, 255, 255, 255))
    white.putalpha(mask)
    return white


def main() -> None:
    src = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "manager/icon/launcher-src.png"
    logo = Image.open(src).convert("RGBA").crop(CROP)
    logo_mono = mono(logo)
    for density, size in DENSITIES.items():
        out = RES / f"mipmap-{density}"
        out.mkdir(parents=True, exist_ok=True)
        fit(logo, size).save(out / "ic_launcher_logo.png", optimize=True)
        fit(logo_mono, size).save(out / "ic_launcher_logo_mono.png", optimize=True)
        print(f"{density}: {size}px")
    # Splash icon is shown at 240dp; xxxhdpi = 960px so it is not upscaled (soft) on dense screens.
    splash_dir = RES / "drawable-xxxhdpi"
    splash_dir.mkdir(parents=True, exist_ok=True)
    fit(logo, 960).save(splash_dir / "ic_splash_logo.png", optimize=True)
    print("splash: 960px")


if __name__ == "__main__":
    main()
