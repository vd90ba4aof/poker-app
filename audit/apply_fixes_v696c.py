#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
apply_fixes_v696c.py — V2.9.696 第二轮修正（donk_route_verify D5 实测发现）
D5 实测: hero call flop donk 后面对 turn barrel, turn 序列被紧邻归属规则混入
  flop 街末 OT → isOppRaiseAfterHero(turn) 误 true → turn barrel 误入 _facingCR。
根因: 街末 OT(hero面对下注未响应) 与 hero-先行动 OT 在 _al 结构上不可区分,
  纯 FDE 层无法完美修(拒收会让真CR漏判+donk误路由)。
方案η: FDE 层保持现状(D1-D3 全对), 决策层加 ActionLine 闸(hero本街行动记录,
  每次决策必写[V2.9.588按钮帧守卫], 可靠):
  ① donk 判据加 !ActionLine.getStreetAction(street) — 本街 hero 未行动过才是 donk
  ② _facingCR 入口加 ActionLine.getStreetAction(street) — 本街 hero 行动过才可能是被CR
  ③ 移除冗余 '·Donk' 后缀(_facingCBet 内部已有 isDonk?'面对Donk':'面对CBet' 命名, 行5444/5453/5486)
"""
import sys, io

PATH = '/root/.codebuddy/artifact/poker-app/app/src/main/assets/poker_helper.html'
src = io.open(PATH, encoding='utf-8').read()
applied = []

def rep(old, new, tag, count=1):
    global src
    n = src.count(old)
    if n != count:
        print(f'❌ [{tag}] 原文出现 {n} 次(期望{count}), 中止'); sys.exit(1)
    src = src.replace(old, new)
    applied.append(tag)
    print(f'✅ [{tag}] 已应用')

# ============================================================
# η-1: donk 判据加 ActionLine 闸 + 移除冗余 Donk 后缀
# ============================================================
rep(
  "  var _isDonk=false;\n  try{\n    if(didPFR && !G._facing3bet && scene==='raise' && street!=='preflop' && typeof FrameDiffEngine!=='undefined' && FrameDiffEngine.isOppLeadRaise){\n      _isDonk = FrameDiffEngine.isOppLeadRaise(street);\n    }\n  }catch(e){}\n  if(_isDonk && (street==='flop'||street==='river') && typeof _facingCBet==='function'){\n    try{\n      var _dkR=_facingCBet(k,hcKey,btKey,ip,bet,pot,street,true,eq);\n      if(_dkR&&_dkR.a){if(_dkR.scene)_dkR.scene=_dkR.scene+'·Donk';return _dkR;}\n    }catch(e){}\n  }",
  "  var _isDonk=false;\n  try{\n    // η补丁: !ActionLine.getStreetAction(street) — 本街hero未行动过(donk=对手在hero任何\n    //   行动前领先下注); hero已cbet/check过的街对手再加注属CR路径, 不进donk防御。\n    //   (实测D5: call flop donk后面对turn barrel, 紧邻归属会混入flop街末OT致误判,\n    //    ActionLine闸按hero真实行动精确区分)\n    if(didPFR && !G._facing3bet && scene==='raise' && street!=='preflop' && !ActionLine.getStreetAction(street) && typeof FrameDiffEngine!=='undefined' && FrameDiffEngine.isOppLeadRaise){\n      _isDonk = FrameDiffEngine.isOppLeadRaise(street);\n    }\n  }catch(e){}\n  if(_isDonk && (street==='flop'||street==='river') && typeof _facingCBet==='function'){\n    try{\n      var _dkR=_facingCBet(k,hcKey,btKey,ip,bet,pot,street,true,eq);\n      if(_dkR&&_dkR.a){return _dkR;}\n    }catch(e){}\n  }",
  'η-1 donk判据ActionLine闸+移除冗余Donk后缀'
)

# ============================================================
# η-2: _facingCR 入口加 ActionLine 闸
# ============================================================
rep(
  "      // FIX-13(审计V2.9.696): 修正FIX-8的donk误路由——对手'raise'含donk(先行动下注)与真CR(hero行动后加注)\n      //   两种, 仅后者应进_facingCR。改用isOppRaiseAfterHero精确判据, donk回退收口(与695前一致)\n      var _fcrEnter=(scene==='reraise');\n      if(!_fcrEnter&&scene==='raise'&&typeof FrameDiffEngine!=='undefined'&&FrameDiffEngine.isOppRaiseAfterHero){\n        if(FrameDiffEngine.isOppRaiseAfterHero(street))_fcrEnter=true;\n      }",
  "      // FIX-13(审计V2.9.696): 修正FIX-8的donk误路由——对手'raise'含donk(先行动下注)与真CR(hero行动后加注)\n      //   两种, 仅后者应进_facingCR。改用isOppRaiseAfterHero精确判据, donk回退收口(与695前一致)\n      // η补丁: 加ActionLine闸(本街hero行动过) — 实测D5: call flop下注后面对turn barrel时,\n      //   FDE紧邻归属会把flop街末OT混入turn序列致isOppRaiseAfterHero误true; hero本街\n      //   未行动过则不可能是'被CR'(CR=hero下注/行动后对手反加), ActionLine按hero真实决策记录精确闸断\n      var _fcrEnter=(scene==='reraise');\n      if(!_fcrEnter&&scene==='raise'&&typeof FrameDiffEngine!=='undefined'&&FrameDiffEngine.isOppRaiseAfterHero){\n        if(FrameDiffEngine.isOppRaiseAfterHero(street)&&ActionLine.getStreetAction(street))_fcrEnter=true;\n      }",
  'η-2 面CR入口ActionLine闸'
)

io.open(PATH, 'w', encoding='utf-8').write(src)
print(f'\n=== η补丁 {len(applied)} 项已写入 ===')
