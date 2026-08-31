global.window=global; global.document={getElementById:function(){return{style:{},classList:{add:function(){},remove:function(){},contains:function(){return false;}},querySelector:function(){return null;},appendChild:function(){}};},addEventListener:function(){},createElement:function(){return{style:{},getContext:function(){return null;}};},body:{appendChild:function(){}}};
global.navigator={userAgent:'node'};global.Worker=function(){};global.location={href:'file:///x'};
var src=require('fs').readFileSync(__dirname+'/blk1.js','utf8');
try{ eval(src); }catch(e){}
DRTA.tracker={hands:100,vpip:30,pfr:20,af:1.5,bluff_attempts:5,recent_agg:[1,1,1],recent:[],fold_to_bet:50,bluff_success_rate:0.5};
function C(r,s){return{rank:r,suit:s};}
Math.random=function(){return 0.0;};
ActionLine._lines=[];ActionLine.record('preflop','raise','call','raise',45,0);ActionLine.record('flop','raise','raise','bet',0,0);
for(var k in {phase:'turn',pos:'co',pot:100,bet:30,stk:10000,scene:'raise',hole:[C('9','h'),C('9','d')],comm:[C('K','s'),C('2','c'),C('4','h'),C('Q','d')],players:[{active:true,chips:9000},{active:true,chips:9000}]})G[k]={phase:'turn',pos:'bb',pot:100,bet:30,stk:10000,scene:'raise',hole:[C('9','h'),C('9','d')],comm:[C('K','s'),C('2','c'),C('4','h'),C('Q','d')],players:[{active:true,chips:9000},{active:true,chips:9000}]}[k];
console.log('street=',getCurrentStreet(),' didPFR=',ActionLine.didPreflopRaise(),' ip?',pA('bb')>=0.5);
var r=StrategyEngine.decidePostflop('99');
console.log('DECISION:',r&&r.a,r&&r.scene,'|',(r&&r.r||'').slice(0,100));
