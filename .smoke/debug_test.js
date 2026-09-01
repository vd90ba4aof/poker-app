const fs=require('fs'),vm=require('vm');
const ctx={
  console,setTimeout,setInterval,clearTimeout,clearInterval,
  Date,Math,JSON,Array,Object,String,Number,parseInt,parseFloat,
  Promise,Error,TypeError,RangeError,RegExp,Map,Set,
  document:{getElementById:()=>({style:{},textContent:'',innerHTML:'',appendChild:()=>{},removeChild:()=>{},querySelector:()=>null,querySelectorAll:()=>[],addEventListener:()=>{}}),querySelector:()=>null,querySelectorAll:()=>[],createElement:()=>({style:{},appendChild:()=>{},setAttribute:()=>{},addEventListener:()=>{},querySelectorAll:()=>[],getContext:()=>({fillStyle:'',fillRect:()=>{},clearRect:()=>{},drawImage:()=>{}})}),addEventListener:()=>{},body:{appendChild:()=>{}},readyState:'complete',createTextNode:()=>({}),head:{appendChild:()=>{}},createEvent:()=>({initEvent:()=>{}})},
  window:{addEventListener:()=>{},location:{href:''},setTimeout,setInterval:()=>{},dispatchEvent:()=>{}},
  navigator:{userAgent:'node'},
  Worker:function(){this.postMessage=()=>{};this.terminate=()=>{};this.addEventListener=()=>{};},
  location:{href:''},
  XMLHttpRequest:function(){this.open=()=>{};this.send=()=>{};this.addEventListener=()=>{};this.setRequestHeader=()=>{};},
  fetch:()=>Promise.resolve({json:()=>Promise.resolve({}),text:()=>Promise.resolve('')}),
  localStorage:{getItem:()=>null,setItem:()=>{},removeItem:()=>{}},
  performance:{now:()=>Date.now()},
  AndroidBridge:{showAdvice:()=>{},autoDecision:()=>{},notifyCrash:()=>{}},
  G:null,
  DRTA:{tracker:{track:()=>{},log:()=>{},record:()=>{},setConfidenceLevel:()=>{}},getProfile:()=>({style:'balanced',aggression:0.5}),getWeights:()=>({}),get:()=>null},
  PotConfidence:{level:'high'},TiltDetector:{detectTilt:()=>({})},CounterExploit:{detect:()=>({})},
  SafetyGuard:{dataReliability:()=>'high'},TablePulse:{analyze:()=>({})},FrameDiffEngine:{getOppActionSummary:()=>null}
};
vm.createContext(ctx);
const blk1=fs.readFileSync('blk1.js','utf-8');
const blk2=fs.readFileSync('blk2.js','utf-8');
try{vm.runInContext(blk1,ctx,{timeout:5000});}catch(e){}
try{vm.runInContext(blk2,ctx,{timeout:5000});}catch(e){}

// Minimal test with stack trace
vm.runInContext(`
function mc(r,s){return{rank:r,suit:s};}
function pc(str){return str.match(/(..)/g).map(function(s){return mc(s[0],s[1]);});}

G={
  hole:pc('AsAh'),
  comm:[mc('7','s'),mc('3','h'),mc('2','c'),null,null],
  pot:10,bet:0,stk:100,pos:'btn',act:2,players:2,
  scene:'check',
  _lastPlayers:[{active:true,folded:false,chips:100},{active:true,folded:false,chips:100}],
  _raiserRole:'btn',_raiserPos:'btn',
  is_bomb_pot:false,_faced3bet:false,_heroDid4bet:false,
  tt:4,_seEnabled:true
};

try {
  var r = StrategyEngine.decidePostflop(G);
  console.log('Result:', JSON.stringify(r).substring(0,200));
} catch(e) {
  console.log('ERROR:', e.message);
  console.log('STACK:', e.stack);
}
`, ctx, {timeout:10000});
