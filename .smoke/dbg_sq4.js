global.window=global; global.document={getElementById:function(){return{style:{},classList:{add:function(){},remove:function(){},contains:function(){return false;}},querySelector:function(){return null;},appendChild:function(){}};},addEventListener:function(){},createElement:function(){return{style:{},getContext:function(){return null;}};},body:{appendChild:function(){}}};
global.navigator={userAgent:'node'};global.Worker=function(){};global.location={href:'file:///x'};
var src=require('fs').readFileSync(__dirname+'/blk1.js','utf8');
try{ eval(src); }catch(e){}
DRTA.tracker={hands:100,vpip:30,pfr:20,af:1.5,bluff_attempts:5,recent_agg:[1,1,1],recent:[],fold_to_bet:50,bluff_success_rate:0.5};
var sq=StrategyEngine.getSqueezeTable();
console.log('squeeze table keys:', Object.keys(sq));
console.log('vs_UTG1_vs_caller keys:', Object.keys(sq.vs_UTG1_vs_caller));
console.log('BTN AA:', JSON.stringify(sq.vs_UTG1_vs_caller.BTN.AA));
// 现在关键: raiserRole='utg1' 时 _pos5 返回什么? 用3b表现反推
G.pos='btn';G.scene='raise';G.stk=20000;G.pot=900;G.bet=600;G.limpers=0;G._numCallersBefore=1;G._raiserRole='utg1';G.opp='unknown';G.ante=0;G.tt=6;G.hole=[{rank:'A',suit:'s'},{rank:'A',suit:'h'}];G.comm=[];
var _or=Math.random;Math.random=function(){return 0;};
var r=StrategyEngine.decidePreflop('AA');
Math.random=_or;
console.log('RAISER=utg1 →', r.r);
// 试 raiserRole='utg'
G._raiserRole='utg';
Math.random=function(){return 0;};
var r2=StrategyEngine.decidePreflop('AA');
Math.random=_or;
console.log('RAISER=utg →', r2.scene, r2.r);
