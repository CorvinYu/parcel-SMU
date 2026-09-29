'use strict';
/**
 * CDP 多视口截图 + 页面结构体检（零 npm 依赖，Node 22+）。
 *
 * 两种运行方式：
 *   A) 本机 Chrome：            node tools/shoot.js <outDir> <url>
 *   B) 远端 Chrome + 隧道：     set CDP_PORT=9333 && node tools/shoot.js <outDir> <url>
 *      （远端 Chrome 用 --remote-debugging-port=9333 起，再 ssh -L 9333:127.0.0.1:9333）
 *      设置 SKIP_CHROME=1 时不会尝试在本机拉起 Chrome。
 *
 * 产出：<outDir>/mobile.png、desktop.png、desktop-light.png、report.json
 */
const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');

const OUT = path.resolve(process.argv[2] || path.join(__dirname, '..', '.shots'));
const TARGET = process.argv[3] || 'http://127.0.0.1:8791/';
const PORT = Number(process.env.CDP_PORT || 9333);
const SKIP_CHROME = process.env.SKIP_CHROME === '1';

const CHROME_CANDIDATES = [
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
  path.join(process.env.LOCALAPPDATA || '', 'Google', 'Chrome', 'Application', 'chrome.exe'),
];
const PROFILE = process.env.SHOT_PROFILE ||
  path.join(__dirname, '..', '..', '.devtools', 'chrome-shot-profile');

const VIEWPORTS = [
  { name: 'mobile', width: 390, height: 844, dsf: 2, mobile: true },
  { name: 'desktop', width: 1440, height: 900, dsf: 1, mobile: false },
];

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function cdpHttp(p, method = 'GET') {
  const res = await fetch(`http://127.0.0.1:${PORT}${p}`, { method });
  if (!res.ok) throw new Error(`CDP HTTP ${res.status} ${p}`);
  return res.json().catch(() => null);
}
async function portAlive() {
  try { await cdpHttp('/json/version'); return true; } catch (_) { return false; }
}
async function ensureChrome() {
  if (await portAlive()) { console.error('复用已在运行的调试 Chrome'); return; }
  if (SKIP_CHROME) throw new Error('CDP 端口无响应，且已设置 SKIP_CHROME=1');
  const exe = CHROME_CANDIDATES.find((c) => c && fs.existsSync(c));
  if (!exe) throw new Error('未找到本机 Chrome');
  fs.mkdirSync(PROFILE, { recursive: true });
  const child = spawn(exe, [
    '--headless=new', `--remote-debugging-port=${PORT}`, `--user-data-dir=${PROFILE}`,
    '--no-first-run', '--no-default-browser-check', '--disable-gpu', '--hide-scrollbars',
    'about:blank',
  ], { detached: true, stdio: 'ignore' });
  child.unref();
  for (let i = 0; i < 60; i++) { await sleep(500); if (await portAlive()) return; }
  throw new Error('Chrome 启动超时');
}

let ws = null, msgId = 0;
const pending = new Map();
const consoleErrors = [];
const failedRequests = [];

function connect(url) {
  return new Promise((resolve, reject) => {
    ws = new WebSocket(url);
    ws.addEventListener('open', resolve, { once: true });
    ws.addEventListener('error', reject, { once: true });
    ws.addEventListener('message', (ev) => {
      let m; try { m = JSON.parse(ev.data); } catch (_) { return; }
      if (m.id && pending.has(m.id)) {
        const { resolve: r, reject: j } = pending.get(m.id);
        pending.delete(m.id);
        m.error ? j(new Error(JSON.stringify(m.error))) : r(m.result);
        return;
      }
      if (m.method === 'Runtime.consoleAPICalled' && m.params.type === 'error') {
        consoleErrors.push((m.params.args || []).map((a) => a.value || a.description || '').join(' '));
      }
      if (m.method === 'Runtime.exceptionThrown') {
        consoleErrors.push('EXCEPTION: ' + JSON.stringify(m.params.exceptionDetails).slice(0, 300));
      }
      if (m.method === 'Network.loadingFailed') failedRequests.push(m.params.errorText);
      if (m.method === 'Network.responseReceived' && m.params.response.status >= 400) {
        failedRequests.push(`${m.params.response.status} ${m.params.response.url}`);
      }
    });
  });
}
function send(method, params = {}, timeoutMs = 60000) {
  const id = ++msgId;
  return new Promise((resolve, reject) => {
    pending.set(id, { resolve, reject });
    ws.send(JSON.stringify({ id, method, params }));
    setTimeout(() => {
      if (pending.has(id)) { pending.delete(id); reject(new Error('CDP 超时 ' + method)); }
    }, timeoutMs);
  });
}
async function evaluate(js) {
  const r = await send('Runtime.evaluate', {
    expression: `(async () => { ${js} })()`, awaitPromise: true, returnByValue: true,
  });
  if (r.exceptionDetails) throw new Error(JSON.stringify(r.exceptionDetails).slice(0, 500));
  return r.result.value;
}

const DIAG = `
  const doc = document.documentElement;
  const overflow = [];
  document.querySelectorAll('body *').forEach(el => {
    const r = el.getBoundingClientRect();
    if (r.width > 0 && (r.right > window.innerWidth + 1.5 || r.left < -1.5)) {
      const cls = (el.className && String(el.className).slice(0, 60)) || el.tagName;
      overflow.push({ sel: cls, left: Math.round(r.left), right: Math.round(r.right) });
    }
  });
  const imgs = Array.from(document.images).map(i => ({
    src: i.getAttribute('src'), ok: i.complete && i.naturalWidth > 0,
    nat: i.naturalWidth + 'x' + i.naturalHeight,
    rendered: Math.round(i.getBoundingClientRect().width) + 'x' + Math.round(i.getBoundingClientRect().height),
  }));
  return {
    url: location.href, title: document.title, theme: doc.getAttribute('data-theme'),
    bodyBg: getComputedStyle(document.body).backgroundColor,
    bodyColor: getComputedStyle(document.body).color,
    viewport: [window.innerWidth, window.innerHeight],
    scrollW: doc.scrollWidth, scrollH: doc.scrollHeight,
    horizontalOverflow: doc.scrollWidth > window.innerWidth + 1,
    overflowEls: overflow.slice(0, 12),
    brokenImages: imgs.filter(i => !i.ok),
    images: imgs.length,
    downloadHrefs: Array.from(new Set(Array.from(document.querySelectorAll('a[download]')).map(a => a.getAttribute('href')))),
    sections: Array.from(document.querySelectorAll('section[id]')).map(s => s.id),
    emptySlots: document.querySelectorAll('.slot.empty').length,
    textLen: (document.body.innerText || '').length,
    topbarHeight: Math.round((document.querySelector('.topbar') || {getBoundingClientRect:()=>({height:0})}).getBoundingClientRect().height),
    h1: (document.querySelector('h1') || {}).textContent,
    haidaCols: (function () {
      const g = document.querySelector('#haida .grid');
      return g ? getComputedStyle(g).gridTemplateColumns : null;
    })(),
    galleryCols: (function () {
      const g = document.querySelector('.gal');
      return g ? getComputedStyle(g).gridTemplateColumns : null;
    })(),
  };
`;

/* 滚动预热：触发 IntersectionObserver 入场动画 + loading="lazy" 图片 */
async function warmup() {
  await evaluate(`
    document.documentElement.style.scrollBehavior = 'auto';   // 截图期间禁用平滑滚动，保证落点确定
    const h = document.documentElement.scrollHeight;
    const step = Math.max(200, Math.round(window.innerHeight * 0.75));
    for (let y = 0; y < h + step; y += step) {
      window.scrollTo(0, y);
      await new Promise(r => setTimeout(r, 110));
    }
    window.scrollTo(0, 0);
    await new Promise(r => setTimeout(r, 250));
    return h;
  `);
  await sleep(400);
  // 仍未加载的图（lazy 未触发 / 未进入视口）强制补一次
  await evaluate(`
    Array.from(document.images).forEach(i => {
      if (!(i.complete && i.naturalWidth)) { i.loading = 'eager'; const s = i.getAttribute('src'); if (s) i.src = s; }
    });
    await Promise.all(Array.from(document.images).map(i =>
      (i.complete && i.naturalWidth) ? 1 : new Promise(r => { i.onload = r; i.onerror = r; setTimeout(r, 4000); })));
    return document.images.length;
  `);
  // 截图归一化：把入场动画元素直接置为可见，避免「滚动太快没触发 IO」被误判成内容缺失
  await evaluate(`document.querySelectorAll('.reveal').forEach(e => e.classList.add('in')); return 1;`);
  await sleep(600);
}

async function capture(file, vp) {
  await send('Emulation.setDeviceMetricsOverride', {
    width: vp.width, height: vp.height, deviceScaleFactor: vp.dsf, mobile: vp.mobile,
  });
  await send('Page.navigate', { url: TARGET });
  await sleep(2800);
  await warmup();
  const m = await send('Page.getLayoutMetrics');
  const s = m.cssContentSize || m.contentSize;
  const shot = await send('Page.captureScreenshot', {
    format: 'png', captureBeyondViewport: true,
    clip: { x: 0, y: 0, width: Math.ceil(s.width), height: Math.min(Math.ceil(s.height), 14000), scale: 1 },
  });
  fs.writeFileSync(file, Buffer.from(shot.data, 'base64'));
  return { file, cssSize: [Math.ceil(s.width), Math.ceil(s.height)], bytes: fs.statSync(file).size };
}

(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  await ensureChrome();
  const ver = await cdpHttp('/json/version');
  const list = await cdpHttp('/json/list');
  let page = list.filter((t) => t.type === 'page')[0];
  if (!page) { page = await cdpHttp('/json/new?about:blank', 'PUT'); }
  await connect(page.webSocketDebuggerUrl);
  await send('Page.enable');
  await send('Runtime.enable');
  await send('Network.enable');

  const report = { target: TARGET, browser: ver && ver.Browser, shots: {}, diag: {}, clips: [], anchors: [] };
  for (const vp of VIEWPORTS) {
    report.shots[vp.name] = await capture(path.join(OUT, vp.name + '.png'), vp);
    report.diag[vp.name] = await evaluate(DIAG);
    // 视口尺寸的真实观感截图（滚到不同位置），用于评估首屏/中段视觉
    const fracs = vp.mobile ? [0, 0.32, 0.62] : [0];
    for (let i = 0; i < fracs.length; i++) {
      const maxY = await evaluate(`return Math.max(0, document.documentElement.scrollHeight - window.innerHeight);`);
      const y = Math.round(maxY * fracs[i]);
      await evaluate(`window.scrollTo(0, ${y}); return 1;`);
      await sleep(450);
      const s = await send('Page.captureScreenshot', { format: 'png' });
      const f = path.join(OUT, `${vp.name}-v${i + 1}.png`);
      fs.writeFileSync(f, Buffer.from(s.data, 'base64'));
      report.clips.push({ file: f, y, vp: vp.name });
    }
  }

  // 关键分区定点截图（滚到对应 section）
  const ANCHORS = ['haida', 'rules', 'shots', 'install', 'faq'];
  for (const vp of VIEWPORTS) {
    await send('Emulation.setDeviceMetricsOverride', {
      width: vp.width, height: vp.height, deviceScaleFactor: vp.dsf, mobile: vp.mobile,
    });
    await send('Page.navigate', { url: TARGET });
    await sleep(2600);
    await warmup();
    for (const id of ANCHORS) {
      const y = await evaluate(`
        const el = document.getElementById('${id}');
        if (!el) return null;
        const top = el.getBoundingClientRect().top + window.pageYOffset - 80;
        window.scrollTo(0, Math.max(0, top));
        return Math.round(window.pageYOffset);
      `);
      if (y === null) continue;
      await sleep(500);
      const s = await send('Page.captureScreenshot', { format: 'png' });
      fs.writeFileSync(path.join(OUT, `${vp.name}-${id}.png`), Buffer.from(s.data, 'base64'));
      report.anchors.push({ vp: vp.name, id, y });
    }
  }

  // 浅色主题（直接切属性，不依赖 localStorage，file:// 下也有效）
  await capture(path.join(OUT, 'desktop-light.png'), VIEWPORTS[1]);
  await evaluate(`document.documentElement.setAttribute('data-theme','light'); return 1;`);
  await sleep(700);
  const m2 = await send('Page.getLayoutMetrics');
  const s2 = m2.cssContentSize || m2.contentSize;
  const shot2 = await send('Page.captureScreenshot', {
    format: 'png', captureBeyondViewport: true,
    clip: { x: 0, y: 0, width: Math.ceil(s2.width), height: Math.min(Math.ceil(s2.height), 14000), scale: 1 },
  });
  fs.writeFileSync(path.join(OUT, 'desktop-light.png'), Buffer.from(shot2.data, 'base64'));
  report.diag.light = await evaluate(DIAG);

  report.consoleErrors = consoleErrors;
  report.failedRequests = failedRequests;
  fs.writeFileSync(path.join(OUT, 'report.json'), JSON.stringify(report, null, 2));
  console.log(JSON.stringify(report, null, 2));
  try { ws.close(); } catch (_) {}
  process.exit(0);
})().catch((e) => { console.error('FAILED: ' + ((e && e.stack) || e)); process.exit(1); });
