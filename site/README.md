# k.corvinyu.icu 下载页（海大取件码）

本目录是 `https://k.corvinyu.icu/` 的静态站点源码。**零外部依赖、零构建步骤**：
`index.html` 单文件内联 CSS/JS，图片全部在 `assets/`。

```
site/
├─ index.html          页面本体（内联样式与脚本，无 CDN、无外部请求）
├─ assets/             图片素材（由 tools/build_assets.py 生成，勿手改）
│   ├─ icon.png / icon-512.png / apple-touch-icon.png / favicon.ico
│   ├─ hero-list.jpg   首屏手机截图（来自仓库 show1.jpg，900px 宽）
│   ├─ shot-1..9.jpg   实机截图画廊（来自 show1..9.jpg，720px 宽）
│   ├─ og.png          社交分享大图 1200×630
│   └─ haida-*.jpg     ← 海大版专属功能截图插槽，**目前不存在**
├─ tools/
│   ├─ build_assets.py 图标/截图/OG 图生成（Python + Pillow）
│   ├─ sync_release.py **发新版本后同步页面字段（从 GitHub Releases 自动取真实值）**
│   ├─ shoot.js        CDP 多视口截图 + 页面结构体检（Node 22+，零依赖）
│   └─ check_live.js   线上自检：同源资源状态码 / 外部域名 / APK HEAD（Node 18+，零依赖）
└─ .shots/             本地验证产物（截图与 report.json），已 gitignore
```

## 🔄 发新版本后：一条命令同步页面字段

页面里散着 6 类**版本相关字段**（文件名、版本号、体积、字节数、SHA-256、日期），手改必漏
（尤其 SHA-256 有两处：正文 + 复制按钮的 `data-copy`）。用这个脚本从 GitHub Releases 取真实值：

```powershell
python site/tools/sync_release.py --dry-run    # 先看会改什么
python site/tools/sync_release.py              # 改本地 site/index.html
python site/tools/sync_release.py --deploy     # 改完 + 传 APK/页面/素材到 Mac + 外网复核
python site/tools/sync_release.py --deploy --verify-full   # 再把外网整包下载下来核对 SHA-256
```

它的规则（都是"以页面当前写的版本为基准做替换"，所以可重复运行、幂等）：

| 改什么 | 依据 |
|---|---|
| `v<旧>` → `v<新>`（含 APK 文件名与 GitHub 下载链接） | 最新正式 Release 的 tag |
| `<旧字节数> 字节` / `<旧体积> MB` | Release 里 asset 的 `size` |
| 旧 SHA-256 → 新 SHA-256（含 `data-copy`） | asset 的 `digest` |
| 旧日期 → 新日期（**按北京时间换算**，UTC 取日期会差一天） | `published_at` |

安全约束：

- **只认已发布的 Release**（自动跳过 draft / prerelease）—— 与下面的维护约定一致
- 替换前后各校验一次：新值必须出现、旧 SHA-256 必须消失，否则**报错中止**（不做半拉子替换）
- 部署前核对「本地 APK 的 SHA-256 == Release 的 digest」，不一致**拒绝部署**
- 文案（功能说明、FAQ）**不会**被自动改 —— 功能有变化时仍需人工改，见下

## 线上自检（最快的一道）

```powershell
node site/tools/check_live.js https://k.corvinyu.icu/
```

会逐个请求页面上所有同源资源（报告状态码 / Content-Type / 字节数）、列出页面引用的
外部域名（确认没有第三方 CDN 与统计）、并用 HEAD 校验 APK 链接。
4 个 `haida-*.jpg` 插槽 404 属预期，不计为异常。

> ⚠️ **本机（笔记本）访问 Cloudflare 不稳定**：`fetch`/`curl` 常 `connect timeout` 或
> 半途 `SSL: UNEXPECTED_EOF_WHILE_READING`（代理与直连都会撞）。Python `urllib` **直连**
> 有时能通（`sync_release.py` 的复核就带重试），但仍会间歇失败。
> ⇒ 需要**可靠**的公网检查时，**在 Mac mini 上跑**：
> `ssh -i E:\claude\nas-pt-ops\sshkey_nas corvinyu@192.168.9.7 "curl -sSI https://k.corvinyu.icu/parcel-spu-v0.1.8.apk"`
> （Mac 侧实测 `HTTP/2 206` + `content-range` 正常。）

## 线上部署链路

```
浏览器 → Cloudflare → CF Tunnel(macmini-tunnel) → Mac mini:80/
         → Caddy 容器（Caddyfile 里 k.corvinyu.icu 的 root * /data/k）
         → 宿主 /Users/corvinyu/server/data/caddy/data/k/
```

要点：Caddy 容器把宿主 `~/server/data/caddy/data` 挂在容器 `/data`，所以
**站点根目录 = `/Users/corvinyu/server/data/caddy/data/k/`**，
而 Caddyfile 里写的是容器内路径 `/data/k`。（该挂载在 `stacks/infra/compose.yml` 里，
不用改 Caddy 配置，**也不需要重建容器**。）

登录：`ssh -i E:\claude\nas-pt-ops\sshkey_nas corvinyu@192.168.9.7`
（别用 `corvinyuu`，那个账号没有 sudo。）

## 部署 / 更新页面

```powershell
$k   = 'E:\claude\nas-pt-ops\sshkey_nas'
$mac = 'corvinyu@192.168.9.7'
$dst = '/Users/corvinyu/server/data/caddy/data/k'

ssh -i $k $mac "mkdir -p $dst/assets"
scp -i $k -q E:\claude\parcel-SPU\site\index.html        "${mac}:$dst/index.html"
scp -i $k -q E:\claude\parcel-SPU\site\assets\*          "${mac}:$dst/assets/"
ssh -i $k $mac "ls -la $dst; ls $dst/assets | wc -l"
```

> 传完即生效（Caddy 直接读文件，无缓存层需要刷新）。
> `assets/` 用**逐文件覆盖**而不是 `scp -r assets`，避免二次部署时被套成 `assets/assets`。

## 回滚

删掉 `index.html` 就退回原始的裸文件下载列表（APK 不受影响）：

```powershell
ssh -i $k $mac "rm -f $dst/index.html"
```

## 发布新版本 APK 时要同步改的地方（都在 `index.html` 内）

**机械字段**（下面前 6 行）现在由 `tools/sync_release.py` 自动改，**不要手改**；
**文案**（最后两行）功能有变化时才需要人工改。

| 位置 | 内容 | 谁改 |
|---|---|---|
| `<title>` / `<meta name="description">` | 版本号、体积 | 🤖 工具 |
| `.hero`、`#download`、`.mbar` 里的下载链接（3 处 `<a href>`） | `./parcel-spu-v<版本>.apk` | 🤖 工具 |
| GitHub 镜像链接（2 处） | `releases/download/v<版本>/parcel-spu-v<版本>.apk` | 🤖 工具 |
| hero chips | `Android 10+`、体积、更新日期 | 🤖 工具 |
| `#install` 的 `.kv` 区 | 版本、字节数、日期、**SHA-256**（含 `data-copy` 属性） | 🤖 工具 |
| 安装步骤里的文件名、校验命令 | `parcel-spu-v<版本>.apk` | 🤖 工具 |
| 功能卡 / FAQ 的**功能描述** | 例如某形态被删掉、某能力新增 | ✍️ 人工 |
| FAQ「安装时提示应用未安装」 | 签名变更说明是否仍然适用 | ✍️ 人工 |

例：0.1.8 这次人工改的内容 —— 应用更名（取件码海大版 → 海大取件码）、
条码出示形态由四种改两种（全屏出示与铺满背景已删）、路线适用范围改成"带格口 + 实体货格号都认"、
补上"首页 ①②③ 排序"与"条码试验"页、签名 FAQ 改为"装最新版"。

APK 本身由工具负责放进站点根目录（`$dst/parcel-spu-v<版本>.apk`），旧包按需保留。

## 海大版三大卖点的截图插槽

页面「海大版专属能力」四张卡各有一个 `<figure class="slot" data-slot="…">`。
**把文件按下面的名字丢进 `assets/` 即可自动替换示意图形**，不用改 HTML：

| 文件名 | 对应卡片 | 建议 |
|---|---|---|
| `haida-barcode.jpg` | 快递中心条码 · 常驻出示 | 竖版手机截图，≥720×1560，顶部常驻条 / 全屏出示形态最好各来一张 |
| `haida-route.jpg` | 取件路线 · 精确最优 | 竖版，最好拍到带 ①②③ 序号的路线结果 |
| `haida-category.jpg` | 列表三分类 · 横向跟手翻页 | 竖版，拍到「快递柜」页与左侧大号柜号 |
| `haida-background.jpg` | 自定义页面背景 | 竖版，能体现渐变/自定义图效果 |

机制：`<img>` 上有 `src`，加载成功就移除 `figure` 上的 `empty` 类（CSS 因此隐藏示意 SVG
与「实机截图待补拍」角标）；文件不存在时保持示意图，页面不报错。
**现有 9 张截图都来自通用数据场景，没有任何一张展示这三个海大版功能**，所以插槽是空的。

## 本地验证（改完页面务必跑一遍）

`tools/shoot.js` 用 CDP 在真实 Chrome 上按移动端（390×844 @2x）与桌面端（1440×900）
两种视口截图，并输出结构体检（视口是否被撑大、横向溢出、破图、下载链接、控制台错误）。

```powershell
# 本机 Chrome（本机沙箱下 Chrome 起不来，见下）
node site/tools/shoot.js site/.shots http://127.0.0.1:8791/
```

⚠️ **本机（Windows 性能笔记本）沙箱下 Chrome 无法启动**：Chrome 的 Mojo 需要命名管道，
报 `platform_channel.cc: Check failed: 拒绝访问 (0x5)`。绕过办法是**让 Chrome 跑在
Mac mini 上，CDP 端口经 SSH 隧道转发回来**：

```powershell
# 1) Mac 上起 headless Chrome（调试端口只绑 127.0.0.1）
ssh -i $k $mac "nohup '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome' \
  --headless --remote-debugging-port=9333 --user-data-dir=/tmp/chrome-shot \
  --no-first-run --disable-gpu about:blank > /tmp/chrome-shot.log 2>&1 </dev/null &"

# 2) 开隧道（后台常驻）
ssh -i $k -N -L 9333:127.0.0.1:9333 $mac

# 3) 本机 Node 驱动远端 Chrome
$env:CDP_PORT='9333'; $env:SKIP_CHROME='1'
node site/tools/shoot.js site/.shots https://k.corvinyu.icu/
```

产物：`mobile.png` / `desktop.png` / `desktop-light.png`（整页）、
`<视口>-v1..3.png`（滚动位置）、`<视口>-<分区>.png`（定点）、`report.json`。

用 `file://` 也能测（改完先本地跑再上线）：

```powershell
ssh -i $k $mac "rm -rf /tmp/pspu-site && mkdir -p /tmp/pspu-site"
scp -i $k -q -r site/index.html site/assets site/tools "${mac}:/tmp/pspu-site/"
node site/tools/shoot.js site/.shots "file:///tmp/pspu-site/index.html"
```

## 素材重新生成

```powershell
python site/tools/build_assets.py     # 需要 Pillow；图标取自 app/src/main/res/mipmap-xxxhdpi/
```

## 维护约定

- 页面必须保留 **「非官方分支 · fork 自 shareven/parcel」** 声明（见项目 `CLAUDE.md`）。
- 只写**已发布版本**的信息：未发 GitHub Release 的本地构建（如 `0.1.8-betaN`）不得出现在下载页上。
- 不加外部 CDN / 统计脚本 —— 目前整页零第三方请求。
