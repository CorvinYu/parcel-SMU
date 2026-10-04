/* route-core.js — 海大快递站寻路核心（纯逻辑，浏览器与 Node 共用）
 * ============================================================================
 * 数据源：`docs/floorplan-corridors.json` —— 由用户 Excel 的**填充色**导出
 *   theme3 = 通道（8 条横向走廊 + 3 条纵向干线 + 北端 J/S 区通道）
 *   闸机带（C12:E16 / C17:E21 / C22:E57）作为「只用于出行」的可通行格
 *
 * 与旧版的区别（用户 2026-09-29 指出）：
 *   旧版把通道写成硬编码行号 + 手写「经主通道 / 经 V」取 min；漏了**西侧纵向通道**
 *   （F~J 列，lat −16~−13.5，与全部 8 条横走廊相交），也没有北端通道。
 *   现在：可走格直接来自 Excel ⇒ **图上最优 = 网格最短路**，不再有任何手写通道假设。
 *
 * 距离单位：**瓷砖**（1 单元格 = 0.5 瓷砖）。所有边权为 1 单元格 ⇒ 最短路用 BFS 即精确。
 * 排序：起点固定（入口）、终点自由（就近西侧闸机）⇒ 开放路径 TSP，Held–Karp 精确解。
 * 画线：直接用 BFS 回溯出的**单元格序列** ⇒ 每段都是相邻格 ⇒ 结构上不可能穿货架、不可能斜穿。
 * ==========================================================================*/
(function (root, factory) {
  var api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.RouteCore = api;
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
  'use strict';

  var CELL_TILES = 0.5;          // 1 单元格 = 0.5 瓷砖
  var EXACT_MAX = 16;            // ≤16 件走精确 Held–Karp（2^16×17×2 ≈ 2.2M 状态）；超过退化为两种子 + 2-opt 并如实标记

  /* ---------- 1) 建模：把通道 RLE 变成可走网格 ---------- */
  function buildModel(cor, opts) {
    opts = opts || {};
    /* 网格范围：通道自身 ∪ 闸机带 ∪ 货架区
       （闸机带在列 3~5，比通道最西列 6 还靠西；货架区可到列 131（Y 区），比通道最东列 94 还靠东。
        不并进来，出口格或 Y 区会整个落在网格外 —— 都踩过。） */
    var span = {
      minCol: cor.span.minCol, maxCol: cor.span.maxCol,
      minRow: cor.span.minRow, maxRow: cor.span.maxRow
    };
    (opts.gateSpans || []).forEach(function (g) {
      span.minRow = Math.min(span.minRow, g[0]); span.maxRow = Math.max(span.maxRow, g[1]);
      span.minCol = Math.min(span.minCol, g[2]); span.maxCol = Math.max(span.maxCol, g[3]);
    });
    (opts.shelfRects || []).forEach(function (rc) {
      span.minRow = Math.min(span.minRow, rc.r0); span.maxRow = Math.max(span.maxRow, rc.r1);
      span.minCol = Math.min(span.minCol, rc.c0); span.maxCol = Math.max(span.maxCol, rc.c1);
    });
    var W = span.maxCol - span.minCol + 1;
    var H = span.maxRow - span.minRow + 1;
    var kind = new Uint8Array(W * H);      // 0 不可走 / 1 通道(theme3) / 2 闸机带(仅出行)
    function at(r, c) {
      if (r < span.minRow || r > span.maxRow || c < span.minCol || c > span.maxCol) return -1;
      return (r - span.minRow) * W + (c - span.minCol);
    }
    (cor.walk || []).forEach(function (seg) {
      var r = seg[0], a = seg[1], b = seg[2];
      for (var c = a; c <= b; c++) { var i = at(r, c); if (i >= 0) kind[i] = 1; }
    });
    (opts.gateSpans || []).forEach(function (g) {
      var r0 = g[0], r1 = g[1], c0 = g[2], c1 = g[3];
      for (var r = r0; r <= r1; r++) for (var c = c0; c <= c1; c++) {
        var i = at(r, c); if (i >= 0 && kind[i] === 0) kind[i] = 2;
      }
    });

    var model = {
      span: span, W: W, H: H, kind: kind, at: at,
      latOf: function (c) { return (c - cor.spineCol) / 2; },
      depOf: function (r) { return (cor.baseRow - r) / 2; },
      colOf: function (l) { return cor.spineCol + 2 * l; },
      rowOf: function (d) { return cor.baseRow - 2 * d; },
      kindAt: function (r, c) { var i = at(r, c); return i < 0 ? 0 : kind[i]; },
      isWalk: function (r, c) { return this.kindAt(r, c) > 0; }
    };

    /* 预列可走格（通道格 + 闸机格），供「投影到最近通道格」用 */
    var cells = [], gates = [];
    for (var r = span.minRow; r <= span.maxRow; r++) {
      for (var c = span.minCol; c <= span.maxCol; c++) {
        var k = kind[at(r, c)];
        if (k === 1) cells.push([r, c]);
        else if (k === 2) gates.push([r, c]);
      }
    }
    model.walkCells = cells;
    model.gates = gates;

    /* 障碍格：墙（theme1）＋ 货架合并区。
       投影/连通必须绕开它们 —— 否则会「穿墙」把 J 柜列投到主通道里（J 区三面是墙、朝南开口）。 */
    var blocked = new Uint8Array(W * H);
    (cor.wall || []).forEach(function (seg) {
      var r = seg[0];
      for (var c = seg[1]; c <= seg[2]; c++) { var i = at(r, c); if (i >= 0) blocked[i] = 1; }
    });
    (opts.shelfRects || []).forEach(function (rc) {
      for (var r = rc.r0; r <= rc.r1; r++) for (var c = rc.c0; c <= rc.c1; c++) {
        var i = at(r, c); if (i >= 0) blocked[i] = 1;
      }
    });
    model.blocked = blocked;

    /* 从「一片格子」（货架/入口区域）出发，绕开墙与货架，找最近的**通道格**。
       同层内以「离该区域中心最近」决胜 ⇒ 结果稳定、可复现。 */
    model.nearestWalkFrom = function (srcCells, centerRow, centerCol) {
      var n = W * H;
      var dist = new Int32Array(n).fill(-1);
      var q = new Int32Array(n), qh = 0, qt = 0;
      (srcCells || []).forEach(function (rc) {
        var i = at(rc[0], rc[1]);
        if (i >= 0 && dist[i] < 0) { dist[i] = 0; q[qt++] = i; }
      });
      if (!qt) return null;
      while (qh < qt) {
        var cur = q[qh++];
        var cr = Math.floor(cur / W) + span.minRow, cc = (cur % W) + span.minCol;
        var nb = [[cr - 1, cc], [cr + 1, cc], [cr, cc - 1], [cr, cc + 1]];
        for (var k = 0; k < 4; k++) {
          var ni = at(nb[k][0], nb[k][1]);
          if (ni < 0 || dist[ni] !== -1 || blocked[ni]) continue;
          dist[ni] = dist[cur] + 1; q[qt++] = ni;
        }
      }
      var best = null, bd = Infinity, bt = Infinity;
      for (var i = 0; i < n; i++) {
        if (kind[i] <= 0 || dist[i] < 0) continue;
        var rr = Math.floor(i / W) + span.minRow, cc2 = (i % W) + span.minCol;
        var t = Math.sqrt((rr - centerRow) * (rr - centerRow) + (cc2 - centerCol) * (cc2 - centerCol));
        if (dist[i] < bd || (dist[i] === bd && t < bt)) { bd = dist[i]; bt = t; best = [rr, cc2]; }
      }
      if (!best) return null;
      return { row: best[0], col: best[1], cellDist: bd * CELL_TILES, hops: bd };
    };

    /* 把任意 (lat, depth) 投到最近的**通道格**（几何法，仅用于不需要绕墙的场合） */
    model.nearest = function (lat, depth) {
      var fc = model.colOf(lat), fr = model.rowOf(depth);
      var best = null, bd = Infinity;
      for (var i = 0; i < cells.length; i++) {
        var rr = cells[i][0], cc = cells[i][1];
        var d = (rr - fr) * (rr - fr) + (cc - fc) * (cc - fc);
        if (d < bd) { bd = d; best = cells[i]; }
      }
      return { row: best[0], col: best[1], cellDist: Math.sqrt(bd) * CELL_TILES };
    };

    /* 单元格 ↔ (lat, depth)：取格中心 */
    model.pointOf = function (r, c) {
      return { lat: (c - cor.spineCol + 0.5) / 2, depth: (cor.baseRow - r + 0.5) / 2 };
    };

    /* ---------- 2) 最短路：全网格 BFS（边权恒为 1 单元格 ⇒ BFS 即精确最短路） ---------- */
    model.bfs = function (srcRow, srcCol) {
      var n = W * H;
      var dist = new Int32Array(n).fill(-1);
      var prev = new Int32Array(n).fill(-1);
      var si = at(srcRow, srcCol);
      if (si < 0 || kind[si] === 0) return null;
      var q = new Int32Array(n), qh = 0, qt = 0;
      dist[si] = 0; q[qt++] = si;
      while (qh < qt) {
        var cur = q[qh++];
        var cr = Math.floor(cur / W) + span.minRow, cc = (cur % W) + span.minCol;
        var d1 = dist[cur] + 1;
        var nb = [[cr - 1, cc], [cr + 1, cc], [cr, cc - 1], [cr, cc + 1]];
        for (var k = 0; k < 4; k++) {
          var ni = at(nb[k][0], nb[k][1]);
          if (ni < 0 || kind[ni] === 0 || dist[ni] !== -1) continue;
          dist[ni] = d1; prev[ni] = cur; q[qt++] = ni;
        }
      }
      return { dist: dist, prev: prev };
    };

    /* BFS 回溯：源 → 目标 的完整单元格序列（保证每步相邻，故画线只会正交） */
    model.path = function (bfs, row, col) {
      var i = at(row, col);
      if (!bfs || i < 0 || bfs.dist[i] < 0) return null;
      var out = [];
      while (i >= 0) {
        out.push([Math.floor(i / W) + span.minRow, (i % W) + span.minCol]);
        i = bfs.prev[i];
      }
      out.reverse();
      return out;
    };
    return model;
  }

  /* ---------- 3) 通道结构自检：把可走格拆成「纵向干线」与「横向走廊」 ----------
   * 只统计**通道格**（kind===1）；闸机带（kind===2）属于出行设施，不算走廊。 */
  function analyze(model) {
    var sp = model.span, W = model.W;
    var trunks = [], corridors = [];
    /* 纵干：每列找连续可走段（≥8 行 = 4 瓷砖），再把「行段完全相同」的相邻列并起来 */
    var byRun = {};
    for (var c = sp.minCol; c <= sp.maxCol; c++) {
      var r = sp.minRow, run = null;
      for (r = sp.minRow; r <= sp.maxRow + 1; r++) {
        var w = r <= sp.maxRow && model.kindAt(r, c) === 1;
        if (w && !run) run = [r, r];
        else if (w) run[1] = r;
        else if (run) {
          if (run[1] - run[0] + 1 >= 8) {
            var key = run[0] + ':' + run[1];
            (byRun[key] = byRun[key] || []).push(c);
          }
          run = null;
        }
      }
    }
    Object.keys(byRun).forEach(function (key) {
      var cols = byRun[key].sort(function (a, b) { return a - b; });
      var a = cols[0], b = cols[0];
      for (var i = 1; i <= cols.length; i++) {
        if (i < cols.length && cols[i] === b + 1) { b = cols[i]; continue; }
        var p = key.split(':');
        trunks.push({ c0: a, c1: b, r0: +p[0], r1: +p[1] });
        if (i < cols.length) { a = b = cols[i]; }
      }
    });
    /* 横走廊：每行找连续可走段（≥8 列），把「列段完全相同」的相邻行并起来 */
    var byCols = {};
    for (var rr = sp.minRow; rr <= sp.maxRow; rr++) {
      var cc = sp.minCol, run2 = null;
      for (cc = sp.minCol; cc <= sp.maxCol + 1; cc++) {
        var w2 = cc <= sp.maxCol && model.kindAt(rr, cc) === 1;
        if (w2 && !run2) run2 = [cc, cc];
        else if (w2) run2[1] = cc;
        else if (run2) {
          if (run2[1] - run2[0] + 1 >= 8) {
            var k2 = run2[0] + ':' + run2[1];
            (byCols[k2] = byCols[k2] || []).push(rr);
          }
          run2 = null;
        }
      }
    }
    Object.keys(byCols).forEach(function (key) {
      var rows = byCols[key].sort(function (a, b) { return a - b; });
      var a = rows[0], b = rows[0];
      for (var i = 1; i <= rows.length; i++) {
        if (i < rows.length && rows[i] === b + 1) { b = rows[i]; continue; }
        var p = key.split(':');
        corridors.push({ c0: +p[0], c1: +p[1], r0: a, r1: b });
        if (i < rows.length) { a = b = rows[i]; }
      }
    });
    var overlap = function (rects) { return rects.filter(function (x) { return x.c1 - x.c0 > 3 && x.r1 - x.r0 > 3; }); };
    return { trunks: trunks, corridors: corridors, cross: overlap(trunks) };
  }

  /* ---------- 4) 精确排序：Held–Karp ＋ 顺丰出库先后约束 ----------
   * 起点：入口闸机（固定）。
   * 🔴 用户 2026-09-29 规则：
   *   ① 「只要拿了 S，一定要先从顺丰专用闸机出库；若同时还有普通件，可以在顺丰出库后再去
   *      普通货架，最后从普通闸机出库直接走人。」
   *   ② 「**顺丰的出库机不能出站，所以要走那个出站机出站**」——出库 ≠ 出站：
   *      - 有普通件 → 终点＝普通闸机（既是普通件出库，也是出站口）
   *      - 只有顺丰件 → 顺丰专用闸机出库 → 再走到**顺丰自取快递出口**出站，才结束
   *   ⇒ 这是**带先后约束**的开放路径 TSP：出库点必须在所有 S 件之后，且路线**永远终于出站口**。
   * `pick.stub`：件在自身区内要走的那一小段（如 J 柜列的纵深），逐段计入。
   */
  function solve(picks, opts) {
    var model = opts.model, entranceCell = opts.entranceCell;
    var defaultGates = opts.gateCells || [];
    var sfCells = opts.sfGateCells || [];                 // 顺丰**出库**节点（单格）
    var sfExitGates = opts.sfExitGates || [];             // 顺丰侧**出站**闸机（只有顺丰件时的终点）
    var normalGates = opts.normalGateCells || defaultGates;
    var n = picks.length;
    var stubOf = function (p) { return p.stub || 0; };            // 区内走位，单位：**瓷砖**
    /* ⚠️ DP 内部一律用**单元格**记账，最后才 ×CELL_TILES；
       所以 stub 必须先换算成单元格，否则会被最后那次 ×0.5 少算一半（踩过）。 */
    var sc = function (p) { return (p.stub || 0) / CELL_TILES; };
    if (!n) return { order: [], total: 0, exact: true, legs: [], sequence: [], sfAfter: -1 };

    var srcs = [{ key: 'entrance', row: entranceCell.row, col: entranceCell.col }].concat(
      picks.map(function (p) { return { key: 'p', row: p.cell.row, col: p.cell.col }; }));
    var bfs = srcs.map(function (s) { return model.bfs(s.row, s.col); });

    function cellDistTo(i, row, col) {
      var b = bfs[i], idx = model.at(row, col);
      if (!b || idx < 0 || b.dist[idx] < 0) return Infinity;
      return b.dist[idx];
    }
    /* 从第 i 个源（0=入口，i+1=第 i 件）到一组格子里最近的那个：距离 + 目标格 */
    function nearestIn(i, cells) {
      var best = Infinity, bc = null;
      for (var k = 0; k < cells.length; k++) {
        var d = cellDistTo(i, cells[k][0], cells[k][1]);
        if (d < best) { best = d; bc = cells[k]; }
      }
      return { d: best, cell: bc };
    }
    /* 两组格子之间的最短距离（对 A 逐点 BFS，取到 B 的最近） */
    function minBetween(cellsA, cellsB) {
      var best = Infinity;
      for (var a = 0; a < cellsA.length; a++) {
        var b = model.bfs(cellsA[a][0], cellsA[a][1]);
        for (var k = 0; k < cellsB.length; k++) {
          var idx = model.at(cellsB[k][0], cellsB[k][1]);
          if (idx >= 0 && b.dist[idx] >= 0 && b.dist[idx] < best) best = b.dist[idx];
        }
      }
      return best;
    }
    function pair(i, j) {
      return sc(picks[i]) + cellDistTo(i + 1, picks[j].cell.row, picks[j].cell.col) + sc(picks[j]);
    }

    var sfMask = 0, sfCount = 0;
    picks.forEach(function (p, i) { if (p.sf) { sfMask |= (1 << i); sfCount++; } });
    var hasSf = sfCount > 0;
    var hasNormal = picks.some(function (p) { return !p.sf; });
    if (hasSf && !sfCells.length) throw new Error('有顺丰件但没提供顺丰出库闸机格');

    var fromEntrance = picks.map(function (p, j) {
      return cellDistTo(0, p.cell.row, p.cell.col) + sc(p);
    });
    var toNormal = picks.map(function (p, i) {
      var r = nearestIn(i + 1, normalGates);
      if (r.d === Infinity) throw new Error('件 ' + (p.label || i) + ' 到普通闸机不可达');
      return r.d + sc(p);
    });
    var toSf = picks.map(function (p, i) {
      var r = nearestIn(i + 1, sfCells);
      if (r.d === Infinity) throw new Error('件 ' + (p.label || i) + ' 到顺丰出库闸机不可达');
      return r.d + sc(p);
    });
    var sfToNormal = hasSf ? minBetween(sfCells, normalGates) : Infinity;   // 出库后走到普通闸机
    var sfToExit = hasSf ? minBetween(sfCells, sfExitGates) : Infinity;     // 出库后走到顺丰侧出站机
    if (hasSf && !hasNormal && !sfExitGates.length) throw new Error('只有顺丰件时必须提供顺丰侧出站闸机');

    var total, order, seqNodes, sfAfter = -1;
    if (n <= EXACT_MAX) {
      /* 状态：dp[mask][last][sfDone]，last ∈ 0..n-1 为件、n 为顺丰出库点 */
      var full = 1 << n, INF = Infinity, L = n + 1;
      var id = function (m, l, s) { return ((m * L + l) << 1) | s; };
      var dp = new Float64Array(full * L * 2).fill(INF);
      var par = new Int32Array(full * L * 2).fill(-1);
      for (var i2 = 0; i2 < n; i2++) dp[id(1 << i2, i2, 0)] = fromEntrance[i2];
      for (var mask = 1; mask < full; mask++) {
        for (var last = 0; last < L; last++) {
          for (var sfl = 0; sfl < 2; sfl++) {
            var cur = dp[id(mask, last, sfl)];
            if (cur >= INF) continue;
            if (last === n) {
              /* 已出库：只能继续去没取的件 */
              for (var x1 = 0; x1 < n; x1++) {
                if (mask & (1 << x1)) continue;
                var r1 = nearestIn(x1 + 1, sfCells);          // 对称：件 x1 到顺丰出库点的距离
                var nv1 = cur + r1.d + sc(picks[x1]);
                var t1 = id(mask | (1 << x1), x1, 1);
                if (nv1 < dp[t1]) { dp[t1] = nv1; par[t1] = last; }
              }
            } else {
              for (var x2 = 0; x2 < n; x2++) {
                if (mask & (1 << x2)) continue;
                var nv2 = cur + pair(last, x2);
                var t2 = id(mask | (1 << x2), x2, sfl);
                if (nv2 < dp[t2]) { dp[t2] = nv2; par[t2] = last; }
              }
              /* 去顺丰出库：必须所有 S 件都取完，且尚未出库 */
              if (hasSf && !sfl && (mask & sfMask) === sfMask) {
                var nv3 = cur + toSf[last];
                var t3 = id(mask, n, 1);
                if (nv3 < dp[t3]) { dp[t3] = nv3; par[t3] = last; }
              }
            }
          }
        }
      }
      var best = INF, bLast = -1, bSf = 0;
      for (var l2 = 0; l2 < L; l2++) {
        for (var s3 = 0; s3 < 2; s3++) {
          var v = dp[id(full - 1, l2, s3)];
          if (v >= INF) continue;
          if (hasSf && !s3) continue;                     // 拿了 S 却没出库 ⇒ 非法
          var endCost;
          if (hasNormal) endCost = (l2 === n) ? sfToNormal : toNormal[l2];
          else {
            /* 只有顺丰件：出库 ≠ 出站 ⇒ 必须在出库之后**再走到顺丰侧出站机** */
            if (l2 !== n) continue;
            endCost = sfToExit;
          }
          if (v + endCost < best) { best = v + endCost; bLast = l2; bSf = s3; }
        }
      }
      if (bLast < 0) throw new Error('无可行顺序（检查闸机带与先后约束）');
      /* 回溯：seqNodes 为含顺丰出库点的节点序列 */
      seqNodes = [];
      var mk = full - 1, cLast = bLast, cSf = bSf;
      while (cLast >= 0) {
        seqNodes.push({ node: cLast, sf: cSf });
        var pidx = id(mk, cLast, cSf);
        var pv = par[pidx];
        if (cLast === n) cSf = 0; else mk &= ~(1 << cLast);
        cLast = pv;
      }
      seqNodes.reverse();
      total = best;
    } else {
      /* 启发式（>EXACT_MAX 件）：S 件块 → 顺丰出库 → 普通件块。
         普通件块用**两个种子**各跑一遍 2-opt 取优者：
           ① 最近邻（聚簇场景好）② **走廊扫描**（按投影行从入口往里、同一走廊内按横向走） */
      var sList = [], nList = [];
      picks.forEach(function (p, i) { (p.sf ? sList : nList).push(i); });
      function nn(list, fromSf) {
        var left = list.slice(), seq = [], cu = -1;
        while (left.length) {
          var bi = 0, bv = Infinity;
          for (var k = 0; k < left.length; k++) {
            var x = left[k], cand;
            if (cu >= 0) cand = pair(cu, x);
            else if (fromSf) cand = nearestIn(x + 1, sfCells).d + sc(picks[x]);
            else cand = fromEntrance[x];
            if (cand < bv) { bv = cand; bi = k; }
          }
          seq.push(left[bi]); cu = left[bi]; left.splice(bi, 1);
        }
        return seq;
      }
      function seqCost(sS, nS) {
        var t = 0, cu = -1;
        sS.forEach(function (x) { t += (cu < 0) ? fromEntrance[x] : pair(cu, x); cu = x; });
        if (hasSf) {
          t += toSf[cu];                                        // 含最后一件的 stub
          if (nS.length) {
            t += nearestIn(nS[0] + 1, sfCells).d + sc(picks[nS[0]]);
            var cu2 = -1;
            nS.forEach(function (x) { if (cu2 >= 0) t += pair(cu2, x); cu2 = x; });
            t += toNormal[cu2];
          } else {
            t += sfToExit;                                      // 只有顺丰件：出库 ≠ 出站，还要出站
          }
        } else {
          var cu3 = -1;
          nS.forEach(function (x) { t += (cu3 < 0) ? fromEntrance[x] : pair(cu3, x); cu3 = x; });
          t += toNormal[cu3];
        }
        return t;
      }
      var sSeq = nn(sList, false);
      var nSeq = nn(nList, hasSf);
      function twoOpt(arr, which) {
        var improved = true;
        while (improved) {
          improved = false;
          for (var a = 0; a < arr.length; a++) for (var b = a + 1; b < arr.length; b++) {
            var cand = arr.slice(0, a).concat(arr.slice(a, b + 1).reverse(), arr.slice(b + 1));
            var cur = (which === 's') ? seqCost(arr, nSeq) : seqCost(sSeq, arr);
            var nw = (which === 's') ? seqCost(cand, nSeq) : seqCost(sSeq, cand);
            if (nw < cur - 1e-9) { arr.length = 0; Array.prototype.push.apply(arr, cand); improved = true; }
          }
        }
      }
      twoOpt(nSeq, 'n');
      var sweep = nList.slice().sort(function (a, b) {
        if (picks[a].cell.row !== picks[b].cell.row) return picks[b].cell.row - picks[a].cell.row;
        return picks[a].cell.col - picks[b].cell.col;
      });
      twoOpt(sweep, 'n');
      if (seqCost(sSeq, sweep) < seqCost(sSeq, nSeq)) nSeq = sweep;
      twoOpt(sSeq, 's');
      order = sSeq.concat(nSeq);
      seqNodes = [];
      sSeq.forEach(function (x) { seqNodes.push({ node: x, sf: 0 }); });
      if (hasSf) seqNodes.push({ node: n, sf: 1 });
      nSeq.forEach(function (x) { seqNodes.push({ node: x, sf: hasSf ? 1 : 0 }); });
      total = seqCost(sSeq, nSeq);
    }

    /* ---------- 段路径（画线 + 断言用）：直接用图的单元格序列 ---------- */
    var legs = [], cellsOut = [], cursor = null, sfRowCol = null;
    function pushLeg(from, to, cells, tiles, kind) {
      legs.push({ from: from, to: to, cells: cells, tiles: tiles, kind: kind });
      cellsOut = cellsOut.concat(cells || []);
    }
    /* 入口 → 第一站 */
    var firstNode = seqNodes[0].node;
    if (firstNode === n) {
      var r0 = nearestIn(0, sfCells);
      var p0 = model.path(bfs[0], r0.cell[0], r0.cell[1]);
      pushLeg('入口', '顺丰出库', p0, r0.d * CELL_TILES, 'sf');
      sfRowCol = r0.cell; cursor = 'sf';
    } else {
      var pa = model.path(bfs[0], picks[firstNode].cell.row, picks[firstNode].cell.col);
      pushLeg('入口', picks[firstNode].label, pa,
        (pa ? (pa.length - 1) * CELL_TILES : 0) + stubOf(picks[firstNode]), 'entrance');
      cursor = firstNode;
    }
    for (var k = 1; k < seqNodes.length; k++) {
      var nd = seqNodes[k].node;
      if (cursor === 'sf') {
        /* 出库 → 件 nd：用「件→出库」最短路反向（无向图，对称） */
        var rr = nearestIn(nd + 1, sfCells);
        var pth = model.path(bfs[nd + 1], rr.cell[0], rr.cell[1]);
        pth = (pth || []).slice().reverse();
        pushLeg('顺丰出库', picks[nd].label, pth, rr.d * CELL_TILES + stubOf(picks[nd]), 'pick');
        cursor = nd;
      } else if (nd === n) {
        var rs = nearestIn(cursor + 1, sfCells);
        var ps = model.path(bfs[cursor + 1], rs.cell[0], rs.cell[1]);
        pushLeg(picks[cursor].label, '顺丰出库', ps, rs.d * CELL_TILES + stubOf(picks[cursor]), 'sf');
        sfRowCol = rs.cell; cursor = 'sf';
      } else {
        var pp = model.path(bfs[cursor + 1], picks[nd].cell.row, picks[nd].cell.col);
        pushLeg(picks[cursor].label, picks[nd].label, pp,
          (pp ? (pp.length - 1) * CELL_TILES : 0) + stubOf(picks[cursor]) + stubOf(picks[nd]), 'pick');
        cursor = nd;
      }
    }
    /* 终点：有普通件 → 普通闸机；否则停在顺丰出库点 */
    var bestGate = sfRowCol, exitKind = 'sf';
    if (hasNormal) {
      if (cursor === 'sf') {
        /* 出库后走到普通闸机：取最近的一对（顺丰格 → 普通格） */
        var bpair = null, bd = Infinity;
        for (var a3 = 0; a3 < sfCells.length; a3++) {
          var bb = model.bfs(sfCells[a3][0], sfCells[a3][1]);
          for (var g3 = 0; g3 < normalGates.length; g3++) {
            var ix = model.at(normalGates[g3][0], normalGates[g3][1]);
            if (ix >= 0 && bb.dist[ix] >= 0 && bb.dist[ix] < bd) { bd = bb.dist[ix]; bpair = { sf: sfCells[a3], g: normalGates[g3] }; }
          }
        }
        var pe = model.path(model.bfs(bpair.sf[0], bpair.sf[1]), bpair.g[0], bpair.g[1]);
        pushLeg('顺丰出库', '普通闸机出库', pe, (pe ? (pe.length - 1) * CELL_TILES : 0), 'exit');
        bestGate = bpair.g;
      } else {
        var rg = nearestIn(cursor + 1, normalGates);
        var pg = model.path(bfs[cursor + 1], rg.cell[0], rg.cell[1]);
        pushLeg(picks[cursor].label, '普通闸机出库', pg, rg.d * CELL_TILES + stubOf(picks[cursor]), 'exit');
        bestGate = rg.cell;
      }
      exitKind = 'normal';
    } else if (hasSf) {
      /* 只有顺丰件：出库 ≠ 出站 ⇒ 出库后还要走到顺丰侧出站机才结束 */
      var bex = null, bd2 = Infinity;
      for (var a4 = 0; a4 < sfCells.length; a4++) {
        var b4 = model.bfs(sfCells[a4][0], sfCells[a4][1]);
        for (var g4 = 0; g4 < sfExitGates.length; g4++) {
          var ix4 = model.at(sfExitGates[g4][0], sfExitGates[g4][1]);
          if (ix4 >= 0 && b4.dist[ix4] >= 0 && b4.dist[ix4] < bd2) { bd2 = b4.dist[ix4]; bex = { sf: sfCells[a4], g: sfExitGates[g4] }; }
        }
      }
      var pex = model.path(model.bfs(bex.sf[0], bex.sf[1]), bex.g[0], bex.g[1]);
      pushLeg('顺丰出库', '顺丰侧出站机', pex, (pex ? (pex.length - 1) * CELL_TILES : 0), 'exit');
      bestGate = bex.g;
      exitKind = 'sfExit';
    }

    order = seqNodes.filter(function (x) { return x.node !== n; }).map(function (x) { return x.node; });
    for (var z = 0, cnt = 0; z < seqNodes.length; z++) {
      if (seqNodes[z].node === n) { sfAfter = cnt; break; }
      cnt++;
    }
    return {
      order: order, total: total * CELL_TILES, exact: n <= EXACT_MAX,
      legs: legs, cells: cellsOut, gateExit: bestGate, exitKind: exitKind,
      sfGate: sfRowCol, sfAfter: sfAfter, hasSf: hasSf, hasNormal: hasNormal,
      bfs: bfs, srcs: srcs
    };
  }

  /* ---------- 5) 取件码定位：完全由 Excel 合并区坐标驱动 ----------
   * 形如 `D8-6` / `S3-2-2628` / `J5-21` / `Y8-1-3` / `A4-1`。
   *
   * 🔴 2026-10-03 换用用户 10-02 更新版地图后，Excel 里**每个货格都有自己的合并区**
   *    （`S1-1`…`S1-10`、`Y1-1`…`Y9`、`Y8-2-1`…），原来那个笼统的 `S1` 标签**已不存在**。
   *    因此定位改为三级优先（与 App 端 `PickupRoute.locate` 完全一致）：
   *      ① **子位精确**：`S1-8` / `Y5-3` / `Y8-2-1` —— 命中该货格自己的合并区
   *      ② **货架级并集**：`S1` = 所有 `S1-*` 的并集（旧图是单个大块，新图被拆成多个小块）
   *      ③ **区级兜底**：`S区域` / `Y区域` ⇒ `approx = true`
   *    只做 ②③ 的话，`S3-2-2628` 这类取件码（货架号只有 S3）在新图上会定位失败。
   *
   * 精度补充（在 ②③ 时使用）：
   *    - **S 顺丰**：s1 货位 1~10、s2/s3 各 1~8，**左端为 1 向右递增** ⇒ 按格号取横向位置
   *    - **J 柜列**：沿列**由外端（靠通道）向里递增**，每列 ≥21 格 ⇒ 按格号取纵深，并把 `stub` 计入走位
   *    - **Y 大件**：货架内按子位等比铺开
   */
  function makeLocator(cor, model, opts) {
    opts = opts || {};
    var jCells = opts.jCells || 21;                 // J 每列格数（未实测，占位）
    var byLabel = {};
    (cor.rects || []).forEach(function (r) {
      if (!r.label) return;
      var k = String(r.label).trim().toUpperCase();
      var e = byLabel[k];
      if (!e) byLabel[k] = { label: k, c0: r.c0, c1: r.c1, r0: r.r0, r1: r.r1, lat0: r.lat0, lat1: r.lat1, d0: r.d0, d1: r.d1 };
      else {
        e.c0 = Math.min(e.c0, r.c0); e.c1 = Math.max(e.c1, r.c1);
        e.r0 = Math.min(e.r0, r.r0); e.r1 = Math.max(e.r1, r.r1);
        e.lat0 = Math.min(e.lat0, r.lat0); e.lat1 = Math.max(e.lat1, r.lat1);
        e.d0 = Math.min(e.d0, r.d0); e.d1 = Math.max(e.d1, r.d1);
      }
    });

    /* 前缀并集：查 `S1` 时并集所有 `S1-*`（只认 `-` 分隔，避免 S1 误匹配 S10） */
    function byPrefix(key) {
      if (byLabel[key]) return byLabel[key];
      var pre = key + '-', acc = null;
      Object.keys(byLabel).forEach(function (k) {
        if (k.indexOf(pre) !== 0) return;
        var r = byLabel[k];
        if (!acc) acc = { label: key, c0: r.c0, c1: r.c1, r0: r.r0, r1: r.r1, lat0: r.lat0, lat1: r.lat1, d0: r.d0, d1: r.d1 };
        else {
          acc.c0 = Math.min(acc.c0, r.c0); acc.c1 = Math.max(acc.c1, r.c1);
          acc.r0 = Math.min(acc.r0, r.r0); acc.r1 = Math.max(acc.r1, r.r1);
          acc.lat0 = Math.min(acc.lat0, r.lat0); acc.lat1 = Math.max(acc.lat1, r.lat1);
          acc.d0 = Math.min(acc.d0, r.d0); acc.d1 = Math.max(acc.d1, r.d1);
        }
      });
      return acc;
    }

    return function (raw) {
      var t = String(raw).trim().toUpperCase();
      var m = t.match(/^([A-Z])\s*(\d{1,2})(?:\s*[-－–—]\s*(\d{1,3}))?(?:\s*[-－–—]\s*(\d{1,4}))?$/);
      if (!m) return null;
      var letter = m[1], shelf = +m[2], cell = m[3] ? +m[3] : null, sub = m[4] ? +m[4] : null;
      var shelfLabel = letter + shelf;

      /* ① 子位精确命中 */
      var cellRect = null;
      if (cell !== null) {
        var base = (sub !== null) ? shelfLabel + '-' + cell : shelfLabel;
        var idx = (sub !== null) ? sub : cell;
        cellRect = byLabel[(base + '-' + idx).toUpperCase()] || null;
      }
      /* ② 货架级并集 */
      var shelfRect = byPrefix(shelfLabel);
      /* ③ 区级兜底 */
      var e = cellRect || shelfRect || byPrefix(letter + '区域') || byPrefix(letter + '区');
      if (!e) return null;
      var approx = !cellRect && !shelfRect;

      /* ---- 区内格位 → 精确坐标 ---- */
      var lat = (e.lat0 + e.lat1) / 2, depth = (e.d0 + e.d1) / 2;
      var posNote = '';
      if (cellRect) {
        posNote = shelfLabel + ' 第' + cell + '格（地图精确格位）';
      } else if (letter === 'S') {
        var sn = (shelf === 1) ? 10 : 8;                       // s1=10 格、s2/s3=8 格（实测锚点）
        var sk = Math.min(Math.max((cell || 1) - 1, 0), sn - 1);
        lat = e.lat0 + (sn > 1 ? sk / (sn - 1) : 0) * (e.lat1 - e.lat0);
        posNote = 's' + shelf + ' 第' + (cell || 1) + '格（' + sn + ' 格中，左端为 1）';
      } else if (letter === 'J') {
        var jk = Math.min(Math.max((cell || 1) - 1, 0), jCells - 1);
        depth = e.d0 + (jCells > 1 ? jk / (jCells - 1) : 0) * (e.d1 - e.d0);   // d0 = 靠通道的外端
        posNote = 'j' + shelf + ' 第' + (cell || 1) + '格（沿列由外端向里，按 ' + jCells + ' 格铺开）';
      } else if (letter === 'Y') {
        var yn = (shelf >= 8) ? 3 : 4;
        var yk = Math.min(Math.max((cell || 1) - 1, 0), yn - 1);
        lat = e.lat0 + (yn > 1 ? yk / (yn - 1) : 0) * (e.lat1 - e.lat0);
        posNote = 'y' + shelf + ' 第' + (cell || 1) + '格（共 ' + yn + ' 格）';
      }
      /* 投影：从整个合并区出发、绕开墙与货架的 BFS（不允许穿墙）。
         ⚠️ 决胜基准必须是**按格位算出来的精确点**，不能是合并区中心 ——
         否则 S 区同一货架的不同格会全部投到同一格，段距恒为 0（踩过）。
         🔴 J 柜列只在**南端开口**，必须限定从开口投影（否则会从柜列东侧通道穿出去）。 */
      var src = [];
      if (letter === 'J') {
        for (var c2 = e.c0; c2 <= e.c1; c2++) {
          var kk = model.kindAt ? model.kindAt(e.r1 + 1, c2) : null;
          if (kk === 1) src.push([e.r1 + 1, c2]);
        }
      }
      if (!src.length) {
        for (var r = e.r0; r <= e.r1; r++) for (var c = e.c0; c <= e.c1; c++) src.push([r, c]);
      }
      var near = model.nearestWalkFrom(src, model.rowOf(depth), model.colOf(lat));
      if (!near) return null;
      /* 区内那一段（投影格 ↔ 取件格）走位：J 柜列的纵深必须算进距离，其余近似为 0。
         🔴 不能用 Math.abs(depth - pc.depth)：柜列两侧都有通道时 BFS 会把不同格投到不同行，
         abs 会让 stub 不再随格位单调递增（2026-10-03 被单测抓到 0.50→0.30→0.30→0.50）。
         正确做法：以柜列**南端开口**（e.d0）为固定基准。 */
      var pc = model.pointOf(near.row, near.col);
      var stub = (letter === 'J') ? Math.max(depth - e.d0, 0) : 0;
      return {
        code: raw, letter: letter, shelf: shelf, cell: cell, sub: sub, rect: e, approx: approx,
        lat: lat, depth: depth, cellPos: near, stub: stub,
        label: letter + ' 排 ' + shelf + ' 号货架' + (cell ? ' 第' + cell + '格' : '')
          + (posNote ? '　' + posNote : '')
          + '（lat ' + lat.toFixed(1) + '，深 ' + depth.toFixed(1) + ' → 通道格 '
          + near.row + ',' + near.col + (stub > 0.01 ? '，区内走位 ' + stub.toFixed(1) + ' 格' : '') + '）'
          + (approx ? '　⚠ 该区在精确版里未细分，只定位到最近通道点' : '')
      };
    };
  }

  /* ---------- 6) 交付前断言：路线必须严格落在通道格上、每步必须相邻 ---------- */
  function assertRoute(model, cells, label) {
    var errs = [];
    for (var i = 0; i < cells.length; i++) {
      var r = cells[i][0], c = cells[i][1];
      if (model.kindAt(r, c) === 0) errs.push((label || '') + ' 第' + i + '点 (' + r + ',' + c + ') 不在通道上');
      if (i) {
        var dr = Math.abs(r - cells[i - 1][0]), dc = Math.abs(c - cells[i - 1][1]);
        if (dr + dc !== 1) errs.push((label || '') + ' 第' + i + '点与前点不相邻 (dr=' + dr + ',dc=' + dc + ') ⇒ 会斜穿或跳格');
      }
    }
    return errs;
  }

  return {
    CELL_TILES: CELL_TILES, EXACT_MAX: EXACT_MAX,
    buildModel: buildModel, analyze: analyze, solve: solve,
    makeLocator: makeLocator, assertRoute: assertRoute
  };
});
