# -*- coding: utf-8 -*-
"""parcel-SPU landing page asset pipeline.

Reads app icon + real device screenshots from the repo, writes web-optimised
assets into ../assets/.  Idempotent: safe to re-run.

Usage:  python site/tools/build_assets.py
"""
import sys
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parents[2]          # E:\claude\parcel-SPU
SITE = ROOT / "site"
OUT = SITE / "assets"                               # 会上线，只放页面真正引用的文件
CACHE = SITE / "tools" / ".cache"                   # 中间产物，不上线
OUT.mkdir(parents=True, exist_ok=True)
CACHE.mkdir(parents=True, exist_ok=True)

GALLERY_W = 720      # gallery thumbnails
HERO_W = 900         # hero phone screenshot
HERO_SHOT = 1        # show1.jpg = home list, most representative app screen
GALLERY_SHOTS = range(1, 10)

FONT_CANDIDATES = [
    (r"C:\Windows\Fonts\msyhbd.ttc", 0),
    (r"C:\Windows\Fonts\msyh.ttc", 0),
    (r"C:\Windows\Fonts\simhei.ttf", 0),
]


def log(*a):
    print(" ".join(str(x) for x in a), flush=True)


def font(size):
    for path, idx in FONT_CANDIDATES:
        try:
            return ImageFont.truetype(path, size, index=idx)
        except Exception:
            continue
    return ImageFont.load_default()


def rounded_mask(size, radius):
    m = Image.new("L", size, 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, size[0] - 1, size[1] - 1], radius=radius, fill=255)
    return m


# --------------------------------------------------------------------- icons

def build_icons():
    src = ROOT / "app/src/main/res/mipmap-xxxhdpi/ic_launcher.png"
    icon = Image.open(src).convert("RGBA")
    for name, size in [
        ("icon.png", 192),
        ("apple-touch-icon.png", 180),
        ("favicon-64.png", 64),
    ]:
        icon.resize((size, size), Image.LANCZOS).save(OUT / name, optimize=True)
    icon.save(OUT / "favicon.ico", sizes=[(16, 16), (32, 32), (48, 48)])
    # 512 只用于合成 OG 分享图，页面不引用 ⇒ 放中间缓存，不上线（省 ~426 KB）
    icon.resize((512, 512), Image.LANCZOS).save(CACHE / "icon-512.png", optimize=True)
    log("icons ok (source %dx%d)" % icon.size)


# ---------------------------------------------------------------- screenshots

def build_shots():
    for i in GALLERY_SHOTS:
        p = ROOT / ("show%d.jpg" % i)
        if not p.exists():
            log("skip missing", p.name)
            continue
        im = Image.open(p).convert("RGB")           # show3/show9 are PNG bytes
        w, h = im.size
        if w > GALLERY_W:
            im = im.resize((GALLERY_W, round(h * GALLERY_W / w)), Image.LANCZOS)
        im.save(OUT / ("shot-%d.jpg" % i), "JPEG", quality=84, optimize=True, progressive=True)
        log("shot-%d.jpg  %dx%d -> %dx%d" % (i, w, h, im.size[0], im.size[1]))

    hero = Image.open(ROOT / ("show%d.jpg" % HERO_SHOT)).convert("RGB")
    w, h = hero.size
    hero = hero.resize((HERO_W, round(h * HERO_W / w)), Image.LANCZOS)
    hero.save(OUT / "hero-list.jpg", "JPEG", quality=88, optimize=True, progressive=True)
    log("hero-list.jpg %dx%d" % hero.size)


# ------------------------------------------------------------------- og card

def build_og():
    W, H = 1200, 630
    top, bot = (8, 15, 27), (14, 40, 50)
    card = Image.new("RGB", (W, H))
    d = ImageDraw.Draw(card)
    for y in range(H):
        t = y / (H - 1)
        d.line([(0, y), (W, y)], fill=tuple(round(top[i] + (bot[i] - top[i]) * t) for i in range(3)))

    blobs = Image.new("RGB", (W, H), (0, 0, 0))
    bd = ImageDraw.Draw(blobs)
    bd.ellipse([-160, -140, 430, 400], fill=(18, 118, 104))
    bd.ellipse([830, 300, 1290, 780], fill=(122, 82, 14))
    blobs = blobs.filter(ImageFilter.GaussianBlur(120))
    card = Image.blend(card, ImageChops.add(card, blobs, scale=1.0), 0.55)

    icon = Image.open(CACHE / "icon-512.png").convert("RGBA")
    icon = icon.resize((208, 208), Image.LANCZOS)
    card.paste(icon, (86, 211), rounded_mask((208, 208), 46))

    d = ImageDraw.Draw(card)
    d.rounded_rectangle([86, 448, 86 + 300, 448 + 6], radius=3, fill=(47, 227, 196))

    x = 342
    d.text((x, 168), "上海海事大学 · 快递取件助手", font=font(32), fill=(126, 226, 208))
    d.text((x, 216), "取件码海大版", font=font(88), fill=(242, 247, 251))
    d.text((x, 330), "取件码自动上桌面 · 离线条码出示", font=font(33), fill=(185, 201, 216))
    d.text((x, 378), "最短取件路线 · 列表三分类", font=font(33), fill=(185, 201, 216))
    d.text((x, 470), "k.corvinyu.icu", font=font(34), fill=(255, 194, 71))

    card.save(OUT / "og.png", optimize=True)
    log("og.png ok")


def main():
    build_icons()
    build_shots()
    build_og()
    log("--- assets ---")
    for f in sorted(OUT.iterdir()):
        log("  %-20s %7.1f KB" % (f.name, f.stat().st_size / 1024))


if __name__ == "__main__":
    sys.exit(main())
