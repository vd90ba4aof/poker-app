/* v700_field_replay.js — 实战日志疑点场景复现验证（禁止猜测,引擎实测）
 * 场景取自 poker_log_20260921_041730.json 的 62 手决策快照(倒序,字段逐一对齐) */
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

// 复现场景: [标签, 手牌, 场景参数, 日志实际动作, 日志reason摘要]
const SCEN = [
  ['S1 A2o@BB需跟1进7pot',   'A2o', {pos:'bb', scene:'raise', bet:1,   pot:7,   stk:87.5, opp:'fish'}, 'fold', '不在3B范围'],
  ['S2 T4o@BB需跟0.5',        'T4o', {pos:'bb', scene:'raise', bet:0.5, pot:2.5, stk:45,   opp:'fish'}, 'fold', '不在3B范围'],
  ['S3 A3o@CO需跟1进3.5',     'A3o', {pos:'co', scene:'raise', bet:1,   pot:3.5, stk:45,   opp:'fish'}, 'fold', '不在3B范围'],
  ['S4 96s@BTN需跟1进2.5',    '96s', {pos:'btn',scene:'raise', bet:1,   pot:2.5, stk:45,   opp:'fish'}, 'fold', '不在3B范围'],
  ['S5 QJo@BTN vs MP需跟1',   'QJo', {pos:'btn',scene:'raise', bet:1,   pot:3.5, stk:87,   opp:'fish'}, 'call', 'GTO call vs MP(69%)'],
  ['S6 22@BTN vs MP需跟1',    '22',  {pos:'btn',scene:'raise', bet:1,   pot:3.5, stk:47,   opp:'fish'}, 'call', 'GTO强牌硬底'],
  ['S7 K3o@BB vs allin40/143','K3o', {pos:'bb', scene:'allin', bet:40,  pot:143, stk:40,   opp:'fish'}, 'fold', 'Nash fold vs allin'],
  ['S8 QTo@CO vs push3BB',    'QTo', {pos:'co', scene:'raise', bet:1,   pot:2.5, stk:41,   opp:'fish'}, 'fold', 'Nash fold vs push'],
];
const N = 50; // 每场景50次(频率决策看主分布)
for (const [name, hk, o, logAct, logReason] of SCEN) {
  const counts = {}; let reasonSample = '';
  for (let i = 0; i < N; i++) {
    global.ActionLine.reset();
    const m = hk.match(/^([2-9TJQKA])([2-9TJQKA])(s|o)?$/);
    const s1 = (m[3]==='s')?'s':'c', s2 = (m[3]==='s')?'s':'d';
    global.G.hole = [C(m[1],s1), C(m[2],s2)];
    global.G.comm = []; global.G.phase = 'pre';
    global.G.pos = o.pos; global.G.scene = o.scene;
    global.G.bet = o.bet; global.G.pot = o.pot; global.G.stk = o.stk;
    global.G.opp = o.opp || 'fish'; global.G.tt = 6; global.G.act = 2;
    global.G.limpers = 0; global.G._facing3bet = false; global.G._heroDid4bet = false;
    global.G._raiserRole = 'mp';
    try { delete global.G.oppSeats; } catch (e) {}
    let r = null;
    try { r = global.StrategyEngine.decidePreflop(global.getHandKey()); } catch (ex) { continue; }
    const a = r ? r.a : 'null';
    counts[a] = (counts[a] || 0) + 1;
    if (a === logAct && !reasonSample && r && r.r) reasonSample = r.r.slice(0, 70);
  }
  const dist = Object.entries(counts).map(([k,v])=>k+':'+Math.round(v/N*100)+'%').join(' ');
  const match = counts[logAct] >= N*0.5;
  console.log((match?'✅':'❌') + ' ' + name);
  console.log('    日志: ' + logAct + ' (' + logReason + ')');
  console.log('    复现: ' + dist + (reasonSample ? ' | ' + reasonSample : ''));
}
