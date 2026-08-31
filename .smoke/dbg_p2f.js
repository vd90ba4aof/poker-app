global.window=global; global.document={getElementById:function(){return{style:{},classList:{add:function(){},remove:function(){},contains:function(){return false;}},querySelector:function(){return null;},appendChild:function(){}};},addEventListener:function(){},createElement:function(){return{style:{},getContext:function(){return null;}};},body:{appendChild:function(){}}};
global.navigator={userAgent:'node'};global.Worker=function(){};global.location={href:'file:///x'};
var src=require('fs').readFileSync(__dirname+'/blk1.js','utf8');
try{ eval(src); }catch(e){ console.log('eval note:',e.message.slice(0,80)); }
DRTA.tracker={hands:100,vpip:30,pfr:20,af:1.5,bluff_attempts:5,recent_agg:[1,1,1],recent:[],fold_to_bet:50,bluff_success_rate:0.5};
function C(r,s){return{rank:r,suit:s};}
function setG(o){ for(var k in o) G[k]=o[k]; }

// 牌面 Ks 2c 4h Qd turn, 99, utg(OOP), 面对下注
ActionLine._lines=[];
ActionLine.record('preflop','raise','call','raise',45,0);
ActionLine.record('flop','raise','call','bet',0,0);
setG({phase:'turn',pos:'utg',pot:100,bet:30,stk:10000,scene:'raise',hole:[C('9','h'),C('9','d')],comm:[C('K','s'),C('2','c'),C('4','h'),C('Q','d')],players:[{active:true,chips:9000},{active:true,chips:9000}]});

var comm=G.comm, hole=G.hole;
var bt=boardTexture(comm);
var hc=handClassify(hole,comm);
console.log('boardTexture:', JSON.stringify(bt));
console.log('handClass:', hc.name, 'key=', hc.key||'(hc2key内部)');
console.log('pA(utg)=', pA('utg'), ' ip=', pA('utg')>=0.5);
console.log('didPFR=', ActionLine.didPreflopRaise());

// 扫描随机数 0..1 看决策分布
var counts={};
for(var i=0;i<=100;i++){
  Math.random=function(){return i/100;};
  var r=StrategyEngine.decidePostflop('99');
  var key=r.a+'|'+(r.r||'').slice(0,30);
  counts[key]=(counts[key]||0)+1;
}
console.log('小注30%pot 决策分布(随机数0-1):');
for(var k2 in counts) console.log('  ',k2,'→',counts[k2],'%');

// 超池对照
setG({bet:120});
var counts2={};
for(var j=0;j<=100;j++){
  Math.random=function(){return j/100;};
  var r2=StrategyEngine.decidePostflop('99');
  var key2=r2.a+'|'+(r2.r||'').slice(0,30);
  counts2[key2]=(counts2[key2]||0)+1;
}
console.log('超池120%pot 决策分布:');
for(var k3 in counts2) console.log('  ',k3,'→',counts2[k3],'%');
