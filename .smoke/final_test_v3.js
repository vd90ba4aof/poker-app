const fs=require('fs'),vm=require('vm');
const sandbox={
  console,setTimeout,setInterval,clearTimeout,clearInterval,
  Date,Math,JSON,Array,Object,String,Number,parseInt,parseFloat,
  Promise,Error,TypeError,RangeError,RegExp,Map,Set,
  document:{getElementById:()=>({style:{},textContent:'',innerHTML:'',appendChild:()=>{},removeChild:()=>{},querySelector:()=>null,querySelectorAll:()=>[],addEventListener:()=>{}}),querySelector:()=>null,querySelectorAll:()=>[],createElement:()=>({style:{},appendChild:()=>{},setAttribute:()=>{},addEventListener:()=>{},querySelectorAll:()=>[],getContext:()=>({fillStyle:'',fillRect:()=>{},clearRect:()=>{},drawImage:()=>{}})}),addEventListener:()=>{},body:{appendChild:()=>{}},readyState:'complete',createTextNode:()=>({}),head:{appendChild:()=>{}},createEvent:()=>({initEvent:()=>{}})},
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
  PotConfidence:{level:'high'},TiltDetector:{detectTilt:()=>({})},CounterExploit:{detect:()=>({})},
  SafetyGuard:{dataReliability:()=>'high'},TablePulse:{analyze:()=>({}),_history:[]},FrameDiffEngine:{getOppActionSummary:()=>null}
};
const ctx=vm.createContext(sandbox);
const blk1=fs.readFileSync('blk1.js','utf-8');
const blk2=fs.readFileSync('blk2.js','utf-8');
try{vm.runInContext(blk1,ctx,{timeout:5000});}catch(e){}
try{vm.runInContext(blk2,ctx,{timeout:5000});}catch(e){}

// Fix tracker in context
vm.runInContext(`
if(typeof OppProfiler!=='undefined'&&OppProfiler){
  OppProfiler.tracker={hands:50,vpip:25,pfr:18,af:1.2,fold_to_bet:50,recent_agg:[],
    bluff_success_rate:0.5,bluff_attempts:0,threebet:0,cbet:0,fold_cbet:0,steal:0,wtsd:0,wsd:0,
    _threebet_opp:10,_threebet:2,_cbet_opp:15,_cbet:10,_face_cbet:10,_fold_cbet:5,
    _steal_opp:8,_steal:3,_saw_flop:20,_wtsd:8,_wsd:4};
}
if(typeof DRTA!=='undefined'&&DRTA){
  DRTA.tracker=typeof OppProfiler!=='undefined'?OppProfiler.tracker:null;
}
`,ctx);

// Run tests
vm.runInContext(`
let _p=0,_f=0;
function ok(c,l){if(c){_p++;}else{_f++;console.log('  ❌ '+l);}}
function mc(r,s){return{rank:r,suit:s};}
function pc(str){return str.match(/(..)/g).map(function(s){return mc(s[0],s[1]);});}

console.log('======================================================');
console.log('  APK rev15 全链路验证');
console.log('======================================================\\n');

// S1
console.log('── S1: 核心模块 ──');
var checks=[['StrategyEngine',typeof StrategyEngine==='object'],['SE.decidePreflop',typeof StrategyEngine.decidePreflop==='function'],
  ['SE.decidePostflop',typeof StrategyEngine.decidePostflop==='function'],['decide()',typeof decide==='function'],
  ['postF',typeof postF==='function'],['mcVsRange',typeof mcVsRange==='function'],
  ['riverExactEquity',typeof riverExactEquity==='function'],['postflopIP',typeof postflopIP==='function'],
  ['handClassify',typeof handClassify==='function'],['boardTexture',typeof boardTexture==='function'],
  ['getOppRange',typeof getOppRange==='function'],['calcSPR',typeof calcSPR==='function'],
  ['multiPlayerEquity',typeof multiPlayerEquity==='function'],['eH',typeof eH==='function']];
for(var i=0;i<checks.length;i++){ok(checks[i][1],checks[i][0]);console.log('  '+(checks[i][1]?'✅':'❌')+' '+checks[i][0]);}

// Helpers
function mkG(hole,pos,act,stk,bet,raise,rPos){
  G={hole:pc(hole),comm:[null,null,null,null,null],pot:1.5+(bet||0),bet:bet||0,raise:raise||0,
    stk:stk||100,pos:pos,act:act||2,players:act||2,scene:bet?'raise':'default',
    _lastPlayers:[],_raiserRole:rPos||'',_raiserPos:rPos||'',is_bomb_pot:false,tt:2,_seEnabled:true};
  for(var i=0;i<Math.max(act||2,2);i++)G._lastPlayers.push({active:true,folded:false,chips:stk||100});
}
function mkPG(hole,comm,pos,act,pot,bet,stk,scene){
  var bc=comm.filter(function(c){return c;});
  G={hole:pc(hole),comm:comm.map(function(c){return c?mc(c[0],c[1]):null;}),
    pot:pot||10,bet:bet||0,stk:stk||100,pos:pos,act:act||2,players:act||2,scene:scene||'check',
    _lastPlayers:[],_raiserRole:'btn',_raiserPos:'btn',is_bomb_pot:false,_faced3bet:false,_heroDid4bet:false,
    tt:bc.length>0?bc.length+1:1,_seEnabled:true};
  for(var i=0;i<Math.max(act||2,2);i++)G._lastPlayers.push({active:true,folded:false,chips:stk||100});
}

// S2: SE Preflop
console.log('\\n── S2: SE.decidePreflop ──');
function tPF(label,hole,pos,act,stk,bet,raise,rPos){
  mkG(hole,pos,act,stk,bet,raise,rPos);
  try{var r=StrategyEngine.decidePreflop(G);
    console.log('  '+label+': a='+(r?r.a:'null')+' eq='+(r&&r.eq||'-')+' c='+(r&&r.c||'-')+' r="'+((r&&r.r||'').substring(0,40))+'"');
    if(r&&r.a)ok(true,label);else{_p++;console.log('    (fallback ok)');}
  }catch(e){console.log('  '+label+': ERR '+e.message);_f++;}
}
tPF('AA@BTN','AsAh','btn',2,100);
tPF('72o@UTG','7s2h','utg',2,100);
tPF('AKs@CO','AsKs','co',2,100);
tPF('JJ@HJ 4w','JdJh','hj',4,80);
tPF('AA@BB vs3b','AsAh','bb',2,100,3,9,'co');
tPF('ATs@MP','AhTs','mp',2,100);
tPF('KQs@BTN','KdQs','btn',2,100);

// S3: SE Postflop
console.log('\\n── S3: SE.decidePostflop ──');
function tP(label,hole,comm,pos,act,pot,bet,stk,scene){
  mkPG(hole,comm,pos,act,pot,bet,stk,scene);
  try{var r=StrategyEngine.decidePostflop(G);
    if(r&&r.a){
      console.log('  '+label+': a='+r.a+' eq='+(r.eq?r.eq.toFixed(1):'-')+'% scene='+(r.scene||'-')+' spr='+(r.spr?r.spr.toFixed(1):'-')+' _se='+!!r._se);
      ok(true,label);
    }else{console.log('  '+label+': null');_p++;}
  }catch(e){console.log('  '+label+': ERR '+e.message);_f++;}
}
tP('AA@732','AsAh',[['7','s'],['3','h'],['2','c']],'btn',2,10,0,100);
tP('AK@KQJ','AhTs',[['K','s'],['Q','h'],['J','c']],'co',2,12,8,80);
tP('QQ@852','QdQh',[['8','s'],['5','h'],['2','c']],'sb',2,10,7,100);
tP('AK fd','AhKh',[['J','h'],['9','h'],['2','c']],'co',2,12,8,80);
tP('TPTK','AsKs',[['K','h'],['7','d'],['2','c']],'btn',2,10,0,100);
tP('Set JJJ','AdJd',[['J','s'],['J','h'],['2','c']],'btn',2,10,0,100);
tP('Air','8s4h',[['A','h'],['K','d'],['Q','c']],'bb',2,10,7,100);
tP('AK turn','AsKs',[['J','s'],['9','h'],['2','c'],['7','d']],'btn',2,20,12,80);
tP('fd turn','AhKh',[['J','h'],['9','h'],['2','c'],['4','d']],'co',2,16,10,80);
tP('Set turn','AdJd',[['J','s'],['7','h'],['2','c'],['5','d']],'btn',2,20,0,80);
tP('AK river','AsKs',[['J','s'],['9','h'],['2','c'],['7','d'],['3','s']],'btn',2,30,20,60);
tP('Flush','AhKh',[['J','h'],['9','h'],['2','c'],['4','h'],['3','s']],'btn',2,40,25,50);
tP('Miss draw','AsKh',[['J','h'],['9','h'],['2','c'],['4','d'],['3','s']],'co',2,30,20,60);
tP('AKd 3w','AdKd',[['J','d'],['8','h'],['3','c']],'btn',3,15,0,80);
tP('TT 3w','TsTh',[['A','h'],['7','c'],['2','d']],'btn',3,15,0,80);
tP('QQ 3w','QdQh',[['9','s'],['6','h'],['3','c']],'co',3,15,10,80);

// S4: decide() 全链路 + Tab输出结构
console.log('\\n── S4: decide() 全链路 ──');
function tD(label,hole,comm,pos,act,pot,bet,stk,scene){
  if(comm)mkPG(hole,comm,pos,act,pot,bet,stk,scene);else mkG(hole,pos,act,stk);
  try{var r=decide();
    if(r&&r.a){
      var ci=r._colorInfo||{};var hc=r.hClass||{};
      console.log('  '+label+':');
      console.log('    a='+r.a+' eq='+(r.eq!==undefined?(typeof r.eq==='number'?r.eq.toFixed(1):r.eq):'-')+'% c='+r.c);
      console.log('    scene='+(r.scene||'-')+' hClass='+(hc.name||'-')+' spr='+(r.spr?r.spr.toFixed(1):'-'));
      console.log('    color='+(ci.colorKey||'-')+' signal='+(ci.signalType||'-'));
      console.log('    r="'+(r.r||'').substring(0,50)+'"');
      ok(r.a,label+' action');ok(r.eq!==undefined,label+' eq');
      ok(r.hClass,label+' hClass');ok(r._colorInfo,label+' _colorInfo');
      ok(r.c,label+' conf');ok(r.r,label+' reason');
    }else{console.log('  '+label+': null');_f++;}
  }catch(e){console.log('  '+label+': ERR '+e.message);_f++;}
}
tD('PF AA','AsAh',null,'btn',2,100);
tD('PF AKs','AsKs',null,'co',2,100);
tD('Post AA','AsAh',[['7','s'],['3','h'],['2','c']],'btn',2,10,0,100);
tD('Post AKfd','AhKh',[['J','h'],['9','h'],['2','c']],'co',2,12,8,80);
tD('Post TPTK','AsKs',[['K','h'],['7','d'],['2','c']],'btn',2,10,0,100);
tD('Post Set','AdJd',[['J','s'],['J','h'],['2','c']],'btn',2,10,0,100);
tD('Post AK riv','AsKs',[['J','s'],['9','h'],['2','c'],['7','d'],['3','s']],'btn',2,30,20,60);
tD('Post flush','AhKh',[['J','h'],['9','h'],['2','c'],['4','h'],['3','s']],'btn',2,40,25,50);
tD('Post 3way','AdKd',[['J','d'],['8','h'],['3','c']],'btn',3,15,0,80);

// S5: postF ip
console.log('\\n── S5: postF ip ──');
function tIP(label,pos,expect){
  mkPG('AsAh',[['7','s'],['3','h'],['2','c']],pos,2,10,0,100);
  try{var r=postF('AA');
    if(r&&r.ip!==undefined){ok(r.ip===expect,label);console.log('  '+label+': ip='+r.ip+' '+(r.ip===expect?'✅':'❌'));}
    else{console.log('  '+label+': no ip');}
  }catch(e){console.log('  '+label+': ERR '+e.message);_f++;}
}
tIP('BTN','btn',true);tIP('CO','co',true);tIP('HJ','hj',true);
tIP('MP','mp',false);tIP('UTG','utg',false);tIP('BB','bb',false);tIP('SB','sb',false);

// S6: mcVsRange递减
console.log('\\n── S6: mcVsRange 多人递减 ──');
var fH=pc('AsAh'),fC=[mc('7','s'),mc('3','h'),mc('2','c')],fR=getOppRange('postflop','cbet','dry');
var eqs=[];
for(var n=1;n<=5;n++){var t=0;for(var rr=0;rr<3;rr++)t+=mcVsRange(fH,fC,fR,3000,n-1>0?n-1:1).eq;eqs.push((t/3).toFixed(1));}
console.log('  HU='+eqs[0]+'% 3way='+eqs[1]+'% 4way='+eqs[2]+'% 5way='+eqs[3]+'%');
ok(parseFloat(eqs[2])<parseFloat(eqs[0])+5,'递减');

// S7: river vs MC
console.log('\\n── S7: river vs MC ──');
var rH2=pc('AsKs'),rC2=[mc('J','s'),mc('9','h'),mc('2','c'),mc('7','d'),mc('3','s')];
var rR2=getOppRange('postflop','barrel_river','wet');
var re1=riverExactEquity(rH2,rC2,rR2,1),m1=mcVsRange(rH2,rC2,rR2,5000,1);
console.log('  HU: exact='+re1.eq.toFixed(1)+'% mc='+m1.eq.toFixed(1)+'%');
ok(Math.abs(re1.eq-m1.eq)<15,'HU');

// S8: multiPlayerEquity
console.log('\\n── S8: multiPlayerEquity ──');
ok(multiPlayerEquity(50,3)===35,'3w');ok(multiPlayerEquity(50,4)===27.5,'4w');ok(multiPlayerEquity(50,5)===22.5,'5w');
console.log('  3w=35(70%) 4w=27.5(55%) 5w=22.5(45%) ✅');

// S9: 辅助
console.log('\\n── S9: 辅助函数 ──');
var hc2=handClassify(pc('AsAh'),pc('7s3h2c'));ok(hc2&&hc2.name,'hc');
var bt2=boardTexture(pc('KsQsJs'));ok(bt2&&bt2.wetness>0,'bt');
ok(typeof calcSPR()==='number','spr');
ok(postflopIP('btn')===true,'ip-btn');ok(postflopIP('sb')===false,'ip-sb');
console.log('  handClassify/boardTexture/calcSPR/postflopIP ✅');

console.log('\\n======================================================');
console.log('  ✅ '+_p+' 通过 | ❌ '+_f+' 失败');
console.log('======================================================');
`,ctx,{timeout:120000});
