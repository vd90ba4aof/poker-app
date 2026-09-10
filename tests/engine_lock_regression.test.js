#!/usr/bin/env node
/*
 * engine_lock_regression.test.js — V2.9.604 手牌锁跨手污染专项回归（发布门禁）
 *
 * 背景：v603真机日志实锤P0——手A Qh4h结束后手B Qh4s，连续两手rank组合相同(Q4)
 *   花色不同(h→s)，JS手牌锁rank-only判据(_newRankKey===_prevRankKey)误判"同一手牌"，
 *   锁段保持旧手牌Qh4h→turn 4红桃K高听牌被当同花NUTS、river一对Q当NUTS全下25BB。
 *
 * 用例：
 *   1. 同rank不同suit跨手(Qh4h→Qh4s)：Kotlin新锁信号帧必须按新牌处理
 *   2. 公牌清零跨手(河牌→新手preflop)：即使牌也相同也必须清锁
 *   3. 公牌数倒退(5张→0张)：硬换手信号
 *   4. 完全相同手牌跨手(8h8s→8h8s)+新锁信号：必须按新手牌重锁
 *   5. 同手锁保持：锁定帧手牌不被刷新（含公牌/底池更新路径）
 *   6. BUG-2：fold(odds)赔率不足型决策不被池驱反fold反转为付费动作（静态断言reason保护）
 *
 * 判定：任一断言失败 → exit 1（CI红牌阻断发布）。
 * 用法：node tests/engine_lock_regression.test.js [poker_helper.html路径]
 */
const fs = require('fs');
const path = require('path');
const vm = require('vm');

let pass = 0, fail = 0;
const failures = [];
function assert(cond, name, detail) {
  if (cond) { pass++; console.log('  ✅ ' + name); }
  else { fail++; failures.push(name + (detail ? '  → ' + detail : '')); console.log('  ❌ ' + name + (detail ? '  → ' + detail : '')); }
}

const htmlPath = process.argv[2] || path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'poker_helper.html');
if (!fs.existsSync(htmlPath)) { console.error('找不到引擎文件: ' + htmlPath); process.exit(2); }
const html = fs.readFileSync(htmlPath, 'utf8');
const code = [...html.matchAll(/<script(?![^>]*\bsrc=)[^>]*>([\s\S]*?)<\/script>/gi)].map(m => m[1]).join('\n;\n');

// ---------- mock DOM（与engine_smoke同契约）----------
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
          prop === 'click' || prop === 'blur' || prop === 'scrollIntoView' ||
          prop === 'insertBefore' || prop === 'cloneNode' || prop === 'contains' ||
          prop === 'dispatchEvent' || prop === 'remove') return () => (prop === 'contains' ? false : (prop === 'cloneNode' ? makeEl() : undefined));
      if (prop === 'children' || prop === 'childNodes') return [];
      if (prop === 'parentNode' || prop === 'firstChild' || prop === 'lastChild') return makeEl();
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
  body: makeEl(), documentElement: makeEl(), head: makeEl(),
  addEventListener(){}, removeEventListener(){}, readyState: 'complete'
};
global.window = global;
global.localStorage = { _d:{}, getItem(k){return this._d[k]||null;}, setItem(k,v){this._d[k]=String(v);}, removeItem(k){delete this._d[k];} };
global.sessionStorage = global.localStorage;
global.navigator = { userAgent: 'node-lockreg' };
global.location = { href: '', reload(){}, search: '', hash: '' };
global.history = { pushState(){}, replaceState(){} };
global.scrollTo = () => {};
global.Worker = class { postMessage(){} terminate(){} addEventListener(){} };
global.Blob = class {};
global.URL = { createObjectURL(){ return ''; }, revokeObjectURL(){} };
global.requestAnimationFrame = () => 0;
global.cancelAnimationFrame = () => {};
global.setTimeout = () => 0;   // 异步决策不执行——本测试只验证onVisionResult锁状态
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

try { vm.runInThisContext(code); } catch (e) { console.error('引擎加载失败: ' + e.message); process.exit(2); }
if (typeof global.onVisionResult !== 'function' || typeof global.G === 'undefined') {
  console.error('引擎关键符号缺失: onVisionResult/G'); process.exit(2);
}

// ---------- 帧构造 ----------
function card(rank, suit) { return { rank, suit }; }
function frame(opts) {
  const hole = opts.hole || [card('Q','h'), card('4','h')];
  const comm = opts.comm || [];
  return {
    is_poker_table: true,
    hole_cards: hole,
    community_cards: comm,
    street: opts.street || 'preflop',
    active_players: opts.actP || 6,
    total_players: 6,
    pot_size: opts.pot || 600,
    my_chips: opts.chips || 4800,
    blind_bb: 200, blind_sb: 100,
    to_call: opts.toCall != null ? opts.toCall : 200,
    buttons: opts.buttons || ['弃牌', '跟注', '加注'],
    d_button_position: opts.pos || 'BTN',
    my_position: opts.pos || 'BTN',
    hole_cards_locked: opts.locked != null ? opts.locked : true,
    lock_reason: opts.lockReason || '已锁定，跳过重识',
    lock_just_established: opts.newLock === true,
    hole_cards_source: 'fresh'
  };
}
function feed(data) {
  try { global.onVisionResult(data); } catch (e) { /* mock不全的无关报错忽略 */ }
}
function heroCards() {
  return (global.G.hole || []).map(c => c ? c.rank + (c.suit || '') : 'null').join(',');
}
function handKey() { return global.G._handLockKey || ''; }
function rankKey() { return global.G._rankLockKey || ''; }

// ============ 用例1: 同rank不同suit跨手 Qh4h → Qh4s（v603实锤P0场景）============
console.log('\n【用例1】同rank不同suit跨手 Qh4h→Qh4s + Kotlin新锁信号');
global.G.hole = [null, null]; global.G.comm = [null,null,null,null,null];
global.G._handLockKey = ''; global.G._rankLockKey = '';
// 手A: Qh4h 首锁帧
feed(frame({ hole:[card('Q','h'),card('4','h')], locked:false, lockReason:'首次识别锁定', newLock:true, street:'preflop', comm:[] }));
assert(handKey()==='Qh4h', '手A首帧锁key=Qh4h', '实际='+handKey());
assert(heroCards()==='Qh,4h', '手A首帧手牌=Qh,4h', '实际='+heroCards());
// 手A 同手锁定帧（锁应保持）
feed(frame({ hole:[card('Q','h'),card('4','h')], locked:true, lockReason:'已锁定，跳过重识', street:'flop',
  comm:[card('T','s'),card('K','h'),card('4','s')] }));
assert(heroCards()==='Qh,4h', '手A flop锁定帧手牌保持Qh,4h', '实际='+heroCards());
// 手A 河牌
feed(frame({ hole:[card('Q','h'),card('4','h')], locked:true, lockReason:'已锁定，跳过重识', street:'river',
  comm:[card('T','s'),card('K','h'),card('4','s'),card('Q','d'),card('8','s')] }));
assert(heroCards()==='Qh,4h', '手A river锁定帧手牌保持Qh,4h', '实际='+heroCards());
// 手B: Qh4s 新锁建立帧——真机时序: Kotlin analyze阶段锁从null建立, toJson时
//   hole_cards_locked已=true + lock_just_established=true(v603此帧locked=true+rank同→保持Qh4h=P0)
feed(frame({ hole:[card('Q','h'),card('4','s')], locked:true, lockReason:'首次识别锁定', newLock:true,
  street:'preflop', comm:[], chips:5775 }));
assert(handKey()==='Qh4s', '手B首帧锁key更新为Qh4s(不是Qh4h)', '实际='+handKey());
assert(heroCards()==='Qh,4s', '★手B手牌=Qh,4s——v603此帧被锁成Qh,4h是P0根因', '实际='+heroCards());
assert(rankKey()==='Q4', 'rankKey仍为Q4(rank相同)', '实际='+rankKey());
// 手B 后续锁定帧（用错误的旧suit h来模拟，证明锁不会回退）
feed(frame({ hole:[card('Q','h'),card('4','s')], locked:true, lockReason:'已锁定，跳过重识', street:'flop',
  comm:[card('K','c'),card('9','h'),card('8','h')] }));
assert(heroCards()==='Qh,4s', '手B flop锁定帧保持Qh,4s不回退Qh4h', '实际='+heroCards());

// ============ 用例2: 公牌清零跨手（河牌→新手preflop，无新锁信号兜底）============
console.log('\n【用例2】公牌清零硬信号: 河牌5张→preflop 0张');
global.G.hole = [null, null]; global.G.comm = [null,null,null,null,null];
global.G._handLockKey = ''; global.G._rankLockKey = '';
feed(frame({ hole:[card('A','h'),card('K','h')], locked:false, lockReason:'首次识别锁定', newLock:true, street:'preflop', comm:[] }));
feed(frame({ hole:[card('A','h'),card('K','h')], locked:true, street:'river',
  comm:[card('2','c'),card('5','d'),card('9','s'),card('J','h'),card('Q','c')] }));
assert(heroCards()==='Ah,Kh', '手A河牌手牌Ah,Kh', '实际='+heroCards());
// 新一手：手牌变成AhKs（rank相同AK！suit不同），Kotlin信号缺失(极端兜底), locked=true, 仅靠公牌清零
feed(frame({ hole:[card('A','h'),card('K','s')], locked:true, lockReason:'已锁定，跳过重识', newLock:false,
  street:'preflop', comm:[] }));
assert(heroCards()==='Ah,Ks', '公牌清零信号→按新牌Ah,Ks处理(不被AK rank锁死)', '实际='+heroCards());

// ============ 用例3: 公牌数倒退硬信号 ============
console.log('\n【用例3】公牌数倒退 4张→0张');
global.G.hole = [null, null]; global.G.comm = [null,null,null,null,null];
global.G._handLockKey = ''; global.G._rankLockKey = '';
feed(frame({ hole:[card('J','c'),card('T','c')], locked:false, lockReason:'首次识别锁定', newLock:true, street:'preflop', comm:[] }));
feed(frame({ hole:[card('J','c'),card('T','c')], locked:true, street:'turn',
  comm:[card('2','h'),card('7','d'),card('9','s'),card('K','c')] }));
assert(heroCards()==='Jc,Tc', '手A turn手牌Jc,Tc', '实际='+heroCards());
// 倒退到0张+新牌（JcTd，rank JT相同）, locked=true, 无Kotlin信号, 仅靠公牌倒退硬信号
feed(frame({ hole:[card('J','c'),card('T','d')], locked:true, lockReason:'已锁定，跳过重识', newLock:false,
  street:'preflop', comm:[] }));
assert(heroCards()==='Jc,Td', '公牌倒退信号→按新牌Jc,Td处理', '实际='+heroCards());

// ============ 用例4: 完全相同手牌跨手 8h8s → 8h8s（rank+suit全同）============
console.log('\n【用例4】完全相同手牌跨手(88→88) + Kotlin新锁信号');
global.G.hole = [null, null]; global.G.comm = [null,null,null,null,null];
global.G._handLockKey = ''; global.G._rankLockKey = '';
feed(frame({ hole:[card('8','h'),card('8','s')], locked:false, lockReason:'首次识别锁定', newLock:true, street:'preflop', comm:[] }));
feed(frame({ hole:[card('8','h'),card('8','s')], locked:true, street:'flop',
  comm:[card('Q','h'),card('T','s'),card('2','s')] }));
assert(heroCards()==='8h,8s', '手A flop 8h,8s', '实际='+heroCards());
// 手A河牌到达后置摊牌门控标记(模拟真实到河牌)
global.G._sdReachedRiver = true; global.G._sdHeroFolded = false;
// 新一手也是8h8s（概率极低但理论存在），Kotlin新锁信号必须强制判新手牌
feed(frame({ hole:[card('8','h'),card('8','s')], locked:true, lockReason:'首次识别锁定', newLock:true,
  street:'preflop', comm:[] }));
// 全同key手牌内容无法区分, 但_isNewHand应为true→preflop块新手牌清零逻辑必须执行:
// 可观测断言: 上一手摊牌门控_sdReachedRiver被清零(v603全同key判同手→不清零, 可区分)
assert(global.G._sdReachedRiver!==true, '全同手牌跨手: 新锁信号→_sdReachedRiver已清零(_isNewHand生效)',
  '实际_sdReachedRiver='+global.G._sdReachedRiver);
// 新锁帧后再喂flop公牌帧，锁仍正常保持且G.comm更新为公牌
feed(frame({ hole:[card('8','h'),card('8','s')], locked:true, street:'flop',
  comm:[card('A','d'),card('K','d'),card('7','c')] }));
assert(heroCards()==='8h,8s', '手B 88跨手重锁后flop手牌仍8h,8s', '实际='+heroCards());
assert((global.G.comm||[]).filter(c=>c!==null).length===3, '手B flop公牌3张正常更新', '实际='+(global.G.comm||[]).filter(c=>c!==null).length+'张');

// ============ 用例5: 同手锁定帧手牌不被错误刷新 ============
console.log('\n【用例5】同手锁定保持');
global.G.hole = [null, null]; global.G.comm = [null,null,null,null,null];
global.G._handLockKey = ''; global.G._rankLockKey = '';
feed(frame({ hole:[card('K','s'),card('Q','s')], locked:false, lockReason:'首次识别锁定', newLock:true, street:'preflop', comm:[] }));
// 同手后续帧（Kotlin锁定帧，牌完全相同）
for (let i = 0; i < 3; i++) {
  feed(frame({ hole:[card('K','s'),card('Q','s')], locked:true, lockReason:'已锁定，跳过重识',
    street: i===0?'flop':(i===1?'turn':'river'),
    comm: i===0?[card('2','s'),card('7','h'),card('9','d')]
        : i===1?[card('2','s'),card('7','h'),card('9','d'),card('J','c')]
        :      [card('2','s'),card('7','h'),card('9','d'),card('J','c'),card('A','s')] }));
}
assert(heroCards()==='Ks,Qs', '同手flop/turn/river三帧手牌始终Ks,Qs', '实际='+heroCards());
assert((global.G.comm||[]).filter(c=>c!==null).length===5, 'river公牌5张', '实际='+(global.G.comm||[]).filter(c=>c!==null).length+'张');

// ============ 用例6: BUG-2 池驱反fold 赔率fold保护（静态断言源码闸门存在）============
console.log('\n【用例6】BUG-2 池驱反fold牌力闸门源码保护');
const js = code;
assert(js.indexOf("result.r.indexOf('fold(odds)')>=0")>=0 || js.indexOf("indexOf('fold(odds)')")>=0,
  "赔率不足fold(fold(odds)标记)豁免反转付费动作的闸门存在");
assert(js.indexOf('_airEqFloor')>=0 && js.indexOf("_psmAir")>=0,
  "AIR空气牌低eq反转付费动作闸门存在");
// v604锁判据保护
assert(js.indexOf('_newHandSignal')>=0 && js.indexOf("lock_just_established")>=0,
  "Kotlin新锁信号换手检测存在");
assert(js.indexOf('公牌数倒退')>=0 && js.indexOf('公牌清零')>=0,
  "公牌倒退/清零硬换手信号存在");
assert(js.indexOf('_suitConflictSameRank')>=0,
  "同手花色分歧保旧牌+多帧复核分支存在");

// ============ 用例7: V2.9.605 offsuit BTN弱牌过滤（62o等不得因posMod越闸）============
console.log('\n【用例7】V2.9.605 offsuit BTN弱牌过滤');
// 静态断言: _earlyPos数组包含'btn'
assert(code.indexOf("['utg','utg1','mp','mp1','hj','co','btn']") >= 0,
  "BTN已加入_earlyPos offsuit弱牌过滤数组");
// 静态断言: 62o不在RFI.BTN范围
var rfiBtnMatch = code.match(/BTN:\{([^}]+)\}/);
assert(rfiBtnMatch && rfiBtnMatch[1].indexOf('62o') === -1,
  "62o不在RFI.BTN范围(新引擎fold)");
// 静态断言: 62o不在O6.btn范围
var o6btnIdx = code.indexOf("O6={");
var o6btnSection = code.substring(o6btnIdx, o6btnIdx + 3000);
assert(o6btnSection.indexOf("'62o'") === -1 && o6btnSection.indexOf("62o") === -1,
  "62o不在O6.btn范围(fallback eQ=30)");
// 静态断言: eQ fallback对未匹配手牌base=30
assert(code.indexOf("base=30") >= 0 && code.indexOf("未匹配手牌") >= 0,
  "eQ fallback对未匹配手牌base=30(posMod BTN +6→eq=36)");
// 端到端: 设置G状态调用preF验证62o BTN facing raise → fold
try {
  global.G.pos = 'btn'; global.G.scene = 'raise'; global.G.bet = 1;
  global.G.pot = 3; global.G.stk = 100; global.G.ante = 0;
  global.G.hole = [{rank:'6',suit:'o'},{rank:'2',suit:'o'}];
  global.G.comm = []; global.G.opp = 'regular'; global.G.act = 2;
  global.G.tt = 6; global.G.ap = 2;
  global.G._inPotPlayers = 2; global.G.opp_seats = [];
  global.G._lastBlindBB = 100;
  var _result = global.preF('62o');
  assert(_result && _result.a === 'fold',
    "62o BTN facing raise → fold(不是call)", "实际=" + (_result ? _result.a : 'null'));
} catch(e) {
  assert(false, "62o BTN facing raise → preF执行异常", e.message);
}

// ============ 汇总 ============
console.log('\n========================================');
console.log('手牌锁专项回归: ' + pass + ' 通过, ' + fail + ' 失败');
if (fail > 0) { console.error('❌ 失败项:\n - ' + failures.join('\n - ')); process.exit(1); }
console.log('✅ 全部通过');
