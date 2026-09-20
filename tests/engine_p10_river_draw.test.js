#!/usr/bin/env node
/*
 * engine_p10_river_draw.test.js — 河牌「误判听牌」回归门禁 (V2.9.694 P10)
 *
 * 缺陷: handClassify 的 flushDraw 判定缺 comm=5 守卫 →
 *       河牌 4 同花面(手牌含该花色)被标 flushDraw=true → madeType='DRAW' → _hc2key→9/10
 *       → _RIV 表听牌档 → 引擎以 eq 0~5% 纯空气牌主动 bet 55~73% pot。
 *
 * 实测影响面(修复前):
 *   - 河牌被判 DRAW: 8.6% ~ 9.3% (枚举 3000~4000 手)
 *   - 这些场景平均边际 EV = -2.88BB/手
 *   - 河牌主动下注率 55~58% → 修复后 43~47%
 *
 * 判定: 任一断言失败 → exit 1（CI 红牌阻断发布）
 * 用法: node tests/engine_p10_river_draw.test.js [poker_helper.html路径]
 */
'use strict';
const fs = require('fs');
const path = require('path');
const vm = require('vm');

let pass = 0, fail = 0;
const failures = [];
function assert(cond, name, detail) {
  if (cond) { pass++; console.log('  ✅ ' + name); }
  else { fail++; failures.push(name + (detail ? '  → ' + detail : '')); console.log('  ❌ ' + name + (detail ? '  → ' + detail : '')); }
}

// ---------- 加载真实 HTML 引擎 ----------
const htmlPath = process.argv[2] || path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'poker_helper.html');
if (!fs.existsSync(htmlPath)) { console.error('找不到引擎文件: ' + htmlPath); process.exit(2); }
const html = fs.readFileSync(htmlPath, 'utf8');
const code = [...html.matchAll(/<script(?![^>]*\bsrc=)[^>]*>([\s\S]*?)<\/script>/gi)].map(m => m[1]).join('\n;\n');

// ---------- 通用 mock DOM ----------
function makeEl() {
  const el = function () {};
  return new Proxy(el, {
    get(t, prop) {
      if (prop === 'style') return {};
      if (prop === 'classList') return { add() {}, remove() {}, contains() { return false; }, toggle() {} };
      if (prop === 'dataset') return {};
      if (prop === 'value') return '100';
      if (prop === 'innerHTML' || prop === 'textContent' || prop === 'href') return '';
      if (prop === 'length') return 0;
      if (prop === 'offsetHeight' || prop === 'offsetWidth') return 0;
      if (prop === 'readyState') return 'complete';
      if (prop === 'getContext') return () => null;
      if (prop === 'querySelector' || prop === 'querySelectorAll') return () => (prop === 'querySelectorAll' ? [] : makeEl());
      if (prop === 'addEventListener' || prop === 'removeEventListener') return () => {};
      if (prop === 'appendChild' || prop === 'removeChild' || prop === 'setAttribute' ||
        prop === 'getAttribute' || prop === 'removeAttribute' || prop === 'focus' ||
        prop === 'click' || prop === 'blur' || prop === 'scrollIntoView' || prop === 'insertBefore' ||
        prop === 'cloneNode' || prop === 'contains' || prop === 'dispatchEvent' || prop === 'remove') return () => (prop === 'contains' ? false : (prop === 'cloneNode' ? makeEl() : undefined));
      if (prop === 'children' || prop === 'childNodes') return [];
      if (prop === 'parentNode' || prop === 'firstChild' || prop === 'lastChild') return makeEl();
      return undefined;
    },
    set() { return true; },
    apply() { return makeEl(); }
  });
}
const elCache = {};
global.document = {
  getElementById(id) { if (!elCache[id]) elCache[id] = makeEl(); return elCache[id]; },
  querySelector() { return makeEl(); }, querySelectorAll() { return []; },
  createElement() { return makeEl(); }, createTextNode() { return makeEl(); },
  body: makeEl(), documentElement: makeEl(), head: makeEl(),
  addEventListener() {}, removeEventListener() {}, readyState: 'complete'
};
global.window = global;
global.localStorage = { _d: {}, getItem(k) { return this._d[k] || null; }, setItem(k, v) { this._d[k] = String(v); }, removeItem(k) { delete this._d[k]; } };
global.sessionStorage = global.localStorage;
// Node 22 的 navigator 是只读 getter, 须用 defineProperty 覆盖
try {
  Object.defineProperty(global, 'navigator', { value: { userAgent: 'node-p10' }, writable: true, configurable: true });
} catch (e) { global.navigator = { userAgent: 'node-p10' }; }
global.location = { href: '', reload() {}, search: '', hash: '' };
global.history = { pushState() {}, replaceState() {} };
global.scrollTo = () => {};
global.Worker = class { postMessage() {} terminate() {} addEventListener() {} };
global.Blob = class {};
global.URL = { createObjectURL() { return ''; }, revokeObjectURL() {} };
global.requestAnimationFrame = () => 0;
global.cancelAnimationFrame = () => {};
global.setTimeout = () => 0;
global.setInterval = () => 0;
global.clearTimeout = () => {};
global.clearInterval = () => {};
global.XMLHttpRequest = function () { this.open = () => {}; this.send = () => {}; this.setRequestHeader = () => {}; this.addEventListener = () => {}; };
global.fetch = () => Promise.resolve({ json: () => Promise.resolve({}), text: () => Promise.resolve('') });
global.performance = { now: () => Date.now() };
global.AndroidBridge = {
  showAdvice() {}, autoDecision() {}, confirmVisionReceived() {}, autoCaptureVisionComplete() {},
  logDecision() {}, logSelfDecision() {}, selfHandResult() {}, opponentStats() {}, triggerMultiFrame() {},
  getSelfLearnData() { return '{}'; }, getLearnedProfile() { return '{}'; }, getErrorLogs() { return '[]'; },
  getDiagData() { return '{}'; }, getPipelineTiming() { return '{}'; }, isAutoCaptureOn() { return true; },
  setAutoSpeed() {}, setBlinkFreq() {}, updateNotification() {}, resetSelfLearn() {}, updateStatus() {}, notifyCrash() {}
};

try { vm.runInThisContext(code); } catch (e) { console.error('引擎加载失败: ' + e.message); process.exit(2); }
if (typeof handClassify !== 'function') { console.error('引擎关键符号缺失: handClassify'); process.exit(2); }

// ---------- 工具 ----------
function h2(s) { const o = []; for (let i = 0; i < s.length; i += 2) o.push({ rank: s[i], suit: s[i + 1] }); return o; }
function hc(h, b) {
  const x = handClassify(h2(h), h2(b));
  return { n: x.name, o: x.outs, d: x.desc };
}
const SUITS = ['s', 'h', 'd', 'c'], RANKS = ['2', '3', '4', '5', '6', '7', '8', '9', 'T', 'J', 'Q', 'K', 'A'];
const ALL = []; for (const r of RANKS) for (const s of SUITS) ALL.push({ rank: r, suit: s });

console.log('\n===== P10-A: 河牌4同花面(手牌含该花色) → 不得为 DRAW =====');
[
  ['4d8d', '5s2s7c2dJd', '板 d:2d,Jd + 手牌 4d,8d = 4同花'],
  ['As4d', '8d3h3c5dQd', '板 d:8d,5d,Qd + 手牌 4d = 4同花'],
  ['Qh8d', 'Ks4dAd3c5d', '板 d:4d,Ad,5d + 手牌 8d = 4同花'],
  ['Tc4d', 'Ks7d3d8s6d', '板 d:7d,3d,6d + 手牌 4d = 4同花'],
].forEach(([h, b, label]) => {
  const r = hc(h, b);
  assert(r.n !== 'DRAW', `P10-A ${h}@${b} 河牌不为DRAW (${label})`, `实际=${r.n}/${r.d} outs=${r.o}`);
});

console.log('\n===== P10-B: 河牌不得产出「听牌型」outs(6/8/9/12) =====');
[
  ['4d8d', '5s2s7c2dJd'], ['As4d', '8d3h3c5dQd'], ['KcTh', '5cKdQc7c6d'],
].forEach(([h, b]) => {
  const r = hc(h, b);
  assert(![6, 8, 9, 12].includes(r.o), `P10-B ${h}@${b} 河牌outs=${r.o} 非听牌值`, `outs=${r.o}`);
});

console.log('\n===== P10-C: 河牌 DRAW 比例必须为 0 (枚举 3000 手) =====');
{
  const N = 3000;
  let nDraw = 0;
  for (let i = 0; i < N; i++) {
    const d = ALL.slice();
    for (let j = 0; j < 7; j++) { const k = j + Math.floor(Math.random() * (d.length - j)); const t = d[j]; d[j] = d[k]; d[k] = t; }
    const x = handClassify([d[0], d[1]], [d[2], d[3], d[4], d[5], d[6]]);
    if (x.name === 'DRAW') nDraw++;
  }
  assert(nDraw === 0, `P10-C 河牌DRAW比例 0.0% (实测 ${(nDraw / N * 100).toFixed(1)}%)`, `${nDraw}/${N}`);
}

console.log('\n===== P10-D: 转牌/翻牌真听牌【必须保留】DRAW (防误伤) =====');
[
  ['4d8d', '5d2s7c2d', '转牌 4同花真听'],
  ['4d8d', '5d2s7c', '翻牌 4同花真听'],
  ['Js7s', '8cTs8sQc', '转牌 4同花真听'],
].forEach(([h, b, label]) => {
  const r = hc(h, b);
  assert(r.n === 'DRAW', `P10-D ${label} ${h}@${b} 保持DRAW`, `实际=${r.n}/${r.d}`);
});

console.log('\n===== P10-E: 河牌已成花/坚果牌【不得降级】 =====');
{
  const r = hc('AdKd', '2d7d9dJh3c');
  assert(r.n === 'NUTS', 'P10-E 河牌坚果同花 AdKd@2d7d9dJh3c = NUTS', `实际=${r.n}/${r.d}`);
}

console.log('\n===== P10-F: 幂等性 (同输入同输出) =====');
{
  const a = hc('4d8d', '5s2s7c2dJd'), b = hc('4d8d', '5s2s7c2dJd');
  assert(a.n === b.n && a.o === b.o && a.d === b.d, 'P10-F 幂等', JSON.stringify(a) + ' vs ' + JSON.stringify(b));
}

console.log('\n===== P10-G: 确定性 — 分类器不依赖随机数 =====');
{
  let ok = true;
  for (let i = 0; i < 20; i++) {
    if (hc('3c6s', 'TcKh7cQc5d').n !== hc('3c6s', 'TcKh7cQc5d').n) { ok = false; break; }
  }
  assert(ok, 'P10-G 20 次重跑分类稳定');
}

console.log('\n========================================');
console.log(`[V2.9.694 P10] 总计: pass=${pass} fail=${fail}`);
if (fail > 0) { console.log('失败项:\n  - ' + failures.join('\n  - ')); process.exit(1); }
console.log('✅ 全部通过');
