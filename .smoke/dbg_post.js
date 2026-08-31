global.window=global; global.document={getElementById:function(){return{style:{},classList:{add:function(){},remove:function(){},contains:function(){return false;}},querySelector:function(){return null;},appendChild:function(){}};},addEventListener:function(){},createElement:function(){return{style:{},getContext:function(){return null;}};},body:{appendChild:function(){}}};
global.navigator={userAgent:'node'};global.Worker=function(){};global.location={href:'file:///x'};
try{eval(require('fs').readFileSync(__dirname+'/blk1.js','utf8'));}catch(e){console.log('eval note:',e.message.slice(0,60));}
DRTA.tracker={hands:100,vpip:30,pfr:20,af:1.5,bluff_attempts:5,recent_agg:[1,1,1],recent:[],fold_to_bet:50,bluff_success_rate:0.5};
function C(r,s){return{rank:r,suit:s};}
var G2={pos:'btn',scene:'check',stk:20000,pot:600,bet:0,limpers:0,opp:'unknown',ante:0,tt:6,phase:'post',
  hole:[C('K','c'),C('K','d')],comm:[C('K','h'),C('7','s'),C('2','c')],
  players:[{chips:20000,active:1},{chips:20000,active:1}],_lastPlayers:[{chips:20000,active:1,folded:false},{chips:20000,active:1,folded:false}]};
for(var k in G2)G[k]=G2[k];
try{
  var hc=handClassify(G.hole,G.comm);
  console.log('hClass:',hc.name,'outs:',hc.outs);
  var r=StrategyEngine.decidePostflop('KK');
  console.log('result:',JSON.stringify(r&&{a:r.a,r:r.r,scene:r.scene}));
}catch(e){console.log('THROW:',e.message);console.log(e.stack.split('\n').slice(0,4).join('\n'));}
