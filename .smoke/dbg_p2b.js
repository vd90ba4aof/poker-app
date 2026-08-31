global.window=global; global.document={getElementById:function(){return{style:{},classList:{add:function(){},remove:function(){},contains:function(){return false;}},querySelector:function(){return null;},appendChild:function(){}};},addEventListener:function(){},createElement:function(){return{style:{},getContext:function(){return null;}};},body:{appendChild:function(){}}};
global.navigator={userAgent:'node'};global.Worker=function(){};global.location={href:'file:///x'};
var src=require('fs').readFileSync(__dirname+'/blk1.js','utf8');
try{ eval(src); }catch(e){ console.log('eval note:',e.message.slice(0,100)); }
DRTA.tracker={hands:100,vpip:30,pfr:20,af:1.5,bluff_attempts:5,recent_agg:[1,1,1],recent:[],fold_to_bet:50,bluff_success_rate:0.5};
function C(r,s){return{rank:r,suit:s};}
function setG(o){ for(var k in o) G[k]=o[k]; }
Math.random=function(){return 0.0;};
// turn OOP防守方 vs 小注: 99 on K24Q turn
ActionLine._lines=[];ActionLine.record('preflop','raise','call','raise',45,0);ActionLine.record('flop','raise','raise','bet',0,0);
setG({pos:'bb',pot:100,bet:30,stk:10000,scene:'raise',hole:[C('9','h'),C('9','d')],comm:[C('K','s'),C('2','c'),C('4','h'),C('Q','d')],players:[{active:true,chips:9000},{active:true,chips:9000}]});
var hole=[C('9','h'),C('9','d')],comm=[C('K','s'),C('2','c'),C('4','h'),C('Q','d')];
var hc=handClassify(hole,comm);
console.log('handClass:',hc.name,hc.desc);
var bt=boardTexture(comm);
console.log('texture:',bt.category,bt.wetness);
var ms=mcVsRange(hole,comm,getOppRange('postflop','raise','dry'),1000);
console.log('mcVsRange eq:',ms.eq);
console.log('street:',getCurrentStreet());
console.log('didPFR:',ActionLine.didPreflopRaise());
var r=StrategyEngine.decidePostflop('99');
console.log('DECISION:',r&&r.a, r&&r.scene, '|', (r&&r.r||'').slice(0,120));
// 直接试decideTurnDefense(它是全局函数?)
try{
  var td=decideTurnDefense('99',calcSPR(),ms.eq,DRTA.getProfile(),bt,hc);
  console.log('decideTurnDefense direct:',td&&td.a,td&&td.r);
}catch(e){console.log('decideTurnDefense err:',e.message);}
