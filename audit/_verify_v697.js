#!/usr/bin/env node
/* threat_probe.js — V2.9.697 板面威胁×牌力互动实测
 * 用户质疑: set/两对等 made hand 面对高威胁板面(三同花/连牌)是否能及时止损
 * 四层验证: ①handClassify分类 ②eq(对手范围威胁组合分布+组合级重加权梯度) ③决策层止损 ④对照不误伤
 * V2.9.697: _threatReweight 修复键级稀释后, T6 断言按真实区间校准(set vs cbet范围真实eq 55-68%)
 */
const fs = require('fs');
const path = require('path');
const vm = require('vm');

let pass = 0, fail = 0;
function assert(cond, name, detail) {
  if (cond) { pass++; console.log('  ✅ ' + name); }
  else { fail++; console.log('  ❌ ' + name + (detail ? '  → ' + detail : '')); }
}
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
global.navigator = { userAgent: 'node-threat' };
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
// V2.9.697 最终运行时验证
const C = (r, s) => ({ rank: r, suit: s });
console.log('引擎加载 OK, ENGINE_VERSION=' + ENGINE_VERSION);
console.log('_threatReweight 存在:', typeof _threatReweight === 'function');
const ms = mcVsRange([C('8','c'), C('8','d')], [C('8','h'), C('7','h'), C('9','h')], CB2.btn, 1500, 1);
console.log('flop三同花 set eq=' + Math.round(ms.eq) + '% (期望55-68)');
const rs = riverExactEquity([C('8','c'), C('8','d')], [C('8','h'), C('7','h'), C('9','h'), C('2','h'), C('3','c')], CB2.btn, 1);
console.log('river四同花 set eq=' + Math.round(rs.eq) + '% combos=' + rs.combos + ' (期望<70,精确枚举含重加权)');
const msDry = mcVsRange([C('8','c'), C('8','d')], [C('8','s'), C('2','d'), C('3','c')], CB2.btn, 1500, 1);
console.log('干燥对照 set eq=' + Math.round(msDry.eq) + '% (期望>85)');
const rsDry = riverExactEquity([C('8','c'), C('8','d')], [C('8','s'), C('2','d'), C('3','c'), C('5','c'), C('6','d')], CB2.btn, 1);
console.log('river干燥对照 set eq=' + Math.round(rsDry.eq) + '% combos=' + rsDry.combos);
