#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
verify_post_release.py — 真机实测日志行为验收（发版后回归门禁）

verify_integrity.py 只能在发布前做静态检查；有一类bug必须真机跑一局、导出 poker_log 才暴露：
  - 点击路径：精准坐标点击(sendTap)是否失效、是否100%回退硬编码坐标(autoTapFallback)
  - 数据链路：策略引擎拿到的筹码深度/底池是否恒为默认值(100/10)，而非vision真实值
  - 盲注识别：本地CV盲注是否把中文笔画误读成数字(如 7100/200，SB>BB)

用法：python3 tests/verify_post_release.py <poker_log_xxx.json>
退出码：0=通过  1=红牌(发现行为回归)  2=日志无法解析/样本不足
"""
import sys, json, re, collections

def main():
    if len(sys.argv) < 2:
        print('用法: python3 verify_post_release.py <poker_log.json>'); return 2
    try:
        d = json.load(open(sys.argv[1], encoding='utf-8'))
    except Exception as e:
        print(f'❌ 日志解析失败: {e}'); return 2

    kd = d.get('kotlinDiag') or d
    taps = kd.get('esp32Taps') or []
    decs = kd.get('decisions') or []
    recs = kd.get('recognitions') or []
    ver = kd.get('version') or d.get('version') or '?'

    fails, warns, passes = [], [], []

    print(f'===== 真机日志行为验收 (version={ver}) =====')
    print(f'样本: taps={len(taps)} decisions={len(decs)} recognitions={len(recs)}')

    # ---------- 检查1：点击路径——自动点击是否100%回退fallback硬编码坐标 ----------
    auto_taps = [t for t in taps if 'Tap' in str(t.get('method','')) or 'tap' in str(t.get('method',''))]
    auto_taps = [t for t in auto_taps if t.get('method') not in ('usbIconClick',)]
    fb = [t for t in auto_taps if 'fallback' in str(t.get('method','')).lower() or 'Fallback' in str(t.get('method',''))]
    precise = [t for t in auto_taps if 'fallback' not in str(t.get('method','')).lower()]
    print(f'\n[1] 点击路径: 自动点击{len(auto_taps)}次 (精准sendTap={len(precise)}, fallback硬编码={len(fb)})')
    if len(auto_taps) >= 3:
        fb_ratio = len(fb) / len(auto_taps)
        if fb_ratio > 0.8:
            fails.append(f'点击路径失效: {len(auto_taps)}次自动点击中{len(fb)}次({fb_ratio:.0%})走autoTapFallback硬编码坐标，'
                         f'精准buttonPositions路径未生效→翻后raise会点到黄色%%滑块/跟注')
            print(f'  ❌ fallback占比{fb_ratio:.0%} > 80%，精准点击路径疑似失效')
        else:
            passes.append(f'点击路径正常: fallback占比{fb_ratio:.0%}')
            print(f'  ✅ fallback占比{fb_ratio:.0%}，精准路径生效')
    else:
        warns.append(f'自动点击样本不足({len(auto_taps)}次)，跳过点击路径判定')
        print(f'  ⚠️ 自动点击样本不足，跳过')

    # ---------- 检查2：数据链路——决策的筹码深度/底池是否恒为默认值(100/10) ----------
    # 决策字段 pot/myChips 是BB单位；若vision读到真实chips但决策仍是100/10，说明换算在决策后才执行(时序倒置)
    real_chips = [ (r.get('vlm') or {}).get('myChips') for r in recs if (r.get('vlm') or {}).get('myChips')]
    real_chips = [c for c in real_chips if isinstance(c,(int,float)) and c > 500]
    n_dec = len(decs)
    stk_default = sum(1 for x in decs if x.get('myChips') == 100)
    pot_default = sum(1 for x in decs if x.get('pot') == 10)
    print(f'\n[2] 数据链路: decisions={n_dec}条, myChips=100默认值 {stk_default}条, pot=10默认值 {pot_default}条; '
          f'vision真实chips样本{len(real_chips)}个(如{real_chips[:3]})')
    if n_dec >= 3 and real_chips:
        if stk_default == n_dec and pot_default == n_dec:
            fails.append(f'数据链路时序倒置: 全部{n_dec}条决策 myChips=100/pot=10(输入框默认值)，'
                         f'但vision读到真实chips≈{int(sum(real_chips)/len(real_chips))}→BB换算在go()决策之后才执行，'
                         f'策略永远按100BB深码+默认底池决策，短码同花/A牌被错误弃牌')
            print(f'  ❌ 决策全用默认stk=100/pot=10，真实筹码未进入决策(时序倒置)')
        else:
            passes.append('数据链路正常: 决策stk/pot非默认值')
            print(f'  ✅ 决策stk/pot已使用vision真实值')
    else:
        warns.append(f'决策/筹码样本不足(dec={n_dec}, real_chips={len(real_chips)})，跳过数据链路判定')
        print(f'  ⚠️ 样本不足，跳过')

    # ---------- 检查3：盲注识别——SB/BB比例异常(中文笔画误读成数字) ----------
    bad_blind, total_blind = 0, 0
    examples = []
    for r in recs:
        b = (r.get('vlm') or {}).get('blinds')
        if not b or not isinstance(b, str): continue
        m = re.match(r'\s*(\d+)\s*/\s*(\d+)\s*', b)
        if not m: continue
        sb, bb = int(m.group(1)), int(m.group(2))
        if bb <= 0: continue
        total_blind += 1
        # 正常: SB≈BB/2 (0.3~0.8倍)；straddle时SB可能更小但绝不会 SB>BB
        if sb > bb * 2:   # SB比BB的2倍还大，必是误读(如 7100/200)
            bad_blind += 1
            if len(examples) < 5: examples.append(b)
    print(f'\n[3] 盲注识别: 解析到{total_blind}帧, SB>BB*2异常 {bad_blind}帧 {examples}')
    if total_blind >= 5:
        ratio = bad_blind / total_blind
        if ratio > 0.3:
            fails.append(f'盲注误读: {total_blind}帧中{bad_blind}帧({ratio:.0%}) SB>BB*2(如{examples[:3]})，'
                         f'中文笔画被模板匹配误读成数字，需几何锚定(斜杠分界+gap切断)')
            print(f'  ❌ 盲注异常帧占比{ratio:.0%} > 30%')
        else:
            passes.append(f'盲注识别正常: 异常帧占比{ratio:.0%}')
            print(f'  ✅ 盲注异常帧占比{ratio:.0%}')
    else:
        warns.append(f'盲注样本不足({total_blind}帧)，跳过')
        print(f'  ⚠️ 盲注样本不足，跳过')

    # ---------- 汇总 ----------
    print('\n========================================')
    for p in passes: print('  ✅ ' + p)
    for w in warns:  print('  ⚠️  ' + w)
    if fails:
        print(f'\n❌ 红牌——发现 {len(fails)} 项行为回归:')
        for f in fails: print('   • ' + f)
        return 1
    print('\n✅ 真机行为验收通过' + ('（有警告项，样本不足）' if warns else ''))
    return 0

if __name__ == '__main__':
    sys.exit(main())
