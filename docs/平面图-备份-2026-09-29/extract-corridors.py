# -*- coding: utf-8 -*-
"""从用户精确版 Excel 的「填充色」导出完整可走通道网（机器可读 JSON）。

依据（2026-09-29 用户指出）：**Excel 里所有通道都用同一种填充色 theme3**。
  theme3 = 通道（走廊 + 纵向干线）
  theme1 = 边界/墙（外圈、南墙、西侧 A 区墙、东外墙 EB 列等）
  theme9 = 货架块分隔标记
  theme5 = 入口闸机（W59:AB59）
本脚本只读 Excel；输出 docs/floorplan-corridors.json + sha256 溯源。
"""
import hashlib, io, json, os
import openpyxl
from openpyxl.utils import get_column_letter

SRC = r'E:\claude\parcel-SMU\docs\海大快递站平面图-2026-09-29-用户精确版.xlsx'
OUT = r'E:\claude\parcel-SMU\docs\floorplan-corridors.json'

SPINE_COL, BASE_ROW = 37.5, 53.5
ROLE = {'theme3': 'walk', 'theme1': 'wall', 'theme9': 'shelfmark', 'theme5': 'gate', 'theme7': 'misc'}


def color_key(c):
    f = c.fill
    if f is None or f.fill_type is None:
        return None
    fg = f.fgColor
    if fg is None:
        return None
    if fg.type == 'rgb' and fg.rgb:
        return str(fg.rgb)
    if fg.type == 'theme':
        return 'theme%d' % fg.theme
    if fg.type == 'indexed':
        return 'indexed%d' % fg.indexed
    return None


def lat(c):
    return (c - SPINE_COL) / 2.0


def dep(r):
    return (BASE_ROW - r) / 2.0


wb = openpyxl.load_workbook(SRC)
ws = wb['Sheet1']

# ---- 1) 逐格取角色 ----
roles = {}          # (row, col) -> role
for row in ws.iter_rows(min_row=1, max_row=ws.max_row, min_col=1, max_col=ws.max_column):
    for c in row:
        k = color_key(c)
        if k in ROLE:
            roles[(c.row, c.column)] = ROLE[k]

# ---- 2) 每种角色按行做 RLE ----
def rle(role):
    out = []
    for r in range(1, ws.max_row + 1):
        cols = sorted(c for (rr, c), v in roles.items() if rr == r and v == role)
        if not cols:
            continue
        start = prev = cols[0]
        for c in cols[1:]:
            if c == prev + 1:
                prev = c
            else:
                out.append([r, start, prev])
                start = prev = c
        out.append([r, start, prev])
    return out


walk, wall, shelfmark, gate = rle('walk'), rle('wall'), rle('shelfmark'), rle('gate')

# ---- 3) 合并区（货架 / 区域标签）----
rects = []
for mr in sorted(ws.merged_cells.ranges, key=lambda x: (x.min_row, x.min_col)):
    v = ws.cell(row=mr.min_row, column=mr.min_col).value
    label = str(v).strip() if v is not None else ''
    rects.append(dict(label=label, c0=mr.min_col, c1=mr.max_col, r0=mr.min_row, r1=mr.max_row,
                      lat0=round(lat(mr.min_col - 0.5), 2), lat1=round(lat(mr.max_col + 0.5), 2),
                      d0=round(dep(mr.max_row + 0.5), 2), d1=round(dep(mr.min_row - 0.5), 2)))

# ---- 4) 通道统计（自检用）----
walk_cells = sum(b - a + 1 for (_, a, b) in walk)
min_col = min([c for (_, a, b) in walk for c in (a, b)] + [ws.max_column])
max_col = max([c for (_, a, b) in walk for c in (a, b)] + [1])
min_row = min(r for (r, _, _) in walk) if walk else 0
max_row = max(r for (r, _, _) in walk) if walk else 0

sha = hashlib.sha256(io.open(SRC, 'rb').read()).hexdigest()
data = dict(
    source=dict(path=os.path.basename(SRC), sha256=sha, bytes=os.path.getsize(SRC)),
    colorRoles=ROLE, spineCol=SPINE_COL, baseRow=BASE_ROW,
    span=dict(minCol=min_col, maxCol=max_col, minRow=min_row, maxRow=max_row,
              maxRowSheet=ws.max_row, maxColSheet=ws.max_column),
    walk=walk, wall=wall, shelfmark=shelfmark, gate=gate,
    rects=rects,
    stats=dict(walkCells=walk_cells, wallCells=sum(b - a + 1 for (_, a, b) in wall),
               shelfmarkCells=sum(b - a + 1 for (_, a, b) in shelfmark),
               gateCells=sum(b - a + 1 for (_, a, b) in gate)),
)
io.open(OUT, 'w', encoding='utf-8', newline='\n').write(json.dumps(data, ensure_ascii=False, indent=1))

print('JSON:', OUT, os.path.getsize(OUT), 'B')
print('source sha256:', sha[:16], '...')
print('可走格(theme3):', walk_cells, ' 墙格(theme1):', data['stats']['wallCells'],
      ' 货架标记(theme9):', data['stats']['shelfmarkCells'], ' 入口(theme5):', data['stats']['gateCells'])
print('列范围 %d~%d  行范围 %d~%d' % (min_col, max_col, min_row, max_row))
print('通道 RLE 行数:', len(walk))
# 纵向干线的列跨度（在同一列区间上连续出现的行跨度）
from collections import defaultdict
colrow = defaultdict(list)
for (r, a, b) in walk:
    for c in range(a, b + 1):
        colrow[c].append(r)
vert = sorted(((c, min(rs), max(rs)) for c, rs in colrow.items() if len(rs) >= 30))
print('纵向连续列（>=30 行）:', [(get_column_letter(c), r0, r1) for (c, r0, r1) in vert])
