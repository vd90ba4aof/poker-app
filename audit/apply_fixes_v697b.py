#!/usr/bin/env python3
# apply_fixes_v697b.py — V2.9.697 第二刀: 威胁面对手下注→极化线
# 缺口(实测): 9d9c@4cTc3c 深码大注 → call(eq=54%虚高)。
#   postflopEqSpec 对 flop 面对下注统一用 line='cbet'(宽防守范围,含88/77/66/55中低对),
#   威胁面上这些中低对不会持续投钱 → 把 made hand eq 拉高(中对真实eq~15-25%)→ 错误float。
# 修复: 对手下注(sceneIn!=='check') + 板面同花威胁(bTexture.hasMonotone, 3+张同花) → 切'raise'极化线
#   (JJ+/同花高张/98s+连张/A2s-A5s诈唬代理——中低对剔除,同花键保留)。
#   连牌面(顺子威胁)不切: 顺子组合不受键级稀释(JT任意花色组合在789板均已成顺),eq已真实。
# barrel_turn/barrel_river 线已自带收窄,不重复处理。
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

OLD = "var line=(sceneIn==='check')?'cbet':(bc.length===5?'barrel_river':bc.length===4?'barrel_turn':'cbet');"
NEW = ("var line=(sceneIn==='check')?'cbet':(bc.length===5?'barrel_river':bc.length===4?'barrel_turn':'cbet');\n"
       "    // V2.9.697 FIX(威胁面eq失真#2): 威胁面对手下注→极化线。中低对(88-)不会在monotone面持续投钱,\n"
       "    // 宽cbet范围把它们计入→made hand eq虚高(实测9d9c@4cTc3c深码大注eq54%,真实~15-25%→错误float)。\n"
       "    // 'raise'线=JJ+/同花高张/98s+连张/A2s-A5s诈唬代理(极化建模)。连牌面不切:顺子组合无键级稀释,eq已真实。\n"
       "    try{if(sceneIn!=='check'&&bTexture&&bTexture.hasMonotone&&line==='cbet')line='raise';}catch(_twE){}")

rep(OLD, NEW, '威胁面下注→极化线')

open(PATH, 'w', encoding='utf-8').write(src)
print(f'\n写入完成: {orig_len} → {len(src)} 字节 (+{len(src)-orig_len})')
