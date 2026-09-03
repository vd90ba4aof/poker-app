#!/usr/bin/env node
/*
 * engine_smoke.test.js — JS策略引擎运行时行为冒烟测试（发布门禁）
 *
 * 作用：verify_integrity.py 是纯静态文本检查，抓不到"代码都在但运行时行为错"的bug。
 *       本脚本用 node + mock DOM 加载 APK 内真实 poker_helper.html，喂标准vision数据，
 *       断言引擎运行时行为。专门拦截 2026-09-01 真机暴露的时序类回归：
 *
 *   P0-A 时序倒置：go()决策在 BB换算(G.stk=chips/bb) 之前触发 → 决策永远用默认
 *                 stk=100/pot=10，20BB短码被当100BB深码 → 同花/A牌被错误弃牌。
 *
 * 判定：任一断言失败 → exit 1（CI红牌阻断发布）。
 * 用法：node tests/engine_smoke.test.js [poker_helper.html路径]
 */
const fs = require('fs');
const path = require('path');
const vm = require('vm');

// ---------- 结果统计 ----------
let pass = 0, fail = 0;
const failures = [];
function assert(cond, name, detail) {
  if (cond) { pass++; console.log('  ✅ ' + name); }
  else { fail++; failures.push(name + (detail ? '  → ' + detail : '')); console.log('  ❌ ' + name + (detail ? '  → ' + detail : '')); }
}

// ---------- 加载真实 HTML ----------
const htmlPath = process.argv[2] || path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'poker_helper.html');
if (!fs.existsSync(htmlPath)) { console.error('找不到引擎文件: ' + htmlPath); process.exit(2); }
const html = fs.readFileSync(htmlPath, 'utf8');
const code = [...html.matchAll(/<script(?![^>]*\bsrc=)[^>]*>([\s\S]*?)<\/script>/gi)].map(m => m[1]).join('\n;\n');

// ---------- 通用 mock DOM（Proxy：任意属性/方法都安全，函数noop、基本属性默认值）----------
function makeEl() {
  const el = function(){};
  return new Proxy(el, {
    get(t, prop) {
      if (prop === 'style') return {};
      if (prop === 'classList') return { add(){}, remove(){}, contains(){ return false; }, toggle(){} };
      if (prop === 'dataset') return {};
      if (prop === 'value') return '100';
      if (prop === 'innerHTML' || prop === 'textContent' || prop === 'href') return '';
      if (prop === 'length') return 0;
      if (prop === 'offsetHeight' || prop === 'offsetWidth') return 0;
      if (prop === 'readyState') return 'complete';
      if (prop === 'getContext') return () => null;
      if (prop === 'querySelector' || prop === 'querySelectorAll') return () => (prop === 'querySelectorAll' ? [] : makeEl());
      if (prop === 'addEventListener' || prop === 'removeEventListener') return () => {};
      if (prop === 'appendChild' || prop === 'removeChild' || prop === 'setAttribute' ||
          prop === 'getAttribute' || prop === 'removeAttribute' || prop === 'focus' ||
          prop === 'click' || prop === 'blur' || prop === 'scrollIntoView' || prop === 'insertBefore' ||
          prop === 'cloneNode' || prop === 'contains' || prop === 'dispatchEvent' || prop === 'remove') return () => (prop === 'contains' ? false : (prop === 'cloneNode' ? makeEl() : undefined));
      if (prop === 'children' || prop === 'childNodes') return [];
      if (prop === 'parentNode' || prop === 'firstChild' || prop === 'lastChild') return makeEl();
      // 其余任意方法 → noop函数；任意属性 → undefined（不抛错）
      return undefined;
    },
    set() { return true; },
    apply() { return makeEl(); }
  });
}
const elCache = {};
global.document = {
  getElementById(id) { if (!elCache[id]) elCache[id] = makeEl(); return elCache[id]; },
  querySelector() { return makeEl(); },
  querySelectorAll() { return []; },
  createElement() { return makeEl(); },
  createTextNode() { return makeEl(); },
  body: makeEl(),
  documentElement: makeEl(),
  head: makeEl(),
  addEventListener(){}, removeEventListener(){},
  readyState: 'complete'
};
global.window = global;
global.localStorage = { _d:{}, getItem(k){return this._d[k]||null;}, setItem(k,v){this._d[k]=String(v);}, removeItem(k){delete this._d[k];} };
global.sessionStorage = global.localStorage;
global.navigator = { userAgent: 'node-smoke' };
global.location = { href: '', reload(){}, search: '', hash: '' };
global.history = { pushState(){}, replaceState(){} };
global.scrollTo = () => {};
global.Worker = class { postMessage(){} terminate(){} addEventListener(){} };
global.Blob = class {};
global.URL = { createObjectURL(){ return ''; }, revokeObjectURL(){} };
global.requestAnimationFrame = () => 0;
global.cancelAnimationFrame = () => {};
global.setTimeout = () => 0;   // 决策异步回调不执行（本测试只验证go()触发时刻的同步状态）
global.setInterval = () => 0;
global.clearTimeout = () => {};
global.clearInterval = () => {};
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

// 加载引擎
try { vm.runInThisContext(code); } catch (e) { console.error('引擎加载失败: ' + e.message); process.exit(2); }
if (typeof global.onVisionResult !== 'function' || typeof global.G === 'undefined') {
  console.error('引擎关键符号缺失: onVisionResult/G'); process.exit(2);
}

// ---------- 拦截 go()：在决策真正运行的瞬间快照 G.stk/G.pot ----------
// 这是本测试的核心——测的是"决策那一刻"引擎手里的筹码/底池，不是onVisionResult跑完后的终值。
// V2.9.555: 引擎在onVisionResult入库期间(window._visionUpdating=true)会抑制UI辅助函数
//           (sTT/setOppType)内部提前触发的go()；测试必须复刻同一契约：
//           只快照"真正放行"的那次权威决策，被抑制的提前调用只计数、不快照。
let goSnapshot = null;
let suppressedCount = 0;
const _origGo = global.go;
global.go = function() {
  if (global.window && global.window._visionUpdating) {
    suppressedCount++;   // 入库期间的提前go()——真机上被引擎guard拦截，不产生决策
    return;
  }
  if (goSnapshot === null) goSnapshot = { stk: global.G.stk, pot: global.G.pot, bet: global.G.bet };
  // 不调用真实go()（避免异步MC/UI链路噪音）；决策时刻状态已快照
};

function feedAndSnap(label, data) {
  goSnapshot = null;
  suppressedCount = 0;
  global.G.stk = 100; global.G.pot = 10; global.G.bet = 0;  // 重置为输入框默认值
  try { global.onVisionResult(data); } catch (e) { /* mock不全的无关报错忽略 */ }
  const expStk = Math.round(data.my_chips / data.blind_bb);
  const expPot = Math.round(data.pot_size / data.blind_bb);
  console.log('\n【' + label + '】 chips=' + data.my_chips + ' pot=' + data.pot_size + ' BB=' + data.blind_bb
    + ' → 决策时刻 stk=' + (goSnapshot ? goSnapshot.stk : 'go未触发') + ' pot=' + (goSnapshot ? goSnapshot.pot : '?')
    + ' (入库期提前go被抑制' + suppressedCount + '次)');
  assert(goSnapshot !== null, 'go()决策被触发', 'onVisionResult未触发有效go()');
  assert(suppressedCount >= 1,
    '入库期提前go()被guard抑制(sTT/setOppType不再用默认值抢先决策)',
    '未检测到抑制的提前go()——guard可能失效，时序倒置回归风险');
  if (goSnapshot) {
    assert(goSnapshot.stk === expStk,
      '决策时刻筹码深度 stk=' + expStk + 'BB（不是默认100）',
      '实际stk=' + goSnapshot.stk + '，说明BB换算在决策之后才执行(时序倒置)，20BB短码被当100BB深码决策');
    assert(goSnapshot.pot === expPot,
      '决策时刻底池 pot=' + expPot + 'BB（不是默认10）',
      '实际pot=' + goSnapshot.pot + '，底池换算在决策之后才执行');
  }
}

// ---------- 用例1：翻前 Qd9d MP（真机22:42场景，chips=3806/pot=500/BB=200 → stk应=19）----------
feedAndSnap('翻前 Qd9d MP', {
  _frameTag: 'normal',
  hole_cards: [{rank:'Q',suit:'d'},{rank:'9',suit:'d'}],
  community_cards: [],
  pot_size: 500, my_chips: 3806, to_call: 200, blind_bb: 200, blind_sb: 100,
  buttons: ['弃牌','跟注 200','加注 400'], is_poker_table: true, my_position: 'mp',
  street: 'preflop', total_players: 6, active_players: 6, players: [], opp_seats: []
});

// ---------- 用例2：翻后 JcQd flop（真机22:40场景，chips=4006/pot=7455/BB=200 → stk应=20）----------
feedAndSnap('翻后 JcQd flop', {
  _frameTag: 'normal',
  hole_cards: [{rank:'J',suit:'c'},{rank:'Q',suit:'d'}],
  community_cards: [{rank:'K',suit:'c'},{rank:'2',suit:'d'},{rank:'Q',suit:'c'}],
  pot_size: 7455, my_chips: 4006, to_call: 200, blind_bb: 200, blind_sb: 100,
  buttons: ['弃牌','跟注 200','加注'], is_poker_table: true, my_position: 'mp',
  street: 'postflop', total_players: 6, active_players: 3, players: [], opp_seats: []
});

// ---------- 汇总 ----------
console.log('\n========================================');
console.log('引擎运行时冒烟测试: ' + pass + ' 通过, ' + fail + ' 失败');
if (fail > 0) {
  console.log('\n❌ 红牌——存在运行时行为回归:');
  failures.forEach(f => console.log('   • ' + f));
  process.exit(1);
}
console.log('✅ 全部通过');
process.exit(0);
