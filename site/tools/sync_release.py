# -*- coding: utf-8 -*-
"""把 k.corvinyu.icu 下载页的**版本相关字段**从 GitHub Releases 自动同步过来。

为什么要有它：每次发新版本，页面里散着 6 类字段要改（文件名、版本号、体积、字节数、
SHA-256、日期），手改容易漏（尤其 SHA-256 有两处：正文 + 复制按钮的 data-copy）。
这个脚本只认「已发布」的 Release —— 与 `site/README.md` 的维护约定一致：
**未发 Release 的本地构建不得出现在下载页上**。

它改什么（全部以「页面现在写的那个版本」为基准做替换，所以可重复运行）：
  1. `v<旧版本>` → `v<新版本>`（含 APK 文件名与 GitHub 下载链接）
  2. `<旧字节数> 字节` → `<新字节数> 字节`
  3. `<旧体积> MB` → `<新体积> MB`
  4. 旧 SHA-256（含 data-copy 属性）→ 新 SHA-256
  5. 旧日期 → 新日期（同一天则不动）

用法：
    python site/tools/sync_release.py                 # 用最新的正式 Release，只改本地文件
    python site/tools/sync_release.py --tag v0.1.8    # 指定版本
    python site/tools/sync_release.py --dry-run       # 只看会改什么
    python site/tools/sync_release.py --deploy        # 改完 + 上传到 Mac mini + 外网复核
    python site/tools/sync_release.py --deploy --verify-full   # 外网下载整包核对 SHA-256

依赖：只用标准库（urllib）。**必须直连**——本机代理会掐断 POST/大传输；
GitHub API 与站点直连都实测可用。
"""
import argparse
import hashlib
import io
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request

PROJ = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
PAGE = os.path.join(PROJ, "site", "index.html")
OWNER, REPO = "CorvinYu", "parcel-SPU"
API = "https://api.github.com/repos/%s/%s" % (OWNER, REPO)
LIVE = "https://k.corvinyu.icu/"
KEY = r"E:\claude\nas-pt-ops\sshkey_nas"
MAC = "corvinyu@192.168.9.7"
REMOTE_DIR = "/Users/corvinyu/server/data/caddy/data/k"
UA = {"User-Agent": "parcel-spu-site-sync"}
# 未认证额度只有 60 次/小时（本机实测会 403 rate limit exceeded）；有令牌就带上。
# 只发给 api.github.com —— 下载直链是 assets 域，不该把令牌带给它。
TOKEN = os.environ.get("GH_RELEASE_TOKEN", "").strip()


def http(url, retries=6, timeout=60, raw=False):
    last = None
    for attempt in range(1, retries + 1):
        try:
            headers = dict(UA)
            if TOKEN and url.startswith("https://api.github.com/"):
                headers["Authorization"] = "Bearer " + TOKEN
            req = urllib.request.Request(url, headers=headers)
            with urllib.request.urlopen(req, timeout=timeout) as r:
                data = r.read()
                return (data, dict(r.headers)) if raw else json.loads(data.decode("utf-8"))
        except urllib.error.HTTPError as e:
            if e.code in (401, 403, 404):
                raise RuntimeError("HTTP %s %s" % (e.code, url))
            last = "HTTP %s" % e.code
        except Exception as e:  # noqa: BLE001
            last = "%s: %s" % (type(e).__name__, e)
        time.sleep(1.5 * attempt)
    raise RuntimeError("重试 %d 次失败 %s：%s" % (retries, url, last))


def beijing_date(iso):
    """published_at 是 UTC（如 2026-09-28T19:01:48Z）——页面写的是北京时间，
    直接取 UTC 日期会差一天（0.1.7 就是这种：UTC 09-28、北京 09-29），所以 +8h 再取日期。"""
    if not iso:
        return ""
    m = re.match(r"(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})", iso)
    if not m:
        return iso[:10]
    import datetime
    y, mo, d, h, mi, s = (int(x) for x in m.groups())
    return (datetime.datetime(y, mo, d, h, mi, s) + datetime.timedelta(hours=8)).strftime("%Y-%m-%d")


def releases():
    """列出所有「带 APK 的非草稿」Release（新的在前）。"""
    out = []
    page = http("%s/releases?per_page=100" % API)
    for r in page:
        if r.get("draft"):
            continue
        asset = next((a for a in r.get("assets", []) if a["name"].endswith(".apk")), None)
        if not asset:
            continue
        out.append({
            "tag": r["tag_name"],
            "version": r["tag_name"].lstrip("v"),
            "name": r.get("name") or r["tag_name"],
            "date": beijing_date(r.get("published_at")),
            "apk": asset["name"],
            "size": asset["size"],
            "digest": (asset.get("digest") or "").replace("sha256:", ""),
            "url": asset["browser_download_url"],
            "prerelease": r.get("prerelease", False),
        })
    return out


def mb(n):
    return "%.1f MB" % (n / 1048576.0)


def remote_sha256(path):
    out = subprocess.run(
        ["ssh", "-i", KEY, "-o", "BatchMode=yes", MAC, "shasum -a 256 '%s'" % path],
        stdout=subprocess.PIPE, stderr=subprocess.DEVNULL).stdout.decode().split()
    return out[0] if out else ""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tag", help="目标 tag（默认取最新正式发布）")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--deploy", action="store_true")
    ap.add_argument("--verify-full", action="store_true", help="外网下载整包核对 SHA-256")
    args = ap.parse_args()

    rels = releases()
    if not rels:
        print("!! GitHub 上没有带 APK 的 Release"); return 2
    target = next((r for r in rels if r["tag"] == args.tag), None) if args.tag \
        else next((r for r in rels if not r["prerelease"]), rels[0])
    if not target:
        print("!! 找不到 tag %s" % args.tag); return 2

    html = io.open(PAGE, encoding="utf-8").read()
    m = re.search(r"parcel-spu-(v[0-9][^\"'\s]*?)\.apk", html)
    if not m:
        print("!! 页面里找不到 APK 文件名，无法判断当前版本"); return 2
    cur_tag = m.group(1)
    current = next((r for r in rels if r["tag"] == cur_tag), None)
    print("页面当前：%s   目标：%s（%s，%s，%s）" % (cur_tag, target["tag"], target["date"],
                                                mb(target["size"]), target["digest"][:12]))
    if current is None:
        print("!! 页面上的 %s 在 GitHub Releases 里找不到对应资源，先人工核对" % cur_tag); return 2
    if current["tag"] == target["tag"]:
        print("已是目标版本，无需改动。")
        if not args.deploy:
            return 0

    pairs = []
    if cur_tag != target["tag"]:
        pairs.append((cur_tag, target["tag"]))                      # v0.1.7 → v0.1.8
    if current["size"] != target["size"]:
        pairs.append(("%s 字节" % format(current["size"], ","), "%s 字节" % format(target["size"], ",")))
        if mb(current["size"]) != mb(target["size"]):
            pairs.append((mb(current["size"]), mb(target["size"])))
    if current["digest"] and current["digest"] != target["digest"]:
        pairs.append((current["digest"], target["digest"]))
    if current["date"] != target["date"]:
        pairs.append((current["date"], target["date"]))

    changed = False
    for old, new in pairs:
        n = html.count(old)
        if n == 0:
            print("  ⚠ 未命中（可能页面已改过）：%s" % old[:48])
            continue
        print("  ✓ %-46s → %-46s ×%d" % (old[:46], new[:46], n))
        html = html.replace(old, new)
        changed = True

    # 后置校验：新值必须出现，旧 SHA-256 必须消失
    if target["digest"] and target["digest"] not in html:
        print("!! 替换后页面里没有新 SHA-256，中止"); return 3
    if current["digest"] and current["digest"] != target["digest"] and current["digest"] in html:
        print("!! 旧 SHA-256 仍在，中止"); return 3
    if target["tag"] not in html:
        print("!! 替换后页面里没有新版本号，中止"); return 3

    if args.dry_run:
        print("（dry-run，未写文件）"); return 0
    if changed:
        io.open(PAGE, "w", encoding="utf-8", newline="").write(html)
        print("已写回 %s" % PAGE)

    if not args.deploy:
        print("下一步部署：python site/tools/sync_release.py --deploy")
        return 0

    # ---- 部署：APK + 页面 + 素材 ----
    local_apk = os.path.join(PROJ, "app", "build", "outputs", "apk", "release", target["apk"])
    if not os.path.exists(local_apk):
        print("本地没有 %s，从 GitHub 下载…" % target["apk"])
        data, _ = http(target["url"], raw=True, timeout=600)
        local_apk = os.path.join(PROJ, ".devtools", target["apk"])
        io.open(local_apk, "wb").write(data)
    local_hash = hashlib.sha256(io.open(local_apk, "rb").read()).hexdigest()
    print("本地 APK SHA-256 = %s" % local_hash)
    if target["digest"] and local_hash != target["digest"]:
        print("!! 本地 APK 与 GitHub 上的 digest 不一致，拒绝部署"); return 3

    print("上传 APK 到 Mac mini …")
    subprocess.run(["scp", "-i", KEY, "-o", "BatchMode=yes", local_apk,
                    "%s:%s/" % (MAC, REMOTE_DIR)], check=True)
    print("上传页面与素材 …")
    subprocess.run(["scp", "-i", KEY, "-o", "BatchMode=yes", PAGE,
                    "%s:%s/index.html" % (MAC, REMOTE_DIR)], check=True)
    assets = os.path.join(PROJ, "site", "assets")
    for f in sorted(os.listdir(assets)):
        subprocess.run(["scp", "-i", KEY, "-o", "BatchMode=yes", os.path.join(assets, f),
                        "%s:%s/assets/" % (MAC, REMOTE_DIR)], check=True)

    print("远端复核 …")
    rsha = remote_sha256("%s/%s" % (REMOTE_DIR, target["apk"]))
    print("  Mac 上 APK SHA-256 = %s  %s" % (rsha, "✓" if rsha == local_hash else "✗ 不一致!"))

    print("外网复核 …")
    req = urllib.request.Request(LIVE + target["apk"],
                                 headers={"User-Agent": "Mozilla/5.0", "Range": "bytes=0-1023"})
    with urllib.request.urlopen(req, timeout=40) as r:
        cr = r.headers.get("Content-Range", "")
        print("  %s → HTTP %s  Content-Range=%s" % (LIVE + target["apk"], r.status, cr))
        if "/%d" % target["size"] not in cr:
            print("  !! 外网大小与 Release 不符")
    # 首页也走重试：Cloudflare 偶尔直接断连接（RemoteDisconnected），不该让整个部署报失败
    body_bytes, _ = http(LIVE, raw=True, timeout=60)
    body = body_bytes.decode("utf-8", "replace")
    print("  首页含新版本号 %s；含新 SHA-256 %s；含新文件名 %s"
          % (target["tag"] in body, target["digest"][:12] in body, target["apk"] in body))
    if target["tag"] not in body or target["apk"] not in body:
        print("  !! 线上页面看起来还是旧版，稍后重试或检查 Caddy 根目录")

    if args.verify_full:
        print("下载整包核对 …")
        data, _ = http(LIVE + target["apk"], raw=True, timeout=900)
        h = hashlib.sha256(data).hexdigest()
        print("  外网整包 SHA-256 = %s  %s" % (h, "✓" if h == local_hash else "✗"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
