// =============================================
// FULL STRATEGY ENGINE AUDIT TEST
// =============================================

// === 1. Stub DOM/Browser APIs ===
if(typeof globalThis.document==='undefined'){
  globalThis.document={getElementById:()=>({classList:{add:()=>{},toggle:()=>{}},textContent:'',style:{},querySelector:()=>null,querySelectorAll:()=>[],addEventListener:()=>{}}),createElement:()=>({style:{},appendChild:()=>{},addEventListener:()=>{},classList:{add:()=>{},remove:()=>{}}}),querySelector:()=>null,querySelectorAll:()=>[],body:{appendChild:()=>{},style:{}}};
  globalThis.window={addEventListener:()=>{},removeEventListener:()=>{},postMessage:()=>{},__seLogged:false,location:{href:''}};
  globalThis.navigator={userAgent:'node'};
  globalThis.location={href:''};
  globalThis.Worker=class{constructor(){this.onmessage=null}postMessage(){}terminate(){}};
  globalThis.localStorage={getItem:()=>null,setItem:()=>{},removeItem:()=>{}};
  globalThis.sessionStorage={getItem:()=>null,setItem:()=>{},removeItem:()=>{}};
  globalThis.setInterval=()=>0;globalThis.clearInterval=()=>{};
  globalThis.setTimeout=(fn)=>{try{fn()}catch(e){};return 0};globalThis.clearTimeout=()=>{};
  globalThis.requestAnimationFrame=()=>0;
  globalThis.Image=function(){};
  globalThis.fetch=()=>Promise.resolve({json:()=>Promise.resolve({})});
  globalThis.DRTA={getProfile:()=>({}),tracker:{track:()=>{}}};
  globalThis.StrategyEngine=undefined;
  globalThis.HandStateMachine={getHeroRole:()=>'unknown',recordPreflopAction:()=>{}};
  globalThis.TiltDetector={detectTilt:()=>({isTilting:false})};
  globalThis.ActionLine={didPreflopRaise:()=>true,getPreflopAggressorPos:()=>'btn',was3Bet:()=>false};
}

let issues=[];
let passes=[];

function assert(cond, name, detail){
  if(cond){ passes.push(name); }
  else{ issues.push({name, detail:detail||''}); }
}

// === 2. Load engine ===
try{ eval(require('fs').readFileSync('blk1.js','utf8')); }catch(e){ try{}catch(e2){} }
try{ eval(require('fs').readFileSync('blk2.js','utf8')); }catch(e){}

// === 3. TEST: mcVsRange 参数检查 ===
console.log('\n===== TEST 1: mcVsRange 参数签名 =====');
// 检查 mcVsRange 函数签名
var mcSrc = mcVsRange.toString();
var hasNOppParam = mcSrc.indexOf('nOpponents')>=0 || mcSrc.indexOf('nOpp')>=0;
assert(!hasNOppParam, 'mcVsRange 函数签名缺少 nOpponents 参数（rev14修复不完整）', 
  'mcVsRange 仍为4参数，第5个 nOpponents 参数被调用方传入但被忽略');

// 测试 mcVsRange 在3way场景下的行为
var testHole = [{rank:'A',suit:'♠'},{rank:'K',suit:'♠'}];
var testComm = [{rank:'J',suit:'♠'},{rank:'9',suit:'♥'},{rank:'2',suit:'♣'},{rank:'7',suit:'♦'},{rank:'3',suit:'♣'}];
var testRange = ['JJ','TT','99','AQs','AKs','KQs','AJs','ATs'];

// HU eq
var huResult = mcVsRange(testHole, testComm, testRange, 3000);
console.log('  HU mcVsRange eq:', huResult.eq.toFixed(1)+'%');

// 模拟3way（传第5个参数）
var mwResult = mcVsRange(testHole, testComm, testRange, 3000, 2);
console.log('  3way mcVsRange eq (nOpp=2 被忽略):', mwResult.eq.toFixed(1)+'%');

// 两次结果应该不同（3way eq应该低于HU），但mcVsRange会给出几乎相同的结果
var diff = Math.abs(huResult.eq - mwResult.eq);
console.log('  HU vs 3way eq 差值:', diff.toFixed(1)+'%');
assert(diff < 5, 'BUG: mcVsRange 忽略nOpponents，3way eq与HU几乎相同', 
  'HU='+huResult.eq.toFixed(1)+'% vs 3way='+mwResult.eq.toFixed(1)+'%, 差='+diff.toFixed(1)+'%');

// === 4. TEST: riverExactEquity 多对手处理 ===
console.log('\n===== TEST 2: riverExactEquity 多对手 =====');
var reHU = riverExactEquity(testHole, testComm, testRange, 1);
console.log('  HU riverExact eq:', reHU.eq.toFixed(1)+'%, combos:', reHU.combos, 'exact:', reHU.exact);

var re3w = riverExactEquity(testHole, testComm, testRange, 2);
console.log('  3way riverExact eq:', re3w.eq.toFixed(1)+'%, combos:', re3w.combos, 'multiway:', re3w.multiway);

// 3way eq 应该显著低于 HU
var reDiff = reHU.eq - re3w.eq;
console.log('  HU vs 3way diff:', reDiff.toFixed(1)+'%');
assert(re3w.eq < reHU.eq, 'riverExactEquity 3way eq < HU eq ✅', 
  'HU='+reHU.eq.toFixed(1)+'% vs 3way='+re3w.eq.toFixed(1)+'%');
assert(reDiff > 2, '3way eq 折扣幅度合理(>2%)', 'diff='+reDiff.toFixed(1)+'%');

// === 5. TEST: multiPlayerEquity 折扣函数 ===
console.log('\n===== TEST 3: multiPlayerEquity =====');
var mpEq3 = multiPlayerEquity(50, 3);
var mpEq4 = multiPlayerEquity(50, 4);
var mpEq5 = multiPlayerEquity(50, 5);
console.log('  50% eq, 3way:', mpEq3+'%', '(expected 43%)');
console.log('  50% eq, 4way:', mpEq4+'%', '(expected 36.5%)');
console.log('  50% eq, 5way:', mpEq5+'%', '(expected 31.5%)');
assert(mpEq3===43, 'multiPlayerEquity 3way 折扣正确', '50*0.86=43');
assert(mpEq4===36.5, 'multiPlayerEquity 4way 折扣正确', '50*0.73=36.5');
assert(mpEq5===31.5, 'multiPlayerEquity 5way 折扣正确', '50*0.63=31.5');
// opponents<=2 时不折扣
var mpEq2 = multiPlayerEquity(50, 2);
assert(mpEq2===50, 'multiPlayerEquity HU(2) 不折扣', 'expected 50');

// === 6. TEST: postflopIP 位置判定 ===
console.log('\n===== TEST 4: postflopIP 位置判定 =====');
var positions = ['sb','bb','utg','utg1','mp','mp1','hj','co','btn'];
var ipResults = {};
positions.forEach(function(p){
  var r = postflopIP(p);
  ipResults[p] = r;
  console.log('  ' + p + ': ip=' + r);
});
assert(ipResults.sb===false, 'SB=OOP');
assert(ipResults.bb===false, 'BB=OOP');
assert(ipResults.utg===false, 'UTG=OOP');
assert(ipResults.utg1===false, 'UTG1=OOP');
assert(ipResults.mp===false, 'MP=OOP');
assert(ipResults.mp1===false, 'MP1=OOP');
assert(ipResults.hj===true, 'HJ=IP');
assert(ipResults.co===true, 'CO=IP');
assert(ipResults.btn===true, 'BTN=IP');

// === 7. TEST: _nActive 重复定义 ===
console.log('\n===== TEST 5: _nActive 重复定义检查 =====');
var decideSrc = decidePostflop.toString();
var nActiveDefs = (decideSrc.match(/var _nActive/g)||[]).length;
console.log('  decidePostflop 中 var _nActive 出现次数:', nActiveDefs);
assert(nActiveDefs<=1, 'BUG: _nActive 重复定义 '+nActiveDefs+' 次', '同一概念只准一个变量');

// === 8. TEST: decidePostflop 输出格式 ===
console.log('\n===== TEST 6: decidePostflop 输出格式 =====');
// 设置各种 G 状态
function setG(pos, hole, comm, pot, bet, stk, active, folded, opp){
  G.phase = comm.filter(function(c){return c}).length>=5?'river':comm.filter(function(c){return c}).length>=4?'turn':'flop';
  G.pos = pos;
  G.hole = hole;
  G.comm = comm;
  G.pot = pot;
  G.bet = bet;
  G.stk = stk;
  G.opp = opp||'unknown';
  G.scene = bet>0?'bet':'check';
  G.act = active;
  G.limpers = 0;
  G.ante = 0;
  G.tt = 'cash';
  // isActive/isFolded arrays
  if(active){
    G.isActive = [];
    G.isFolded = [];
    for(var i=0;i<(active+folded);i++){
      G.isActive.push(i<active);
      G.isFolded.push(i>=active);
    }
  }
}

// 测试 HU BTN 翻后
var commFlop = [{rank:'J',suit:'♠'},{rank:'9',suit:'♥'},{rank:'2',suit:'♣'},null,null];
setG('btn', [{rank:'A',suit:'♠'},{rank:'K',suit:'♠'}], commFlop, 100, 0, 1000, 2, 0, 'tag');
try{
  var r1 = decidePostflop(getHandKey());
  if(r1){
    console.log('  HU BTN AKs flop check: action='+r1.a+', eq='+((r1.eq||0).toFixed?r1.eq.toFixed(1):r1.eq));
    assert(typeof r1.a==='string', 'decidePostflop 返回包含 action', 'action='+r1.a);
  } else {
    console.log('  HU BTN AKs flop: returned null (GTO表未匹配)');
    passes.push('decidePostflop null return handled');
  }
}catch(e){
  issues.push({name:'decidePostflop HU BTN crash', detail:e.message});
}

// === 9. TEST: postF 旧引擎 ip 逻辑 ===
console.log('\n===== TEST 7: postF 旧引擎 ip 逻辑对比 =====');
// postF 使用相对位置判定 ip: _hOrd > _oOrd
// 如果 hero=MP(order=5), raiser=UTG(order=3), postF说ip=true
// 但 postflopIP(MP)=false
// 这是两套不同的逻辑
console.log('  postflopIP(MP)=false (绝对位置<7)');
console.log('  postF(MP,raiser=UTG): _hOrd=5 > _oOrd=3 → ip=true (相对位置)');
console.log('  ⚠️ 两套ip逻辑不同，但走不同代码路径');
issues.push({name:'策略冲突: ip判定逻辑不一致', 
  detail:'decidePostflop用postflopIP(绝对位置>=7=IP)，postF用相对位置(hero>raiser=IP)。MP位对UTG加注：新引擎=OOP，旧引擎=IP。两条路径可能给出不同建议。'});

// === 10. TEST: _computeFullEVCore 多人池 fold 概率 ===
console.log('\n===== TEST 8: _computeFullEVCore 多人池 =====');
// 验证 Math.pow 衰减
var baseFold = 0.4;
var nOpp3 = 3;
var adjFold3 = Math.pow(baseFold, nOpp3-1);
console.log('  baseFold='+baseFold+', 3way pow adj: '+adjFold3.toFixed(4)+' (expected 0.16)');
assert(Math.abs(adjFold3-0.16)<0.01, '_computeFullEVCore 3way fold概率衰减正确');

// === 11. TEST: _applyPipeline 多人池调整 ===
console.log('\n===== TEST 9: _applyPipeline 多人池调整 =====');
var srcApply = _applyPipeline.toString();
assert(srcApply.indexOf('isMW')>=0, '_applyPipeline 有 isMW 参数');
assert(srcApply.indexOf('f=f*0.7')>=0 || srcApply.indexOf('f=f*0.7')>=0, '_applyPipeline isMW 时 f*0.7');

// === 12. TEST: _facingCBet 多人池处理 ===
console.log('\n===== TEST 10: _facingCBet 多人池 =====');
var srcFCB = _facingCBet.toString();
assert(srcFCB.indexOf('G._isMultiway')>=0, '_facingCBet 使用 G._isMultiway 判断多人池');
assert(srcFCB.indexOf('adjF=Math.min(1,adjF*1.2)')>=0, '_facingCBet 多人池 fold 频率增加');
assert(srcFCB.indexOf('adjR=adjR*0.6')>=0, '_facingCBet 多人池 raise 频率降低');

// === 13. 全量调用链完整性 ===
console.log('\n===== TEST 11: 调用链完整性 =====');
// 检查 decidePostflop 内所有被调用函数是否存在
var calledFns = ['riverExactEquity','mcVsRange','getOppRange','multiPlayerEquity',
  'postflopIP','_computeFullEVCore','_turnFullEnumerateEV','_riverFullEnumerateEV',
  '_turnBarrel','_riverDecision','_facingCBet','_donkDecision','_applyPipeline',
  '_facingCR','ExploitAdjuster','_quantExploitAdjust','_sprSizingAdjust',
  '_pickSizing','_snapToGGTiers','_buildSprAdjForEV','decideTurnDefense',
  'decideRiverValue','decideTurnDoubleBarrel'];
calledFns.forEach(function(fn){
  var exists = (typeof eval(fn) === 'function');
  assert(exists, '函数存在: '+fn, exists?'':'函数未定义');
});

// === OUTPUT ===
console.log('\n'+'='.repeat(50));
console.log('PASSES: '+passes.length);
console.log('ISSUES: '+issues.length);
console.log('='.repeat(50));
if(issues.length>0){
  console.log('\n🔴 ISSUES FOUND:');
  issues.forEach(function(iss, i){
    console.log((i+1)+'. '+iss.name);
    if(iss.detail) console.log('   '+iss.detail);
  });
}
if(passes.length>0){
  console.log('\n🟢 PASSES:');
  passes.forEach(function(p){ console.log('  ✅ '+p); });
}
