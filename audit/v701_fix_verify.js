/* v701_fix_verify.js — V2.9.701 五项修复验证(实战审查报告建议1-5)
 * 场景全部来自 poker_log_20260921_041730.json 实证案例, 修复前后行为对照 */
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
let pass = 0, fail = 0;
function ok(cond, name, detail) {
  if (cond) { pass++; console.log('  ✅ ' + name); }
  else { fail++; console.log('  ❌ ' + name + (detail ? '  → ' + detail : '')); }
}

function preflopRun(hk, o, N) {
  N = N || 200; const counts = {}; const reasons = {};
  for (let i = 0; i < N; i++) {
    global.ActionLine.reset();
    const m = hk.match(/^([2-9TJQKA])([2-9TJQKA])(s|o)?$/);
    const s1 = (m[3]==='s')?'s':'c', s2 = (m[3]==='s')?'s':'d';
    global.G.hole = [C(m[1],s1), C(m[2],s2)];
    global.G.comm = []; global.G.phase = 'pre';
    global.G.pos = o.pos; global.G.scene = o.scene;
    global.G.bet = o.bet; global.G.pot = o.pot; global.G.stk = o.stk;
    global.G.opp = o.opp || 'fish'; global.G.tt = o.tt || 6; global.G.act = o.act || 2;
    global.G.limpers = o.limpers || 0; global.G._facing3bet = false; global.G._heroDid4bet = false;
    global.G._raiserRole = o.raiserRole || 'mp';
    if (o.inPot) global.G._inPotPlayers = o.inPot; else { try { delete global.G._inPotPlayers; } catch(e){} }
    if (o.oppSeats) global.G.oppSeats = o.oppSeats; else { try { delete global.G.oppSeats; } catch(e){} }
    let r = null;
    try { r = global.StrategyEngine.decidePreflop(global.getHandKey()); } catch (ex) { continue; }
    const a = r ? r.a : 'null';
    counts[a] = (counts[a] || 0) + 1;
    if (r && r.r) reasons[r.r.split('(')[0].slice(0, 30)] = true;
  }
  return { counts, reasons, N };
}
const rate = (d, a) => (d.counts[a] || 0) / d.N;

console.log('\n================================================================');
console.log(' V2.9.701 修复验证: 实战审查报告建议1-5 (修复前→修复后)');
console.log('================================================================');

console.log('\n【FIX-1】FlatCall 底池赔率豁免 (修复前: A2o/T4o小额跟注100%fold丢EV)');
const r1 = preflopRun('A2o', {pos:'bb', scene:'raise', bet:1, pot:7, stk:87.5});
console.log('  A2o@BB需跟1进7池(需求12.5%): call=' + Math.round(rate(r1,'call')*100) + '% fold=' + Math.round(rate(r1,'fold')*100) + '%');
ok(rate(r1,'call') > 0.9, 'F1a A2o@BB小额跟注豁免放行(修复前100%fold, 实战[03]实证)', 'call=' + Math.round(rate(r1,'call')*100) + '%');
const r2 = preflopRun('T4o', {pos:'bb', scene:'raise', bet:0.5, pot:2.5, stk:45});
console.log('  T4o@BB需跟0.5(需求16.7%): call=' + Math.round(rate(r2,'call')*100) + '% fold=' + Math.round(rate(r2,'fold')*100) + '%');
ok(rate(r2,'call') > 0.9, 'F1b T4o@BB小额跟注豁免放行(修复前100%fold, 实战[18]实证)', 'call=' + Math.round(rate(r2,'call')*100) + '%');
const r1c = preflopRun('A2o', {pos:'bb', scene:'raise', bet:3, pot:7, stk:87.5});
console.log('  A2o@BB需跟3进7池(需求30%,豁免线外): call=' + Math.round(rate(r1c,'call')*100) + '% fold=' + Math.round(rate(r1c,'fold')*100) + '%');
ok(rate(r1c,'call') < 0.2, 'F1c 豁免不越界——正常3BB open防御保持收紧(需求30%>17%不豁免)', 'call=' + Math.round(rate(r1c,'call')*100) + '%');

console.log('\n【FIX-2】Nash触发尺寸守卫+全押A闸范围感知 (修复前: 池内15BB短码在场+40BB allin误触发短码Nash表)');
// 日志捕获: 验证Nash守卫触发+A闸fish范围感知
let aiLogBuf = [];
const _origLog2 = console.log;
console.log = function(){ aiLogBuf.push(Array.from(arguments).join(' ')); };
const r7 = preflopRun('K3o', {pos:'bb', scene:'allin', bet:40, pot:143, stk:40, oppSeats:[{chips:3000, active:true, action:'raise'}], inPot:4});
console.log = _origLog2;
const nashGuardHit = aiLogBuf.some(l => l.indexOf('[V2.9.701Nash守卫]') >= 0);
const fishRangeHit = aiLogBuf.some(l => l.indexOf('[V2.9.701全押范围]') >= 0);
const aiPathHit = aiLogBuf.some(l => l.indexOf('[V2.9.689全押硬闸]') >= 0 || l.indexOf('[V2.9.625全押eq]') >= 0);
console.log('  K3o@BB vs allin40进143(池内15BB短码): ' + JSON.stringify(r7.counts) + ' reason样本: ' + Object.keys(r7.reasons).join(' / ').slice(0, 80));
console.log('  Nash守卫日志: ' + (nashGuardHit ? '✓触发(跳过Nash)' : '✗未触发') + ' | fish范围感知: ' + (fishRangeHit ? '✓gO宽表' : '✗') + ' | 走全押赔率路径: ' + (aiPathHit ? '✓' : '✗'));
ok(nashGuardHit, 'F2a Nash守卫生效——40BB需跟>15BB短码×1.15, 不再误触短码Nash表(实战[13])');
ok(aiPathHit && Object.keys(r7.reasons).some(x => x.indexOf('vs allin') >= 0), 'F2b 决策改走全押赔率路径(vs allin eq判定, 非Nash表)', JSON.stringify(Object.keys(r7.reasons)));
ok(fishRangeHit, 'F2c A闸fish范围感知——vs fish用gO开池范围替代gT 3bet紧表(实测K3o 26.7%→36.4%差10pp)');
// 数学核查注记(实事求是): 4人池K3o vs gO宽表nOpp=3真实MC eq≈17%, 需求21.9%<25%地板 → fold数学正确。
//   实战分析初判"应call"系单挑eq口径误判(36.4%是HU值), 多人池需赢多人通杀, eq客观回落。
//   守卫修复的是"路径错误"(Nash短码表误用), 最终fold是路径正确后的数学结论, 非缺陷。
const r7mc = aiLogBuf.find(l => l.indexOf('[V2.9.625全押eq]') >= 0);
if (r7mc) console.log('  MC实测: ' + r7mc.slice(0, 80));

console.log('\n【FIX-3】表内路径挂钩PF-1被支配折价 (修复前: QJo@BTN vs MP走表内GTO call 69%完全绕过折价)');
const r5 = preflopRun('QJo', {pos:'btn', scene:'raise', bet:1, pot:3.5, stk:87});
console.log('  QJo@BTN vs MP open: call=' + Math.round(rate(r5,'call')*100) + '% (修复前100%: GTO call 69%频率+硬底兜底)');
ok(rate(r5,'call') < 0.55 && rate(r5,'call') > 0.1, 'F3 QJo被支配折价生效(频率×0.5≈35%, 且硬底不再捞回)', 'call=' + Math.round(rate(r5,'call')*100) + '%');
const r5b = preflopRun('QJo', {pos:'btn', scene:'raise', bet:1, pot:3.5, stk:87, raiserRole:'btn'});
console.log('  对照QJo@BTN vs BTN open(后位不折价): call=' + Math.round(rate(r5b,'call')*100) + '%');
ok(rate(r5b,'call') > 0.8, 'F3b vs后位raiser不折价(与v699 PF-1同口径)', 'call=' + Math.round(rate(r5b,'call')*100) + '%');

console.log('\n【FIX-4】强牌硬底eq真实化 (修复前: 22查表56+bonus+修正=74虚高, ≥60误触发硬底不弃)');
const r6 = preflopRun('22', {pos:'btn', scene:'raise', bet:1, pot:3.5, stk:47});
const r6Reasons = Object.keys(r6.reasons).join('/');
console.log('  22@BTN vs MP: call=' + Math.round(rate(r6,'call')*100) + '% reasons: ' + r6Reasons.slice(0, 90));
ok(r6Reasons.indexOf('GTO强牌硬底 22') < 0, 'F4a 22不再触发强牌硬底(eq74×0.7=51.8<60)', r6Reasons.slice(0, 60));
ok(rate(r6,'call') > 0.4 && rate(r6,'call') < 0.95, 'F4b 22保留频率混合call(~69%频率命中, 廉价set mine不被一刀切)', 'call=' + Math.round(rate(r6,'call')*100) + '%');
const r6b = preflopRun('JJ', {pos:'mp', scene:'raise', bet:1, pot:3.5, stk:24});
ok(rate(r6b,'call') + rate(r6b,'raise') > 0.95, 'F4c JJ等大对子硬底保护不受影响(77+不在折损范围)');

console.log('\n【FIX-5】翻后威胁可观测+需求透明化');
// F5a: 威胁重加权标记(同花威胁面)
let consoleBuf = [];
const _origLog = console.log;
console.log = function(){ consoleBuf.push(Array.from(arguments).join(' ')); };
try {
  const hole=[C('A','c'),C('K','d')], comm=[C('Q','c'),C('7','c'),C('2','c')];
  global.mcVsRange(hole, comm, ['KK','QQ','JJ','TT','AK','AQ','AJ','KQ','KJ','QJ'], 200, 1);
} catch(e){}
console.log = _origLog;
const twHit = consoleBuf.find(l => l.indexOf('[V2.9.701威胁重加权]') >= 0);
console.log('  威胁面(Qc7c2c三同花)MC调用输出: ' + (twHit || '(无)'));
ok(!!twHit, 'F5a 威胁重加权运行时标记输出(修复前零可观测, 111条日志无威胁证据)');
// F5b: 非威胁面不输出
consoleBuf = [];
console.log = function(){ consoleBuf.push(Array.from(arguments).join(' ')); };
try {
  global._threatReweight._lastSig = undefined;
  const hole2=[C('A','c'),C('K','d')], comm2=[C('Q','d'),C('7','h'),C('2','s')];
  global.mcVsRange(hole2, comm2, ['KK','QQ','JJ','TT','AK','AQ','AJ','KQ','KJ','QJ'], 200, 1);
} catch(e){}
console.log = _origLog;
const twMiss = consoleBuf.find(l => l.indexOf('[V2.9.701威胁重加权]') >= 0);
ok(!twMiss, 'F5b 非威胁面(rainbow无连无对)不输出标记(无威胁不重加权, 原逻辑保持)');
// F5c: 收口需求透明化——弱对面对超顶牌面走收口弃牌(实测触发场景)
consoleBuf = [];
console.log = function(){ consoleBuf.push(Array.from(arguments).join(' ')); };
let foldReason = '';
try {
  for (let i = 0; i < 5; i++) {
    global.ActionLine.reset();
    global.G.hole=[C('3','d'),C('3','h')]; global.G.comm=[C('A','c'),C('K','d'),C('Q','s'),C('7','c')];
    global.G.phase='post'; global.G.pos='utg'; global.G.scene='bet';
    global.G.bet=4; global.G.pot=10; global.G.stk=50; global.G.opp='fish'; global.G.tt=6; global.G.act=2;
    try{delete global.G.oppSeats;}catch(e){}
    let r=null;
    try{ r=global.StrategyEngine.decidePostflop(global.getHandKey()); }catch(ex){}
    if(r && r.a==='fold' && /收口弃牌/.test(r.r||'')){ foldReason=r.r; break; }
  }
} catch(e){}
console.log = _origLog;
console.log('  收口弃牌reason: ' + (foldReason || '(未触发)').slice(0, 90));
ok(/=赔率\d+%\+策略\d+%/.test(foldReason), 'F5c 收口需求构成透明化(修复前只显示"需38%"不透明)');

console.log('\n================================================================');
console.log(' V2.9.701 修复验证: ' + pass + ' 通过, ' + fail + ' 失败');
console.log('================================================================');
process.exit(fail > 0 ? 1 : 0);
