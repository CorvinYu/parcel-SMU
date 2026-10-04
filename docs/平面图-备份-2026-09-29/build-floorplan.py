# -*- coding: utf-8 -*-
"""把「Excel → 通道网格 JSON → 寻路核心 → 验过的底图」构建成单文件交互页 `docs/floorplan.html`。

管线（全部来自用户的 Excel，无手写通道假设）：
  1. render-excel.py     照抄 Excel 填充色渲底图（含**全部通道**）→ .devtools/floorplan-excel.png
  2. extract-corridors.py 把通道填充色导成机器可读网格 → docs/floorplan-corridors.json
  3. route-core.js        网格 BFS 最短路 + Held–Karp 精确排序 + 画线（浏览器/Node 共用）
  4. 本脚本               内联三者 → docs/floorplan.html（零外部请求、单文件）

产物自检：占位符必须全部替换、页面里必须有通道网格与寻路核心。
"""
import base64, io, json, os

ROOT = r'E:\claude\parcel-SMU'
PNG = os.path.join(ROOT, r'.devtools\floorplan-excel.png')
COR = os.path.join(ROOT, r'docs\floorplan-corridors.json')
CORE = os.path.join(ROOT, r'.devtools\route-core.js')
TPL = os.path.join(ROOT, r'.devtools\floorplan-template.html')
OUT = os.path.join(ROOT, r'docs\floorplan.html')

b64 = base64.b64encode(io.open(PNG, 'rb').read()).decode('ascii')
cor = json.load(io.open(COR, encoding='utf-8'))
cor_txt = json.dumps(cor, ensure_ascii=False, separators=(',', ':'))
core_txt = io.open(CORE, encoding='utf-8').read()
tpl = io.open(TPL, encoding='utf-8').read()

# 不能出现会提前结束 <script> 的序列
for name, txt in (('cor', cor_txt), ('core', core_txt)):
    if '</' in txt:
        print('注意：%s 含 "</"，已转义' % name)
cor_txt = cor_txt.replace('</', '<\\/')
core_txt = core_txt.replace('</', '<\\/')

html = tpl.replace('__B64__', b64).replace('__COR__', cor_txt).replace('__CORE__', core_txt)
io.open(OUT, 'w', encoding='utf-8', newline='').write(html)

# ---- 自检 ----
left = [p for p in ('__B64__', '__COR__', '__CORE__') if p in html]
checks = {
    '底图 base64': 'data:image/png;base64,iVBOR' in html,
    '通道 RLE 已注入': '"walk":[[' in html,
    '寻路核心已注入': 'buildModel: buildModel' in html,
    '页面标注全部通道': '浅蓝＝你给通道填的色' in html,
    '无残留占位符': not left,
    '无外部请求': ('http://' not in html and 'https://' not in html),
}
print('HTML:', OUT, os.path.getsize(OUT), '字节')
print('通道格:', cor['stats']['walkCells'], '｜通道 JSON', len(cor_txt), 'B ｜核心', len(core_txt), 'B ｜底图', len(b64), 'B')
for k, v in checks.items():
    print(('  [OK] ' if v else '  [NG] ') + k)
if left or not all(checks.values()):
    raise SystemExit('构建自检未通过：' + str(left))
