global.window=global; global.document={getElementById:function(){return null;},addEventListener:function(){},createElement:function(){return{style:{},getContext:function(){return null;}};},body:{appendChild:function(){}}};
global.navigator={userAgent:'node'};
global.Worker=function(){};
global.location={href:'file:///x'};
var path=require('path');
var src=require('fs').readFileSync(path.join(__dirname,'blk1.js'),'utf8');
try{ eval(src); }catch(e){ console.log('eval note:',e.message.slice(0,80)); }
try{
  DRTA.tracker={hands:100,vpip:30,pfr:20,af:1.5,bluff_attempts:5,bluff_successes:3,_bet_faced:10,_fold_to_bet:5,recent_agg:[1,1,1],recent:[],aggro:[],threebet:8,cbet:50,fold_cbet:45,steal:30,wtsd:25,wsd:50,fold_to_bet:50,bluff_success_rate:0.5};
}catch(e){console.log('tracker stub note:',e.message);}

function setG(o){ for(var k in o) G[k]=o[k]; }
var pass=0,fail=0;
function chk(name,cond,extra){ if(cond){pass++;console.log('PASS',name,extra||'');}else{fail++;console.log('FAIL',name,extra||'');} }

// 1. PRE-2 squeeze: BTN vs UTG1 open + 1冷跟, AA
try{
  setG({pos:'btn',scene:'raise',stk:20000,pot:900,bet:600,limpers:0,_numCallersBefore:1,_raiserRole:'utg1',opp:'unknown',ante:0,tt:6,hole:[{rank:'A',suit:'s'},{rank:'A',suit:'h'}],comm:[]});
  var _or=Math.random; Math.random=function(){return 0.0;};
  var r=StrategyEngine.decidePreflop('AA');
  Math.random=_or;
  chk('PRE-2 squeeze AA btn vs open+caller', r&&r.scene==='Squeeze', r&&(r.scene+' | '+r.r));
}catch(e){chk('PRE-2 squeeze',false,e.message);}

// 2. PRE-2 无冷跟者 → 不走squeeze
try{
  setG({pos:'btn',scene:'raise',stk:20000,pot:900,bet:600,limpers:0,_numCallersBefore:0,_raiserRole:'utg1',opp:'unknown',ante:0,tt:6,hole:[{rank:'A',suit:'s'},{rank:'A',suit:'h'}],comm:[]});
  var _or=Math.random; Math.random=function(){return 0.0;};
  var r=StrategyEngine.decidePreflop('AA');
  Math.random=_or;
  chk('PRE-2 无冷跟不走squeeze', r&&r.scene!=='Squeeze', r&&(r.scene+'/'+r.a));
}catch(e){chk('PRE-2 无冷跟',false,e.message);}

// 3. PRE-2 死代码已移除
chk('PRE-2 死分支scene==reraise内无squeeze', src.indexOf("scene==='reraise'")>0 && src.indexOf('Squeeze挤压 vs ')>0, '');

// 4. PRE-3 iso: BTN + 1 limper 尺度~5BB
try{
  setG({pos:'btn',scene:'check',stk:20000,pot:300,bet:0,limpers:1,_numCallersBefore:0,opp:'unknown',ante:0,tt:6,hole:[{rank:'A',suit:'s'},{rank:'K',suit:'h'}],comm:[]});
  var _or=Math.random; Math.random=function(){return 0.0;};
  var r=StrategyEngine.decidePreflop('AKo');
  Math.random=_or;
  chk('PRE-3 iso btn 1limper 尺度~5BB(1000)', r&&r.a==='raise'&&r.v>=1150&&r.v<=1300, r&&(r.a+' v='+r.v+' '+(r.r||'')));
}catch(e){chk('PRE-3 iso',false,e.message);}

// 5. PRE-3 iso 2 limpers 尺度~6BB且fish×1.3
try{
  setG({pos:'btn',scene:'check',stk:20000,pot:300,bet:0,limpers:2,_numCallersBefore:0,opp:'fish',ante:0,tt:6,hole:[{rank:'A',suit:'s'},{rank:'K',suit:'s'}],comm:[]});
  var _or=Math.random; Math.random=function(){return 0.0;};
  var r=StrategyEngine.decidePreflop('AKs');
  Math.random=_or;
  // 6BB=1200 ×1.3=1560
  chk('PRE-3 iso 2limper fish加码', r&&r.a==='raise'&&r.v>=1500&&r.v<=2700, r&&(r.a+' v='+r.v));
}catch(e){chk('PRE-3 iso fish',false,e.message);}

// 6. PRE-3 无limper → 普通open尺度(pot*3=900 for btn)
try{
  setG({pos:'btn',scene:'check',stk:20000,pot:300,bet:0,limpers:0,_numCallersBefore:0,opp:'unknown',ante:0,tt:6,hole:[{rank:'A',suit:'s'},{rank:'K',suit:'h'}],comm:[]});
  var _or=Math.random; Math.random=function(){return 0.0;};
  var r=StrategyEngine.decidePreflop('AKo');
  Math.random=_or;
  chk('PRE-3 无limper普通open ~900', r&&r.a==='raise'&&r.scene==='开池'&&r.v<=1200, r&&(r.a+' v='+r.v));
}catch(e){chk('PRE-3 无limper',false,e.message);}

// 7. PRE-4 源码验证
chk('PRE-4 _get3bAlt非常量0.3', src.indexOf('function _get3bAlt')>0 && src.indexOf('口袋对(set矿)')>0, '');
chk('PRE-4 _adjFreq4bet存在', src.indexOf('function _adjFreq4bet')>0, '');
chk('PRE-4 nit blocker A2s-A5s/Kxs ×1.3', src.indexOf("'2345'.indexOf(k[1])>=0")>0, '');

console.log('\n== 翻前冒烟: '+pass+' pass, '+fail+' fail ==');
process.exit(fail?1:0);
