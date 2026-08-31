global.window=global; global.document={getElementById:function(){return{style:{},classList:{add:function(){},remove:function(){},contains:function(){return false;}},querySelector:function(){return null;},appendChild:function(){}};},addEventListener:function(){},createElement:function(){return{style:{},getContext:function(){return null;}};},body:{appendChild:function(){}}};
global.navigator={userAgent:'node'};global.Worker=function(){};global.location={href:'file:///x'};
var src=require('fs').readFileSync(__dirname+'/blk1.js','utf8');
try{ eval(src); }catch(e){ console.log('eval note:',e.message.slice(0,80)); }
DRTA.tracker={hands:100,vpip:30,pfr:20,af:1.5,bluff_attempts:5,recent_agg:[1,1,1],recent:[],fold_to_bet:50,bluff_success_rate:0.5};
function C(r,s){return{rank:r,suit:s};}
function setG(o){ for(var k in o) G[k]=o[k]; }
function lineCaller(){ ActionLine._lines=[];ActionLine.record('preflop','raise','call','raise',45,0);ActionLine.record('flop','raise','call','bet',0,0); }

function dist(bet,label){
  lineCaller();
  setG({phase:'turn',pos:'utg',pot:100,bet:bet,stk:10000,scene:'raise',hole:[C('9','h'),C('9','d')],comm:[C('K','s'),C('2','c'),C('4','h'),C('Q','d')],players:[{active:true,chips:9000},{active:true,chips:9000}]});
  var counts={};
  for(var i=0;i<=100;i++){
    Math.random=function(){return i/100;};
    var r=StrategyEngine.decidePostflop('99');
    counts[r.a]=(counts[r.a]||0)+1;
  }
  var fp=counts.fold||0, cp=counts.call||0, rp=counts.raise||0;
  console.log(label+': raise='+rp+'% call='+cp+'% fold='+fp+'%');
  return {fold:fp,call:cp,raise:rp};
}
var s=dist(30,'小注30%pot');
var b=dist(120,'超池120% ');
console.log('---');
console.log('小注规则效应: fold ' + b.fold + '% → ' + s.fold + '% (应降25pp), call ' + b.call + '% → ' + s.call + '% (应升25pp), raise 不变=' + s.raise + '%');
console.log('MDF校验: 30%pot下注 → MDF应≥71%, 实际防守率(call+raise)=' + (s.call+s.raise) + '%');
console.log('120%pot下注 → pot odds 120/(100+120+120)=37.5% → MDF≈62%, 实际防守率=' + (b.call+b.raise) + '%');
