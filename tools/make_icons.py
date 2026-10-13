#!/usr/bin/env python3
"""Draws the launcher icon layers (adaptive icon) with Pillow and the game font.

Usage: python3 -I tools/make_icons.py
Writes app/src/main/res/mipmap-*/ic_launcher_{background,foreground,monochrome}.png
"""
import os
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FONT = os.path.join(ROOT, 'app', 'src', 'main', 'assets', 'fonts', 'BalooBhaijaan2.ttf')
DENSITIES = {'mdpi': 1, 'hdpi': 1.5, 'xhdpi': 2, 'xxhdpi': 3, 'xxxhdpi': 4}
BASE = 108  # dp, adaptive icon canvas


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def background(px):
    img = Image.new('RGB', (px, px))
    top, horizon, sea = (93, 169, 236), (247, 216, 174), (26, 110, 149)
    d = ImageDraw.Draw(img)
    for y in range(px):
        t = y / (px - 1)
        if t < 0.62:
            c = lerp(top, horizon, (t / 0.62) ** 1.3)
        else:
            c = lerp((71, 194, 196), sea, (t - 0.62) / 0.38)
        d.line([(0, y), (px, y)], fill=c)
    # soft sun glow
    glow = Image.new('L', (px, px), 0)
    ImageDraw.Draw(glow).ellipse((px * 0.55, px * 0.18, px * 0.95, px * 0.58), fill=150)
    glow = glow.filter(ImageFilter.GaussianBlur(px * 0.08))
    img = Image.composite(Image.new('RGB', (px, px), (255, 241, 214)), img, glow)
    return img


def tile_layer(px, mono=False):
    img = Image.new('RGBA', (px, px), (0, 0, 0, 0))
    s = px / BASE
    # tile occupies the safe zone (66dp) with room for its depth
    x0, y0, x1, y1 = 27 * s, 25 * s, 81 * s, 79 * s
    r = 12 * s
    depth = 5 * s
    d = ImageDraw.Draw(img)
    if not mono:
        shadow = Image.new('RGBA', (px, px), (0, 0, 0, 0))
        ImageDraw.Draw(shadow).rounded_rectangle((x0 + 2 * s, y0 + 6 * s, x1 + 2 * s, y1 + 6 * s), r, fill=(10, 30, 50, 120))
        img.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(3 * s)))
        d = ImageDraw.Draw(img)
        d.rounded_rectangle((x0, y0 + depth, x1, y1 + depth), r, fill=(36, 120, 74, 255))  # side
        d.rounded_rectangle((x0, y0, x1, y1), r, fill=(59, 164, 102, 255))                 # face
        hl = Image.new('RGBA', (px, px), (0, 0, 0, 0))
        ImageDraw.Draw(hl).rounded_rectangle((x0 + 3 * s, y0 + 2 * s, x1 - 3 * s, y0 + 16 * s), r * 0.8, fill=(255, 255, 255, 46))
        img.alpha_composite(hl.filter(ImageFilter.GaussianBlur(2 * s)))
        d = ImageDraw.Draw(img)
    font = ImageFont.truetype(FONT, int(40 * s), layout_engine=ImageFont.Layout.RAQM)
    font.set_variation_by_axes([800])
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    color = (255, 255, 255, 255)
    if mono:
        d.rounded_rectangle((x0, y0, x1, y1), r, outline=color, width=int(4 * s))
    d.text((cx, cy + 1 * s), 'خ', font=font, fill=color, anchor='mm')
    return img


def main():
    res = os.path.join(ROOT, 'app', 'src', 'main', 'res')
    for name, scale in DENSITIES.items():
        px = int(BASE * scale)
        out = os.path.join(res, f'mipmap-{name}')
        os.makedirs(out, exist_ok=True)
        background(px).save(os.path.join(out, 'ic_launcher_background.png'), optimize=True)
        tile_layer(px).save(os.path.join(out, 'ic_launcher_foreground.png'), optimize=True)
        tile_layer(px, mono=True).save(os.path.join(out, 'ic_launcher_monochrome.png'), optimize=True)
    # preview for review
    prev = background(432).convert('RGBA')
    prev.alpha_composite(tile_layer(432))
    mask = Image.new('L', (432, 432), 0)
    ImageDraw.Draw(mask).ellipse((54, 54, 378, 378), fill=255)
    out = Image.new('RGBA', (432, 432), (0, 0, 0, 0))
    out.paste(prev, mask=mask)
    out.save(os.path.join(ROOT, 'docs', 'icon_preview.png'))


if __name__ == '__main__':
    main()
