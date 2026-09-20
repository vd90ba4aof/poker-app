#!/usr/bin/env node
/* threat_probe.js — V2.9.696 板面威胁×牌力互动实测
 * 用户质疑: set/两对等 made hand 面对高威胁板面(三同花/连牌)是否能及时止损
 * 三层验证: ①handClassify分类 ②eq(对手范围是否含已成同花/顺子) ③决策层止损
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
// === dump 范围真相 ===
const C = (r, s) => ({ rank: r, suit: s });
// 1. CB2.btn / CB6.mp 静态内容
console.log('CB2.btn 长度=' + CB2.btn.length);
console.log('CB2.btn = ' + CB2.btn.join(','));
console.log('CB6.mp 长度=' + CB6.mp.length);
console.log('CB6.mp = ' + CB6.mp.join(','));
// 2. getOppRange 实际返回(默认G: tt=6, raiserRole=unknown → mp)
let r1 = getOppRange('postflop', 'cbet', 'wet');
console.log('\n默认G(tt=6) getOppRange(cbet,wet) 长度=' + r1.length + ' = ' + r1.join(','));
// 3. 2人桌场景(探针第3层): tt=2 → _fp('mp')='btn' → CB2.btn
G.tt = 2;
let r2 = getOppRange('postflop', 'cbet', 'wet');
console.log('\ntt=2 getOppRange(cbet,wet) 长度=' + r2.length);
const flushKeys = r2.filter(k => k.length === 3 && k[2] === 's');
console.log('  其中同花键 ' + flushKeys.length + ' 个: ' + flushKeys.join(','));
// 4. 展开验证: AJs 在 8h7h9h 板上含 AhJh?
const ex = handKeyToCards('AJs');
console.log('\nAJs 展开组合: ' + ex.map(h => h[0].rank + h[0].suit + h[1].rank + h[1].suit).join(' '));
// 5. 关键实测: 8c8d@8h7h9h vs CB2.btn eq
const hole = [C('8','c'), C('8','d')];
const comm = [C('8','h'), C('7','h'), C('9','h')];
const ms = mcVsRange(hole, comm, r2, 3000, 1);
console.log('\nset@monotone(8c8d@8h7h9h) vs CB2.btn(54手) eq = ' + (Math.round(ms.eq*10)/10) + '%');
// 6. 对照: vs 全对子范围
const pairsOnly = r2.filter(k => k.length === 2);
const ms2 = mcVsRange(hole, comm, pairsOnly, 3000, 1);
console.log('set@monotone vs 纯对子(' + pairsOnly.length + '手) eq = ' + (Math.round(ms2.eq*10)/10) + '%');
// 7. 对照: vs 人为注入同花威胁(基线+该板面所有已成同花键)
const rHeart = ['AKs','AQs','AJs','ATs','A9s','A8s','A7s','A6s','A5s','A4s','A3s','A2s','KQs','KJs','KTs','K9s','K8s','K7s','K6s','K5s','K4s','K3s','K2s','QJs','QTs','Q9s','Q8s','Q7s','Q6s','Q5s','Q4s','Q3s','Q2s','JTs','JT'+'s','J9s','J8s','J7s','J6s','T9s','T8s','T7s','98s','97s','96s','87s','86s','76s','75s','65s','64s','54s','53s','43s','42s','32s'].filter((v,i,a)=>a.indexOf(v)===i);
const ms3 = mcVsRange(hole, comm, rHeart, 3000, 1);
console.log('set@monotone vs 全同花键面 eq = ' + (Math.round(ms3.eq*10)/10) + '%');
