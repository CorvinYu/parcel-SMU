# -*- coding: utf-8 -*-
"""直接按用户 Excel 逐格渲染（合并区=货架/区域，边框=墙），再叠我的通道底图。
1 单元格 = 半块瓷砖；1 瓷砖 = 26px。
"""
import base64, io, os, json
import openpyxl
from openpyxl.utils import range_boundaries, get_column_letter
from PIL import Image, ImageDraw, ImageFont

SRC = r'E:\claude\parcel-SMU\docs\海大快递站平面图-2026-09-29-用户精确版.xlsx'
P = 26                      # 每瓷砖像素
CELL = P / 2                # 每单元格像素
L_LANE, T, R, B = 190, 66, 40, 54
wb = openpyxl.load_workbook(SRC); ws = wb['Sheet1']
maxrow, maxcol = ws.max_row, ws.max_column
minrow = min(c.row for row in ws.iter_rows() for c in row if c.value is not None or c.border.top.style or c.border.left.style)
mincol = min(c.column for row in ws.iter_rows() for c in row if c.value is not None or c.border.top.style or c.border.left.style)
W = int(L_LANE + maxcol*CELL + R); H = int(T + maxrow*CELL + B)
X = lambda ci: L_LANE + (ci-1)*CELL
Y = lambda ri: T + (ri-1)*CELL          # 行号越大越靠南（下）
img = Image.new('RGB', (W, H), 'white'); dr = ImageDraw.Draw(img)
def fnt(sz, bold=False):
    for p in ([r'C:\Windows\Fonts\msyhbd.ttc', r'C:\Windows\Fonts\msyh.ttc'] if bold else [r'C:\Windows\Fonts\msyh.ttc']):
        if os.path.exists(p):
            try: return ImageFont.truetype(p, sz)
            except Exception: pass
    return ImageFont.load_default()

# ---- 通道底纹：**直接照抄用户 Excel 的填充色**，不再硬编码行号 ----
# 用户 2026-09-29 指出：Excel 里所有通道都用同一种颜色填了。
#   theme3 = 通道（走廊 + 三条纵向干线）  theme1 = 边界/墙  theme9 = 货架块分隔  theme5 = 入口闸机
# ⇒ 逐格铺色，保证「所有通道」都被标出，且与我建模用的可走格完全同源。
ROLE_FILL = {'theme3': '#e3edfb', 'theme1': '#eef0f2', 'theme9': '#fde68a', 'theme5': '#bbf7d0'}


def fillkey(c):
    f = c.fill
    if f is None or f.fill_type is None or f.fgColor is None:
        return None
    fg = f.fgColor
    if fg.type == 'rgb' and fg.rgb:
        return str(fg.rgb)
    if fg.type == 'theme':
        return 'theme%d' % fg.theme
    if fg.type == 'indexed':
        return 'indexed%d' % fg.indexed
    return None


walk_cells, inside_merge = 0, 0
merged_set = set()
for mr in ws.merged_cells.ranges:
    for rr in range(mr.min_row, mr.max_row + 1):
        for cc in range(mr.min_col, mr.max_col + 1):
            merged_set.add((rr, cc))
for row in ws.iter_rows(min_row=minrow, max_row=maxrow, min_col=mincol, max_col=maxcol):
    for c in row:
        k = fillkey(c)
        if k in ROLE_FILL:
            dr.rectangle([X(c.column), Y(c.row), X(c.column + 1), Y(c.row + 1)], fill=ROLE_FILL[k])
        if k == 'theme3':
            walk_cells += 1
            if (c.row, c.column) in merged_set:
                inside_merge += 1
print('通道格(theme3) %d 个，其中落在合并区内的 %d 个' % (walk_cells, inside_merge))
    
# ---- 边框（墙 / 货架轮廓）----
for row in ws.iter_rows(min_row=minrow, max_row=maxrow, min_col=mincol, max_col=maxcol):
    for c in row:
        b = c.border
        x0, y0, x1, y1 = X(c.column), Y(c.row), X(c.column+1), Y(c.row+1)
        def seg(s, xa, ya, xb, yb):
            if s and s.style:
                w = {'thin':1,'medium':3,'thick':4,'double':3,'hair':1}[s.style] if s.style in ('thin','medium','thick','double','hair') else 1
                dr.line([xa,ya,xb,yb], fill='#111111', width=w)
        seg(b.top, x0,y0,x1,y0); seg(b.bottom, x0,y1,x1,y1)
        seg(b.left, x0,y0,x0,y1); seg(b.right, x1,y0,x1,y1)

# ---- 合并区（货架 / 区域）+ 文字 ----
for mr in sorted(ws.merged_cells.ranges, key=lambda r:(r.min_row, r.min_col)):
    c0,r0,c1,r1 = mr.min_col, mr.min_row, mr.max_col, mr.max_row
    x0,y0,x1,y1 = X(c0), Y(r0), X(c1+1), Y(r1+1)
    val = ws.cell(row=r0, column=c0).value
    label = str(val).strip() if val is not None else ''
    w,h = x1-x0, y1-y0
    vertical = h > w*1.6
    fill = None
    if label and label[0] in 'Jj': fill='#cfe0f5'
    elif label and label[0] in 'Ss' and label[1:2].isdigit(): fill='#cfe0f5'
    elif label and label[0] in 'Aa': fill='#f6e2c8'
    elif label and label[0] in 'Yy': fill='#e2d4f0'
    elif label and label[0].isalpha() and label[1:2].isdigit(): fill='#e8e8ea'
    if fill: dr.rectangle([x0,y0,x1,y1], fill=fill)
    dr.rectangle([x0,y0,x1,y1], outline='#5b6472')
    if label:
        fs = max(8, min(int(min(w,h)*0.55), 20 if not vertical else 22))
        f = fnt(fs, True)
        if vertical:
            tmp = Image.new('RGBA', (int(w), int(h)), (0,0,0,0)); d2 = ImageDraw.Draw(tmp)
            d2.text((tmp.width/2, tmp.height/2), label, font=f, fill='#1e3a5f' if fill=='#cfe0f5' else '#333333', anchor='mm')
            img.paste(tmp.rotate(90, expand=True), (int(x0 - (h-w)/2), int(y0 + (h-w)/2)), tmp.rotate(90, expand=True))
        else:
            dr.text(((x0+x1)/2,(y0+y1)/2), label, font=f, fill='#1e3a5f' if fill=='#cfe0f5' else ('#6b4a1e' if fill=='#f6e2c8' else '#333333'), anchor='mm')

# ---- 刻度：把 Excel 列号映射为距主通道的瓷砖数（主通道中心 ≈ N 列，即第 14 列）----
SPINE_COL = 37.5
dr.text((14,20), '海大快递站 平面图（上为北）· 按你的 Excel 逐格渲染', font=fnt(17,True), fill='#111111')
dr.text((14,44), '1 单元格 = 半块瓷砖　·　黑线 = 你画的墙/轮廓　·　浅蓝 = 通道（照抄你的填充色，全部通道）　·　灰底 = 货架　·　蓝 = J/S　·　橙 = A 区　·　紫 = Y 区', font=fnt(12), fill='#666666')
for ci in range(mincol, maxcol+1, 6):
    lat = (ci - SPINE_COL)/2
    dr.text((X(ci)+CELL*3, Y(maxrow+1)+16), ('%+.0f' % lat), font=fnt(11), fill='#8494a8', anchor='mm')
dr.text((X(maxcol/2), Y(maxrow+1)+38), '← 西　瓷砖横坐标（N 列＝主通道中心＝0）　东 →', font=fnt(12), fill='#5a6b80', anchor='mm')
for r in range(minrow, maxrow+1, 6):
    dr.text((L_LANE-8, Y(r)+CELL*3), '第%d行' % r, font=fnt(11), fill='#8494a8', anchor='rm')
bx, by = X(mincol), Y(maxrow+1)+62
dr.line([bx,by,bx+6*P,by], fill='#111111', width=3)
for i in range(7): dr.line([bx+i*P/2*2,by-4,bx+i*P/2*2,by+4], fill='#111111')
dr.text((bx+3*P, by+18), '5 瓷砖', font=fnt(11), fill='#111111', anchor='mm')
dr.polygon([(W-40,22),(W-32,46),(W-40,40),(W-48,46)], fill='#111111')
dr.text((W-40,56), 'N', font=fnt(13,True), fill='#111111', anchor='mm')

# ---- 出入口标注（按 Excel 的标注位置，画在货架之外，不覆盖任何合并区）----
# 入口闸机：Excel 的「入口闸机」在 W58 ⇒ A9(Q58:V58) 之东，取 W~AD 列(24~30)，最右缘距主通道 3 格
dr.rectangle([X(24), Y(57), X(31), Y(59)], fill='#86efac', outline='#15803d', width=2)
dr.text(((X(24)+X(31))/2, Y(55.6)), '入口闸机', font=fnt(12, True), fill='#14532d', anchor='mm')
# 出口：7 个普通闸机 C15:C33（西侧、跨 o~d）、出站闸机 C12、顺丰专用闸机 C13
dr.rectangle([X(4), Y(22), X(6), Y(34)], fill='#fca5a5', outline='#b91c1c', width=2)
dr.text((X(4)-6, (Y(22)+Y(34))/2), '出口 7 闸机', font=fnt(12, True), fill='#7f1d1d', anchor='rm')
dr.rectangle([X(4), Y(15), X(6), Y(17)], fill='#fca5a5', outline='#b91c1c', width=2)
dr.text((X(4)-6, Y(16)), '顺丰专用闸机 C13', font=fnt(11, True), fill='#7f1d1d', anchor='rm')
dr.rectangle([X(4), Y(11), X(6), Y(13)], fill='#fca5a5', outline='#b91c1c', width=2)
dr.text((X(4)-6, Y(12)), '出站闸机 C12', font=fnt(11, True), fill='#7f1d1d', anchor='rm')

png = r'E:\claude\parcel-SMU\.devtools\floorplan-excel.png'; img.save(png)
print('PNG:', png, img.size, os.path.getsize(png), '字节')
print('单元格范围: 行', minrow, '-', maxrow, ' 列', mincol, '-', maxcol)
