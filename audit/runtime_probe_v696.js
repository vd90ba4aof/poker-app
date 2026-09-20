#!/usr/bin/env node
/* runtime_probe_v696.js — V2.9.696 街语义判据运行时实测（修正版验证）
 * 直接从 poker_helper.html 提取真实 FrameDiffEngine 代码跑，processFrame 构造帧序列。
 * 覆盖: tc 滞后帧(对手下注记raise) / tc 完成态帧(对手下注记call) 两种分类噪声时序。
 */
const fs = require('fs');
const path = '/root/.codebuddy/artifact/poker-app/app/src/main/assets/poker_helper.html';
const src = fs.readFileSync(path, 'utf8');
const lines = src.split('\n');

let code = '', started = false, depth = 0;
for (let i = 2606; i < 2740; i++) {
  const L = lines[i] || '';
  code += L + '\n';
  if (L.startsWith('var FrameDiffEngine={')) started = true;
  if (started) {
    for (const ch of L) { if (ch === '{') depth++; else if (ch === '}') depth--; }
    if (started && depth === 0 && L.trim().endsWith('};')) break;
  }
}
fs.writeFileSync('/tmp/fde_module.js', `
var console={log:function(){},warn:function(){},error:function(){}};
var G={};
${code}
module.exports={FrameDiffEngine:FrameDiffEngine};
`);
const { FrameDiffEngine } = require('/tmp/fde_module.js');

function frame(o) {
  return {
    hole_cards: [{rank:'A',suit:'s'},{rank:'K',suit:'s'}],
    community_cards: o.cc || [],
    pot_size: o.pot|0, my_chips: o.me|0, to_call: o.tc|0,
    street: o.st || '', active_players: 2, total_players: 6,
    opp_seats: o.os || [{seat:2, chips:500, action:'active'}],
    buttons: o.btn || []
  };
}
const BTN = ['弃牌','跟注','加注'];
const FLOP3 = [{rank:'7',suit:'h'},{rank:'8',suit:'d'},{rank:'2',suit:'c'}];
function dumpAl(tag) {
  const al = FrameDiffEngine.getActionLog();
  process.stdout.write(`[${tag}] _al(${al.length}): ` + al.map(a => a.a + (a.st ? '(' + a.st + ')' : '')).join(' → ') + '\n');
  return al;
}
let pass = 0, fail = 0;
function check(name, got, want) {
  const ok = JSON.stringify(got) === JSON.stringify(want);
  process.stdout.write(`${ok ? '✅' : '❌'} ${name}: got=${JSON.stringify(got)}${JSON.stringify(got)===JSON.stringify(want)?'':' want='+JSON.stringify(want)}\n`);
  ok ? pass++ : fail++;
}
// 回归老方法: getOppPostflopAction 不受本次修改影响
function checkOld(name, got, want) { check(name, got, want); }

// ================================================================
// 场景A: hero PFR + 对手 preflop call + FLOP 对手 donk（同帧街转换）
// 变体A1: tc滞后帧(对手donk记raise)  变体A2: tc完成态帧(对手donk记call)
// ================================================================
function runA(tcVal) {
  FrameDiffEngine.reset();
  FrameDiffEngine.processFrame(frame({st:'preflop', btn:BTN, pot:15, me:500, tc:0}), 'auto');
  FrameDiffEngine.processFrame(frame({st:'preflop', btn:[], pot:135, me:380, tc:0}), 'auto');          // hero加注
  FrameDiffEngine.processFrame(frame({st:'preflop', btn:[], os:[{seat:2,chips:380,action:'active'}], pot:235, me:380, tc:0}), 'auto'); // 对手call(tc=0→记raise)
  // flop街转换+对手donk+hero按钮 同帧
  FrameDiffEngine.processFrame(frame({st:'flop', btn:BTN, cc:FLOP3, os:[{seat:2,chips:280,action:'active'}], pot:335, me:380, tc:tcVal}), 'auto');
  return dumpAl(`A tc=${tcVal}`);
}
let alA1 = runA(0);    // tc滞后 → donk记raise
check('A1.isOppLeadRaise(flop) [donk·记raise → true]', FrameDiffEngine.isOppLeadRaise('flop'), true);
check('A1.isOppRaiseAfterHero(flop) [donk → false]', FrameDiffEngine.isOppRaiseAfterHero('flop'), false);
check('A1.getStreetActs(flop) [序列含对手投入+our_turn]', FrameDiffEngine.getStreetActs('flop'), ['raise','our_turn']);

let alA2 = runA(100);  // tc=100完成态 → donk记call(d=100<=100+33.5)
check('A2.isOppLeadRaise(flop) [donk·记call → 鲁棒仍true]', FrameDiffEngine.isOppLeadRaise('flop'), true);
check('A2.isOppRaiseAfterHero(flop) [donk → false]', FrameDiffEngine.isOppRaiseAfterHero('flop'), false);

// ================================================================
// 场景B: hero PFR + 对手flop check + hero cbet + 对手 check-raise（真CR·跨帧）
// ================================================================
function runB(tcVal) {
  FrameDiffEngine.reset();
  FrameDiffEngine.processFrame(frame({st:'preflop', btn:BTN, pot:15, me:500, tc:0}), 'auto');
  FrameDiffEngine.processFrame(frame({st:'preflop', btn:[], pot:135, me:380, tc:0}), 'auto');
  FrameDiffEngine.processFrame(frame({st:'preflop', btn:[], os:[{seat:2,chips:380,action:'active'}], pot:235, me:380, tc:0}), 'auto'); // 对手call(记raise preflop)
  FrameDiffEngine.processFrame(frame({st:'flop', btn:BTN, cc:FLOP3, os:[{seat:2,chips:380,action:'active'}], pot:235, me:380, tc:0}), 'auto');  // flop开始+对手check+hero按钮
  FrameDiffEngine.processFrame(frame({st:'flop', btn:[], cc:FLOP3, os:[{seat:2,chips:380,action:'active'}], pot:285, me:330, tc:0}), 'auto');    // hero cbet
  FrameDiffEngine.processFrame(frame({st:'flop', btn:BTN, cc:FLOP3, os:[{seat:2,chips:230,action:'active'}], pot:435, me:330, tc:tcVal}), 'auto'); // 对手CR+hero按钮
  return dumpAl(`B tc=${tcVal}`);
}
let alB1 = runB(0);    // tc滞后 → CR记raise
check('B1.getStreetActs(flop) [真CR序列: our_turn,raise,our_turn]', FrameDiffEngine.getStreetActs('flop'), ['our_turn','raise','our_turn']);
check('B1.isOppLeadRaise(flop) [真CR → false]', FrameDiffEngine.isOppLeadRaise('flop'), false);
check('B1.isOppRaiseAfterHero(flop) [真CR → true]', FrameDiffEngine.isOppRaiseAfterHero('flop'), true);

let alB2 = runB(150);  // tc=150完成态 → CR记call(已知局限: 保守漏判)
process.stdout.write(`ℹ️ B2(完成态CR记call) isOppRaiseAfterHero=${FrameDiffEngine.isOppRaiseAfterHero('flop')} [已知局限: 保守漏判→落通用防御, 不劣于695前]\n`);
checkOld('B2.getOppPostflopAction(flop) [老方法回归: 最后动作]', FrameDiffEngine.getOppPostflopAction('flop'), 'call');

// ================================================================
// 场景C: 对手PFR open + hero call + flop 对手 c-bet（CBet场景·非donk）
// ================================================================
function runC(tcVal) {
  FrameDiffEngine.reset();
  FrameDiffEngine.processFrame(frame({st:'preflop', btn:[], pot:15, me:500, tc:0}), 'auto');   // 首帧(对手open前)
  FrameDiffEngine.processFrame(frame({st:'preflop', btn:BTN, os:[{seat:2,chips:380,action:'active'}], pot:105, me:500, tc:120}), 'auto'); // 对手open+hero按钮(tc=120→记call)
  FrameDiffEngine.processFrame(frame({st:'preflop', btn:[], os:[{seat:2,chips:380,action:'active'}], pot:235, me:380, tc:0}), 'auto');    // hero call
  FrameDiffEngine.processFrame(frame({st:'flop', btn:BTN, cc:FLOP3, os:[{seat:2,chips:280,action:'active'}], pot:335, me:380, tc:tcVal}), 'auto'); // 街转换+对手cbet+hero按钮 同帧
  return dumpAl(`C tc=${tcVal}`);
}
let alC1 = runC(0);    // tc滞后 → cbet记raise
check('C1.isOppLeadRaise(flop) [对手CBet·记raise → true]', FrameDiffEngine.isOppLeadRaise('flop'), true);
check('C1.isOppRaiseAfterHero(flop) [CBet → false]', FrameDiffEngine.isOppRaiseAfterHero('flop'), false);
let alC2 = runC(100);  // tc=100完成态 → cbet记call
check('C2.isOppLeadRaise(flop) [对手CBet·记call → 鲁棒仍true]', FrameDiffEngine.isOppLeadRaise('flop'), true);
check('C2.getStreetActs(flop) [preflop的our_turn不混入]', FrameDiffEngine.getStreetActs('flop'), ['call','our_turn']);

// ================================================================
// 场景D: limped pot + hero BB check + 对手 flop bet（hero已行动·非donk非CR）
// ================================================================
FrameDiffEngine.reset();
FrameDiffEngine.processFrame(frame({st:'preflop', btn:[], pot:10, me:490, tc:0}), 'auto');
FrameDiffEngine.processFrame(frame({st:'preflop', btn:BTN, pot:10, me:490, tc:0}), 'auto');   // hero BB按钮(our_turn)
FrameDiffEngine.processFrame(frame({st:'preflop', btn:[], pot:10, me:490, tc:0}), 'auto');    // hero check
FrameDiffEngine.processFrame(frame({st:'flop', btn:BTN, cc:FLOP3, pot:10, me:490, tc:0}), 'auto'); // flop开始+hero先行动按钮
FrameDiffEngine.processFrame(frame({st:'flop', btn:[], cc:FLOP3, pot:10, me:490, tc:0}), 'auto');  // hero check
FrameDiffEngine.processFrame(frame({st:'flop', btn:BTN, cc:FLOP3, os:[{seat:2,chips:450,action:'active'}], pot:60, me:490, tc:50}), 'auto'); // 对手bet+hero按钮(tc=50完成态→记call)
let alD = dumpAl('D limped·hero check后对手bet');
check('D.getStreetActs(flop) [hero先行动: our_turn在前]', FrameDiffEngine.getStreetActs('flop')[0], 'our_turn');
check('D.isOppLeadRaise(flop) [hero已行动 → false]', FrameDiffEngine.isOppLeadRaise('flop'), false);
check('D.isOppRaiseAfterHero(flop) [对手bet记call → false(保守)]', FrameDiffEngine.isOppRaiseAfterHero('flop'), false);

// ================================================================
// 场景E: 对手 fold（街内首个动作）+ 边界
// ================================================================
FrameDiffEngine.reset();
FrameDiffEngine.processFrame(frame({st:'flop', btn:[], cc:FLOP3, os:[{seat:2,chips:500,action:'fold'}], pot:235, me:380, tc:0}), 'auto');
let alE = dumpAl('E 对手fold');
check('E.isOppLeadRaise(flop) [对手fold → false]', FrameDiffEngine.isOppLeadRaise('flop'), false);
check('E.空/无该街.getStreetActs(turn)', FrameDiffEngine.getStreetActs('turn'), null);
check('E.空.isOppRaiseAfterHero(river)', FrameDiffEngine.isOppRaiseAfterHero('river'), false);
FrameDiffEngine.reset();
check('E.reset后.isOppLeadRaise(flop)', FrameDiffEngine.isOppLeadRaise('flop'), false);
check('E.reset后.getStreetActs(preflop)', FrameDiffEngine.getStreetActs('preflop'), null);

// ================================================================
// 场景F: preflop 街（无 new_street 条目时，新方法借条目自带st可查询——
//         相比老方法getOppPostflopAction依赖new_street永返null是改进；决策层暂不依赖此能力）
// ================================================================
FrameDiffEngine.reset();
FrameDiffEngine.processFrame(frame({st:'preflop', btn:BTN, pot:15, me:500, tc:0}), 'auto');
FrameDiffEngine.processFrame(frame({st:'preflop', btn:[], os:[{seat:2,chips:380,action:'active'}], pot:135, me:380, tc:0}), 'auto');
let alF = dumpAl('F preflop无new_street');
check('F.getStreetActs(preflop) [条目自带st→可查询(改进)]', FrameDiffEngine.getStreetActs('preflop'), ['raise']);
checkOld('F.getOppPostflopAction(preflop) [老方法依赖new_street→null]', FrameDiffEngine.getOppPostflopAction('preflop'), null);

process.stdout.write(`\n=== 结果: ${pass} pass / ${fail} fail ===\n`);
process.exit(fail > 0 ? 1 : 0);
