#!/usr/bin/env python3
"""Deterministic Room Browser icon generator.

Renders the Room Browser mark — a rounded-square (radius ~22% of the side)
with a subtle vertical gradient #2A2A3B -> #1D1D27, a centered bold white
"R" (DejaVuSans-Bold) and a small purple #A78BFA dot in the bottom-right
corner — into this directory:

    icon-16.png icon-32.png icon-48.png icon-64.png
    icon-128.png icon-256.png icon.ico (sizes 16, 32, 48, 256)

The script is fully deterministic: no randomness, fixed font, fixed math,
4x supersampling with a LANCZOS downscale.  Regenerate any time with:

    python3 assets/generate_icons.py
"""

import os

from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
FONT_PATH = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"

PNG_SIZES = [16, 32, 48, 64, 128, 256]
ICO_SIZES = [16, 32, 48, 256]

BG_TOP = (42, 42, 59)      # #2A2A3B
BG_BOTTOM = (29, 29, 39)   # #1D1D27
DOT = (167, 139, 250)      # #A78BFA
WHITE = (255, 255, 255, 255)

SS = 4  # supersampling factor


def _lerp(a, b, t):
    return int(round(a + (b - a) * t))


def _blend(top, bottom, t):
    return (_lerp(top[0], bottom[0], t),
            _lerp(top[1], bottom[1], t),
            _lerp(top[2], bottom[2], t))


def render(size):
    """Renders the mark at `size` px (RGBA, transparent corners)."""
    s = size * SS
    # rounded-square mask
    radius = max(1, int(round(s * 0.22)))
    mask = Image.new("L", (s, s), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        [0, 0, s - 1, s - 1], radius=radius, fill=255)

    # vertical gradient background
    col = Image.new("RGBA", (1, s))
    for y in range(s):
        t = y / (s - 1) if s > 1 else 0.0
        col.putpixel((0, y), _blend(BG_TOP, BG_BOTTOM, t) + (255,))
    grad = col.resize((s, s))

    im = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    im.paste(grad, (0, 0), mask)

    d = ImageDraw.Draw(im)
    # centered bold white "R"
    font = ImageFont.truetype(FONT_PATH, max(1, int(round(s * 0.62))))
    d.text((s * 0.5, s * 0.5), "R", font=font, fill=WHITE, anchor="mm")
    # small purple rounded dot, bottom-right
    dr = s * 0.125
    dx = s * 0.76
    dy = s * 0.76
    d.ellipse([dx - dr, dy - dr, dx + dr, dy + dr], fill=DOT + (255,))

    return im.resize((size, size), Image.LANCZOS)


def main():
    written = []
    for size in PNG_SIZES:
        path = os.path.join(HERE, "icon-%d.png" % size)
        render(size).save(path, format="PNG")
        written.append(path)
    master = render(256)
    ico = os.path.join(HERE, "icon.ico")
    master.save(ico, format="ICO",
                sizes=[(s, s) for s in ICO_SIZES])
    written.append(ico)
    for path in written:
        print("generated %s (%d bytes)" % (path, os.path.getsize(path)))


if __name__ == "__main__":
    main()
