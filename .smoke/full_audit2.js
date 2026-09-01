// FULL AUDIT Part 2 — continue from where part 1 crashed
// Focus on static analysis + targeted function tests

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

// Load engine
try{ eval(require('fs').readFileSync('blk1.js','utf8')); }catch(e){ console.log('blk1 load err (partial):',e.message.substring(0,80)); }
try{ eval(require('fs').readFileSync('blk2.js','utf8')); }catch(e){ console.log('blk2 load err (partial):',e.message.substring(0,80)); }

// Read source for static analysis
var html = require('fs').readFileSync('../app/src/main/assets/poker_helper.html','utf8');

// ========== STATIC ANALYSIS ==========

console.log('\n===== STATIC: mcVsRange 函数签名 =====');
var mcMatch = html.match(/function mcVsRange\(([^)]+)\)/);
console.log('  参数:', mcMatch?mcMatch[1]:'NOT FOUND');
assert(mcMatch && mcMatch[1].split(',').length===4, 'BUG-CONFIRMED: mcVsRange 只有4个参数(无nOpponents)', 
  '签名='+mcMatch[1]);

console.log('\n===== STATIC: riverExactEquity 函数签名 =====');
var reMatch = html.match(/function riverExactEquity\(([^)]+)\)/);
console.log('  参数:', reMatch?reMatch[1]:'NOT FOUND');
assert(reMatch && reMatch[1].split(',').length===4, 'riverExactEquity 有4个参数(含nOpponents) ✅');

console.log('\n===== STATIC: mcVsRange 调用点 =====');
var mcCalls = [];
var mcRe = /mcVsRange\(([^;]{10,200})\)/g;
var m;
while((m = mcRe.exec(html)) !== null){
  var lineNum = html.substring(0,m.index).split('\n').length;
  mcCalls.push({line:lineNum, args:m[1]});
}
mcCalls.forEach(function(c){
  var nargs = c.args.split(',').length;
  var has5th = nargs>=5;
  console.log('  L'+c.line+': '+nargs+'个参数'+(has5th?' (第5个='+c.args.split(',')[4].trim().substring(0,20)+')':''));
  if(has5th){
    assert(false, 'BUG: L'+c.line+' 传5参数给mcVsRange但函数只接受4个', '第5参数被忽略: '+c.args.split(',')[4].trim());
  }
});

console.log('\n===== STATIC: riverExactEquity 调用点 =====');
var reCalls = [];
var reRe = /riverExactEquity\(([^;]{10,200})\)/g;
while((m = reRe.exec(html)) !== null){
  var lineNum2 = html.substring(0,m.index).split('\n').length;
  reCalls.push({line:lineNum2, args:m[1]});
}
reCalls.forEach(function(c){
  var nargs = c.args.split(',').length;
  console.log('  L'+c.lineNum+': '+nargs+'个参数');
});

console.log('\n===== STATIC: _nActive 重复定义 =====');
// 在 decidePostflop 函数体内找 var _nActive
var dpStart = html.indexOf('function decidePostflop(k){');
var dpEnd = html.indexOf('\nfunction ', dpStart+1);
var dpBody = html.substring(dpStart, dpEnd);
var nActDefs = (dpBody.match(/var _nActive/g)||[]).length;
console.log('  decidePostflop 内 var _nActive 出现: '+nActDefs+'次');
assert(nActDefs<=1, 'BUG: _nActive 重复定义 '+nActDefs+'次', '违反"同一概念只准一个变量"铁律');

console.log('\n===== STATIC: ip 赋值点全量扫描 =====');
// 找所有 var ip= 或 ip= 赋值（排除 CSS/HTML 相关）
var ipAssignRe = /var ip\s*=|[^a-z]ip\s*=[^=]/g;
var ipPoints = [];
while((m = ipAssignRe.exec(html)) !== null){
  var lineNum3 = html.substring(0,m.index).split('\n').length;
  var ctx = html.substring(m.index, m.index+80).replace(/\n/g,' ');
  ipPoints.push({line:lineNum3, ctx:ctx});
}
console.log('  ip 赋值/使用点共 '+ipPoints.length+'处');
ipPoints.slice(0,15).forEach(function(p){
  console.log('  L'+p.line+': '+p.ctx.substring(0,70));
});

console.log('\n===== STATIC: postF vs decidePostflop ip 逻辑差异 =====');
var postFStart = html.indexOf('function postF(k){');
var postFEnd = html.indexOf('\nfunction ', postFStart+1);
var postFBody = html.substring(postFStart, postFEnd);
var postFIp = postFBody.match(/var ip\s*=\s*([^;]+)/);
console.log('  postF ip逻辑:', postFIp?postFIp[1].substring(0,80):'NOT FOUND');
console.log('  decidePostflop ip逻辑: postflopIP(G.pos) — 绝对位置>=7');
console.log('  ⚠️ postF 用相对位置(_hOrd>_oOrd)，decidePostflop 用绝对位置(>=7)');

console.log('\n===== STATIC: multiPlayerEquity 折扣应用点 =====');
var mpCalls = [];
var mpRe = /multiPlayerEquity\(([^)]+)\)/g;
while((m = mpRe.exec(html)) !== null){
  var lineNum4 = html.substring(0,m.index).split('\n').length;
  mpCalls.push({line:lineNum4, args:m[1]});
}
mpCalls.forEach(function(c){
  console.log('  L'+c.line+': multiPlayerEquity('+c.args+')');
});

console.log('\n===== STATIC: _computeFullEVCore _nOpp 使用 =====');
var cfStart = html.indexOf('function _computeFullEVCore(');
var cfEnd = html.indexOf('\nfunction ', cfStart+1);
var cfBody = html.substring(cfStart, cfEnd);
var nOppUses = (cfBody.match(/_nOpp/g)||[]).length;
console.log('  _computeFullEVCore 内 _nOpp 使用: '+nOppUses+'次');
// 检查 _nOpp 是否被正确使用
var powMatch = cfBody.match(/Math\.pow\([^)]*_nOpp[^)]*\)/g);
if(powMatch) powMatch.forEach(function(p){ console.log('  pow: '+p); });

console.log('\n===== STATIC: _applyPipeline isMW 处理 =====');
var apStart = html.indexOf('function _applyPipeline(');
var apEnd = html.indexOf('\nfunction ', apStart+1);
var apBody = html.substring(apStart, apEnd);
var isMWuses = (apBody.match(/isMW/g)||[]).length;
console.log('  _applyPipeline 内 isMW 使用: '+isMWuses+'次');
var mwAdj = apBody.match(/if\(isMW\)\{[^}]+\}/g);
if(mwAdj) mwAdj.forEach(function(a){ console.log('  isMW逻辑: '+a.substring(0,100)); });

console.log('\n===== STATIC: 翻前范围表完整性 =====');
// 检查 decidePreflop 各位置范围查表
var dpfStart = html.indexOf('function decidePreflop(k){');
var dpfEnd = html.indexOf('\nfunction ', dpfStart+1);
var dpfBody = html.substring(dpfStart, dpfEnd);
var posKeys = ['utg','utg1','mp','mp1','hj','co','sb','bb'];
var tables = ['_RFI','_3B','_F3B','_SQUEEZE'];
tables.forEach(function(t){
  var found = dpfBody.indexOf(t)>=0;
  console.log('  decidePreflop 使用 '+t+': '+(found?'✅':'❌'));
});

// ========== 动态测试 ==========
console.log('\n===== DYNAMIC: mcVsRange 多场景对比 =====');
var scenarios = [
  {name:'AKs on J92r7r3 (river)', hole:[{rank:'A',suit:'♠'},{rank:'K',suit:'♠'}], 
   comm:[{rank:'J',suit:'♠'},{rank:'9',suit:'♥'},{rank:'2',suit:'♣'},{rank:'7',suit:'♦'},{rank:'3',suit:'♣'}],
   range:['JJ','TT','99','AQs','AKs','KQs','AJs','ATs']},
  {name:'AQo on T83r5h2 (river)', hole:[{rank:'A',suit:'♠'},{rank:'Q',suit:'♥'}],
   comm:[{rank:'T',suit:'♣'},{rank:'8',suit:'♦'},{rank:'3',suit:'♥'},{rank:'5',suit:'♣'},{rank:'2',suit:'♠'}],
   range:['TT','99','88','AQs','AKs','AJs','KQs','QJs']},
  {name:'77 on K♠T♠9♠J♠2 (river 花面)', hole:[{rank:'7',suit:'♣'},{rank:'7',suit:'♦'}],
   comm:[{rank:'K',suit:'♠'},{rank:'T',suit:'♠'},{rank:'9',suit:'♠'},{rank:'J',suit:'♠'},{rank:'2',suit:'♣'}],
   range:['KQs','KJs','QTs','JTs','T9s','AQs','AJs','KQo']},
  {name:'AA on 722 (flop)', hole:[{rank:'A',suit:'♠'},{rank:'A',suit:'♥'}],
   comm:[{rank:'7',suit:'♣'},{rank:'2',suit:'♦'},{rank:'2',suit:'♠'},null,null],
   range:['KK','QQ','JJ','AKs','AQs','AJs','ATs','KQs']},
];

scenarios.forEach(function(s){
  var hu = mcVsRange(s.hole, s.comm, s.range, 5000);
  var mw = mcVsRange(s.hole, s.comm, s.range, 5000, 2);
  var reHu = riverExactEquity(s.hole, s.comm, s.range, 1);
  var reMw = riverExactEquity(s.hole, s.comm, s.range, 2);
  console.log('\n  '+s.name+':');
  console.log('    mcVsRange  HU='+hu.eq.toFixed(1)+'%  3way(nOpp忽略)='+mw.eq.toFixed(1)+'%  diff='+Math.abs(hu.eq-mw.eq).toFixed(1)+'%');
  if(s.comm.filter(function(c){return c}).length===5){
    console.log('    riverExact HU='+reHu.eq.toFixed(1)+'%  3way='+reMw.eq.toFixed(1)+'%  diff='+(reHu.eq-reMw.eq).toFixed(1)+'%');
  }
  // mcVsRange 在3way应该给出比HU低的eq，但因为忽略nOpp，两者几乎一样
  if(Math.abs(hu.eq-mw.eq)<3 && s.comm.filter(function(c){return c}).length===5){
    assert(false, 'BUG: '+s.name+' mcVsRange 3way eq≈HU eq (差'+Math.abs(hu.eq-mw.eq).toFixed(1)+'%)', 
      '应该像 riverExactEquity 那样3way显著降低(diff='+(reHu.eq-reMw.eq).toFixed(1)+'%)');
  }
});

console.log('\n===== DYNAMIC: multiPlayerEquity 叠加效果验证 =====');
// 验证 mcVsRange(高估) + multiPlayerEquity(折扣) 的最终效果
var testEq = 40; // 假设原始eq 40%
var nActive = 3;
var eqAfterDiscount = multiPlayerEquity(testEq, nActive);
console.log('  原始eq='+testEq+'%, 3way折扣后='+eqAfterDiscount+'%');
console.log('  折扣系数: '+decayFactors[nActive]);
// 问题: mcVsRange 高估的 eq 经过 multiPlayerEquity 折扣后是否合理?
// mcVsRange HU eq ≈ riverExactEquity HU eq (单对手抽样=精确)
// 但 mcVsRange 3way eq ≈ mcVsRange HU eq (因为忽略nOpp)
// multiPlayerEquity 把 HU eq * 0.86 作为 3way eq
// 而 riverExactEquity 3way eq ≈ HU eq * 0.34 (17.4/26.3 ≈ 66% 衰减)
// multiPlayerEquity 只衰减 14%，远远不足以补偿!
console.log('  ⚠️ multiPlayerEquity 3way衰减14%，但实际3way衰减约'+((1-9.0/26.3)*100).toFixed(0)+'%');
assert(false, 'BUG: multiPlayerEquity 折扣力度不足', 
  '3way 实际衰减'+((1-9.0/26.3)*100).toFixed(0)+'% 但 multiPlayerEquity 只衰减14%。边缘牌在多人池eq严重高估');

// ========== OUTPUT ==========
console.log('\n'+'='.repeat(60));
console.log('PASSES: '+passes.length);
console.log('ISSUES: '+issues.length);
console.log('='.repeat(60));
if(issues.length>0){
  console.log('\n🔴 ISSUES:');
  issues.forEach(function(iss, i){
    console.log((i+1)+'. ['+iss.name+']');
    if(iss.detail) console.log('   → '+iss.detail);
  });
}
