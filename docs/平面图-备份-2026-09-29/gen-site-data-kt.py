# -*- coding: utf-8 -*-
"""把 docs/floorplan-corridors.json 生成为 Kotlin 常量 `app/.../util/SiteData.kt`。

为什么生成代码而不是运行时读 JSON：
  - App 内不需要额外 JSON 依赖（org.json 在 JVM 单测里是抛异常的桩）
  - 单测可以直接用真实场地数据跑，和浏览器端用同一份数据源
生成物**入库**；源数据仍是 `docs/floorplan-corridors.json`（可追溯 Excel sha256）。
"""
import io, json, os

ROOT = r'E:\claude\parcel-SMU'
SRC = os.path.join(ROOT, r'docs\floorplan-corridors.json')
OUT = os.path.join(ROOT, r'app\src\main\java\com\xxxx\parcel\util\SiteData.kt')

d = json.load(io.open(SRC, encoding='utf-8'))

# 闸机带 = 标签里含「闸机/出口」的合并区
gates = [r for r in d['rects'] if r['label'] and ('闸机' in r['label'] or '出口' in r['label'])]
gate_spans = []
for r in gates:
    gate_spans += [r['c0'], r['c1'], r['r0'], r['r1']]

# 入口闸机：用户用 theme5 单独填的格子（**不是合并区**）—— RLE 是 [行, 起始列, 结束列]
entrance_spans = [v for seg in d['gate'] for v in seg]

min_col = min([d['span']['minCol']] + [r['c0'] for r in d['rects']] + [r['c0'] for r in gates])
max_col = max([d['span']['maxCol']] + [r['c1'] for r in d['rects']] + [r['c1'] for r in gates])
min_row = min([d['span']['minRow']] + [r['r0'] for r in d['rects']] + [r['r0'] for r in gates])
max_row = max([d['span']['maxRow']] + [r['r1'] for r in d['rects']] + [r['r1'] for r in gates])


def flat(segments):
    out = []
    for s in segments:
        out += list(s)
    return out


def emit_ints(name, values, per_line=16, indent='        '):
    lines = ['    val %s: IntArray = intArrayOf(' % name]
    for i in range(0, len(values), per_line):
        chunk = ', '.join(str(v) for v in values[i:i + per_line])
        lines.append(indent + chunk + ',')
    lines.append('    )')
    return '\n'.join(lines)


walk = flat(d['walk'])
wall = flat(d['wall'])
rects = d['rects']
rect_labels = [r['label'] for r in rects]
rect_bounds = []
for r in rects:
    rect_bounds += [r['c0'], r['c1'], r['r0'], r['r1']]

label_lines = []
for i in range(0, len(rect_labels), 6):
    chunk = ', '.join('"%s"' % s.replace('"', '') for s in rect_labels[i:i + 6])
    label_lines.append('        ' + chunk + ',')

src = d['source']
body = '''package com.xxxx.parcel.util

/**
 * 场地数据 —— **由用户 Excel 的填充色自动生成，请勿手改**。
 *
 * 来源：`docs/海大快递站平面图-2026-09-29-用户精确版.xlsx`
 *   sha256 = %(sha)s（%(bytes)d 字节）
 * 生成脚本：`.devtools/gen-site-data-kt.py`（备份见 `docs/平面图-备份-2026-09-29/`）
 *
 * 语义（用户在 Excel 里用颜色区分，代码照抄）：
 *   `walk`  = 通道（走廊 + 3 条纵向干线）**%(walk_cells)d 格**
 *   `wall`  = 墙 / 边界（投影必须绕开）**%(wall_cells)d 格**
 *   `rects` = 所有合并区（货架 / 区域 / 闸机带）：定位取件点、且作为投影障碍
 *   `gateSpans` = 闸机带（可通行，只用于出行）
 *
 * 坐标：1 单元格 = 半块瓷砖；`lat = (列-37.5)/2`（主通道中心 = 0），`depth = (53.5-行)/2`。
 */
internal object SiteData {

    const val SPINE_COL = 37.5
    const val BASE_ROW = 53.5

    const val MIN_COL = %(minc)d
    const val MAX_COL = %(maxc)d
    const val MIN_ROW = %(minr)d
    const val MAX_ROW = %(maxr)d

    /** 通道 RLE：每 3 个数为 `行, 起始列, 结束列` */
%(walk)s

    /** 墙 RLE：每 3 个数为 `行, 起始列, 结束列` */
%(wall)s

    /** 闸机带：每 4 个数为 `起始列, 结束列, 起始行, 结束行` */
%(gates)s

    /** 入口闸机的格子 RLE（用户用单独颜色填的，**不是合并区**）：每 3 个数为 `行, 起始列, 结束列` */
%(entrance)s

    /** 合并区标签（与 [rectBounds] 一一对应，同一标签可能出现多次） */
    val rectLabels: Array<String> = arrayOf(
%(labels)s
    )

    /** 合并区边界：每 4 个数为 `起始列, 结束列, 起始行, 结束行` */
%(bounds)s
}
''' % dict(
    sha=src['sha256'], bytes=src['bytes'],
    walk_cells=d['stats']['walkCells'], wall_cells=d['stats']['wallCells'],
    minc=min_col, maxc=max_col, minr=min_row, maxr=max_row,
    walk=emit_ints('walk', walk),
    wall=emit_ints('wall', wall),
    gates=emit_ints('gateSpans', gate_spans),
    entrance=emit_ints('entranceSpans', entrance_spans),
    labels='\n'.join(label_lines),
    bounds=emit_ints('rectBounds', rect_bounds),
)

io.open(OUT, 'w', encoding='utf-8', newline='\n').write(body)
print('生成:', OUT, os.path.getsize(OUT), 'B')
print('  walk %d 段 / %d 值    wall %d 段 / %d 值    rect %d 个（有标签 %d）    gate %d 处'
      % (len(d['walk']), len(walk), len(d['wall']), len(wall), len(rects),
         sum(1 for s in rect_labels if s), len(gates)))
print('  网格范围: 列 %d~%d  行 %d~%d' % (min_col, max_col, min_row, max_row))
