// ============================================================
// FULL PIPELINE TEST: APK rev15 策略引擎 → Tab 输出全链路验证
// ============================================================
const fs = require('fs');

// ---- DOM stubs ----
global.document = {
  getElementById: () => ({ style: {}, textContent: '', innerHTML: '', appendChild:()=>{}, removeChild:()=>{} }),
  querySelector: () => null,
  querySelectorAll: () => [],
  createElement: () => ({ style: {}, appendChild: () => {}, setAttribute: () => {}, addEventListener:()=>{} }),
  addEventListener: () => {},
  body: { appendChild: () => {} },
  readyState: 'complete'
};
global.window = { addEventListener: () => {}, location: { href: '' }, setTimeout: setTimeout, setInterval: () => {}, clearTimeout: () => {}, clearInterval: () => {}, fetch:()=>Promise.resolve({json:()=>Promise.resolve({})}) };
global.navigator = { userAgent: 'node' };
global.Worker = function() { this.postMessage = () => {}; this.terminate = () => {}; this.addEventListener = () => {}; };
global.location = { href: '' };
global.XMLHttpRequest = function() { this.open = () => {}; this.send = () => {}; this.addEventListener = () => {}; };
global.fetch = () => Promise.resolve({ json: () => Promise.resolve({}), text: () => Promise.resolve('') });
global.localStorage = { getItem: () => null, setItem: () => {}, removeItem: () => {} };
global.performance = { now: () => Date.now() };
global.DRTA = {
  tracker: { track: () => {}, log: () => {}, record: () => {}, setConfidenceLevel: () => {} },
  getProfile: () => ({ style: 'balanced', aggression: 0.5 }),
  getWeights: () => ({}),
  get: () => null
};

// ---- Load engine ----
const blk1 = fs.readFileSync('blk1.js', 'utf-8');
const blk2 = fs.readFileSync('blk2.js', 'utf-8');
try { eval(blk1); } catch(e) { /* API_BASE expected */ }
try { eval(blk2); } catch(e) { console.log('blk2 err:', e.message); }

// ---- Helpers ----
function mc(rank, suit) { return { rank, suit }; }
function parseCards(str) {
  return str.match(/(..)/g).map(s => mc(s[0], s[1]));
}

// Simulate a full game state
let scenarioLog = [];
let passCount = 0, failCount = 0, warnCount = 0;

function check(condition, label, detail) {
  if (condition) { passCount++; }
  else { failCount++; console.log(`  ❌ FAIL: ${label} — ${detail||''}`); }
}
function warn(label, detail) {
  warnCount++;
  console.log(`  ⚠️ WARN: ${label} — ${detail||''}`);
}

// ---- Test Suite ----
console.log('========================================');
console.log('  全链路验证: APK rev15 策略引擎');
console.log('========================================\n');

// ======== SECTION 1: 基础函数可用性 ========
console.log('── SECTION 1: 核心函数可用性 ──');
const coreFunctions = [
  'decidePreflop', 'decidePostflop', 'postF',
  'mcVsRange', 'riverExactEquity', 'multiPlayerEquity',
  'postflopIP', 'handClassify', 'boardTexture',
  'getOppRange', 'calcSPR', 'eH',
  'handKeyToCards', 'getBlockerScore'
];
for (const fn of coreFunctions) {
  const exists = typeof global[fn] === 'function';
  check(exists, `${fn} 存在`, typeof global[fn]);
  console.log(`  ${exists ? '✅' : '❌'} ${fn}: ${typeof global[fn]}`);
}

// ======== SECTION 2: 翻前决策 (Preflop) ========
console.log('\n── SECTION 2: 翻前决策 ──');

function setupPreflopState(hole, pos, act, stk, players, bet, raise) {
  global.G = {
    hole: parseCards(hole),
    comm: [null,null,null,null,null],
    pot: 1.5,
    bet: bet || 0,
    raise: raise || 0,
    stk: stk || 100,
    pos: pos,
    act: act,
    players: players || 2,
    scene: 'default',
    _lastPlayers: [],
    _raiserRole: '',
    _raiserPos: '',
    is_bomb_pot: false
  };
  // Build _lastPlayers
  var positions = ['sb','bb','utg','utg1','mp','mp1','hj','co','btn'];
  var actCount = act || 2;
  for (var i = 0; i < Math.max(actCount, 2); i++) {
    var p = positions[positions.length - 1 - i] || ('p' + i);
    global.G._lastPlayers.push({ active: true, folded: false, chips: stk || 100 });
  }
}

// Test 2a: AA from BTN (should open/raise)
setupPreflopState('AsAh', 'btn', 2, 100);
var r1 = decidePreflop(global.G);
console.log('  AA@BTN:', JSON.stringify(r1).substring(0, 200));
check(r1 && r1.a, 'AA@BTN 有决策', r1 ? r1.a : 'null');
check(r1 && (r1.a === 'raise' || r1.a === 'call'), 'AA@BTN 应该是raise/call', r1 ? r1.a : 'null');

// Test 2b: 72o from UTG (should fold)
setupPreflopState('7s2h', 'utg', 2, 100);
var r2 = decidePreflop(global.G);
console.log('  72o@UTG:', JSON.stringify(r2).substring(0, 200));
check(r2 && r2.a, '72o@UTG 有决策', r2 ? r2.a : 'null');

// Test 2c: AKs from CO (should raise)
setupPreflopState('AsKs', 'co', 2, 100);
var r3 = decidePreflop(global.G);
console.log('  AKs@CO:', JSON.stringify(r3).substring(0, 200));
check(r3 && r3.a, 'AKs@CO 有决策', r3 ? r3.a : 'null');

// Test 2d: 面对raise，AA应该3bet
setupPreflopState('AsAh', 'bb', 2, 100, 2, 3, 9);
global.G._raiserRole = 'co';
global.G._raiserPos = 'co';
var r4 = decidePreflop(global.G);
console.log('  AA@BB vs raise:', JSON.stringify(r4).substring(0, 200));
check(r4 && r4.a, 'AA vs raise 有决策', r4 ? r4.a : 'null');

// Test 2e: 多人池 preflop
setupPreflopState('JdJh', 'hj', 4, 80);
var r5 = decidePreflop(global.G);
console.log('  JJ@HJ 4way:', JSON.stringify(r5).substring(0, 200));
check(r5 && r5.a, 'JJ 4way 有决策', r5 ? r5.a : 'null');

// ======== SECTION 3: 翻后决策 (Postflop) ========
console.log('\n── SECTION 3: 翻后决策 ──');

function setupPostflopState(hole, comm, pos, act, pot, bet, stk, scene) {
  global.G = {
    hole: parseCards(hole),
    comm: comm.map(c => c ? mc(c[0], c[1]) : null),
    pot: pot || 10,
    bet: bet || 0,
    stk: stk || 100,
    pos: pos,
    act: act || 2,
    players: act || 2,
    scene: scene || 'check',
    _lastPlayers: [],
    _raiserRole: 'btn',
    _raiserPos: 'btn',
    is_bomb_pot: false,
    _faced3bet: false,
    _heroDid4bet: false
  };
  for (var i = 0; i < Math.max(act, 2); i++) {
    global.G._lastPlayers.push({ active: true, folded: false, chips: stk || 100 });
  }
}

// Test 3a: Flop c-bet with overpair (AA on 732 rainbow)
setupPostflopState('AsAh', [['7','s'],['3','h'],['2','c']], 'btn', 2, 10, 0, 100, 'check');
var r6 = decidePostflop(global.G);
console.log('  AA@J♠9♥2♣ BTN IP:', JSON.stringify(r6).substring(0, 300));
check(r6 && r6.a, 'AA flooop 有决策', r6 ? r6.a : 'null');
check(r6 && r6.eq !== undefined, 'AA flop 有eq', r6 ? r6.eq : 'undefined');
if (r6 && r6.eq !== undefined) {
  console.log(`    eq=${r6.eq.toFixed(1)}% action=${r6.a} scene=${r6.scene||'?'}`);
}

// Test 3b: Flop with marginal hand (AT on KQJ board - scared)
setupPostflopState('AhTs', [['K','s'],['Q','h'],['J','c']], 'co', 2, 12, 8, 80, 'check');
var r7 = decidePostflop(global.G);
console.log('  AT@KQJ wet board:', JSON.stringify(r7).substring(0, 300));
check(r7 && r7.a, 'AT@KQJ 有决策', r7 ? r7.a : 'null');
if (r7 && r7.eq !== undefined) {
  console.log(`    eq=${r7.eq.toFixed(1)}% action=${r7.a}`);
}

// Test 3c: Turn decision (flop+turn = 4 comm cards)
setupPostflopState('AsKs', [['J','s'],['9','h'],['2','c'],['7','d']], 'btn', 2, 20, 12, 80, 'check');
var r8 = decidePostflop(global.G);
console.log('  AKs turn J927:', JSON.stringify(r8).substring(0, 300));
check(r8 && r8.a, 'AKs turn 有决策', r8 ? r8.a : 'null');
if (r8 && r8.eq !== undefined) {
  console.log(`    eq=${r8.eq.toFixed(1)}% action=${r8.a}`);
}

// Test 3d: River decision (5 comm cards)
setupPostflopState('AsKs', [['J','s'],['9','h'],['2','c'],['7','d'],['3','s']], 'btn', 2, 30, 20, 60, 'check');
var r9 = decidePostflop(global.G);
console.log('  AKs river J9273:', JSON.stringify(r9).substring(0, 300));
check(r9 && r9.a, 'AKs river 有决策', r9 ? r9.a : 'null');
if (r9 && r9.eq !== undefined) {
  console.log(`    eq=${r9.eq.toFixed(1)}% action=${r9.a}`);
}

// Test 3e: OOP position (SB facing c-bet)
setupPostflopState('QdQh', [['8','s'],['5','h'],['2','c']], 'sb', 2, 10, 7, 100, 'check');
var r10 = decidePostflop(global.G);
console.log('  QQ@SB OOP vs cbet:', JSON.stringify(r10).substring(0, 300));
check(r10 && r10.a, 'QQ OOP vs cbet 有决策', r10 ? r10.a : 'null');
if (r10 && r10.eq !== undefined) {
  console.log(`    eq=${r10.eq.toFixed(1)}% action=${r10.a}`);
}

// Test 3f: 3way postflop
setupPostflopState('AdKd', [['J','d'],['8','h'],['3','c']], 'btn', 3, 15, 0, 80, 'check');
var r11 = decidePostflop(global.G);
console.log('  AKd 3way J83 dry:', JSON.stringify(r11).substring(0, 300));
check(r11 && r11.a, 'AKd 3way 有决策', r11 ? r11.a : 'null');
if (r11 && r11.eq !== undefined) {
  console.log(`    eq=${r11.eq.toFixed(1)}% action=${r11.a}`);
}

// Test 3g: Draw hand (flush draw)
setupPostflopState('AhKh', [['J','h'],['9','h'],['2','c']], 'co', 2, 12, 8, 80, 'check');
var r12 = decidePostflop(global.G);
console.log('  AK flush draw Jh9h2c:', JSON.stringify(r12).substring(0, 300));
check(r12 && r12.a, 'Flush draw 有决策', r12 ? r12.a : 'null');
if (r12 && r12.eq !== undefined) {
  console.log(`    eq=${r12.eq.toFixed(1)}% action=${r12.a}`);
}

// ======== SECTION 4: 旧引擎 (postF) fallback ========
console.log('\n── SECTION 4: postF 旧引擎 fallback ──');

function setupPostFState(hole, comm, pos, act, pot, bet, stk, scene) {
  global.G = {
    hole: parseCards(hole),
    comm: comm.map(c => c ? mc(c[0], c[1]) : null),
    pot: pot || 10,
    bet: bet || 0,
    stk: stk || 100,
    pos: pos,
    act: act || 2,
    players: act || 2,
    scene: scene || 'check',
    _lastPlayers: [],
    _raiserRole: 'btn',
    _raiserPos: 'btn',
    is_bomb_pot: false
  };
  for (var i = 0; i < Math.max(act, 2); i++) {
    global.G._lastPlayers.push({ active: true, folded: false, chips: stk || 100 });
  }
}

setupPostFState('AsAh', [['7','s'],['3','h'],['2','c']], 'btn', 2, 10, 0, 100, 'check');
var r13 = postF('AA');
console.log('  postF AA@732:', JSON.stringify(r13).substring(0, 300));
check(r13 !== null && r13 !== undefined, 'postF 返回非null', r13);
if (r13 && r13.ip !== undefined) {
  console.log(`    ip=${r13.ip} (should be true for BTN)`);
  check(r13.ip === true, 'postF BTN ip=true', `got ${r13.ip}`);
}

setupPostFState('AsAh', [['7','s'],['3','h'],['2','c']], 'sb', 2, 10, 0, 100, 'check');
var r14 = postF('AA');
console.log('  postF AA@SB:', JSON.stringify(r14).substring(0, 300));
if (r14 && r14.ip !== undefined) {
  console.log(`    ip=${r14.ip} (should be false for SB)`);
  check(r14.ip === false, 'postF SB ip=false', `got ${r14.ip}`);
}

// ======== SECTION 5: 输出结构完整性 ========
console.log('\n── SECTION 5: 输出结构完整性 ──');

// Check decidePostflop output has required fields
setupPostflopState('AsAh', [['J','s'],['9','h'],['2','c']], 'btn', 2, 10, 0, 100, 'check');
var r15 = decidePostflop(global.G);
if (r15) {
  var fields = ['a', 'r', 'eq'];
  for (const f of fields) {
    check(r15[f] !== undefined, `decidePostflop 输出含 ${f}`, `${f}=${r15[f]}`);
  }
  console.log(`  完整输出: action=${r15.a}, reason="${(r15.r||'').substring(0,60)}", eq=${r15.eq ? r15.eq.toFixed(1) : '?'}`);
  if (r15.scene) console.log(`  scene=${r15.scene}`);
  if (r15.c) console.log(`  confidence=${r15.c}`);
  if (r15.spr) console.log(`  spr=${r15.spr}`);
  if (r15._se) console.log(`  _se(StrategyEngine)=${r15._se}`);
}

// ======== SECTION 6: MC vs Exact 一致性 ========
console.log('\n── SECTION 6: mcVsRange vs riverExactEquity 一致性 ──');

var rHole = parseCards('AsKs');
var rComm = [['J','s'],['9','h'],['2','c'],['7','d'],['3','s']].map(c => mc(c[0], c[1]));
var rRange = typeof getOppRange === 'function'
  ? getOppRange('postflop','barrel_river','wet')
  : ['AA','KK','QQ','JJ','TT','AKs','AKo','AQs','KQs'];

var river = riverExactEquity(rHole, rComm, rRange, 1);
var mcResult = mcVsRange(rHole, rComm, rRange, 5000, 1);
console.log(`  River exact: ${river.eq.toFixed(1)}%`);
console.log(`  MC(5000):    ${mcResult.eq.toFixed(1)}%`);
var mcDiff = Math.abs(river.eq - mcResult.eq);
console.log(`  Diff:        ${mcDiff.toFixed(1)}%`);
check(mcDiff < 10, 'MC vs Exact diff < 10%', `diff=${mcDiff.toFixed(1)}%`);

// 3way comparison
var river3 = riverExactEquity(rHole, rComm, rRange, 2);
var mc3 = mcVsRange(rHole, rComm, rRange, 5000, 2);
console.log(`  River exact 3way: ${river3.eq.toFixed(1)}%`);
console.log(`  MC(5000) 3way:    ${mc3.eq.toFixed(1)}%`);
var mcDiff3 = Math.abs(river3.eq - mc3.eq);
console.log(`  Diff 3way:        ${mcDiff3.toFixed(1)}%`);
check(mcDiff3 < 10, 'MC vs Exact 3way diff < 10%', `diff=${mcDiff3.toFixed(1)}%`);

// ======== SECTION 7: 多人池 eq 递减验证 ========
console.log('\n── SECTION 7: 多人池 eq 递减 ──');

var fHole = parseCards('AsAh');
var fComm = [['7','s'],['3','h'],['2','c']].map(c => mc(c[0], c[1]));
var fRange = typeof getOppRange === 'function'
  ? getOppRange('postflop','cbet','dry')
  : ['AKs','AKo','AQs','AQo','KQs','KQo','JJ','TT','99','88'];

var eqs = [];
for (var n = 1; n <= 4; n++) {
  let total = 0;
  for (var r = 0; r < 3; r++) {
    var res = mcVsRange(fHole, fComm, fRange, 3000, n-1 > 0 ? n-1 : 1);
    total += res.eq;
  }
  var avg = (total/3).toFixed(1);
  eqs.push(parseFloat(avg));
  console.log(`  nOpp=${n-1} (${n}way): ${avg}%`);
}
// Check that 3way < 2way < HU (with small tolerance for MC variance)
check(eqs[2] < eqs[0] + 3, '3way < HU (within tolerance)', `HU=${eqs[0]} 3way=${eqs[2]}`);
check(eqs[3] < eqs[1] + 3, '4way < 3way (within tolerance)', `3way=${eqs[2]} 4way=${eqs[3]}`);

// ======== SECTION 8: SPR 计算 ========
console.log('\n── SECTION 8: SPR 计算 ──');
setupPostflopState('AsAh', [['7','s'],['3','h'],['2','c']], 'btn', 2, 10, 0, 100, 'check');
var spr = typeof calcSPR === 'function' ? calcSPR() : 'N/A';
console.log(`  SPR (pot=10, stk=100): ${spr}`);
check(typeof spr === 'number' && spr > 0, 'SPR 正数', `spr=${spr}`);

setupPostflopState('AsAh', [['7','s'],['3','h'],['2','c']], 'btn', 2, 50, 0, 20, 'check');
var spr2 = typeof calcSPR === 'function' ? calcSPR() : 'N/A';
console.log(`  SPR (pot=50, stk=20): ${spr2}`);
check(typeof spr2 === 'number' && spr2 > 0 && spr2 < spr, 'SPR pot大stk小 → SPR小', `spr2=${spr2}`);

// ======== SECTION 9: handClassify ========
console.log('\n── SECTION 9: handClassify 牌型识别 ──');
var hcTests = [
  ['AsAh', [['7','s'],['3','h'],['2','c']], '高对'],
  ['AsKs', [['K','h'],['7','d'],['2','c']], 'TPTK'],
  ['AsKs', [['A','h'],['K','d'],['2','c']], '两对'],
  ['JdTs', [['Q','h'],['9','d'],['2','c']], '两头顺听牌'],
  ['AsKh', [['J','h'],['9','h'],['2','c']], '高牌+后门花'],
];
for (const [h, c, desc] of hcTests) {
  var hc = handClassify(parseCards(h), c.map(x => mc(x[0],x[1])));
  var name = hc ? hc.name : 'null';
  console.log(`  ${h} on ${c.map(x=>x.join('')).join('')}: ${name} (${desc})`);
  check(hc !== null, `${desc} 识别非null`, name);
}

// ======== SECTION 10: boardTexture ========
console.log('\n── SECTION 10: boardTexture 牌面质地 ──');
var btTests = [
  [['J','s'],['9','h'],['2','c']],
  [['K','s'],['Q','s'],['J','s']],
  [['7','h'],['7','d'],['2','c']],
  [['T','h'],['9','h'],['8','h']],
  [['A','s'],['K','h'],['Q','d']],
];
for (const c of btTests) {
  var bt = boardTexture(c.map(x => mc(x[0],x[1])));
  console.log(`  ${c.map(x=>x.join('')).join('')}: wetness=${bt.wetness} paired=${bt.paired||false} texture=${bt.name||bt.desc||JSON.stringify(bt).substring(0,60)}`);
  check(bt !== null, 'boardTexture 非null', JSON.stringify(bt).substring(0,50));
}

// ======== SUMMARY ========
console.log('\n========================================');
console.log(`  结果: ✅ ${passCount} 通过 | ❌ ${failCount} 失败 | ⚠️ ${warnCount} 警告`);
console.log('========================================');
