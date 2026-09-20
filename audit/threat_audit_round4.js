#!/usr/bin/env node
/* threat_audit_round4.js — 对手成牌空间视角审计(用户框架):
 * "根据公牌, 考虑其他玩家可以组合的牌面(set/两对/顺子/葫芦/四条/更大牌), 降低风险及时止损"
 * 每个场景测 4 层:
 *   ①对手范围展开后的成牌份额(用引擎eH精确判定对手当前牌型, cat: 8同花顺7四条6葫芦5同花4顺3三条2两对1对0高牌)
 *   ②其中已击败hero的组合份额(直接威胁密度)
 *   ③hero eq(引擎口径MC)
 *   ④handClassify分类 + 深码大注(SPR~2.5)决策建议
 * 场景均为"对手已下注"(scene=bet)——威胁条件化最该生效的行动线。
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
const CAT = ['高牌','一对','两对','三条','顺子','同花','葫芦','四条','同花顺'];

function eqAvg(hole, comm, range, iter, nOpp, reps) {
  reps = reps || 3; let s = 0;
  for (let i = 0; i < reps; i++) s += G_.mcVsRange(hole, comm, range, iter, nOpp || 1).eq;
  return s / reps;
}
function decideScenario(holeC, commC, betSz, potSz, label, stkSz) {
  G_.FrameDiffEngine._al = [];
  G_.FrameDiffEngine._hid = holeC[0].rank + holeC[1].rank;
  G_.ActionLine.reset();
  G_.ActionLine.record('preflop','open','raise');
  G_.G.hole = holeC; G_.G.comm = commC;
  G_.G.pot = potSz; G_.G.bet = betSz; G_.G.stk = stkSz || 200;
  G_.G.scene = 'bet'; G_.G.pos = 'btn'; G_.G.opp = 'unknown';
  G_.G.act = 2; G_.G.tt = 2; G_.G._facing3bet = false; G_.G._heroDid4bet = false;
  let r = null;
  try { r = G_.StrategyEngine.decidePostflop(null); } catch (e) { return { a: 'ERR:' + e.message }; }
  return r;
}
// 对手范围展开 + 成牌份额统计已内联到 auditScene

function auditScene(id, name, hole, comm, refEq, deepBet) {
  console.log('\n----------------------------------------------------------------');
  console.log(' ' + id + ' ' + name);
  console.log('----------------------------------------------------------------');
  const bt = G_.boardTexture(comm);
  const spec = G_.postflopEqSpec(hole, comm, 'bet', 160, 200, bt, 2);
  // 份额统计(手工展开, 不用上面有占位符的函数)
  const known = [].concat(hole, comm);
  let hands = [];
  for (const key of spec.range) for (const h of G_.handKeyToCards(key)) {
    const cl = known.some(c => (c.rank===h[0].rank&&(c.suit===h[0].suit||!c.suit))) || known.some(c => (c.rank===h[1].rank&&(c.suit===h[1].suit||!c.suit)));
    if (!cl) hands.push(h);
  }
  const heroBest = G_.eH([].concat(hole, comm));
  const heroCat = Math.floor(heroBest / 1e10);
  const dist = {}; let beat = 0; const beatKeys = {};
  for (const h of hands) {
    const sc = G_.eH([].concat(h, comm));
    const cat = Math.floor(sc / 1e10);
    dist[cat] = (dist[cat] || 0) + 1;
    if (sc > heroBest) { beat++; }
  }
  const distStr = Object.keys(dist).sort((a,b)=>b-a).map(k => CAT[k] + '=' + dist[k] + '(' + (dist[k]/hands.length*100).toFixed(1) + '%)').join(' ');
  console.log('  板面: ' + bt.desc + ' | line=' + spec.line + ' | hero牌型=' + CAT[heroCat]);
  console.log('  对手范围(' + spec.range.length + '键/' + hands.length + '组合) 成牌分布: ' + distStr);
  console.log('  直接威胁(当前已>hero): ' + beat + '组合 (' + (beat/hands.length*100).toFixed(1) + '%)');
  const eq = eqAvg(hole, comm, spec.range, 1500, 1);
  console.log('  hero eq(引擎)=' + eq.toFixed(1) + '%  [参考区间 ' + refEq + ']');
  const hc = G_.handClassify(hole, comm);
  console.log('  handClassify: ' + hc.name + ' / ' + (hc.desc||'') + (hc.detail?('/'+hc.detail):''));
  if (deepBet) {
    const r = decideScenario(hole, comm, deepBet.bet, deepBet.pot, name, deepBet.stk);
    console.log('  深码决策(SPR~' + (deepBet.stk/ (deepBet.pot+deepBet.bet)).toFixed(1) + ', bet=' + deepBet.bet + '/' + deepBet.pot + '): ' +
      (r ? (r.a + ' | eq=' + (r.eq!==undefined?Math.round(r.eq):'?') + '% | ' + String(r.r||'').slice(0,70)) : 'null'));
  }
  return { eq, beatPct: beat/hands.length*100, dist, heroCat };
}

console.log('\n================================================================');
console.log(' 对手成牌空间威胁审计 (V2.9.697) — 全威胁类型: set/两对/顺子/葫芦/四条/更大牌');
console.log('================================================================');

// H1 顶对 vs 隐蔽set (板面无对, 对手pocket=板面牌)
auditScene('H1', '顶对顶踢 AK @ Kc7h2d(彩虹) vs 隐蔽set(77/22/KK)',
  [C('A','h'), C('K','d')], [C('K','c'), C('7','h'), C('2','d')],
  '70-78%', { bet: 160, pot: 200, stk: 500 });

// H2 超对 vs 隐蔽set + 更大超对
auditScene('H2', '超对 TT @ 9c7h2d(彩虹) vs set(99/77/22)+超对(JJ+)',
  [C('T','c'), C('T','d')], [C('9','c'), C('7','h'), C('2','d')],
  '62-72%', { bet: 160, pot: 200, stk: 500 });

// H3 顶对 vs 两对+set (对手双参与)
auditScene('H3', '顶对 AQ @ QcTh9d vs 两对(QT/T9/AQ)+set(QQ/TT/99)+顺听(KJ/J8)',
  [C('A','s'), C('Q','d')], [C('Q','c'), C('T','h'), C('9','d')],
  '48-58%', { bet: 160, pot: 200, stk: 500 });

// H4 非坚果顺 vs 更大顺
auditScene('H4', '非坚果顺 98 @ 7h6s5c vs 更大顺(T9/T8)+同听',
  [C('9','c'), C('8','d')], [C('7','h'), C('6','s'), C('5','c')],
  '55-65%', { bet: 160, pot: 200, stk: 500 });

// H5 坚果顺 vs 双花面同花听
auditScene('H5', '坚果顺 AT @ QsJhTh(双花) vs 同花听(h键)',
  [C('A','c'), C('T','d')], [C('Q','s'), C('J','h'), C('T','h')],
  '60-70%', { bet: 160, pot: 200, stk: 500 });

// H6 动态威胁: 顺子@turn落公对 → FH/quads支配 (跨街对比)
{
  console.log('\n----------------------------------------------------------------');
  console.log(' H6 动态: 顺子T9 @ flop 6h7s8d → turn落7c(公对) FH威胁');
  console.log('----------------------------------------------------------------');
  const hole = [C('T','c'), C('9','d')];
  const flop = [C('6','h'), C('7','s'), C('8','d')];
  const turn = [C('6','h'), C('7','s'), C('8','d'), C('7','c')];
  for (const [nm, cc] of [['flop 6-7-8(顺子,坚果)', flop], ['turn 6-7-8-7(公对落地,FH威胁)', turn]]) {
    const bt = G_.boardTexture(cc);
    const spec = G_.postflopEqSpec(hole, cc, 'bet', 100, 200, bt, 2);
    const eq = eqAvg(hole, cc, spec.range, 1500, 1);
    const hc = G_.handClassify(hole, cc);
    console.log('  ' + nm + ': ' + bt.desc + ' line=' + spec.line + ' eq=' + eq.toFixed(1) + '%  class=' + hc.name + '/' + (hc.desc||'') + (hc.detail?('/'+hc.detail):''));
  }
  const r = decideScenario(hole, turn, 160, 200, 'H6', 500);
  console.log('  turn深码决策: ' + (r ? (r.a + ' | eq=' + (r.eq!==undefined?Math.round(r.eq):'?') + '%') : 'null'));
}

// H7 动态威胁: 顶对@turn落公对 → FH/trips支配
{
  console.log('\n----------------------------------------------------------------');
  console.log(' H7 动态: 顶对AK @ flop Kc9h5d → turn落5c(公对)');
  console.log('----------------------------------------------------------------');
  const hole = [C('A','h'), C('K','d')];
  const flop = [C('K','c'), C('9','h'), C('5','d')];
  const turn = [C('K','c'), C('9','h'), C('5','d'), C('5','c')];
  for (const [nm, cc] of [['flop K-9-5', flop], ['turn K-9-5-5(公对)', turn]]) {
    const bt = G_.boardTexture(cc);
    const spec = G_.postflopEqSpec(hole, cc, 'bet', 100, 200, bt, 2);
    const eq = eqAvg(hole, cc, spec.range, 1500, 1);
    const hc = G_.handClassify(hole, cc);
    console.log('  ' + nm + ': ' + bt.desc + ' line=' + spec.line + ' eq=' + eq.toFixed(1) + '%  class=' + hc.name + '/' + (hc.desc||'') + (hc.detail?('/'+hc.detail):''));
  }
  const r = decideScenario(hole, turn, 160, 200, 'H7', 500);
  console.log('  turn深码决策: ' + (r ? (r.a + ' | eq=' + (r.eq!==undefined?Math.round(r.eq):'?') + '%') : 'null'));
}

// H8 中set vs 顶set
auditScene('H8', '中set 88 @ Kh8s2c vs 顶set KK(3组合)+Kx顶对',
  [C('8','c'), C('8','d')], [C('K','h'), C('8','s'), C('2','c')],
  '82-88%', { bet: 160, pot: 200, stk: 500 });

// H9 两对 vs set
auditScene('H9', '两对 K8 @ Kh8s2c vs set(KK/88/22)',
  [C('K','c'), C('8','d')], [C('K','h'), C('8','s'), C('2','c')],
  '62-70%', { bet: 160, pot: 200, stk: 500 });

// H10 river: 顺子@双公对面 → FH密度
auditScene('H10', 'river顺子 T9 @ 6c7d7h8s9c vs 双公对面FH/四条',
  [C('T','h'), C('9','d')], [C('6','c'), C('7','d'), C('7','h'), C('8','s'), C('9','c')],
  '35-50%', { bet: 160, pot: 200, stk: 500 });

// H11 低同花 vs 高花 (hero视角: 我的花是小的)
auditScene('H11', '低同花 7h8h @ 2h9h5h(monotone) vs 高花(Ah/Kh/Qh/Jh/Th+xh)',
  [C('7','h'), C('8','h')], [C('2','h'), C('9','h'), C('5','h')],
  '55-70%', { bet: 160, pot: 200, stk: 500 });

// H12 超对+坚果花阻断 @ monotone vs 全下
auditScene('H12', '超对AhAd @ QhJh9h(monotone,持Ah阻断) vs 全下',
  [C('A','h'), C('A','d')], [C('Q','h'), C('J','h'), C('9','h')],
  '45-58%', { bet: 500, pot: 220, stk: 500 });

// H13 动态: 顶两对 @ turn落第3张花
{
  console.log('\n----------------------------------------------------------------');
  console.log(' H13 动态: 两对K8 @ flop Kc8s2h → turn落5h(3张红桃)');
  console.log('----------------------------------------------------------------');
  const hole = [C('K','d'), C('8','d')];
  const flop = [C('K','c'), C('8','s'), C('2','h')];
  const turn = [C('K','c'), C('8','s'), C('2','h'), C('5','h')];
  for (const [nm, cc] of [['flop K-8-2(两对)', flop], ['turn K-8-2-5(3花落地)', turn]]) {
    const bt = G_.boardTexture(cc);
    const spec = G_.postflopEqSpec(hole, cc, 'bet', 100, 200, bt, 2);
    const eq = eqAvg(hole, cc, spec.range, 1500, 1);
    const hc = G_.handClassify(hole, cc);
    console.log('  ' + nm + ': ' + bt.desc + ' line=' + spec.line + ' eq=' + eq.toFixed(1) + '%  class=' + hc.name + '/' + (hc.desc||'') + (hc.detail?('/'+hc.detail):''));
  }
  const r = decideScenario(hole, turn, 160, 200, 'H13', 500);
  console.log('  turn深码决策: ' + (r ? (r.a + ' | eq=' + (r.eq!==undefined?Math.round(r.eq):'?') + '%') : 'null'));
}

console.log('\n================================================================');
console.log(' 对手成牌空间审计完成');
console.log('================================================================');
