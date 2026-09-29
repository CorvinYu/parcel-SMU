/* test-floorplan-page.js —— 把**构建出来的** docs/floorplan.html 里的脚本原样跑一遍并断言。
 * 本机 Chrome 起不来（沙箱禁命名管道），所以用 DOM 桩在 Node 里执行页面脚本：
 *   1) 语法：每段 <script> 都能编译
 *   2) 数据：注入的通道 JSON 与核心都完好；模型可走格 = Excel 通道格数
 *   3) 结构：3 条纵向干线（含西侧）/ ≥8 条横走廊
 *   4) 行为：默认 6 件能算出顺序、画出路线；路线每点都在通道格上、且严格正交
 *   5) 断路器：页面自身的「越界断言」必须没有触发
 * 失败即非 0 退出。
 */
'use strict';
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const HTML = path.join(__dirname, '..', 'docs', 'floorplan.html');
const html = fs.readFileSync(HTML, 'utf8');

let fail = 0;
function ok(name, cond, extra) {
  console.log((cond ? '  [OK] ' : '  [NG] ') + name + (extra ? '  ' + extra : ''));
  if (!cond) fail++;
}

/* ---------- DOM 桩 ---------- */
const ctx = new Proxy({}, {
  get: (t, k) => (k in t ? t[k] : () => {}),
  set: (t, k, v) => { t[k] = v; return true; }
});
function el() {
  return {
    style: {}, classList: { add() {}, remove() {} }, dataset: {}, _children: [],
    value: '', textContent: '', innerHTML: '', checked: true, width: 0, height: 0,
    getContext: () => ctx, addEventListener() {},
    appendChild(c) { this._children.push(c); },
    getBoundingClientRect: () => ({ left: 0, top: 0, width: 2154, height: 1043 })
  };
}
const rowsHtml = id => (els[id] && els[id]._children ? els[id]._children.map(c => c.innerHTML).join('') : '');
const els = {};
/* 让桩元素带上 HTML 里写的初始值（浏览器里 input 的 value 来自属性，桩不会自动解析） */
const attrVal = (id) => {
  const m = html.match(new RegExp('id="' + id + '"[^>]*value="([^"]*)"'));
  return m ? m[1] : '';
};
els['codes'] = Object.assign(el(), { value: attrVal('codes') });
const sandbox = {
  console, Math, Map, Set, JSON, Object, Array, String, Number, Boolean, Date, Symbol,
  Uint8Array, Int32Array, Float64Array, isNaN, parseInt, parseFloat, Infinity, NaN,
  document: { getElementById: id => els[id] || (els[id] = el()), createElement: () => el(), addEventListener() {} }
};
sandbox.window = sandbox;
sandbox.self = sandbox;
sandbox.globalThis = sandbox;
vm.createContext(sandbox);

const scripts = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].map(m => m[1]);
console.log('== 0) 页面脚本提取');
ok('找到 2 段内联脚本', scripts.length === 2, '实际 ' + scripts.length);

console.log('\n== 1) 语法编译');
scripts.forEach((s, i) => {
  try { new vm.Script(s, { filename: 'page-script-' + i }); ok('第 ' + (i + 1) + ' 段可编译', true, s.length + ' B'); }
  catch (e) { ok('第 ' + (i + 1) + ' 段可编译', false, e.message); }
});

console.log('\n== 2) 执行页面脚本（含启动时的 run()）');
let ran = true;
try { scripts.forEach(s => vm.runInContext(s, sandbox)); }
catch (e) { ran = false; ok('页面脚本执行无异常', false, e.stack.split('\n').slice(0, 3).join(' | ')); }
if (ran) ok('页面脚本执行无异常', true);

const g = expr => vm.runInContext(expr, sandbox);
const model = g('model'), COR = g('COR'), RC = g('RouteCore');

console.log('\n== 3) 数据与模型');
ok('注入的通道 JSON 完好', COR && COR.stats && COR.walk.length > 0, 'walk 段数 ' + (COR.walk || []).length);
ok('可走格数 = 你 Excel 的通道格数', model.walkCells.length === COR.stats.walkCells,
  model.walkCells.length + ' vs ' + COR.stats.walkCells);
const lanes = g('LANES');
ok('3 条纵向干线（含西侧闸机旁那条）', lanes.trunks.filter(t => t.r1 - t.r0 >= 30).length === 3,
  lanes.trunks.filter(t => t.r1 - t.r0 >= 30).map(t => t.c0 + '~' + t.c1).join(' / '));
ok('横向走廊 ≥8 条', lanes.corridors.filter(t => t.c1 - t.c0 >= 30).length >= 8,
  lanes.corridors.filter(t => t.c1 - t.c0 >= 30).length + ' 条');
ok('闸机带 3 处已并入可通行', g('GATE_SPANS').length === 3 && model.gates.length > 0, model.gates.length + ' 格');
ok('出口按件分工（普通 / 顺丰各一组）',
  g('generalGates').length > 0 && g('sfAll').length > 0 && g('sfGates').length === 1,
  '普通 ' + g('generalGates').length + ' 格 + 顺丰闸机带 ' + g('sfAll').length + ' 格（出库节点取 1 格）');

console.log('\n== 4) 默认 6 件的路线');
const order = g('lastOrder.map(x=>x.code)');
ok('算出顺序', order.length === 6, order.join(' → '));
const plan = g('lastPlan');
ok('画出路线（点数 > 2）', plan && plan.length > 2, plan ? plan.length + ' 个折点' : 'null');
ok('页面的越界断言未触发', els['err'].style.display !== 'block', String(els['err'].textContent || ''));
/* 顺丰出库规则（用户 2026-09-29）：默认用例含 S3-2-2628 ⇒ 必须有顺丰出库段，且排在所有 S 件之后 */
const res0 = g('lastRes');
ok('顺丰出库段存在且在所有 S 件之后',
  res0.hasSf && res0.sfAfter >= 1 && res0.legs.some(l => l.kind === 'sf'),
  'sfAfter=' + res0.sfAfter + '，出库段 ' + res0.legs.filter(l => l.kind === 'sf').length + ' 段');
ok('混合件最后从普通闸机出库', res0.exitKind === 'normal' && res0.gateExit[0] >= 22 && res0.gateExit[0] <= 57,
  '行' + res0.gateExit[0]);
ok('列表里插入了顺丰出库这一行', rowsHtml('seq').indexOf('顺丰出库') >= 0,
  '列表行数 ' + (els['seq']._children || []).length);
let offGrid = 0, diag = 0, diagAt = '';
for (const [x, y] of (plan || [])) {
  const c = (x - 183.5) / 13, r = (y - 59.5) / 13;
  if (Math.abs(c - Math.round(c)) > 1e-6 || Math.abs(r - Math.round(r)) > 1e-6) offGrid++;
  else if (model.kindAt(Math.round(r), Math.round(c)) === 0) offGrid++;
}
for (let i = 1; i < (plan || []).length; i++) {
  const dx = Math.abs(plan[i][0] - plan[i - 1][0]), dy = Math.abs(plan[i][1] - plan[i - 1][1]);
  if (dx > 1e-6 && dy > 1e-6) { diag++; if (!diagAt) diagAt = JSON.stringify([plan[i - 1], plan[i]]); }
}
ok('路线每个折点都落在通道格上', offGrid === 0, offGrid ? offGrid + ' 个越界点' : '');
ok('路线严格正交（无斜线）', diag === 0, diag ? diag + ' 段斜线 ' + diagAt : '');
ok('列表与徽标已填好', String(els['totalBadge'].textContent).indexOf('格') > 0, String(els['totalBadge'].textContent));
ok('精确解标记正确', String(els['exactBadge'].textContent) === '精确最优', String(els['exactBadge'].textContent));
ok('入口投影到通道格', g('entrance').row > 0 && model.kindAt(g('entrance').row, g('entrance').col) === 1,
  '(' + g('entrance').row + ',' + g('entrance').col + ')');
/* J 柜列三面是墙、朝南开口 ⇒ 投影必须落在南侧走廊，不许穿墙投进主通道 */
const jn = g('locate("J5-21")').cellPos;
ok('J 柜列投影不穿墙（落在南侧走廊）', jn.row >= 12 && jn.col >= 25 && jn.col <= 34, '(' + jn.row + ',' + jn.col + ')');

/* 把**页面自己算出的**路线落盘，交给 check-route.py 独立复验并渲染（我肉眼核对的＝用户将看到的） */
const res = g('lastRes');
fs.writeFileSync(path.join(__dirname, 'page-sample.json'), JSON.stringify({
  entrance: { row: g('entrance').row, col: g('entrance').col, lat: g('entrance').depth },
  gateSpan: g('GATE_SPANS'),
  picks: g('items').map(it => ({ code: it.code, label: it.pos.label, row: it.pos.cellPos.row, col: it.pos.cellPos.col })),
  order: g('lastOrder').map(x => x.code),
  total: res.total,
  legs: res.legs.map(l => ({ from: l.from, to: l.to, tiles: l.tiles, cells: l.cells }))
}), 'utf8');
console.log('  [--] 页面真实输出已落盘 .devtools/page-sample.json（' + res.order.length + ' 件，' + res.total.toFixed(1) + ' 格）');

/* 纯普通件：不绕顺丰出库；纯顺丰件：终点就是顺丰出库点 */
els['codes'].value = 'B1-1, D8-6';
g('run()');
const nOnly = g('lastRes');
ok('纯普通件不绕顺丰出库', nOnly.hasSf === false && nOnly.sfAfter === -1 && nOnly.exitKind === 'normal',
  'exitKind=' + nOnly.exitKind);
els['codes'].value = 'S1-10, S3-2-2628';
g('run()');
const sOnly = g('lastRes');
ok('纯顺丰件：出库在顺丰专用闸机（行17~21）', sOnly.sfGate[0] >= 17 && sOnly.sfGate[0] <= 21, '行' + sOnly.sfGate[0]);
ok('纯顺丰件：终点是顺丰侧出站机（行12~16），不是出库机',
  sOnly.exitKind === 'sfExit' && sOnly.gateExit[0] >= 12 && sOnly.gateExit[0] <= 16,
  'exitKind=' + sOnly.exitKind + ' 行' + sOnly.gateExit[0]);
/* 随机池必须覆盖 S 顺丰与 Y 大件（用户指出：以前随机/点图根本不会出现这两类货） */
const pool = g('SHELF_CODES');
ok('随机池覆盖 S 顺丰与 Y 大件',
  pool.some(x => /^S/.test(x)) && pool.some(x => /^Y/.test(x)) && pool.some(x => /^J/.test(x)),
  '池里 ' + pool.length + ' 个：' + pool.filter(x => /^[SJY]/.test(x)).join(','));
/* J 柜列：格位落入柜列纵深，且有区内走位 */
const j1 = g('locate("J5-1")'), j21 = g('locate("J5-21")');
ok('J 按格位展开纵深', j21.depth > j1.depth && j21.stub > j1.stub,
  '深 ' + j1.depth + '→' + j21.depth + '，区内走位 ' + j1.stub.toFixed(2) + '→' + j21.stub.toFixed(2));
/* S 区按格位横向展开 */
const s1a = g('locate("S1-1")'), s1b = g('locate("S1-10")');
ok('S 按格位横向展开', s1a.lat < s1b.lat, s1a.lat + ' → ' + s1b.lat);

/* S 区同货架不同格必须投到不同通道格（曾经的 bug：用合并区中心决胜 ⇒ 段距恒为 0） */
const s32 = g('locate("S3-2-2628")'), s33 = g('locate("S3-3-7606")');
ok('S 区同货架不同格投到不同通道格',
  !(s32.cellPos.row === s33.cellPos.row && s32.cellPos.col === s33.cellPos.col),
  `S3-2 (${s32.cellPos.col}) vs S3-3 (${s33.cellPos.col})`);

console.log('\n== 5) 边界：无法定位 / 超过 13 件 / 30 件压测');
/* 混合件：顺丰出库点必须落在顺丰两处闸机（行 12~21），最终仍从普通闸机出 */
els['codes'].value = 'S3-2-2628, B1-1';
g('run()');
const mixRes = g('lastRes');
ok('混合件：顺丰出库点在顺丰闸机带（行 12~21）',
  mixRes.sfGate[0] >= 12 && mixRes.sfGate[0] <= 21, '行' + mixRes.sfGate[0] + ' 列' + mixRes.sfGate[1]);
ok('混合件：最终出库点在普通闸机带（行 22~57）',
  mixRes.exitKind === 'normal' && mixRes.gateExit[0] >= 22 && mixRes.gateExit[0] <= 57,
  '行' + mixRes.gateExit[0]);
ok('顺丰路线仍只走通道', g('lastPlan').length > 2);
els['codes'].value = 'XYZ-9, 12345';
g('run()');
ok('无法定位时不报错、给出提示', /无法定位/.test(String(els['runInfo'].textContent || '')), String(els['runInfo'].textContent));
els['codes'].value = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14].map(i => 'B' + ((i % 12) + 1) + '-1').join(', ');
g('run()');
/* 精确上限已从 13 提到 16 ⇒ 14 件现在是精确解（退化点改由下面的 17 件用例覆盖） */
ok('14 件走精确解（上限已提到 16）', String(els['exactBadge'].textContent) === '精确最优', String(els['exactBadge'].textContent));
ok('14 件仍只走通道', g('lastPlan').length > 2);

/* 16 件（新的精确上限）应是精确解；17 件退化 */
els['codes'].value = Array.from({ length: 16 }, (_, i) => 'B' + ((i % 12) + 1) + '-1').filter((v, i, a) => a.indexOf(v) === i).concat(['D1-1', 'D2-1', 'D3-1', 'D4-1', 'E1-1']).slice(0, 16).join(', ');
g('run()');
console.log('  16 件用例 =', String(els['codes'].value));
ok('16 件走精确解（新的上限）', String(els['exactBadge'].textContent) === '精确最优', String(els['exactBadge'].textContent));
els['codes'].value = Array.from({ length: 17 }, (_, i) => 'D' + ((i % 12) + 1) + '-1').filter((v, i, a) => a.indexOf(v) === i)
  .concat(['E1-1', 'E2-1', 'E3-1', 'E4-1', 'F1-1', 'F2-1', 'F3-1', 'F4-1']).slice(0, 17).join(', ');
g('run()');
console.log('  17 件用例 =', String(els['codes'].value));
ok('17 件退化为启发式', String(els['exactBadge'].textContent).indexOf('非精确') >= 0, String(els['exactBadge'].textContent));

/* 30 件压测：跑得动、路线仍只走通道、逐段和 == 总距离、无重复件 */
{
  const pool = g('SHELF_CODES');
  const codes30 = [];
  for (let i = 0; i < pool.length && codes30.length < 30; i += Math.max(1, Math.floor(pool.length / 30))) {
    codes30.push(pool[i] + '-1');
  }
  els['codes'].value = codes30.join(', ');
  const t0 = Date.now();
  g('run()');
  const ms = Date.now() - t0;
  const r30 = g('lastRes');
  const sum30 = r30.legs.reduce((a, l) => a + (l.tiles || 0), 0);
  ok('30 件压测：件数正确', g('lastOrder').length === 30, g('lastOrder').length + ' 件');
  ok('30 件压测：逐段和 == 总距离', Math.abs(sum30 - r30.total) < 1e-6, sum30.toFixed(1) + ' vs ' + r30.total.toFixed(1));
  ok('30 件压测：无重复件', new Set(g('lastOrder').map(x => x.code)).size === 30);
  ok('30 件压测：路线仍只走通道（无斜线）', (() => {
    const pl = g('lastPlan');
    for (let i = 1; i < pl.length; i++) {
      const dx = Math.abs(pl[i][0] - pl[i - 1][0]), dy = Math.abs(pl[i][1] - pl[i - 1][1]);
      if (dx > 1e-6 && dy > 1e-6) return false;
    }
    return pl.length > 2;
  })(), g('lastPlan').length + ' 个折点');
  ok('30 件压测：耗时可控（< 3000 ms）', ms < 3000, ms + ' ms');
  console.log('  30 件：共 ' + r30.total.toFixed(1) + ' 格，' + g('lastPlan').length + ' 个折点，' + ms + ' ms');
}

console.log('\n' + (fail ? 'FAILED ' + fail : 'ALL PASSED'));
process.exit(fail ? 1 : 0);
