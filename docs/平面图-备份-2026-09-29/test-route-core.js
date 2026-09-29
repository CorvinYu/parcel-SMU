/* test-route-core.js —— 寻路核心的独立验证（Node 直跑，不需要浏览器）
 *   1) 通道覆盖：模型的可走格必须与 Excel 填充色导出的**逐格一致**
 *   2) 结构自检：3 条纵向干线（含西侧）+ 9 条横向走廊带
 *   3) 度量公理：对称、d(a,a)=0、三角不等式
 *   4) SJY 定位精度：S 按格位横向展开、J 按纵深展开（含区内走位 stub）、Y 如实标 approx
 *   5) 最优性：Held–Karp vs **暴力枚举全排列**（含「顺丰出库必须在所有 S 件之后」约束）
 *   6) 顺丰出库规则（用户 2026-09-29）：拿 S 必须先顺丰出库；混合时先在顺丰出库再去普通，末了从普通出
 *   7) 路线合法性：每步相邻（正交、不跳格）、每格都在通道上
 *   8) 西侧通道确实被用上；J 投影不穿墙
 * 失败即非 0 退出。
 */
'use strict';
const fs = require('fs');
const path = require('path');
const RC = require(path.join(__dirname, 'route-core.js'));

const COR = path.join(__dirname, '..', 'docs', 'floorplan-corridors.json');
const cor = JSON.parse(fs.readFileSync(COR, 'utf8'));

/* 闸机带分组（依据 Excel 合并区标签**原文**）：
   「7个普通闸机」C22:E57 = 普通件出库 + 出站
   「顺丰专用闸机」C17:E21 = 顺丰**出库**（用户：这台**不能出站**）
   「顺丰和无快递出口」C12:E16 = 顺丰侧**出站**（只有顺丰件时，出库后还要走到这里） */
const gateRects = cor.rects.filter(r => r.label && /(闸机|出口)/.test(r.label));
const spansOf = re => gateRects.filter(r => re.test(r.label)).map(r => [r.r0, r.r1, r.c0, r.c1]);
const normalSpans = spansOf(/普通闸机/);
const sfRects = gateRects.filter(r => /顺丰/.test(r.label));
const sfCheckoutSpans = sfRects.filter(r => /专用/.test(r.label)).map(r => [r.r0, r.r1, r.c0, r.c1]);
const sfExitSpans = sfRects.filter(r => !/专用/.test(r.label)).map(r => [r.r0, r.r1, r.c0, r.c1]);
const model = RC.buildModel(cor, { gateSpans: gateRects.map(r => [r.r0, r.r1, r.c0, r.c1]), shelfRects: cor.rects });
const cellsIn = sp => model.gates.filter(([r, c]) => r >= sp[0] && r <= sp[1] && c >= sp[2] && c <= sp[3]);
const centerOf = (spans, cells) => {
  const r0 = Math.min(...spans.map(s => s[0])), r1 = Math.max(...spans.map(s => s[1]));
  const c0 = Math.min(...spans.map(s => s[2])), c1 = Math.max(...spans.map(s => s[3]));
  const cr = (r0 + r1 + 1) / 2, cc = (c0 + c1 + 1) / 2;
  let best = null, bd = Infinity;
  cells.forEach(c => { const d = (c[0] - cr) * (c[0] - cr) + (c[1] - cc) * (c[1] - cc); if (d < bd) { bd = d; best = c; } });
  return best;
};
const normalGates = normalSpans.flatMap(cellsIn);
const sfCheckoutCells = sfCheckoutSpans.flatMap(cellsIn);
const sfExitGates = sfExitSpans.flatMap(cellsIn);
const sfAll = spansOf(/顺丰/).flatMap(cellsIn);
const sfGates = [centerOf(sfCheckoutSpans, sfCheckoutCells)];   // 出库节点：中间停靠点 ⇒ 单一格
const locate = RC.makeLocator(cor, model);

let fail = 0;
function ok(name, cond, extra) {
  console.log((cond ? '  ✅ ' : '  ❌ ') + name + (extra ? '  ' + extra : ''));
  if (!cond) fail++;
}
function eq(a, b) { return Math.abs(a - b) < 1e-6; }

/* ---------- 1) 通道覆盖 ---------- */
let expected = 0;
cor.walk.forEach(([r, a, b]) => { for (let c = a; c <= b; c++) expected++; });
let got = 0;
for (let r = cor.span.minRow; r <= cor.span.maxRow; r++)
  for (let c = cor.span.minCol; c <= cor.span.maxCol; c++)
    if (model.kindAt(r, c) === 1) got++;
console.log('\n== 1) 通道覆盖（Excel 填充色 theme3 全量）');
ok('可走通道格数与 Excel 完全一致', expected === got, `${got} 格`);
const missing = [];
cor.walk.forEach(([r, a, b]) => { for (let c = a; c <= b; c++) if (model.kindAt(r, c) !== 1) missing.push([r, c]); });
ok('没有任何一段通道落在模型之外', missing.length === 0, missing.length ? JSON.stringify(missing.slice(0, 5)) : '');

/* ---------- 2) 结构自检 ---------- */
console.log('\n== 2) 通道结构（模型自己识别出来的）');
const an = RC.analyze(model);
const fmtV = t => `列${t.c0}~${t.c1}(lat ${model.latOf(t.c0 - 0.5).toFixed(1)}~${model.latOf(t.c1 + 0.5).toFixed(1)}) 行${t.r0}~${t.r1}`;
console.log('  纵向干线：');
an.trunks.sort((a, b) => a.c0 - b.c0).forEach(t => console.log('    · ' + fmtV(t)));
const longTrunks = an.trunks.filter(t => t.r1 - t.r0 >= 30);
ok('识别出 3 条纵向干线（西侧 F~J / 主通道 AI~AN / 东侧 CK~CP）', longTrunks.length === 3, longTrunks.length + ' 条');
const westTrunk = longTrunks.find(t => t.c0 <= 6 && t.c1 >= 10);
ok('西侧纵向通道在模型里（列 F~J，lat −16~−13.5）', !!westTrunk, westTrunk ? fmtV(westTrunk) : '缺失');
ok('横向走廊识别到 8 条带以上', an.corridors.filter(t => t.c1 - t.c0 >= 30).length >= 8,
  an.corridors.filter(t => t.c1 - t.c0 >= 30).length + ' 个矩形');

/* ---------- 3) 取件点与度量公理 ---------- */
const codes = ['B1-1', 'B12-1', 'D8-6', 'F12-32', 'Q1-3', 'P1-1', 'N5-1', 'K3-2',
  'J5-21', 'J5-1', 'S1-1', 'S1-10', 'S3-2-2628', 'S2-8', 'A4-1', 'A9-1', 'Y5-7-1'];
const picks = codes.map(c => { const p = locate(c); return p ? Object.assign({ code: c }, p) : null; }).filter(Boolean);
console.log('\n== 3) 度量公理（' + picks.length + ' 个取件点，全部来自 Excel 合并区）');
const dcell = (a, b) => {
  const bfs = model.bfs(a.cellPos.row, a.cellPos.col);
  const i = model.at(b.cellPos.row, b.cellPos.col);
  return bfs.dist[i];
};
let sym = true, deg = true, tri = true, unreach = 0;
for (const a of picks) for (const b of picks) {
  const d1 = dcell(a, b), d2 = dcell(b, a);
  if (d1 < 0) { unreach++; continue; }
  if (d1 !== d2) sym = false;
  if (a === b && d1 !== 0) deg = false;
}
for (const a of picks) for (const b of picks) for (const c of picks) {
  const ab = dcell(a, b), bc = dcell(b, c), ac = dcell(a, c);
  if (ab < 0 || bc < 0 || ac < 0) continue;
  if (ac > ab + bc) tri = false;
}
ok('对称性 d(a,b)=d(b,a)', sym);
ok('单位元 d(a,a)=0', deg);
ok('三角不等式', tri);
ok('所有取件点互相可达', unreach === 0, unreach ? unreach + ' 对不可达' : '');

/* ---------- 4) SJY 定位精度 ---------- */
console.log('\n== 4) S / J / Y 定位精度');
const s1a = locate('S1-1'), s1b = locate('S1-10'), s3a = locate('S3-2-2628'), s3b = locate('S3-8');
console.log(`  S1-1  lat ${s1a.lat.toFixed(2)}    S1-10 lat ${s1b.lat.toFixed(2)}`);
console.log(`  S3-2  lat ${s3a.lat.toFixed(2)}    S3-8  lat ${s3b.lat.toFixed(2)}`);
ok('S 区按格位横向展开（左端为 1，向右递增）',
  s1a.lat < s1b.lat && s3a.lat < s3b.lat && eq(s1a.lat, 1.5) && eq(s1b.lat, 4.5),
  `S1: ${s1a.lat.toFixed(1)} → ${s1b.lat.toFixed(1)}`);
/* 回归：同一货架的不同格必须投到**不同的通道格**（曾经用合并区中心决胜 ⇒ 全投同一格 ⇒ 段距恒为 0） */
const s3c = locate('S3-3-7606');
console.log(`  S3-2 → 通道格 (${s3a.cellPos.row},${s3a.cellPos.col})   S3-3 → (${s3c.cellPos.row},${s3c.cellPos.col})`);
ok('S 区同货架不同格投到不同通道格（否则段距恒为 0）',
  !(s3a.cellPos.row === s3c.cellPos.row && s3a.cellPos.col === s3c.cellPos.col),
  `S3-2 (${s3a.cellPos.col}) vs S3-3 (${s3c.cellPos.col})`);
ok('S1-1 与 S1-10 也投到不同格',
  locate('S1-1').cellPos.col !== locate('S1-10').cellPos.col,
  `${locate('S1-1').cellPos.col} vs ${locate('S1-10').cellPos.col}`);
const j1 = locate('J5-1'), j21 = locate('J5-21');
console.log(`  J5-1  深 ${j1.depth.toFixed(2)} 区内走位 ${j1.stub.toFixed(2)} 格`);
console.log(`  J5-21 深 ${j21.depth.toFixed(2)} 区内走位 ${j21.stub.toFixed(2)} 格`);
ok('J 柜列按格位纵向展开（外端为 1，向里递增）', j21.depth > j1.depth, `${j1.depth.toFixed(1)} → ${j21.depth.toFixed(1)}`);
ok('J 的区内走位计入距离（越往里 stub 越大）', j21.stub > j1.stub, `${j1.stub.toFixed(2)} < ${j21.stub.toFixed(2)}`);
ok('J 格位落在柜列合并区内', j21.depth >= j21.rect.d0 - 1e-6 && j21.depth <= j21.rect.d1 + 1e-6,
  `深 ${j21.depth.toFixed(2)} ⊂ [${j21.rect.d0}, ${j21.rect.d1}]`);
const y = locate('Y5-7-1');
ok('Y 区如实标注「精确版里是一整块」', y.approx === true, y.label.slice(-24));

/* ---------- 5) 最优性：Held–Karp vs 暴力枚举（含顺丰约束） ---------- */
console.log('\n== 5) 最优性（Held–Karp vs 暴力枚举，含顺丰出库先后约束）');
const entRect = cor.gate.find(g => g[2] - g[1] >= 2);
const entrance = model.nearestWalkFrom(
  Array.from({ length: entRect[2] - entRect[1] + 1 }, (_, k) => [entRect[0], entRect[1] + k]),
  entRect[0], (entRect[1] + entRect[2] + 1) / 2);
console.log(`  入口：Excel 入口闸机 行${entRect[0]} 列${entRect[1]}~${entRect[2]} ⇒ 通道格 (${entrance.row},${entrance.col})`);

const bfsCache = new Map();
function dcBfs(a) {                                   // a: {row,col}
  const k = a.row + ',' + a.col;
  let b = bfsCache.get(k);
  if (!b) { b = model.bfs(a.row, a.col); bfsCache.set(k, b); }
  return b;
}
function dc(a, b) {
  const idx = model.at(b.row, b.col);
  const d = dcBfs(a).dist[idx];
  return d < 0 ? Infinity : d;
}
function minTo(a, cells) { let best = Infinity; for (const c of cells) best = Math.min(best, dc(a, { row: c[0], col: c[1] })); return best; }
function minBetween(A, B) { let m = Infinity; for (const a of A) m = Math.min(m, minTo({ row: a[0], col: a[1] }, B)); return m; }

/* 暴力枚举：所有排列 × 顺丰出库点的**所有合法插入位置**（必须在最后一个 S 之后）。
   ⚠️ 一律用**单元格**记账（stub 是瓷砖，1 瓷砖 = 2 单元格），最后 ×0.5；单位混用会少算一半。 */
function bruteForce(ps) {
  const n = ps.length, idx = [...Array(n).keys()];
  const hasSf = ps.some(p => p.sf), hasNormal = ps.some(p => !p.sf);
  const stub = p => (p.stub || 0) / 0.5;
  let best = Infinity, bestOrder = null, bestSfAt = -1;
  const permute = (arr, k) => {
    if (k === n) {
      let lastS = -1;
      for (let i = 0; i < n; i++) if (ps[arr[i]].sf) lastS = i;
      const positions = hasSf ? Array.from({ length: n - lastS }, (_, j) => lastS + 1 + j) : [null];
      for (const pos of positions) {
        /* 显式构造停靠序列：件…、可选的「顺丰出库」、件… */
        const stops = [];
        for (let i = 0; i < n; i++) {
          if (hasSf && i === pos) stops.push({ t: 'sf' });
          stops.push({ t: 'p', i: arr[i] });
        }
        if (hasSf && pos === n) stops.push({ t: 'sf' });
        let cost = 0, prev = { t: 'e' };
        for (const nd of stops) {
          if (nd.t === 'sf') {
            if (prev.t === 'p') cost += stub(ps[prev.i]);
            cost += minTo(prev.t === 'p' ? ps[prev.i].cell : entrance, sfGates);
            prev = { t: 'sf' };
          } else {
            let d;
            if (prev.t === 'e') d = dc(entrance, ps[nd.i].cell);
            else if (prev.t === 'sf') d = minTo(ps[nd.i].cell, sfGates);
            else d = dc(ps[prev.i].cell, ps[nd.i].cell);
            if (prev.t === 'p') cost += stub(ps[prev.i]);      // 离开上一个件（出区）
            cost += d + stub(ps[nd.i]);                        // 到达本件（入区）
            prev = { t: 'p', i: nd.i };
          }
        }
        if (hasNormal) {
          if (prev.t === 'sf') cost += minBetween(sfGates, normalGates);
          else { cost += stub(ps[prev.i]); cost += minTo(ps[prev.i].cell, normalGates); }
        } else {
          /* 只有顺丰件：出库 ≠ 出站 ⇒ 必须**出库之后**再走到顺丰侧出站机 */
          if (prev.t !== 'sf') cost = Infinity;
          else cost += minBetween(sfGates, sfExitGates);
        }
        if (cost < best) { best = cost; bestOrder = arr.slice(); bestSfAt = pos; }
      }
      return;
    }
    for (let i = k; i < n; i++) { [arr[k], arr[i]] = [arr[i], arr[k]]; permute(arr, k + 1); [arr[k], arr[i]] = [arr[i], arr[k]]; }
  };
  permute(idx, 0);
  return { cells: best, order: bestOrder, sfAt: bestSfAt };
}
function sol(ps) {
  return RC.solve(ps, { model, entranceCell: entrance, sfGateCells: sfGates, sfExitGates: sfExitGates, normalGateCells: normalGates });
}
let cmp = 0, mismatch = [];
const sfPool = picks.filter(p => /^S/.test(p.code));
const nmPool = picks.filter(p => !/^S/.test(p.code));
for (let trial = 0; trial < 72; trial++) {
  const n = 1 + (trial % 8);
  const wantSf = trial % 3 !== 2;                     // 约 2/3 的用例含顺丰件
  const set = [];
  if (wantSf && sfPool.length) set.push(sfPool[Math.floor(Math.random() * sfPool.length)]);
  while (set.length < n) {
    const pool = (set.length && trial % 4 === 3) ? nmPool : picks;
    const p = pool[Math.floor(Math.random() * pool.length)];
    if (!set.includes(p)) set.push(p);
  }
  const ps = set.map(p => ({ label: p.code, cell: p.cellPos, sf: /^S/.test(p.code), stub: p.stub }));
  const r = sol(ps);
  const bf = bruteForce(ps);
  cmp++;
  if (!eq(r.total, bf.cells * 0.5)) mismatch.push({ n, hk: r.total, bf: bf.cells * 0.5, codes: set.map(s => s.code) });
}
ok(cmp + ' 组随机用例（n=1~8，含顺丰约束）：Held–Karp == 暴力枚举', mismatch.length === 0,
  mismatch.length ? JSON.stringify(mismatch.slice(0, 2)) : '');

/* ---------- 6) 顺丰出库规则 ---------- */
console.log('\n== 6) 顺丰出库规则（用户 2026-09-29）');
function mkPs(list) { return list.map(p => ({ label: p.code, cell: p.cellPos, sf: /^S/.test(p.code), stub: p.stub })); }
/* 6a 只有普通件：不出现顺丰出库，终点是普通闸机 */
const onlyN = sol(mkPs([picks.find(p => p.code === 'B1-1'), picks.find(p => p.code === 'D8-6')]));
ok('6a 纯普通件：不绕顺丰出库', onlyN.hasSf === false && onlyN.sfAfter === -1 && onlyN.exitKind === 'normal',
  'exitKind=' + onlyN.exitKind);
ok('6a 终点落在 7 号普通闸机带内', onlyN.gateExit[0] >= 22 && onlyN.gateExit[0] <= 57, '行' + onlyN.gateExit[0]);
/* 6b 只有顺丰件：出库在顺丰专用闸机（行17~21），**终点是顺丰侧出站机**（行12~16）——出库机不能出站 */
const onlyS = sol(mkPs([picks.find(p => p.code === 'S1-10'), picks.find(p => p.code === 'S3-2-2628')]));
ok('6b 纯顺丰件：出库点在顺丰专用闸机（行17~21）', onlyS.hasSf && onlyS.sfGate[0] >= 17 && onlyS.sfGate[0] <= 21,
  '行' + onlyS.sfGate[0] + ' 列' + onlyS.sfGate[1]);
ok('6b 纯顺丰件：终点是顺丰侧出站机（行12~16），不是出库机',
  onlyS.exitKind === 'sfExit' && onlyS.gateExit[0] >= 12 && onlyS.gateExit[0] <= 16,
  'exitKind=' + onlyS.exitKind + ' 行' + onlyS.gateExit[0]);
ok('6b 出库点在最后一个 S 件之后', onlyS.sfAfter === 2, 'sfAfter=' + onlyS.sfAfter);
/* 6c 混合：顺丰出库 → 普通件 → 普通闸机出库 */
const mix = sol(mkPs(['S3-2-2628', 'B1-1', 'D8-6', 'J5-21'].map(c => picks.find(p => p.code === c))));
const sfLeg = mix.legs.find(l => l.kind === 'sf');
const exitLeg = mix.legs[mix.legs.length - 1];
ok('6c 混合：存在顺丰出库这一段', !!sfLeg, sfLeg ? (sfLeg.from + '→' + sfLeg.to + ' ' + sfLeg.tiles.toFixed(1) + ' 格') : '缺失');
ok('6c 混合：顺丰出库在所有 S 件之后',
  mix.sfAfter >= 1 && mix.order.slice(0, mix.sfAfter).filter(i => /^S/.test(['S3-2-2628', 'B1-1', 'D8-6', 'J5-21'][i])).length === 1,
  'sfAfter=' + mix.sfAfter + '，顺序 ' + mix.order.map(i => ['S3-2-2628', 'B1-1', 'D8-6', 'J5-21'][i]).join(' → '));
ok('6c 混合：最后从普通闸机出库', mix.exitKind === 'normal' && exitLeg.to === '普通闸机出库', exitLeg.to);
/* 6d 显式构造「普通件排最后」的用例，验证出库点位置随顺序变化 */
const mix2 = sol(mkPs(['S1-1', 'Q1-3', 'B1-1'].map(c => picks.find(p => p.code === c))));
ok('6d 混合用例都能算出合法顺序', mix2.order.length === 3 && mix2.exitKind === 'normal',
  'sfAfter=' + mix2.sfAfter + '，顺序 ' + mix2.order.join(','));

/* ---------- 7) 路线合法性 ---------- */
console.log('\n== 7) 路线合法性（不压货架 / 不斜穿）');
function checkLegs(res, tag) {
  let errs = [], sum = 0;
  res.legs.forEach(l => { sum += l.tiles || 0; if (l.cells) errs = errs.concat(RC.assertRoute(model, l.cells, l.from + '→' + l.to)); });
  return { errs, sum };
}
const bigList = picks.filter(p => ['B1-1', 'B12-1', 'D8-6', 'F12-32', 'Q1-3', 'N5-1', 'K3-2', 'J5-21', 'S3-2-2628', 'A4-1'].includes(p.code));
const bigPs = mkPs(bigList);
const big = sol(bigPs);
const ck = checkLegs(big, '多件');
ok('所有路线单元格都在通道上、每步相邻', ck.errs.length === 0, ck.errs.slice(0, 4).join(' | ') || (ck.sum.toFixed(1) + ' 格'));
ok('逐段距离之和 == 总距离', eq(ck.sum, big.total), ck.sum.toFixed(1) + ' vs ' + big.total.toFixed(1));
ok('件数 ≤13 时走精确解', big.exact === true);
let bad2 = 0;
for (let t = 0; t < 200; t++) {
  const n = 2 + Math.floor(Math.random() * 6);
  const set = [];
  if (t % 3 === 0 && sfPool.length) set.push(sfPool[0]);
  while (set.length < n) { const p = picks[Math.floor(Math.random() * picks.length)]; if (!set.includes(p)) set.push(p); }
  const r2 = sol(mkPs(set));
  r2.legs.forEach(l => { if (l.cells && RC.assertRoute(model, l.cells, '').length) bad2++; });
}
ok('再随机 200 组：全部只走通道', bad2 === 0, bad2 ? bad2 + ' 段越界' : '');

/* ---------- 8) 西侧通道收益 + J 不穿墙 ---------- */
console.log('\n== 8) 西侧纵向通道收益 / J 柜列投影');
const b1 = locate('B1-1'), q1 = locate('Q1-1');
const viaModel = dcell(b1, q1) * 0.5;
const oldFormula = Math.abs(b1.lat) + Math.abs(b1.depth - q1.depth) + Math.abs(q1.lat);
console.log(`  B1-1(走廊0) → Q1-1(走廊7)：新模型 ${viaModel.toFixed(1)} 格，旧模型(绕主通道) ${oldFormula.toFixed(1)} 格`);
ok('走西侧通道比绕主通道更短', viaModel < oldFormula, `${viaModel.toFixed(1)} < ${oldFormula.toFixed(1)}`);
const j5 = locate('J5-21');
ok('J 柜列投到南侧走廊（不许穿墙进主通道）',
  j5.cellPos.row >= 12 && j5.cellPos.col >= 25 && j5.cellPos.col <= 34,
  '(' + j5.cellPos.row + ',' + j5.cellPos.col + ')');

/* ---------- 落盘：给渲染自检用 ---------- */
const dump = {
  entrance: { row: entrance.row, col: entrance.col },
  gateSpan: gateRects.map(r => [r.r0, r.r1, r.c0, r.c1]),
  normalSpans: normalSpans, sfCheckoutSpans: sfCheckoutSpans, sfExitSpans: sfExitSpans,
  picks: bigList.map(p => ({ code: p.code, label: p.label, row: p.cellPos.row, col: p.cellPos.col, lat: p.lat, depth: p.depth, stub: p.stub })),
  order: big.order.map(i => bigPs[i].label),
  sfAfter: big.sfAfter, sfGate: big.sfGate, exitKind: big.exitKind,
  total: big.total,
  legs: big.legs.map(l => ({ from: l.from, to: l.to, tiles: l.tiles, cells: l.cells, kind: l.kind }))
};
fs.writeFileSync(path.join(__dirname, 'route-sample.json'), JSON.stringify(dump), 'utf8');
console.log('\n样例路线已落盘：.devtools/route-sample.json（' + dump.order.length + ' 件，共 ' + big.total.toFixed(1) + ' 格）');
console.log('顺序：' + dump.order.join(' → ') + (dump.sfAfter >= 0 ? '（第 ' + dump.sfAfter + ' 件后顺丰出库）' : '') + ' → ' + dump.exitKind + ' 出库');

/* ---------- 9) 大件数压测（20 / 30 件） ----------
   用户 2026-09-30 要求：拿 20、30 件这种量级试。这里只断言结构性性质（精确解不可能，
   2^30 状态）：件不重不漏、逐段和 == 总距离、耗时可控、每段都只走通道。 */
console.log('\n== 9) 大件数压测（20 / 30 件，走启发式）');
{
  /* 大池子：全部普通排 + J + S + Y（codes 里只有 17 个，凑不出 30 件） */
  const poolAll = cor.rects
    .filter(r => /^[A-R]\d{1,2}$/.test(r.label || ''))
    .map(r => r.label + '-1')
    .concat(['S1-1', 'S2-1', 'S3-1', 'S3-8', 'Y1-1', 'Y5-1', 'Y8-1']);
  let seed = 20260930;

  /* ⚠️ 必须用 Math.imul 做 32 位乘法：JS 的 Number 在 seed*1103515245 时已超 2^53，
     低位被抹平 ⇒ & 0x7fffffff 恒为同一个值 ⇒ 「随机取件码」会死循环（踩过）。 */
  function nextInt(bound) {
    seed = (Math.imul(seed, 1103515245) + 12345) & 0x7fffffff;
    return seed % bound;
  }

  function makeSet(size) {
    const set = [];
    let guard = 0;
    while (set.length < size && guard++ < size * 200) {
      const c = poolAll[nextInt(poolAll.length)];
      if (!set.includes(c)) set.push(c);
    }
    return set;
  }

  let bad = 0, worstMs = 0, totals = [];
  for (const size of [20, 30]) {
    for (let t = 0; t < 6; t++) {
      const codesN = makeSet(size);
      const ps = codesN.map(c => { const p = locate(c); return { label: c, cell: p.cellPos, sf: /^S/.test(c), stub: p.stub }; });
      const t0 = Date.now();
      const r = RC.solve(ps, { model, entranceCell: entrance, sfGateCells: sfGates, sfExitGates: sfExitGates, normalGateCells: normalGates });
      const ms = Date.now() - t0;
      worstMs = Math.max(worstMs, ms);
      totals.push(r.total);
      if (r.exact) bad++;                                        // >16 件必须如实标非精确
      const visited = r.order.slice().sort((a, b) => a - b).join(',');
      if (visited !== ps.map((_, i) => i).join(',')) bad++;       // 件不重不漏
      const sum = r.legs.reduce((a, l) => a + (l.tiles || 0), 0);
      if (Math.abs(sum - r.total) > 1e-6) bad++;
      r.legs.forEach(l => { if (l.cells && RC.assertRoute(model, l.cells, '').length) bad++; });
    }
  }
  ok('12 组 n=20/30 用例：件不重不漏、逐段和==总距离、每段只走通道、如实标非精确', bad === 0, bad ? bad + ' 处异常' : '');
  console.log('  最长耗时 ' + worstMs + ' ms（本机 Node）；总距离样例 ' + totals.slice(0, 4).map(t => t.toFixed(0)).join(' / '));
  ok('大件数耗时可控（单次 < 3000 ms）', worstMs < 3000, worstMs + ' ms');

  /* 大件数里含顺丰件：必须仍然出现「顺丰出库」停靠点，且排在所有 S 件之后 */
  let sfOk = 0;
  for (let t = 0; t < 6; t++) {
    const set = ['S1-1', 'S3-2-2628', 'S3-8'];
    let guard = 0;
    while (set.length < 20 && guard++ < 4000) {
      const c = poolAll[nextInt(poolAll.length)];
      if (!set.includes(c)) set.push(c);
    }
    const ps = set.map(c => { const p = locate(c); return { label: c, cell: p.cellPos, sf: /^S/.test(c), stub: p.stub }; });
    const r = RC.solve(ps, { model, entranceCell: entrance, sfGateCells: sfGates, sfExitGates: sfExitGates, normalGateCells: normalGates });
    const sfLeg = r.legs.filter(l => l.kind === 'sf').length;
    const sCount = ps.filter(x => x.sf).length;
    if (r.hasSf && sfLeg === 1 && r.sfAfter >= sCount && r.exitKind === 'normal') sfOk++;
  }
  ok('20 件里含 3 件顺丰：仍有 1 段顺丰出库、排在所有 S 件之后、末了走普通闸机出站', sfOk === 6, sfOk + '/6');
}

console.log('\n' + (fail ? '❌ 失败 ' + fail + ' 项' : '✅ 全部通过'));
process.exit(fail ? 1 : 0);
