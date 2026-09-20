#!/usr/bin/env node
/* preflop_postflop_gap_audit.js — V2.9.699 前置审计: 翻前/翻后策略的威胁认知缺口量化
 * 反推思路: v698 已让 eq层/范围层/分类层 认识"对手成牌空间"(D1-D5),
 *           本脚本实测这些威胁认知是否【反哺到决策层】(频率/尺度/入池标准):
 *
 * 翻前组(P):
 *   P1  被支配broadway入池: KJo/KQo/AJo vs CO open 的 FlatCall倾向(v698 D3揭示对手平跟范围含QQ/AK)
 *   P2  set mining隐含赔率: 55 vs 3bet池(spr≈7) 的call频率(3bet池set mine需≥15:1隐含赔率)
 *   P3  limp兜底小对子码深校准(基线对照, v672已有)
 * 翻后组(F):
 *   F1  威胁交互(阻断)缺失: JhJd(阻断同花) vs JcJd(无阻断) @ Qh9h2h 的CBet频率/尺寸
 *   F2  降级牌控池缺失: AKo@QJ9(连牌面, v698 D5降级MEDIUM) vs @Q72(干燥) 的CBet尺寸
 *   F3  坚果deny-equity: 88@8c7c2d(set@两同花连牌面) 的CBet尺寸
 *   F4  turn落威胁牌barrel收缩(回归确认, D4反推)
 * 结构组(S):
 *   S1  handClassify威胁降级标记透传: hazard字段是否暴露给决策层
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
const G_ = global;
let findings = 0;
function finding(name, detail) { findings++; console.log('  ⚠️ ' + name + (detail ? '  → ' + detail : '')); }
function baseline(name, detail) { console.log('  ✓ ' + name + (detail ? '  → ' + detail : '')); }

console.log('\n================================================================');
console.log(' V2.9.699 前置审计: 翻前/翻后决策层威胁认知缺口');
console.log('================================================================');

/* ================================================================
 * 翻前组: decidePreflop 直接调用(同步查表, 无MC开销)
 * ================================================================ */
function preflopScenario(handK, opts) {
  const o = Object.assign({ pos: 'mp', scene: 'raise', bet: 3, pot: 4.5, stk: 100, tt: 2, act: 2, reps: 300 }, opts || {});
  const counts = {};
  let reasons = {};
  for (let i = 0; i < o.reps; i++) {
    G_.ActionLine.reset();
    // 手牌: 由键还原(rank同花色即可, 翻前花色不影响查表)
    const m = handK.match(/^([2-9TJQKA])([2-9TJQKA])(s|o)?$/);
    const s1 = (m[3] === 's') ? 's' : 'c', s2 = (m[3] === 's') ? 's' : 'd';
    G_.G.hole = [C(m[1], s1), C(m[2], s2)];
    G_.G.comm = [];
    G_.G.phase = 'pre';
    G_.G.pos = o.pos; G_.G.scene = o.scene;
    G_.G.bet = o.bet; G_.G.pot = o.pot; G_.G.stk = o.stk;
    G_.G.opp = 'unknown'; G_.G.tt = o.tt; G_.G.act = o.act;
    G_.G.limpers = o.limpers || 0;
    G_.G._facing3bet = o.scene === 'reraise' ? true : false;
    G_.G._heroDid4bet = false;
    if (o.oppSeats) G_.G.oppSeats = o.oppSeats; else { try { delete G_.G.oppSeats; } catch (e) { G_.G.oppSeats = undefined; } }
    let r = null;
    try { r = G_.StrategyEngine.decidePreflop(G_.getHandKey()); } catch (ex) { counts['ERR'] = (counts['ERR'] || 0) + 1; continue; }
    const a = r ? r.a : 'null';
    counts[a] = (counts[a] || 0) + 1;
    if (r && r.r) { const key = r.r.split('(')[0].slice(0, 40); reasons[key] = (reasons[key] || 0) + 1; }
  }
  const dist = Object.keys(counts).map(k => k + ':' + Math.round(counts[k] / o.reps * 100) + '%').join(' ');
  console.log('  [' + handK + '@' + o.pos + ' scene=' + o.scene + ' bet=' + o.bet + 'BB pot=' + o.pot + 'BB stk=' + o.stk + 'BB] → ' + dist);
  const topReasons = Object.keys(reasons).sort((a, b) => reasons[b] - reasons[a]).slice(0, 2).map(k => '"' + k + '"×' + reasons[k]).join(' / ');
  if (topReasons) console.log('    主要理由: ' + topReasons);
  return counts;
}

console.log('\n────────────────────────────────────────');
console.log('【P1】被支配broadway入池检查 (v698 D3: 对手平跟范围含QQ/AKs/AKo)');
console.log('────────────────────────────────────────');
const p1KJo = preflopScenario('KJo', { pos: 'mp', scene: 'raise', bet: 3, pot: 4.5, stk: 100 });
const p1KQo = preflopScenario('KQo', { pos: 'mp', scene: 'raise', bet: 3, pot: 4.5, stk: 100 });
const p1AJo = preflopScenario('AJo', { pos: 'mp', scene: 'raise', bet: 3, pot: 4.5, stk: 100 });
const p1call = (p1KJo.call || 0) + (p1KJo.raise || 0);
if (p1call / 300 > 0.35) finding('P1a KJo被支配高危牌入池率' + Math.round(p1call / 3) + '%', '对手3bet范围AA/KK/QQ/AK+平跟D3注入QQ/AK → KJo被支配(reverse implied odds), 决策层无折价');
else baseline('P1a KJo入池率' + Math.round(p1call / 3) + '% (保守)');

console.log('\n────────────────────────────────────────');
console.log('【P2】set mining vs 3bet池 隐含赔率检查');
console.log('────────────────────────────────────────');
// 读 _F3B 表确认 55 的原始 entry
try {
  const f3b = G_._F3B;
  ['BTN', 'CO', 'MP', 'UTG1'].forEach(pos => {
    if (f3b[pos] && f3b[pos]['55']) {
      console.log('  _F3B[' + pos + ']["55"] = ' + JSON.stringify(f3b[pos]['55']));
    }
  });
} catch (e) { console.log('  _F3B 读取异常: ' + e.message); }
// 场景: BTN 55 面对UTG 3bet 10BB, pot 13.5BB, 100BB深 → spr≈6.5
const p2shallow = preflopScenario('55', { pos: 'btn', scene: 'reraise', bet: 10, pot: 13.5, stk: 100 });
const p2shallowCall = ((p2shallow.call || 0) + (p2shallow.raise || 0)) / 300;
if (p2shallowCall > 0.3) finding('P2a 55 vs 3bet池(spr≈6.5)入池率' + Math.round(p2shallowCall * 100) + '%', '3bet池set mine需call≈10BB赢150BB(15:1); 100BB码深不足, 应近全弃');
else baseline('P2a 55 vs 3bet池入池率' + Math.round(p2shallowCall * 100) + '%');
// 深码对照: 250BB → set mine合理化
const p2deep = preflopScenario('55', { pos: 'btn', scene: 'reraise', bet: 10, pot: 13.5, stk: 250 });
const p2deepCall = ((p2deep.call || 0) + (p2deep.raise || 0)) / 300;
console.log('    (深码250BB对照: ' + Math.round(p2deepCall * 100) + '% — 若与浅码相同=码深未入set mine决策)');

console.log('\n────────────────────────────────────────');
console.log('【P3】limp兜底小对子码深校准(基线对照, v672已有)');
console.log('────────────────────────────────────────');
const p3short = preflopScenario('44', { pos: 'co', scene: 'raise', bet: 2, pot: 3.5, stk: 30, reps: 200 });
const p3deep = preflopScenario('44', { pos: 'co', scene: 'raise', bet: 2, pot: 3.5, stk: 200, reps: 200 });
const p3s = ((p3short.call || 0) + (p3short.raise || 0)) / 200, p3d = ((p3deep.call || 0) + (p3deep.raise || 0)) / 200;
if (p3s < p3d - 0.1) baseline('P3 小对子limp码深响应正常(短' + Math.round(p3s * 100) + '% < 深' + Math.round(p3d * 100) + '%)');
else finding('P3 小对子limp码深无响应(短' + Math.round(p3s * 100) + '% vs 深' + Math.round(p3d * 100) + '%)');

/* ================================================================
 * 翻后组: decidePostflop + eq注入(跳过MC, 纯测决策层频率/尺寸)
 * ================================================================ */
function postflopScenario(holeC, commC, eqInj, opts) {
  const o = Object.assign({ pot: 10, bet: 0, stk: 100, pos: 'btn', reps: 200 }, opts || {});
  const counts = {}; const sizes = [];
  for (let i = 0; i < o.reps; i++) {
    G_.FrameDiffEngine._al = [];
    G_.ActionLine.reset();
    G_.ActionLine.record('preflop', 'open', 'raise');
    G_.G.hole = holeC; G_.G.comm = commC;
    G_.G.phase = 'post';
    G_.G.pot = o.pot; G_.G.bet = o.bet; G_.G.stk = o.stk;
    G_.G.scene = o.scene || 'check'; G_.G.pos = o.pos; G_.G.opp = 'unknown';
    G_.G.act = 2; G_.G.tt = 2; G_.G._facing3bet = false; G_.G._heroDid4bet = false;
    let r = null;
    try { r = G_.StrategyEngine.decidePostflop(null, { eq: eqInj }); } catch (ex) { counts['ERR'] = (counts['ERR'] || 0) + 1; continue; }
    const a = r ? r.a : 'null';
    counts[a] = (counts[a] || 0) + 1;
    if (r && (r.a === 'raise' || r.a === 'bet') && r.v) sizes.push(r.v / o.pot);
  }
  const dist = Object.keys(counts).map(k => k + ':' + Math.round(counts[k] / o.reps * 100) + '%').join(' ');
  const sizeStr = sizes.length ? ('尺寸中位≈' + Math.round(sizes.slice().sort((a, b) => a - b)[Math.floor(sizes.length / 2)] * 100) + '%pot') : '无下注';
  console.log('  [' + o.label + ' eq注入=' + eqInj + '%] → ' + dist + ' | ' + sizeStr);
  return { counts, sizes, reps: o.reps };
}

// 先用MC算真实eq(每场景1次, 注入用)
function trueEq(hole, comm, iter) {
  try {
    const spec = G_.postflopEqSpec(hole, comm, 'check', 0, 10, G_.boardTexture(comm), 2);
    return Math.round(G_.mcVsRange(hole, comm, spec.range, iter || 1200, 1).eq * 10) / 10;
  } catch (e) { return 50; }
}

console.log('\n────────────────────────────────────────');
console.log('【F1】威胁交互(阻断)缺失: JhJd vs JcJd @ Qh9h2h (monotone)');
console.log('────────────────────────────────────────');
const f1comm = [C('Q', 'h'), C('9', 'h'), C('2', 'h')];
const f1block = [C('J', 'h'), C('J', 'd')];   // 超对+持h(阻断对手同花1张)
const f1noblock = [C('J', 'c'), C('J', 'd')]; // 超对无阻断
const f1eqB = trueEq(f1block, f1comm, 1500), f1eqN = trueEq(f1noblock, f1comm, 1500);
console.log('  真实eq: JhJd(阻断)=' + f1eqB + '% / JcJd(无阻断)=' + f1eqN + '%');
// 用同一eq注入, 隔离"决策层是否用阻断信息"——若频率/尺寸一致=阻断未入决策
const f1rB = postflopScenario(f1block, f1comm, f1eqB, { label: 'JhJd超对+阻断@三同花面' });
const f1rN = postflopScenario(f1noblock, f1comm, f1eqN, { label: 'JcJd超对无阻断@三同花面' });
const f1betB = (f1rB.counts.raise || 0) / f1rB.reps, f1betN = (f1rN.counts.raise || 0) / f1rN.reps;
if (Math.abs(f1betB - f1betN) < 0.05) finding('F1a 阻断未入CBet决策(阻断' + Math.round(f1betB * 100) + '% vs 无阻断' + Math.round(f1betN * 100) + '%)', '合理策略: 持阻断牌对手同花浓度↓ → 更敢打; v698 D4仅作用于eq层范围加权');
else baseline('F1a 阻断影响CBet频率(' + Math.round(f1betB * 100) + '% vs ' + Math.round(f1betN * 100) + '%)');

console.log('\n────────────────────────────────────────');
console.log('【F2】降级牌控池缺失: AQo@QT9(连牌,D5降级MEDIUM+hazard) vs @Q72(干燥)');
console.log('  (V2.9.699修正: 初版误用AK@QJ9——该牌有gutshot走DRAW路径非降级顶对路径,');
console.log('   正确场景是AQ@QT9: 顶对顶踢无顺听, 走v698 D5b连牌面降级MEDIUM)');
console.log('────────────────────────────────────────');
const f2wet = postflopScenario([C('A', 's'), C('Q', 'd')], [C('Q', 's'), C('J', 's'), C('9', 'c')], 55, { label: 'AQo@QsJs9c 连牌面降级顶对(MEDIUM+hazard)' });
const f2dry = postflopScenario([C('A', 's'), C('K', 'd')], [C('Q', 's'), C('7', 'd'), C('2', 'c')], 62, { label: 'AKo@Qs7d2c 干燥面顶对(STRONG)' });
const med = arr => arr.length ? arr.slice().sort((a, b) => a - b)[Math.floor(arr.length / 2)] : null;
const f2wetSz = med(f2wet.sizes), f2drySz = med(f2dry.sizes);
if (f2wetSz && f2wetSz >= 0.6) finding('F2a 连牌面降级顶对CBet尺寸' + Math.round(f2wetSz * 100) + '%pot', 'v698 D5已降级MEDIUM(频率联动✓)但尺寸仍w档——被raise时损失最大化; 合理: 降级威胁牌应控池33-50%');
else baseline('F2a 连牌面降级顶对尺寸' + (f2wetSz ? Math.round(f2wetSz * 100) + '%pot' : 'n/a'));
console.log('    (干燥面顶对对照: ' + (f2drySz ? Math.round(f2drySz * 100) + '%pot' : 'n/a') + ')');

console.log('\n────────────────────────────────────────');
console.log('【F3】坚果deny-equity: 88@8c7c2d (set@两同花+连牌面)');
console.log('────────────────────────────────────────');
const f3r = postflopScenario([C('8', 'h'), C('8', 's')], [C('8', 'c'), C('7', 'c'), C('2', 'd')], 78, { label: '88@8c7c2d set@同花连牌面' });
const f3sz = med(f3r.sizes);
if (f3sz && f3sz < 0.75) finding('F3a set@听牌面CBet尺寸' + Math.round(f3sz * 100) + '%pot', '对手同花/顺听牌空间大(v698威胁认知可算), 坚果应75-85%deny equity');
else baseline('F3a set@听牌面尺寸' + (f3sz ? Math.round(f3sz * 100) + '%pot' : 'n/a') + ' (deny equity充分)');

console.log('\n────────────────────────────────────────');
console.log('【F4】turn落威胁牌barrel收缩(回归确认, D4反推)');
console.log('────────────────────────────────────────');
const f4hole = [C('A', 's'), C('K', 's')];
const f4turnSafe = [C('Q', 's'), C('9', 'c'), C('2', 'd'), C('4', 'h')];   // turn 4: 无新威胁
const f4turnPair = [C('Q', 's'), C('9', 'c'), C('2', 'd'), C('9', 'h')];   // turn 9: 公对+顺面
try {
  const btSafe = G_._bt2key(G_.boardTexture(f4turnSafe));
  const btPair = G_._bt2key(G_.boardTexture(f4turnPair));
  console.log('  turn 4h(安全): btKey=' + btSafe + ' | turn 9h(公对): btKey=' + btPair);
  if (btPair === '5') baseline('F4a 公对turn正确进对子面表(btKey=5), barrel频率自动收缩(D4联动✓)');
  else finding('F4a 公对turn纹理键异常: ' + btPair);
} catch (e) { console.log('  F4异常: ' + e.message); }

/* ================================================================
 * 结构组: handClassify 威胁降级标记透传
 * ================================================================ */
console.log('\n────────────────────────────────────────');
console.log('【S1】handClassify 威胁降级标记透传检查');
console.log('────────────────────────────────────────');
try {
  // V2.9.699修正: 用AQ@QT9(走D5b连牌面降级路径)而非AK@QJ9(有gutshot走DRAW路径无降级)
  const hc = G_.handClassify([C('A', 's'), C('Q', 'd')], [C('Q', 's'), C('J', 's'), C('9', 'c')]);
  console.log('  handClassify(AQ@QsJs9c) = ' + JSON.stringify(hc));
  if (hc && !hc.hazard && !hc.downgraded) finding('S1a 威胁降级标记未透传(返回值仅name/desc/outs/isOvercard)', '_downgradedByBoardHazard/3Flush是handClassify内部变量, _pickSizing/尺寸层无法感知威胁→F2a的根因');
  else baseline('S1a 威胁降级标记已透传(hazard=' + (hc && hc.hazard) + ')');
} catch (e) { console.log('  S1异常: ' + e.message); }

console.log('\n================================================================');
console.log(' 审计结论: 发现 ' + findings + ' 个反推升级缺口');
console.log('================================================================');
