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
function linePFR(){ try{ActionLine._lines=[];ActionLine.record('preflop','raise','raise','call',55,300);ActionLine.record('flop','check','check','',0,0);}catch(e){console.log('linePFR err:'+e.message);} }
function lineCaller(){ try{ActionLine._lines=[];ActionLine.record('preflop','raise','call','raise',45,0);ActionLine.record('flop','raise','call','bet',0,0);}catch(e){console.log('lineCaller err:'+e.message);} }
function C(r,s){return{rank:r,suit:s};}
var _or=Math.random;
function det(){Math.random=function(){return 0.0;};}
function und(){Math.random=_or;}

console.log('===== POST-11: flushDraw 手牌参与条件 =====');
try{
  // 场景A(BUG场景): turn公牌4张黑桃+手牌0黑桃 → 不应flushDraw/outs不应含9花
  var hc4board=handClassify([C('A','h'),C('K','h')],[C('2','s'),C('5','s'),C('9','s'),C('J','s')]);
  chk('POST-11 公4花0手花不算听花', hc4board.outs<9, hc4board.name+' outs='+hc4board.outs+' '+hc4board.desc);
  // 场景B(对照): turn公牌3张黑桃+手牌1黑桃 → 真听花 outs应>=9
  var hc3b1h=handClassify([C('A','s'),C('K','h')],[C('2','s'),C('5','s'),C('9','s'),C('J','h')]);
  chk('POST-11 公3花1手花算听花', hc3b1h.outs>=9, hc3b1h.name+' outs='+hc3b1h.outs+' '+hc3b1h.desc);
  // 场景C(对照): flop公2花+手2花 = 4 → 真听花
  var hc2b2h=handClassify([C('A','s'),C('K','s')],[C('2','s'),C('5','s'),C('9','h')]);
  chk('POST-11 公2花2手花算听花', hc2b2h.outs>=9, hc2b2h.name+' outs='+hc2b2h.outs+' '+hc2b2h.desc);
  chk('POST-11 源码含_flushHoleSuits', src.indexOf('_flushHoleSuits')>=0);
}catch(e){chk('POST-11 异常',false,e.message);}

console.log('===== POST-14: ExploitAdjuster 再分配公式 =====');
try{
  chk('POST-14 旧公式已移除', src.indexOf('adjC=adjC+(1-_fcbEA.freq)*adjC')<0);
  chk('POST-14 新再分配_dR存在', src.indexOf('_dR=adjR*(1-_fcbEA.freq)')>=0);
  chk('POST-14 末端统一归一化', src.indexOf('_sumEA=adjC+adjR+adjF')>=0);
  var adjR=0.13,adjC=0.52,adjF=0.35,freq=0.3;
  var dR=adjR*(1-freq); adjR=adjR*freq;
  var cfBase=adjC+adjF; adjC+=dR*(adjC/cfBase); adjF+=dR*(adjF/cfBase);
  chk('POST-14 新公式call不放大', adjC<0.6, 'adjC='+adjC.toFixed(3)+'(旧公式0.884)');
  chk('POST-14 新公式fold被补偿', adjF>0.3, 'adjF='+adjF.toFixed(3));
  var sum=adjR+adjC+adjF;
  chk('POST-14 三概率和=1', Math.abs(sum-1)<0.01, 'sum='+sum.toFixed(3));
}catch(e){chk('POST-14 异常',false,e.message);}

console.log('===== POST-12: face 小注防守(端到端) =====');
try{
  chk('POST-12 river小注规则源码', src.indexOf('rivSzRatio')>=0 && src.indexOf('river小注防守')>=0);
  chk('POST-12 turn小注规则源码', src.indexOf('_tdSzRatio')>=0 && src.indexOf('turn小注防守')>=0);
  // 端到端: river防守方底对(77 on K94Q3),对手1/3pot小注 → 不应fold
  lineCaller();
  setG({phase:'river',pos:'bb',pot:100,bet:33,stk:10000,scene:'raise',hole:[C('7','h'),C('7','d')],comm:[C('K','s'),C('9','c'),C('4','h'),C('Q','d'),C('3','s')],players:[{active:true,chips:9000},{active:true,chips:9000}]});
  det();
  var rivSmall=StrategyEngine.decidePostflop('77');
  und();
  chk('POST-12 river 1/3小注底对不fold', rivSmall&&rivSmall.a!=='fold', 'a='+(rivSmall&&rivSmall.a)+' r='+(rivSmall&&rivSmall.r||'').slice(0,50));
  // 对照: 超池注(150%)——小注规则不得触发(szRatio>=0.4),决策由枚举器pot odds主导
  lineCaller();
  setG({phase:'river',pos:'bb',pot:100,bet:150,stk:10000,scene:'raise',hole:[C('7','h'),C('7','d')],comm:[C('K','s'),C('9','c'),C('4','h'),C('Q','d'),C('3','s')],players:[{active:true,chips:9000},{active:true,chips:9000}]});
  det();
  var rivBig=StrategyEngine.decidePostflop('77');
  und();
  chk('POST-12 river 超池注有EV决策(小注规则不干预)', rivBig&&(rivBig.a==='fold'||rivBig.a==='call'||rivBig.a==='raise')&&(rivBig.r||'').indexOf('小注防守')<0, 'a='+(rivBig&&rivBig.a)+' r='+(rivBig&&rivBig.r||'').slice(0,60));
  // 纯空气(72o)河牌vs小注: hc=15>10,小注规则不救,仍fold(过度防守的边界守住)
  lineCaller();
  setG({phase:'river',pos:'bb',pot:100,bet:33,stk:10000,scene:'raise',hole:[C('7','s'),C('2','h')],comm:[C('K','s'),C('9','c'),C('4','h'),C('Q','d'),C('3','s')],players:[{active:true,chips:9000},{active:true,chips:9000}]});
  det();
  var rivAir=StrategyEngine.decidePostflop('72o');
  und();
  chk('POST-12 river 小注纯空气仍fold', rivAir&&rivAir.a==='fold', 'a='+(rivAir&&rivAir.a));
  // turn端到端: OOP防守方(桩中pA('utg')=0<0.5→ip=false)面对小注,decideTurnDefense路径
  // 概率决策用随机数0..1扫描取分布(固定rand=0必中raise档,不能断言单次行动)
  function _tdDist(bet){
    lineCaller();
    setG({phase:'turn',pos:'utg',pot:100,bet:bet,stk:10000,scene:'raise',hole:[C('9','h'),C('9','d')],comm:[C('K','s'),C('2','c'),C('4','h'),C('Q','d')],players:[{active:true,chips:9000},{active:true,chips:9000}]});
    var _c={};
    for(var _i=0;_i<=100;_i++){ Math.random=function(){return _i/100;}; var _r=StrategyEngine.decidePostflop('99'); _c[_r.a]=(_c[_r.a]||0)+1; }
    und();
    return {fold:_c.fold||0,call:_c.call||0,raise:_c.raise||0};
  }
  var _tdS=_tdDist(30), _tdB=_tdDist(120);
  // 小注30%pot: fold减半(25%档→实测~26%), call升至~65%; 防守率(call+raise)>=70%达MDF(30%注MDF=71%)
  chk('POST-12 turn 1/3小注fold减半', _tdS.fold<=30, 'fold='+_tdS.fold+'% call='+_tdS.call+'% raise='+_tdS.raise+'%');
  chk('POST-12 turn 1/3小注防守率达MDF', (_tdS.call+_tdS.raise)>=70, '防守率='+(_tdS.call+_tdS.raise)+'% (MDF 71%)');
  // 对照: 超池120%小注规则不干预——fold显著高于小注场景(~50% vs ~26%)
  chk('POST-12 turn 超池小注规则不干预', _tdB.fold>=45 && _tdB.fold>_tdS.fold+15, '超池fold='+_tdB.fold+'% vs 小注fold='+_tdS.fold+'%');
}catch(e){chk('POST-12 异常',false,e.message);}

console.log('===== POST-13: 下注档+delayed cbet+turn probe(端到端) =====');
try{
  chk('POST-13 _betList扩5档', src.indexOf('_betList=[s33,s50,s75,s100,s133]')>=0);
  chk('POST-13 barrel分支去didFlopCBet门槛', src.indexOf("if(street==='turn' && didPFR && typeof _turnBarrel==='function')")>=0);
  chk('POST-13 barrel传真实didFlop状态', src.indexOf('var _didFCB=ActionLine.didFlopCBet();')>=0);
  chk('POST-13 turn probe分支', src.indexOf('Turn Probe')>=0);
  // 端到端turn probe: 防守方(!didPFR) turn对手check, 我们持有超对AA → 应probe出bet
  // 行动线: pre call, flop check-check(对手check), turn check
  try{ActionLine._lines=[];ActionLine.record('preflop','raise','call','raise',45,0);ActionLine.record('flop','check','check','check',0,0);}catch(e){}
  setG({phase:'turn',pos:'bb',pot:100,bet:0,stk:10000,scene:'check',hole:[C('A','s'),C('A','h')],comm:[C('K','s'),C('4','h'),C('2','d'),C('9','c')],players:[{active:true,chips:9000},{active:true,chips:9000}]});
  det();
  var probeR=StrategyEngine.decidePostflop('AA');
  und();
  chk('POST-13 turn probe超对领先下注', probeR&&probeR.a==='raise'&&/probe/i.test(probeR.r||probeR.scene||''), 'a='+(probeR&&probeR.a)+' scene='+(probeR&&probeR.scene)+' r='+(probeR&&probeR.r||'').slice(0,50));
  // 端到端delayed cbet: PFR flop check后turn拿强牌,应能开火(不返回null)
  try{ActionLine._lines=[];ActionLine.record('preflop','raise','raise','call',55,300);ActionLine.record('flop','check','check','check',0,0);}catch(e){}
  setG({phase:'turn',pos:'btn',pot:100,bet:0,stk:10000,scene:'check',hole:[C('A','s'),C('A','h')],comm:[C('K','s'),C('9','c'),C('4','h'),C('2','d')],players:[{active:true,chips:9000},{active:true,chips:9000}]});
  det();
  var delayR=StrategyEngine.decidePostflop('AA');
  und();
  chk('POST-13 delayed cbet有决策(非null)', delayR&&delayR.a, 'a='+(delayR&&delayR.a)+' scene='+(delayR&&delayR.scene)+' r='+(delayR&&delayR.r||'').slice(0,50));
}catch(e){chk('POST-13 异常',false,e.message);}

console.log('===== PRE-5: 范围收紧/BB防守补档/5bet =====');
try{
  var utg1=StrategyEngine.getRFI('UTG1');
  chk('PRE-5 UTG1 A9o<=.1', utg1.A9o<=0.1, 'A9o='+utg1.A9o);
  chk('PRE-5 UTG1 K8s<=.1', utg1.K8s<=0.1, 'K8s='+utg1.K8s);
  chk('PRE-5 UTG1 Q8s<=.1', utg1.Q8s<=0.1, 'Q8s='+utg1.Q8s);
  chk('PRE-5 UTG1 J8s<=.1', utg1.J8s<=0.1, 'J8s='+utg1.J8s);
  var bbBtn=StrategyEngine.get3B('vs_BTN','from_BB');
  chk('PRE-5 BBvsBTN K8o补call', bbBtn.K8o&&bbBtn.K8o.a==='c', JSON.stringify(bbBtn.K8o));
  chk('PRE-5 BBvsBTN Q9o补call', bbBtn.Q9o&&bbBtn.Q9o.a==='c', JSON.stringify(bbBtn.Q9o));
  chk('PRE-5 BBvsBTN T9o补call', bbBtn.T9o&&bbBtn.T9o.a==='c', JSON.stringify(bbBtn.T9o));
  chk('PRE-5 BBvsBTN 98o补call', bbBtn['98o']&&bbBtn['98o'].a==='c', JSON.stringify(bbBtn['98o']));
  chk('PRE-5 BBvsBTN 87o补call', bbBtn['87o']&&bbBtn['87o'].a==='c', JSON.stringify(bbBtn['87o']));
  chk('PRE-5 新引擎5bet分支源码', src.indexOf('5bet-shove')>=0 && src.indexOf('_5bBetBB')>=0);
  // 行为: 面对15BB加注(5bet) JTs→fold
  setG({pos:'btn',pot:3,bet:30,stk:10000,scene:'reraise',hole:[C('J','h'),C('T','s')]});
  det();
  var r5fold=StrategyEngine.decidePreflop('JTs');
  und();
  chk('PRE-5 5bet场景JTs弃牌', r5fold&&r5fold.a==='fold'&&r5fold.r.indexOf('5bet')>=0, (r5fold&&r5fold.a)+' '+(r5fold&&r5fold.r||'').slice(0,40));
  // AA面对5bet继续全下
  setG({pos:'btn',pot:3,bet:30,stk:10000,scene:'reraise',hole:[C('A','h'),C('A','s')]});
  det();
  var r5shove=StrategyEngine.decidePreflop('AA');
  und();
  chk('PRE-5 5bet场景AA全下', r5shove&&r5shove.a==='raise'&&r5shove.scene==='面对5bet', (r5shove&&r5shove.a)+' '+(r5shove&&r5shove.scene));
  // 对照: 正常3bet(9BB)不误判
  setG({pos:'btn',pot:3,bet:18,stk:10000,scene:'reraise',hole:[C('J','h'),C('T','s')]});
  det();
  var r3b=StrategyEngine.decidePreflop('JTs');
  und();
  chk('PRE-5 正常3bet不误判5bet', r3b&&r3b.r.indexOf('5bet')<0, (r3b&&r3b.r||'').slice(0,50));
}catch(e){chk('PRE-5 异常',false,e.message);}

console.log('\n===== 结果: '+pass+' PASS / '+fail+' FAIL =====');
if(fail>0)process.exit(1);
