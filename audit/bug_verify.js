#!/usr/bin/env node
/*
 * bug_verify.js — 独立审计验证脚本(不依赖mock DOM, 只载入引擎纯函数段)
 * 验证 2026-09-20 审计发现的 6 个疑似缺陷是否真实存在
 */
const fs = require('fs');
const path = require('path');
const vm = require('vm');

let pass = 0, fail = 0;
const results = [];
function assert(cond, name, detail) {
  if (cond) { pass++; results.push('✅ ' + name + (detail ? ' → ' + detail : '')); }
  else { fail++; results.push('❌ ' + name + (detail ? ' → ' + detail : '')); }
}

// ---------- 加载引擎(复用 smoke 测试的 mock DOM 思路) ----------
const htmlPath = path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'poker_helper.html');
const html = fs.readFileSync(htmlPath, 'utf8');
const code = [...html.matchAll(/<script(?![^>]*\bsrc=)[^>]*>([\s\S]*?)<\/script>/gi)].map(m => m[1]).join('\n;\n');

function makeEl() {
  const el = function(){};
  return new Proxy(el, {
    get(t, prop) {
      if (prop === 'style') return {};
      if (prop === 'classList') return { add(){}, remove(){}, contains(){ return false; }, toggle(){} };
      if (prop === 'dataset') return {};
      if (prop === 'value') return '100';
      if (prop === 'innerHTML' || prop === 'textContent') return '';
      if (prop === 'length') return 0;
      if (prop === 'offsetHeight' || prop === 'offsetWidth') return 0;
      if (prop === 'getContext') return () => null;
      if (prop === 'querySelector' || prop === 'querySelectorAll') return () => (prop === 'querySelectorAll' ? [] : makeEl());
      if (prop === 'addEventListener' || prop === 'removeEventListener') return () => {};
      if (prop === 'appendChild' || prop === 'removeChild' || prop === 'setAttribute' || prop === 'getAttribute') return () => {};
      if (prop === 'getBoundingClientRect') return () => ({ top:0,left:0,right:0,bottom:0,width:0,height:0 });
      return makeEl();
    },
    set(){ return true; },
    apply(){ return makeEl(); }
  });
}

const sandbox = {
  console: { log(){}, warn(){}, error(){}, info(){} },
  document: new Proxy({}, {
    get(t,p){ if (p === 'getElementById' || p === 'querySelector') return () => makeEl();
      if (p === 'querySelectorAll') return () => [];
      if (p === 'addEventListener') return () => {};
      if (p === 'createElement') return () => makeEl();
      if (p === 'body' || p === 'documentElement') return makeEl();
      return makeEl(); }
  }),
  window: {},
  localStorage: { getItem(){ return null; }, setItem(){}, removeItem(){} },
  setInterval: () => 0, setTimeout: (f) => 0, clearTimeout(){}, clearInterval(){},
  navigator: { userAgent: 'node-test' },
  location: { href: 'http://localhost/', reload(){} },
  Worker: function(){ this.postMessage=()=>{}; this.terminate=()=>{}; this.onmessage=null; },
  Blob: function(){}, URL: { createObjectURL: () => '', revokeObjectURL(){} },
  fetch: () => Promise.resolve({ json: () => Promise.resolve({}) }),
  performance: { now: () => Date.now() },
  Math, Date, JSON, Object, Array, String, Number, Boolean, RegExp, Error, parseInt, parseFloat, isNaN,
  Promise, Map, Set, WeakMap,
};
sandbox.window = sandbox;
sandbox.globalThis = sandbox;
sandbox.self = sandbox;

vm.createContext(sandbox);
try {
  vm.runInContext(code, sandbox, { filename: 'poker_helper.js' });
} catch (e) {
  console.error('引擎加载失败(部分函数可能未定义): ' + e.message);
}

const C = (rank, suit) => ({ rank, suit });

// ============================================================
console.log = () => {}; // 静音引擎内部日志
// 保留原始断言输出
const log = (...a) => process.stdout.write(a.join(' ') + '\n');

// ============================================================
log('\n========== BUG#1: _tripsBoardMax 未定义 → P7c set连牌面降级死代码 ==========');
// 场景: set + 连牌面. 如 hero 5h5d @ 4s5c6d7h (flop 4-5-6 连牌, hero 底set)
// 期望(V2.9.693 P7c声明): 板面连通且板面最高rank>hero三条rank → STRONG
// 实际: _tripsBoardMax 未定义 → 恒 false → 永不降级
try {
  const hole = [C('5','h'), C('5','d')];
  const comm = [C('4','s'), C('5','c'), C('6','d'), null, null];
  const hc = sandbox.handClassify(hole, comm);
  log('  handClassify(5h5d @ 4s5c6d) = ' + hc.name + ' / ' + hc.desc);
  // P7c 条件: _boardIsConnected(4-5-6 连牌✓) 且 _tripsBoardMax(板面最高单张6) > _tripsRank(5)
  // 声称应降级 STRONG; 若仍 NUTS 则死代码实锤
  assert(hc.name === 'NUTS',
    'BUG#1 实锤: set@连牌面仍判NUTS (P7c降级分支永不执行)',
    'V2.9.693声称修复此场景, 但 _tripsBoardMax 未定义(定义的是 _tripsBoardMaxSingle), undefined>x恒false');
} catch (e) { assert(false, 'BUG#1 测试异常', e.message); }

// ============================================================
log('\n========== BUG#2: MEDIUM/STRONG 组合听牌 outs 双计 ==========');
// 场景: 顶对 + 同花听 + 两端顺听. 如 hero Ah2h @ 3h4h5c (A高同花听 + A是高张不算顺)
// 更好的例子: hero KhQh @ JhTh2c → 顶对K? 不对 K<Q<J... 用 Kh9h @ ThJhQc? 
// 直接构造: hero 顶对+花听+OESD: hole=[Kh9h] comm=[Kc,Jh,Th,2s] → 顶对K + 同花听(h) + OESD(Q8/8Q... K-hold)
// handClassify: madeHand2='pair' 顶对 → MEDIUM; flushDraw+OESD → bonusOuts=9+8=17 → outs=2+17=19
// V2.9.602 已在 DRAW 路径修复双计(9+8→15), 但 MEDIUM/STRONG 路径(L8600-8606)漏修
try {
  const hole = [C('J','h'), C('T','h')];
  const comm = [C('J','c'), C('9','h'), C('8','h'), null, null];
  const hc = sandbox.handClassify(hole, comm);
  log('  handClassify(JhTh @ Jc9h8h) = ' + hc.name + ' / ' + hc.desc + ' / outs=' + hc.outs);
  // 顶对J + 同花听(4h在手+板) + OESD(Q/7补顺)
  // 修复前(bug): bonusOuts=9+8=17, outs=2+17=19(双计)
  // 修复后(FIX-2): bonusOuts=15(flush+OESD口径), outs=2+15=17
  assert(hc.outs >= 19,
    'BUG#2 实锤: 顶对+花听+OESD outs=' + hc.outs + ' (双计, 真实≈17)',
    'DRAW路径V2.9.602已修(15), MEDIUM/STRONG路径漏修仍9+8=17(+2基数=19)');
} catch (e) { assert(false, 'BUG#2 测试异常', e.message); }

// ============================================================
log('\n========== BUG#3: _CR表 else-call 无权益闸 (P5/P6同类残留) ==========');
// decidePostflop L4238-4241: CR频率未中 → 无条件 call, 无 pot odds 校验
// 对比: _facingCBet call支路有odds校验(L4988), fold支路有P5硬闸(L5012)
// 场景: OOP非PFR flop面对下注, _facingCBet 表miss时落 _CR 表 → CR未中 → call
// 构造: 对手下重注75%pot, hero空气牌eq≈8%, 需eq≥30%才够赔率
try {
  const G = sandbox.G;
  // 设定: 空气牌 @ 湿面, 面对大注
  G.hole = [C('8','d'), C('3','c')];
  G.comm = [C('A','h'), C('K','h'), C('7','s'), null, null];
  G.phase = 'post'; G.scene = 'bet'; G.pos = 'bb'; G.opp = 'unknown';
  G.stk = 100; G.pot = 10; G.bet = 7.5; G.tt = 6; G.act = 2; G.limpers = 0;
  G.ante = 0; G.players = []; G.oppSeats = [];
  // 调用 decidePostflop (跳过 MC 注入)
  const k = sandbox.getHandKey();
  let r = null;
  try { r = sandbox.StrategyEngine ? (function(){ 
    sandbox._setPfEqInj && sandbox._setPfEqInj(null);
    return sandbox.StrategyEngine.decidePostflop(k, {eq: 8}); // 注入极低eq
  })() : null; } catch (e2) { log('  decidePostflop 异常: ' + e2.message); }
  if (r) {
    log('  决策: ' + r.a + ' | ' + (r.r || '').slice(0, 80));
    const isMwOrOthers = (r.r || '').indexOf('收口') >= 0 || (r.r || '').indexOf('面CBet') >= 0;
    // 若走了 _CR 表路径且 call, 且 reason 含 'CR未中' → 实锤无权益闸
    const crPath = (r.r || '').indexOf('CR未中') >= 0;
    if (crPath) {
      assert(r.a === 'call', 'BUG#3 实锤: CR未中→call(eq=8%<赔率30%) 无odds校验', (r.r||'').slice(0,90));
    } else {
      // 该场景走了别的路径(如facingCBet覆盖), 换一个更精确的触发场景
      log('  本场景未触发_CR路径(被上游分支拦截): ' + (r.r||'').slice(0,60));
      // 静态验证: 从文件源码定位_CR else分支确认odds校验存在性
      const fsrc = fs.readFileSync(htmlPath, 'utf8');
      const crElse = fsrc.indexOf("CR未中");
      if (crElse >= 0) {
        const seg = fsrc.slice(crElse - 500, crElse + 400);
        const hasOddsCheck = /eq\s*[<>]|odds|_need|赔率|_crCallReq/.test(seg);
        assert(!hasOddsCheck, 'BUG#3(静态): _CR else-call分支无eq/odds校验(代码审读)', seg.slice(seg.length-200).replace(/\s+/g,' ').slice(-160));
      }
    }
  } else {
    log('  decidePostflop 返回 null');
    // 静态审查兜底
    const src = fs.readFileSync(htmlPath, 'utf8');
    const i = src.indexOf("CR未中");
    const seg = src.slice(i - 300, i + 150).replace(/\s+/g, ' ');
    const hasOdds = /eq\s*[<>]|odds|赔率/.test(seg);
    assert(!hasOdds, 'BUG#3(静态): CR未中→call 无权益校验', seg.slice(-200));
  }
} catch (e) { assert(false, 'BUG#3 测试异常', e.message); }

// ============================================================
log('\n========== BUG#4: Worker端 _mcCache 未定义 → V2.9.690 缓存在主链路失效 ==========');
// mcVsRange 函数体引用 _mcCache(外部var声明), toString()注入Worker后该变量不存在
// → typeof守卫静默跳过 → Worker每次全新MC → 同街帧间eq抖动(30s缓存目标落空)
try {
  const src = fs.readFileSync(htmlPath, 'utf8');
  // 1) mcVsRange 函数体内引用 _mcCache
  const fnStart = src.indexOf('function mcVsRange(');
  const fnEnd = src.indexOf('\nfunction handKeyToCards', fnStart);
  const fnBody = src.slice(fnStart, fnEnd);
  const refsCache = fnBody.indexOf('_mcCache') >= 0;
  // 2) Worker wCode 是否注入 _mcCache 声明 (锚点用 wCode= 开头, 不依赖内容前缀)
  const wcodeStart = src.indexOf("var wCode='");
  const wcodeEnd = src.indexOf("try{\n    var blob", wcodeStart) > 0 ? src.indexOf("try{\n    var blob", wcodeStart) : wcodeStart + 12000;
  const wcode = src.slice(wcodeStart, wcodeEnd);
  const workerHasCacheDecl = /var _mcCache\s*=/.test(wcode);
  assert(refsCache && !workerHasCacheDecl,
    'BUG#4 实锤: 函数体引用_mcCache但Worker未注入声明 → Worker端缓存恒失效',
    'go()主链路走Worker, V2.9.690"30s缓存消除抖动"在主链路无效, 仅主线程兜底路径生效');
} catch (e) { assert(false, 'BUG#4 测试异常', e.message); }

// ============================================================
log('\n========== BUG#5: 多人池 MC tie 计数 0.5 高估 ==========');
// mcVsRange: eq=(w+t*0.5)/iterations — nOpp≥2时tie应按 1/(tie人数) 分摊
try {
  const src = fs.readFileSync(htmlPath, 'utf8');
  const fnStart = src.indexOf('function mcVsRange(');
  const fnBody = src.slice(fnStart, fnStart + 20000);
  const t05 = fnBody.indexOf('(w+t*0.5)') >= 0;
  const hasNoppAwareTie = /t\*\(\s*1\s*\/|tieCount|tiePlayers/.test(fnBody);
  assert(t05 && !hasNoppAwareTie,
    'BUG#5 实锤: tie恒按0.5计, 3人池tie实际≈1/3',
    '多人池hero equity系统性高估(3人tie场景高估~17pp)');
} catch (e) { assert(false, 'BUG#5 测试异常', e.message); }

// ============================================================
log('\n========== BUG#6: RangeVsRange 位置硬编码 btn_open ==========');
try {
  const src = fs.readFileSync(htmlPath, 'utf8');
  const i = src.indexOf("G.pos==='btn'?'btn_open':'btn_open'");
  assert(i >= 0,
    'BUG#6 实锤: 三元表达式两分支相同',
    '非BTN位置(CO/SB/UTG)的范围权益对比全按BTN open范围计算, 范围建议失真');
} catch (e) { assert(false, 'BUG#6 测试异常', e.message); }

// ============================================================
log('\n========== BUG#7: _isNonNutStraight 孤立高牌误判 ==========');
// 场景: hero 8h9c @ 5s6d7cKsQd — 5-6-7-8-9 坚果顺(板面K/Q不可能构成更高顺)
// 代码: _heroStraightTop(9) < _maxBoardRank(K) → 误判非坚果 → 降级STRONG
try {
  const hole = [C('8','h'), C('9','c')];
  const comm = [C('5','s'), C('6','d'), C('7','c'), C('K','s'), C('Q','d')];
  const hc = sandbox.handClassify(hole, comm);
  log('  handClassify(8h9c @ 567KQ) = ' + hc.name + ' / ' + hc.desc);
  // 正确判定: 板面567+KQ, 对手两手牌最多补T-J构成T-J-9-8-7? 需要板面8/9, 不可能 → 89是坚果顺
  // 对手 9T: 5,6,7,9,T 不成顺(缺8); 对手 TJ: 5,6,7,T,J 缺8,9 → 不可能更高顺
  assert(hc.name === 'STRONG',
    'BUG#7 实锤: 坚果顺被误判非坚果而降级STRONG',
    '板面孤立高牌(K/Q)不可能参与更高顺, _heroStraightTop<板面最高 的判据过粗');
} catch (e) { assert(false, 'BUG#7 测试异常', e.message); }

// ============================================================
log('\n========================================');
log('审计验证: ' + pass + ' 项实锤, ' + (fail ? fail + ' 项未复现' : '0 项未复现'));
log('========================================');
results.forEach(r => log(r));
process.exit(fail > 0 ? 1 : 0);
