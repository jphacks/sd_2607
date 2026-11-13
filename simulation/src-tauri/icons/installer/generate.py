"""Installer bitmaps for WiX v3 (WixUI) and NSIS (MUI2), styled after ../hp.

Palette from hp: ink #191b18, paper #f2f0e7, yellow (logo) #f9d400, signal red #ff2d2d.
Installer text is drawn by WixUI/MUI2 itself in black, so the areas under it stay light:
  WiX dialog  493x312 : welcome text from x~135 -> art only in the left 128px
  WiX banner  493x58  : title on the left       -> art only on the right
  NSIS sidebar 164x314: text is outside the image -> full art
  NSIS header 150x57  : sits on the white header  -> white background
Rendered at 4x and downsampled for smooth edges.
"""
import sys
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

icon_path, out_dir = Path(sys.argv[1]), Path(sys.argv[2])
out_dir.mkdir(parents=True, exist_ok=True)
S = 4

INK = (25, 27, 24)
PAPER = (242, 240, 231)
PAPER_DEEP = (232, 231, 220)
YELLOW = (249, 212, 0)
RED = (255, 45, 45)
MUTED = (199, 199, 189)
WHITE = (255, 255, 255)

FONT_JP = "C:/Windows/Fonts/YuGothB.ttc"
FONT_LATIN = "C:/Windows/Fonts/seguibl.ttf"  # Segoe UI Black

icon_src = Image.open(icon_path).convert("RGBA")


def font(path, px):
    return ImageFont.truetype(path, px * S)


def disc_icon(d):
    """App icon cropped to a circle (yellow disc with the rhino mark)."""
    ic = icon_src.resize((d, d), Image.LANCZOS)
    mask = Image.new("L", (d, d), 0)
    ImageDraw.Draw(mask).ellipse([0, 0, d - 1, d - 1], fill=255)
    out = Image.new("RGBA", (d, d), (0, 0, 0, 0))
    out.paste(ic, (0, 0), mask)
    return out


def canvas(w, h, bg):
    img = Image.new("RGB", (w * S, h * S), bg)
    return img, ImageDraw.Draw(img)


def finish(img, w, h, name):
    img.resize((w, h), Image.LANCZOS).save(out_dir / name)


def put_disc(img, d, cx, cy):
    ic = disc_icon(d * S)
    img.paste(ic, (cx * S - ic.width // 2, cy * S - ic.height // 2), ic)


def signal_arcs(draw, cx, cy, radii, width, color, start=-80, end=-10):
    for r in radii:
        draw.arc([(cx - r) * S, (cy - r) * S, (cx + r) * S, (cy + r) * S],
                 start, end, fill=color, width=width * S)


def kicker(draw, x, y, text, px, color, dot=YELLOW):
    f = font(FONT_LATIN, px)
    r = px * 0.38
    cy = y + px * 0.62
    draw.ellipse([(x) * S, (cy - r) * S, (x + 2 * r) * S, (cy + r) * S], fill=dot)
    draw.text(((x + 2 * r + px * 0.6) * S, y * S), text, font=f, fill=color)


def dot_grid(draw, x0, y0, x1, y1, step, r, color):
    y = y0
    while y <= y1:
        x = x0
        while x <= x1:
            draw.ellipse([(x - r) * S, (y - r) * S, (x + r) * S, (y + r) * S], fill=color)
            x += step
        y += step


# ---------- WiX dialog 493x312 ----------
W, H = 493, 312
img, d = canvas(W, H, PAPER)
d.rectangle([0, 0, 126 * S, H * S], fill=INK)
d.rectangle([126 * S, 0, 129 * S, H * S], fill=YELLOW)
dot_grid(d, 18, 222, 110, 300, 13, 1.3, (60, 62, 58))
signal_arcs(d, 34, 250, [26, 40, 54], 6, RED)
put_disc(img, 76, 63, 88)
kicker(d, 16, 150, "MOSIMO", 11, PAPER)
d.text((16 * S, 168 * S), "SIMULATOR", font=font(FONT_LATIN, 11), fill=YELLOW)
# faint ring in the lower right corner, away from the welcome text
d.ellipse([400 * S, 230 * S, 540 * S, 370 * S], outline=PAPER_DEEP, width=18 * S)
finish(img, W, H, "wix-dialog.bmp")

# ---------- WiX banner 493x58 ----------
# WixUI's description text runs to x~407 (305 dialog units), so the art starts at x~412.
W, H = 493, 58
img, d = canvas(W, H, PAPER)
d.polygon([(436 * S, 0), (W * S, 0), (W * S, H * S), (418 * S, H * S)], fill=INK)
d.polygon([(430 * S, 0), (436 * S, 0), (418 * S, H * S), (412 * S, H * S)], fill=YELLOW)
signal_arcs(d, W, H, [7, 13], 3, RED, start=180, end=270)
put_disc(img, 36, 458, 27)
finish(img, W, H, "wix-banner.bmp")

# NSIS (MUI2) stretches its bitmaps to the control size, which depends on the dialog font.
# With the Japanese dialog font the controls measure 176x54 (header) and ~192x297 (sidebar)
# at 100% scaling, instead of the 150x57 / 164x314 documented for the English font, so the
# bitmaps are drawn at those sizes to keep their aspect ratio.

# ---------- NSIS sidebar 192x297 ----------
W, H = 192, 297
# same composition as the WiX dialog's left panel
img, d = canvas(W, H, INK)
d.rectangle([(W - 4) * S, 0, W * S, H * S], fill=YELLOW)
dot_grid(d, 20, 212, 176, 288, 13, 1.3, (60, 62, 58))
signal_arcs(d, 40, 242, [28, 43, 58], 6, RED)
put_disc(img, 92, 94, 84)
kicker(d, 22, 148, "MOSIMO", 12, PAPER)
d.text((22 * S, 167 * S), "SIMULATOR", font=font(FONT_LATIN, 12), fill=YELLOW)
finish(img, W, H, "nsis-sidebar.bmp")

# ---------- NSIS header 176x54 ----------
W, H = 176, 54
img, d = canvas(W, H, WHITE)
put_disc(img, 38, 27, 27)
kicker(d, 54, 11, "MOSIMO", 9, INK)
d.text((54 * S, 25 * S), "SIMULATOR", font=font(FONT_LATIN, 9), fill=INK)
d.rectangle([54 * S, 41 * S, 100 * S, 43 * S], fill=YELLOW)
finish(img, W, H, "nsis-header.bmp")

print("ok")
