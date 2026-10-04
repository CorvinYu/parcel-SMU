# -*- coding: utf-8 -*-
"""parcel-SMU landing page asset pipeline.

0.2.0 起，页面上的所有界面图形都改成**内联 HTML/CSS/SVG 元素**（见 index.html 的
「界面元素一览」与能力卡），所以这里只剩两件事：

1. `icon.png` / `apple-touch-icon.png` / `favicon-64.png` / `favicon.ico` —— 取自 App 的启动图标
2. `og.png` —— 社交分享大图（1200×630）

🚫 **不再生成任何「界面截图」**：`hero-list.jpg` / `shot-1..9.jpg` / `haida-*.jpg` 已从
assets 与页面里移除。仓库根目录的实机截图（`show1..9.jpg`）保留在仓库里供存档，
但不再上线（含真实取件码与地址）。

Idempotent: safe to re-run.

Usage:  python site/tools/build_assets.py
        python site/tools/build_assets.py --check   # 只校验 assets 与页面引用一致，不写文件
"""
import re
import sys
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parents[2]          # E:\claude\parcel-SMU
SITE = ROOT / "site"
OUT = SITE / "assets"                               # 会上线
CACHE = SITE / "tools" / ".cache"                   # 中间产物，不上线
OUT.mkdir(parents=True, exist_ok=True)
CACHE.mkdir(parents=True, exist_ok=True)

FONT_CANDIDATES = [
    (r"C:\Windows\Fonts\msyhbd.ttc", 0),
    (r"C:\Windows\Fonts\msyh.ttc", 0),
    (r"C:\Windows\Fonts\simhei.ttf", 0),
]

CHECK_ONLY = "--check" in sys.argv


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
    # 512 只用于合成 OG 分享图，页面不引用 ⇒ 放中间缓存，不上线
    icon.resize((512, 512), Image.LANCZOS).save(CACHE / "icon-512.png", optimize=True)
    log("icons ok (source %dx%d)" % icon.size)


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
    # 应用 0.1.8 起正式更名为「海大取件码」（旧名「取件码海大版」）
    d.text((x, 168), "上海海事大学 · 快递取件助手", font=font(32), fill=(126, 226, 208))
    d.text((x, 216), "海大取件码", font=font(88), fill=(242, 247, 251))
    d.text((x, 330), "取件码自动上桌面 · 离线条码出示", font=font(33), fill=(185, 201, 216))
    d.text((x, 378), "最短取件路线 · 列表三分类", font=font(33), fill=(185, 201, 216))
    d.text((x, 470), "k.corvinyu.icu", font=font(34), fill=(255, 194, 71))

    card.save(OUT / "og.png", optimize=True)
    log("og.png ok")


# ------------------------------------------------------------------- check

def check():
    """assets/ 里的文件必须都被页面（或页面加载的 JS）引用；引用到的文件必须都存在。"""
    html = (SITE / "index.html").read_text(encoding="utf-8")
    # 首屏动态地图是 JS 运行时再取的（hero-map.js 里写着 'assets/venue-model.json' 等）
    js = "".join(p.read_text(encoding="utf-8") for p in OUT.glob("*.js"))
    used = set(re.findall(r'assets/([A-Za-z0-9_.\-]+)', html + js))
    have = {p.name for p in OUT.iterdir() if p.is_file()}
    missing = sorted(used - have)
    orphan = sorted(have - used)
    for n in sorted(used & have):
        log("  ok      %s" % n)
    for n in missing:
        log("  🔴 页面引用了但文件不存在：%s" % n)
    for n in orphan:
        log("  ⚠️  文件存在但页面没引用（可以删）：%s" % n)
    return 1 if missing else 0


def main():
    if CHECK_ONLY:
        return check()
    build_icons()
    build_og()
    log("--- assets ---")
    for f in sorted(OUT.iterdir()):
        log("  %-24s %7.1f KB" % (f.name, f.stat().st_size / 1024))
    return check()


if __name__ == "__main__":
    sys.exit(main())
