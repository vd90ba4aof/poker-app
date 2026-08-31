global.window=global; global.document={getElementById:function(){return{style:{},classList:{add:function(){},remove:function(){},contains:function(){return false;}},querySelector:function(){return null;},appendChild:function(){}};},addEventListener:function(){},createElement:function(){return{style:{},getContext:function(){return null;}};},body:{appendChild:function(){}}};
global.navigator={userAgent:'node'};global.Worker=function(){};global.location={href:'file:///x'};
var src=require('fs').readFileSync(__dirname+'/blk1.js','utf8');
try{ eval(src); }catch(e){ console.log('eval note:',e.message.slice(0,60)); }
DRTA.tracker={hands:100,vpip:30,pfr:20,af:1.5,bluff_attempts:5,recent_agg:[1,1,1],recent:[],fold_to_bet:50,bluff_success_rate:0.5};
G.pos='btn';G.scene='raise';G.stk=20000;G.pot=900;G.bet=600;G.limpers=0;G._numCallersBefore=1;G._raiserRole='utg1';G.opp='unknown';G.ante=0;G.tt=6;G.hole=[{rank:'A',suit:'s'},{rank:'A',suit:'h'}];G.comm=[];
console.log('_pos5(utg1)=', _pos5('utg1'));
console.log('p in [btn,sb,bb]=', ['btn','sb','bb'].indexOf('btn'));
console.log('_SQUEEZE keys=', Object.keys(_SQUEEZE));
var t=_SQUEEZE['vs_UTG1_vs_caller'];
console.log('table keys=', t?Object.keys(t):'NULL');
console.log('BTN.AA=', t&&t.BTN?t.BTN.AA:'no BTN');
