#!/usr/bin/env node
/* threat_upgrade_verify.js — V2.9.699 翻前/翻后策略威胁反推升级验证
 * PF-1: 被支配broadway折价 — KJo vs前位open入池率100%→大幅下降; AJo/KQo适度保留
 * PF-2: 小对子set实现折损 — 44@limp 30BB弃/200BB跟(码深成为决定变量); 55不误伤
 * POST-3: 两同花+邻张面升semi-wet — 8c7c2d wetness 1→2
 * POST-2: 坚果deny-equity — 88@8c7c2d set cbet 30%→~80%pot
 * POST-1: hazard透传+控池降档 — AQ@QT9(降级MEDIUM) cbet尺寸w档→p档; 干燥面/NUTS不受影响
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
let pass = 0, fail = 0;
function ok(cond, name, detail) {
  if (cond) { pass++; console.log('  ✅ ' + name); }
  else { fail++; console.log('  ❌ ' + name + (detail ? '  → ' + detail : '')); }
}

console.log('\n================================================================');
console.log(' V2.9.699 升级验证: 翻前/翻后策略威胁反推 (修复前→修复后)');
console.log('================================================================');

/* ---------- 翻前 ---------- */
function preflopDist(handK, opts) {
  const o = Object.assign({ pos: 'mp', scene: 'raise', bet: 3, pot: 4.5, stk: 100, tt: 2, act: 2, reps: 300 }, opts || {});
  const counts = {};
  for (let i = 0; i < o.reps; i++) {
    G_.ActionLine.reset();
    const m = handK.match(/^([2-9TJQKA])([2-9TJQKA])(s|o)?$/);
    const s1 = (m[3] === 's') ? 's' : 'c', s2 = (m[3] === 's') ? 's' : 'd';
    G_.G.hole = [C(m[1], s1), C(m[2], s2)];
    G_.G.comm = []; G_.G.phase = 'pre';
    G_.G.pos = o.pos; G_.G.scene = o.scene;
    G_.G.bet = o.bet; G_.G.pot = o.pot; G_.G.stk = o.stk;
    G_.G.opp = 'unknown'; G_.G.tt = o.tt; G_.G.act = o.act;
    G_.G.limpers = o.limpers || 0;
    G_.G._facing3bet = o.scene === 'reraise' ? true : false;
    G_.G._heroDid4bet = false;
    G_.G._raiserRole = o.raiserRole || 'mp';  // V2.9.699: 显式设置raiser位置(前位mp触发折价/后位btn不触发)
    try { delete G_.G.oppSeats; } catch (e) {}
    let r = null;
    try { r = G_.StrategyEngine.decidePreflop(G_.getHandKey()); } catch (ex) { continue; }
    const a = r ? r.a : 'null';
    counts[a] = (counts[a] || 0) + 1;
  }
  return counts;
}
function entryRate(c, reps) { return ((c.call || 0) + (c.raise || 0)) / reps; }

console.log('\n【PF-1】被支配broadway折价 (修复前: KJo/AJo/KQo@MP vs open 全部100% call)');
const v1 = preflopDist('KJo', { pos: 'mp', scene: 'raise', bet: 3, pot: 4.5, stk: 100 });
console.log('  KJo@MP vs open: call=' + Math.round((v1.call || 0) / 3) + '% fold=' + Math.round((v1.fold || 0) / 3) + '%');
ok(entryRate(v1, 300) < 0.25, 'V1 KJo vs前位open入池率<25%(修复前100%, 被支配折价生效)', entryRate(v1, 300).toFixed(2));
const v1b = preflopDist('QJo', { pos: 'mp', scene: 'raise', bet: 3, pot: 4.5, stk: 100 });
console.log('  QJo@MP vs open: call=' + Math.round((v1b.call || 0) / 3) + '% fold=' + Math.round((v1b.fold || 0) / 3) + '%');
ok(entryRate(v1b, 300) < 0.15, 'V1b QJo(折价+10最重档)入池率<15%', entryRate(v1b, 300).toFixed(2));
const v2 = preflopDist('AJo', { pos: 'mp', scene: 'raise', bet: 3, pot: 4.5, stk: 100 });
console.log('  AJo@MP vs open: call=' + Math.round((v2.call || 0) / 3) + '%');
ok(entryRate(v2, 300) > 0.5, 'V2 AJo(折价+6,eq58)保留call能力不过度收紧', entryRate(v2, 300).toFixed(2));
const v2b = preflopDist('KQo', { pos: 'mp', scene: 'raise', bet: 3, pot: 4.5, stk: 100 });
console.log('  KQo@MP vs open: call=' + Math.round((v2b.call || 0) / 3) + '%');
ok(entryRate(v2b, 300) > 0.5, 'V2b KQo(折价+6,eq55)保留call能力', entryRate(v2b, 300).toFixed(2));
// 对照: vs BTN(后位raiser)不加折价 — KJo vs BTN 应保留一定call
// (getOppPos读G._raiserRole, mock需显式设置后位raiser——v699验证场景修正)
const v2c = preflopDist('KJo', { pos: 'bb', scene: 'raise', bet: 3, pot: 4.5, stk: 100, raiserRole: 'btn' });
console.log('  KJo@BB vs BTN open(后位不折价): call=' + Math.round((v2c.call || 0) / 3) + '%');
ok(entryRate(v2c, 300) > 0.3, 'V2c KJo vs后位raiser不被过度折价(后位范围宽支配浓度低)', entryRate(v2c, 300).toFixed(2));

console.log('\n【PF-2】小对子set实现折损 (修复前: 44@limp 30BB/200BB 均100% call无码深响应)');
const v3s = preflopDist('44', { pos: 'co', scene: 'raise', bet: 2, pot: 3.5, stk: 30, reps: 200 });
const v3d = preflopDist('44', { pos: 'co', scene: 'raise', bet: 2, pot: 3.5, stk: 200, reps: 200 });
console.log('  44@CO limp兜底: 30BB入池=' + Math.round(entryRate(v3s, 200) * 100) + '% / 200BB入池=' + Math.round(entryRate(v3d, 200) * 100) + '%');
ok(entryRate(v3s, 200) < 0.3, 'V3 44@30BB短码入池率<30%(修复前100%, set折损后51%<53%弃)', entryRate(v3s, 200).toFixed(2));
ok(entryRate(v3d, 200) > 0.7, 'V3b 44@200BB深码保留入池(51%≥38%深码门槛)', entryRate(v3d, 200).toFixed(2));
ok(entryRate(v3d, 200) - entryRate(v3s, 200) > 0.3, 'V3c 码深成为set mine决定变量(深-短>30pp)', (entryRate(v3d, 200) - entryRate(v3s, 200)).toFixed(2));
const v4 = preflopDist('55', { pos: 'mp', scene: 'raise', bet: 3, pot: 4.5, stk: 100 });
console.log('  55@MP vs open(FlatCall): call=' + Math.round((v4.call || 0) / 3) + '%');
ok(entryRate(v4, 300) > 0.5, 'V4 55 vs open(74×0.7=52≥47)不误伤正常set mine', entryRate(v4, 300).toFixed(2));
const v4b = preflopDist('JJ', { pos: 'mp', scene: 'raise', bet: 3, pot: 4.5, stk: 100 });
console.log('  JJ@MP vs open(中对不折损): call=' + Math.round((v4b.call || 0) / 3) + '%');
ok(entryRate(v4b, 300) > 0.7, 'V4b JJ(77+/中对)不受set折损影响', entryRate(v4b, 300).toFixed(2));

/* ---------- 翻后 ---------- */
console.log('\n【POST-3】两同花+邻张面 wetness 升级 (修复前: 8c7c2d判dry wetness=1)');
const bt = G_.boardTexture([C('8', 'c'), C('7', 'c'), C('2', 'd')]);
console.log('  boardTexture(8c7c2d) = wetness:' + bt.wetness + ' category:' + bt.category);
ok(bt.wetness === 2 && bt.category === 'semi-wet', 'V5 8c7c2d升semi-wet(修复前dry/1)');
const bt2 = G_.boardTexture([C('A', 'h'), C('7', 'h'), C('2', 'd')]);
console.log('  boardTexture(Ah7h2d) = wetness:' + bt2.wetness + ' category:' + bt2.category);
ok(bt2.wetness === 1, 'V5b Ah7h2d(两花无邻张)保持dry(不过度升级)', bt2.category);

function cbetSizing(holeC, commC, eqInj, potSz) {
  G_.FrameDiffEngine._al = [];
  G_.ActionLine.reset();
  G_.ActionLine.record('preflop', 'open', 'raise');
  G_.G.hole = holeC; G_.G.comm = commC;
  G_.G.phase = 'post'; G_.G.pot = potSz || 10; G_.G.bet = 0; G_.G.stk = 100;
  G_.G.scene = 'check'; G_.G.pos = 'btn'; G_.G.opp = 'unknown';
  G_.G.act = 2; G_.G.tt = 2; G_.G._facing3bet = false; G_.G._heroDid4bet = false;
  try { return G_.StrategyEngine.decidePostflop(null, { eq: eqInj }); } catch (ex) { return null; }
}
function medSizing(holeC, commC, eqInj, potSz, reps) {
  reps = reps || 60; const szs = [];
  for (let i = 0; i < reps; i++) {
    const r = cbetSizing(holeC, commC, eqInj, potSz);
    if (r && (r.a === 'raise' || r.a === 'bet') && r.v) szs.push(r.v / (potSz || 10));
  }
  if (!szs.length) return null;
  return szs.slice().sort((a, b) => a - b)[Math.floor(szs.length / 2)];
}

console.log('\n【POST-2】坚果deny-equity收费 (修复前: 88@8c7c2d set cbet仅30%pot)');
const hc88 = G_.handClassify([C('8', 'h'), C('8', 's')], [C('8', 'c'), C('7', 'c'), C('2', 'd')]);
console.log('  handClassify(88@8c7c2d)=' + JSON.stringify(hc88));
const v6sz = medSizing([C('8', 'h'), C('8', 's')], [C('8', 'c'), C('7', 'c'), C('2', 'd')], 78, 10);
console.log('  88@8c7c2d set CBet尺寸中位: ' + (v6sz ? Math.round(v6sz * 100) + '%pot' : '无下注'));
ok(v6sz !== null && v6sz >= 0.6, 'V6 set@同花连牌面尺寸≥60%pot(修复前30%, deny equity收费)', v6sz ? Math.round(v6sz * 100) + '%' : 'null');

console.log('\n【POST-1】威胁降级hazard透传+控池降档 (修复前: 降级牌威胁面尺寸80%pot失控池)');
const hcAQ = G_.handClassify([C('A', 's'), C('Q', 'd')], [C('Q', 's'), C('J', 's'), C('9', 'c')]);
console.log('  handClassify(AQ@QsJs9c)=' + JSON.stringify(hcAQ));
ok(hcAQ && hcAQ.name === 'MEDIUM', 'V7 AQ@QT9连牌面降级MEDIUM(v698 D5保持)', hcAQ && hcAQ.name);
ok(hcAQ && hcAQ.hazard === 'board', 'V7b hazard字段透传=board(修复前undefined)', hcAQ && String(hcAQ.hazard));
const v7sz = medSizing([C('A', 's'), C('Q', 'd')], [C('Q', 's'), C('J', 's'), C('9', 'c')], 55, 10);
console.log('  AQ@QsJs9c 降级MEDIUM CBet尺寸中位: ' + (v7sz ? Math.round(v7sz * 100) + '%pot' : '无下注'));
ok(v7sz !== null && v7sz <= 0.65, 'V7c 降级牌威胁面尺寸≤65%pot(修复前80%, hazard降p档控池)', v7sz ? Math.round(v7sz * 100) + '%' : 'null');
ok(v7sz === null || v7sz >= 0.5, 'V7d 降档后仍≥50%pot(保留薄价值/保护, 不弃价值)', v7sz ? Math.round(v7sz * 100) + '%' : 'null');

console.log('\n【回归】不受影响的场景');
const v8sz = medSizing([C('A', 's'), C('K', 'd')], [C('Q', 's'), C('7', 'd'), C('2', 'c')], 62, 10);
console.log('  AKo@Qs7d2c 干燥面顶对(STRONG无hazard)尺寸: ' + (v8sz ? Math.round(v8sz * 100) + '%pot' : '无下注'));
ok(v8sz === null || (v8sz <= 0.4), 'V8 干燥面顶对尺寸保持小注档(≤40%, 修复前30%)', v8sz ? Math.round(v8sz * 100) + '%' : 'null');
const hcAK = G_.handClassify([C('A', 's'), C('K', 'd')], [C('Q', 's'), C('7', 'd'), C('2', 'c')]);
ok(hcAK && !hcAK.hazard, 'V8b 干燥面顶对无hazard(不触发降档)', hcAK && String(hcAK.hazard));

console.log('\n================================================================');
console.log(' 升级验证: ' + pass + ' 通过, ' + fail + ' 失败');
console.log('================================================================');
process.exit(fail > 0 ? 1 : 0);
