# 平面图与寻路备份（2026-09-29）

重做平面图/寻路的过程中多次改坏文件，这里存一份**可用的快照**，随时可回滚。

> 2026-09-29 晚更新：管线改为 **「Excel 填充色 → 通道网格 → 图上最短路」**（用户指出
> Excel 里所有通道都填了同一种颜色）。旧的手写「双干线 + 硬编码行号」版本已废弃，
> 构建器 `build-html-from-excel.py` 已删除，改由 `build-floorplan.py` 负责。

## 包含

| 文件 | 说明 |
|---|---|
| `floorplan.html` | **交付页**：底图 + 全部通道 + 交互寻路（单文件、零外部请求） |
| `floorplan-corridors.json` | 从 Excel 填充色导出的**可走通道网格**（含来源 sha256） |
| `floorplan-excel.png` | 底图 PNG（照抄你的 Excel：填充色/合并区/边框） |
| `用户精确版Excel.xlsx` | 你手工画的精确版（2 格 = 1 瓷砖） |
| `render-excel.py` | 渲底图：逐格照抄填充色 + 合并区 + 边框 + 出入口 |
| `extract-corridors.py` | 导出通道网格 JSON（theme3 = 通道 / theme1 = 墙 / theme9 = 货架分隔 / theme5 = 入口） |
| `route-core.js` | 寻路核心（网格 BFS 最短路 + Held–Karp 精确排序 + 画线 + 断言；浏览器/Node 共用） |
| `floorplan-template.html` | 页面模板（构建时注入 PNG / 通道 JSON / 寻路核心） |
| `build-floorplan.py` | 构建器（内联三者 → `docs/floorplan.html`，含自检） |
| `check-route.py` | 自检渲染：把路线画成 PNG 供肉眼核对，并**回读 Excel 填充色**独立复验 |

## 恢复

```powershell
# 页面与数据
Copy-Item docs\平面图-备份-2026-09-29\floorplan.html, docs\平面图-备份-2026-09-29\floorplan-corridors.json docs\ -Force
# 工具链（.devtools 是 gitignore 的本地目录）
Copy-Item docs\平面图-备份-2026-09-29\*.py, docs\平面图-备份-2026-09-29\route-core.js, docs\平面图-备份-2026-09-29\floorplan-template.html .devtools\ -Force
```

## 重建顺序（幂等）

```powershell
python .devtools\render-excel.py        # 1) 底图
python .devtools\extract-corridors.py   # 2) 通道网格 JSON
python .devtools\build-floorplan.py     # 3) 构建 docs\floorplan.html（含自检）
node   .devtools\test-route-core.js     # 4) 寻路核心验证（含与暴力枚举比对）
node   .devtools\test-floorplan-page.js # 5) 页面脚本端到端（DOM 桩）
python .devtools\check-route.py [样例.json]   # 6) 渲图供肉眼核对
```

## 坐标换算（所有脚本共用）

- 单元格 → 像素：`x = 190 + (列-1) × 13`，`y = 66 + (行-1) × 13`（1 格 = 0.5 瓷砖，13px）
- 单元格 → 瓷砖：`横向 lat = (列 - 37.5) / 2`（主通道中心 = 0，西负东正）；`深度 depth = (53.5 - 行) / 2`
- HTML 叠加层：`PX(lat) = 664.5 + 26×lat`，`PY(depth) = 748.5 - 26×depth`；格中心 `(183.5 + 13×列, 59.5 + 13×行)`

## 通道网格的**权威来源**

🔴 **通道的唯一权威 = 用户 Excel 的填充色 `theme3`**（3362 格）。不要手写通道行号/列号。
- `theme1` = 墙/边界（外圈、南墙 `C71:U71`、J 区围墙 `X3:AH3`/`X4:X11`/`AH5:AH11`、东外墙 `EB` 列…）
- `theme9` = 货架块分隔标记　`theme5` = 入口闸机（`W59:AB59`）
- 投影（取件点 → 通道格）**必须绕开** `theme1` 墙与货架合并区，否则会穿墙
  （J 柜列三面是墙、朝南开口，几何投影会错误地把 J 投进主通道）。
