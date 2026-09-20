#!/usr/bin/env node
/* threat_fix_verify.js — V2.9.698 修复效果验证(修复前后对照)
 * D1: 非坚果顺守卫短路 → 真场景hero 54@9876(对手Tx成T高顺)应降STRONG; 假阳性场景98@765保持NUTS
 * D2: 公对面FH/quads键清零 → river顺子@67789 范围应含66-99, eq 92.1%→~80%
 * D3: 平跟强牌注入 → CB范围含QQ/AKs/AKo, set88@K82 eq下降
 * D4: 公对组合重加权 → 顺子@6778 turn eq跨街下降
 * D5: 连牌面顶对降级 + 已成顺加权 → AQ@QT9 class STRONG→MEDIUM, eq下降
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
let pass = 0, fail = 0;
function ok(cond, name, detail) {
  if (cond) { pass++; console.log('  ✅ ' + name); }
  else { fail++; console.log('  ❌ ' + name + (detail ? '  → ' + detail : '')); }
}
function eqAvg(hole, comm, range, iter, reps) {
  reps = reps || 3; let s = 0;
  for (let i = 0; i < reps; i++) s += G_.mcVsRange(hole, comm, range, iter, 1).eq;
  return s / reps;
}

console.log('\n================================================================');
console.log(' V2.9.698 修复效果验证 (修复前→修复后对照)');
console.log('================================================================');

console.log('\n[D1] 非坚果顺守卫短路修复');
{
  // 真实bug场景: hero 54@9-8-7-6(turn), hero顺顶9=板面最高, 任意对手Tx成T-9-8-7-6更高顺
  const r1 = G_.handClassify([C('5','c'), C('4','d')], [C('9','h'), C('8','s'), C('7','c'), C('6','d')]);
  console.log('  D1a hero 54@9876(对手Tx成T高顺): ' + r1.name + '/' + (r1.desc||''));
  ok(r1.name !== 'NUTS', 'D1a 非坚果顺不再误判NUTS(修复前: NUTS)');
  // 假阳性场景回归: 98@765 hero顺顶9=板面可能最大顺顶(9-8-7-6-5窗口对手98同级) → 真坚果保持
  const r2 = G_.handClassify([C('9','c'), C('8','d')], [C('7','h'), C('6','s'), C('5','c')]);
  console.log('  D1b hero 98@765(真坚果,9-8-7-6-5为最大可能窗口): ' + r2.name);
  ok(r2.name === 'NUTS', 'D1b 真坚果顺不误伤(窗口扫描正确判定无更大窗口)');
  // Broadway坚果: AK@QJT
  const r3 = G_.handClassify([C('A','c'), C('K','d')], [C('Q','s'), C('J','h'), C('T','c')]);
  console.log('  D1c hero AK@QJT(Broadway坚果): ' + r3.name);
  ok(r3.name === 'NUTS', 'D1c Broadway坚果顺保持NUTS');
  // board 4连张底端: 5x@T-J-Q-K? 用 54@T-9-8-7? hero 5-4@T987=顺T高? 不: T,9,8,7+5 → T-9-8-7-5无顺。
  // 用 hero 65@9-8-7-6 → 顺9-8-7-6-5顶9? 板面9876+hero65 → 最佳=T?无T。9-8-7-6-5顶9=板面最高9, 对手Tx成T高顺
  const r4 = G_.handClassify([C('6','c'), C('5','d')], [C('9','h'), C('8','s'), C('7','c'), C('6','d')]);
  console.log('  D1d hero 65@9876(同类, 对手Tx反超): ' + r4.name);
  ok(r4.name !== 'NUTS', 'D1d board四连底端成顺不再判NUTS');
}

console.log('\n[D2] 公对面FH/quads键板面交互校正');
{
  const hole = [C('T','h'), C('9','d')];
  const comm = [C('6','c'), C('7','d'), C('7','h'), C('8','s'), C('9','c')];
  const bt = G_.boardTexture(comm);
  const spec = G_.postflopEqSpec(hole, comm, 'bet', 160, 200, bt, 2);
  const hasFH = ['66','77','88','99'].some(k => spec.range.indexOf(k) >= 0);
  console.log('  D2a river 67789 barrel_river范围(' + spec.range.length + '键): 含66/77/88/99=' + hasFH);
  ok(hasFH, 'D2a 公对面FH/quads键不再被TT+过滤清零(修复前: 全部剔除)');
  const eq = eqAvg(hole, comm, spec.range, 2000);
  console.log('  D2b 顺子T9@67789 eq=' + eq.toFixed(1) + '% (修复前92.1%, 真实区间35-60%)');
  ok(eq < 88, 'D2b river公对面顺子eq显著下降(威胁进eq)');
}

console.log('\n[D3] 平跟范围强牌回收');
{
  const comm = [C('8','c'), C('5','s'), C('K','h')];
  const bt = G_.boardTexture(comm);
  const spec = G_.postflopEqSpec([C('A','c'), C('K','d')], comm, 'bet', 160, 200, bt, 2);
  const hasQQ = spec.range.indexOf('QQ') >= 0, hasAKs = spec.range.indexOf('AKs') >= 0;
  console.log('  D3a cbet线范围(' + spec.range.length + '键): 含QQ=' + hasQQ + ' AKs=' + hasAKs);
  ok(hasQQ && hasAKs, 'D3a 对手平跟范围回收QQ/AKs(修复前: 强牌全剥离)');
  const hole = [C('K','c'), C('Q','d')];
  const comm2 = [C('K','h'), C('8','s'), C('2','c')];
  const spec2 = G_.postflopEqSpec(hole, comm2, 'bet', 160, 200, G_.boardTexture(comm2), 2);
  const eq = eqAvg(hole, comm2, spec2.range, 1500);
  console.log('  D3b 顶对KQ@K82(被AK/AA平跟支配线) eq=' + eq.toFixed(1) + '% (修复前91%+, 注入AK后应回落)');
  ok(eq < 91, 'D3b 顶对被支配威胁进eq(AK/AKs平跟线回收生效)');
  // set不误伤确认: set赢所有注入强牌(QQ/AK输set), eq应保持高位
  const holeS = [C('8','c'), C('8','d')];
  const specS = G_.postflopEqSpec(holeS, comm2, 'bet', 160, 200, G_.boardTexture(comm2), 2);
  const eqS = eqAvg(holeS, comm2, specS.range, 1500);
  console.log('  D3c set88@K82 eq=' + eqS.toFixed(1) + '% (set赢全部注入强牌, 应保持高位不误杀)');
  ok(eqS >= 88, 'D3c set@干燥面不误伤(注入强牌对set是反向增强)');
}

console.log('\n[D4] 公对面组合重加权(turn落公对动态威胁)');
{
  const hole = [C('T','c'), C('9','d')];
  const turn = [C('6','h'), C('7','s'), C('8','d'), C('7','c')];
  const spec = G_.postflopEqSpec(hole, turn, 'bet', 100, 200, G_.boardTexture(turn), 2);
  const eq = eqAvg(hole, turn, spec.range, 1500);
  console.log('  D4a 顺子T9@6778 turn eq=' + eq.toFixed(1) + '% (修复前93.0%, flop基线92.4%)');
  ok(eq < 90, 'D4a turn落公对后eq下降(FH条件化进eq)');
  const hole2 = [C('A','h'), C('K','d')];
  const turn2 = [C('K','c'), C('9','h'), C('5','d'), C('5','c')];
  const spec2 = G_.postflopEqSpec(hole2, turn2, 'bet', 100, 200, G_.boardTexture(turn2), 2);
  const eq2 = eqAvg(hole2, turn2, spec2.range, 1500);
  console.log('  D4b 顶对两对化AK@K955 turn eq=' + eq2.toFixed(1) + '% (修复前87.7%)');
  ok(eq2 < 86, 'D4b 顶对@turn公对面eq回落(trips/FH条件化)');
}

console.log('\n[D5] 连牌面: 顶对降级 + 已成顺加权');
{
  const hole = [C('A','s'), C('Q','d')];
  const comm = [C('Q','c'), C('T','h'), C('9','d')];
  const hc = G_.handClassify(hole, comm);
  console.log('  D5a AQ@QT9 handClassify: ' + hc.name + '/' + (hc.desc||'') + (hc.detail?('/'+hc.detail):''));
  ok(hc.name !== 'STRONG' && hc.name !== 'NUTS', 'D5a 顶对@连牌面降级STRONG→MEDIUM(修复前: STRONG)');
  const spec = G_.postflopEqSpec(hole, comm, 'bet', 160, 200, G_.boardTexture(comm), 2);
  const eq = eqAvg(hole, comm, spec.range, 1500);
  console.log('  D5b AQ@QT9 eq=' + eq.toFixed(1) + '% (修复前64.0%, 真实48-58%)');
  ok(eq < 62, 'D5b 顶对@连牌面eq回落(已成顺组合×2加权+强牌回收)');
}

console.log('\n[回归] 不误伤抽检');
{
  // 干燥面set不误伤
  const hole = [C('8','c'), C('8','d')];
  const comm = [C('8','s'), C('2','d'), C('3','c')];
  const spec = G_.postflopEqSpec(hole, comm, 'bet', 50, 100, G_.boardTexture(comm), 2);
  const eq = eqAvg(hole, comm, spec.range, 1500);
  console.log('  R1 set@干燥面8s2d3c eq=' + eq.toFixed(1) + '% (v697基线96.1%)');
  ok(eq >= 88, 'R1 干燥面set不误伤(eq≥88%)');
  // 坚果顺@无威胁面
  const hole2 = [C('A','c'), C('K','d')];
  const comm2 = [C('Q','s'), C('J','h'), C('T','c')];
  const spec2 = G_.postflopEqSpec(hole2, comm2, 'bet', 50, 100, G_.boardTexture(comm2), 2);
  const eq2 = eqAvg(hole2, comm2, spec2.range, 1500);
  const hc2 = G_.handClassify(hole2, comm2);
  console.log('  R2 坚果顺AK@QJT eq=' + eq2.toFixed(1) + '% class=' + hc2.name);
  ok(hc2.name === 'NUTS' && eq2 >= 80, 'R2 Broadway坚果顺保持NUTS且eq不误杀');
  // monotone主线(v697核心)不回退
  const hole3 = [C('8','c'), C('8','d')];
  const comm3 = [C('8','h'), C('7','h'), C('9','h')];
  const spec3 = G_.postflopEqSpec(hole3, comm3, 'bet', 50, 100, G_.boardTexture(comm3), 2);
  const eq3 = eqAvg(hole3, comm3, spec3.range, 1500);
  console.log('  R3 set@monotone eq=' + eq3.toFixed(1) + '% (v697基线55.7%, 断言区间15-70%)');
  ok(eq3 > 15 && eq3 < 70, 'R3 monotone威胁认知保持(v697主线不回退)');
}

console.log('\n================================================================');
console.log(' 修复验证: ' + pass + ' 通过, ' + fail + ' 失败');
console.log('================================================================');
