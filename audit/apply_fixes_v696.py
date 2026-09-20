#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
apply_fixes_v696.py — V2.9.695 → V2.9.696 短期升级修复
SR-1 街语义统一: FrameDiffEngine精确判据 + donk检测复活(BUG#8) + FIX-8 donk误路由修正
SR-3 版本号单一来源: ENGINE_VERSION 派生 CacheManager.CURRENT_VERSION / APP_VERSION
每处替换前断言原文存在且唯一, 任何一步失败即中止(不产生半成品)
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
# FIX-10 (SR-3): 版本号单一来源 — ENGINE_VERSION 常量
# 定义于 CacheManager(1394)与APP_VERSION(2953)之前, 两处派生引用
# ============================================================
rep(
  "// V2.9.46: 缓存管理——版本升级时自动清理旧缓存\nvar CacheManager={\n  VERSION_KEY:'poker_cache_version',\n  CURRENT_VERSION:'2.9.695',",
  "// V2.9.696 FIX(SR-3): 版本号单一来源——ENGINE_VERSION为唯一字面量, CacheManager/APP_VERSION派生\n// (build.gradle与UI横幅仍需手动同步, 见审计报告)\nvar ENGINE_VERSION='2.9.696';\n// V2.9.46: 缓存管理——版本升级时自动清理旧缓存\nvar CacheManager={\n  VERSION_KEY:'poker_cache_version',\n  CURRENT_VERSION:ENGINE_VERSION,",
  'FIX-10a 版本单一来源: ENGINE_VERSION常量+CURRENT_VERSION派生'
)
rep(
  "var APP_VERSION = '2.9.695';",
  "var APP_VERSION = ENGINE_VERSION;",
  'FIX-10b APP_VERSION派生自ENGINE_VERSION'
)

# ============================================================
# FIX-11 (SR-1a): FrameDiffEngine 新增街语义精确判据
# getOppPostflopAction 只返回本街最后动作, 无法区分:
#   donk(对手先于hero行动下注) vs 真CR(hero行动后对手加注)
# 新增三个方法基于 _al 动作序列(含our_turn)精确判定
# ============================================================
rep(
  "  getOppPostflopAction:function(st){var f=false;var sa=[];for(var i=0;i<this._al.length;i++){var a=this._al[i];if(a.a==='new_street'&&a.st===st)f=true;if(f&&(a.a==='fold'||a.a==='call'||a.a==='raise'))sa.push(a);}return sa.length>0?sa[sa.length-1].a:null;},",
  "  getOppPostflopAction:function(st){var f=false;var sa=[];for(var i=0;i<this._al.length;i++){var a=this._al[i];if(a.a==='new_street'&&a.st===st)f=true;if(f&&(a.a==='fold'||a.a==='call'||a.a==='raise'))sa.push(a);}return sa.length>0?sa[sa.length-1].a:null;},\n  // V2.9.696 FIX(SR-1a): 本街动作序列(含our_turn)——街语义精确判据的基础\n  getStreetActs:function(st){var f=false;var sa=[];for(var i=0;i<this._al.length;i++){var a=this._al[i];if(a.a==='new_street'&&a.st===st){f=true;sa=[];continue;}if(f&&(a.a==='fold'||a.a==='call'||a.a==='raise'||a.a==='our_turn'))sa.push(a.a);}return f?sa:null;},\n  // 对手先于hero行动且加注(领先下注: donk或CBet, 区分靠对手是否翻前加注者)\n  isOppLeadRaise:function(st){var sa=this.getStreetActs(st);if(!sa)return false;for(var i=0;i<sa.length;i++){if(sa[i]==='raise')return true;if(sa[i]==='our_turn'||sa[i]==='fold'||sa[i]==='call')return false;}return false;},\n  // hero已行动(our_turn)后对手加注 = 真check-raise/再加注\n  isOppRaiseAfterHero:function(st){var sa=this.getStreetActs(st);if(!sa)return false;var seen=false;for(var i=0;i<sa.length;i++){if(sa[i]==='our_turn')seen=true;else if(sa[i]==='raise'&&seen)return true;}return false;},",
  'FIX-11 FrameDiffEngine街语义判据(getStreetActs/isOppLeadRaise/isOppRaiseAfterHero)'
)

# ============================================================
# FIX-12 (SR-1b): donk 检测复活(BUG#8) + donk 防御路由
# 根因: _fdA==='bet'永假(FrameDiff只产fold/call/raise) + G._facedDonk无赋值点
#   → _isDonk恒false → _donkDecision不可达 + _facingCBet的isDonk调整恒false
# 修复: 精确判据(对手领先下注 且 对手非翻前加注者) + donk专用路由
# ============================================================
rep(
  "  // ★ V3.11: Flop/River面对下注 — 补全场景覆盖\n  if(!didPFR && scene==='raise' && (street==='flop'||street==='river') && typeof _facingCBet==='function'){\n    try{\n      var _fcbR=_facingCBet(k,hcKey,btKey,ip,bet,pot,street,false,eq);\n      if(_fcbR&&_fcbR.a){return _fcbR;}\n    }catch(e){}\n  }",
  "  // ★ V3.11: Flop/River面对下注 — 补全场景覆盖\n  // V2.9.696 FIX(SR-1b/BUG#8): donk检测复活——旧条件_fdA==='bet'永假+_facedDonk死flag致_isDonk恒false,\n  //   donk场景被误当面CBet(对手被假设为PFR范围, 实际donk范围更两极)。\n  //   新判据: 对手领先下注(isOppLeadRaise) 且 对手非翻前加注者(翻前getOppPostflopAction!=='raise', 数据缺失时保守不判)\n  var _isDonk=false;\n  try{\n    if(!didPFR && scene==='raise' && street!=='preflop' && typeof FrameDiffEngine!=='undefined' && FrameDiffEngine.isOppLeadRaise){\n      var _oppPreRA=FrameDiffEngine.getOppPostflopAction('preflop');\n      _isDonk = FrameDiffEngine.isOppLeadRaise(street) && _oppPreRA!==null && _oppPreRA!=='raise';\n    }\n  }catch(e){}\n  if(!_isDonk && !didPFR && scene==='raise' && (street==='flop'||street==='river') && typeof _facingCBet==='function'){\n    try{\n      var _fcbR=_facingCBet(k,hcKey,btKey,ip,bet,pot,street,false,eq);\n      if(_fcbR&&_fcbR.a){return _fcbR;}\n    }catch(e){}\n  }\n  if(_isDonk){\n    // 1) GTO donk-raise 频率掷骰(原V3.12死代码复活)\n    if(typeof _donkDecision==='function'){\n      try{\n        var _dkR=_donkDecision(k,hcKey,btKey,ip,pot,oppType,null,_is3betPot,_isMultiway,street,eq);\n        if(_dkR&&_dkR.a){return _dkR;}\n      }catch(e){}\n    }\n    // 2) donk调整的防御: _facingCBet(isDonk=true)——对手donk范围偏两极→更call(见其内部isDonk调整)\n    if((street==='flop'||street==='river') && typeof _facingCBet==='function'){\n      try{\n        var _dkFR=_facingCBet(k,hcKey,btKey,ip,bet,pot,street,true,eq);\n        if(_dkFR&&_dkFR.a){return _dkFR;}\n      }catch(e){}\n    }\n    // turn的donk掷骰未中→落后续Turn防御表(现状语义, 不扩展)\n  }",
  'FIX-12a donk检测复活+防御路由(_facingCBet调用块改造)'
)
rep(
  "  // ★ V3.12: Donk Bet (对手OOP主动下注)\n  // V3.32: 用FrameDiffEngine检测donk（_facedDonk无人设置，死flag）\n  var _isDonk=false;\n  try{\n    if(!didPFR && scene==='raise' && street!=='preflop'){\n      var _fdA=typeof FrameDiffEngine!=='undefined'?FrameDiffEngine.getOppPostflopAction(street):null;\n      // 翻牌圈对手先下注=donk; 或ActionLine记录对手先行动\n      _isDonk = (_fdA&&_fdA==='bet') || G._facedDonk===true;\n    }\n  }catch(e){}\n  if(!didPFR && scene==='raise' && typeof _donkDecision==='function' && _isDonk){\n    try{\n      var _donkR=_donkDecision(k,hcKey,btKey,ip,pot,oppType,null,_is3betPot,_isMultiway,street,eq);\n      if(_donkR&&_donkR.a){return _donkR;}\n    }catch(e){}\n  }\n\n  // ★ V3.0: Turn防御 — OOP面对CBet",
  "  // ★ V3.0: Turn防御 — OOP面对CBet",
  'FIX-12b 移除原donk死代码块(功能已由FIX-12a前置接管)'
)

# ============================================================
# FIX-13 (SR-1c): FIX-8 修正 — _facingCR 入口排除 donk 误路由
# 根因: getOppPostflopAction(street)==='raise' 无法区分 donk 与真CR
#   (hero PFR后对手OOP领先donk也被判'raise'误入_facingCR)
# 修复: 仅 isOppRaiseAfterHero(hero行动后对手加注) 才进入
# ============================================================
rep(
  "      // FIX-8(审计): scene==='reraise'在自动链路永不出现(vision只产出check/bet/allin)\n      // → V2.9.692 P6权益硬闸修复从未生效。补: FrameDiff检测对手本街raise(=被CR/再加注)也进入\n      var _fcrEnter=(scene==='reraise');\n      if(!_fcrEnter&&scene==='raise'&&typeof FrameDiffEngine!=='undefined'&&FrameDiffEngine.getOppPostflopAction){\n        var _oppRA=FrameDiffEngine.getOppPostflopAction(street);\n        if(_oppRA==='raise')_fcrEnter=true;\n      }",
  "      // FIX-8(审计V2.9.695): scene==='reraise'在自动链路永不出现(vision只产出check/bet/allin)\n      // → V2.9.692 P6权益硬闸修复从未生效。补: FrameDiff检测对手本街raise也进入\n      // FIX-13(审计V2.9.696): 修正FIX-8的donk误路由——对手'raise'含donk(先行动下注)与真CR(hero行动后加注)\n      //   两种, 仅后者应进_facingCR。改用isOppRaiseAfterHero精确判据, donk回退收口(与695前一致)\n      var _fcrEnter=(scene==='reraise');\n      if(!_fcrEnter&&scene==='raise'&&typeof FrameDiffEngine!=='undefined'&&FrameDiffEngine.isOppRaiseAfterHero){\n        if(FrameDiffEngine.isOppRaiseAfterHero(street))_fcrEnter=true;\n      }",
  'FIX-13 _facingCR入口修正(isOppRaiseAfterHero排除donk)'
)

io.open(PATH, 'w', encoding='utf-8').write(src)
print(f'\n=== 全部 {len(applied)} 项修复已写入 ===')
