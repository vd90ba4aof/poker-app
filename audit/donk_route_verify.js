#!/usr/bin/env node
/* donk_route_verify.js — V2.9.696 决策层端到端验证
 * mock 环境加载完整引擎 → 经 StrategyEngine.decidePostflop 驱动
 * 证据链: ①返回值 scene 字段(donk 路由加'·Donk'标记) ②引擎内部日志捕获(面CBet/面CR)
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
global.navigator = { userAgent: 'node-donk-verify' };
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
if (typeof (global.StrategyEngine && global.StrategyEngine.decidePostflop) !== 'function') {
  console.error('StrategyEngine.decidePostflop 不可访问'); process.exit(2);
}

// ---------- 引擎日志捕获 ----------
let engineLogs = [];
const origLog = console.log;
console.log = function() {
  const s = Array.from(arguments).join(' ');
  if (/面CR|面CBet|Donk|donk/i.test(s)) engineLogs.push(s);
  origLog.apply(console, arguments);
};

// ---------- 场景构造 ----------
const C = (r, s) => ({ rank: r, suit: s });
const FLOP3 = [C('7','h'), C('8','d'), C('2','c')];
const TURN4 = [C('7','h'), C('8','d'), C('2','c'), C('K','s')];
const oppAct = (a, st, am) => ({ s: 2, a, am: am || 100, cf: 'm', st });
const OT = { a: 'our_turn', cf: 'h' };
const NS = st => ({ a: 'new_street', st, cf: 'h' });

function runCase(alLines, comm, actionLineFeeds, opts) {
  engineLogs = [];
  global.FrameDiffEngine._al = alLines;
  global.FrameDiffEngine._hid = 'AsKs';
  global.ActionLine.reset();
  (actionLineFeeds || []).forEach(f => global.ActionLine.record(f[0], f[1], f[2]));
  global.G.hole = [C('A','s'), C('K','s')];
  global.G.comm = comm;
  global.G.pot = 335; global.G.bet = 100; global.G.stk = 280;
  global.G.scene = 'bet';  // vision 产出 → SE 归一 'raise'
  global.G.pos = 'btn'; global.G.opp = 'unknown';
  global.G.act = 2; global.G.tt = 2; global.G._facing3bet = false;
  global.G._heroDid4bet = false;
  if (opts && opts.facing3bet) global.G._facing3bet = true;
  let result = null, err = null;
  try { result = global.StrategyEngine.decidePostflop(null, { eq: 55 }); } catch (e) { err = e; }
  return { result, err, logs: engineLogs.slice() };
}
const sceneOf = r => (r && r.result && r.result.scene) || '';
const reasonOf = r => (r && r.result && r.result.r) || '';

// ================================================================
console.log('\n【D1】hero PFR + 对手 flop donk → donk 防御路由(isDonk=true)');
let d1 = runCase(
  [oppAct('raise','preflop',120), oppAct('raise','flop',100), OT, NS('flop')],
  FLOP3, [['preflop','open','raise']]);
assert(d1.err === null, 'D1 decidePostflop 无异常', d1.err && (d1.err.message + ' | ' + (d1.err.stack||'').split('\n').slice(0,3).join(' § ')));
console.log('     返回: ' + (d1.result ? (d1.result.a + ' | scene=' + sceneOf(d1) + ' | ' + reasonOf(d1).slice(0,70)) : 'null'));
assert(/Donk/i.test(sceneOf(d1)), 'D1 返回 scene 含 Donk 标记 — donk 防御路由首次实际生效', 'scene=' + JSON.stringify(sceneOf(d1)) + ' logs=' + JSON.stringify(d1.logs));

// ================================================================
console.log('\n【D2】对手 PFR CBet + hero call → 普通 _facingCBet(isDonk=false)，不误判 donk');
let d2 = runCase(
  [oppAct('call','preflop',120), OT, oppAct('raise','flop',100), OT, NS('flop')],
  FLOP3, [['preflop','raise','call']]);
assert(d2.err === null, 'D2 decidePostflop 无异常', d2.err && d2.err.message);
console.log('     返回: ' + (d2.result ? (d2.result.a + ' | scene=' + sceneOf(d2) + ' | ' + reasonOf(d2).slice(0,70)) : 'null'));
assert(!/Donk/i.test(sceneOf(d2)), 'D2 不带 Donk 标记(普通CBet防御)', 'scene=' + JSON.stringify(sceneOf(d2)));
assert(d2.result !== null, 'D2 有防御决策产出(面CBet路径)', 'result=null');

// ================================================================
console.log('\n【D3】hero PFR cbet 后对手 check-raise（真 CR）→ _facingCR');
let d3 = runCase(
  [oppAct('raise','preflop',120), OT, NS('flop'), oppAct('raise','flop',150), OT],
  FLOP3, [['preflop','open','raise'], ['flop','check','raise']]);
assert(d3.err === null, 'D3 decidePostflop 无异常', d3.err && d3.err.message);
console.log('     返回: ' + (d3.result ? (d3.result.a + ' | scene=' + sceneOf(d3) + ' | ' + reasonOf(d3).slice(0,70)) : 'null'));
console.log('     日志: ' + JSON.stringify(d3.logs));
assert(/CR/i.test(sceneOf(d3)) || /面CR/.test(d3.logs.join(' ')), 'D3 走面CR路径 — isOppRaiseAfterHero 判据生效', 'scene=' + JSON.stringify(sceneOf(d3)));
assert(!/Donk/i.test(sceneOf(d3)), 'D3 不误入 donk 防御', 'scene=' + JSON.stringify(sceneOf(d3)));

// ================================================================
console.log('\n【D4】hero open 被 3bet 后 call（3bet pot）对手 flop cbet → 不判 donk');
let d4 = runCase(
  [oppAct('raise','preflop',120), oppAct('raise','flop',100), OT, NS('flop')],
  FLOP3, [['preflop','open','raise']], { facing3bet: true });
assert(d4.err === null, 'D4 decidePostflop 无异常', d4.err && d4.err.message);
assert(!/Donk/i.test(sceneOf(d4)), 'D4 3bet pot 不误判 donk(对手是翻前最后加注者)', 'scene=' + JSON.stringify(sceneOf(d4)));
console.log('     返回: ' + (d4.result ? (d4.result.a + ' | scene=' + sceneOf(d4)) : 'null(落通用防御, 与695前一致不恶化)'));

// ================================================================
console.log('\n【D5】turn 面对donk → 街闸生效(flop/river限定, turn不扩展)');
let d5 = runCase(
  [oppAct('raise','preflop',120), OT, NS('flop'), oppAct('raise','flop',100), OT, NS('turn'), oppAct('raise','turn',120), OT],
  TURN4, [['preflop','open','raise'], ['flop','check','check']]);
assert(d5.err === null, 'D5 decidePostflop 无异常', d5.err && d5.err.message);
assert(!/Donk/i.test(sceneOf(d5)), 'D5 turn 不进donk防御(街闸)', 'scene=' + JSON.stringify(sceneOf(d5)));
console.log('     返回: ' + (d5.result ? (d5.result.a + ' | scene=' + sceneOf(d5)) : 'null'));

console.log('\n========================================');
console.log(`donk/CR 决策层路由验证: ${pass} 通过, ${fail} 失败`);
console.log('========================================');
process.exit(fail > 0 ? 1 : 0);
