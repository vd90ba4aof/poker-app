// V2.9.693 FIX(P7a/P7b/P7c) 回归测试
// P7a: 三条(trips)踢脚质量闸 —— 仅"手牌1张+板面对子"情形适用
// P7b: 底葫芦(minFullHouse)不再无条件NUTS
// P7c: set 补齐对子面/连牌面威胁检查
'use strict';
const { sandbox, vm } = require('./harness.js');
function C(s){return vm.runInContext(s, sandbox);}

function cls(hole,board){
  const h=hole.map(c=>({rank:c[0],suit:c[1]}));
  const b=board.map(c=>({rank:c[0],suit:c[1]}));
  return JSON.parse(C(`JSON.stringify(handClassify(${JSON.stringify(h)},${JSON.stringify(b)}))`));
}

let pass=0, fail=0;
function check(name, cond, detail){
  if(cond){pass++;console.log('  ✓ '+name);}
  else{fail++;console.log('  ✗ '+name+' | '+(detail||''));}
}
function name(h,b){return cls(h,b).name;}

// ===== P7a-1: 日志铁证手牌 —— 低踢三条必须降级 =====
console.log('\n===== P7a-1: 日志手5-8 Qh5h@QdJcQc (板对+hero单张) =====');
{
  const c=cls(['Qh','5h'],['Qd','Jc','Qc']);
  console.log('  Qh5h → '+c.name+' | '+(c.desc||''));
  check('P7a Qh5h 低踢三条降级(修复前NUTS)', c.name==='STRONG', c.name);
  const c2=cls(['Qh','2h'],['Qd','Jc','Qc']);
  console.log('  Qh2h → '+c2.name+' | '+(c2.desc||''));
  check('P7a Qh2h 低踢三条降级(修复前NUTS)', c2.name==='STRONG', c2.name);
}

// ===== P7a-2: 高踢三条必须保持 NUTS(不得过度降级) =====
console.log('\n===== P7a-2: 高踢三条保持NUTS =====');
for(const [n,h] of [['QhAh',['Qh','Ah']],['QhKh',['Qh','Kh']],['QhJh',['Qh','Jh']]]){
  const c=cls(h,['Qd','Jc','Qc']);
  console.log('  '+n+' → '+c.name);
  check('P7a '+n+' 高踢保持NUTS', c.name==='NUTS', c.name);
}

// ===== P7a-3: 情形A(手牌对子+板面1张)必须保持NUTS =====
console.log('\n===== P7a-3: 自纠——手牌对子+板面单张 不得误降 =====');
const CASE_A=[
  ['8h8d @2c8s9h', ['8h','8d'], ['2c','8s','9h']],
  ['7h7d @7sKdQs', ['7h','7d'], ['7s','Kd','Qs']],
  ['5h5d @5sAdKh', ['5h','5d'], ['5s','Ad','Kh']],
];
for(const [n,h,b] of CASE_A){
  const c=cls(h,b);
  console.log('  '+n+' → '+c.name+' | '+(c.desc||''));
  check('P7a-3 '+n+' 保持NUTS(对手不可能成同三条)', c.name==='NUTS', c.name);
}

// ===== P7b: 底葫芦 =====
console.log('\n===== P7b: 底葫芦降级 / 真葫芦保持NUTS =====');
{
  const c=cls(['5h','5d'],['Ks','Kh','Qd','Qc','5s']);
  console.log('  5h5d @KsKhQdQc5s (555+QQ底葫芦) → '+c.name+' | '+(c.desc||''));
  check('P7b 底葫芦降级STRONG(修复前NUTS)', c.name==='STRONG', c.name);
  const c2=cls(['Qh','Td'],['Ks','Kh','7d','Qc','Qs']);
  console.log('  QhTd @KsKh7dQcQs (QQQ+KK,日志手1) → '+c2.name+' | '+(c2.desc||''));
  check('P7b 日志手1底葫芦降级STRONG', c2.name==='STRONG', c2.name);
}
for(const [n,h,b] of [
  ['KsKd @Kh7d7c2s3h (KKK+77真葫芦)', ['Ks','Kd'], ['Kh','7d','7c','2s','3h']],
  ['AhAd @As7d7c2s3h (AAA+77真葫芦)', ['Ah','Ad'], ['As','7d','7c','2s','3h']],
]){
  const c=cls(h,b);
  console.log('  '+n+' → '+c.name);
  check('P7b '+n+' 保持NUTS', c.name==='NUTS', c.name);
}

// ===== P7c: set 对子面威胁(板对rank>三条rank) =====
console.log('\n===== P7c: 三条·对子面威胁 =====');
{
  const c=cls(['7h','7d'],['Ks','Kh','7d','Qc','2s']);
  console.log('  7h7d @KsKh7dQc2s (板对K>三条7 → 实为葫芦) → '+c.name);
  check('P7c 板对>三条 降级', c.name==='STRONG', c.name);
  const c2=cls(['Qh','5h'],['Qd','Jc','Qc']);
  console.log('  Qh5h @QdJcQc (板对Q=三条Q) → '+c2.name);
  check('P7c 板对=三条 不重复降级(已由P7a处理)', c2.name==='STRONG', c2.name);
}

// ===== 回归: 四条/同花顺仍NUTS =====
console.log('\n===== 回归: 四条/同花顺仍NUTS =====');
{
  const c=cls(['Ks','Kd'],['Kh','Kc','2s','3h','4d']);
  console.log('  KsKd @KhKc2s3h4d (四条K) → '+c.name);
  check('回归 四条保持NUTS', c.name==='NUTS', c.name);
  const c2=cls(['9h','8h'],['Th','Jh','Qh','7h','2s']);
  console.log('  9h8h @ThJhQh7h2s (同花顺) → '+c2.name);
  check('回归 同花顺保持NUTS', c2.name==='NUTS', c2.name);
}

// ===== 计数自洽 =====
console.log('\n===== 计数自洽 =====');
for(const [n,h,b] of [['Qh5h',['Qh','5h'],['Qd','Jc','Qc']],['8h8d',['8h','8d'],['2c','8s','9h']]]){
  const c=cls(h,b);
  check('计数自洽 '+n, typeof c.name==='string'&&c.name.length>0, JSON.stringify(c));
}

console.log('\n[V2.9.693 P7] 总计: pass='+pass+' fail='+fail);
process.exit(fail>0?1:0);
