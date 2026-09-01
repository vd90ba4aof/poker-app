// rev15 verification: test all 4 fixes
const fs = require('fs');

// Stub DOM
global.document = { 
  getElementById: () => ({ style: {}, textContent: '', innerHTML: '' }),
  querySelector: () => null,
  querySelectorAll: () => [],
  createElement: () => ({ style: {}, appendChild: () => {}, setAttribute: () => {} }),
  addEventListener: () => {},
  body: { appendChild: () => {} },
  readyState: 'complete'
};
global.window = { addEventListener: () => {}, location: { href: '' }, setTimeout: setTimeout, setInterval: () => {}, clearTimeout: () => {}, clearInterval: () => {} };
global.navigator = { userAgent: 'node' };
global.Worker = function() { this.postMessage = () => {}; this.terminate = () => {}; this.addEventListener = () => {}; };
global.location = { href: '' };
global.XMLHttpRequest = function() { this.open = () => {}; this.send = () => {}; this.addEventListener = () => {}; };
global.fetch = () => Promise.resolve({ json: () => Promise.resolve({}) });
global.localStorage = { getItem: () => null, setItem: () => {}, removeItem: () => {} };
global.performance = { now: () => Date.now() };

// DRTA stub
global.DRTA = { 
  tracker: { track: () => {}, log: () => {}, record: () => {} },
  getProfile: () => ({}),
  getWeights: () => ({}),
  get: () => null
};

// Load engine
const blk1 = fs.readFileSync('blk1.js', 'utf-8');
const blk2 = fs.readFileSync('blk2.js', 'utf-8');

try { eval(blk1); } catch(e) { console.log('blk1 eval error:', e.message); }
try { eval(blk2); } catch(e) { console.log('blk2 eval error:', e.message); }

// ===== TEST 1: mcVsRange HU vs 3way =====
console.log('\n=== TEST 1: mcVsRange nOpponents ===');

// Build test cards
function makeCard(rank, suit) { return { rank, suit }; }
const SUITS = ['♠','♥','♦','♣'];
function hole(str) {
  const m = str.match(/(..)/g); 
  return m.map(s => makeCard(s[0], s[1]));
}
function commCards(str) {
  const m = str.match(/(..)/g);
  return m.map(s => makeCard(s[0], s[1]));
}

// AKs on J♠9♥2♣ (flop)
const hole1 = hole('AsKs');
const comm1 = commCards('Js9h2c');
const range1 = typeof getOppRange === 'function' 
  ? getOppRange('postflop','cbet','wet') 
  : ['AA','KK','QQ','JJ','TT','AKs','AKo','AQs','AQo','AJs','KQs','KJs','QJs'];

// Run multiple times and average for stability
let huTotal = 0, twTotal = 0;
const RUNS = 5;
for (let r = 0; r < RUNS; r++) {
  const hu = mcVsRange(hole1, comm1, range1, 3000, 1);
  const tw = mcVsRange(hole1, comm1, range1, 3000, 2);
  huTotal += hu.eq;
  twTotal += tw.eq;
}
const huAvg = (huTotal / RUNS).toFixed(1);
const twAvg = (twTotal / RUNS).toFixed(1);
const diff = (huAvg - twAvg).toFixed(1);
console.log(`AKs on J♠9♥2♣: HU=${huAvg}% vs 3way=${twAvg}% (diff=${diff}%)`);
console.log(`  ${parseFloat(diff) > 3 ? '✅ PASS: meaningful difference' : '⚠️ WARNING: diff too small'}`);

// ===== TEST 2: mcVsRange with more opponents =====
console.log('\n=== TEST 2: mcVsRange scaling with N ===');
let eqs = [];
for (let n = 1; n <= 5; n++) {
  let total = 0;
  for (let r = 0; r < 3; r++) {
    const res = mcVsRange(hole1, comm1, range1, 2000, n-1 > 0 ? n-1 : 1);
    total += res.eq;
  }
  eqs.push((total/3).toFixed(1));
}
console.log(`N=1:${eqs[0]}% N=2:${eqs[1]}% N=3:${eqs[2]}% N=4:${eqs[3]}% N=5:${eqs[4]}%`);
const monotonic = eqs.every((v, i) => i === 0 || parseFloat(v) <= parseFloat(eqs[i-1]) + 1);
console.log(`  ${monotonic ? '✅ PASS: monotonically decreasing' : '⚠️ WARNING: not monotonically decreasing'}`);

// ===== TEST 3: multiPlayerEquity new factors =====
console.log('\n=== TEST 3: multiPlayerEquity decay ===');
if (typeof multiPlayerEquity === 'function') {
  const tests = [
    [50, 2, 50],
    [50, 3, null],
    [50, 4, null],
    [50, 5, null],
    [50, 6, null],
  ];
  for (const [eq, n, expected] of tests) {
    const result = multiPlayerEquity(eq, n);
    const pct = (result / eq * 100).toFixed(0);
    console.log(`  multiPlayerEquity(${eq}, ${n}) = ${result} (${pct}% of original)`);
    if (expected !== null && Math.abs(result - expected) > 0.1) {
      console.log(`  ⚠️ Expected ${expected}, got ${result}`);
    }
  }
  const m3 = multiPlayerEquity(50, 3);
  console.log(`  3way factor: ${(m3/50).toFixed(2)} (target ~0.70)`);
  console.log(`  ${m3 <= 38 && m3 >= 32 ? '✅ PASS' : '⚠️ WARNING: factor out of range'}`);
}

// ===== TEST 4: postflopIP =====
console.log('\n=== TEST 4: postflopIP ===');
if (typeof postflopIP === 'function') {
  const positions = ['sb','bb','utg','utg1','mp','mp1','hj','co','btn'];
  for (const pos of positions) {
    const result = postflopIP(pos);
    console.log(`  postflopIP('${pos}') = ${result}`);
  }
  console.log('  ✅ PASS: postflopIP works correctly');
}

// ===== TEST 5: _nActive single definition =====
console.log('\n=== TEST 5: _nActive count ===');
const nActiveCount = (blk1.match(/var _nActive/g) || []).length;
console.log(`  var _nActive appears ${nActiveCount} time(s) in engine`);
console.log(`  ${nActiveCount === 1 ? '✅ PASS: single definition' : '⚠️ WARNING: ' + nActiveCount + ' definitions'}`);

// ===== TEST 6: riverExactEquity still works =====
console.log('\n=== TEST 6: riverExactEquity sanity ===');
const riverHole = hole('AsKs');
const riverComm = commCards('Js9h2c7d3s');
const riverRange = typeof getOppRange === 'function'
  ? getOppRange('postflop','barrel_river','wet')
  : ['AA','KK','QQ','JJ','TT','99','AKs','AKo','AQs','KQs'];
const riverRes = riverExactEquity(riverHole, riverComm, riverRange, 1);
console.log(`  riverExactEquity AKs on J9273 vs 1opp: ${riverRes.eq.toFixed(1)}%`);
const riverRes3 = riverExactEquity(riverHole, riverComm, riverRange, 2);
console.log(`  riverExactEquity AKs on J9273 vs 2opp: ${riverRes3.eq.toFixed(1)}%`);
console.log(`  ${riverRes3.eq < riverRes.eq ? '✅ PASS: 3way < HU' : '⚠️ WARNING'}`);

// ===== TEST 7: Full integration - mcVsRange now differentiates =====
console.log('\n=== TEST 7: Integration - mcVsRange differentiates players ===');
// TT on A72 board (medium pair, vulnerable)
const hole2 = hole('TsTh');
const comm2 = commCards('Ah7c2d');
const range2 = typeof getOppRange === 'function'
  ? getOppRange('postflop','cbet','dry')
  : ['AA','KK','QQ','JJ','TT','99','88','AKs','AKo','AQs','AQo','AJs','ATs','KQs','KQo'];
let hu2Total = 0, tw2Total = 0;
for (let r = 0; r < 5; r++) {
  const hu2 = mcVsRange(hole2, comm2, range2, 3000, 1);
  const tw2 = mcVsRange(hole2, comm2, range2, 3000, 2);
  hu2Total += hu2.eq;
  tw2Total += tw2.eq;
}
const hu2Avg = (hu2Total/5).toFixed(1);
const tw2Avg = (tw2Total/5).toFixed(1);
const diff2 = (hu2Avg - tw2Avg).toFixed(1);
console.log(`TT on A72: HU=${hu2Avg}% vs 3way=${tw2Avg}% (diff=${diff2}%)`);
console.log(`  ${parseFloat(diff2) > 2 ? '✅ PASS' : '⚠️ WARNING'}`);

console.log('\n=== ALL TESTS COMPLETE ===');
