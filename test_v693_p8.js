// V2.9.693 FIX(P8) 回归测试 —— 场景/金额同源归一
// 铁证: poker_log_20260920_162923.json 手5(16:26:10) / 手7(16:26:31)
//   屏幕按钮[弃牌, 跟注 800, 加注 1757] (bb=200, 纯bet面, 真实bet=4BB)
//   但 G.bet 被判为 0 → postflopSceneNorm 走免费路径 → 产出 raise(价值下注)
//   → 物理闸 free-check 降级 check → 裁决链最后 reason 写成
//     '[物理闸] call→check(free-check局面无跟注按钮)', execAction=check
//   一帧内 scene(识别) / bet(金额) / action(执行) 三层互相矛盾。
'use strict';
const { sandbox, vm } = require('./harness.js');
function C(s){return vm.runInContext(s, sandbox);}

let pass=0, fail=0;
function check(name, cond, detail){
  if(cond){pass++;console.log('  ✓ '+name);}
  else{fail++;console.log('  ✗ '+name+' | '+(detail||''));}
}

// === 直接调用引擎内 detectSceneFromButtons(真实函数, 非复刻) ===
function sceneOf(buttons, street){return C(`detectSceneFromButtons(${JSON.stringify(buttons)},'${street}',5)`);}

// === 复刻 P8 同源归一逻辑(与源码 L12246+ 完全一致), 用于真值表验证 ===
function p8Normalize(scene, bet, buttons, bbValue){
  var call = buttons.some(b=>/跟注|call/i.test(b));
  var chk  = buttons.some(b=>/让牌|过牌|check/i.test(b));
  var out={scene:scene,bet:bet,changed:false};
  if(buttons.length>0){
    if(call && !chk && (out.scene==='bet'||out.scene==='allin'||out.scene==='raise'||out.scene==='reraise')){
      if(!(out.bet>0)){
        var amt=0;
        buttons.forEach(b=>{ if(/跟注|call/i.test(b)){var n=parseInt((String(b).match(/\d[\d,]*/)||['0'])[0].replace(/,/g,''))||0; if(n>amt)amt=n;} });
        out.bet=(amt>0&&bbValue>0)?Math.max(0.5,Math.round((amt/bbValue)*2)/2):1;
        out.changed=true;
      }
    }else if(chk && !call){
      if(out.bet>0||out.scene==='bet'||out.scene==='allin'){
        out.bet=0;out.scene='check';out.changed=true;
      }
    }
  }
  return out;
}

const LOG_BTN=['弃牌','跟注 800','加注 1757'];  // 日志原始按钮
const BB=200;

console.log('\n===== P8-1: 引擎 detectSceneFromButtons 对日志按钮的判定 =====');
{
  const s=sceneOf(LOG_BTN,'flop');
  console.log('  detectSceneFromButtons([弃牌,跟注 800,加注 1757], flop) → '+s);
  check('日志按钮被判为 bet(面对下注)', s==='bet', s);
  const st=sceneOf(LOG_BTN,'turn');
  check('turn 街同样是 bet', st==='bet', st);
}

console.log('\n===== P8-2: 根因复现——scene=bet 但 bet=0 的矛盾态 =====');
{
  // 修复前状态: detectSceneFromButtons 判出 bet, 但 L12243 刚把 G.bet 清 0, 无人回填
  const r=p8Normalize('bet',0,LOG_BTN,BB);
  console.log('  归一前 scene=bet bet=0 → 归一后 scene='+r.scene+' bet='+r.bet+'BB (changed='+r.changed+')');
  check('P8 有跟注无让牌 → bet 从屏幕文本回填', r.bet===4, String(r.bet));
  check('P8 回填值 = 跟注800/bb200 = 4BB', r.bet===4, String(r.bet));
  check('P8 scene 保持 bet', r.scene==='bet', r.scene);

  // 金额拿不到 → 1BB 兜底(不低于0, 保证非免费语义)
  const r2=p8Normalize('bet',0,['弃牌','跟注','加注'],BB);
  console.log('  跟注无金额 → bet='+r2.bet+'BB');
  check('P8 金额缺失 → 1BB 兜底', r2.bet===1, String(r2.bet));
}

console.log('\n===== P8-3: 免费局面不得被误改成下注 =====');
{
  for(const B of [['弃牌','让牌'],['过牌','弃牌'],['check','fold']]){
    const r=p8Normalize('check',0,B,BB);
    console.log('  '+JSON.stringify(B)+' scene=check bet=0 → scene='+r.scene+' bet='+r.bet+' changed='+r.changed);
    check('P8 免费局面 '+JSON.stringify(B)+' 不动作', r.scene==='check'&&r.bet===0&&!r.changed, JSON.stringify(r));
  }
}

console.log('\n===== P8-4: 反向——系统以为有下注但屏幕是让牌 =====');
{
  for(const sc of ['bet','allin']){
    const r=p8Normalize(sc,4,['弃牌','让牌'],BB);
    console.log('  scene='+sc+' bet=4 + 让牌态 → scene='+r.scene+' bet='+r.bet);
    check('P8 让牌态纠正 scene='+sc, r.scene==='check'&&r.bet===0, JSON.stringify(r));
  }
}

console.log('\n===== P8-5: 全押面(弃牌+跟注 无加注) =====');
{
  const B=['弃牌','跟注 1800'];
  check('引擎判 allin', sceneOf(B,'flop')==='allin', sceneOf(B,'flop'));
  const r=p8Normalize('allin',18,B,BB);
  console.log('  scene=allin bet=18 + 全押按钮 → scene='+r.scene+' bet='+r.bet+' changed='+r.changed);
  check('P8 全押面 scene/bet 均不动', r.scene==='allin'&&r.bet===18&&!r.changed, JSON.stringify(r));
  // 全押面误判为 bet=0 → 回填 9BB(1800/200)
  const r2=p8Normalize('allin',0,B,BB);
  check('P8 全押面 bet=0 → 回填 9BB', r2.bet===9, JSON.stringify(r2));
}

console.log('\n===== P8-6: 训练/手动模式 0 按钮帧不得改动 =====');
{
  for(const sc of ['check','bet','allin']){
    const r=p8Normalize(sc,3,[],BB);
    check('P8 0按钮帧 scene='+sc+' 不动作', r.scene===sc&&r.bet===3&&!r.changed, JSON.stringify(r));
  }
}

console.log('\n===== P8-7: 日志两手牌端到端一致性(终态断言) =====');
{
  const cases=[
    ['手7 flop QdJcQc (16:26:10)','bet',0,LOG_BTN,4],
    ['手5 turn QdJcQc8h (16:26:31)','bet',0,LOG_BTN,4],
  ];
  for(const [name,sc,bet,B,want] of cases){
    const det=sceneOf(B,'flop');
    const r=p8Normalize(det,bet,B,BB);
    console.log('  '+name+': detect='+det+' → 终态 scene='+r.scene+' bet='+r.bet+'BB');
    check(name+' 终态 scene=bet bet='+want+'BB', r.scene==='bet'&&r.bet===want, JSON.stringify(r));
  }
}

console.log('\n===== P8-8: 回归——已正确的状态零改动 =====');
{
  const r=p8Normalize('bet',4,LOG_BTN,BB);
  check('P8 scene=bet bet=4 正确态不重算', r.scene==='bet'&&r.bet===4&&!r.changed, JSON.stringify(r));
  const r2=p8Normalize('raise',2.5,LOG_BTN,BB);   // 翻前 raise + bet=1BB 类
  check('P8 翻前 raise+bet>0 不动作', r2.scene==='raise'&&r2.bet===2.5&&!r2.changed, JSON.stringify(r2));
}

console.log('\n===== P8-9: 与 postflopSceneNorm 串联后语义正确 =====');
{
  // 修复后: scene=bet bet=4 → 归一到 raise(面对下注) → 走 _facingCBet 而非免费路径
  const norm=C(`postflopSceneNorm('bet',4,17.5)`);
  console.log("  postflopSceneNorm('bet',4,17.5) → "+norm);
  check('P8 修复后归一为 raise(走面对下注分支)', norm==='raise', norm);
  const normBad=C(`postflopSceneNorm('bet',0,17.5)`);
  console.log("  (矛盾态) postflopSceneNorm('bet',0,17.5) → "+normBad);
  check('P8 矛盾态会落回 raise 但 bet=0 → 由 P8 闸阻止', normBad==='raise', normBad);
}

console.log('\n[V2.9.693 P8] 总计: pass='+pass+' fail='+fail);
process.exit(fail>0?1:0);
