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
  var EXACT_MAX = 13;            // ≤13 件走精确 Held–Karp，超过退化为 NN + 2-opt（并如实标记）

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

  /* ---------- 4) 精确排序：Held–Karp（起点固定=入口，终点自由=就近闸机） ---------- */
  function solve(picks, opts) {
    var model = opts.model, entranceCell = opts.entranceCell;
    /* 出口闸机**按件分工**（用户 Excel 的三处闸机标签）：
     *   普通件 → 「7号普通闸机」（C22:E57，需扫快递与取件码条码）
     *   顺丰件（S 开头）→ 「顺丰专用闸机」C17:E21 / 「顺丰自取快递出口」C12:E16
     * opts.gatesOf(pick, i) 可覆盖；缺省则所有件共用 opts.gateCells。 */
    var defaultGates = opts.gateCells || [];
    var gatesOf = opts.gatesOf || function () { return defaultGates; };
    var n = picks.length;
    if (!n) return { order: [], total: 0, exact: true, legs: [] };

    var srcs = [{ key: 'entrance', row: entranceCell.row, col: entranceCell.col }].concat(
      picks.map(function (p, i) { return { key: 'p' + i, row: p.cell.row, col: p.cell.col }; }));
    var bfs = srcs.map(function (s) { return model.bfs(s.row, s.col); });

    function cellDistTo(i, row, col) {
      var b = bfs[i], idx = model.at(row, col);
      if (!b || idx < 0 || b.dist[idx] < 0) return Infinity;
      return b.dist[idx];
    }
    function pair(i, j) {   // i,j 为 picks 下标；0 号源是入口
      return cellDistTo(i + 1, picks[j].cell.row, picks[j].cell.col);
    }
    var fromEntrance = picks.map(function (p, j) { return cellDistTo(0, p.cell.row, p.cell.col); });
    var toGate = picks.map(function (p, i) {
      var G = gatesOf(p, i), best = Infinity;
      for (var g = 0; g < G.length; g++) {
        var d = cellDistTo(i + 1, G[g][0], G[g][1]);
        if (d < best) best = d;
      }
      if (best === Infinity) throw new Error('件 ' + (p.label || i) + ' 没有任何可达的出口闸机');
      return best;
    });

    var i2, j2, total, order;
    if (n <= EXACT_MAX) {
      var full = 1 << n, INF = Infinity;
      var dp = [], par = [];
      for (i2 = 0; i2 < full; i2++) { dp.push(new Array(n).fill(INF)); par.push(new Array(n).fill(-1)); }
      for (i2 = 0; i2 < n; i2++) dp[1 << i2][i2] = fromEntrance[i2];
      for (var mask = 1; mask < full; mask++) {
        for (var last = 0; last < n; last++) {
          var v = dp[mask][last];
          if (v >= INF) continue;
          for (var x = 0; x < n; x++) {
            if (mask & (1 << x)) continue;
            var nm = mask | (1 << x), nv = v + pair(last, x);
            if (nv < dp[nm][x]) { dp[nm][x] = nv; par[nm][x] = last; }
          }
        }
      }
      var best = INF, bl = -1;
      for (i2 = 0; i2 < n; i2++) {
        var t = dp[full - 1][i2] + toGate[i2];
        if (t < best) { best = t; bl = i2; }
      }
      if (bl < 0) throw new Error('无可行顺序：出口不可达（闸机格数=' + defaultGates.length + '）');
      /* 回溯顺序 */
      order = []; var mk = full - 1, cur = bl;
      while (cur >= 0) { order.push(cur); var pv = par[mk][cur]; mk &= ~(1 << cur); cur = pv; }
      order.reverse();
      total = best;
    } else {
      /* 启发式：最近邻 + 2-opt（如实标记 exact=false，不假装最优） */
      var left = picks.map(function (_, i) { return i; }), seq = [], cu = -1;
      while (left.length) {
        var bi = 0, bv = Infinity;
        for (var k = 0; k < left.length; k++) {
          var cand = cu < 0 ? fromEntrance[left[k]] : pair(cu, left[k]);
          if (cand < bv) { bv = cand; bi = k; }
        }
        seq.push(left[bi]); cu = left[bi]; left.splice(bi, 1);
      }
      function cost(s) {
        var t2 = fromEntrance[s[0]];
        for (var q = 0; q + 1 < s.length; q++) t2 += pair(s[q], s[q + 1]);
        return t2 + toGate[s[s.length - 1]];
      }
      var improved = true;
      while (improved) {
        improved = false;
        for (var a1 = 0; a1 < seq.length; a1++) for (var b1 = a1 + 1; b1 < seq.length; b1++) {
          var cand2 = seq.slice(0, a1).concat(seq.slice(a1, b1 + 1).reverse(), seq.slice(b1 + 1));
          if (cost(cand2) < cost(seq) - 1e-9) { seq = cand2; improved = true; }
        }
      }
      order = seq; total = cost(seq);
    }

    /* 逐段单元格路径（画线 + 断言用） */
    var legs = [], cellsOut = [];
    var entrancePath = model.path(bfs[0], picks[order[0]].cell.row, picks[order[0]].cell.col);
    legs.push({ from: '入口', to: picks[order[0]].label, cells: entrancePath, tiles: entrancePath ? (entrancePath.length - 1) * CELL_TILES : null });
    cellsOut = cellsOut.concat(entrancePath || []);
    for (var q2 = 0; q2 + 1 < order.length; q2++) {
      var pth = model.path(bfs[order[q2] + 1], picks[order[q2 + 1]].cell.row, picks[order[q2 + 1]].cell.col);
      legs.push({ from: picks[order[q2]].label, to: picks[order[q2 + 1]].label, cells: pth, tiles: pth ? (pth.length - 1) * CELL_TILES : null });
      cellsOut = cellsOut.concat(pth || []);
    }
    var lastPick = order[order.length - 1];
    var lastGates = gatesOf(picks[lastPick], lastPick);
    var bestGate = null, bg = Infinity;
    for (var g2 = 0; g2 < lastGates.length; g2++) {
      var gd = cellDistTo(lastPick + 1, lastGates[g2][0], lastGates[g2][1]);
      if (gd < bg) { bg = gd; bestGate = lastGates[g2]; }
    }
    var exitPath = model.path(bfs[lastPick + 1], bestGate[0], bestGate[1]);
    legs.push({ from: picks[lastPick].label, to: '闸机出口', cells: exitPath, tiles: exitPath ? (exitPath.length - 1) * CELL_TILES : null });
    cellsOut = cellsOut.concat(exitPath || []);

    return {
      order: order, total: total * CELL_TILES, exact: n <= EXACT_MAX,
      legs: legs, cells: cellsOut, gateExit: bestGate, bfs: bfs, srcs: srcs, orderIdx: order
    };
  }

  /* ---------- 5) 取件码定位：完全由 Excel 合并区坐标驱动 ----------
   * 形如 `D8-6` / `S3-2-2628` / `J5-21` / `Y8-1-3` / `A4-1`；
   * 命中合并区（如 `D8`）⇒ 取该区中心，再投影到最近通道格。
   * Y 区在用户精确版里是**一整块**（`Y区域`）⇒ 只能定位到东侧通道的最近点（已在界面注明）。
   */
  function makeLocator(cor, model) {
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
    return function (raw) {
      var t = String(raw).trim().toUpperCase();
      var m = t.match(/^([A-Z])\s*(\d{1,2})(?:\s*[-－–—]\s*(\d{1,3}))?(?:\s*[-－–—]\s*(\d{1,4}))?$/);
      if (!m) return null;
      var letter = m[1], shelf = +m[2], cell = m[3] ? +m[3] : null, sub = m[4] ? +m[4] : null;
      var e = byLabel[letter + shelf];
      var approx = false;
      if (!e) { e = byLabel[letter + '区域'] || byLabel[letter + '区']; approx = !!e; }
      if (!e) return null;
      var lat = (e.lat0 + e.lat1) / 2, depth = (e.d0 + e.d1) / 2;
      /* 投影：从整个合并区出发、绕开墙与货架的 BFS（不允许穿墙） */
      var src = [];
      for (var r = e.r0; r <= e.r1; r++) for (var c = e.c0; c <= e.c1; c++) src.push([r, c]);
      var near = model.nearestWalkFrom(src, (e.r0 + e.r1 + 1) / 2, (e.c0 + e.c1 + 1) / 2);
      if (!near) return null;
      return {
        code: raw, letter: letter, shelf: shelf, cell: cell, sub: sub, rect: e, approx: approx,
        lat: lat, depth: depth, cellPos: near,
        label: letter + ' 排 ' + shelf + ' 号货架' + (cell ? ' 第' + cell + '格' : '')
          + '（lat ' + lat.toFixed(1) + '，深 ' + depth.toFixed(1) + ' → 投影到通道格 '
          + near.row + ',' + near.col + '）' + (approx ? '　⚠ 该区在精确版里是一整块，只定位到最近通道点' : '')
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
