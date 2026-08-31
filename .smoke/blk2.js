// ============================================================================
// V2.9.180: 三大智能模块 - 全自动闭环 + 对手动态建模 + 牌面纹理分析
// ============================================================================

// ===== 模块1: AutoConfidence - 置信度分级自动执行 =====
var AutoConfidence = {
  compute: function(r) {
    var eq = r.eq || 50;
    var hClass = r.hClass;
    var reliability = r._reliability || 'high';
    var potConf = (typeof PotConfidence !== 'undefined' && PotConfidence.level) ? PotConfidence.level : 'high';
    var action = r.a || 'fold';

    // 低置信触发条件
    if (reliability === 'low') return 'low';
    if (potConf === 'low') return 'low';
    if (hClass && (!hClass.name || hClass.name === '?' || hClass.name === 'UNKNOWN')) return 'low';
    if (eq === 0 && reliability !== 'high') return 'low';

    // 高置信触发条件
    if (hClass && eq >= 80 && (hClass.name === 'NUTS' || hClass.name === 'STRONG')) return 'high';
    if (hClass && eq >= 75 && (hClass.name === 'NUTS' || hClass.name === 'STRONG') && reliability === 'high') return 'high';
    if (hClass && eq <= 20 && hClass.name === 'AIR' && reliability === 'high') return 'high';
    if (action === 'fold' && eq <= 25 && reliability === 'high') return 'high';
    if (hClass && hClass.name === 'NUTS' && reliability === 'high') return 'high';

    return 'medium';
  },

  execute: function(r) {
    var conf = this.compute(r);
    var action = r.a || 'fold';
    console.log('[AutoConf] confidence=' + conf + ' action=' + action + ' eq=' + (r.eq||'?') + ' hClass=' + (r.hClass?r.hClass.name:'?'));

    if (conf === 'low') {
      console.log('[AutoConf] LOW→强制弃牌');
      return { action: 'fold', auto: true, reason: 'low_confidence', confidence: 'low' };
    }

    if (conf === 'medium') {
      if (action === 'allin') {
        console.log('[AutoConf] MEDIUM+allin→等待确认');
        return { action: action, auto: false, reason: 'allin_needs_confirm', confidence: 'medium' };
      }
      console.log('[AutoConf] MEDIUM→自动执行');
      return { action: action, auto: true, reason: 'medium_auto', confidence: 'medium' };
    }

    console.log('[AutoConf] HIGH→自动执行');
    return { action: action, auto: true, reason: 'high_confidence', confidence: 'high' };
  }
};

// ===== 模块2: OpponentTracker - 对手动态建模 =====
var OpponentTracker = {
  _data: {},
  _sessionStart: Date.now(),

  init: function() {
    try {
      var saved = localStorage.getItem('_oppTrack_v2');
      if (saved) { this._data = JSON.parse(saved); console.log('[OppTrack] loaded ' + Object.keys(this._data).length + ' players'); }
    } catch(e) { console.log('[OppTrack] init error: ' + e.message); }
  },

  _save: function() {
    try { localStorage.setItem('_oppTrack_v2', JSON.stringify(this._data)); } catch(e) {}
  },

  // 用昵称或座位号作为key
  _getKey: function(seatIdx, nickname) {
    return nickname && nickname !== 'unknown' ? nickname : 'seat_' + seatIdx;
  },

  // 记录一手牌的结果
  recordHand: function(oppSeats, heroAction, street, result) {
    if (!oppSeats || !oppSeats.length) return;

    for (var i = 0; i < oppSeats.length; i++) {
      var s = oppSeats[i];
      var key = this._getKey(s.seat, s.nickname);
      if (!this._data[key]) {
        this._data[key] = { hands: 0, vpip: 0, pfr: 0, folds: 0, calls: 0, raises: 0, threeBets: 0, cbets: 0, _actions: [], nickname: s.nickname || '', seat: s.seat };
      }
      var d = this._data[key];
      d.hands++;
      d.nickname = s.nickname || d.nickname;

      var act = (s.action || '').toLowerCase();
      if (act.indexOf('fold') >= 0) d.folds++;
      else if (act.indexOf('raise') >= 0 || act.indexOf('bet') >= 0) {
        d.raises++;
        if (act.indexOf('3bet') >= 0 || act.indexOf('reraise') >= 0) d.threeBets++;
        if (street === 'flop' || street === 'turn' || street === 'river') d.cbets++;
      } else if (act.indexOf('call') >= 0 || act.indexOf('check') >= 0) d.calls++;

      d._actions.push({ a: act, st: street, ts: Date.now() });
      if (d._actions.length > 200) d._actions.shift();

      // 重新计算频率
      var total = d.hands;
      if (total > 0) {
        d.vpip = Math.round((d.calls + d.raises) / total * 100);
        d.pfr = Math.round(d.raises / total * 100);
        d.af = d.calls > 0 ? parseFloat((d.raises / d.calls).toFixed(1)) : (d.raises > 0 ? 99 : 0);
      }
    }
    this._save();
  },

  // 自动分类对手
  classify: function(seatIdx, nickname) {
    var key = this._getKey(seatIdx, nickname);
    var d = this._data[key];
    if (!d || d.hands < 5) return 'unknown';

    if (d.vpip >= 55 && d.pfr <= 8) return 'calling_station';
    if (d.vpip >= 45 && d.pfr >= 25) return 'maniac';
    if (d.vpip >= 30 && d.pfr >= 20) return 'lag';
    if (d.vpip <= 12 && d.pfr <= 8) return 'nit';
    if (d.vpip >= 25 && d.pfr <= 12) return 'fish';
    if (d.vpip >= 18 && d.pfr >= 12) return 'tp';

    return 'unknown';
  },

  // 获取对手数据
  getStats: function(seatIdx, nickname) {
    var key = this._getKey(seatIdx, nickname);
    return this._data[key] || { hands: 0, vpip: 0, pfr: 0, af: 0, folds: 0, calls: 0, raises: 0 };
  },

  // 返回最活跃对手的类型
  getMainOpponentType: function() {
    if (!G.oppSeats || !G.oppSeats.length) return 'unknown';
    var best = null, bestHands = 0;
    for (var i = 0; i < G.oppSeats.length; i++) {
      var s = G.oppSeats[i];
      var key = this._getKey(s.seat, s.nickname);
      var d = this._data[key];
      if (d && d.hands > bestHands) { bestHands = d.hands; best = d; }
    }
    if (best && best.hands >= 5) return this.classify(best.seat, best.nickname);
    return 'unknown';
  },

  reset: function() {
    this._data = {};
    this._save();
  }
};

// ===== 模块3: BoardTexture - 牌面纹理深度分析 =====
var BoardTexture = {
  _rankVals: { A: 12, K: 11, Q: 10, J: 9, T: 8, '9': 7, '8': 6, '7': 5, '6': 4, '5': 3, '4': 2, '3': 1, '2': 0 },

  analyze: function(comm) {
    var cards = comm ? comm.filter(function(c) { return c; }) : [];
    if (cards.length < 3) {
      return { wetness: 'dry', connectivity: 'none', paired: false, trips: false, flushPossible: false, straightPossible: false, rank: 'low', cards: cards.length, desc: '翻前' };
    }

    var ranks = cards.map(function(c) { return c.rank; });
    var suits = cards.map(function(c) { return c.suit; });
    var self = this;

    // 对子/三条
    var rankCount = {};
    ranks.forEach(function(r) { rankCount[r] = (rankCount[r] || 0) + 1; });
    var paired = Object.values(rankCount).some(function(n) { return n >= 2; });
    var trips = Object.values(rankCount).some(function(n) { return n >= 3; });

    // 同花可能
    var suitCount = {};
    suits.forEach(function(s) { suitCount[s] = (suitCount[s] || 0) + 1; });
    var maxSameSuit = Math.max.apply(null, Object.values(suitCount));
    var flushPossible = maxSameSuit >= 3;
    var flushDraw = maxSameSuit === 2; // 2同花+2张未发

    // 顺子可能
    var vals = ranks.map(function(r) { return self._rankVals[r] || 0; }).sort(function(a, b) { return a - b; });
    // 去重
    var uniqVals = [];
    vals.forEach(function(v) { if (uniqVals.indexOf(v) === -1) uniqVals.push(v); });
    var gaps = [];
    for (var i = 1; i < uniqVals.length; i++) gaps.push(uniqVals[i] - uniqVals[i - 1]);
    var maxGap = gaps.length > 0 ? Math.max.apply(null, gaps) : 99;
    var straightPossible = uniqVals.length >= 3 && maxGap <= 4;

    // 连接性
    var connectivity = maxGap <= 2 ? 'high' : maxGap <= 3 ? 'medium' : 'low';

    // 湿润度
    var wetness = 'dry';
    if (flushPossible && straightPossible) wetness = 'very_wet';
    else if (flushPossible || straightPossible) wetness = 'wet';
    else if (paired) wetness = 'paired';

    // 最高牌等级
    var maxRank = Math.max.apply(null, vals);
    var rank = maxRank >= 10 ? 'high' : maxRank >= 7 ? 'medium' : 'low';

    var desc = '';
    if (wetness === 'very_wet') desc = '极湿(花+顺)';
    else if (wetness === 'wet') desc = (flushPossible ? '同花面' : '顺子面');
    else if (paired) desc = '对子面';
    else desc = '干燥面';

    return {
      wetness: wetness, connectivity: connectivity, paired: paired, trips: trips,
      flushPossible: flushPossible, flushDraw: flushDraw, straightPossible: straightPossible,
      maxGap: maxGap, rank: rank, cards: cards.length, desc: desc, maxSameSuit: maxSameSuit
    };
  },

  // 获取翻后调整因子
  getAdjustment: function(texture, heroIsAggressor) {
    var adj = { cbetFreq: 1.0, barrelFreq: 1.0, sizingMult: 1.0, checkFreq: 1.0 };

    if (texture.wetness === 'very_wet') {
      adj.cbetFreq = heroIsAggressor ? 0.75 : 1.15;
      adj.sizingMult = 1.25;
      adj.barrelFreq = heroIsAggressor ? 0.7 : 1.0;
    } else if (texture.wetness === 'wet') {
      adj.cbetFreq = heroIsAggressor ? 0.85 : 1.1;
      adj.sizingMult = 1.1;
      adj.barrelFreq = heroIsAggressor ? 0.8 : 1.0;
    } else if (texture.paired) {
      adj.cbetFreq = 1.35;
      adj.sizingMult = 0.65;
      adj.barrelFreq = 1.2;
    }

    if (texture.rank === 'high') {
      adj.cbetFreq *= 0.8;
      adj.checkFreq *= 1.3;
    } else if (texture.rank === 'low') {
      adj.cbetFreq *= 1.15;
      adj.checkFreq *= 0.8;
    }

    return adj;
  }
};

// ===== 自动执行桥接 =====
var _autoExecEnabled = false;
function enableAutoExec(){_autoExecEnabled=true;console.log("[AutoExec] enabled");}
function disableAutoExec(){_autoExecEnabled=false;console.log("[AutoExec] disabled");}
// 筹码重置——Kotlin端ChipTracker.reset()后回调，清空JS侧追踪状态
function onChipReset(){
  try{
    G.hole=[null,null];G.comm=[null,null,null,null,null];
    G.bet=0;G.pot=10;G.limpers=0;G._heroDid4bet=false;G._slowplayed=false;
    if(typeof DRTA!=='undefined'&&DRTA.reset)DRTA.reset();
    if(typeof HudLearner!=='undefined'&&HudLearner.reset)HudLearner.reset();
    if(typeof uCards==='function')uCards();
    if(typeof clr==='function')clr();
    console.log('[onChipReset] JS侧筹码/追踪状态已重置');
  }catch(e){console.log('[onChipReset] err:'+e.message);}
}
// 自动执行决策——在bridgeAdvice之后调用
function autoExecuteDecision(r, isAutoMode) {
  if (!isAutoMode) return false; // V2.9.553-rev9: 移除_autoExecEnabled依赖(Kotlin侧isAutoCaptureOn已是权威源)

  var exec = AutoConfidence.execute(r);
  console.log('[AutoExec] result=' + JSON.stringify(exec));

  if (exec.confidence === 'low') {
    // 低置信→强制弃牌
    console.log('[AutoExec] 低置信→强制弃牌: eq='+(r.eq||0)+' hClass='+(r.hClass?r.hClass.name:'?')+' pot='+G.pot+' stk='+G.stk+' bet='+G.bet);
    if (typeof AndroidBridge !== 'undefined' && AndroidBridge.autoDecision) {
      AndroidBridge.autoDecision(JSON.stringify({
        action: 'fold',
        auto: true,
        confidence: 'low',
        reason: 'Low confidence, force fold',
        eq: r.eq || 0,
        hClass: r.hClass ? r.hClass.name : '?',
        pot: G.pot || 0,
        myChips: G.stk || 0,
        toCall: G.bet || 0
      }));
    }
    return true;
  }

  if (exec.auto) {
    // 自动执行
    if (typeof AndroidBridge !== 'undefined' && AndroidBridge.autoDecision) {
      AndroidBridge.autoDecision(JSON.stringify({
        action: exec.action,
        auto: true,
        confidence: exec.confidence,
        reason: exec.reason,
        eq: r.eq || 0,
        hClass: r.hClass ? r.hClass.name : '?',
        sizing: r.v || 0,
        pot: G.pot || 0,
        myChips: G.stk || 0,
        toCall: G.bet || 0,
        phase: G.phase || 'post',
        nash: r._nash ? true : false
      }));
    }
    return true;
  }

  // 中置信+全押→不自动执行，等用户确认
  console.log('[AutoExec] 需要人工确认: ' + exec.action + ' (' + exec.reason + ')');
  return false;
}

// ===== 初始化 =====
(function() {
  OpponentTracker.init();
  console.log('[' + APP_VERSION + '] AutoConfidence + OpponentTracker + BoardTexture 已加载');
})();