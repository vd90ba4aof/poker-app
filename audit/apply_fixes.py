#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
apply_fixes.py — 对 poker_helper.html 实施 8 项审计修复
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
# FIX-1 (BUG#1): _tripsBoardMax 未定义 → P7c set连牌面降级死代码
# 修复: 用安全局部变量(兼容 P7a try块内 var 提升的 undefined)
# ============================================================
rep(
  "if(madeType==='NUTS'&&_boardIsConnected&&_tripsBoardMax>_tripsRank){\n          madeType='STRONG';pairSubType+='(连牌面降级)';\n          console.log('[V2.9.693牌力] set连牌面降级NUTS→STRONG(板面最高'+_tripsBoardMax+'>三条'+_tripsRank+')');\n        }",
  "if(madeType==='NUTS'&&_boardIsConnected){\n          var _tbmSafe=-1;\n          try{for(var _tbk in _boardRanks){var _tbv=RV[_tbk];if(_tbv>_tbmSafe)_tbmSafe=_tbv;}}catch(_tbE){_tbmSafe=-1;}\n          var _trSafe=(typeof _tripsRank==='number')?_tripsRank:-1;\n          if(_tbmSafe>_trSafe){\n          madeType='STRONG';pairSubType+='(连牌面降级)';\n          console.log('[V2.9.693牌力] set连牌面降级NUTS→STRONG(板面最高'+_tbmSafe+'>三条'+_trSafe+')');\n          }\n        }",
  'FIX-1 P7c死代码: 直接计算板面最高单张rank'
)

# ============================================================
# FIX-2 (BUG#2): MEDIUM/STRONG 组合听牌 outs 双计(9+8=17)
# V2.9.602 已修 DRAW 路径(15/12), 此处同口径
# ============================================================
rep(
  "if(madeType==='MEDIUM'||madeType==='STRONG'){\n    var bonusOuts=0;\n    if(flushDraw)bonusOuts+=9;\n    if(isOpenEnd)bonusOuts+=8;else if(isGutshot)bonusOuts+=4;\n    outs=2;\n    if(bonusOuts>0){outs+=bonusOuts;hasComboDraw=true;}\n  }",
  "if(madeType==='MEDIUM'||madeType==='STRONG'){\n    var bonusOuts=0;\n    if(isOpenEnd)bonusOuts=flushDraw?15:8;\n    else if(isGutshot)bonusOuts=flushDraw?12:4;\n    else if(flushDraw)bonusOuts=9;\n    outs=2;\n    if(bonusOuts>0){outs+=bonusOuts;hasComboDraw=true;}\n  }",
  'FIX-2 outs双计: MEDIUM/STRONG路径与DRAW路径同口径(15/12)'
)

# ============================================================
# FIX-3 (BUG#3): _CR表 else-call 无 pot odds 校验
# 与 _facingCBet L4988 同口径: eq<赔率-5% → fold
# ============================================================
rep(
  "      } else {\n        // V3.21: CR未命中频率 → call兜底（面对CBet不能静默弃牌）\n        var _callEq = applyExploit(eq,'call',oppType,{bet:bet,pot:pot});\n        return{a:'call',r:'GTO 面CBet call(CR未中)',eq:_callEq.eq,c:'m',scene:'面对CBet',spr:spr,_se:true,_seFreq:crFreq};\n      }",
  "      } else {\n        // V3.21: CR未命中频率 → call兜底（面对CBet不能静默弃牌）\n        // FIX-3(审计): call前pot odds硬校验(与_facingCBet同口径), 弱牌不无脑追\n        var _crCallReq=Math.max(1,bet)/Math.max(pot+bet,1);\n        if(eq!==undefined&&eq!==null&&eq>=0&&eq/100<_crCallReq-0.05){\n          return _fold(eq,'CR未中 fold(odds) eq'+Math.round(eq)+'%<需'+Math.round(_crCallReq*100)+'%',spr);\n        }\n        var _callEq = applyExploit(eq,'call',oppType,{bet:bet,pot:pot});\n        return{a:'call',r:'GTO 面CBet call(CR未中)',eq:_callEq.eq,c:'m',scene:'面对CBet',spr:spr,_se:true,_seFreq:crFreq};\n      }",
  'FIX-3 CR未中call加odds闸'
)

# ============================================================
# FIX-4 (BUG#4): Worker端 _mcCache 未定义 → V2.9.690缓存主链路失效
# 修复: wCode 前缀注入缓存声明(主线程语句无法随toString注入)
# ============================================================
rep(
  "var wCode='var RV={A:12,K:11,Q:10,J:9,T:8,9:7,8:6,7:5,6:4,5:3,4:2,3:1,2:0};var R=[\"A\",\"K\",\"Q\",\"J\",\"T\",\"9\",\"8\",\"7\",\"6\",\"5\",\"4\",\"3\",\"2\"];var SU=[\"h\",\"d\",\"c\",\"s\"];'",
  "var wCode='var _mcCache=(typeof Map!==\"undefined\")?new Map():null;var RV={A:12,K:11,Q:10,J:9,T:8,9:7,8:6,7:5,6:4,5:3,4:2,3:1,2:0};var R=[\"A\",\"K\",\"Q\",\"J\",\"T\",\"9\",\"8\",\"7\",\"6\",\"5\",\"4\",\"3\",\"2\"];var SU=[\"h\",\"d\",\"c\",\"s\"];'",
  'FIX-4 Worker缓存: wCode注入_mcCache声明'
)

# ============================================================
# FIX-5 (BUG#5): 多人池 MC tie 恒按0.5计 → 高估
# 修复: 多对手分支统计tie份额 1/(tie人数)
# ============================================================
rep(
  "if(valid){/*V2.9.602:多对手补牌从剔除全部oppTaken的牌堆全洗牌抽取,零重牌*/var mDeck=deck.filter(function(c){return !oppTaken.some(function(o){return o.rank===c.rank&&o.suit===c.suit;});});for(var mj=mDeck.length-1;mj>0;mj--){var mk2=Math.floor(Math.random()*(mj+1));var mt=mDeck[mj];mDeck[mj]=mDeck[mk2];mDeck[mk2]=mt;}var mBoard=[].concat(comm.filter(function(c){return c;}),(nd>0?mDeck.slice(-nd):[]));myBest=eH([].concat(hole,mBoard));for(var oi2=0;oi2<oppTaken.length;oi2+=2){var oB=eH([oppTaken[oi2],oppTaken[oi2+1]].concat(mBoard));if(oB>oppBest)oppBest=oB;}}",
  "if(valid){/*V2.9.602:多对手补牌从剔除全部oppTaken的牌堆全洗牌抽取,零重牌*/var mDeck=deck.filter(function(c){return !oppTaken.some(function(o){return o.rank===c.rank&&o.suit===c.suit;});});for(var mj=mDeck.length-1;mj>0;mj--){var mk2=Math.floor(Math.random()*(mj+1));var mt=mDeck[mj];mDeck[mj]=mDeck[mk2];mDeck[mk2]=mt;}var mBoard=[].concat(comm.filter(function(c){return c;}),(nd>0?mDeck.slice(-nd):[]));myBest=eH([].concat(hole,mBoard));/*FIX-5(审计): 多人tie按份额1/(tie人数)计, 旧恒0.5在3人池高估~17pp; 块内tieN必≥2(hero与≥1对手同分), t累计份额差值(基准0.5在FIX-5b)*/var _oppBList=[];for(var oi2=0;oi2<oppTaken.length;oi2+=2){var oB=eH([oppTaken[oi2],oppTaken[oi2+1]].concat(mBoard));_oppBList.push(oB);if(oB>oppBest)oppBest=oB;}if(myBest===oppBest){var _tieN=1;for(var _tn=0;_tn<_oppBList.length;_tn++){if(_oppBList[_tn]===myBest)_tieN++;}t+=(1/_tieN-0.5);}}",
  'FIX-5 多人tie份额计数'
)
# 同步修正统计公式: tie 份额已在 t 中按 1/tieN 累计(单挑保持0.5)
rep(
  "if(myBest>oppBest)w++;else if(myBest===oppBest)t++;}var _mcRes={eq:(w+t*0.5)/iterations*100,win:w/iterations*100,tie:t/iterations*100};",
  "if(myBest>oppBest)w++;else if(myBest===oppBest)t+=0.5;}var _mcRes={eq:(w+t)/iterations*100,win:w/iterations*100,tie:t/iterations*100};",
  'FIX-5b eq公式: t已按份额累计'
)

# ============================================================
# FIX-6 (BUG#6): RangeVsRange 位置硬编码 btn_open
# 修复: 按位置映射(RANGES只有btn_open/bb_call/bb_defend_vs_steal,
#        UTG/MP用btn_open前60%近似紧范围, 并在建议中标注近似)
# ============================================================
rep(
  "var myRangeName=G.pos==='btn'?'btn_open':'btn_open';",
  "var myRangeName='btn_open';var _rvrApprox='';if(['utg','utg1','mp','mp1','hj'].indexOf(G.pos)>=0){try{var _fullR=RangeVsRange.getRange('btn_open');var _nKeep=Math.max(20,Math.round(_fullR.length*0.6));RangeVsRange.setTightRange(_fullR.slice(0,_nKeep));myRangeName='_tight';_rvrApprox='(UTG/MP按紧范围近似)';}catch(_rvrE){}}",
  'FIX-6 RangeVsRange位置映射'
)
# getRange 支持 '_tight' 虚拟键 (对象字面量方法, this绑定RangeVsRange)
rep(
  "    getRange:function(name){return RANGES[name]||RANGES.btn_open;},",
  "    getRange:function(name){if(name==='_tight')return this._tightRange||RANGES.btn_open;return RANGES[name]||RANGES.btn_open;},\n    setTightRange:function(r){this._tightRange=r;},",
  'FIX-6b getRange支持_tight+setTightRange'
)

# 建议文本标注近似
rep(
  "_rangeAdvice=' | 范围权益'+Math.round(rrEq.myEquity)+'%(坚果差'+rrEq.nutAdvantage+')';",
  "_rangeAdvice=' | 范围权益'+Math.round(rrEq.myEquity)+'%(坚果差'+rrEq.nutAdvantage+')'+_rvrApprox;",
  'FIX-6g 建议标注近似'
)

# ============================================================
# FIX-7 (BUG#7): _isNonNutStraight 孤立高牌误判
# 修复: 枚举对手可用 board+2张 构成更高顺的窗口(板面覆盖>=3)
# ============================================================
rep(
  "    var _maxBoardRank=comm.filter(function(c){return c;}).map(function(c){return RV[c.rank];});\n    _maxBoardRank=_maxBoardRank.length>0?Math.max.apply(null,_maxBoardRank):0;\n    if(_heroStraightTop<_maxBoardRank)_isNonNutStraight=true;",
  "    var _maxBoardRank=comm.filter(function(c){return c;}).map(function(c){return RV[c.rank];});\n    _maxBoardRank=_maxBoardRank.length>0?Math.max.apply(null,_maxBoardRank):0;\n    if(_heroStraightTop<_maxBoardRank)_isNonNutStraight=true;\n    // FIX-7(审计): 精确判据——孤立高牌不可能参与更高顺(如89@567KQ, K/Q构不成顺)\n    // 对手更高顺需: 存在顺窗口W(顶>hero顺顶)且W中>=3个rank已在板面(对手2张补齐剩余)\n    if(_isNonNutStraight){\n      _isNonNutStraight=false;\n      var _boardRV=comm.filter(function(c){return c;}).map(function(c){return RV[c.rank];});\n      for(var _swTop=Math.max(_heroStraightTop+1,4);_swTop<=12;_swTop++){\n        var _winHave=0;\n        for(var _swr=_swTop-4;_swr<=_swTop;_swr++){if(_boardRV.indexOf(_swr)>=0)_winHave++;}\n        if(_winHave>=3){_isNonNutStraight=true;break;}\n      }\n      if(!_isNonNutStraight&&_heroStraightTop<3){\n        var _wheel=[12,0,1,2,3],_wh=0;\n        for(var _wi=0;_wi<5;_wi++){if(_boardRV.indexOf(_wheel[_wi])>=0)_wh++;}\n        if(_wh>=3)_isNonNutStraight=true;\n      }\n    }",
  'FIX-7 非坚果顺精确判据'
)

# ============================================================
# FIX-8 (P6无效修复): _facingCR 需 scene==='reraise', 自动链路不可达
# 修复: didPFR+面对下注+FrameDiff检测对手本街raise → 也进 _facingCR
# ============================================================
rep(
  "  if(didPFR && scene==='reraise' && typeof _facingCR==='function'){\n    try{\n      var _fcrR=_facingCR(k,hcKey,btKey,bet,pot,eq,street);\n      if(_fcrR&&_fcrR.a){return _fcrR;}\n    }catch(e){}\n  }",
  "  if(didPFR && typeof _facingCR==='function'){\n    try{\n      // FIX-8(审计): scene==='reraise'在自动链路永不出现(vision只产出check/bet/allin)\n      // → V2.9.692 P6权益硬闸修复从未生效。补: FrameDiff检测对手本街raise(=被CR/再加注)也进入\n      var _fcrEnter=(scene==='reraise');\n      if(!_fcrEnter&&scene==='raise'&&typeof FrameDiffEngine!=='undefined'&&FrameDiffEngine.getOppPostflopAction){\n        var _oppRA=FrameDiffEngine.getOppPostflopAction(street);\n        if(_oppRA==='raise')_fcrEnter=true;\n      }\n      if(_fcrEnter){\n        var _fcrR=_facingCR(k,hcKey,btKey,bet,pot,eq,street);\n        if(_fcrR&&_fcrR.a){return _fcrR;}\n      }\n    }catch(e){}\n  }",
  'FIX-8 _facingCR自动链路可达性'
)

# ============================================================
# FIX-9: 版本号 bump 2.9.694 → 2.9.695 (审计修复版)
# ============================================================
rep(
  "CURRENT_VERSION:'2.9.694',",
  "CURRENT_VERSION:'2.9.695',",
  'FIX-9a CURRENT_VERSION bump'
)
rep(
  "var APP_VERSION = '2.9.694';",
  "var APP_VERSION = '2.9.695';",
  'FIX-9b APP_VERSION bump'
)

io.open(PATH, 'w', encoding='utf-8').write(src)
print(f'\n=== 全部 {len(applied)} 项修复已写入 ===')
