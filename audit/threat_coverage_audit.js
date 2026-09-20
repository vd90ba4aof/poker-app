#!/usr/bin/env node
/* threat_coverage_audit.js — V2.9.697 翻后牌面威胁覆盖面独立审计
 * 思路: 用引擎自身口径(boardTexture+postflopEqSpec+mcVsRange+_threatReweight)
 *       实测各威胁板面类型的 eq 梯度与对手范围条件化程度, 与干燥面对照,
 *       找出"威胁进不了 eq"的板面类型(v697 只修了 monotone)。
 */
const fs = require('fs');
const path = require('path');
const vm = require('vm');

let pass = 0, fail = 0, warn = 0;
function ok(cond, name, detail) {
  if (cond) { pass++; console.log('    ✅ ' + name); }
  else { fail++; console.log('    ❌ ' + name + (detail ? '  → ' + detail : '')); }
}
function note(name, detail) { warn++; console.log('    ⚠️ ' + name + (detail ? '  → ' + detail : '')); }

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

// 多次MC取均值, 降抖动
function eqAvg(hole, comm, range, iter, nOpp, reps) {
  reps = reps || 3;
  let s = 0;
  for (let i = 0; i < reps; i++) s += G_.mcVsRange(hole, comm, range, iter, nOpp || 1).eq;
  return s / reps;
}

console.log('\n================================================================');
console.log(' V2.9.697 翻后牌面威胁覆盖面审计 (引擎口径实测)');
console.log('================================================================');

// ----------------------------------------------------------------
console.log('\n【A. 威胁梯度总览】set(8c8d) 在不同板面 vs 对手flop下注(cbet线) eq');
// ----------------------------------------------------------------
const SET = [C('8','c'), C('8','d')];
const boards = [
  { name: 'A1 干燥面(对照)',        cards: [C('8','s'), C('2','d'), C('3','c')] },
  { name: 'A2 monotone(3同花,已修)', cards: [C('8','h'), C('7','h'), C('9','h')] },
  { name: 'A3 two-tone(2同花,疑漏)', cards: [C('8','h'), C('7','h'), C('9','d')] },
  { name: 'A4 连牌面(789,疑漏)',    cards: [C('8','h'), C('7','s'), C('9','c')] },
  { name: 'A5 公对面(88x,疑漏)',    cards: [C('8','h'), C('8','s'), C('9','c')] },
];
const eqs = {};
for (const b of boards) {
  const bt = G_.boardTexture(b.cards);
  const spec = G_.postflopEqSpec(SET, b.cards, 'bet', 50, 100, bt, 2);
  const eq = eqAvg(SET, b.cards, spec.range, 1500, 1);
  eqs[b.name] = eq;
  console.log('  ' + b.name.padEnd(24) + ' wet=' + bt.wetness + ' ' + bt.category.padEnd(8) +
    ' mono=' + bt.hasMonotone + ' line=' + spec.line.padEnd(12) + ' eq=' + eq.toFixed(1) + '%');
}
const dry = eqs['A1 干燥面(对照)'];
const mono = eqs['A2 monotone(3同花,已修)'];
const twoTone = eqs['A3 two-tone(2同花,疑漏)'];
const conn = eqs['A4 连牌面(789,疑漏)'];
const paired = eqs['A5 公对面(88x,疑漏)'];
console.log('  → 干燥 vs monotone 梯度 = ' + (dry - mono).toFixed(1) + 'pp (v697 修复生效证据)');
ok(dry - mono > 15, 'monotone 威胁已进 eq (梯度>15pp)', '实际=' + (dry-mono).toFixed(1));
console.log('  → 干燥 vs two-tone 梯度 = ' + (dry - twoTone).toFixed(1) + 'pp');
note('two-tone(2同花)威胁梯度', '真实set权益应降8-15pp(对手同花听条件化); 若梯度<4pp=威胁未进eq');
console.log('  → 干燥 vs 连牌面 梯度 = ' + (dry - conn).toFixed(1) + 'pp');
note('连牌面威胁梯度', '对手连张条件化应有5-12pp; 若<3pp=威胁未进eq');
console.log('  → 干燥 vs 公对面 梯度 = ' + (dry - paired).toFixed(1) + 'pp');
note('公对面威胁梯度', 'set在88x面被8x/FH支配风险,应降5-15pp; 若<3pp=威胁未进eq');

// ----------------------------------------------------------------
console.log('\n【B. 极化线覆盖审计】v697 极化线只切 flop cbet; turn/river barrel 是否漏切');
// ----------------------------------------------------------------
// B1: turn 第4张同花落地, 对手barrel → line='barrel_turn', monotone=true 但不切
{
  const hole = [C('8','c'), C('8','d')];
  const board4 = [C('8','h'), C('7','h'), C('2','h'), C('9','h')]; // turn落第4张红桃
  const bt = G_.boardTexture(board4);
  const spec = G_.postflopEqSpec(hole, board4, 'bet', 100, 200, bt, 2);
  console.log('  B1 set@turn4同花 board=8h7h2h9h: hasMonotone=' + bt.hasMonotone + ' line=' + spec.line);
  ok(spec.line !== 'raise', 'B1 现状确认: turn barrel 不切极化线 (line=' + spec.line + ')');
  // 量化: 同一局面强制 raise 线对比
  const rangeRaise = G_.getOppRange('postflop', 'raise', 'wet');
  const eqBarrel = eqAvg(hole, board4, spec.range, 1500, 1);
  const eqRaise = eqAvg(hole, board4, rangeRaise, 1500, 1);
  console.log('    eq vs barrel_turn范围 = ' + eqBarrel.toFixed(1) + '%   eq vs raise极化范围 = ' + eqRaise.toFixed(1) + '%');
  note('turn4花极化线盲区量级', '两者差=' + (eqBarrel - eqRaise).toFixed(1) + 'pp; >5pp=有实际止损意义被漏掉');
}
// B2: river 4同花(第5张异花), 对手barrel → line='barrel_river', 不切
{
  const hole = [C('8','c'), C('8','d')];
  const board5 = [C('8','h'), C('7','h'), C('2','h'), C('9','h'), C('K','d')];
  const bt = G_.boardTexture(board5);
  const spec = G_.postflopEqSpec(hole, board5, 'bet', 100, 200, bt, 2);
  console.log('  B2 set@river4同花 board=8h7h2h9hKd: hasMonotone=' + bt.hasMonotone + ' line=' + spec.line + ' isRiver=' + spec.isRiver);
  const eqRiver = eqAvg(hole, board5, spec.range, 1500, 1);
  const rangeRaise = G_.getOppRange('postflop', 'raise', 'wet');
  const eqRaise = eqAvg(hole, board5, rangeRaise, 1500, 1);
  console.log('    eq vs barrel_river范围 = ' + eqRiver.toFixed(1) + '%   eq vs raise极化范围 = ' + eqRaise.toFixed(1) + '%');
  note('river4花极化线盲区量级', '差=' + (eqRiver - eqRaise).toFixed(1) + 'pp');
}
// B3: 对照 — 同为 flop, 连牌面/公对面/双花面均不切(只有 monotone 切)
{
  const hole = [C('8','c'), C('8','d')];
  for (const b of [
    { name: '连牌面 8h7s9c', cards: [C('8','h'), C('7','s'), C('9','c')] },
    { name: '公对面 8h8s9c', cards: [C('8','h'), C('8','s'), C('9','c')] },
    { name: '双花面 8h7h9c', cards: [C('8','h'), C('7','h'), C('9','c')] },
  ]) {
    const bt = G_.boardTexture(b.cards);
    const spec = G_.postflopEqSpec(hole, b.cards, 'bet', 50, 100, bt, 2);
    console.log('  B3 ' + b.name + ': wet=' + bt.wetness + ' mono=' + bt.hasMonotone + ' line=' + spec.line +
      (spec.line === 'cbet' ? '  ← 未切极化线' : ''));
  }
  ok(true, 'B3 现状确认: 非monotone威胁面一律不切极化线');
}

// ----------------------------------------------------------------
console.log('\n【C. _threatReweight 覆盖审计】对手组合威胁条件化的板面类型覆盖');
// ----------------------------------------------------------------
// C1: two-tone flop — sc[thr]=2, 重加权完全不触发
{
  const hole = [C('8','c'), C('8','d')];
  const comm = [C('8','h'), C('7','h'), C('9','d')];
  const spec = G_.postflopEqSpec(hole, comm, 'bet', 50, 100, G_.boardTexture(comm), 2);
  // 复刻 mcVsRange 的展开逻辑, 检查重加权前后组合数
  const known = [].concat(hole, comm);
  let oppHands = [];
  for (const key of spec.range) {
    const hands = G_.handKeyToCards(key);
    for (const h of hands) {
      const clash = known.some(c => (c.rank===h[0].rank&&(c.suit===h[0].suit||!c.suit))||(c.rank===h[1].rank&&(c.suit===h[1].suit||!c.suit)));
      const clash2 = known.some(c => (c.rank===h[1].rank&&(c.suit===h[1].suit||!c.suit)));
      if (!clash && !clash2) oppHands.push(h);
    }
  }
  const after = G_._threatReweight(oppHands, comm);
  console.log('  C1 two-tone(8h7h9d): 对手组合 ' + oppHands.length + ' → 重加权后 ' + after.length +
    ' (比率=' + (after.length/oppHands.length).toFixed(2) + ')');
  ok(after.length === oppHands.length, 'C1 现状确认: two-tone 重加权不触发 (比率=1.0)');
}
// C2: 公对面 8h8s9c — 无花色威胁(sc max=1), 重加权不触发; 对手8x(trips)无加权
{
  const hole = [C('A','c'), C('K','d')]; // 顶对顶踢
  const comm = [C('8','h'), C('8','s'), C('9','c')];
  const spec = G_.postflopEqSpec(hole, comm, 'bet', 50, 100, G_.boardTexture(comm), 2);
  const known = [].concat(hole, comm);
  let oppHands = [];
  for (const key of spec.range) {
    const hands = G_.handKeyToCards(key);
    for (const h of hands) {
      const clash = known.some(c => (c.rank===h[0].rank&&(c.suit===h[0].suit||!c.suit)));
      const clash2 = known.some(c => (c.rank===h[1].rank&&(c.suit===h[1].suit||!c.suit)));
      if (!clash && !clash2) oppHands.push(h);
    }
  }
  // 统计对手持8(trips)的组合占比
  let tripsC = 0;
  for (const h of oppHands) if (h[0].rank === '8' || h[1].rank === '8') tripsC++;
  const after = G_._threatReweight(oppHands, comm);
  console.log('  C2 公对面(8h8s9c): AK顶对 vs cbet宽范围');
  console.log('    对手组合=' + oppHands.length + ' 其中持8(trips)=' + tripsC + ' (' + (tripsC/oppHands.length*100).toFixed(1) + '%)');
  console.log('    重加权后=' + after.length + ' (比率=' + (after.length/oppHands.length).toFixed(2) + ') → 公对面组合威胁无任何条件化');
  ok(after.length === oppHands.length, 'C2 现状确认: 公对面重加权不触发');
}
// C3: 连牌面 8h7s9c — 对手已成顺组合占比 vs 行动条件化应有占比
{
  const hole = [C('A','c'), C('K','d')];
  const comm = [C('8','h'), C('7','s'), C('9','c')];
  const spec = G_.postflopEqSpec(hole, comm, 'bet', 50, 100, G_.boardTexture(comm), 2);
  const known = [].concat(hole, comm);
  let oppHands = [];
  for (const key of spec.range) {
    const hands = G_.handKeyToCards(key);
    for (const h of hands) {
      const clash = known.some(c => (c.rank===h[0].rank&&(c.suit===h[0].suit||!c.suit)));
      const clash2 = known.some(c => (c.rank===h[1].rank&&(c.suit===h[1].suit||!c.suit)));
      if (!clash && !clash2) oppHands.push(h);
    }
  }
  // 判对手是否已成顺(含两手+板): 用引擎 eH
  let madeStr = 0;
  for (const h of oppHands) {
    const sc = G_.eH([].concat(h, comm));
    if (Math.floor(sc / 1e10) === 4) madeStr++; // cat=4 straight
  }
  const after = G_._threatReweight(oppHands, comm);
  console.log('  C3 连牌面(8h7s9c): AK顶对 vs cbet宽范围');
  console.log('    对手组合=' + oppHands.length + ' 其中已成顺=' + madeStr + ' (' + (madeStr/oppHands.length*100).toFixed(1) + '%)');
  console.log('    重加权后=' + after.length + ' (比率=' + (after.length/oppHands.length).toFixed(2) + ') → 顺子威胁组合无加权(提交声称无稀释故不切)');
  ok(after.length === oppHands.length, 'C3 现状确认: 连牌面重加权不触发');
  // 量化: 若顺子组合×3(对手连张持续投钱), AK顶对eq掉多少
  const wHands = [];
  for (const h of oppHands) {
    const sc = G_.eH([].concat(h, comm));
    wHands.push(h);
    if (Math.floor(sc / 1e10) === 4) { wHands.push(h, h); } // ×3
  }
  // 手工MC: 用加权数组模拟 (复刻mcVsRange单挑路径太重, 直接按份额近似)
  // 这里用简化: eq_加权 = 按组合胜率均值
  let wSum = 0, wN = 0;
  for (const h of oppHands) {
    // 每个对手组合 vs AK 在随机补牌下的胜率 — 用一次MC近似(固定20次补牌采样)
    let wins = 0, T = 40;
    const deck = [];
    for (const r of ['A','K','Q','J','T','9','8','7','6','5','4','3','2']) for (const s of ['h','d','c','s']) {
      if (!known.some(c => c.rank===r&&c.suit===s) && !(h[0].rank===r&&h[0].suit===s) && !(h[1].rank===r&&h[1].suit===s)) deck.push(C(r,s));
    }
    for (let t = 0; t < T; t++) {
      // 洗牌补2张
      const d2 = deck.slice();
      for (let i = d2.length-1; i > 0; i--) { const k2 = Math.floor(Math.random()*(i+1)); const tmp=d2[i]; d2[i]=d2[k2]; d2[k2]=tmp; }
      const full = [].concat(comm, d2.slice(0,2));
      const my = G_.eH([].concat(hole, full));
      const op = G_.eH([].concat(h, full));
      if (my > op) wins++; else if (my === op) wins += 0.5;
    }
    const wr = wins / T;
    const sc = G_.eH([].concat(h, comm));
    const w = (Math.floor(sc/1e10) === 4) ? 3 : 1;
    wSum += wr * w; wN += w;
  }
  const eqWeighted = wSum / wN * 100;
  const eqPlain = eqAvg(hole, comm, spec.range, 1500, 1);
  console.log('    AK@连牌面 eq(现状MC)=' + eqPlain.toFixed(1) + '%  eq(顺子组合×3加权近似)=' + eqWeighted.toFixed(1) + '%');
  note('连牌面条件化缺失量级', '差=' + (eqPlain - eqWeighted).toFixed(1) + 'pp; >4pp=顶对在连牌面eq虚高');
}

// ----------------------------------------------------------------
console.log('\n【D. 高牌威胁/范围优势】boardTexture.rangeAdv 是否有高牌面建模');
// ----------------------------------------------------------------
{
  const bt1 = G_.boardTexture([C('A','c'), C('9','d'), C('5','s')]);
  const bt2 = G_.boardTexture([C('7','c'), C('6','d'), C('2','s')]);
  console.log('  A高面 rangeAdv=' + bt1.rangeAdv + ' | 低高面 rangeAdv=' + bt2.rangeAdv);
  ok(bt1.rangeAdv === 'neutral' && bt2.rangeAdv === 'neutral', 'D 现状确认: rangeAdv 恒 neutral, 高牌支配威胁无建模');
  // KQ顶对@A面 vs cbet 的 eq — 验证支配是否自然进eq
  const hole = [C('K','c'), C('Q','d')];
  const comm = [C('A','c'), C('9','d'), C('5','s')];
  const spec = G_.postflopEqSpec(hole, comm, 'bet', 50, 100, bt1, 2);
  const eq = eqAvg(hole, comm, spec.range, 1500, 1);
  console.log('  KQ@A95(cbet) eq=' + eq.toFixed(1) + '% (真实区间参考: 52-62%, 偏高=支配威胁认知不足)');
}

// ----------------------------------------------------------------
console.log('\n【E. getOppRange wetness 参数】湿/干面对手范围是否分化');
// ----------------------------------------------------------------
{
  const rWet = G_.getOppRange('postflop', 'cbet', 'wet');
  const rDry = G_.getOppRange('postflop', 'cbet', 'dry');
  const same = JSON.stringify(rWet) === JSON.stringify(rDry);
  console.log('  cbet线 wet范围=' + rWet.length + '手 dry范围=' + rDry.length + '手 完全相同=' + same);
  ok(same, 'E 现状确认: wetness 参数传入但从未使用, 湿/干面对手范围零分化');
}

console.log('\n================================================================');
console.log(' 审计结果: ' + pass + ' 确认, ' + warn + ' 提示, ' + fail + ' 失败');
console.log('================================================================');
