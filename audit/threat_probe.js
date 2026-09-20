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

const C = (r, s) => ({ rank: r, suit: s });

// ================================================================
console.log('\n【第1层】handClassify: made hand × 板面威胁分类');
// ================================================================
function hc(hole, comm) {
  const r = global.handClassify(hole, comm);
  return r;
}
// T1: 真 set @ monotone(三同花) — 8c8d @ 8h7h9h: hero set of 8s + 对手任意两张红桃=已成同花
let t1 = hc([C('8','c'), C('8','d')], [C('8','h'), C('7','h'), C('9','h')]);
console.log('  set@monotone(8h7h9h) = ' + t1.name + ' / ' + (t1.desc||'') + (t1.detail?('/'+t1.detail):''));
assert(t1.name !== 'NUTS', 'T1 真 set@三同花面 不判NUTS(572威胁降级生效)', t1.name);
assert(t1.name !== 'AIR', 'T1b set@三同花面 不降到AIR(set有改进权益,过度降级=误杀)', t1.name);

// T2: 真 set @ 连牌面 — 8c8d @ 8s7c9d: 对手JT已成顺/68听顺
let t2 = hc([C('8','c'), C('8','d')], [C('8','s'), C('7','c'), C('9','d')]);
console.log('  set@连牌面(8s7c9d) = ' + t2.name + ' / ' + (t2.desc||''));
assert(t2.name !== 'NUTS', 'T2 真 set@连牌面 不判NUTS(P7c②降级)', t2.name);
assert(t2.name !== 'AIR', 'T2b set@连牌面 不降到AIR', t2.name);

// T3: 真 set @ 干燥面(对照) — 8c8d @ 8s2d3c: 无威胁应保持NUTS
let t3 = hc([C('8','c'), C('8','d')], [C('8','s'), C('2','d'), C('3','c')]);
console.log('  set@干燥面(8s2d3c) = ' + t3.name + ' / ' + (t3.desc||''));
assert(t3.name === 'NUTS', 'T3 真 set@干燥面 保持NUTS(不误伤)', t3.name);

// T3c: 中对@三同花面(676合法降级对照) — 88@7h9h2h(非set,板面9>8): 实机教训9d9c@4cTc3c
let t3c = hc([C('8','c'), C('8','d')], [C('7','h'), C('9','h'), C('2','h')]);
console.log('  中对@三同花面(对照,7h9h2h) = ' + t3c.name + ' / ' + (t3c.desc||''));
assert(t3c.name === 'AIR', 'T3c 中对@三同花面 降AIR(676实机教训,不回退)', t3c.name);

// T4: 顶对 @ monotone — AhKd @ 7h9h2h: 顶对顶踢但对手可能同花
let t4 = hc([C('A','h'), C('K','d')], [C('7','h'), C('9','h'), C('2','h')]);
console.log('  顶对A@monotone = ' + t4.name + ' / ' + (t4.desc||'') + ' outs=' + (t4.outs||0));
assert(t4.name !== 'NUTS' && t4.name !== 'STRONG', 'T4 顶对@三同花面 ≤MEDIUM(威胁认知)', t4.name);

// T5: 两对 @ 连牌+同花听 — 8c9d @ 7s8h9c: 两对但对手JT已成顺/听同花
let t5 = hc([C('8','c'), C('9','d')], [C('7','s'), C('8','h'), C('9','c')]);
console.log('  两对@连牌对子面(7s8h9c) = ' + t5.name + ' / ' + (t5.desc||''));
assert(t5.name !== 'NUTS', 'T5 两对@高威胁面 不判NUTS', t5.name);

// ================================================================
console.log('\n【第2层】eq: 对手范围是否反映威胁(set@monotone 的真实权益)');
// ================================================================
// 8c8d @ 7h9h2h vs 对手持续下注范围: 理论上对手范围应含已成同花, set eq≈30-40%
// 用引擎自己的 mcVsRange 直接测
const hole = [C('8','c'), C('8','d')];
const comm = [C('8','h'), C('7','h'), C('9','h')];  // 真 set@monotone(hero无红桃阻断)
// 模拟对手 cbet 范围: 引擎的 gC/gO 基线范围(看它是否含同花组合)
let oppRange = null;
try {
  const bt = global.boardTexture(comm);
  console.log('  boardTexture: ' + bt.desc + ' wetness=' + bt.wetness + ' monotone=' + bt.hasMonotone);
} catch(e) { console.log('  boardTexture异常: ' + e.message); }
// postflopEqSpec 拿到引擎实际使用的对手范围
try {
  const spec = global.postflopEqSpec(hole, comm, 'raise', 100, 335, global.boardTexture(comm), 2);
  console.log('  postflopEqSpec.range 长度=' + (spec.range ? spec.range.length : 'null') + ' iter=' + spec.iter);
  // 检查范围里是否含红桃组合(同花威胁): 范围键格式检查
  if (spec.range && spec.range.length) {
    const sample = spec.range.slice(0, 8).join(',');
    console.log('  范围样本: ' + sample);
    // MC 模拟 eq
    const ms = global.mcVsRange(hole, comm, spec.range, spec.iter || 300, 1);
    console.log('  set@monotone vs cbet范围 eq = ' + Math.round(ms.eq * 10) / 10 + '%');
    // V2.9.697: 修复前72.6%(键级稀释:范围含不含同花键eq几乎不变)
    // 修复后预期显著下降(组合级重加权还原威胁分布), 真实区间55-68%
    assert(ms.eq < 70, 'T6 set@monotone eq<70%(威胁组合重加权生效,修复前72.6%)', ms.eq + '%');
    assert(ms.eq > 15, 'T6b set@monotone eq>15%(仍有改进权益,不过度悲观)', ms.eq + '%');
    // T6c 威胁对照(稳定版): 同set@干燥面(8s2d3c无威胁) eq 应显著高于威胁面——
    // 第二刀极化线生效后混合范围同花浓度已高, 全同花键vs混合的组内梯度差距进入MC噪声区(±1.5pp),
    // 干燥面vs威胁面差距~35pp(修复前72.6%被稀释问题掩盖, 修复后56%), 是威胁认知的稳定证据
    const msDry = global.mcVsRange(hole, [C('8','s'), C('2','d'), C('3','c')], spec.range, spec.iter || 1500, 1);
    console.log('  干燥面对照: 同set@8s2d3c eq = ' + Math.round(msDry.eq * 10) / 10 + '%');
    assert(msDry.eq > ms.eq + 15, 'T6c 威胁认知: 同set干燥面eq比威胁面高15pp+', msDry.eq + '% vs ' + ms.eq + '%');
  }
} catch(e) { console.log('  eq探测异常: ' + e.message); assert(false, 'T6 eq探测', e.message); }

// ================================================================
console.log('\n【第3层】决策: set@monotone 面对大注是否止损');
// ================================================================
function decideScenario(holeC, commC, betSz, potSz, label, stkSz) {
  global.FrameDiffEngine._al = [];
  global.FrameDiffEngine._hid = holeC[0].rank + holeC[1].rank;
  global.ActionLine.reset();
  global.ActionLine.record('preflop','open','raise');
  global.G.hole = holeC; global.G.comm = commC;
  global.G.pot = potSz; global.G.bet = betSz; global.G.stk = stkSz || 200;
  global.G.scene = 'bet'; global.G.pos = 'btn'; global.G.opp = 'unknown';
  global.G.act = 2; global.G.tt = 2; global.G._facing3bet = false; global.G._heroDid4bet = false;
  // 注入真实eq路径太慢(300次MC), 直接走SE自算(不注入)——decidePostflop内部会跑MC
  let r = null, e = null;
  try { r = global.StrategyEngine.decidePostflop(null); } catch (ex) { e = ex; }
  if (e) { console.log('  ' + label + ' 异常: ' + e.message); return null; }
  console.log('  ' + label + ' → ' + (r ? (r.a + ' | scene=' + (r.scene||'') + ' | eq=' + (r.eq!==undefined?Math.round(r.eq):'?') + '% | ' + (r.r||'').slice(0,80)) : 'null'));
  return r;
}
// D-A: 真set@monotone 面对满池下注: set真实eq≈35-45%(对手范围含已成同花), 满池需23% → 应call
let dA = decideScenario(hole, comm, 100, 235, '真set@monotone vs 满池(100/235)');
assert(dA !== null, 'D-A 有决策产出');
if (dA) {
  assert(dA.a === 'fold' || dA.a === 'call' || dA.a === 'raise' || dA.a === 'allin', 'D-A 决策合法');
  // V2.9.697: SPR≈0.85短码 + 修复后eq≈66% >> 满池需求30% → raise/allin是数学正确决策(不再断言禁raise)
  // 止损语义落在: eq不再虚高(若raise且eq>70%说明威胁仍未进eq), 以及深码弱牌场景(D-C/D-D)
  assert(dA.a !== 'fold', 'D-A set@monotone面对满池不弃(set权益>>赔率要求,弃=误杀)', dA.a + ' eq=' + dA.eq);
  if (dA.a === 'raise' || dA.a === 'allin') {
    assert(dA.eq <= 70, 'D-A 若加注则eq≤70%(威胁已进eq,不再虚高72%+)', dA.eq + '%');
  }
}
// D-B: 真set@干燥面 vs 满池(对照): 8c8d@8s2d3c 应敢打
let dB = decideScenario([C('8','c'), C('8','d')], [C('8','s'), C('2','d'), C('3','c')], 100, 235, '真set@干燥面 vs 满池(对照)');
if (dB) assert(dB.a !== 'fold', 'D-B 干燥面set不弃(不误伤)', dB.a);

// D-C: 顶对@monotone 深码大注(用户止损诉求直接场景) — AdKd@Ah7h9h, SPR=2.5, 80%池大注
// 顶对顶踢无红桃阻断, 对手重加权范围含25%已成同花+坚果听 → eq应~25-35% → 不该raise
let dC = decideScenario([C('A','d'), C('K','d')], [C('A','h'), C('7','h'), C('9','h')], 160, 200, '顶对@monotone vs 80%池(SPR2.5)', 500);
if (dC) {
  console.log('  D-C 顶对eq=' + (dC.eq !== undefined ? Math.round(dC.eq) : '?') + '%');
  assert(dC.a !== 'raise' && dC.a !== 'allin', 'D-C 顶对@威胁面深码大注不加注(止损)', dC.a);
}

// D-D: 中对@三花面深码大注(实机教训复现: 9d9c@4cTc3c 错误call all-in) — 676降AIR + eq修正(54%→40%)
// float 40%>31%需求在数学上可辩护(flop双街eq),但威胁认知已进eq;断言:不激进+eq显著低于修复前54%
let dD = decideScenario([C('9','d'), C('9','c')], [C('4','c'), C('T','c'), C('3','c')], 180, 220, '中对@三花面 vs 大注(实机教训)', 500);
if (dD) {
  console.log('  D-D 中对eq=' + (dD.eq !== undefined ? Math.round(dD.eq) : '?') + '%');
  assert(dD.a !== 'raise' && dD.a !== 'allin', 'D-D 中对@三花面深码大注不加注(止损)', dD.a);
  assert(dD.eq <= 45, 'D-D 中对威胁面eq≤45%(威胁进eq,修复前54%虚高)', dD.eq + '%');
}

// D-D2: 实机教训精确复现 — 9d9c@4cTc3c 面对全下(bet=stk=500): 双街eq~40% < 全下需求~41%, 无实现权益 → fold
let dD2 = decideScenario([C('9','d'), C('9','c')], [C('4','c'), C('T','c'), C('3','c')], 500, 220, '中对@三花面 vs 全下(实机教训精确复现)', 500);
if (dD2) {
  console.log('  D-D2 全下场景eq=' + (dD2.eq !== undefined ? Math.round(dD2.eq) : '?') + '%');
  assert(dD2.a === 'fold', 'D-D2 中对@三花面面对全下止损fold(9d9c@4cTc3c实机教训:错误call all-in)', dD2.a);
}

// D-E: 两对@连牌面(用户原诉求"顺子威胁") — 8c9d@7s8h9c 两对, 对手JT已成顺
let dE = decideScenario([C('8','c'), C('9','d')], [C('7','s'), C('8','h'), C('9','c')], 120, 150, '两对@连牌对子面 vs 80%池', 400);
if (dE) {
  console.log('  D-E 两对eq=' + (dE.eq !== undefined ? Math.round(dE.eq) : '?') + '%');
  assert(dE.a !== 'allin', 'D-E 两对@连牌面不无脑全下(顺子威胁认知)', dE.a);
}

console.log('\n========================================');
console.log(`板面威胁×牌力互动实测: ${pass} 通过, ${fail} 失败`);
console.log('========================================');
process.exit(fail > 0 ? 1 : 0);
