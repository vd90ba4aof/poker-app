#!/usr/bin/env node
/* threat_audit_round2.js — 补充实验: 公对面威胁 + A高面支配 + 范围构成 */
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
function eqAvg(hole, comm, range, iter, nOpp, reps) {
  reps = reps || 3; let s = 0;
  for (let i = 0; i < reps; i++) s += G_.mcVsRange(hole, comm, range, iter, nOpp || 1).eq;
  return s / reps;
}

console.log('\n================================================================');
console.log(' 补充实验: 公对面威胁 / A高面支配 / 对手范围构成');
console.log('================================================================');

// F1: 修正后的公对面 set 实验 — 9d9h set@8c8s9s (hero 999, 对手8x=888 trips, 98/88=FH/quads支配线)
{
  const hole = [C('9','d'), C('9','h')];
  const commP = [C('8','c'), C('8','s'), C('9','s')];   // 公对面: 88+9, hero=999
  const commD = [C('8','c'), C('2','s'), C('9','s')];   // 对照: 非公对同高牌面, hero=999
  for (const [nm, cc] of [['公对面 8c8s9s', commP], ['对照面 8c2s9s', commD]]) {
    const bt = G_.boardTexture(cc);
    const spec = G_.postflopEqSpec(hole, cc, 'bet', 50, 100, bt, 2);
    const eq = eqAvg(hole, cc, spec.range, 1500, 1);
    console.log('  F1 ' + nm + ': category=' + bt.category + ' line=' + spec.line + ' eq=' + eq.toFixed(1) + '%');
  }
}
// F2: 顶对@公对面 vs 顶对@非公对 (trips/FH 支配威胁)
{
  const hole = [C('A','c'), C('K','d')];
  const commP = [C('8','c'), C('8','s'), C('K','h')];   // 公对面: 88+K, hero=顶对K
  const commD = [C('8','c'), C('5','s'), C('K','h')];   // 对照: 85K
  for (const [nm, cc] of [['顶对@公对面 8c8sKh', commP], ['顶对@对照 8c5sKh', commD]]) {
    const bt = G_.boardTexture(cc);
    const spec = G_.postflopEqSpec(hole, cc, 'bet', 50, 100, bt, 2);
    const eq = eqAvg(hole, cc, spec.range, 1500, 1);
    // 统计对手持8组合
    const known = [].concat(hole, cc);
    let opp = [], t8 = 0;
    for (const key of spec.range) for (const h of G_.handKeyToCards(key)) {
      const cl = known.some(c => (c.rank===h[0].rank&&(c.suit===h[0].suit||!c.suit))) || known.some(c => (c.rank===h[1].rank&&(c.suit===h[1].suit||!c.suit)));
      if (!cl) { opp.push(h); if (h[0].rank==='8'||h[1].rank==='8') t8++; }
    }
    console.log('  F2 ' + nm + ': category=' + bt.category + ' line=' + spec.line + ' eq=' + eq.toFixed(1) + '%  对手组合=' + opp.length + ' 持8(trips)=' + t8 + '(' + (t8/opp.length*100).toFixed(1) + '%)');
  }
}
// F3: 超对@公对面 (TT@8c8sKh — 被Kx/8x/88/99... 支配)
{
  const hole = [C('T','c'), C('T','d')];
  const commP = [C('8','c'), C('8','s'), C('K','h')];
  const commD = [C('8','c'), C('5','s'), C('K','h')];
  for (const [nm, cc] of [['超对TT@公对面', commP], ['超对TT@对照', commD]]) {
    const bt = G_.boardTexture(cc);
    const spec = G_.postflopEqSpec(hole, cc, 'bet', 50, 100, bt, 2);
    const eq = eqAvg(hole, cc, spec.range, 1500, 1);
    console.log('  F3 ' + nm + ': category=' + bt.category + ' line=' + spec.line + ' eq=' + eq.toFixed(1) + '%');
  }
}
// F4: A高面顶对支配 — KQ@Ac9d5s 对比 KQ@Kc9d5s (顶对K vs 顶对A)
{
  const hole1 = [C('K','c'), C('Q','d')];
  const commA = [C('A','c'), C('9','d'), C('5','s')];   // A面: KQ=次顶对
  const hole2 = [C('A','c'), C('Q','d')];
  const commK = [C('K','c'), C('9','d'), C('5','s')];   // K面: AQ=顶对顶踢
  const r1 = G_.postflopEqSpec(hole1, commA, 'bet', 50, 100, G_.boardTexture(commA), 2);
  const r2 = G_.postflopEqSpec(hole2, commK, 'bet', 50, 100, G_.boardTexture(commK), 2);
  console.log('  F4 KQ@A95(次顶对) eq=' + eqAvg(hole1, commA, r1.range, 1500, 1).toFixed(1) + '%   AQ@K95(顶对顶踢) eq=' + eqAvg(hole2, commK, r2.range, 1500, 1).toFixed(1) + '%');
  console.log('     (KQ@A95 是次顶对非顶对; 真实区间~35-50%: 对手btn cbet含Ax多)');
}
// F5: 对手 cbet 范围构成 (CB9.btn 25手)
{
  const commD = [C('8','c'), C('5','s'), C('K','h')];
  const spec = G_.postflopEqSpec([C('A','c'),C('K','d')], commD, 'bet', 50, 100, G_.boardTexture(commD), 2);
  console.log('  F5 CB默认.btn cbet范围(' + spec.range.length + '手): ' + spec.range.join(','));
  const ax = spec.range.filter(k => k[0] === 'A');
  console.log('     含A键: ' + ax.length + '手 (' + (ax.length/spec.range.length*100).toFixed(0) + '%)');
}
// F6: turn 落第3张花 (flop 2花 + turn 1花) 动态威胁检查 — _threatReweight sc=3 应生效
{
  const hole = [C('8','c'), C('8','d')];
  const comm = [C('8','h'), C('7','h'), C('2','c'), C('9','h')]; // flop 8h7h2c, turn 9h → 3张红桃
  const bt = G_.boardTexture(comm);
  const spec = G_.postflopEqSpec(hole, comm, 'bet', 100, 200, bt, 2);
  const known = [].concat(hole, comm);
  let opp = [];
  for (const key of spec.range) for (const h of G_.handKeyToCards(key)) {
    const cl = known.some(c => (c.rank===h[0].rank&&(c.suit===h[0].suit||!c.suit))) || known.some(c => (c.rank===h[1].rank&&(c.suit===h[1].suit||!c.suit)));
    if (!cl) opp.push(h);
  }
  const after = G_._threatReweight(opp, comm);
  const eq = eqAvg(hole, comm, spec.range, 1500, 1);
  console.log('  F6 turn落第3张花(8h7h2c9h): hasMonotone=' + bt.hasMonotone + ' line=' + spec.line +
    ' 重加权=' + opp.length + '→' + after.length + '(' + (after.length/opp.length).toFixed(2) + ') eq=' + eq.toFixed(1) + '%');
  console.log('     (动态威胁: turn第3花落地时加权生效, 但极化线不切)');
}
console.log('\n补充实验完成');
