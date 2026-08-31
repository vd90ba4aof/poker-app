global.window=global; global.document={getElementById:function(){return{style:{},classList:{add:function(){},remove:function(){},contains:function(){return false;}},querySelector:function(){return null;},appendChild:function(){}};},addEventListener:function(){},createElement:function(){return{style:{},getContext:function(){return null;}};},body:{appendChild:function(){}}};
global.navigator={userAgent:'node'};global.Worker=function(){};global.location={href:'file:///x'};
var src=require('fs').readFileSync(__dirname+'/blk1.js','utf8');
try{ eval(src); }catch(e){}
DRTA.tracker={hands:100,vpip:30,pfr:20,af:1.5,bluff_attempts:5,recent_agg:[1,1,1],recent:[],fold_to_bet:50,bluff_success_rate:0.5};
function C(r,s){return{rank:r,suit:s};}
var comm=[C('K','s'),C('2','c'),C('4','h'),C('Q','d')], hole=[C('9','h'),C('9','d')];
var bt=boardTexture(comm);
console.log('texture category=',bt.category,'wetness=',bt.wetness);
var hc=handClassify(hole,comm);
console.log('hClass=',hc.name,'outs=',hc.outs);
for(var t of ['0','2','3','4','5']){
  var td=StrategyEngine.getTurnDefense(t);
  if(td) console.log('btKey',t,'key6=',td[6],'key4=',td[4],'key9=',td[9]);
}
console.log('pA(bb)=',pA('bb'));
console.log('getCurrentStreet=',getCurrentStreet());
// 找全局bt2key函数(非IIFE内)
console.log('typeof _bt2keyNewTable=',typeof _bt2keyNewTable,' typeof _hc2keySafe=',typeof _hc2keySafe,' typeof _hc2key=',typeof _hc2key,' typeof _bt2key=',typeof _bt2key);
