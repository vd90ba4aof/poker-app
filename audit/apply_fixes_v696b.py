#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
apply_fixes_v696b.py — V2.9.696 修正补丁（基于 runtime_probe_v696.js 实测发现）
实测发现（8 pass / 4 fail）:
  ① _al 中对手动作(带st)先于 new_street 入列（同帧街转换时序）→ getStreetActs 丢动作
  ② preflop 无 new_street 条目 → 依赖它不可行
  ③ 筹码差分类噪声: 对手 preflop call 被记 raise(tc=0)；对手主动bet完成态被记 call(tc=d)
     → preflop 对手动作不可靠、isOppLeadRaise 需 call/raise 双计入
  ④ _donkDecision 是 hero 主动 donk 攻击决策（scene='check'），原死代码在 scene='raise' 调用属语义错位
修正: R-1 getStreetActs 重写 / R-2 isOppLeadRaise 鲁棒化 / R-3 FIX-12a donk 块重写
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
# R-1 + R-2: getStreetActs 重写(时序归属) + isOppLeadRaise 鲁棒化
# ============================================================
rep(
  "  // V2.9.696 FIX(SR-1a): 本街动作序列(含our_turn)——街语义精确判据的基础\n  getStreetActs:function(st){var f=false;var sa=[];for(var i=0;i<this._al.length;i++){var a=this._al[i];if(a.a==='new_street'&&a.st===st){f=true;sa=[];continue;}if(f&&(a.a==='fold'||a.a==='call'||a.a==='raise'||a.a==='our_turn'))sa.push(a.a);}return f?sa:null;},\n  // 对手先于hero行动且加注(领先下注: donk或CBet, 区分靠对手是否翻前加注者)\n  isOppLeadRaise:function(st){var sa=this.getStreetActs(st);if(!sa)return false;for(var i=0;i<sa.length;i++){if(sa[i]==='raise')return true;if(sa[i]==='our_turn'||sa[i]==='fold'||sa[i]==='call')return false;}return false;},",
  "  // V2.9.696 FIX(SR-1a): 本街动作序列(含our_turn)——街语义精确判据的基础\n  // 实测修正: _al中对手动作(带st字段)先于new_street入列(同帧街转换时序), 故以条目自带st\n  //   归属为主、new_street区间为辅; our_turn无st字段, 按当前区间或紧随其后的new_street归属。\n  getStreetActs:function(st){var sa=[],inSt=false,found=false;for(var i=0;i<this._al.length;i++){var a=this._al[i];if(a.a==='new_street'){inSt=(a.st===st);if(inSt)found=true;continue;}if(a.a==='fold'||a.a==='call'||a.a==='raise'){if(a.st===st){sa.push(a.a);found=true;}}else if(a.a==='our_turn'){if(inSt)sa.push(a.a);else{var nx=(i+1<this._al.length)?this._al[i+1]:null;if(nx&&nx.a==='new_street'&&nx.st===st){sa.push(a.a);found=true;}}}return found?sa:null;},\n  // 对手先于hero行动而下注(领先下注: donk或CBet)。\n  // 实测修正: 筹码差推断把对手下注记为call(动作完成态, d<=tc+p*0.1)或raise(tc滞后帧),\n  //   故call/raise记录均计入\"投入\"; fold/our_turn视为\"非投入\"截断。\n  isOppLeadRaise:function(st){var sa=this.getStreetActs(st);if(!sa)return false;for(var i=0;i<sa.length;i++){if(sa[i]==='call'||sa[i]==='raise')return true;if(sa[i]==='our_turn'||sa[i]==='fold')return false;}return false;},",
  'R-1/R-2 getStreetActs重写+isOppLeadRaise鲁棒化'
)

# ============================================================
# R-3: FIX-12a donk 块重写
#   - 入口翻转: !didPFR → didPFR && !G._facing3bet (hero为翻前最后加注者)
#   - 移除 preflop 对手动作依赖 (实测: 筹码差噪声使其不可靠)
#   - 移除 _donkDecision 调用 (语义错位: hero主动donk攻击决策不适用于面对下注场景)
#   - 仅保留 _facingCBet(isDonk=true) 防御路由
# ============================================================
rep(
  "  // ★ V3.11: Flop/River面对下注 — 补全场景覆盖\n  // V2.9.696 FIX(SR-1b/BUG#8): donk检测复活——旧条件_fdA==='bet'永假+_facedDonk死flag致_isDonk恒false,\n  //   donk场景被误当面CBet(对手被假设为PFR范围, 实际donk范围更两极)。\n  //   新判据: 对手领先下注(isOppLeadRaise) 且 对手非翻前加注者(翻前getOppPostflopAction!=='raise', 数据缺失时保守不判)\n  var _isDonk=false;\n  try{\n    if(!didPFR && scene==='raise' && street!=='preflop' && typeof FrameDiffEngine!=='undefined' && FrameDiffEngine.isOppLeadRaise){\n      var _oppPreRA=FrameDiffEngine.getOppPostflopAction('preflop');\n      _isDonk = FrameDiffEngine.isOppLeadRaise(street) && _oppPreRA!==null && _oppPreRA!=='raise';\n    }\n  }catch(e){}\n  if(!_isDonk && !didPFR && scene==='raise' && (street==='flop'||street==='river') && typeof _facingCBet==='function'){\n    try{\n      var _fcbR=_facingCBet(k,hcKey,btKey,ip,bet,pot,street,false,eq);\n      if(_fcbR&&_fcbR.a){return _fcbR;}\n    }catch(e){}\n  }\n  if(_isDonk){\n    // 1) GTO donk-raise 频率掷骰(原V3.12死代码复活)\n    if(typeof _donkDecision==='function'){\n      try{\n        var _dkR=_donkDecision(k,hcKey,btKey,ip,pot,oppType,null,_is3betPot,_isMultiway,street,eq);\n        if(_dkR&&_dkR.a){return _dkR;}\n      }catch(e){}\n    }\n    // 2) donk调整的防御: _facingCBet(isDonk=true)——对手donk范围偏两极→更call(见其内部isDonk调整)\n    if((street==='flop'||street==='river') && typeof _facingCBet==='function'){\n      try{\n        var _dkFR=_facingCBet(k,hcKey,btKey,ip,bet,pot,street,true,eq);\n        if(_dkFR&&_dkFR.a){return _dkFR;}\n      }catch(e){}\n    }\n    // turn的donk掷骰未中→落后续Turn防御表(现状语义, 不扩展)\n  }",
  "  // ★ V3.11: Flop/River面对下注 — 补全场景覆盖\n  // V2.9.696 FIX(SR-1b/BUG#8): hero PFR面对对手donk的防御路由(原V3.12块为死代码, 且其在\n  //   scene==='raise'(hero面对下注)调用_donkDecision(hero主动donk攻击决策, 适用scene==='check',\n  //   参见Turn probe的正确用法)属语义错位, 不予复活)。\n  //   新判据: hero为翻前最后加注者(didPFR且!G._facing3bet——open未被3bet/hero加注后被call)\n  //   + 对手本街先于hero行动而下注(isOppLeadRaise, call/raise记录均计入——分类噪声鲁棒)\n  //   = hero面对donk → _facingCBet(isDonk=true): 其isDonk形参的\"donk范围偏两极→更call\"\n  //   调整(行内 adjC+adjF*0.25)自V3.12以来首次实际生效。\n  var _isDonk=false;\n  try{\n    if(didPFR && !G._facing3bet && scene==='raise' && street!=='preflop' && typeof FrameDiffEngine!=='undefined' && FrameDiffEngine.isOppLeadRaise){\n      _isDonk = FrameDiffEngine.isOppLeadRaise(street);\n    }\n  }catch(e){}\n  if(_isDonk && (street==='flop'||street==='river') && typeof _facingCBet==='function'){\n    try{\n      var _dkR=_facingCBet(k,hcKey,btKey,ip,bet,pot,street,true,eq);\n      if(_dkR&&_dkR.a){if(_dkR.scene)_dkR.scene=_dkR.scene+'·Donk';return _dkR;}\n    }catch(e){}\n  }\n  if(!didPFR && scene==='raise' && (street==='flop'||street==='river') && typeof _facingCBet==='function'){\n    try{\n      var _fcbR=_facingCBet(k,hcKey,btKey,ip,bet,pot,street,false,eq);\n      if(_fcbR&&_fcbR.a){return _fcbR;}\n    }catch(e){}\n  }",
  'R-3 FIX-12a donk块重写(didPFR入口+_facingCBet防御路由)'
)

io.open(PATH, 'w', encoding='utf-8').write(src)
print(f'\n=== 修正补丁 {len(applied)} 项已写入 ===')
