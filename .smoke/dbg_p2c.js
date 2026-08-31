global.window=global; global.document={getElementById:function(){return{style:{},classList:{add:function(){},remove:function(){},contains:function(){return false;}},querySelector:function(){return null;},appendChild:function(){}};},addEventListener:function(){},createElement:function(){return{style:{},getContext:function(){return null;}};},body:{appendChild:function(){}}};
global.navigator={userAgent:'node'};global.Worker=function(){};global.location={href:'file:///x'};
var src=require('fs').readFileSync(__dirname+'/blk1.js','utf8');
try{ eval(src); }catch(e){}
DRTA.tracker={hands:100,vpip:30,pfr:20,af:1.5,bluff_attempts:5,recent_agg:[1,1,1],recent:[],fold_to_bet:50,bluff_success_rate:0.5};
function C(r,s){return{rank:r,suit:s};}
var hole=[C('9','h'),C('9','d')],comm=[C('K','s'),C('2','c'),C('4','h'),C('Q','d')];
function probe(rngName,wet){
  var rng=getOppRange('postflop',rngName,wet);
  var ms=mcVsRange(hole,comm,rng,2000);
  console.log(rngName,wet,'n=',rng.length,'eq=',ms.eq);
}
probe('raise','dry');
probe('raise','wet');
probe('barrel_turn','dry');
probe('cbet','dry');
// 99在K24Q面的真实权益: 对宽范围
console.log('gC btn n=',gC('btn').length);
var ms2=mcVsRange(hole,comm,gC('btn'),2000);
console.log('vs 全btn范围 eq=',ms2.eq);
