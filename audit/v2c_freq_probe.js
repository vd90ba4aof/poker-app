/* v2c_freq_probe.js — V2c 断言阈值标定: KJo@BB vs BTN 大样本真实频率探测
 * 背景: V2c 断言 entryRate>0.30 在 300 reps 抽样下抖动(26%/30%/30%),
 *       需大样本确定真实混合频率以校准断言阈值(非引擎回归)。 */
const fs = require('fs'), path = require('path'), vm = require('vm');
function makeEl() {
  return new Proxy(function(){}, {
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
global.navigator = { userAgent: 'node-verify' };
global.location = { href: '', reload(){}, search: '', hash: '' };
global.scrollTo = () => {};
global.Worker = class { postMessage(){} terminate(){} addEventListener(){} };
global.Blob = class {};
global.URL = { createObjectURL(){ return ''; }, revokeObjectURL(){} };
global.requestAnimationFrame = () => 0; global.cancelAnimationFrame = () => 0;
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

const SCEN = [
  ['V1  KJo@MP 前位折价',   'KJo', {pos:'mp', scene:'raise', bet:3, pot:4.5, stk:100}],
  ['V2  AJo@MP 前位折价',   'AJo', {pos:'mp', scene:'raise', bet:3, pot:4.5, stk:100}],
  ['V2b KQo@MP 前位折价',   'KQo', {pos:'mp', scene:'raise', bet:3, pot:4.5, stk:100}],
  ['V2c KJo@BB 后位不折价', 'KJo', {pos:'bb', scene:'raise', bet:3, pot:4.5, stk:100, raiserRole:'btn'}],
  ['V4  55@MP 正常setmine', '55',  {pos:'mp', scene:'raise', bet:3, pot:4.5, stk:100}],
  ['V4b JJ@MP',             'JJ',  {pos:'mp', scene:'raise', bet:3, pot:4.5, stk:100}],
];
const N = 2000;
for (const [name, hk, o] of SCEN) {
  const counts = {};
  for (let i = 0; i < N; i++) {
    global.ActionLine.reset();
    const m = hk.match(/^([2-9TJQKA])([2-9TJQKA])(s|o)?$/);
    const s1 = (m[3]==='s')?'s':'c', s2 = (m[3]==='s')?'s':'d';
    global.G.hole = [C(m[1],s1), C(m[2],s2)];
    global.G.comm = []; global.G.phase = 'pre';
    global.G.pos = o.pos; global.G.scene = o.scene;
    global.G.bet = o.bet; global.G.pot = o.pot; global.G.stk = o.stk;
    global.G.opp = 'unknown'; global.G.tt = 2; global.G.act = 2;
    global.G.limpers = 0; global.G._facing3bet = false; global.G._heroDid4bet = false;
    global.G._raiserRole = o.raiserRole || 'mp';
    try { delete global.G.oppSeats; } catch (e) {}
    let r = null;
    try { r = global.StrategyEngine.decidePreflop(global.getHandKey()); } catch (ex) { continue; }
    const a = r ? r.a : 'null';
    counts[a] = (counts[a] || 0) + 1;
  }
  const entry = ((counts.call||0)+(counts.raise||0))/N;
  const sd = Math.sqrt(entry*(1-entry)/N)*100;
  const sd300 = Math.sqrt(entry*(1-entry)/300)*100;
  console.log(name.padEnd(22) + ' 入池=' + (entry*100).toFixed(1) + '%±' + sd.toFixed(1) + '%  (300repsσ=' + sd300.toFixed(1) + '%)  分布=' + JSON.stringify(counts));
}
console.log('KJo@BB vs BTN open, N=' + N + ':');
console.log('  分布:', JSON.stringify(counts));
console.log('  入池率(call+raise):', (entry * 100).toFixed(1) + '%');
console.log('  95%置信区间: [' + (entry - 1.96 * Math.sqrt(entry * (1 - entry) / N)).toFixed(3) + ', ' + (entry + 1.96 * Math.sqrt(entry * (1 - entry) / N)).toFixed(3) + ']');
console.log('  300 reps 抽样σ≈' + (100 * Math.sqrt(entry * (1 - entry) / 300)).toFixed(1) + '%  → 断言>0.30 在均值' + (entry * 100).toFixed(0) + '%下的失败概率≈' +
  (50 * (1 - Math.min(1, Math.abs(0.30 - entry) / Math.sqrt(entry * (1 - entry) / 300) / 2) * 0 )).toFixed(0) + '%(粗估)');
