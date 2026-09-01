// 验证 postflopIP() 修复效果
try{eval(require('fs').readFileSync('blk1.js','utf8'));}catch(e){}

// stub
if(typeof document==='undefined')global.document={getElementById:()=>({style:{},classList:{add:()=>{},remove:()=>{}}}),querySelectorAll:()=>[],createElement:()=>({style:{},appendChild:()=>{},classList:{add:()=>{},remove:()=>{}}})};
if(typeof window==='undefined')global.window={};
if(typeof navigator==='undefined')global.navigator={userAgent:''};
if(typeof Worker==='undefined')global.Worker=function(){};
if(typeof location==='undefined')global.location={href:''};
if(typeof DRTA==='undefined')global.DRTA={tracker:{track:()=>{}},getProfile:()=>({type:'regular',confidence:0.8}),getWeights:()=>({})};

// 测试 postflopIP()
const positions = ['sb','bb','utg','utg1','mp','mp1','hj','co','btn'];
console.log('postflopIP() 测试：');
for(const pos of positions){
    const ip = postflopIP(pos);
    console.log(`  ${pos.padEnd(5)} → ip=${ip} ${ip?'(IP 有位置优势)':'(OOP 无位置优势)'}`);
}

// 对比旧 pA() 逻辑
console.log('\n旧 pA()>=0.5 逻辑（bug）：');
for(const pos of positions){
    const ip_old = pA(pos) >= 0.5;
    console.log(`  ${pos.padEnd(5)} → ip=${ip_old}`);
}
