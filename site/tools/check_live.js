'use strict';
/**
 * 线上自检（零依赖，Node 18+）。
 *   1) 抓首页 HTML，解析出所有同源资源并逐个请求，报告状态码 / Content-Type / 字节数
 *   2) 列出页面引用的所有外部域名（用于确认没有第三方 CDN / 统计）
 *   3) 单独用 HEAD 校验 APK 下载链接（避免真拉 23MB）
 *
 * 用法：node tools/check_live.js [baseUrl]
 * 退出码：0 = 全部符合预期；1 = 有异常
 *
 * 说明：本机（Windows）PowerShell/curl 的 Schannel 取不到凭证，直连公网会
 * `SEC_E_NO_CREDENTIALS`；Node 自带 OpenSSL，所以这个脚本在本机可以直接跑。
 */
const BASE = (process.argv[2] || 'https://k.corvinyu.icu/').replace(/\/?$/, '/');
// 0.2.0 起页面上的界面图形全部内联（HTML/CSS/SVG），assets/ 只剩图标与 OG 图 ⇒ 不再有预期 404 的插槽
const EXPECT_MISSING = [];
// 页面外引用、但值得一并体检的文件
const EXTRA = ['assets/og.png', 'assets/favicon.ico', 'assets/apple-touch-icon.png'];
// SEO 基础设施：爬虫入口，必须公开可访问（0.2.1 起新增，之前是 404 ⇒ 站点搜不到）
const SEO = ['robots.txt', 'sitemap.xml'];

(async () => {
  const res = await fetch(BASE);
  const html = await res.text();
  console.log(`页面 ${BASE}\n  HTTP ${res.status}  type=${res.headers.get('content-type')}  html=${html.length} 字节\n`);

  const refs = new Set();
  for (const m of html.matchAll(/(?:src|href)="([^"]+)"/g)) refs.add(m[1].trim());
  const local = [...refs].filter(
    (r) => r && !/^(https?:)?\/\//.test(r) && !r.startsWith('#') && !/^(mailto|tel|data):/.test(r)
  ).map((r) => r.replace(/^\.\//, ''));

  const apks = local.filter((r) => r.endsWith('.apk'));
  const targets = [...new Set([...local.filter((r) => !r.endsWith('.apk')), ...EXTRA])];

  let ok = 0, bad = 0;
  for (const t of targets) {
    const url = new URL(t, BASE).href;
    const r = await fetch(url);
    const buf = await r.arrayBuffer();
    const mayMiss = EXPECT_MISSING.some((n) => t.endsWith(n));
    const good = r.ok || (mayMiss && r.status === 404);
    good ? ok++ : bad++;
    console.log(`  ${good ? '✅' : '❌'} ${String(r.status).padEnd(4)} ${String(r.headers.get('content-type') || '-').padEnd(30)} ${String(buf.byteLength).padStart(8)}  ${t}`);
  }

  console.log('');
  for (const a of [...new Set(apks)]) {
    const url = new URL(a, BASE).href;
    const r = await fetch(url, { method: 'HEAD' });
    const len = r.headers.get('content-length');
    const good = r.ok && len;
    good ? ok++ : bad++;
    console.log(`  ${good ? '✅' : '❌'} ${String(r.status).padEnd(4)} ${String(r.headers.get('content-type') || '-').padEnd(30)} ${String(len || '-').padStart(8)}  ${a}`);
  }

  const ext = new Set();
  for (const m of html.matchAll(/https?:\/\/([^/"'\s)>]+)/g)) ext.add(m[1]);
  console.log(`\n页面引用的外部域名（${ext.size} 个）：`);
  for (const d of [...ext].sort()) console.log('  - ' + d);

  // ---- SEO 基础设施 ----
  console.log('\nSEO 基础设施：');
  for (const f of SEO) {
    const url = new URL(f, BASE).href;
    try {
      const r = await fetch(url);
      const txt = await r.text();
      const need = f === 'robots.txt' ? 'Sitemap:' : '<loc>';
      const good = r.ok && txt.includes(need);
      good ? ok++ : bad++;
      console.log(`  ${good ? '✅' : '❌'} ${String(r.status).padEnd(4)} ${f.padEnd(14)} 含 ${need} = ${txt.includes(need)}`);
    } catch (e) {
      bad++;
      console.log(`  ❌ ${f} 请求失败：${e.message}`);
    }
  }
  const seoChecks = [
    ['canonical', /<link[^>]+rel="canonical"[^>]+href="https:\/\/k\.corvinyu\.icu\/"/],
    ['JSON-LD SoftwareApplication', /"@type"\s*:\s*"SoftwareApplication"/],
    ['JSON-LD 应用名', /"name"\s*:\s*"海大取件码"/],
    ['JSON-LD 版本号', /"softwareVersion"\s*:\s*"[\d.]+"/],
  ];
  for (const [label, re] of seoChecks) {
    const good = re.test(html);
    good ? ok++ : bad++;
    console.log(`  ${good ? '✅' : '❌'} 页面 ${label}`);
  }

  console.log(`\n结果：同源资源 ${ok} 项正常 / ${bad} 项异常`);
  process.exit(bad === 0 ? 0 : 1);
})().catch((e) => {
  const c = e && e.cause;
  console.error('FAILED: ' + ((e && e.message) || e) +
    (c ? `  (cause: ${c.message || c.code || c})` : ''));
  console.error('提示：本机直连 HTTPS 若失败，可设代理后重试 ——' +
    ' $env:HTTPS_PROXY="http://127.0.0.1:8890"; $env:NODE_USE_ENV_PROXY="1"');
  process.exit(1);
});
