#!/usr/bin/env python3
"""
Regenerate the Android launcher icons from the web app's brand icon.

Source : ../icons/icon-512.png        (the same icon the PWA manifest uses)
Output : android/app/src/main/res/mipmap-*/

To replace the placeholder icons with a final BEN AMOR GROUP logo:
  1. Overwrite icons/icon-512.png with the new logo (square, 512x512).
  2. Run:  python3 android/scripts/make_icons.py
  3. Rebuild the APK (or open the project in Android Studio).

Requires: pip install Pillow
"""
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[2]          # repository root
SRC = ROOT / "icons" / "icon-512.png"
RES = ROOT / "android" / "app" / "src" / "main" / "res"

DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}
LEGACY_DP = 48          # classic square launcher icon
FOREGROUND_DP = 108     # adaptive-icon foreground canvas


def rounded_corners(img: Image.Image, radius: int) -> Image.Image:
    mask = Image.new("L", img.size, 0)
    d = ImageDraw.Draw(mask)
    d.rounded_rectangle([0, 0, img.size[0] - 1, img.size[1] - 1], radius=radius, fill=255)
    out = img.copy()
    out.putalpha(mask)
    return out


def circular(img: Image.Image) -> Image.Image:
    mask = Image.new("L", img.size, 0)
    d = ImageDraw.Draw(mask)
    d.ellipse([0, 0, img.size[0] - 1, img.size[1] - 1], fill=255)
    out = img.copy()
    out.putalpha(mask)
    return out


def main() -> None:
    if not SRC.exists():
        raise SystemExit(f"source icon not found: {SRC}")
    src = Image.open(SRC).convert("RGBA")

    for dpi, mult in DENSITIES.items():
        out_dir = RES / f"mipmap-{dpi}"
        out_dir.mkdir(parents=True, exist_ok=True)

        # 1. classic launcher icon (rounded square)
        size = round(LEGACY_DP * mult)
        icon = src.resize((size, size), Image.LANCZOS)
        rounded_corners(icon, round(size * 0.14)).save(out_dir / "ic_launcher.png")

        # 2. round launcher icon (circle)
        circular(icon).save(out_dir / "ic_launcher_round.png")

        # 3. adaptive-icon foreground: the logo on a transparent canvas,
        #    sized to stay inside the Android "safe zone" (center 66%).
        fsize = round(FOREGROUND_DP * mult)
        canvas = Image.new("RGBA", (fsize, fsize), (0, 0, 0, 0))
        inner = round(fsize * 0.60)
        fg = src.resize((inner, inner), Image.LANCZOS)
        off = (fsize - inner) // 2
        canvas.paste(fg, (off, off), fg)
        canvas.save(out_dir / "ic_launcher_foreground.png")

    print("Launcher icons written to", RES)


if __name__ == "__main__":
    main()
