"""DMG background and app icon (icon.icns) for macOS, styled after ../hp like generate.py.

Palette from hp: ink #191b18, paper #f2f0e7, yellow (logo) #f9d400, signal red #ff2d2d.
  DMG window 660x400 : Finder draws the two icons (128px) and their labels on top of the
                       background at APP / APPS below, so those spots stay plain paper.
                       Written as a TIFF holding 1x and 2x so Retina displays stay sharp.
  icon.icns          : the hp icon (round mark on yellow) in Apple's rounded square, 824px of
                       1024px with a soft shadow. macOS 26 puts any other shape on a gray
                       plate, so the mark is not cut out as a circle here as it is on Windows.
Requires macOS (fonts, tiffutil, iconutil).
"""
import math
import subprocess
import sys
import tempfile
from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter, ImageFont

icon_path, out_dir, icns_path = Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3])
out_dir.mkdir(parents=True, exist_ok=True)

INK = (25, 27, 24)
PAPER = (242, 240, 231)
PAPER_DEEP = (232, 231, 220)
YELLOW = (249, 212, 0)
RED = (255, 45, 45)
MUTED = (104, 105, 97)

FONT_HEAVY = "/System/Library/Fonts/ヒラギノ角ゴシック W8.ttc"
FONT_BOLD = "/System/Library/Fonts/ヒラギノ角ゴシック W6.ttc"

# window size and icon centers, as in tauri.conf.json (bundle.macOS.dmg); its windowSize is
# 28px taller because Finder counts the title bar in the window height
W, H = 660, 400
APP = (170, 262)
APPS = (490, 262)

icon_src = Image.open(icon_path).convert("RGBA")


def disc_icon(d):
    """App icon cropped to a circle, drawn at 4x and downsampled for a smooth edge."""
    big = d * 4
    ic = icon_src.resize((big, big), Image.LANCZOS)
    mask = Image.new("L", (big, big), 0)
    ImageDraw.Draw(mask).ellipse([0, 0, big - 1, big - 1], fill=255)
    out = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    out.paste(ic, (0, 0), mask)
    return out.resize((d, d), Image.LANCZOS)


def background(s):
    """The DMG window at scale s (1 or 2), drawn at 4x of that and downsampled."""
    S = 4 * s
    img = Image.new("RGB", (W * S, H * S), PAPER)
    d = ImageDraw.Draw(img)

    def box(x0, y0, x1, y1):
        return [x0 * S, y0 * S, x1 * S, y1 * S]

    def font(path, px):
        return ImageFont.truetype(path, round(px * S))

    # header band, like the hp footer: ink with a yellow rule
    d.rectangle(box(0, 0, W, 108), fill=INK)
    d.rectangle(box(0, 108, W, 112), fill=YELLOW)
    y = 82
    while y <= 100:
        x = 470
        while x <= 646:
            d.ellipse(box(x - 1.3, y - 1.3, x + 1.3, y + 1.3), fill=(60, 62, 58))
            x += 13
        y += 13
    for r in (26, 42, 58):
        d.arc(box(W - r, 108 - r, W + r, 108 + r), 180, 270, fill=RED, width=6 * S)

    disc = disc_icon(68 * S)
    img.paste(disc, (24 * S, 20 * S), disc)
    d.ellipse(box(110, 31, 119, 40), fill=YELLOW)
    d.text((126 * S, 28 * S), "MOSIMO SIMULATOR", font=font(FONT_HEAVY, 12), fill=PAPER)
    d.text((110 * S, 50 * S), "もしもシミュレーター", font=font(FONT_HEAVY, 26), fill=YELLOW)

    # instruction
    msg = "アプリを Applications フォルダへドラッグしてください"
    f = font(FONT_BOLD, 14)
    tw = d.textlength(msg, font=f)
    d.text(((W * S - tw) / 2, 146 * S), msg, font=f, fill=INK)

    # faint ring in the lower right corner, as on the WiX dialog
    d.ellipse(box(560, 320, 720, 480), outline=PAPER_DEEP, width=18 * S)

    # arrow from the app to Applications, between the two icons
    ax0, ax1, ay = APP[0] + 92, APPS[0] - 92, APP[1] - 10
    d.line(box(ax0, ay, ax1 - 14, ay), fill=INK, width=6 * S)
    d.polygon([((ax1 - 20) * S, (ay - 12) * S), (ax1 * S, ay * S), ((ax1 - 20) * S, (ay + 12) * S)], fill=INK)
    d.ellipse(box(ax0 - 7, ay - 7, ax0 + 7, ay + 7), fill=YELLOW)

    return img.resize((W * s, H * s), Image.LANCZOS)


def squircle(d, n=5, steps=720):
    """Points of the superellipse |x|^n + |y|^n = 1 that approximates Apple's rounded square."""
    r = d / 2
    pts = []
    for i in range(steps):
        t = 2 * math.pi * i / steps
        c, s = math.cos(t), math.sin(t)
        pts.append((r + r * math.copysign(abs(c) ** (2 / n), c),
                    r + r * math.copysign(abs(s) ** (2 / n), s)))
    return pts


def mac_icon(px):
    """Rounded square on Apple's 1024 grid: 824px body, shadow 28px blur offset 12px down."""
    k = px / 1024
    out = Image.new("RGBA", (px, px), (0, 0, 0, 0))
    body = round(824 * k)
    off = (px - body) // 2
    big = body * 4
    mask = Image.new("L", (big, big), 0)
    ImageDraw.Draw(mask).polygon(squircle(big), fill=255)
    mask = mask.resize((body, body), Image.LANCZOS)
    if px >= 64:
        shadow = Image.new("RGBA", (px, px), (0, 0, 0, 0))
        shadow.paste((0, 0, 0, 90), (off, off + round(12 * k)), mask)
        out = Image.alpha_composite(out, shadow.filter(ImageFilter.GaussianBlur(14 * k)))
    tile = Image.new("RGBA", (body, body), (0, 0, 0, 0))
    tile.paste(icon_src.resize((body, body), Image.LANCZOS), (0, 0), mask)
    out.alpha_composite(tile, (off, off))
    return out


with tempfile.TemporaryDirectory() as tmp:
    tmp = Path(tmp)
    background(1).save(tmp / "bg.png", dpi=(72, 72))
    background(2).save(tmp / "bg@2x.png", dpi=(144, 144))
    subprocess.run(["tiffutil", "-cathidpicheck", tmp / "bg.png", tmp / "bg@2x.png",
                    "-out", out_dir / "dmg-background.tiff"], check=True, capture_output=True)

    iconset = tmp / "icon.iconset"
    iconset.mkdir()
    for size in (16, 32, 128, 256, 512):
        mac_icon(size).save(iconset / f"icon_{size}x{size}.png")
        mac_icon(size * 2).save(iconset / f"icon_{size}x{size}@2x.png")
    subprocess.run(["iconutil", "-c", "icns", iconset, "-o", icns_path], check=True)

print("ok")
