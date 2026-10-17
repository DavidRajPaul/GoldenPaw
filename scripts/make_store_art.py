#!/usr/bin/env python3
"""Renders GoldenPaw's icon and store graphics from the same paw geometry as the Android icon
(composeApp/src/androidMain/res/drawable/ic_launcher_foreground.xml, 108-unit viewport).

    python3 scripts/make_store_art.py            # needs Pillow; Inter font optional (falls back)

Writes:
    iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png   (iOS app icon, opaque)
    docs/store/assets/play-icon-512.png                                  (Play listing icon)
    docs/store/assets/feature-graphic-1024x500.png                       (Play feature graphic)
"""
import json
import os
from PIL import Image, ImageDraw, ImageFont, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CREAM = (246, 217, 166)      # @color/ic_launcher_background
BROWN = (138, 90, 18)        # paw fill
DEEP = (74, 49, 15)
SS = 4                        # supersampling factor for smooth edges

# (cx, cy, rx, ry) in the 108-unit adaptive-icon viewport
PAW = [(54.0, 63.0, 12.5, 10.5), (38.5, 49.0, 5.2, 6.6), (47.5, 41.0, 5.2, 6.8),
       (60.5, 41.0, 5.2, 6.8), (69.5, 49.0, 5.2, 6.6)]


def draw_paw(d, ox, oy, scale, color):
    for cx, cy, rx, ry in PAW:
        d.ellipse([ox + (cx - rx) * scale, oy + (cy - ry) * scale,
                   ox + (cx + rx) * scale, oy + (cy + ry) * scale], fill=color)


def icon(size):
    """Opaque square icon. Crops the 108 viewport to its central 72 units (the adaptive-icon safe
    area), so the paw fills the icon the way it does on Android launchers."""
    big = size * SS
    img = Image.new("RGB", (big, big), CREAM)
    d = ImageDraw.Draw(img)
    scale = big / 72.0
    draw_paw(d, -18 * scale, -18 * scale, scale, BROWN)
    return img.resize((size, size), Image.LANCZOS)


def font(path_names, size):
    for name in path_names:
        for base in ("/usr/share/fonts/opentype/inter", "/Library/Fonts", os.path.expanduser("~/Library/Fonts"),
                     "C:/Windows/Fonts"):
            p = os.path.join(base, name)
            if os.path.exists(p):
                return ImageFont.truetype(p, size)
    return ImageFont.load_default(size)


def feature_graphic():
    w, h = 1024 * SS, 500 * SS
    img = Image.new("RGB", (w, h))
    # Warm vertical-diagonal gradient: cream → honey.
    top, bottom = (253, 243, 224), (240, 200, 130)
    px = img.load()
    for y in range(h):
        for x in range(0, w, 1):
            t = min(1.0, max(0.0, 0.65 * y / h + 0.35 * x / w))
            px[x, y] = tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3))
    # Soft circle behind the paw.
    glow = Image.new("L", (w, h), 0)
    ImageDraw.Draw(glow).ellipse([90 * SS, 70 * SS, 450 * SS, 430 * SS], fill=110)
    glow = glow.filter(ImageFilter.GaussianBlur(30 * SS))
    img = Image.composite(Image.new("RGB", (w, h), (255, 250, 240)), img, glow)
    d = ImageDraw.Draw(img)
    # Paw, centred at (270, 250), ~250 px wide.
    scale = 5.9 * SS
    draw_paw(d, 270 * SS - 54 * scale, 250 * SS - 55 * scale, scale, BROWN)
    title = font(["InterDisplay-Bold.otf", "Inter-Bold.otf", "arialbd.ttf", "Arial Bold.ttf"], 92 * SS)
    sub = font(["Inter-Medium.otf", "arial.ttf", "Arial.ttf"], 38 * SS)
    d.text((500 * SS, 168 * SS), "GoldenPaw", font=title, fill=DEEP)
    d.text((504 * SS, 282 * SS), "Calm care for", font=sub, fill=(110, 78, 34))
    d.text((504 * SS, 330 * SS), "your senior pet", font=sub, fill=(110, 78, 34))
    return img.resize((1024, 500), Image.LANCZOS)


def save(img, rel):
    path = os.path.join(ROOT, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path, optimize=True)
    print("wrote", rel)


if __name__ == "__main__":
    iconset = "iosApp/iosApp/Assets.xcassets/AppIcon.appiconset"
    save(icon(1024), f"{iconset}/AppIcon-1024.png")
    with open(os.path.join(ROOT, iconset, "Contents.json"), "w") as f:
        json.dump({"images": [{"filename": "AppIcon-1024.png", "idiom": "universal", "platform": "ios",
                               "size": "1024x1024"}], "info": {"author": "xcode", "version": 1}}, f, indent=2)
    with open(os.path.join(ROOT, "iosApp/iosApp/Assets.xcassets/Contents.json"), "w") as f:
        json.dump({"info": {"author": "xcode", "version": 1}}, f, indent=2)
    save(icon(512), "docs/store/assets/play-icon-512.png")
    save(feature_graphic(), "docs/store/assets/feature-graphic-1024x500.png")
