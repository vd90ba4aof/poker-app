# 青云扑克 — 运行时行为测试（补充静态门禁）

`verify_integrity.py` 是**发布前静态门禁**（版本号同步/字符串存在/括号平衡/HID字节断言等92项），
能抓"代码删错、结构缺失、格式错"，但抓不到"代码都在、运行时行为错"的回归。
本目录补的是**运行时行为门禁**，专治 2026-09-01 真机暴露的三类bug。

## 1. engine_smoke.test.js —— JS引擎运行时冒烟（发布前，已接入CI）

- 时机：push 后 CI 自动跑，红牌阻断发布。
- 做法：node + mock DOM 加载 APK 内**真实** `app/src/main/assets/poker_helper.html`，
  喂标准 vision 数据，在 `go()` 决策真正运行的瞬间快照 `G.stk/G.pot`。
- 拦截：**时序倒置**——BB换算（`G.stk=chips/BB`）若在 `go()` 决策之后才执行，
  决策永远用默认 stk=100/pot=10，20BB短码被当100BB深码 → 同花/A牌被错误弃牌。
- 手动跑：`node tests/engine_smoke.test.js`
  - 也可指定引擎：`node tests/engine_smoke.test.js <poker_helper.html路径>`

## 2. verify_post_release.py —— 真机日志行为验收（发版后，人工跑）

- 时机：豪哥真机实测一局、导出 `poker_log_xxx.json` 后跑。
- 做法：解析日志，对三类行为做硬验收（各带样本量门槛，样本不足只警告不红牌）：
  1. **点击路径**：自动点击 fallback 占比 >80% = 精准 buttonPositions 路径失效
     （翻后 raise 会点到黄色%滑块/跟注）。
  2. **数据链路**：全部决策 myChips=100/pot=10 默认值，但 vision 读到真实 chips
     = 换算在决策后才执行（时序倒置）。
  3. **盲注识别**：SB > BB×2 的帧占比 >30% = 中文笔画被误读成数字（如 7100/200）。
- 手动跑：`python3 tests/verify_post_release.py <poker_log_xxx.json>`
- 退出码：0=通过，1=红牌（行为回归），2=日志无法解析/样本不足。
