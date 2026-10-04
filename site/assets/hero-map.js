/* hero-map.js — 下载页首屏的「动态取件路线」地图
 * ============================================================================
 * 画法照着 **.devtools/map-prototype-template.html**（就是那份「A 清爽浅色 / B 夜跑深色」的
 * 路线图风格原型）搬过来的：柔和渐变底 + 点阵底纹 + 货架圆角块（浅色带投影）+ 虚线场地外框 +
 * 路线三段着色（已走 / 这一段带光晕 / 待走）+ 聚焦聚光灯 + 站点圆点与呼吸光晕 + 下一步箭头 +
 * 相机跟随。调色板直接沿用原型的两套（与 App 的 RouteMiniMap 同源）。
 *
 * 数据与寻路**不重写**，复用仓库权威件：
 *   · assets/venue-model.js = docs/floorplan-corridors.json（用户 Excel 填充色导出，3362 可走格）
 *   · assets/route-core.js  = .devtools/route-core.js（建模 / 绕墙投影 / BFS 最短路 / Held–Karp）
 * 所以路线只能走通道格、每步相邻 —— 每次算完都跑 RouteCore.assertRoute，不过就在面板里报出来。
 *
 * 每趟随机 4~7 件（同一排货架只取一件：普通排同排不同货位会投到同一个通道格，否则编号会重叠）；
 * 跑完一趟自动换下一批。
 * ==========================================================================*/
(function () {
  'use strict';

  var host = document.getElementById('heroMap');
  if (!host) return;

  var stageEl = host.querySelector('.hm-stage');
  var canvas = host.querySelector('.hm-canvas');
  var listEl = host.querySelector('.hm-list');
  var statEl = host.querySelector('.hm-stat');
  var flagEl = host.querySelector('.hm-flag');
  var msgEl = host.querySelector('.hm-msg');
  var btnNew = host.querySelector('.hm-new');
  var btnPlay = host.querySelector('.hm-play');
  var btnView = host.querySelector('.hm-view');
  var ctx = canvas.getContext('2d');

  var ASSET_V = '20261004a';   // 自研资源版本号（改了 JS/数据就 bump）
  var N_MIN = 4, N_MAX = 7;      // 每趟取件点个数（随机）
  var ROAM_MS = 15000;           // 一趟走完的目标时长
  var STOP_MS = 620;             // 每站停留
  var RESTART_MS = 1600;         // 走完自动换下一批的间隔
  var CAM_MS = 980;              // 相机飞行时长（长一点，过渡更顺）
  var INTRO_MS = 1800;           // 每批开头在全览停留的时长（先看清整条路线）

  var OPT = { view: 'close', texture: 'dots', radius: 24 };


  /* ---------------------------------------------------------------- 颜色 */
  var C = {}, dark = false;
  function readColors() {
    dark = document.documentElement.getAttribute('data-theme') === 'dark';
    var cs = getComputedStyle(document.documentElement);
    function g(n, d) { var v = cs.getPropertyValue(n); return (v && v.trim()) || d; }
    C = {
      bg1: g('--m-bg1', '#131C2B'), bg2: g('--m-bg2', '#0F1724'),
      texture: g('--m-texture', 'rgba(255,255,255,.05)'),
      accent: g('--m-accent', '#5B95F5'), accent2: g('--m-accent2', '#8AB6FF'),
      sf: g('--m-sf', '#F07A2B'), exit: g('--m-exit', '#D0483C'),
      shelf: g('--m-shelf', '#26344A'), shelfEdge: g('--m-shelfEdge', '#3A4C68'),
      gate: g('--m-gate', '#4A3A22'), gateEdge: g('--m-gateEdge', '#7C5F2C'),
      entrance: g('--m-entrance', '#255C3B'), entranceInk: g('--m-entranceInk', '#CFEFD8'),
      todo: g('--m-todo', '#43628F'), done: g('--m-done', '#39465A'),
      vignette: g('--m-vignette', 'rgba(8,12,20,.74)'),
      shadow: g('--m-shadow', 'rgba(0,0,0,0)'),
      label: g('--m-label', '#8FA2B8'), outline: g('--m-outline', 'rgba(120,150,200,.35)'),
      ink: g('--m-ink', '#E8EEF7'), ink2: g('--m-ink2', '#9DACBE')
    };
  }

  /* ---------------------------------------------------------------- 数据 */
  var COR, model, modelRoute, locate, entrance, generalGates, sfGates, sfExitGates, VENUE, ready = false;

  function loadScript(src) {
    return new Promise(function (res, rej) {
      var s = document.createElement('script');
      s.src = src; s.onload = res; s.onerror = function () { rej(new Error('无法加载 ' + src)); };
      document.head.appendChild(s);
    });
  }
  function inSpan(sp) {
    return bandCells.filter(function (g) {
      return g[0] >= sp[0] && g[0] <= sp[1] && g[1] >= sp[2] && g[1] <= sp[3];
    });
  }
  function spansOf(re) {
    return COR.rects.filter(function (r) { return r.label && re.test(r.label); })
      .map(function (r) { return [r.r0, r.r1, r.c0, r.c1]; });
  }
  function cellsOf(spans) {
    var out = [];
    spans.forEach(function (sp) { out = out.concat(inSpan(sp)); });
    return out;
  }
  /* 闸机「门口」= 闸机带外紧邻的通道格。
     🔴 同步 App 0.2.0 的修改：闸机带**不可通行**，停靠点取带外的门口格
     （真机地图上「道路压在闸机上」就是这么修掉的）。 */
  var bandCells = [];       // 闸机带内的格（只用来算门口，不参与寻路）
  function mouthsOf(spans) {
    var seen = {}, out = [];
    cellsOf(spans).forEach(function (c) {
      [[0, 1], [0, -1], [1, 0], [-1, 0]].forEach(function (d) {
        var r = c[0] + d[0], col = c[1] + d[1];
        if (modelRoute.kindAt(r, col) === 0) return;      // 带外的可走格
        if (inGateBand(col, r)) return;                   // 仍在别的闸机带里
        var k = r + ',' + col;
        if (seen[k]) return;
        seen[k] = 1; out.push([r, col]);
      });
    });
    return out;
  }

  function initModel() {
    var GATE_SPANS = spansOf(/(闸机|出口)/);
    GATE_BANDS = GATE_SPANS;
    /* 两个模型：
       · model      —— 闸机带可走（只为取「带内格」来算门口）
       · modelRoute —— **闸机带不可走**，寻路与投影都用它（与 App 一致） */
    model = RouteCore.buildModel(COR, { gateSpans: GATE_SPANS, shelfRects: COR.rects });
    modelRoute = RouteCore.buildModel(COR, { gateSpans: [], shelfRects: COR.rects });
    bandCells = model.gates.slice();
    locate = RouteCore.makeLocator(COR, modelRoute, { jCells: 21 });

    generalGates = mouthsOf(spansOf(/普通闸机/));
    var sfSpans = COR.rects.filter(function (r) {
      return r.label && /顺丰/.test(r.label) && /(闸机|出口)/.test(r.label);
    });
    var sfCheckSpans = sfSpans.filter(function (r) { return /专用/.test(r.label); })
      .map(function (r) { return [r.r0, r.r1, r.c0, r.c1]; });
    var sfExitSpans = sfSpans.filter(function (r) { return !/专用/.test(r.label); })
      .map(function (r) { return [r.r0, r.r1, r.c0, r.c1]; });
    sfGates = mouthsOf(sfCheckSpans);
    sfExitGates = mouthsOf(sfExitSpans);
    if (!sfExitGates.length) sfExitGates = generalGates;

    var entRun = COR.gate.filter(function (g) { return g[2] - g[1] >= 2; })[0] || COR.gate[0];
    var entCells = [];
    for (var c = entRun[1]; c <= entRun[2]; c++) entCells.push([entRun[0], c]);
    var e = modelRoute.nearestWalkFrom(entCells, entRun[0], (entRun[1] + entRun[2] + 1) / 2);
    entrance = e || null;                     // ⚠️ {row,col} 对象：solve() 读 .row/.col

    VENUE = {
      c0: COR.span.minCol, c1: COR.span.maxCol,
      r0: COR.span.minRow, r1: COR.span.maxRow
    };
    /* 场地范围要把最东的 Y 大件区也算进去（它比通道最东列还靠东） */
    COR.rects.forEach(function (r) {
      VENUE.c0 = Math.min(VENUE.c0, r.c0); VENUE.c1 = Math.max(VENUE.c1, r.c1);
      VENUE.r0 = Math.min(VENUE.r0, r.r0); VENUE.r1 = Math.max(VENUE.r1, r.r1);
    });
    ready = true;
  }

  /* ------------------------------------------------------- 随机取件点与路线 */
  var POOL = { main: [], jc: [], sf: [], bulk: [] };
  var specialTick = 0;          // 特色区轮转计数（顺丰 → J 柜列 → 大件）
  var GATE_BANDS = [];          // 闸机带矩形 [r0,r1,c0,c1]（带内不再算停靠点）

  /* 把一段路线两端的「闸机带内」格子去掉 ⇒ 路线停在**门口通道格**上。
     （同步 App 0.2.0 的修改：停靠点从闸机带内部改到带外紧邻的通道格） */
  function inGateBand(col, row) {
    for (var i = 0; i < GATE_BANDS.length; i++) {
      var g = GATE_BANDS[i];
      if (row >= g[0] && row <= g[1] && col >= g[2] && col <= g[3]) return true;
    }
    return false;
  }
  function trimGateCells(seg) {
    var a = 0, b = seg.length;
    while (a < b && inGateBand(seg[a][0], seg[a][1])) a++;
    while (b > a && inGateBand(seg[b - 1][0], seg[b - 1][1])) b--;
    if (b - a < 2) return seg;             // 整段都在带里就别动（保底）
    return seg.slice(a, b);
  }
  function buildShelfPool() {
    COR.rects.forEach(function (r) {
      var L = (r.label || '').trim();
      if (/^[A-HK-R]\d{1,2}$/.test(L)) POOL.main.push({ label: L, max: 20 });
      else if (/^J\d$/.test(L)) POOL.jc.push({ label: L, max: 21 });
      else if (/^S\d$/.test(L)) POOL.sf.push({ label: L, max: 8 });
    });
    POOL.bulk.push({ label: 'Y5', max: 5 });  // 大件区在精确版里是一整块
  }

  function zoneOf(code) {
    if (/^S/i.test(code)) return '顺丰 S 区';
    if (/^J/i.test(code)) return 'J 柜列';
    if (/^Y/i.test(code)) return '大件 Y 区';
    var m = /^([A-Z])(\d{1,2})/.exec(code);
    return m ? m[1] + ' 排 ' + m[2] + ' 号货架' : code;
  }

  function makeBatch() {
    var want = N_MIN + Math.floor(Math.random() * (N_MAX - N_MIN + 1));
    var used = {}, items = [];
    function take(kind) {
      var pool = POOL[kind];
      if (!pool || !pool.length) return false;
      for (var t = 0; t < 24; t++) {
        var s = pool[(Math.random() * pool.length) | 0];
        if (used[s.label]) continue;
        var code = s.label + '-' + (1 + ((Math.random() * s.max) | 0));
        var pos = locate(code);
        if (!pos) continue;
        used[s.label] = 1;
        items.push({ code: code, pos: pos, zone: zoneOf(code) });
        return true;
      }
      return false;
    }
    /* 🔴 普通排有 189 个货架、顺丰只有 3 个、大件只有 1 个：纯随机几乎永远抽不到 S / Y。
       改成：**特色区轮转保底**（顺丰 → J 柜列 → 大件 依次轮），再按概率补第二个特色区，
       剩下的用普通排补足 ⇒ 每批至少 1 个特色区，且三者长期均匀出现。 */
    var primary = ['sf', 'jc', 'bulk'][specialTick++ % 3];
    take(primary);
    if (Math.random() < 0.45) take(Math.random() < 0.5 ? 'sf' : 'jc');
    if (Math.random() < 0.38) take('bulk');
    var guard = 0;
    while (items.length < want && guard++ < 200) {
      if (!take('main')) take('jc');
    }
    while (items.length > want) items.pop();

    var res = RouteCore.solve(
      items.map(function (i) {
        return { label: i.code, cell: i.pos.cellPos, sf: /^S/i.test(i.code), stub: i.pos.stub || 0 };
      }),
      {
        model: modelRoute, entranceCell: entrance, sfGateCells: sfGates,
        sfExitGates: sfExitGates, normalGateCells: generalGates
      }
    );
    var errs = [];
    res.legs.forEach(function (l) {
      if (l.cells) errs = errs.concat(RouteCore.assertRoute(modelRoute, l.cells, l.from + '→' + l.to));
    });

    var order = res.order.map(function (i) { return items[i]; });
    var stops = [];
    order.forEach(function (it, i) {
      stops.push({ kind: 'pick', code: it.code, zone: it.zone, pos: it.pos, n: i + 1 });
      if (res.sfAfter === i + 1) stops.push({ kind: 'sf', code: '顺丰出库', zone: '顺丰专用闸机' });
    });
    stops.push({
      kind: 'exit',
      code: res.exitKind === 'normal' ? '出站：7个普通闸机' : '出站：顺丰和无快递出口',
      zone: res.exitKind === 'normal' ? '出库 + 出站' : '顺丰侧出口'
    });

    var legs = [];
    res.legs.forEach(function (l) {
      var seg = [];
      (l.cells || []).forEach(function (rc) {
        var p = [rc[1], rc[0]];
        var q = seg[seg.length - 1];
        if (!q || q[0] !== p[0] || q[1] !== p[1]) seg.push(p);
      });
      /* 🔴 同步 App 已修的问题：停靠点应该落在**闸机带外的门口通道格**，
         路线不该画进闸机带里面（真机地图上「道路压在闸机上」已被修掉）。 */
      seg = trimGateCells(seg);
      if (seg.length > 1) legs.push({ cells: seg, tiles: l.tiles });
    });

    var legOf = [], li = 0;
    stops.forEach(function (st, i) {
      if (i === stops.length - 1) { legOf.push(Math.max(0, legs.length - 1)); return; }
      legOf.push(Math.min(li, Math.max(0, legs.length - 1)));
      li++;
    });
    var cum = [], acc = 0;
    legs.forEach(function (l) { acc += (l.cells.length - 1) || 0; cum.push(acc); });

    return {
      stops: stops, legs: legs, legOf: legOf, cum: cum, total: acc,
      tiles: res.total, exact: res.exact, errs: errs, res: res
    };
  }

  /* ------------------------------------------------------------ 画布与相机 */
  var dpr = Math.min(window.devicePixelRatio || 1, 2);
  var W = 0, H = 0;
  var cam = { cx: 64, cy: 36, scale: 6 }, anim = null;

  function S() { return cam.scale; }
  function sx(c) { return W / 2 + (c - cam.cx) * S(); }
  function sy(r) { return H / 2 + (r - cam.cy) * S(); }

  /* easeInOutQuint：起步与收尾都慢，比 cubic 顺很多 */
  function easeInOut(p) { return p < 0.5 ? 16 * p * p * p * p * p : 1 - Math.pow(-2 * p + 2, 5) / 2; }
  function flyTo(t, ms) {
    ms = (ms === undefined ? CAM_MS : ms);
    /* 目标几乎没变就别重启动画（连续几站会把飞行动画反复打断，看着很生硬） */
    if (ms > 0 && Math.abs(t.cx - cam.cx) < 1.2 && Math.abs(t.cy - cam.cy) < 1.2 &&
        Math.abs(t.scale / cam.scale - 1) < 0.02) return;
    if (ms <= 0) { cam.cx = t.cx; cam.cy = t.cy; cam.scale = t.scale; anim = null; return; }
    anim = { from: { cx: cam.cx, cy: cam.cy, scale: cam.scale }, to: t, t0: performance.now(), ms: ms };
  }
  function fitBounds(b, pad) {
    pad = (pad === undefined ? 34 : pad);
    var w = b.c1 - b.c0 + 1, h = b.r1 - b.r0 + 1;
    var sc = clampScale(Math.min((W - pad * 2) / w, (H - pad * 2) / h));
    return { cx: (b.c0 + b.c1 + 1) / 2, cy: (b.r0 + b.r1 + 1) / 2, scale: sc };
  }
  /* scale 范围：不小于「全览」，也不超过全览的 2.4 倍 ——
     否则很短的一段会放大到只剩两排货架，看不出自己站在场地哪儿 */
  function clampScale(sc) {
    var cols = VENUE.c1 - VENUE.c0 + 1, rows = VENUE.r1 - VENUE.r0 + 1;
    var base = Math.min((W - 28) / cols, (H - 28) / rows);
    return Math.max(base * 0.98, Math.min(sc, base * 2.4));
  }
  function fitAll() { return fitBounds(VENUE, 14); }
  function boundsOf(i) {
    var b = ST.b;
    var leg = b && b.legs[i], cells = (leg && leg.cells) || [];
    if (!cells.length) return VENUE;
    var c0 = 1e9, c1 = -1e9, r0 = 1e9, r1 = -1e9;
    cells.forEach(function (p) {
      c0 = Math.min(c0, p[0]); c1 = Math.max(c1, p[0]);
      r0 = Math.min(r0, p[1]); r1 = Math.max(r1, p[1]);
    });
    if (i > 0) {
      var prev = b.legs[i - 1].cells, last = prev[prev.length - 1];
      c0 = Math.min(c0, last[0]); c1 = Math.max(c1, last[0]);
      r0 = Math.min(r0, last[1]); r1 = Math.max(r1, last[1]);
    }
    var pad = 6;
    return { c0: c0 - pad, c1: c1 + pad, r0: r0 - pad, r1: r1 + pad };
  }
  function viewTarget() {
    if (OPT.view === 'overview' || !ST.b) return fitAll();
    return fitBounds(boundsOf(legIndexAt(ST.d)), 30);
  }

  function resize() {
    var r = stageEl.getBoundingClientRect();
    W = Math.max(200, Math.round(r.width));
    H = Math.max(120, Math.round(r.height || 200));
    canvas.width = Math.round(W * dpr); canvas.height = Math.round(H * dpr);
    canvas.style.width = W + 'px'; canvas.style.height = H + 'px';
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  }

  function rr(x, y, w, h, r) {
    r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
    ctx.beginPath();
    ctx.moveTo(x + r, y);
    ctx.arcTo(x + w, y, x + w, y + h, r);
    ctx.arcTo(x + w, y + h, x, y + h, r);
    ctx.arcTo(x, y + h, x, y, r);
    ctx.arcTo(x, y, x + w, y, r);
    ctx.closePath();
  }

  /* --------------------------------------------------------------- 状态 */
  var ST = {
    b: null, d: 0, pause: 0, playing: true, visible: true, rest: 0, intro: 0,
    done: false, hover: null
  };

  function loadBatch(fly) {
    ST.b = makeBatch();
    ST.d = 0; ST.pause = 0; ST.rest = 0; ST.done = false; ST.hover = null;
    ST.intro = OPT.view === 'close' ? INTRO_MS : 0;   // 先亮着看全貌，再跟着走
    renderList(); renderHead();
    show(ST.b.errs.length ? ('⚠ 路线断言未通过：' + ST.b.errs.slice(0, 2).join(' | ')) : '');
    if (fly !== false) flyTo(fitAll(), 700);
  }
  function show(t) {
    if (!msgEl) return;
    msgEl.textContent = t || '';
    msgEl.style.display = t ? 'block' : 'none';
  }
  function pickCount() {
    return ST.b ? ST.b.stops.filter(function (s) { return s.kind === 'pick'; }).length : 0;
  }
  function legIndexAt(d) {
    var b = ST.b;
    for (var i = 0; i < b.cum.length; i++) if (d < b.cum[i] - 1e-6) return i;
    return Math.max(0, b.legs.length - 1);
  }
  function stopIndexAt(d) { return Math.min(legIndexAt(d), ST.b.stops.length - 1); }
  function posAt(d) {
    var b = ST.b, i = legIndexAt(d), prev = i ? b.cum[i - 1] : 0;
    var leg = b.legs[i], o = Math.max(0, Math.min(leg.cells.length - 1, d - prev));
    var k = Math.min(leg.cells.length - 2, Math.floor(o)), f = o - k;
    var a = leg.cells[k], c = leg.cells[Math.min(leg.cells.length - 1, k + 1)];
    return [a[0] + (c[0] - a[0]) * f, a[1] + (c[1] - a[1]) * f];
  }

  function renderHead() {
    var b = ST.b;
    if (statEl) {
      statEl.innerHTML = '<b>' + pickCount() + '</b> 个取件点 · 全程 <b>' + b.tiles.toFixed(1)
        + '</b> 格 · ' + (b.exact ? '精确最优' : '启发式近似');
    }
    if (flagEl) {
      var st = b.stops[stopIndexAt(ST.d)];
      if (ST.done) flagEl.textContent = '已走完 · 全程 ' + b.tiles.toFixed(1) + ' 格';
      else if (st.kind === 'pick') flagEl.textContent = '下一站 ' + st.n + '/' + pickCount() + ' · ' + st.code;
      else if (st.kind === 'sf') flagEl.textContent = '下一步：顺丰出库（顺丰专用闸机）';
      else flagEl.textContent = '最后一步：' + st.code;
    }
  }

  function renderList() {
    if (!listEl) return;
    var b = ST.b, html = '';
    b.stops.forEach(function (st, i) {
      var badge = st.kind === 'pick' ? String(st.n) : st.kind === 'sf' ? 'SF' : '出';
      var cls = 'hm-row' + (st.kind === 'sf' ? ' sf' : st.kind === 'exit' ? ' exit' : '');
      var leg = b.legs[b.legOf[i]];
      html += '<button class="' + cls + '" data-i="' + i + '" type="button">'
        + '<span class="hm-n">' + badge + '</span>'
        + '<span class="hm-code">' + st.code + '</span>'
        + '<span class="hm-zone">' + st.zone + '</span>'
        + '<span class="hm-leg">' + (leg ? leg.tiles.toFixed(1) + ' 格' : '') + '</span>'
        + '</button>';
    });
    listEl.innerHTML = html;
    /* 行数装不下时才加底部淡出（否则最后一行会被无谓地淡化） */
    listEl.classList.toggle('scroll', listEl.scrollHeight > listEl.clientHeight + 4);
  }

  function markList() {
    if (!listEl) return;
    var cur = stopIndexAt(ST.d);
    Array.prototype.forEach.call(listEl.children, function (el, i) {
      el.classList.toggle('on', i === cur && !ST.done);
      el.classList.toggle('past', i < cur || (ST.done && i <= cur));
    });
    var on = listEl.children[cur];
    if (on && listEl.scrollHeight > listEl.clientHeight + 4) {
      listEl.scrollTop = Math.max(0, on.offsetTop - listEl.clientHeight / 2 + on.offsetHeight / 2);
    }
  }

  /* ------------------------------------------------------------------ 画 */
  function strokePath(cells) {
    ctx.beginPath();
    cells.forEach(function (p, i) {
      var X = sx(p[0]) + S() / 2, Y = sy(p[1]) + S() / 2;
      i ? ctx.lineTo(X, Y) : ctx.moveTo(X, Y);
    });
    ctx.stroke();
  }
  function color(name) { return C[name]; }

  function marker(x, y, label, fill, fg, big) {
    var r = Math.max(9, Math.min(15, S() * 1.5)) * (big ? 1.06 : 1);
    ctx.save();
    if (dark) { ctx.shadowColor = 'rgba(0,0,0,.5)'; ctx.shadowBlur = 10; ctx.shadowOffsetY = 2; }
    else { ctx.shadowColor = 'rgba(16,34,64,.28)'; ctx.shadowBlur = 8; ctx.shadowOffsetY = 2; }
    ctx.fillStyle = fill;
    ctx.beginPath(); ctx.arc(x, y, r, 0, Math.PI * 2); ctx.fill();
    ctx.restore();
    ctx.strokeStyle = 'rgba(255,255,255,.92)'; ctx.lineWidth = 2;
    ctx.beginPath(); ctx.arc(x, y, r, 0, Math.PI * 2); ctx.stroke();
    ctx.fillStyle = fg; ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
    ctx.font = '700 ' + Math.max(9, r * 1.05) + 'px -apple-system,"PingFang SC",sans-serif';
    ctx.fillText(label, x, y + 0.5);
  }
  function draw(now) {
    var b = ST.b;
    ctx.clearRect(0, 0, W, H);

    /* ① 底：竖向柔和渐变 */
    var g = ctx.createLinearGradient(0, 0, 0, H);
    g.addColorStop(0, C.bg1); g.addColorStop(1, C.bg2);
    ctx.fillStyle = g; ctx.fillRect(0, 0, W, H);

    /* ② 底纹：点阵（原型 A 方案） */
    if (OPT.texture === 'dots') {
      ctx.save(); ctx.fillStyle = C.texture;
      for (var y = 7; y < H; y += 14) {
        for (var x = 7; x < W; x += 14) {
          ctx.beginPath(); ctx.arc(x, y, 1.1, 0, Math.PI * 2); ctx.fill();
        }
      }
      ctx.restore();
    }

    /* ③ 场地外框（虚线圆角矩形） */
    ctx.save();
    ctx.setLineDash([6, 6]);
    ctx.strokeStyle = C.outline; ctx.lineWidth = 1.5;
    rr(sx(VENUE.c0) - 4, sy(VENUE.r0) - 4, S() * (VENUE.c1 - VENUE.c0 + 1) + 8,
      S() * (VENUE.r1 - VENUE.r0 + 1) + 8, 14);
    ctx.stroke();
    ctx.restore();

    /* ④ 通道格：只在放大到看得清时淡画（全览时不画，保持干净） */
    if (S() >= 5) {
      ctx.save();
      ctx.fillStyle = dark ? 'rgba(120,150,200,.10)' : 'rgba(120,150,200,.14)';
      COR.walk.forEach(function (w) {
        ctx.fillRect(sx(w[1]), sy(w[0]), S() * (w[2] - w[1] + 1), S());
      });
      ctx.restore();
    }

    /* ⑤ 货架 / 柜列 / 大件区 / 闸机带（原型画法：圆角块 + 边线，浅色带投影） */
    COR.rects.forEach(function (r) {
      var label = (r.label || '');
      var isGate = /闸机|出口/.test(label);
      var x = sx(r.c0), y = sy(r.r0), w = S() * (r.c1 - r.c0 + 1), h = S() * (r.r1 - r.r0 + 1);
      if (x > W + 60 || y > H + 60 || x + w < -60 || y + h < -60) return;
      ctx.save();
      if (!dark) { ctx.shadowColor = C.shadow; ctx.shadowBlur = 6; ctx.shadowOffsetY = 2; }
      ctx.fillStyle = isGate ? C.gate : C.shelf;
      rr(x, y, w, h, Math.min(OPT.radius / 2, S() / 2.6)); ctx.fill();
      ctx.restore();
      ctx.strokeStyle = isGate ? C.gateEdge : C.shelfEdge; ctx.lineWidth = 1;
      rr(x, y, w, h, Math.min(OPT.radius / 2, S() / 2.6)); ctx.stroke();

      /* 大区域（大件区 / 顺丰）始终标名；小货架放大到 S≥11 才标 */
      var big = (w > 34 && h > 12) || (h > 34 && w > 12);
      if (label && !/^`$/.test(label) && !isGate && (big || S() >= 11)) {
        ctx.fillStyle = C.label;
        ctx.font = Math.min(big ? 12 : 11, Math.max(9, S() * 0.75)) + 'px -apple-system,"PingFang SC",sans-serif';
        ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
        ctx.fillText(label, x + w / 2, y + h / 2);
      }
    });

    /* ⑥ 入口闸机带（底层的绿带；「入」标志留到最后画，压在路线之上） */
    COR.gate.filter(function (g2) { return g2[2] - g2[1] >= 2; }).forEach(function (gt) {
      var x = sx(gt[1]), y = sy(gt[0]), w = S() * (gt[2] - gt[1] + 1), h = S();
      ctx.fillStyle = C.entrance;
      rr(x, y, w, h, Math.min(6, S() / 2)); ctx.fill();
    });

    if (!b) return;


    /* ⑦ 路线：先画「已走 + 待走」，**当前段最后画**（否则后面的段会把高亮盖住）；
       当前段再加一层沿行进方向流动的虚线 —— 用「流动感」代替原来的三角箭头。 */
    var lineW = Math.max(3, Math.min(7, S() * 0.8));
    var curLeg = legIndexAt(ST.d);
    ctx.lineCap = 'round'; ctx.lineJoin = 'round';
    b.legs.forEach(function (leg, li) {
      if (li === curLeg || leg.cells.length < 2) return;
      ctx.save();
      ctx.strokeStyle = li < curLeg ? C.done : C.todo;
      ctx.lineWidth = lineW;
      strokePath(leg.cells);
      ctx.restore();
    });
    if (b.legs[curLeg] && b.legs[curLeg].cells.length > 1) {
      var cc = b.legs[curLeg].cells;
      ctx.save();
      /* 光晕（比线宽大 2.6 倍） */
      ctx.strokeStyle = dark ? 'rgba(91,149,245,.20)' : 'rgba(47,111,228,.16)';
      ctx.lineWidth = lineW * 2.6;
      strokePath(cc);
      /* 主线 */
      ctx.strokeStyle = C.accent; ctx.lineWidth = lineW;
      strokePath(cc);
      /* 流动虚线：offset 一直减 ⇒ 看起来往行进方向跑 */
      ctx.strokeStyle = dark ? 'rgba(214,232,255,.92)' : 'rgba(255,255,255,.95)';
      ctx.lineWidth = Math.max(1.6, lineW * 0.4);
      ctx.setLineDash([lineW * 1.15, lineW * 2.5]);
      ctx.lineDashOffset = -((now / 22) % 1000);
      strokePath(cc);
      ctx.restore();
    }
    /* 待走的段也给一点流动感（更淡、更慢），整条路线像是活的 */
    ctx.save();
    ctx.strokeStyle = dark ? 'rgba(140,180,240,.32)' : 'rgba(255,255,255,.55)';
    ctx.lineWidth = Math.max(1.4, lineW * 0.32);
    ctx.setLineDash([lineW * 1.1, lineW * 3.2]);
    ctx.lineDashOffset = -((now / 34) % 1000);
    b.legs.forEach(function (leg, li) {
      if (li <= curLeg || leg.cells.length < 2) return;
      strokePath(leg.cells);
    });
    ctx.restore();

    /* ⑧ 聚焦聚光灯：只把「当前这一段」照亮，其余压暗。
       ⚠️ 浅色主题不能用近白的高透明蒙版（会糊成一片白雾）——改用冷灰蓝的阴影色；
          深色主题也别压太狠，否则货架完全看不见。 */
    if (OPT.view === 'close' && ST.intro <= 0 && b.legs[curLeg]) {
      var bb = boundsOf(curLeg);
      var cxp = (sx(bb.c0) + sx(bb.c1 + 1)) / 2, cyp = (sy(bb.r0) + sy(bb.r1 + 1)) / 2;
      var rx = Math.max(120, (sx(bb.c1 + 1) - sx(bb.c0)) / 2 + 78);
      var ry = Math.max(92, (sy(bb.r1 + 1) - sy(bb.r0)) / 2 + 58);
      var rad = Math.max(rx, ry) * 1.45;
      var rg = ctx.createRadialGradient(cxp, cyp, rad * 0.5, cxp, cyp, rad);
      rg.addColorStop(0, 'rgba(0,0,0,0)');
      rg.addColorStop(0.7, dark ? 'rgba(8,13,22,.18)' : 'rgba(84,106,140,.14)');
      rg.addColorStop(1, dark ? 'rgba(8,13,22,.36)' : 'rgba(84,106,140,.30)');
      ctx.save(); ctx.fillStyle = rg; ctx.fillRect(0, 0, W, H); ctx.restore();
    }

    /* ⑨ 行进光点（径向光晕 + 白核 + 蓝心） */
    if (!ST.done && b.legs[curLeg]) {
      var p = posAt(ST.d);
      var X = sx(p[0]) + S() / 2, Y = sy(p[1]) + S() / 2;
      var halo = ctx.createRadialGradient(X, Y, 0, X, Y, Math.max(10, S() * 2.2));
      halo.addColorStop(0, dark ? 'rgba(91,149,245,.45)' : 'rgba(47,111,228,.45)');
      halo.addColorStop(1, 'rgba(47,111,228,0)');
      ctx.save();
      ctx.fillStyle = halo;
      ctx.beginPath(); ctx.arc(X, Y, Math.max(10, S() * 2.2), 0, Math.PI * 2); ctx.fill();
      ctx.fillStyle = '#fff';
      ctx.beginPath(); ctx.arc(X, Y, Math.max(3.4, S() * 0.75), 0, Math.PI * 2); ctx.fill();
      ctx.fillStyle = C.accent;
      ctx.beginPath(); ctx.arc(X, Y, Math.max(2.2, S() * 0.5), 0, Math.PI * 2); ctx.fill();
      ctx.restore();
    }

    /* ⑩ 站点标记（当前站呼吸光晕；顺丰 / 出站各自配色） */
    var curStop = stopIndexAt(ST.d);
    b.stops.forEach(function (st, i) {
      var leg = b.legs[b.legOf[i]];
      if (!leg || !leg.cells.length) return;
      var cell = leg.cells[leg.cells.length - 1];
      var x = sx(cell[0]) + S() / 2, y = sy(cell[1]) + S() / 2;
      var col = st.kind === 'sf' ? C.sf : st.kind === 'exit' ? C.exit : C.accent;
      if (i === curStop && !ST.done) {
        var t = (now % 1800) / 1800;
        ctx.save(); ctx.globalAlpha = 0.28 * (1 - t);
        ctx.fillStyle = C.accent;
        ctx.beginPath(); ctx.arc(x, y, Math.max(12, S() * 2.6) * (1 + t * 0.9), 0, Math.PI * 2); ctx.fill();
        ctx.restore();
      }
      var label = st.kind === 'pick' ? String(st.n) : st.kind === 'sf' ? 'SF' : '出';
      marker(x, y, label, col, '#fff', i === curStop);
    });

    /* ⑫ 入口「入」标志：**最后画**，压在路线与标记之上（不然会被路线盖住） */
    if (b.legs.length && b.legs[0].cells.length) {
      var e0 = b.legs[0].cells[0];
      var ex = sx(e0[0]) + S() / 2, ey = sy(e0[1]) + S() / 2;
      var er = Math.max(11, Math.min(17, S() * 1.9));
      ctx.save();
      ctx.shadowColor = 'rgba(16,34,64,.35)'; ctx.shadowBlur = 10; ctx.shadowOffsetY = 2;
      ctx.fillStyle = C.entrance; rr(ex - er, ey - er, er * 2, er * 2, er * 0.5); ctx.fill();
      ctx.restore();
      ctx.strokeStyle = 'rgba(255,255,255,.95)'; ctx.lineWidth = 2;
      rr(ex - er, ey - er, er * 2, er * 2, er * 0.5); ctx.stroke();
      ctx.fillStyle = C.entranceInk; ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
      ctx.font = '700 ' + Math.max(10, er * 1.02) + 'px -apple-system,"PingFang SC",sans-serif';
      ctx.fillText('入', ex, ey + 0.5);
    }
  }


  /* --------------------------------------------------------------- 逐帧 */
  function advance(dt) {
    var b = ST.b;
    if (!b || !ST.playing || !ST.visible) return;
    /* 每批开头先在全览停一下（看清整条路线），再进入特写跟随 */
    if (ST.intro > 0) {
      ST.intro -= dt;
      if (ST.intro <= 0 && OPT.view === 'close') {
        flyTo(fitBounds(boundsOf(legIndexAt(ST.d)), 30));
      }
      return;
    }
    if (ST.done) {
      ST.rest += dt;
      if (ST.rest >= RESTART_MS) loadBatch();
      return;
    }
    if (ST.pause > 0) { ST.pause -= dt; return; }

    var speed = Math.max(30, Math.min(200, b.total / (ROAM_MS / 1000)));
    var before = ST.d;
    ST.d = Math.min(b.total, ST.d + (dt / 1000) * speed);
    for (var i = 0; i < b.cum.length; i++) {
      if (before < b.cum[i] && ST.d >= b.cum[i]) {
        ST.d = b.cum[i];
        ST.pause = STOP_MS;
        renderHead();
        if (OPT.view === 'close' && i + 1 < b.legs.length) {
          flyTo(fitBounds(boundsOf(i + 1), 30));       // 相机跟到下一段
        }
        break;
      }
    }
    if (ST.d >= b.total - 1e-6) { ST.d = b.total; ST.done = true; ST.rest = 0; }
    renderHead();
  }

  var last = 0;
  function loop(now) {
    var dt = Math.min(70, now - (last || now));
    last = now;
    if (anim) {
      var p = Math.min(1, (now - anim.t0) / anim.ms), e = easeInOut(p);
      cam.cx = anim.from.cx + (anim.to.cx - anim.from.cx) * e;
      cam.cy = anim.from.cy + (anim.to.cy - anim.from.cy) * e;
      /* 缩放走**对数插值**：倍率差大时线性插值会前快后慢，看着一顿一顿 */
      cam.scale = anim.from.scale * Math.pow(anim.to.scale / anim.from.scale, e);
      if (p >= 1) anim = null;
    }
    advance(dt);
    draw(now);
    markList();
    requestAnimationFrame(loop);
  }

  /* --------------------------------------------------------------- 交互 */
  function focusStop(i) {
    var b = ST.b;
    if (!b) return;
    ST.d = i ? b.cum[i - 1] : 0;
    ST.done = false; ST.pause = 0; ST.intro = 0;
    flyTo(fitBounds(boundsOf(Math.min(i, b.legs.length - 1)), 30));
    renderHead();
  }
  function setView(v) {
    OPT.view = v; ST.intro = 0;
    if (btnView) btnView.textContent = v === 'close' ? '全览' : '特写';
    flyTo(v === 'overview' ? fitAll() : fitBounds(boundsOf(legIndexAt(ST.d)), 30));
  }

  if (listEl) {
    listEl.addEventListener('click', function (e) {
      var el = e.target.closest('.hm-row');
      if (!el) return;
      focusStop(+el.getAttribute('data-i'));
      ST.playing = true; syncPlay();
    });
    listEl.addEventListener('mouseover', function (e) {
      var el = e.target.closest('.hm-row');
      ST.hover = el ? +el.getAttribute('data-i') : null;
    });
    listEl.addEventListener('mouseleave', function () { ST.hover = null; });
  }

  /* 画布：点编号跳过去 / 鼠标拖动平移 / 双击切全览特写（滚轮不劫持，页面正常滚动） */
  if (canvas) {
    canvas.style.cursor = 'grab';
    var drag = null;
    canvas.addEventListener('pointerdown', function (e) {
      if (e.pointerType !== 'mouse') return;
      drag = { x: e.clientX, y: e.clientY, cx: cam.cx, cy: cam.cy, moved: 0 };
      canvas.style.cursor = 'grabbing';
      canvas.setPointerCapture(e.pointerId);
    });
    canvas.addEventListener('pointermove', function (e) {
      if (!drag) return;
      var dx = (e.clientX - drag.x) / S(), dy = (e.clientY - drag.y) / S();
      drag.moved = Math.max(drag.moved, Math.abs(e.clientX - drag.x) + Math.abs(e.clientY - drag.y));
      anim = null;
      cam.cx = drag.cx - dx; cam.cy = drag.cy - dy;
    });
    canvas.addEventListener('pointerup', function (e) {
      if (!drag) return;
      var wasClick = drag.moved < 4;
      drag = null;
      canvas.style.cursor = 'grab';
      try { canvas.releasePointerCapture(e.pointerId); } catch (_) {}
      if (!wasClick) return;
      /* 命中最近的编号点 ⇒ 跳过去 */
      var b = ST.b; if (!b) return;
      var r = canvas.getBoundingClientRect();
      var mx = e.clientX - r.left, my = e.clientY - r.top, best = -1, bd = Infinity;
      b.stops.forEach(function (st, i) {
        if (st.kind !== 'pick' || !st.pos) return;
        var leg = b.legs[b.legOf[i]];
        var cell = leg.cells[leg.cells.length - 1];
        var d = Math.hypot(sx(cell[0]) + S() / 2 - mx, sy(cell[1]) + S() / 2 - my);
        if (d < bd) { bd = d; best = i; }
      });
      if (best >= 0 && bd < 26) focusStop(best);
    });
    canvas.addEventListener('dblclick', function () {
      setView(OPT.view === 'close' ? 'overview' : 'close');
    });
  }

  function syncPlay() {
    if (!btnPlay) return;
    btnPlay.textContent = ST.playing ? '暂停' : '播放';
    btnPlay.setAttribute('aria-pressed', ST.playing ? 'true' : 'false');
  }
  if (btnPlay) btnPlay.addEventListener('click', function () { ST.playing = !ST.playing; syncPlay(); });
  if (btnNew) btnNew.addEventListener('click', function () { loadBatch(); ST.playing = true; syncPlay(); setView(OPT.view); });
  if (btnView) btnView.addEventListener('click', function () { setView(OPT.view === 'close' ? 'overview' : 'close'); });

  new MutationObserver(function () { readColors(); })
    .observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] });

  if ('IntersectionObserver' in window) {
    new IntersectionObserver(function (es) {
      es.forEach(function (x) { ST.visible = x.isIntersecting; });
    }, { threshold: 0.04 }).observe(host);
  }
  var rt;
  window.addEventListener('resize', function () {
    clearTimeout(rt);
    rt = setTimeout(function () {
      if (!ready) return;
      resize();
      flyTo(viewTarget(), 0);
    }, 160);
  });

  /* --------------------------------------------------------------- 启动 */
  Promise.all([
    window.RouteCore ? Promise.resolve() : loadScript('assets/route-core.js?v=' + ASSET_V),
    window.__VENUE_MODEL__ ? Promise.resolve() : loadScript('assets/venue-model.js?v=' + ASSET_V)
  ]).then(function () {
    COR = window.__VENUE_MODEL__;
    if (!COR) throw new Error('场地模型没加载出来');
    initModel();
    buildShelfPool();
    readColors();
    resize();
    flyTo(fitAll(), 0);
    loadBatch(false);
    syncPlay();
    if (btnView) btnView.textContent = OPT.view === 'close' ? '全览' : '特写';
    requestAnimationFrame(loop);
    host.classList.add('ready');
  }).catch(function (e) {
    show('地图初始化失败：' + ((e && e.message) || e));
  });

  /* 供本地验证脚本用（不影响页面） */
  window.__heroMap = {
    state: ST, cam: cam,
    info: function () {
      return ST.b ? {
        picks: pickCount(), stops: ST.b.stops.length, legs: ST.b.legs.length,
        totalCells: ST.b.total, tiles: ST.b.tiles, exact: ST.b.exact, errs: ST.b.errs.length,
        codes: ST.b.stops.filter(function (s) { return s.kind === 'pick'; }).map(function (s) { return s.code; }),
        distinct: (function () {
          var set = {}, n = 0;
          ST.b.stops.forEach(function (s) {
            if (s.kind !== 'pick' || !s.pos) return;
            var k = s.pos.cellPos.row + ',' + s.pos.cellPos.col;
            if (!set[k]) { set[k] = 1; n++; }
          });
          return n;
        })(),
        done: ST.done, d: ST.d, sc: cam.scale, view: OPT.view
      } : null;
    },
    view: setView,
    next: function () { loadBatch(); }
  };
})();
