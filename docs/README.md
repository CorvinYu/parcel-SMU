# docs/ — 平面图与场地数据

本目录是**海大快递站平面图**相关的权威数据与交付物。2026-10-04 做过一次目录整理，
历史过程稿统一收进 `_archive/`。

## 现行文件（权威 / 在用）

| 文件 | 作用 | 谁在用 |
|---|---|---|
| `海大快递站平面图-2026-10-03-S区补齐_Y区编号_Y1横排.xlsx` | **当前权威布局**（S 区补齐 / Y 区编号 / Y1 横排） | `.devtools/render-excel.py`、`extract-corridors.py` |
| `海大快递站平面图-2026-09-29-用户精确版.xlsx` | 上一轮权威精确版（2 格 = 1 瓷砖） | 备份 / 溯源 |
| `海大快递站平面布局与货位清单.xlsx` | 货位清单（S/J/Y 编号方向与锚点） | `gen-recon-xlsx.py` 产物、定位依据 |
| `原始踩点图-2026-09-29.xlsx` | 现场手绘原图（最早一版） | 溯源 |
| `floorplan-corridors.json` | **从 Excel 填充色导出的可走通道网格**（含 Excel sha256 溯源） | `.devtools/gen-site-data-kt.py`、`build-floorplan.py`、App 侧 |
| `floorplan.html` | 交付的交互平面图（内嵌验过的 PNG + 全部通道 + 交互寻路） | 交付物 |
| `route-map-prototype.html` | 取件路线风格原型（**仍在用**，见 `ROADMAP.md`） | 风格微调对比 |
| `平面图-备份-2026-09-29/` | 该轮 Excel 与配套脚本备份（11 份，含 `README.md` 说明恢复顺序） | 回溯 |
| `平面图-备份-2026-10-02/` | 该轮 Excel 备份（含 `README.md`） | 回溯 |

> 🔴 **通道的唯一权威 = 用户 Excel 的填充色 `theme3`**，不要手写通道行号/列号。
> 详见 `CLAUDE.md` 的「平面图 / 寻路铁律」。

## `_archive/` — 历史归档（不再引用，仅供查阅）

| 子目录 | 内容 | 归档原因 |
|---|---|---|
| `_archive/upstream-screenshots/` | `show1..9.jpg` — **上游 shareven/parcel 的 UI 截图** | 上游 README 用它们展示界面；海大版 README 不引用，且界面已大改（旧版紫渐变、无海大功能）。从仓库根目录移入，保留作历史对照 |
| `_archive/releases/` | `release-v1.0.57-haida.1.md`、`parcel-spu-v1.0.57-haida.1.apk.sha256` | 旧版发布残留（该版签名密钥已丢失）。从仓库根目录移入 |
| `_archive/prototypes/` | `floorplan-v5.html`、`floorplan-grid.html`、`route-sim.html`、`route-sim-v2.html` | 平面图/路线图早期迭代过程稿，**零引用** |
| `_archive/pre-switch-2026-10-03/` | 换图前的 `floorplan.html` / `floorplan-corridors.json` / `SiteData.kt` + `SHA256SUMS.txt` | 2026-10-03 换精确图前的一致性快照 |

## 相关工具（都在 `.devtools/`，已 gitignore）

| 脚本 | 作用 |
|---|---|
| `render-excel.py` | 照抄 Excel（填充色 + 合并区 + 边框 + 出入口）渲成 PNG |
| `extract-corridors.py` | 填充色 → 通道网格 JSON（`theme3` 通道 / `theme1` 墙 / `theme9` 货架分隔 / `theme5` 入口） |
| `gen-site-data-kt.py` | `floorplan-corridors.json` → 生成 `app/.../util/SiteData.kt`（**自动生成，勿手改**） |
| `build-floorplan.py` + `floorplan-template.html` | 模板 + 构建器 → `floorplan.html`（含自检） |
| `check-route.py` | 把路线渲成 PNG 供肉眼核对，并回读 Excel 填充色独立复判 |
| `route-core.js` / `test-route-core.js` / `test-floorplan-page.js` | 寻路核心 + 验证 |
