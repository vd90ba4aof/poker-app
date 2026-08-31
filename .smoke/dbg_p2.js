global.window=global; global.document={getElementById:function(){return{style:{},classList:{add:function(){},remove:function(){},contains:function(){return false;}},querySelector:function(){return null;},appendChild:function(){}};},addEventListener:function(){},createElement:function(){return{style:{},getContext:function(){return null;}};},body:{appendChild:function(){}}};
global.navigator={userAgent:'node'};global.Worker=function(){};global.location={href:'file:///x'};
var src=require('fs').readFileSync(__dirname+'/blk1.js','utf8');
try{ eval(src); }catch(e){ console.log('eval note:',e.message.slice(0,100)); }
DRTA.tracker={hands:100,vpip:30,pfr:20,af:1.5,bluff_attempts:5,recent_agg:[1,1,1],recent:[],fold_to_bet:50,bluff_success_rate:0.5};
function C(r,s){return{rank:r,suit:s};}
function setG(o){ for(var k in o) G[k]=o[k]; }
Math.random=function(){return 0.0;};
ActionLine._lines=[];ActionLine.record('preflop','raise','call','raise',45,0);ActionLine.record('flop','raise','call','bet',0,0);
setG({pos:'bb',pot:100,bet:150,stk:10000,scene:'raise',hole:[C('7','h'),C('7','d')],comm:[C('K','s'),C('9','c'),C('4','h'),C('Q','d'),C('3','s')],players:[{active:true,chips:9000},{active:true,chips:9000}]});
var hole=[C('7','h'),C('7','d')],comm=[C('K','s'),C('9','c'),C('4','h'),C('Q','d'),C('3','s')];
var hc=handClassify(hole,comm);
console.log('handClass:',hc.name,hc.desc);
var rs=riverExactEquity(hole,comm,getOppRange('postflop','raise','wet'));
console.log('eq vs raise范围:',rs.eq,' range n=',getOppRange('postflop','raise','wet').length);
var r=StrategyEngine.decidePostflop('77');
console.log('DECISION:',r&&r.a, r&&r.scene, '|', (r&&r.r||'').slice(0,120));
