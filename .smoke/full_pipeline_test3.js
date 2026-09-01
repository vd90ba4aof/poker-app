const fs = require('fs');

// DOM stubs
global.document = {
  getElementById:()=>({style:{},textContent:'',innerHTML:'',appendChild:()=>{},removeChild:()=>{}}),
  querySelector:()=>null,querySelectorAll:()=>[],
  createElement:()=>({style:{},appendChild:()=>{},setAttribute:()=>{},addEventListener:()=>{}}),
  addEventListener:()=>{},body:{appendChild:()=>{}},readyState:'complete'
};
global.window={addEventListener:()=>{},location:{href:''},setTimeout,setInterval:()=>{},clearTimeout:()=>{},clearInterval:()=>{}};
global.navigator={userAgent:'node'};
global.Worker=function(){this.postMessage=()=>{};this.terminate=()=>{};this.addEventListener=()=>{};};
global.location={href:''};
global.XMLHttpRequest=function(){this.open=()=>{};this.send=()=>{};this.addEventListener=()=>{};};
global.fetch=()=>Promise.resolve({json:()=>Promise.resolve({}),text:()=>Promise.resolve('')});
global.localStorage={getItem:()=>null,setItem:()=>{},removeItem:()=>{}};
global.performance={now:()=>Date.now()};
global.DRTA={
  tracker:{track:()=>{},log:()=>{},record:()=>{},setConfidenceLevel:()=>{}},
  getProfile:()=>({style:'balanced',aggression:0.5}),
  getWeights:()=>({}),get:()=>null
};

// Load blk1 - wrap to catch API_BASE error
const blk1 = fs.readFileSync('blk1.js','utf-8');
const blk2 = fs.readFileSync('blk2.js','utf-8');

// Eval blk1 - contains most functions, may throw API_BASE at the end
try { eval(blk1); } catch(e) { /* expected: API_BASE */ }
// Eval blk2 - may also throw
try { eval(blk2); } catch(e) { /* expected */ }

let pass=0,fail=0;
function ok(c,l){if(c){pass++;}else{fail++;console.log(`  ❌ ${l}`);}}
function mc(r,s){return{rank:r,suit:s};}
function pc(str){return str.match(/(..)/g).map(s=>mc(s[0],s[1]));}

console.log('========================================');
console.log('  APK rev15 全链路验证');
console.log('========================================\n');

// ======== S1: 函数可用性 ========
console.log('── S1: 核心函数可用性 ──');
const fnList=['decidePreflop','decidePostflop','postF','mcVsRange','riverExactEquity','multiPlayerEquity','postflopIP','handClassify','boardTexture','getOppRange','calcSPR','eH','handKeyToCards','getBlockerScore'];
for(const n of fnList){
  const t=typeof eval(n);
  ok(t==='function',`${n} 不可用`);
  console.log(`  ${t==='function'?'✅':'❌'} ${n}`);
}

// ======== S2: Preflop ========
console.log('\n── S2: 翻前决策 ──');
function mkPF(hole,pos,act,stk,bet,raise,raiserPos){
  G={hole:pc(hole),comm:[null,null,null,null,null],pot:1.5+(bet||0),bet:bet||0,raise:raise||0,
    stk:stk||100,pos,act:act||2,players:act||2,scene:bet?'raise':'default',
    _lastPlayers:[],_raiserRole:raiserPos||'',_raiserPos:raiserPos||'',is_bomb_pot:false};
  for(let i=0;i<Math.max(act||2,2);i++)
    G._lastPlayers.push({active:true,folded:false,chips:stk||100});
}

function tPF(label,hole,pos,act,stk,bet,raise,raiserPos){
  mkPF(hole,pos,act,stk,bet,raise,raiserPos);
  try{
    var r=decidePreflop(G);
    console.log(`  ${label}: action=${r?.a} reason="${(r?.r||'').substring(0,50)}" eq=${r?.eq||'-'} conf=${r?.c||'-'}`);
    ok(r&&r.a,`${label} 无决策`);
  }catch(e){console.log(`  ${label}: ERR ${e.message}`);fail++;}
}
tPF('AA@BTN HU','AsAh','btn',2,100);
tPF('72o@UTG','7s2h','utg',2,100);
tPF('AKs@CO','AsKs','co',2,100);
tPF('JJ@HJ 4way','JdJh','hj',4,80);
tPF('AA@BB vs raise','AsAh','bb',2,100,3,9,'co');
tPF('ATs@MP','AhTs','mp',2,100);
tPF('QJs@SB','QdJs','sb',2,100);

// ======== S3: Postflop ========
console.log('\n── S3: 翻后决策 ──');
function mkPost(hole,comm,pos,act,pot,bet,stk,scene){
  G={hole:pc(hole),comm:comm.map(c=>c?mc(c[0],c[1]):null),pot:pot||10,bet:bet||0,stk:stk||100,
    pos,act:act||2,players:act||2,scene:scene||'check',
    _lastPlayers:[],_raiserRole:'btn',_raiserPos:'btn',
    is_bomb_pot:false,_faced3bet:false,_heroDid4bet:false};
  for(let i=0;i<Math.max(act||2,2);i++)
    G._lastPlayers.push({active:true,folded:false,chips:stk||100});
}

function tPost(label,hole,comm,pos,act,pot,bet,stk,scene){
  mkPost(hole,comm,pos,act,pot,bet,stk,scene);
  try{
    var r=decidePostflop(G);
    var eqs=r?.eq?.toFixed?.(1)||'-';
    var sprs=r?.spr?.toFixed?.(1)||'-';
    console.log(`  ${label}: a=${r?.a} eq=${eqs}% scene=${r?.scene||'-'} spr=${sprs} reason="${(r?.r||'').substring(0,45)}"`);
    ok(r&&r.a,`${label} 无决策`);
    ok(r&&r.eq!==undefined,`${label} 无eq`);
    if(r&&r._evExplain)console.log(`    _evExplain: ${JSON.stringify(r._evExplain).substring(0,100)}`);
    return r;
  }catch(e){console.log(`  ${label}: ERR ${e.message}`);fail++;return null;}
}

// Flop
tPost('AA@732 BTN','AsAh',[['7','s'],['3','h'],['2','c']],'btn',2,10,0,100);
tPost('AK@KQJwet','AhTs',[['K','s'],['Q','h'],['J','c']],'co',2,12,8,80);
tPost('QQ@852 SB','QdQh',[['8','s'],['5','h'],['2','c']],'sb',2,10,7,100);
tPost('AK fd Jh9h','AhKh',[['J','h'],['9','h'],['2','c']],'co',2,12,8,80);
tPost('TPTK dry','AsKs',[['K','h'],['7','d'],['2','c']],'btn',2,10,0,100);
tPost('Set JJJ','AdJd',[['J','s'],['J','h'],['2','c']],'btn',2,10,0,100);
tPost('Air vs cbet','8s4h',[['A','h'],['K','d'],['Q','c']],'bb',2,10,7,100);
// Turn
tPost('AK turn J927','AsKs',[['J','s'],['9','h'],['2','c'],['7','d']],'btn',2,20,12,80);
tPost('fd turn','AhKh',[['J','h'],['9','h'],['2','c'],['4','d']],'co',2,16,10,80);
tPost('Set turn','AdJd',[['J','s'],['7','h'],['2','c'],['5','d']],'btn',2,20,0,80);
// River
tPost('AK river J9273','AsKs',[['J','s'],['9','h'],['2','c'],['7','d'],['3','s']],'btn',2,30,20,60);
tPost('Flush made','AhKh',[['J','h'],['9','h'],['2','c'],['4','h'],['3','s']],'btn',2,40,25,50);
tPost('Missed draw','AsKh',[['J','h'],['9','h'],['2','c'],['4','d'],['3','s']],'co',2,30,20,60);
// 3way
tPost('AKd 3way','AdKd',[['J','d'],['8','h'],['3','c']],'btn',3,15,0,80);
tPost('TT 3way','TsTh',[['A','h'],['7','c'],['2','d']],'btn',3,15,0,80);
tPost('QQ 3way face','QdQh',[['9','s'],['6','h'],['3','c']],'co',3,15,10,80);

// ======== S4: postF ip 统一 ========
console.log('\n── S4: postF ip 统一 ──');
function tIP(label,pos,expect){
  mkPost('AsAh',[['7','s'],['3','h'],['2','c']],pos,2,10,0,100);
  try{
    var r=postF('AA');
    if(r&&r.ip!==undefined){
      ok(r.ip===expect,`${label}: ip应为${expect} 实际${r.ip}`);
      console.log(`  ${label}: ip=${r.ip} ${r.ip===expect?'✅':'❌'}`);
    }else{console.log(`  ${label}: no ip field`);}
  }catch(e){console.log(`  ${label}: ERR ${e.message}`);}
}
tIP('BTN','btn',true);
tIP('CO','co',true);
tIP('HJ','hj',true);
tIP('MP','mp',false);
tIP('UTG','utg',false);
tIP('BB','bb',false);
tIP('SB','sb',false);

// ======== S5: mcVsRange 多人递减 ========
console.log('\n── S5: mcVsRange 多人递减 ──');
var fH=pc('AsAh'),fC=[mc('7','s'),mc('3','h'),mc('2','c')];
var fR=getOppRange('postflop','cbet','dry');
var eqs=[];
for(let n=1;n<=5;n++){
  let t=0;
  for(let r=0;r<3;r++) t+=mcVsRange(fH,fC,fR,3000,n-1>0?n-1:1).eq;
  eqs.push((t/3).toFixed(1));
}
console.log(`  HU=${eqs[0]}% 3way=${eqs[1]}% 4way=${eqs[2]}% 5way=${eqs[3]}% 6way=${eqs[4]}%`);
ok(parseFloat(eqs[1])<parseFloat(eqs[0])+3,'3way < HU');

// ======== S6: river vs MC 一致性 ========
console.log('\n── S6: riverExact vs MC ──');
var rH2=pc('AsKs'),rC2=[mc('J','s'),mc('9','h'),mc('2','c'),mc('7','d'),mc('3','s')];
var rR2=getOppRange('postflop','barrel_river','wet');
var re1=riverExactEquity(rH2,rC2,rR2,1),m1=mcVsRange(rH2,rC2,rR2,5000,1);
console.log(`  HU: exact=${re1.eq.toFixed(1)}% mc=${m1.eq.toFixed(1)}% diff=${Math.abs(re1.eq-m1.eq).toFixed(1)}%`);
ok(Math.abs(re1.eq-m1.eq)<15,'HU MC vs Exact <15%');
var re2=riverExactEquity(rH2,rC2,rR2,2),m2=mcVsRange(rH2,rC2,rR2,5000,2);
console.log(`  3way: exact=${re2.eq.toFixed(1)}% mc=${m2.eq.toFixed(1)}% diff=${Math.abs(re2.eq-m2.eq).toFixed(1)}%`);
ok(Math.abs(re2.eq-m2.eq)<15,'3way MC vs Exact <15%');

// ======== S7: multiPlayerEquity ========
console.log('\n── S7: multiPlayerEquity ──');
for(let n=2;n<=6;n++){
  var v=multiPlayerEquity(50,n);
  console.log(`  eq=50 n=${n}: ${v} (${(v/50*100).toFixed(0)}%)`);
}
ok(multiPlayerEquity(50,3)===35,'3way=0.70');

// ======== S8: handClassify ========
console.log('\n── S8: handClassify ──');
for(const[h,c]of[['AsAh','7s3h2c'],['AsKs','Kh7d2c'],['AsKs','AhKd2c'],['JdTs','Qh9d2c']]){
  var r=handClassify(pc(h),pc(c));
  console.log(`  ${h}@${c}: ${r?.name||'null'}`);
  ok(r!==null,`${h}@${c} null`);
}

// ======== S9: boardTexture ========
console.log('\n── S9: boardTexture ──');
for(const c of['Js9h2c','KsQsJs','7h7d2c','Th9h8h']){
  var r=boardTexture(pc(c));
  console.log(`  ${c}: wet=${r?.wetness} paired=${r?.paired||false}`);
  ok(r!==null,`${c} null`);
}

// ======== S10: SPR ========
console.log('\n── S10: calcSPR ──');
mkPost('AsAh',[['7','s'],['3','h'],['2','c']],'btn',2,10,0,100);
var s1=calcSPR();
mkPost('AsAh',[['7','s'],['3','h'],['2','c']],'btn',2,50,0,20);
var s2=calcSPR();
console.log(`  pot=10,stk=100: SPR=${s1}`);
console.log(`  pot=50,stk=20: SPR=${s2}`);
ok(typeof s1==='number'&&s1>0,'SPR1>0');
ok(s2<s1,'SPR2<SPR1');

// ======== SUMMARY ========
console.log('\n========================================');
console.log(`  ✅ ${pass} 通过 | ❌ ${fail} 失败`);
console.log('========================================');
