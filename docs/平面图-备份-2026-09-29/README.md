# 平面图备份（2026-09-29）

我在重做平面图的过程中多次改坏文件，这里存一份**可用的快照**，随时可回滚。

## 包含

| 文件 | 说明 |
|---|---|
| `floorplan.html` | 交互平面图（内嵌验过的 PNG + 叠加路线/序号） |
| `floorplan-excel.png` | 底图 PNG（照抄你的 Excel 渲染） |
| `用户精确版Excel.xlsx` | 你手工画的精确版（2 格 = 1 瓷砖），已补全东侧 78 个编号 |
| `render-excel.py` | 渲染器（合并区 + 边框 + 出入口 → PNG） |
| `floorplan-template.html` | HTML 模板（含寻路与画线逻辑） |
| `build-html-from-excel.py` | 构建器（把 PNG 内嵌进模板） |

## 恢复

```powershell
Copy-Item docs\平面图-备份-2026-09-29\* docs\ -Force
Copy-Item docs\平面图-备份-2026-09-29\render-excel.py .devtools\ -Force
Copy-Item docs\平面图-备份-2026-09-29\floorplan-template.html .devtools\ -Force
Copy-Item docs\平面图-备份-2026-09-29\build-html-from-excel.py .devtools\ -Force
```

## 坐标换算（所有脚本共用）

- 单元格 → 像素：`x = 190 + (列-1) × 13`，`y = 66 + (行-1) × 13`（1 格 = 0.5 瓷砖，13px）
- 单元格 → 瓷砖：`横向 lat = (列 - 37.5) / 2`（主通道中心 = 0，西负东正）；`深度 depth = (53.5 - 行) / 2`
- HTML 叠加层：`PX(lat) = 664.5 + 26×lat`，`PY(depth) = 748.5 - 26×depth`
