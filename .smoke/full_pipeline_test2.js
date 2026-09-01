const fs = require('fs');

// ---- DOM stubs ----
global.document = {
  getElementById: () => ({ style: {}, textContent: '', innerHTML: '', appendChild:()=>{}, removeChild:()=>{} }),
  querySelector: () => null, querySelectorAll: () => [],
  createElement: () => ({ style: {}, appendChild: () => {}, setAttribute: () => {}, addEventListener:()=>{} }),
  addEventListener: () => {}, body: { appendChild: () => {} }, readyState: 'complete'
};
global.window = { addEventListener: () => {}, location: { href: '' }, setTimeout, setInterval:()=>{}, clearTimeout:()=>{}, clearInterval:()=>{} };
global.navigator = { userAgent: 'node' };
global.Worker = function() { this.postMessage=()=>{}; this.terminate=()=>{}; this.addEventListener=()=>{}; };
global.location = { href: '' };
global.XMLHttpRequest = function() { this.open=()=>{}; this.send=()=>{}; this.addEventListener=()=>{}; };
global.fetch = () => Promise.resolve({ json:()=>Promise.resolve({}), text:()=>Promise.resolve('') });
global.localStorage = { getItem:()=>null, setItem:()=>{}, removeItem:()=>{} };
global.performance = { now: () => Date.now() };
global.DRTA = {
  tracker: { track:()=>{}, log:()=>{}, record:()=>{}, setConfidenceLevel:()=>{} },
  getProfile: () => ({ style:'balanced', aggression:0.5 }),
  getWeights: () => ({}),
  get: () => null
};

// ---- Load engine into THIS scope ----
const blk1 = fs.readFileSync('blk1.js', 'utf-8');
const blk2 = fs.readFileSync('blk2.js', 'utf-8');

// Use a function to eval and return the functions we need
const engine = new Function(blk1 + '\n' + blk2 + '\nreturn {decidePreflop,decidePostflop,postF,mcVsRange,riverExactEquity,multiPlayerEquity,postflopIP,handClassify,boardTexture,getOppRange,calcSPR,eH,handKeyToCards,getBlockerScore};')();

// Assign to this scope
const { decidePreflop, decidePostflop, postF, mcVsRange, riverExactEquity, multiPlayerEquity, postflopIP, handClassify, boardTexture, getOppRange, calcSPR, eH, handKeyToCards, getBlockerScore } = engine;

let pass=0, fail=0, warn=0;
function ok(cond, label) { if(cond){pass++;}else{fail++;console.log(`  ❌ ${label}`);} }

// ---- Helpers ----
function mc(r,s){return{rank:r,suit:s};}
function pc(str){return str.match(/(..)/g).map(s=>mc(s[0],s[1]));}

console.log('========================================');
console.log('  APK rev15 全链路验证');
console.log('========================================\n');

// ======== S1: 函数可用性 ========
console.log('── S1: 核心函数可用性 ──');
const fns = {decidePreflop,decidePostflop,postF,mcVsRange,riverExactEquity,multiPlayerEquity,postflopIP,handClassify,boardTexture,getOppRange,calcSPR,eH,handKeyToCards,getBlockerScore};
for (const [n,f] of Object.entries(fns)) {
  ok(typeof f === 'function', `${n} 不可用`);
  console.log(`  ${typeof f==='function'?'✅':'❌'} ${n}`);
}

// ======== S2: Preflop ========
console.log('\n── S2: 翻前决策 ──');
function mkPF(hole,pos,act,stk,bet,raise,raiserPos){
  global.G = {
    hole: pc(hole),
    comm:[null,null,null,null,null],
    pot:1.5+(bet||0), bet:bet||0, raise:raise||0,
    stk:stk||100, pos, act:act||2, players:act||2,
    scene:bet?'raise':'default',
    _lastPlayers:[], _raiserRole:raiserPos||'', _raiserPos:raiserPos||'',
    is_bomb_pot:false
  };
  for(let i=0;i<Math.max(act||2,2);i++)
    global.G._lastPlayers.push({active:true,folded:false,chips:stk||100});
  return global.G;
}

function testPF(label, hole, pos, act, stk, bet, raise, raiserPos) {
  mkPF(hole,pos,act,stk,bet,raise,raiserPos);
  try {
    var r = decidePreflop(global.G);
    var ok_r = r && r.a;
    console.log(`  ${label}: action=${r?.a} reason="${(r?.r||'').substring(0,50)}" eq=${r?.eq||'-'} conf=${r?.c||'-'}`);
    ok(ok_r, `${label} 无决策`);
    return r;
  } catch(e) { console.log(`  ${label}: ERROR ${e.message}`); fail++; return null; }
}

testPF('AA@BTN HU', 'AsAh', 'btn', 2, 100);
testPF('72o@UTG', '7s2h', 'utg', 2, 100);
testPF('AKs@CO', 'AsKs', 'co', 2, 100);
testPF('JJ@HJ 4way', 'JdJh', 'hj', 4, 80);
testPF('AA@BB vs raise', 'AsAh', 'bb', 2, 100, 3, 9, 'co');
testPF('ATs@MP', 'AhTs', 'mp', 2, 100);
testPF('QJs@SB', 'QdJs', 'sb', 2, 100);

// ======== S3: Postflop ========
console.log('\n── S3: 翻后决策 ──');
function mkPost(hole,comm,pos,act,pot,bet,stk,scene){
  global.G = {
    hole: pc(hole),
    comm: comm.map(c=>c?mc(c[0],c[1]):null),
    pot:pot||10, bet:bet||0, stk:stk||100,
    pos, act:act||2, players:act||2,
    scene:scene||'check',
    _lastPlayers:[], _raiserRole:'btn', _raiserPos:'btn',
    is_bomb_pot:false, _faced3bet:false, _heroDid4bet:false
  };
  for(let i=0;i<Math.max(act||2,2);i++)
    global.G._lastPlayers.push({active:true,folded:false,chips:stk||100});
  return global.G;
}

function testPost(label,hole,comm,pos,act,pot,bet,stk,scene){
  mkPost(hole,comm,pos,act,pot,bet,stk,scene);
  try {
    var r = decidePostflop(global.G);
    console.log(`  ${label}: action=${r?.a} eq=${r?.eq?.toFixed?.(1)||'-'} scene=${r?.scene||'-'} spr=${r?.spr?.toFixed?.(1)||'-'} reason="${(r?.r||'').substring(0,50)}"`);
    ok(r && r.a, `${label} 无决策`);
    ok(r && r.eq !== undefined, `${label} 无eq`);
    if(r && r._evExplain) console.log(`    _evExplain: ${JSON.stringify(r._evExplain).substring(0,120)}`);
    return r;
  } catch(e) { console.log(`  ${label}: ERROR ${e.message}`); fail++; return null; }
}

// Flop scenarios
testPost('AA@732r BTN IP', 'AsAh', [['7','s'],['3','h'],['2','c']], 'btn', 2, 10, 0, 100);
testPost('AK@KQJwet OOP', 'AhTs', [['K','s'],['Q','h'],['J','c']], 'co', 2, 12, 8, 80);
testPost('QQ@852r SB vs cbet', 'QdQh', [['8','s'],['5','h'],['2','c']], 'sb', 2, 10, 7, 100);
testPost('AK flush draw', 'AhKh', [['J','h'],['9','h'],['2','c']], 'co', 2, 12, 8, 80);
testPost('TPTK dry board', 'AsKs', [['K','h'],['7','d'],['2','c']], 'btn', 2, 10, 0, 100);
testPost('Set on paired', 'AdJd', [['J','s'],['J','h'],['2','c']], 'btn', 2, 10, 0, 100);
testPost('Air vs cbet', '8s4h', [['A','h'],['K','d'],['Q','c']], 'bb', 2, 10, 7, 100);

// Turn scenarios
testPost('AK turn J927', 'AsKs', [['J','s'],['9','h'],['2','c'],['7','d']], 'btn', 2, 20, 12, 80);
testPost('Flush draw turn', 'AhKh', [['J','h'],['9','h'],['2','c'],['4','d']], 'co', 2, 16, 10, 80);
testPost('Set turn', 'AdJd', [['J','s'],['7','h'],['2','c'],['5','d']], 'btn', 2, 20, 0, 80);

// River scenarios
testPost('AK river J9273', 'AsKs', [['J','s'],['9','h'],['2','c'],['7','d'],['3','s']], 'btn', 2, 30, 20, 60);
testPost('Made flush river', 'AhKh', [['J','h'],['9','h'],['2','c'],['4','h'],['3','s']], 'btn', 2, 40, 25, 50);
testPost('Missed draw river', 'AsKh', [['J','h'],['9','h'],['2','c'],['4','d'],['3','s']], 'co', 2, 30, 20, 60);

// 3way
testPost('AKd 3way J83', 'AdKd', [['J','d'],['8','h'],['3','c']], 'btn', 3, 15, 0, 80);
testPost('TT 3way A72', 'TsTh', [['A','h'],['7','c'],['2','d']], 'btn', 3, 15, 0, 80);
testPost('QQ 3way 963', 'QdQh', [['9','s'],['6','h'],['3','c']], 'co', 3, 15, 10, 80);

// ======== S4: postF ip 验证 ========
console.log('\n── S4: postF ip 统一验证 ──');
function testPostF(label,hole,comm,pos,expectIP){
  mkPost(hole,comm,pos,2,10,0,100);
  try {
    var r = postF(label.split(' ')[0]); // hand class not critical
    if(r && r.ip !== undefined) {
      ok(r.ip === expectIP, `${label}: ip应为${expectIP} 实际${r.ip}`);
      console.log(`  ${label}: ip=${r.ip} ${r.ip===expectIP?'✅':'❌'}`);
    } else {
      console.log(`  ${label}: ip=${r?.ip} (${r?'has result':'no result'})`);
    }
  } catch(e) { console.log(`  ${label}: ERROR ${e.message}`); }
}
testPostF('BTN ip', 'AsAh', [['7','s'],['3','h'],['2','c']], 'btn', true);
testPostF('CO ip', 'AsAh', [['7','s'],['3','h'],['2','c']], 'co', true);
testPostF('HJ ip', 'AsAh', [['7','s'],['3','h'],['2','c']], 'hj', true);
testPostF('MP oop', 'AsAh', [['7','s'],['3','h'],['2','c']], 'mp', false);
testPostF('UTG oop', 'AsAh', [['7','s'],['3','h'],['2','c']], 'utg', false);
testPostF('BB oop', 'AsAh', [['7','s'],['3','h'],['2','c']], 'bb', false);
testPostF('SB oop', 'AsAh', [['7','s'],['3','h'],['2','c']], 'sb', false);

// ======== S5: mcVsRange 多人递减 ========
console.log('\n── S5: mcVsRange 多人递减 ──');
var fH=pc('AsAh'), fC=[mc('7','s'),mc('3','h'),mc('2','c')];
var fR = getOppRange('postflop','cbet','dry');
var eqs=[];
for(let n=1;n<=5;n++){
  let t=0;
  for(let r=0;r<3;r++){
    t+=mcVsRange(fH,fC,fR,3000,n-1>0?n-1:1).eq;
  }
  eqs.push((t/3).toFixed(1));
}
console.log(`  HU=${eqs[0]}% 3way=${eqs[1]}% 4way=${eqs[2]}% 5way=${eqs[3]}% 6way=${eqs[4]}%`);
ok(parseFloat(eqs[1])<parseFloat(eqs[0])+2, '3way < HU');
ok(parseFloat(eqs[4])<parseFloat(eqs[1])+2, '6way < 3way');

// ======== S6: riverExact vs MC 一致性 ========
console.log('\n── S6: riverExact vs MC 一致性 ──');
var rH=pc('AsKs'), rC=[mc('J','s'),mc('9','h'),mc('2','c'),mc('7','d'),mc('3','s')];
var rR = getOppRange('postflop','barrel_river','wet');
var rex1=riverExactEquity(rH,rC,rR,1), mc1=mcVsRange(rH,rC,rR,5000,1);
console.log(`  HU: exact=${rex1.eq.toFixed(1)}% mc=${mc1.eq.toFixed(1)}% diff=${Math.abs(rex1.eq-mc1.eq).toFixed(1)}%`);
ok(Math.abs(rex1.eq-mc1.eq)<12, 'HU MC vs Exact <12%');
var rex2=riverExactEquity(rH,rC,rR,2), mc2=mcVsRange(rH,rC,rR,5000,2);
console.log(`  3way: exact=${rex2.eq.toFixed(1)}% mc=${mc2.eq.toFixed(1)}% diff=${Math.abs(rex2.eq-mc2.eq).toFixed(1)}%`);
ok(Math.abs(rex2.eq-mc2.eq)<12, '3way MC vs Exact <12%');

// ======== S7: multiPlayerEquity ========
console.log('\n── S7: multiPlayerEquity ──');
for(let n=2;n<=6;n++){
  var v=multiPlayerEquity(50,n);
  console.log(`  eq=50 n=${n}: ${v} (${(v/50*100).toFixed(0)}%)`);
}
ok(multiPlayerEquity(50,3)===35, '3way factor=0.70');
ok(multiPlayerEquity(50,4)===27.5, '4way factor=0.55');

// ======== S8: handClassify ========
console.log('\n── S8: handClassify ──');
var hcs=[
  ['AsAh','7s3h2c'],['AsKs','Kh7d2c'],['AsKs','AhKd2c'],
  ['JdTs','Qh9d2c'],['9d8d','7h6d2c'],['AsAd','Jh9h2c']
];
for(const[h,c]of hcs){
  var r=handClassify(pc(h),pc(c));
  console.log(`  ${h}@${c}: ${r?.name||'null'}`);
  ok(r!==null, `${h}@${c} classify null`);
}

// ======== S9: boardTexture ========
console.log('\n── S9: boardTexture ──');
var bts=[
  ['Js9h2c'],['KsQsJs'],['7h7d2c'],['Th9h8h'],['AsKhQd']
];
for(const[c]of bts){
  var r=boardTexture(pc(c));
  console.log(`  ${c}: wet=${r?.wetness} paired=${r?.paired||false}`);
  ok(r!==null, `${c} texture null`);
}

// ======== S10: calcSPR ========
console.log('\n── S10: calcSPR ──');
mkPost('AsAh',[['7','s'],['3','h'],['2','c']],'btn',2,10,0,100);
var s1=calcSPR();
mkPost('AsAh',[['7','s'],['3','h'],['2','c']],'btn',2,50,0,20);
var s2=calcSPR();
console.log(`  SPR(pot=10,stk=100)=${s1}, SPR(pot=50,stk=20)=${s2}`);
ok(typeof s1==='number'&&s1>0, 'SPR1 > 0');
ok(s2<s1, 'SPR2 < SPR1');

// ======== SUMMARY ========
console.log('\n========================================');
console.log(`  ✅ ${pass} 通过 | ❌ ${fail} 失败 | ⚠️ ${warn} 警告`);
console.log('========================================');
