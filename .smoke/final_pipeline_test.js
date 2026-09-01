const fs=require('fs'),vm=require('vm');
const ctx={
  console,setTimeout,setInterval,clearTimeout,clearInterval,
  Date,Math,JSON,Array,Object,String,Number,parseInt,parseFloat,
  Promise,Error,TypeError,RangeError,RegExp,Map,Set,
  document:{
    getElementById:()=>({style:{},textContent:'',innerHTML:'',appendChild:()=>{},removeChild:()=>{},querySelector:()=>null,querySelectorAll:()=>[],addEventListener:()=>{}}),
    querySelector:()=>null,querySelectorAll:()=>[],
    createElement:()=>({style:{},appendChild:()=>{},setAttribute:()=>{},addEventListener:()=>{},querySelectorAll:()=>[],getContext:()=>({fillStyle:'',fillRect:()=>{},clearRect:()=>{},drawImage:()=>{}})}),
    addEventListener:()=>{},body:{appendChild:()=>{}},readyState:'complete',
    createTextNode:()=>({}),head:{appendChild:()=>{}},createEvent:()=>({initEvent:()=>{}})
  },
  window:{addEventListener:()=>{},location:{href:''},setTimeout,setInterval:()=>{},dispatchEvent:()=>{}},
  navigator:{userAgent:'node'},
  Worker:function(){this.postMessage=()=>{};this.terminate=()=>{};this.addEventListener=()=>{};},
  location:{href:''},
  XMLHttpRequest:function(){this.open=()=>{};this.send=()=>{};this.addEventListener=()=>{};this.setRequestHeader=()=>{};},
  fetch:()=>Promise.resolve({json:()=>Promise.resolve({}),text:()=>Promise.resolve('')}),
  localStorage:{getItem:()=>null,setItem:()=>{},removeItem:()=>{}},
  performance:{now:()=>Date.now()},
  AndroidBridge:{showAdvice:()=>{},autoDecision:()=>{},notifyCrash:()=>{}},
  G:null,
  DRTA:{
    tracker:{track:()=>{},log:()=>{},record:()=>{},setConfidenceLevel:()=>{}},
    getProfile:()=>({style:'balanced',aggression:0.5}),
    getWeights:()=>({}),get:()=>null
  }
};
// Additional stubs needed by blk2
ctx.PotConfidence={level:'high'};
ctx.TiltDetector={detectTilt:()=>({})};
ctx.CounterExploit={detect:()=>({})};
ctx.SafetyGuard={dataReliability:()=>'high'};
ctx.TablePulse={analyze:()=>({})};
ctx.FrameDiffEngine={getOppActionSummary:()=>null};

vm.createContext(ctx);
const blk1=fs.readFileSync('blk1.js','utf-8');
const blk2=fs.readFileSync('blk2.js','utf-8');
try{vm.runInContext(blk1,ctx,{timeout:5000});}catch(e){}
try{vm.runInContext(blk2,ctx,{timeout:5000});}catch(e){}

// Run all tests in context
vm.runInContext(`
let _p=0,_f=0;
function ok(c,l){if(c){_p++;}else{_f++;console.log('  ❌ '+l);}}
function mc(r,s){return{rank:r,suit:s};}
function pc(str){return str.match(/(..)/g).map(function(s){return mc(s[0],s[1]);});}

console.log('======================================================');
console.log('  APK rev15 全链路验证 (StrategyEngine + decide)');
console.log('======================================================\\n');

// ===== S1: StrategyEngine 可用性 =====
console.log('── S1: StrategyEngine ──');
ok(typeof StrategyEngine==='object','StrategyEngine 未挂载');
console.log('  '+(typeof StrategyEngine==='object'?'✅':'❌')+' StrategyEngine: '+typeof StrategyEngine);
ok(typeof StrategyEngine.decidePreflop==='function','SE.decidePreflop 不可用');
console.log('  '+(typeof StrategyEngine.decidePreflop==='function'?'✅':'❌')+' SE.decidePreflop');
ok(typeof StrategyEngine.decidePostflop==='function','SE.decidePostflop 不可用');
console.log('  '+(typeof StrategyEngine.decidePostflop==='function'?'✅':'❌')+' SE.decidePostflop');
ok(typeof decide==='function','decide() 全局不可用');
console.log('  '+(typeof decide==='function'?'✅':'❌')+' decide()');

// ===== S2: StrategyEngine 翻前 =====
console.log('\\n── S2: SE.decidePreflop ──');
function mkG(hole,pos,act,stk,bet,raise,raiserPos,scene){
  G={hole:pc(hole),comm:[null,null,null,null,null],pot:1.5+(bet||0),bet:bet||0,raise:raise||0,
    stk:stk||100,pos:pos,act:act||2,players:act||2,scene:scene||(bet?'raise':'default'),
    _lastPlayers:[],_raiserRole:raiserPos||'',_raiserPos:raiserPos||'',is_bomb_pot:false,
    tt:2,_seEnabled:true};
  for(var i=0;i<Math.max(act||2,2);i++)
    G._lastPlayers.push({active:true,folded:false,chips:stk||100});
}

function tSEPF(label,hole,pos,act,stk,bet,raise,raiserPos){
  mkG(hole,pos,act,stk,bet,raise,raiserPos);
  try{
    var r=StrategyEngine.decidePreflop(G);
    if(r){
      console.log('  '+label+': a='+r.a+' r="'+((r.r||'').substring(0,50))+'" eq='+(r.eq||'-')+' c='+(r.c||'-')+' scene='+(r.scene||'-'));
      ok(r.a,'SE.PF '+label+' 无action');
    }else{console.log('  '+label+': null (fallback needed)');}
  }catch(e){console.log('  '+label+': ERR '+e.message);_f++;}
}
tSEPF('AA@BTN','AsAh','btn',2,100);
tSEPF('72o@UTG','7s2h','utg',2,100);
tSEPF('AKs@CO','AsKs','co',2,100);
tSEPF('JJ@HJ 4way','JdJh','hj',4,80);
tSEPF('AA@BB vs raise','AsAh','bb',2,100,3,9,'co');
tSEPF('ATs@MP','AhTs','mp',2,100);
tSEPF('QJs@SB','QdJs','sb',2,100);
tSEPF('KQs@BTN','KdQs','btn',2,100);

// ===== S3: StrategyEngine 翻后 =====
console.log('\\n── S3: SE.decidePostflop ──');
function mkPostG(hole,comm,pos,act,pot,bet,stk,scene){
  var bc=comm.filter(function(c){return c;});
  G={hole:pc(hole),comm:comm.map(function(c){return c?mc(c[0],c[1]):null;}),
    pot:pot||10,bet:bet||0,stk:stk||100,pos:pos,act:act||2,players:act||2,
    scene:scene||'check',_lastPlayers:[],_raiserRole:'btn',_raiserPos:'btn',
    is_bomb_pot:false,_faced3bet:false,_heroDid4bet:false,
    tt:bc.length>0?bc.length+1:1,_seEnabled:true};
  for(var i=0;i<Math.max(act||2,2);i++)
    G._lastPlayers.push({active:true,folded:false,chips:stk||100});
}

function tSEPost(label,hole,comm,pos,act,pot,bet,stk,scene){
  mkPostG(hole,comm,pos,act,pot,bet,stk,scene);
  try{
    var r=StrategyEngine.decidePostflop(G);
    if(r){
      var eqs=r.eq!==undefined?r.eq.toFixed(1):'-';
      console.log('  '+label+': a='+r.a+' eq='+eqs+'% scene='+(r.scene||'-')+' spr='+(r.spr?r.spr.toFixed(1):'-')+' r="'+((r.r||'').substring(0,45))+'" _se='+!!r._se);
      ok(r.a,'SE.Post '+label+' 无action');
      ok(r.eq!==undefined,'SE.Post '+label+' 无eq');
    }else{console.log('  '+label+': null');}
  }catch(e){console.log('  '+label+': ERR '+e.message);_f++;}
}

// Flop
tSEPost('AA@732 BTN','AsAh',[['7','s'],['3','h'],['2','c']],'btn',2,10,0,100);
tSEPost('AK@KQJ wet','AhTs',[['K','s'],['Q','h'],['J','c']],'co',2,12,8,80);
tSEPost('QQ@852 SB','QdQh',[['8','s'],['5','h'],['2','c']],'sb',2,10,7,100);
tSEPost('AK fd Jh9h','AhKh',[['J','h'],['9','h'],['2','c']],'co',2,12,8,80);
tSEPost('TPTK dry','AsKs',[['K','h'],['7','d'],['2','c']],'btn',2,10,0,100);
tSEPost('Set JJJ','AdJd',[['J','s'],['J','h'],['2','c']],'btn',2,10,0,100);
tSEPost('Air vs cbet','8s4h',[['A','h'],['K','d'],['Q','c']],'bb',2,10,7,100);
// Turn
tSEPost('AK turn J927','AsKs',[['J','s'],['9','h'],['2','c'],['7','d']],'btn',2,20,12,80);
tSEPost('fd turn','AhKh',[['J','h'],['9','h'],['2','c'],['4','d']],'co',2,16,10,80);
tSEPost('Set turn','AdJd',[['J','s'],['7','h'],['2','c'],['5','d']],'btn',2,20,0,80);
// River
tSEPost('AK river J9273','AsKs',[['J','s'],['9','h'],['2','c'],['7','d'],['3','s']],'btn',2,30,20,60);
tSEPost('Flush made','AhKh',[['J','h'],['9','h'],['2','c'],['4','h'],['3','s']],'btn',2,40,25,50);
tSEPost('Missed draw','AsKh',[['J','h'],['9','h'],['2','c'],['4','d'],['3','s']],'co',2,30,20,60);
// 3way
tSEPost('AKd 3way','AdKd',[['J','d'],['8','h'],['3','c']],'btn',3,15,0,80);
tSEPost('TT 3way','TsTh',[['A','h'],['7','c'],['2','d']],'btn',3,15,0,80);
tSEPost('QQ 3way face','QdQh',[['9','s'],['6','h'],['3','c']],'co',3,15,10,80);

// ===== S4: decide() 全链路 =====
console.log('\\n── S4: decide() 全链路 ──');
function tDecide(label,hole,comm,pos,act,pot,bet,stk,scene){
  if(comm){
    mkPostG(hole,comm,pos,act,pot,bet,stk,scene);
  }else{
    mkG(hole,pos,act,stk,bet,0,'',scene);
  }
  try{
    var r=decide();
    if(r){
      var eqs=r.eq!==undefined?(typeof r.eq==='number'?r.eq.toFixed(1):r.eq):'-';
      var colorInfo=r._colorInfo||{};
      var hClass=r.hClass||{};
      console.log('  '+label+':');
      console.log('    action='+r.a+' eq='+eqs+'% c='+r.c);
      console.log('    reason="'+(r.r||'').substring(0,60)+'"');
      console.log('    scene='+(r.scene||'-')+' spr='+(r.spr?r.spr.toFixed(1):'-'));
      console.log('    hClass='+(hClass.name||'-')+' ('+(hClass.desc||'')+')');
      console.log('    color='+(colorInfo.colorKey||'-')+' ('+(colorInfo.color||'')+')');
      console.log('    signal='+(colorInfo.signalType||'-'));
      if(r._evExplain) console.log('    _evExplain='+JSON.stringify(r._evExplain).substring(0,120));
      ok(r.a,'decide '+label+' 无action');
      ok(r.eq!==undefined,'decide '+label+' 无eq');
      ok(r.hClass,'decide '+label+' 无hClass');
      ok(r._colorInfo,'decide '+label+' 无_colorInfo');
      ok(r.c,'decide '+label+' 无confidence');
    }else{console.log('  '+label+': null');_f++;}
  }catch(e){console.log('  '+label+': ERR '+e.message);_f++;}
}

// Preflop full pipeline
tDecide('PF AA@BTN','AsAh',null,'btn',2,100);
tDecide('PF AKs@CO','AsKs',null,'co',2,100);
tDecide('PF 72o@UTG','7s2h',null,'utg',2,100);
// Postflop full pipeline
tDecide('Post AA@732','AsAh',[['7','s'],['3','h'],['2','c']],'btn',2,10,0,100);
tDecide('Post AK fd','AhKh',[['J','h'],['9','h'],['2','c']],'co',2,12,8,80);
tDecide('Post TPTK','AsKs',[['K','h'],['7','d'],['2','c']],'btn',2,10,0,100);
tDecide('Post Set','AdJd',[['J','s'],['J','h'],['2','c']],'btn',2,10,0,100);
tDecide('Post AK river','AsKs',[['J','s'],['9','h'],['2','c'],['7','d'],['3','s']],'btn',2,30,20,60);
tDecide('Post flush made','AhKh',[['J','h'],['9','h'],['2','c'],['4','h'],['3','s']],'btn',2,40,25,50);
tDecide('Post 3way','AdKd',[['J','d'],['8','h'],['3','c']],'btn',3,15,0,80);

// ===== S5: postF ip 验证 (通过StrategyEngine fallback路径) =====
console.log('\\n── S5: postF ip 一致性 ──');
function tIP(label,pos,expect){
  mkPostG('AsAh',[['7','s'],['3','h'],['2','c']],pos,2,10,0,100);
  try{
    var r=postF('AA');
    if(r&&r.ip!==undefined){
      ok(r.ip===expect,label+': ip应为'+expect+' 实际'+r.ip);
      console.log('  '+label+': ip='+r.ip+' '+(r.ip===expect?'✅':'❌'));
    }else{console.log('  '+label+': ip='+(r?r.ip:'null'));}
  }catch(e){console.log('  '+label+': ERR '+e.message);}
}
tIP('BTN','btn',true);
tIP('CO','co',true);
tIP('HJ','hj',true);
tIP('MP','mp',false);
tIP('UTG','utg',false);
tIP('BB','bb',false);
tIP('SB','sb',false);

// ===== S6: mcVsRange + riverExact =====
console.log('\\n── S6: mcVsRange + riverExact ──');
var fH=pc('AsAh'),fC=[mc('7','s'),mc('3','h'),mc('2','c')];
var fR=getOppRange('postflop','cbet','dry');
var eqs=[];
for(var n=1;n<=5;n++){
  var t=0;
  for(var rr=0;rr<3;rr++) t+=mcVsRange(fH,fC,fR,3000,n-1>0?n-1:1).eq;
  eqs.push((t/3).toFixed(1));
}
console.log('  AA@732: HU='+eqs[0]+'% 3way='+eqs[1]+'% 4way='+eqs[2]+'% 5way='+eqs[3]+'% 6way='+eqs[4]+'%');
ok(parseFloat(eqs[1])<parseFloat(eqs[0])+3,'3way<HU');

var rH2=pc('AsKs'),rC2=[mc('J','s'),mc('9','h'),mc('2','c'),mc('7','d'),mc('3','s')];
var rR2=getOppRange('postflop','barrel_river','wet');
var re1=riverExactEquity(rH2,rC2,rR2,1),m1=mcVsRange(rH2,rC2,rR2,5000,1);
console.log('  River AKs: exact='+re1.eq.toFixed(1)+'% mc='+m1.eq.toFixed(1)+'%');
ok(Math.abs(re1.eq-m1.eq)<15,'river HU diff<15%');

// ===== S7: multiPlayerEquity =====
console.log('\\n── S7: multiPlayerEquity ──');
var mp3=multiPlayerEquity(50,3);
var mp4=multiPlayerEquity(50,4);
var mp5=multiPlayerEquity(50,5);
console.log('  3way='+mp3+'(70%) 4way='+mp4+'(55%) 5way='+mp5+'(45%)');
ok(mp3===35,'3way=35');
ok(mp4===27.5,'4way=27.5');
ok(mp5===22.5,'5way=22.5');

// ===== S8: 辅助函数 =====
console.log('\\n── S8: 辅助函数 ──');
var hc=handClassify(pc('AsAh'),pc('7s3h2c'));
console.log('  handClassify AA@732: '+hc.name);
ok(hc&&hc.name,'handClassify');
var bt=boardTexture(pc('Js9h2c'));
console.log('  boardTexture J92: wet='+bt.wetness+' paired='+(bt.paired||false));
ok(bt!==null,'boardTexture');
var spr=calcSPR();
console.log('  calcSPR: '+spr);
ok(typeof spr==='number','calcSPR');
var ip=postflopIP('btn');
console.log('  postflopIP(btn): '+ip);
ok(ip===true,'postflopIP btn=true');
var ip2=postflopIP('sb');
console.log('  postflopIP(sb): '+ip2);
ok(ip2===false,'postflopIP sb=false');

// ===== SUMMARY =====
console.log('\\n======================================================');
console.log('  ✅ '+_p+' 通过 | ❌ '+_f+' 失败');
console.log('======================================================');
`, ctx, {timeout:120000});
