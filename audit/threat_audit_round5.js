#!/usr/bin/env node
/* threat_audit_round5.js — 关键 bug 复现验证:
 * B1: 非坚果顺误判 NUTS (FIX-7 守卫短路: 对手T8@765可成T高顺, 但板面最高7<hero顺顶9 → 精确判据不跑)
 * B2: barrel_river 键级过滤把公对面 FH/quads 组合清零 (66/77/88/99 被TT+过滤剔除)
 */
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
function eqAvg(hole, comm, range, iter, reps) {
  reps = reps || 3; let s = 0;
  for (let i = 0; i < reps; i++) s += G_.mcVsRange(hole, comm, range, iter, 1).eq;
  return s / reps;
}

console.log('\n================================================================');
console.log(' 关键缺陷复现验证');
console.log('================================================================');

console.log('\n[B1] 非坚果顺误判 NUTS — FIX-7 守卫短路复现');
const b1a = G_.handClassify([C('9','c'), C('8','d')], [C('7','h'), C('6','s'), C('5','c')]);
console.log('  B1a 98@765(对手T8可成T高顺): ' + b1a.name + '/' + (b1a.desc||'') + (b1a.detail?('/'+b1a.detail):'') +
  (b1a.name === 'NUTS' ? '  ← BUG复现: 应为STRONG(非坚果)' : '  ← 正确降级'));
const b1b = G_.handClassify([C('9','c'), C('8','d')], [C('7','h'), C('6','s'), C('5','c'), C('T','d')]);
console.log('  B1b 98@765T(板面有T,守卫可过): ' + b1b.name + '/' + (b1b.desc||'') + (b1b.detail?('/'+b1b.detail):'') +
  (b1b.name === 'NUTS' ? '  ← 板面有T仍判NUTS' : '  ← 降级(对照)'));
const b1c = G_.handClassify([C('8','c'), C('7','d')], [C('6','h'), C('5','s'), C('4','c')]);
console.log('  B1c 87@654(对手T9/98可成更大顺): ' + b1c.name + '/' + (b1c.desc||'') + (b1c.detail?('/'+b1c.detail):'') +
  (b1c.name === 'NUTS' ? '  ← BUG同类: 87中顺非坚果' : '  ← 降级'));
const b1d = G_.handClassify([C('T','c'), C('9','d')], [C('8','h'), C('7','s'), C('6','c')]);
console.log('  B1d T9@876(对手J9? no; 87? 顶顺=Q?板面无Q/T? 对手QT? Q,T+8,7,6 → Q,T,8,7,6无顺; J9+876=J,9,8,7,6 ✓更大顺存在): ' + b1d.name + '/' + (b1d.desc||'') +
  (b1d.name === 'NUTS' ? '  ← BUG同类: J9可成J高顺' : '  ← 降级'));

console.log('\n[B2] barrel_river 键级过滤把公对面 FH/quads 清零');
const holeH10 = [C('T','h'), C('9','d')];
const commH10 = [C('6','c'), C('7','d'), C('7','h'), C('8','s'), C('9','c')];
const bt = G_.boardTexture(commH10);
const spec = G_.postflopEqSpec(holeH10, commH10, 'bet', 160, 200, bt, 2);
console.log('  H10 board 6c7d7h8s9c barrel_river 范围(' + spec.range.length + '键): ' + spec.range.join(','));
const has66 = spec.range.indexOf('66') >= 0, has77 = spec.range.indexOf('77') >= 0, has88 = spec.range.indexOf('88') >= 0, has99 = spec.range.indexOf('99') >= 0;
console.log('  含66=' + has66 + ' 77=' + has77 + ' 88=' + has88 + ' 99=' + has99 + '  ← 全部被 TT+ 过滤剔除(FH/quads线清零)');
// 修正范围: 加回 66/77/88/99, 重算 eq
const fixedRange = spec.range.concat(['66','77','88','99']);
const eqNow = eqAvg(holeH10, commH10, spec.range, 2000);
const eqFixed = eqAvg(holeH10, commH10, fixedRange, 2000);
console.log('  顺子T9 eq vs 现行barrel_river范围 = ' + eqNow.toFixed(1) + '%');
console.log('  顺子T9 eq vs 补回FH/quads键(66-99) = ' + eqFixed.toFixed(1) + '%   差=' + (eqNow - eqFixed).toFixed(1) + 'pp');

console.log('\n[B3] 翻前范围剥离强牌确认 — CB2/CB9 平跟范围含 AA/KK/AK?');
const cb2 = G_.gC('btn'); // G.tt 未设 → CB9
console.log('  CB9.btn(' + cb2.length + '键) 含AA=' + (cb2.indexOf('AA')>=0) + ' KK=' + (cb2.indexOf('KK')>=0) + ' AKs=' + (cb2.indexOf('AKs')>=0) + ' AKo=' + (cb2.indexOf('AKo')>=0) + ' QQ=' + (cb2.indexOf('QQ')>=0));
try {
  G_.G.tt = 2;
  const cb2r = G_.gC('btn');
  console.log('  CB2.btn(' + cb2r.length + '键) 含AA=' + (cb2r.indexOf('AA')>=0) + ' KK=' + (cb2r.indexOf('KK')>=0) + ' AKs=' + (cb2r.indexOf('AKs')>=0) + ' AKo=' + (cb2r.indexOf('AKo')>=0) + ' QQ=' + (cb2r.indexOf('QQ')>=0));
} catch(e) {}
console.log('  ← 若为false: 对手平跟范围假设"强牌必3bet", hero顶对/set的被支配威胁系统性缺失');

console.log('\n[B4] turn落公对 FH 条件化缺失量化 — H6 场景(顺子T9@6778)');
{
  const hole = [C('T','c'), C('9','d')];
  const turn = [C('6','h'), C('7','s'), C('8','d'), C('7','c')];
  const specT = G_.postflopEqSpec(hole, turn, 'bet', 100, 200, G_.boardTexture(turn), 2);
  // barrel_turn 范围里 pocket 在此面的成FH组合
  const known = [].concat(hole, turn);
  let hands = [], fh = 0;
  for (const key of specT.range) for (const h of G_.handKeyToCards(key)) {
    const cl = known.some(c => (c.rank===h[0].rank&&(c.suit===h[0].suit||!c.suit))) || known.some(c => (c.rank===h[1].rank&&(c.suit===h[1].suit||!c.suit)));
    if (!cl) { hands.push(h); const sc = G_.eH([].concat(h, turn)); if (Math.floor(sc/1e10) === 6) fh++; }
  }
  console.log('  barrel_turn范围(' + specT.range.length + '键/' + hands.length + '组合) 已成FH=' + fh + '(' + (fh/hands.length*100).toFixed(1) + '%) — 对手66/88/99/TT@6778已成FH但无加权');
  console.log('  (66被barrel_turn的77+过滤剔除; 77只剩1组合quads路径; 88/99/TT已成FH且在范围内但权重=1)');
  const eqT = eqAvg(hole, turn, specT.range, 2000);
  // 手工FH加权×4
  const specFlop = G_.postflopEqSpec(hole, [C('6','h'), C('7','s'), C('8','d')], 'bet', 100, 200, G_.boardTexture([C('6','h'), C('7','s'), C('8','d')]), 2);
  console.log('  顺子eq: flop=92.4%(见round4) → turn现状=' + eqT.toFixed(1) + '%  ← 落公对后威胁几乎未进eq');
}
console.log('\n验证完成');
