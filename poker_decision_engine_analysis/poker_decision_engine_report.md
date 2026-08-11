# 青云扑克App PokerDecisionEngine 策略引擎完整分析报告

**完成日期**: 2026-08-11  
**分析版本**: v2.9.180 (poker_helper.html, 12,426行)  
**代码仓库**: /app/data/所有对话/主对话/poker-app-latest/

---

## 1. 策略引擎相关文件清单

### 1.1 核心策略文件

| 文件 | 行数 | 职责 |
|------|------|------|
| `app/src/main/assets/poker_helper.html` | 12,426 | **核心策略引擎**(RTA实时决策)，内含StrategyEngine、DRTA、NashPushFold、OppProfiler、HandStateMachine等15+模块 |
| `strategy_engine_v2155.js` | 429 | 独立的策略引擎v2.1.5.5（备用/早期版本） |
| `poker.test.js` | 432 | 策略引擎单元测试 |
| `test_v279.js` | 230+ | v2.7.9策略测试用例 |

### 1.2 Android端调用/桥接文件

| 文件 | 大小 | 职责 |
|------|------|------|
| `FloatingService.kt` | 124,933 B | **主服务**：WebView加载poker_helper.html、调用onVisionResult()、AndroidBridge JS接口 |
| `VisionApiClient.kt` | 67,364 B | Vision API客户端，VisionResult数据类、toJson()序列化（Kotlin到JS字段名转换） |
| `LocalSceneRecognizer.kt` | 35,689 B | 本地CV识别，输出VisionResult |
| `CardRecognizer.kt` | 41,440 B | 牌面识别（模板匹配+本地CV） |
| `GameModeConfig.kt` | 19,784 B | 游戏模式/平台配置（GG/标准/短牌等） |

### 1.3 内嵌于 poker_helper.html 的主要模块（按行号）

| 模块 | 起始行 | 功能定位 |
|------|--------|----------|
| G 全局状态 | 434 | 决策上下文（pot/stk/bet/pos/hole/comm等） |
| mcVsRangeAsync / WebWorker | 446-497 | 蒙特卡洛equity计算（Web Worker隔离，5秒超时降级） |
| GTO_FREQ 频率表 | 512-528 | 关键场景GTO频率（pre3bet/cbet/riverValue） |
| PotConfidence 底池置信度 | ~530+ | EMA多帧平滑、跨街约束、智能纠正 |
| ActionLine 行动线 | 1534 | 翻前/翻后行动线追踪 |
| OppProfiler 对手画像 | 1607 | VPIP/PFR/3bet/CBet/FoldToCBet等统计采集 |
| HandStateMachine (HSM) | 2720 | 手牌状态机（角色推断、子状态、策略路径） |
| **StrategyEngine 策略引擎核心** | 2852-4417 | **GTO频率表驱动的决策引擎**（1,566行） |
| decidePreflop 翻前决策 | 3030 | 纳什短码优先，RFI/3bet/4bet/Squeeze |
| decidePostflop 翻后决策 | 3271 | CBet/FCB/CR/Donk/Barrel/River全流程 |
| _icmPressure ICM压力计算 | 3565-3601 | 锦标赛ICM乘数（fold/raise/call） |
| _applyPipeline 统一调整管道 | 3623-3672 | SPR/3bet/多人/剥削/范围/ICM叠加调整 |
| _computeFullEVCore EV核心计算 | 3944-4010 | 价值bet/bluff/raise/call/fold EV比较 |
| _turnFullEnumerateEV | 4011-4084 | 转牌完全枚举EV（MC: 2000-5000次迭代） |
| _riverFullEnumerateEV | 4085-4154 | 河牌精确equity + EV决策 |
| _riverDecision 河牌决策 | 4155-4218 | 按面纹理+位置的策略表驱动 |
| ExploitAdjuster 剥削闭环 | 4286-4340+ | 基于历史结果的自适应剥削 |
| DRTA 动态实时调整 | 4488-4777 | 对手类型识别、权重计算、bluff频率 |
| multiPlayerEquity 多人equity | 7212 | 多人底池equity分摊 |
| **decide() 主入口** | 8207-8239 | 决策总入口 + FallbackStrategy兜底 |
| _decideInner() 内部决策 | 8240+ | ICM计算、StrategyEngine、对手偏移、Equity底线 |
| preF 旧翻前引擎 | 8606+ | 兼容旧决策路径（pre-SE时代） |
| postF 旧翻后引擎 | 9025+ | 兼容旧决策路径 |
| onVisionResult 数据入口 | 10708+ | Android到JS 数据注入与状态同步 |
| NashPushFold 纳什短码 | 12043-12104 | <15BB push/fold 表（6个位置） |

---

## 2. poker_helper.html 策略引擎深度分析

### 2.1 整体架构：双通道决策模式

```
decide() [L8207]
  └─ _decideInner() [L8240]
       ├─ ICM压力计算 (_icmPressure) [L8244-8259]
       ├─ StrategyEngine 路由 [L8261]
       │    ├─ cc > 0 → StrategyEngine.decidePostflop(k)
       │    └─ cc == 0 → StrategyEngine.decidePreflop(k)
       │    └─ SE未匹配 → preF(k) / postF(k) (旧引擎回退)
       ├─ Step1: 对手类型偏移 (applyOppActionShift) [L8283]
       ├─ Step2: Equity底线 (applyEquityGuard) [L8288]
       ├─ PKO赏金赛调整 [L8295+]
       └─ 颜色映射 + 输出
```

**关键设计特征**:
- **GTO频率表优先**：StrategyEngine启用时优先使用预计算GTO频率表
- **旧引擎兜底**：SE未匹配场景回退到preF/postF旧路径
- **两层后处理**：策略引擎输出后，再经过对手偏移和Equity底线两道防线
- **异常兜底**：decide()有try-catch + FallbackStrategy，崩溃时不会输出空白

### 2.2 翻前决策流程 (decidePreflop, L3030-3270)

```
decidePreflop(k)
  ├─ [1] 纳什短码优先 (spr < 1.5 且 effBB ≤ 15)
  │    └─ NashPushFold.decide() → push/fold
  │       └─ 锦标赛ICM: 短码范围再收紧10% [L3044-3048]
  ├─ [2] equity计算: eQ(k, ap-1) + 投机加成 + 位置调整 + Ante
  ├─ [3] 对手定量剥削 (OppProfiler数值调整) [L3080-3116]
  │    ├─ 开池场景: 盲注位FoldToCBet/3bet率 → eq 加减调整
  │    └─ 面对加注: 加注者PFR/FoldTo3bet → eq 加减调整
  ├─ [4] 场景路由:
  │    ├─ open/check → RFI表 (_RFI[5max位置]) + 频率混合
  │    ├─ raise (有callers) → Squeeze表 (_SQUEEZE) 
  │    ├─ raise → 3bet表 (_3B[vs_X][from_Y]) + _adjFreq3bet
  │    ├─ reraise → 面对3bet表 (_F3B[位置]) + 4bet/call/fold
  │    └─ (limp场景通过adjustRangeForLimp处理)
  └─ [5] 剥削调整 + sizing + 返回结果
```

**翻前GTO表数据**:
- `_RFI`: 5位置(UTG1/MP/CO/BTN/SB) × ~100手牌 = **~400+个频率数据点**
- `_3B`: vs_UTG1/vs_CO/vs_BTN × from_MP/CO/BTN/SB/BB × ~20-30手牌 = **~300+个条目**
- `_F3B`: 5位置 × ~20手牌 = **~100个条目**

**问题与局限**:
1. **仅支持5-Max**：_RFI只有UTG1/MP/CO/BTN/SB 5个位置，6-Max缺UTG，9-Max完全无对应表
2. **手牌粒度有限**：每个位置约70-100手牌，vs 标准GTO解的169种手牌组合有差距
3. **Limp pot策略薄弱**：通过adjustRangeForLimp [L6436]简单调整，缺乏专门的limp/resteal策略表
4. **Straddle支持简陋**：仅标记is_straddle，实际策略调整极少 [L3132-3140]
5. **位置到5max映射有损**：通过_pos5(p)映射，6/7/8/9人桌信息丢失

### 2.3 翻后决策流程 (decidePostflop, L3271-3557)

```
decidePostflop(k)
  ├─ equity计算 (河牌精确枚举 / 翻牌转牌蒙特卡洛)
  │    ├─ river: riverExactEquity() → 完全枚举精确值
  │    └─ flop/turn: mcVsRange(hole, comm, oppRange, 2000-5000迭代)
  ├─ 场景识别: 3bet底池 / 多人池 / SPR区
  ├─ SPR区频率调整 (_sprAdj)
  ├─ 场景路由:
  │    ├─ PFR + check + flop → CBet (_CBET_IP/_CBET_OOP[7纹理×16hc])
  │    ├─ OOP + raise + flop → Check-Raise (_CR[纹理×16hc])
  │    ├─ OOP + check + (flop/turn) → Donk (_donkDecision)
  │    ├─ raise + !river → 面对下注防守 (_facingCBet → _FCB表)
  │    │    └─ turn: 优先_turnFullEnumerateEV (EV驱动) → 失败回退FCB表
  │    ├─ PFR + reraise + (flop/turn) → 面对Check-Raise (_facingCR)
  │    ├─ turn barrel: PFR + check + turn → _turnBarrel (_TB_OOP表)
  │    └─ river → _riverDecision → _riverFullEnumerateEV (EV驱动)
  └─ _applyPipeline 统一调整 (SPR/3bet/多人/剥削/范围/ICM)
```

**翻后策略表数据结构**:
- **牌面纹理**：7类（dry_high / mid_dry / wet_conn / low_conn / flush_draw / paired / two_tone）
- **手牌类别**：16级（0=坚果 → 15=空气，含set/两对/超对/顶对/听牌等）
- **位置**：IP / OOP 分别建表
- **CBet表**：7纹理 × 16hc × [freq, small%] = 224条目
- **FCB防守表**：7纹理 × 3尺度(s/m/l) × 16hc × {c,r,f} = 1,008条目/位置

**关键创新点**:
1. **Turn/River EV驱动决策** [L4011, L4085]：不再仅依赖静态频率表，而是通过蒙特卡洛equity + 对手弃牌率 + bet sizing计算真实EV，选最大EV动作
2. **统一调整管道** `_applyPipeline` [L3623]：SPR/3bet底池/多人池/剥削/范围宽度/ICM 6层叠加
3. **15%pot价值阈值** [L3983]：betValue EV - check EV >= 0.15 * pot 才下价值注，避免薄价值
4. **bluff启用条件** [L3985]：仅在"价值bet EV <= check EV"时启用bluff（即真实不占优时才诈唬）

### 2.4 ICM支持分析 (Phase 2)

**位置**：`_icmPressure()` [L3565-3601] + `_detectTournamentPhase()` [L3603-3621]

**集成方式**：
- `_decideInner()`入口计算ICM乘数 [L8244-8259]
- `_applyPipeline()`中应用到频率 [L3660-3672]
- 纳什短码场景额外收紧10% [L3044-3048]

**ICM模型评估**:
_icmPressure 函数根据4种情况给出 fold/raise/call 三个乘数：
1. 短筹码 <15BB: foldMult=1.3, raiseMult=1.2, callMult=0.9
2. 泡沫期 (payBubble±5人, 15-40BB): foldMult=1.5, raiseMult=0.8, callMult=0.7
3. 决赛桌 ≤9人: foldMult=1.2, raiseMult=1.1
4. 进入钱圈后: foldMult×0.9, raiseMult×1.1

**严重问题**:
1. **伪ICM（乘数表，非真实ICM计算）**：没有基于ICM公式的真实计算，只是根据筹码深度和阶段给出固定乘数。真正的ICM需要知道所有玩家筹码分布和奖金结构。
2. **缺少关键输入**：没有奖金结构(payouts)输入，没有奖金结构就无法计算真实ICM美元价值。
3. **avgStack未使用**：函数接收了avgStack参数但实际计算中未使用 [L3569]
4. **inMoney永远为false**：_detectTournamentPhase只返回4个阶段字符串，没有任何路径传入inMoney=true
5. **泡沫估算过于简化**：payBubble = totalPlayers / 8，假设12.5%进入钱圈
6. **只有3个阶段**：early/bubble/final，缺少深筹码阶段、钱圈边缘、决赛桌FT泡沫等细分

### 2.5 纳什Push/Fold分析 (短码<15BB)

**位置**：`NashPushFold`对象 [L12043-12104]，共62行

**数据表**：
- PUSH表：6个位置(sb/btn/co/hj/mp/utg) × 6个BB档位(1/3/5/8/12/15) = 36组范围
- CALL表：3个位置(bb/sb/btn) × 6个BB档位 = 18组范围
- 每组范围包含30-130手牌（1BB最紧，15BB最宽）

**触发条件** [L3037-3041]:
  if spr < 1.5 → _nashEffBB = round(1/max(spr, 0.02)) → 若<=15则启用

**问题与局限**:
1. **触发条件可能有误**：spr < 1.5对应有效筹码=pot×1.5，而15BB短码通常spr更高（比如pot=3BB, stk=15BB → spr=5）。spr<1.5意味着极度短码（<2BB）。
2. **_nashEffBB计算方式存疑**：Math.round(1/spr)得出的是"pot是几个BB"的倒数，不是有效筹码BB数。15BB短码且pot=3BB时spr=5，1/spr=0.2，round后=0，判断错误。
3. **场景覆盖不全**：shouldUseNash只检查check/call场景，但raise场景（面对allin）也应该走纳什call表
4. **无ICM感知的纳什表**：锦标赛模式下只是简单加了10%收紧，真正的ICM Nash需要重新求解
5. **档位跳跃**：只有1/3/5/8/12/15六个档位，中间值向下取整匹配，精度损失较大
6. **不考虑ante/limpers**：这些因素显著影响push/fold范围

### 2.6 对手统计注入分析 (Phase 4)

**数据来源**：
- `OppProfiler` [L1607+]：通过视觉识别的opp_seats和players数组采集
- `DRTA` [L4488-4777]：基于OppProfiler数据的对手类型推断
- `opp_hud` API字段：外部HUD数据注入 [toJson L998]

**采集的统计项**:
| 统计项 | 用途 |
|--------|------|
| VPIP (entered/hands) | 对手类型分类、范围宽度估计 |
| PFR (_pfRaise/hands) | 范围宽度估计 |
| 3bet率 (_pf3bet) | vs 3bet策略调整 |
| CBet Flop率 | Donk/CR策略调整 |
| FoldToCBet | CBet频率调整、价值/诈唬比 |
| AF (攻击系数) | 对手松紧判断 |
| 筹码历史 (chipsHistory) | 筹码深度/趋势 |
| stackType | 短码/标准/深码 |

**注入决策的链路**：
1. **定量eq调整** [L3080-3116]：翻前根据盲注位弃牌率/3bet率直接加减eq
2. **频率调整** [ExploitAdjuster, L4289+]：基于foldToCBet/cbetFlop/AF调整bet/raise频率
3. **类型分类** [DRTA, L4488+]：7种类型→粗粒度剥削
4. **范围宽度估计** [RangeEstimator, L4238+]：从类型→范围宽度→组合数→弱牌比例→频率调整

**问题与局限**:
1. **统计采样不足**：对手统计依赖视觉识别准确率，每手牌只能采集1-2个数据点
2. **对手身份识别粗糙**：通过座位号+昵称匹配，多桌轮换容易串号
3. **翻后统计项太少**：只有CBet Flop和FoldToCBet Flop，缺少Turn CBet、FoldToTurnBet、Flop CR等
4. **无位置维度统计**：所有统计不区分位置（UTG vs BTN的VPIP含义完全不同）
5. **剥削深度有限**：主要是频率乘数调整，没有基于对手range构建的精确剥削解
6. **VPIP/PFR在策略中使用浅**：主要用于类型分类，没有直接参与ev计算或范围构建
7. **反剥削机制简单**：CounterExploit只有bluff减少一种调整

### 2.7 价值bet阈值 & bluff启用条件

**价值bet阈值 (15%pot规则)** [L3983]:
- 规则：价值下注EV - check EV >= 0.15 * pot 才下注
- 意图：防止薄价值下注，减少被raise/反打的风险
- 问题：固定15%阈值过于粗暴，应根据对手类型、位置、牌面纹理、SPR深度、是否还有后续街动态调整

**bluff启用条件** [L3978, L3985]:
- 规则1：eq < 0.40 才考虑bluff [L3978]
- 规则2：仅在"价值bet EV <= check EV"时启用bluff [L3985]
- 优点：保证价值牌和诈唬牌的策略分离
- 问题：
  - 不考虑半诈唬（semi-bluff）：听牌既有showdown value又有弃牌赢率
  - eq>40%的强听牌（flush draw + overcards, eq≈45%）也应该半诈唬
  - 价值和诈唬使用相同的sizing候选，GTO中有时诈唬用更小尺度

### 2.8 EV计算精度和性能

**计算方式分层**:
| 场景 | 方法 | 迭代数 | 精度 | 耗时估计 |
|------|------|--------|------|----------|
| 河牌 | riverExactEquity 完全枚举 | - | 精确 | ~20ms |
| 翻/转牌 | mcVsRange 蒙特卡洛 | 2000-5000 | ±1-2% | ~10-30ms |
| 翻前 | eQ(k, ap-1) 表查 | - | 粗粒度 | 即时 |

**性能优化**：
1. Web Worker隔离MC计算 [L446-497]，5秒超时降级
2. preEq复用 [L4019, L4088]，避免重复计算
3. 迭代数自适应：大注翻倍，中注1.3x
4. 决策缓存 _decisionCache [L436-437]，200条上限
5. 策略表优先：大多数场景走静态频率表

**精度问题**：
1. **对手范围假设过粗**：getOppRange只有wet/dry两档，真实对手范围应该根据位置、行动线、牌面纹理精细构建
2. **MC迭代数偏低**：2000次迭代→标准误差约±1.5%，边缘决策可能误判
3. **翻前eQ精度有限**：基于ap-1个对手的粗略equity
4. **多人equity分摊简单**：multiPlayerEquity不是真实的多人全桌equity计算

---

## 3. Android → JS 数据传递链路分析

### 3.1 链路总览

```
视觉识别层 (VisionApiClient / LocalSceneRecognizer)
  │ 输出: VisionResult (Kotlin data class, L107-144)
  ▼
VisionApiClient.toJson() [L969-1013]
  │ 转换: Kotlin字段 → snake_case JSON (20+字段)
  ▼
FloatingService
  │ 方式: webView.evaluateJavascript("onVisionResult($json)") [L2197]
  ▼
poker_helper.html :: onVisionResult(data) [L10708]
  │ 解析: 20+字段 → G.xxx 全局状态 (100+行赋值逻辑)
  ▼
decide() → 返回决策
  │ 方式: AndroidBridge.showAdvice() @JavascriptInterface
  ▼
FloatingService :: showAdvice() → 悬浮窗/通知
```

### 3.2 VisionResult → JSON 字段映射 (Kotlin到JS)

| Kotlin字段 (VisionResult) | JSON字段 (toJson) | JS端G.变量 | 备注 |
|---------------------------|-------------------|------------|------|
| isPokerTable | is_poker_table | - | 入口判断 |
| holeCards | hole_cards | G.hole[] | rank+suit |
| communityCards | community_cards | G.comm[] | rank+suit |
| potSize | pot_size | G.pot (BB) | 经BB换算 |
| playerChips | my_chips | G.stk (BB) | 经BB换算 |
| totalPlayers | total_players | G.tt | 总人数 |
| activePlayers | active_players | G.act | 活跃人数 |
| myPosition | my_position | G.pos | 转换后 |
| street | street | G.phase (pre/post) | 间接 |
| toCall | to_call | G.bet (BB) | 经BB换算 |
| minRaise | min_raise | - | **未使用** |
| buttons | buttons | G.buttons, G.scene | 场景推断 |
| blindSB / blindBB | blind_sb / blind_bb | G._lockedBB | BB换算基准 |
| ante | ante | G.ante | |
| players | players / opp_seats | G.players, G.oppSeats | 两种格式 |
| dButtonPosition | d_button_position | G.pos (经映射) | 按钮位置→座位 |
| showdownCards | showdown_cards | - | **未注入G状态** |
| oppHud | opp_hud | - | OppProfiler.applyHudData |
| isStraddle | is_straddle | G.is_straddle | V2.9.200+ |
| isBombPot | is_bomb_pot | G.is_bomb_pot | |
| isInsurance | is_insurance | G.is_insurance | |
| isPKO | is_pko | G.is_pko | |
| gameMode | game_mode | G.game_mode | cash/tournament |
| platform | platform | G.platform | |
| localSuitUsed | local_suit_used | - | **未注入G状态** |
| suitUncertain | suit_uncertain | - | **未注入G状态** |

### 3.3 可能丢失或格式不匹配的字段

**高风险字段**:

1. **min_raise 完全未使用** [VisionResult L117]:
   - 策略引擎只使用G.bet（to_call）来判断跟注额，不使用min_raise
   - 导致的问题：计算raise sizing时，不知道对手加注到了多少，可能计算出的raise size不合法

2. **showdown_cards 未进入策略决策**：
   - 摊牌信息只用于HandHistory展示，没有用于更新对手范围/剥削模型
   - 巨大浪费：摊牌是验证对手范围的最有价值数据

3. **suit_uncertain 未注入G**：
   - 本地CV识别中花色不确定时，策略引擎应该降低置信度或增加安全边际
   - 目前只在前端展示，不影响决策

4. **local_suit_used 未注入G**：
   - 同上，花色来自本地推断时equity计算精度下降

5. **players 数组结构不匹配**：
   - Kotlin端：{position, bet, chips, active, nickname}
   - JS端：直接赋值G.players=data.players [L11395]，但后续使用时假设的字段名可能不同
   - 同时提供了opp_seats格式，两套数据可能不一致

6. **BB换算精度损失** [L11159, L11170, L11188]:
   - pot/stk/bet都被转为**整数BB**（Math.round）
   - 小底池场景精度损失严重（比如pot=1.5BB被round到1或2）
   - 短码场景影响更大：10BB差1BB就是10%误差

7. **game_mode 与 game_type 命名易混淆**：
   - G.game_mode: cash/tournament（ICM用）[L8245]
   - G.game_type: normal/rush_cash（游戏子类型）[L11337]
   - 两个变量含义不同，但命名容易混淆

8. **按钮到场景推断的脆弱性**：
   - detectSceneFromButtons(data.buttons, street, activeP) [L10879]
   - 从按钮文字（中文/英文）推断当前场景
   - 不同平台按钮文字不同，可能误判场景，导致完全错误的决策路径

9. **_facing3bet 推断可能不准** [L11454-11475]:
   - 通过底池大小+to_call金额反推是否在3bet底池
   - 多人底池、limp pot场景容易误判

---

## 4. 策略引擎升级空间与改进方向

### 4.1 当前缺失的高级策略

#### 4.1.1 GTO范围平衡（最大缺口）

**现状**：基于静态频率表的混合策略，虽然有GTO_FREQ表和Math.random()混合，但本质上是"查表+随机化"，不是真正的GTO解。

**缺失**：
- 无范围构建：对手的range不是从行动线逐步缩窄，而是固定的wet/dry两档
- 无组合数平衡：价值组合与诈唬组合比例未按GTO最优比例（1-α）计算
- 无河牌下注尺度最优化：sizing选择凭经验（33%/66%/100%pot），不是从均衡解得出
- 无check-raise vs call vs fold的精确频率比

**升级建议**：
1. **PioSolver格式预解表集成**：为常见场景（翻牌dry/wet/paired × 3种pot × 2种位置）预存GTO解，包含每个组合的行动频率和EV
2. **实时范围缩窄**：根据每条街的行动（check/bet/call/raise）用贝叶斯更新对手range
3. **组合数级别的诈唬-价值平衡**：根据当前手牌类别和对手range，计算最佳bluff频率

#### 4.1.2 多路底池优化

**现状**：_isMultiway = _nActive >= 3 [L3310]，只做了简单的频率乘数调整（cbet×0.7，bluff×0.3）。

**缺失**：
- 无位置加权：多人池中位置价值指数级增长，当前只区分IP/OOP
- 无多对手范围：对手范围是单人的，没有考虑多个对手的range交集
- 无implied odds多人修正：跟注的隐含赔率在多人池完全不同
- 无保护性下注计算：多人池中强牌需要下注保护不被draw到，但又不能太大被raise
- 无侧池/分级筹码考虑：allin时的主池/边池计算

**升级建议**：
1. 增加nOpponents维度到所有策略表（2人/3人/4+人）
2. 多人equity计算从简单分摊改为独立多对手MC模拟
3. 实现"pot equity + 位置"的多人池决策框架

#### 4.1.3 位置加权精细化

**现状**：仅分IP/OOP两档（通过_pfOrd字典比较位置先后 [L3288-3291]）。

**缺失**：
- 翻后位置只有"先手/后手"二元，没有BTN vs CO vs MP vs BB这样的位置梯度
- 位置对策略的影响非线性：盲注位vs按钮位的差距远大于MP vs CO
- 无位置+牌力的交互效应：强牌在OOP和IP打法差异极大

**升级建议**：
1. 翻后策略表增加位置维度（BB/SB/EP/MP/LP 5档）
2. 位置到equity调整从简单加减改为乘数（不同牌力对位置敏感度不同）
3. OOP时增加check-raise频率表的细分（按位置而非统一OOP）

#### 4.1.4 翻前策略深度不足

**缺失**：
- 4bet/5bet博弈：_F3B只有call/4b/fold三选一，没有4bet规模优化、5bet应对
- 隔离加注(isolation raise)：vs limpers的隔离策略表缺失
- 3bet规模优化：固定3x，没有根据位置/对手类型/筹码深度调整
- 防守范围vs不同位置加注者的差异：虽然有vs_UTG1/vs_CO/vs_BTN，但粒度粗
- 翻前squeeze策略只有表，没有call 3bet vs squeeze的选择比较

### 4.2 现有策略的精度/性能瓶颈

#### 4.2.1 精度瓶颈

| 瓶颈 | 影响 | 优先级 | 改进方向 |
|------|------|--------|----------|
| 对手范围只有wet/dry两档 | equity误差±5-10% | P0 | 按位置+行动线+牌面纹理细分10+档range |
| MC迭代2000-5000次 | ±1-2%标准误差 | P1 | 关键决策点增加到10000次 |
| 翻前eQ粗略 | 决策依赖equity的场景误差大 | P1 | 用预计算的精确equity表 |
| 整数BB精度 | 短码/小底池误差大 | P2 | 保留1位小数BB精度 |
| 7类对手类型 | 剥削粒度过粗 | P2 | 连续统计量直接驱动策略 |

#### 4.2.2 性能瓶颈

| 瓶颈 | 耗时 | 影响 | 改进方向 |
|------|------|------|----------|
| 河牌精确枚举 | ~20ms | 还可以接受 | 已做缓存，可考虑precompute表 |
| MC 5000次迭代 | ~30-50ms | 高下注场景 | 可以接受 |
| WebWorker消息开销 | ~5ms | 可忽略 | 已实现 |
| onVisionResult整体处理 | 含DOM操作 | UI阻塞 | 决策计算放Worker，UI渲染放主线程 |
| 12,426行单文件 | 加载+解析慢 | 启动延迟 | 拆分为模块化JS文件，按需加载 |

### 4.3 对手统计的更深层次利用

#### 4.3.1 短期可实现

1. **VPIP/PFR直接调整范围**：
   - 现状：VPIP/PFR只用于分类→类型→粗乘数
   - 升级：用VPIP/PFR数值直接缩放对手preflop range宽度（线性映射）

2. **翻后统计项扩充**：
   - 增加：Turn CBet频率、FoldToTurnBet、FoldToRiverBet、Flop CR频率、River XR频率
   - 用途：更精确的EV计算中的弃牌率/跟注率估计

3. **位置维度统计**：
   - 每个对手按位置（BTN/CO/MP/EP/盲注）分开统计VPIP/PFR/CBet
   - 这样面对不同位置的对手下注，能使用不同的弃牌率估计

4. **摊牌信息利用**：
   - 现状：showdown_cards完全未用于策略
   - 升级：每次摊牌后更新对手range估计（贝叶斯更新），验证/修正我们对对手的画像

#### 4.3.2 中期可实现

5. **频率剥削精确化**：
   - 从乘数调整改为"基于对手range的精确剥削解"
   - 给定对手range和我们的手牌，计算max-EV行动（而不是调频率）

6. **游戏流感知**：
   - 识别桌面节奏（紧/松/凶/被动）并全局调整
   - TablePulse [L5931] 已有雏形，但未深度整合

7. **多桌对手画像迁移**：
   - 同平台/同级别对手特征可迁移
   - 建立"级别画像"（NL25/NL50/NL100平均统计）

### 4.4 锦标赛后期 ICM 泡沫因子优化

#### 4.4.1 现有问题总结

- 不是真实ICM计算，只是阶段×乘数表
- 缺少奖金结构输入
- inMoney参数永远为false
- avgStack参数未使用
- 泡沫估算粗糙（总人数/8）
- 只影响频率，不影响sizing和跟注阈值

#### 4.4.2 升级路径

**Phase 1: 基础ICM集成（中优先级）**
1. 增加奖金结构输入（payouts数组）
2. 实现Simplified ICM（独立筹码模型）：给定所有玩家筹码分布和奖金结构，计算每个位置的ICM美元价值
3. 用ICM价值替代chip EV进行决策：
   - 跟注阈值：ICM(call) vs ICM(fold) 的比较
   - 弃牌收益：不是0，而是fold后的ICM价值

**Phase 2: 泡沫期精确化（高优先级）**
1. 钱圈泡沫位置精确计算（根据当前总人数和奖金结构）
2. 泡沫因子随"距离钱圈"动态调整：越接近泡沫越紧
3. 不同筹码深度的泡沫策略不同：
   - 大筹码：利用泡沫剥削短码（更多施压）
   - 中筹码：最脆弱（最紧）
   - 短码：反而可以稍微放宽（已经没什么可失去的）

**Phase 3: 决赛桌ICM（低优先级）**
1. 决赛桌位置支付权重
2. 单挑阶段的ICM=chip EV（50/50）
3. 3人桌/4人桌的ICM特殊形态（小盲位置特殊）

### 4.5 其他关键升级点

#### 4.5.1 短码纳什表修正（P0）
- 修复spr和BB的换算关系
- 扩展到20BB（行业标准）
- 增加ante/limpers场景
- 锦标赛ICM纳什表（独立求解）

#### 4.5.2 牌面纹理细化（P1）
- 从7类扩展到20+类：具体区分高牌/低牌/同花面/顺面/对子面/双同花面等
- 加入牌面动力学：转牌/河牌对range的影响（改善/恶化/中性）

#### 4.5.3 下注尺度连续化（P1）
- 从离散的小/中/大尺度，改为基于EV优化的连续尺度
- 河牌不同牌力级别对应不同最优下注尺寸

#### 4.5.4 代码架构改进（P2）
- 12,426行单文件→模块化拆分（engine/evaluation/opponent/ui/icm等）
- 单元测试覆盖率提升（目前只有poker.test.js 432行）
- TypeScript化或至少JSDoc类型标注

---

## 5. 可操作的升级优先级清单

| 优先级 | 升级项 | 预期收益 | 工作量 |
|--------|--------|----------|--------|
| **P0** | 修复纳什push/fold的spr↔BB换算错误 | 短码场景决策正确性 | 小 |
| **P0** | 对手范围从wet/dry两档扩展到位置+纹理细分 | equity精度提升5-10% | 中 |
| **P0** | pot/stk/bet保留小数BB精度 | 短码/小底池精度提升 | 小 |
| **P1** | 实现真实ICM计算（需要奖金结构输入） | 锦标赛决策质量大幅提升 | 大 |
| **P1** | 翻前策略扩展到6-Max完整范围 | 覆盖最主流游戏形式 | 中 |
| **P1** | 增加翻后统计项（Turn CBet/FCB/CR等） | 剥削深度提升 | 中 |
| **P1** | 15%pot阈值动态化（对手/位置/牌面关联） | 价值下注更精准 | 小 |
| **P1** | 半诈唬(semi-bluff)从bluff中独立出来 | 听牌决策优化 | 中 |
| **P2** | 多人底池策略精细化（n对手维度） | 多人池胜率提升 | 大 |
| **P2** | 位置维度从IP/OOP→5档位置 | 位置策略精度提升 | 中 |
| **P2** | 摊牌信息反馈更新对手画像 | 长期剥削效果提升 | 中 |
| **P2** | 代码模块化拆分 + 单元测试 | 可维护性提升 | 大 |

---

*报告基于对 poker_helper.html (12,426行)、FloatingService.kt、VisionApiClient.kt、LocalSceneRecognizer.kt 的静态分析。所有行号引用均来自实际代码文件。*
