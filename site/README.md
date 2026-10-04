# k.corvinyu.icu 下载页（海大取件码）

本目录是 `https://k.corvinyu.icu/` 的静态站点源码。**零外部依赖、零构建步骤**：
`index.html` 单文件内联 CSS/JS；界面图形也全部内联（HTML/CSS/SVG），
`assets/` 里只有图标、OG 图，以及首屏动态地图要用的场地模型与寻路核心。

```
site/
├─ index.html          页面本体（内联样式与脚本，无 CDN、无外部请求）
│                      **界面图形全部是页面里的 HTML/CSS/内联 SVG 元素**（见「界面元素一览」一节），
│                      不引用任何截图文件
├─ assets/             前端资源（**生成的，勿手改**；见下）
│   ├─ icon.png / apple-touch-icon.png / favicon-64.png / favicon.ico   ← build_assets.py
│   ├─ og.png          社交分享大图 1200×630                            ← build_assets.py
│   ├─ venue-model.js  场地模型（通道网格 / 货架合并区 / 闸机）           ← build-hero-map.py
│   ├─ venue-mini.svg  静态平面示意图（能力卡用）                        ← build-hero-map.py
│   ├─ route-core.js   寻路核心：建模 / 定位 / BFS 最短路 / Held–Karp    ← 复制自 .devtools/
│   └─ hero-map.js     首屏动态地图（画法 + 动画 + 交互，手写）
├─ tools/
│   ├─ build_assets.py 图标 + OG 图生成（Python + Pillow）；`--check` 校验 assets 与页面引用一致
│   ├─ sync_release.py **发新版本后同步页面字段（从 GitHub Releases 自动取真实值）**
│   ├─ shoot.js        CDP 多视口截图 + 页面结构体检（Node 22+，零依赖）
│   └─ check_live.js   线上自检：同源资源状态码 / 外部域名 / APK HEAD（Node 18+，零依赖）
└─ .shots/             本地验证产物（截图与 report.json），已 gitignore
```

## 🗺 首屏动态地图（0.2.0 起的地图卖点）

首屏右侧是一张**可交互的取件路线图**：每趟**随机 4~7 个真实货位** → 在真实通道网格上求最短路
→ 相机跟着当前段飞、光点沿路线走，**跑完一趟自动换下一批**（不点也能一直看）。

**画法照着 `.devtools/map-prototype-template.html`**（那份「A 清爽浅色 / B 夜跑深色」的路线图风格原型）：
柔和竖向渐变底 + 点阵底纹 + 虚线场地外框 + 货架圆角块（浅色带投影）+ 路线**三段着色**
（已走灰蓝 / 这一段高亮 + 光晕 / 待走浅蓝）+ **聚焦聚光灯**（当前段之外压暗，浅色主题用冷灰蓝阴影色，
不用近白蒙版）+ 站点圆点与呼吸光晕 + **当前段流动虚线**（流动感代替箭头）+
相机飞行（**对数插值缩放 + easeInOutQuint**，近目标不重启动画）。
调色板就是原型的两套，页面切主题时整张图跟着换（`--m-*` 变量，深色 = B 方案）。

**与 App 同步的两处要点**：

- 闸机带**不可通行**：寻路用 `modelRoute`（`gateSpans: []` 建出来的模型），停靠点取闸机**带外门口的通道格**
  （`mouthsOf()`）。验证脚本会断言「路线 0 格落在闸机带内」。
- 当前段**最后绘制**（否则后面的灰色 / 浅蓝段会盖住高亮段）。
- 随机池按**配额**挑：普通排 189 个货架 vs 顺丰 3 个、大件 1 个，纯随机几乎抽不到 S/Y ⇒
  每批**保底 1 个特色区**（顺丰 / J 柜列 / 大件），再按概率补。验证脚本会抽样 12 批统计出现率。

数据与寻路**不重写**，只调两个权威件：

| 文件 | 作用 | 来源 |
|---|---|---|
| `assets/venue-model.js` | 3362 个可走格 / 218 个货架合并区 / 3 处闸机带 | `docs/floorplan-corridors.json`（用户 Excel 填充色导出） |
| `assets/route-core.js` | 建模 · 绕墙投影 · BFS 最短路 · Held–Karp 精确排序 · 路线断言 | `.devtools/route-core.js`（与 App 端 `SiteModel` + `PickupRoute` 同源） |
| `assets/hero-map.js` | 画法（照原型搬）+ 动画 + 相机 + 交互 | 手写（本文件同目录） |

再生成 / 校验：

```powershell
python .devtools/build-hero-map.py            # 同步上面两个权威件 + 重画 venue-mini.svg
python .devtools/build-hero-map.py --check    # 校验站点里的副本与源文件是否一致（JSON 比内容、JS 比 sha）
node   .devtools/verify-hero-map.js           # 真机 Chrome（CDP）：点数 4~7 / 去重 / 断言 / 动画 / 换一批 /
                                              #   点站跳转 / 视角切换 / 跑完自动换批 / 主题重画 / 截图
node   .devtools/calc-sample-route.js D5-23 B4-3 S3-2-2628   # 用真实模型算样例路线的件数/全程/每段（页面卡里的数字来自这里）
python .devtools/check-live.py                # 上线后外网复核（本机直连；失败时用下面的 Mac 脚本）
sh     .devtools/check-live-mac.sh            # 在 Mac 上跑（必须经 Clash 代理，那台机器是 fake-IP）
```

交互：**全览·特写**（也可双击图切换）· **换一批** · **暂停/播放** · 点列表任一站或图上编号点跳过去 ·
鼠标拖动平移 · 滚出视口自动暂停。**滚轮不劫持**（页面正常滚动），触摸拖动也不抢页面手势。
节奏：每批开头在**全览**停留 1.8s（先看清整条路线），再进特写跟随 + 聚光灯；走完停 1.6s 自动换下一批。

**地图上不再画取件号小签**（用户两次反馈：小屏糊、位置怪 ⇒ 已整块删除，只保留 ①②③ 编号点）。
取件码只在下方列表里出现。

**手机端（≤720px）首屏顺序**：大标题 → 副标题 → **完整地图 hero** → 说明 → 下载按钮 →（**非官方分支胶囊挪到最末**）。
做法是给左栏 `display:contents`，让它的子元素变成网格项，再用 `order` 与地图交错排序
（`.hero h1{order:1}` / `.stage{order:3}` / `.badge{order:9}`）。
地图头部压缩：隐藏面板标题行与图例、三个按钮铺满一行、`aspect-ratio:1.72`，
保证 390×844 打开时「标题 + 整块地图面板」都在首屏内（实测面板底边 657px < 可用 758px）。
下方停靠列表**只露约 3 行且不可手指滑动**（`max-height:96px; overflow:hidden; touch-action:pan-y`，
随当前站自动滚到可见位置）· 首屏关键词胶囊隐藏 · 说明文字压到 2 行。
自动换批时特色区**轮转保底**（顺丰 → J 柜列 → 大件），否则普通排 189 个货架会把 S/Y 淹掉。

**改了 JS / 场地数据要 bump 资源版本号**：`python .devtools/add-asset-version.py <版本>` —
它会给 `index.html` 里的 `hero-map.js?v=` / `venue-mini.svg?v=` 和 `hero-map.js` 内部动态加载的
`route-core.js?v=` / `venue-model.js?v=` 统一打上版本号，避免浏览器拿缓存跑旧代码（**踩过**：
第一次上线后线上验证一直失败，就是 Chrome 缓存了旧的 `hero-map.js`）。

> ⚠️ 四个坑（都踩过）：
> 1. 场地数据**必须**用 `<script src>` 加载（`venue-model.js`）而不是 `fetch('*.json')` ——
>    后者在 `file://` 下会被 Chrome 拦成 `Failed to fetch`，本地双击打开就看不到地图。
> 2. `RouteCore.solve()` 的 `entranceCell` 要 **`{row, col}` 对象**；传 `[row, col]` 数组会让起点失效、
>    直接抛「无可行顺序」。
> 3. 同一排货架的不同货位会投到**同一个通道格**（普通排不区分货架内位置）⇒ 每趟每排只取一件，
>    否则图上两个编号会重叠成一个。
> 4. `hero-map.js` 里「每格像素」的函数与状态对象**不能同名**（第一版状态对象叫 `S`，把 `S()` 覆盖成
>    非函数，整张图直接白屏 + 报 `S is not a function`）——状态对象现叫 `ST`。
> 另外相机的 scale 夹在「全览 ~ 全览×2.4」之间：不夹的话，很短的一段会放大到只剩两排货架。
> 5. **聚光灯在浅色主题不能用近白蒙版**（会糊成一片白雾），要用冷灰蓝的阴影色；深色主题也别压太狠。
> 6. **闸机带不可走要先建第二个模型**：`buildModel({gateSpans: []})`；门口格用原模型（带闸机）取带内格再找四邻的可走格。

> **0.2.0 起不再有「界面截图」**：页面上的标签页、取件码行、①②③ 序号、路线步骤、
> 条码、开关、滑块、渐变预设、桌面小组件，都是**直接画在页面里的扁平化元素**
> （HTML + CSS + 内联 SVG），配色与中文文案对着 App 源码写。
> 好处：任何分辨率都清晰、跟着页面深浅主题变色、体积极小，
> 而且不再需要把真机截图（含真实取件码与地址）放上网。

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
0.2.0 起 `assets/` 只剩图标与 OG 图，**没有「预期 404」的插槽**了 —— 任何 404 都是真问题。

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

## 界面元素（0.2.0 起全部内联，没有图片插槽）

页面「海大版专属能力」四张卡与「界面元素一览」一节的图形，都是**页面里真实的 HTML/CSS/SVG 元素**，
（原来的 `<figure class="slot">` 图片插槽已删除）。要改一处元素，就改 `index.html` 里对应的那一小块：

| 想改什么 | 在 index.html 里找 |
|---|---|
| 元素图的通用样式与语义色 | 样式区「界面元素图」一段（`.uifig` / `.uitab` / `.uipill` / `.uistep` / `.uibc` / `.uiswitch` / `.uislider` …，深/浅两套 `--el-*` 变量） |
| 首屏元素拼贴 | `<div class="ui-board">`（四块 `.uifig`：分类+①②③ / 步骤 / 条码 / 柜号+渐变） |
| 能力卡里的元素图 | 四张 `.card` 内的 `<figure class="uifig tight">` |
| 元素一览的 9 个格子 | `#shots` 一节里的 `<figure class="tile">` |
| 条码条纹 | `<symbol id="bc128">`（真实 Code128，由 `.devtools/gen-barcode.java svg <payload>` 生成后粘进来；**不要手写条纹**） |

约定：

- 语义色取自 App 源码的浅/深两套（顶栏标志、步骤卡、提示条、渐变预设），跟着页面主题切换；
- 文案逐字对着源码（`HomeTopBar.kt` / `ParcelList.kt` / `AddressCard.kt` / `AppBackgroundScreen.kt` …）；
- 取件码口径：`formatPickupCode()` 会把 ≥8 位纯数字按 4 位分组（`54018314` → `5401 8314`）；
  `preferLockerAddress` 默认 true ⇒ 快递柜卡片右侧只写「N格口」；
- 元素里的数据（取件码 / 地址 / 时间）是**示例**，不要写真实用户的取件码。

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
python site/tools/build_assets.py            # 图标 + OG 图（需要 Pillow；图标取自 app/src/main/res/mipmap-xxxhdpi/）
python site/tools/build_assets.py --check    # 只校验：assets/ 与 index.html 的引用是否一一对应
```

界面元素不需要「重新生成」——它们就在 `index.html` 里（见上一节）。
唯一需要用工具生成的是**条码条纹**：

```powershell
# 用项目自带的 ZXing（与 App 同一个库）把 Code128 打成 SVG path，再粘进 <symbol id="bc128">
& "$env:JAVA_HOME\bin\java.exe" -cp <zxing-core-3.5.3.jar> .devtools\gen-barcode.java svg <payload>
```

## 维护约定

- 页面必须保留 **「非官方分支 · fork 自 shareven/parcel」** 声明（见项目 `CLAUDE.md`）。
- 只写**已发布版本**的信息：未发 GitHub Release 的本地构建（如 `0.1.8-betaN`）不得出现在下载页上。
- 不加外部 CDN / 统计脚本 —— 目前整页零第三方请求。
