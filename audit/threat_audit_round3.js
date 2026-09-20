#!/usr/bin/env node
/* threat_audit_round3.js — 决策层补偿验证: 公对面顶对/超对的牌力分类与决策建议 */
const fs = require('fs');
const path = require('path');
const vm = require('vm');
function makeEl() {
  const el = function(){};
  return new Proxy(el, {
    get(t, prop) {
      if (prop === 'style') return {};
      if (prop === 'classList') return { add(){}, remove(){}, contains(){ return false; }, toggle(){} };
      if (prop === 'value') return '100';
      if (prop === 'innerHTML' || prop === 'textContent') return '';
      if (prop === 'length') return 0;
      if (prop === 'querySelector' || prop === 'querySelectorAll') return () => (prop === 'querySelectorAll' ? [] : makeEl());
      if (prop === 'addEventListener' || prop === 'removeEventListener') return () => {};
      if (prop === 'appendChild' || prop === 'removeChild' || prop === 'setAttribute' || prop === 'getAttribute') return () => {};
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
  addEventListener(){}, removeEventListener(){}, readyState: 'complete'
};
global.window = global;
global.localStorage = { _d:{}, getItem(k){return this._d[k]||null;}, setItem(k,v){this._d[k]=String(v);}, removeItem(k){delete this._d[k];} };
global.sessionStorage = global.localStorage;
global.navigator = { userAgent: 'node-audit' };
global.location = { href: '', reload(){}, search: '', hash: '' };
global.scrollTo = () => {};
global.Worker = class { postMessage(){} terminate(){} addEventListener(){} };
global.Blob = class {};
global.URL = { createObjectURL(){ return ''; }, revokeObjectURL(){} };
global.requestAnimationFrame = () => 0; global.cancelAnimationFrame = () => {};
global.setTimeout = () => 0; global.setInterval = () => 0;
global.clearTimeout = () => {}; global.clearInterval = () => {};
global.XMLHttpRequest = function(){ this.open=()=>{}; this.send=()=>{}; this.setRequestHeader=()=>{}; this.addEventListener=()=>{}; };
global.fetch = () => Promise.resolve({ json: () => Promise.resolve({}), text: () => Promise.resolve('') });
global.performance = { now: () => Date.now() };
global.AndroidBridge = {
  showAdvice(){}, autoDecision(){}, confirmVisionReceived(){}, autoCaptureVisionComplete(){},
  logDecision(){}, logSelfDecision(){}, selfHandResult(){}, opponentStats(){}, triggerMultiFrame(){},
  getSelfLearnData(){ return '{}'; }, getLearnedProfile(){ return '{}'; }, getErrorLogs(){ return '[]'; },
  getDiagData(){ return '{}'; }, getPipelineTiming(){ return '{}'; }, isAutoCaptureOn(){ return true; },
  setAutoSpeed(){}, setBlinkFreq(){}, updateNotification(){}, resetSelfLearn(){}, updateStatus(){}, notifyCrash(){}
};
const htmlPath = path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'poker_helper.html');
const html = fs.readFileSync(htmlPath, 'utf8');
const code = [...html.matchAll(/<script(?![^>]*\bsrc=)[^>]*>([\s\S]*?)<\/script>/gi)].map(m => m[1]).join('\n;\n');
try { vm.runInThisContext(code); } catch (e) { console.error('引擎加载失败: ' + e.message); process.exit(2); }
const C = (r, s) => ({ rank: r, suit: s });
const G_ = global;

console.log('\n================================================================');
console.log(' 决策层补偿验证: handClassify 在公对面的牌力分类');
console.log('================================================================');
const cases = [
  { nm: '顶对AK@公对面 8c8sKh', hole: [C('A','c'), C('K','d')], comm: [C('8','c'), C('8','s'), C('K','h')] },
  { nm: '顶对AK@对照 8c5sKh',   hole: [C('A','c'), C('K','d')], comm: [C('8','c'), C('5','s'), C('K','h')] },
  { nm: '超对TT@公对面 8c8sKh', hole: [C('T','c'), C('T','d')], comm: [C('8','c'), C('8','s'), C('K','h')] },
  { nm: '超对TT@对照 8c5sKh',   hole: [C('T','c'), C('T','d')], comm: [C('8','c'), C('5','s'), C('K','h')] },
  { nm: '两对A8@公对面 8c8sKh', hole: [C('A','c'), C('8','d')], comm: [C('8','c'), C('8','s'), C('K','h')] },  // hero trips!
  { nm: 'set KK@公对面 8c8sKh', hole: [C('K','c'), C('K','d')], comm: [C('8','c'), C('8','s'), C('K','h')] },  // hero set+FH听
];
for (const c of cases) {
  const r = G_.handClassify(c.hole, c.comm);
  console.log('  ' + c.nm.padEnd(26) + ' → ' + r.name + ' / ' + (r.desc || '') + (r.detail ? (' / ' + r.detail) : ''));
}
// 双公对面 hero 强牌
const cases2 = [
  { nm: '顶对AK@双公对 8c8sKdKh', hole: [C('A','c'), C('Q','d')], comm: [C('8','c'), C('8','s'), C('K','d'), C('K','h')] }, // AQ=顶对A? board KK88 → hero A kicker
  { nm: '葫芦线 88@双公对 8c8sKdKh', hole: [C('8','d'), C('8','h')], comm: [C('8','c'), C('8','s'), C('K','d'), C('K','h')] }, // hero quads
];
for (const c of cases2) {
  const r = G_.handClassify(c.hole, c.comm);
  console.log('  ' + c.nm.padEnd(26) + ' → ' + r.name + ' / ' + (r.desc || '') + (r.detail ? (' / ' + r.detail) : ''));
}
console.log('\n(对照: 引擎对三花面的降级日志见 threat_probe; 公对面顶对/超对若无降级 → 决策层也无补偿)');
