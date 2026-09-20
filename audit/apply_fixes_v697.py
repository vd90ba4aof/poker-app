#!/usr/bin/env python3
# apply_fixes_v697.py — V2.9.697 威胁板面组合级重加权(同花威胁稀释修正)
# 根因(实测): 键级范围('XXs'按4花色均匀展开)在威胁面把已成同花组合稀释~4倍:
#   CB2.btn 55手(31同花键)@8h7h9h → 352组合中仅31个已成同花(8.8%);
#   对照实验: vs CB2.btn 72.6% / vs 纯对子 73.2% / vs 全同花键 74.3% —— 范围含同花与否eq几乎不变
#   真实对手在威胁面的行动范围是花色条件化的(持Ah打/持As弃),已成同花真实占比25%+
# 修复: mcVsRange(MC)与riverExactEquity(枚举)双路径统一调用 _threatReweight:
#   - 双威胁花色(已成同花) ×4
#   - 单威胁花色高张(坚果/近坚果听) ×2; board≥4张同花时单张=已成 ×4
#   - board 5张同花不加权(kicker决定,分布已合理)
# Worker: _threatReweight.toString() 追加注入(否则Worker内ReferenceError永久降级)
import sys

PATH = 'app/src/main/assets/poker_helper.html'
src = open(PATH, encoding='utf-8').read()
orig_len = len(src)

def rep(old, new, tag):
    global src
    n = src.count(old)
    assert n == 1, f'[{tag}] 锚点出现次数 {n} != 1, 中止'
    src = src.replace(old, new)
    print(f'[OK] {tag}')

# ---------- Fix 1: 新增 _threatReweight 公共函数(插在 riverExactEquity 后、_mcCache 声明注释前) ----------
FUNC = '''// V2.9.697 FIX(威胁面eq失真): 威胁板面组合级重加权——修正键级范围的同花威胁组合稀释
// 根因: 键级范围('XXs'按4花色均匀展开)在威胁面把已成同花组合稀释~4倍——
//   实测 8c8d@8h7h9h(set) vs CB2.btn(含31同花键,352组合): eq=72.6%,其中已成同花组合仅31个(8.8%);
//   对照: vs 纯对子范围73.2% / vs 全同花键范围74.3% —— 键级展开下"范围含不含同花键"对eq几乎无影响,
//   因为'AJs'的4个组合里只有AhJh在monotone面已成同花(25%),持AsJs的对手根本不会继续投钱。
//   真实对手在威胁面的行动范围是花色条件化的: 已成同花组合真实占比25%+ → made hand eq虚高(72.6% vs 真实~60-65%)
//   → set/顶对/两对等在威胁面的精细决策(call/fold边界、薄价值加注线)全偏激进。
// 修复: 按板面威胁对组合级分布重加权,还原"行动条件化"后的真实组合分布:
//   - 双威胁花色组合(已成同花) ×4: 8.8%→~25%
//   - 单威胁花色+高张(≥板面最高rank-2,坚果/近坚果听) ×2: 对手持Ah会继续打
//   - board≥4张同花: 单张威胁花色=已成同花 ×4
//   - board 5张同花: 不加权(板面自成同花,kicker决定,键级分布已合理)
// 落点: mcVsRange(MC路径)与riverExactEquity(精确枚举路径)统一调用,Worker经toString注入同口径。
// 注: 顺子威胁不受键级稀释(JT任意花色组合在789板均已成顺,展开自然覆盖);hero阻断由组合过滤自然处理。
function _threatReweight(oppHands,comm){
  try{
    if(!oppHands||!oppHands.length||!comm)return oppHands;
    var cOK=comm.filter(function(x){return x&&x.suit;});
    if(cOK.length<3)return oppHands;
    var sc={};for(var i=0;i<cOK.length;i++)sc[cOK[i].suit]=(sc[cOK[i].suit]||0)+1;
    var thr=null;for(var s in sc){if(sc[s]>=3&&sc[s]<5){thr=s;break;}}
    if(!thr)return oppHands;
    var hiRV=0;for(var b2=0;b2<cOK.length;b2++){var rv2=RV[cOK[b2].rank];if(rv2!==undefined&&rv2>hiRV)hiRV=rv2;}
    var out=[];
    for(var j=0;j<oppHands.length;j++){
      var h=oppHands[j];
      var c0h=h[0]&&h[0].suit===thr,c1h=h[1]&&h[1].suit===thr;
      if(c0h&&c1h){out.push(h,h,h,h);}
      else if(c0h||c1h){
        if(sc[thr]>=4){out.push(h,h,h,h);}
        else{var hv2=Math.max(RV[h[0].rank]||-1,RV[h[1].rank]||-1);if(hv2>=hiRV-2)out.push(h,h);else out.push(h);}
      }else{out.push(h);}
    }
    return out;
  }catch(e){return oppHands;}
}
'''
rep('// V2.9.600 精度债#3: 空范围兜底洗整副牌取对手牌',
    FUNC + '// V2.9.600 精度债#3: 空范围兜底洗整副牌取对手牌',
    'Fix1 新增 _threatReweight 函数')

# ---------- Fix 2: mcVsRange 调用(单行压缩体内) ----------
rep('{oppHands.push(h);}}}var w=0,t=0;var hv=eH(',
    '{oppHands.push(h);}}}oppHands=_threatReweight(oppHands,comm);var w=0,t=0;var hv=eH(',
    'Fix2 mcVsRange 注入重加权')

# ---------- Fix 3: riverExactEquity 调用(精确枚举路径) ----------
rep('var myBest=e5([].concat(hole,comm));',
    'oppHands=_threatReweight(oppHands,comm);var myBest=e5([].concat(hole,comm));',
    'Fix3 riverExactEquity 注入重加权')

# ---------- Fix 4: Worker 注入 _threatReweight(否则Worker内mcVsRange调用它时ReferenceError→永久降级) ----------
rep("+(typeof mcVsRange==='function'?mcVsRange.toString()+'\\n':'')",
    "+(typeof _threatReweight==='function'?_threatReweight.toString()+'\\n':'')  // V2.9.697: 威胁重加权随mcVsRange注入Worker\n  +(typeof mcVsRange==='function'?mcVsRange.toString()+'\\n':'')",
    'Fix4 Worker 注入 _threatReweight')

# ---------- Fix 5: 版本号(SR-3 单一来源) ----------
rep("var ENGINE_VERSION='2.9.696';",
    "var ENGINE_VERSION='2.9.697';",
    'Fix5 ENGINE_VERSION → 2.9.697')

open(PATH, 'w', encoding='utf-8').write(src)
print(f'\n写入完成: {orig_len} → {len(src)} 字节 (+{len(src)-orig_len})')
