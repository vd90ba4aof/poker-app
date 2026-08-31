global.window=global; global.document={getElementById:function(){return{style:{},classList:{add:function(){},remove:function(){},contains:function(){return false;}},querySelector:function(){return null;},appendChild:function(){}};},addEventListener:function(){},createElement:function(){return{style:{},getContext:function(){return null;}};},body:{appendChild:function(){}}};
global.navigator={userAgent:'node'};global.Worker=function(){};global.location={href:'file:///x'};
var src=require('fs').readFileSync(__dirname+'/blk1.js','utf8');
try{ eval(src); }catch(e){}
DRTA.tracker={hands:100,vpip:30,pfr:20,af:1.5,bluff_attempts:5,recent_agg:[1,1,1],recent:[],fold_to_bet:50,bluff_success_rate:0.5};
// squeeze段变量推演
var p='btn', p5=_pos5(p);
console.log('p5=',p5);
var raiserRole='utg1';
var rP5=_pos5(raiserRole);
console.log('rP5(raiser)=',rP5);
// 我们的代码在 scene==='raise' 分支开头用 _pos5(G._raiserRole)
var _sqOpenP5=_pos5('utg1');
console.log('_sqOpenP5=',_sqOpenP5);
var sq=StrategyEngine.getSqueezeTable?StrategyEngine.getSqueezeTable():null;
console.log('table key vs_'+_sqOpenP5+'_vs_caller =', sq?('vs_'+_sqOpenP5+'_vs_caller' in sq):'no table');
