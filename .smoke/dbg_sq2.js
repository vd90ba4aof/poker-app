global.window=global; global.document={getElementById:function(){return{style:{},classList:{add:function(){},remove:function(){},contains:function(){return false;}},querySelector:function(){return null;},appendChild:function(){}};},addEventListener:function(){},createElement:function(){return{style:{},getContext:function(){return null;}};},body:{appendChild:function(){}}};
global.navigator={userAgent:'node'};global.Worker=function(){};global.location={href:'file:///x'};
var src=require('fs').readFileSync(__dirname+'/blk1.js','utf8');
try{ eval(src); }catch(e){ console.log('eval note:',e.message.slice(0,60)); }
DRTA.tracker={hands:100,vpip:30,pfr:20,af:1.5,bluff_attempts:5,recent_agg:[1,1,1],recent:[],fold_to_bet:50,bluff_success_rate:0.5};
// 直接在源码上下文测试 squeeze 段逻辑
G.pos='btn';G.scene='raise';G.stk=20000;G.pot=900;G.bet=600;G.limpers=0;G._numCallersBefore=1;G._raiserRole='utg1';G.opp='unknown';G.ante=0;G.tt=6;G.hole=[{rank:'A',suit:'s'},{rank:'A',suit:'h'}];G.comm=[];
var _or=Math.random;Math.random=function(){return 0;};
// 临时插桩: 重写console.log看squeeze日志
var r=StrategyEngine.decidePreflop('AA');
Math.random=_or;
console.log('RESULT:', r&&r.a, r&&r.scene, r&&r.r);
console.log('_nashEffBB would be:', Math.round(20000*1.5/900));
