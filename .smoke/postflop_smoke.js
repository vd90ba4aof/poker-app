global.window=global; global.document={getElementById:function(){return{style:{},classList:{add:function(){},remove:function(){},contains:function(){return false;}},querySelector:function(){return null;},appendChild:function(){}};},addEventListener:function(){},createElement:function(){return{style:{},getContext:function(){return null;}};},body:{appendChild:function(){}}};
global.navigator={userAgent:'node'};global.Worker=function(){};global.location={href:'file:///x'};
var src=require('fs').readFileSync(__dirname+'/blk1.js','utf8');
try{ eval(src); }catch(e){ console.log('eval note:',e.message.slice(0,100)); }
try{
  DRTA.tracker={hands:100,vpip:30,pfr:20,af:1.5,bluff_attempts:5,recent_agg:[1,1,1],recent:[],fold_to_bet:50,bluff_success_rate:0.5};
}catch(e){console.log('tracker stub note:',e.message);}

var pass=0,fail=0;
function chk(name,cond,extra){ if(cond){pass++;console.log('PASS',name,extra||'');}else{fail++;console.log('FAIL',name,extra||'');} }
function setG(o){ for(var k in o) G[k]=o[k]; }
// 行动线: hero是PFR(翻前加注者)
function linePFR(){ try{ActionLine._lines=[];ActionLine.record('preflop','raise','raise','call',55,300);ActionLine.record('flop','check','check','',0,0);}catch(e){console.log('linePFR err:'+e.message);} }
// 行动线: hero是防守方(翻前call,未加注)
function lineCaller(){ try{ActionLine._lines=[];ActionLine.record('preflop','raise','call','raise',45,0);ActionLine.record('flop','raise','raise','bet',0,0);}catch(e){console.log('lineCaller err:'+e.message);} }

function C(r,s){return{rank:r,suit:s};}
var _or=Math.random;
function det(){Math.random=function(){return 0.0;};}
function und(){Math.random=_or;}

// ===== POST-6: _narrowRangeByLine 静态收窄 =====
try{
  var base=gC('btn');
  var nCbet=_narrowRangeByLine(base.slice(),'cbet',0).length;
  var nTurn=_narrowRangeByLine(base.slice(),'barrel_turn',0).length;
  var nRiver=_narrowRangeByLine(base.slice(),'barrel_river',0).length;
  var nRaise=_narrowRangeByLine(base.slice(),'raise',0).length;
  console.log('  范围数 base='+base.length+' cbet='+nCbet+' turn='+nTurn+' river='+nRiver+' raise='+nRaise);
  chk('POST-6 cbet不变', nCbet===base.length);
  chk('POST-6 turn收窄', nTurn<base.length && nTurn>=8, 'turn='+nTurn);
  chk('POST-6 river更紧', nRiver<=nTurn && nRiver>=8, 'river='+nRiver);
  chk('POST-6 raise极化', nRaise<base.length && nRaise>=8, 'raise='+nRaise);
  // <8回退: 构造极小数组
  var tiny=_narrowRangeByLine(['22','33'],'barrel_river',0);
  chk('POST-6 <8回退原范围', tiny.length===2, 'len='+tiny.length);
  // getOppRange postflop 接通
  setG({pos:'btn'});
  var gr=getOppRange('postflop','barrel_turn',{wetness:1});
  chk('POST-6 getOppRange收窄接通', Array.isArray(gr)&&gr.length>=8, 'len='+gr.length);
}catch(e){chk('POST-6 收窄',false,e.message);}

// ===== POST-10: _mcFallbackEq 粗估 =====
try{
  // NUTS: flop 三条A (AAs on A-K-7)
  var eqNuts=_mcFallbackEq([C('A','s'),C('A','h')],[C('A','c'),C('K','d'),C('7','s')],1);
  chk('POST-10 NUTS粗估~90', eqNuts>=80, 'eq='+eqNuts);
  // AIR: 72o on K-Q-9 不相关
  var eqAir=_mcFallbackEq([C('7','s'),C('2','h')],[C('K','c'),C('Q','d'),C('9','s')],1);
  chk('POST-10 AIR粗估~15', eqAir>0&&eqAir<=25, 'eq='+eqAir);
  // DRAW: 9s8s on AsKs2h = 坚果同花听牌(flushDraw,outs~9)+两高张? 98s无高张 outs≈9
  var eqDraw=_mcFallbackEq([C('9','s'),C('8','s')],[C('A','s'),C('K','s'),C('2','h')],1);
  chk('POST-10 DRAW粗估(outs)', eqDraw>eqAir&&eqDraw<eqNuts, 'eq='+eqDraw);
  // 多人池衰减: 4人池(nOpp=3) AIR应低于单挑
  var eqAirMW=_mcFallbackEq([C('7','s'),C('2','h')],[C('K','c'),C('Q','d'),C('9','s')],3);
  chk('POST-10 多人池衰减', eqAirMW<eqAir, '3way eq='+eqAirMW+' vs hu eq='+eqAir);
  // 粗估永不为0/null
  chk('POST-10 永不为0', eqNuts>0&&eqAir>0&&eqDraw>0&&eqAirMW>0);
  // 源码: 超时/onerror返回timeout标记, 无eq:0
  chk('POST-10 超时返回timeout标记', src.indexOf('timeout:true')>=0 && src.indexOf('_mcFallbackEq')>=0);
  chk('POST-10 无resolve eq:0', !/resolve\(\{eq:0,win:0,tie:0\}\)/.test(src));
}catch(e){chk('POST-10 粗估',false,e.message);}

// ===== POST-8: 多人池 CBet/CR 收紧 (行为级) =====
// 场景A: 3way flop hero是PFR, 空气牌(72o完全miss dry board) → 不应cbet/raise
try{
  setG({pos:'btn',scene:'check',stk:20000,pot:600,bet:0,limpers:0,opp:'unknown',ante:0,tt:6,phase:'post',
    hole:[C('7','c'),C('2','d')],comm:[C('K','h'),C('9','s'),C('3','c')],
    players:[{chips:20000,active:1},{chips:20000,active:1},{chips:20000,active:1}],_lastPlayers:[{chips:20000,active:1,folded:false},{chips:20000,active:1,folded:false},{chips:20000,active:1,folded:false}]});
  linePFR();
  det();
  var r=StrategyEngine.decidePostflop('72o');
  und();
  chk('POST-8 3way空气不cbet/raise', r&&r.a!=='raise', r&&(r.a+' | '+r.r));
}catch(e){chk('POST-8 3way空气',false,e.message);}
// 场景B: headsup flop 空气 对照(允许cbet bluff)
try{
  setG({pos:'btn',scene:'check',stk:20000,pot:600,bet:0,limpers:0,opp:'unknown',ante:0,tt:6,phase:'post',
    hole:[C('7','c'),C('2','d')],comm:[C('K','h'),C('9','s'),C('3','c')],
    players:[{chips:20000,active:1},{chips:20000,active:1}],_lastPlayers:[{chips:20000,active:1,folded:false},{chips:20000,active:1,folded:false}]});
  linePFR();
  det();
  var r2=StrategyEngine.decidePostflop('72o');
  und();
  chk('POST-8 HU对照可正常决策(不报错)', !!r2, r2&&(r2.a+' | '+r2.r));
}catch(e){chk('POST-8 HU对照',false,e.message);}
// 源码断言: MW收紧标记存在
chk('POST-8 CBet MW收紧源码', src.indexOf('[MW] flop多人池CBet收紧')>=0);
chk('POST-8 CR MW收紧源码', src.indexOf('[MW] flop多人池CR收紧')>=0);

// ===== POST-7/9: 源码断言(内部函数不可达,验证接线) =====
chk('POST-9 分街fold getter接线', src.indexOf('getFoldToTurnCBetPct')>=0 && src.indexOf('getFoldToRiverCBetPct')>=0);
chk('POST-9 GTO回退基线', src.indexOf('foldToCBetFlop')>=0 && src.indexOf('foldToCBetTurn')>=0);
chk('POST-7 check EV分级源码', /evs\.check|check.*hcKey/.test(src) && src.indexOf('_coreStreet')>=0);
chk('POST-7 两枚举器透传street', src.indexOf("_coreStreet='river'")>=0 && src.indexOf("_coreStreet='turn'")>=0);
chk('POST-7 turn枚举器barrel_turn', src.indexOf("'barrel_turn'")>=0);
chk('POST-7 固定0.70已移除(callAfterEq)', src.indexOf('callAfterEq')>=0 && /var callAfterEq=Math\.min\(0\.97,eq\*_caMult\)/.test(src));

// ===== 回归: 翻后强牌能正常价值行动 =====
try{
  // flop 顶set? 用KK on K-7-2 rainbow → 三条K=STRONG/NUTS
  setG({pos:'btn',scene:'check',stk:20000,pot:600,bet:0,limpers:0,opp:'unknown',ante:0,tt:6,phase:'post',
    hole:[C('K','c'),C('K','d')],comm:[C('K','h'),C('7','s'),C('2','c')],
    players:[{chips:20000,active:1},{chips:20000,active:1}],_lastPlayers:[{chips:20000,active:1,folded:false},{chips:20000,active:1,folded:false}]});
  linePFR();
  det();
  var r3=StrategyEngine.decidePostflop('KK');
  und();
  chk('回归 flop setKK价值行动', r3&&(r3.a==='raise'||r3.a==='call'||r3.a==='bet'), r3&&(r3.a+' | '+r3.r));
}catch(e){chk('回归 setKK',false,e.message);}
// turn 场景
try{
  setG({pos:'btn',scene:'check',stk:20000,pot:900,bet:0,limpers:0,opp:'unknown',ante:0,tt:6,phase:'post',
    hole:[C('K','c'),C('K','d')],comm:[C('K','h'),C('7','s'),C('2','c'),C('Q','d')],
    players:[{chips:20000,active:1},{chips:20000,active:1}],_lastPlayers:[{chips:20000,active:1,folded:false},{chips:20000,active:1,folded:false}]});
  try{ActionLine._lines=[];ActionLine.record('preflop','raise','raise','call',55,300);ActionLine.record('flop','check','raise','call',80,300);ActionLine.record('turn','check','check','',0,0);}catch(e){}
  det();
  var r4=StrategyEngine.decidePostflop('KK');
  und();
  chk('回归 turn setKK不fold', r4&&r4.a!=='fold', r4&&(r4.a+' | '+r4.r));
}catch(e){chk('回归 turn setKK',false,e.message);}
// river 场景(完全枚举)
try{
  setG({pos:'btn',scene:'check',stk:20000,pot:1200,bet:0,limpers:0,opp:'unknown',ante:0,tt:6,phase:'post',
    hole:[C('K','c'),C('K','d')],comm:[C('K','h'),C('7','s'),C('2','c'),C('Q','d'),C('9','c')],
    players:[{chips:20000,active:1},{chips:20000,active:1}],_lastPlayers:[{chips:20000,active:1,folded:false},{chips:20000,active:1,folded:false}]});
  try{ActionLine._lines=[];ActionLine.record('preflop','raise','raise','call',55,300);ActionLine.record('flop','check','raise','call',80,300);ActionLine.record('turn','check','raise','call',85,600);ActionLine.record('river','check','check','',0,0);}catch(e){}
  det();
  var r5=StrategyEngine.decidePostflop('KK');
  und();
  chk('回归 river setKK不fold', r5&&r5.a!=='fold', r5&&(r5.a+' | '+(r5.r||'')));
}catch(e){chk('回归 river setKK',false,e.message);}
// 面对bet的防守: flop 顶对TPTK vs cbet 不fold
try{
  setG({pos:'bb',scene:'raise',stk:20000,pot:600,bet:300,limpers:0,opp:'unknown',ante:0,tt:6,phase:'post',
    hole:[C('A','c'),C('K','d')],comm:[C('K','h'),C('7','s'),C('2','c')],
    players:[{chips:20000,active:1},{chips:20000,active:1}],_lastPlayers:[{chips:20000,active:1,folded:false},{chips:20000,active:1,folded:false}]});
  lineCaller();
  det();
  var r6=StrategyEngine.decidePostflop('AKo');
  und();
  chk('回归 flop TPTK vs cbet不fold', r6&&r6.a!=='fold', r6&&(r6.a+' | '+r6.r));
}catch(e){chk('回归 TPTK防守',false,e.message);}

console.log('\n==== 翻后冒烟: '+pass+' PASS / '+fail+' FAIL ====');
if(fail>0)process.exit(1);
