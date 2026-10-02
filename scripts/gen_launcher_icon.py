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
# Crop of the 1254x1254 source that keeps the whole logo (triangle + text) with a small margin.
CROP = (150, 30, 1104, 1184)
SAFE_ZONE = 0.66  # launchers always show the centered 66dp of the 108dp canvas
MONO_THRESHOLD = 90


def fit(logo: Image.Image, size: int) -> Image.Image:
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    box = int(size * SAFE_ZONE)
    scaled = logo.copy()
    scaled.thumbnail((box, box), Image.LANCZOS)
    canvas.paste(scaled, ((size - scaled.width) // 2, (size - scaled.height) // 2), scaled)
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


if __name__ == "__main__":
    main()
