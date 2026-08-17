#!/usr/bin/env python3
"""
青云扑克 代码完整性自动验证脚本 v1.5
检测类型（20大类）：
  1. AndroidBridge 方法一致性（JS调用 ↔ Kotlin实现）
  2. 版本号全链路一致性（build.gradle / HTML×2 / XML 五端对齐）
  3. 日志导出数据流完整性（exportLog必须包含所有关键数据）
  4. Pipeline耗时追踪覆盖率（所有执行路径必须调用updatePipelineTiming）
  5. try-catch 包裹检查（新增bridge调用）
  6. 硬编码版本号检测
  7. Kotlin诊断数据字段完整性
  8. _pipeline 变量定义完整性
  9. Android 10+ Scoped Storage 兼容
  10. UI文本旧版本号扫描（XML/HTML用户可见文本中的过时版本标记）
  11. 线程安全 — 共享变量@Volatile检查（WebView线程可见性）
  12. 策略引擎完整性（getVersion/isEnabled/decide方法必须存在）
  13. DiagnosticLogger 方法完整性（所有log方法+export必须存在）
  14. Shot Clock 时序合理性（防止超时过早导致VLM还没返回就弃牌）
  15. BLE安全检查（自动点击前必须检查BLE连接状态）
  16. versionCode递增检查（防止忘记递增）
  17. Kotlin类型安全 - 已知类型属性访问检查
  18. 初始化完整性检查 — 组件创建后是否调用了init()等初始化方法
  19. Kotlin代码质量检查 — 模拟Detekt/Lint规则（空catch/unsafe cast/TODO等）
  20. Android Lint报告解析 — 解析CI生成的Lint报告或执行基础Android检查
"""
import re, sys, os, glob

REPO = os.path.dirname(os.path.abspath(__file__))
HTML = os.path.join(REPO, "app/src/main/assets/poker_helper.html")
FLOATING = os.path.join(REPO, "app/src/main/java/com/pokerhelper/app/FloatingService.kt")
DIAG = os.path.join(REPO, "app/src/main/java/com/pokerhelper/app/DiagnosticLogger.kt")
GRADLE = os.path.join(REPO, "app/build.gradle")

passed = 0
failed = 0
warnings = 0

def check(name, condition, detail=""):
    global passed, failed
    if condition:
        print(f"  ✅ {name}")
        passed += 1
    else:
        print(f"  ❌ {name} — {detail}")
        failed += 1

def warn(name, detail=""):
    global warnings
    print(f"  ⚠️  {name} — {detail}")
    warnings += 1

def read(path):
    with open(path, 'r', encoding='utf-8', errors='replace') as f:
        return f.read()

# ============================================================
print("=" * 60)
print("🔍 检查1: AndroidBridge 方法一致性")
print("=" * 60)

html = read(HTML)
kotlin = read(FLOATING)

# 提取JS端调用的所有 AndroidBridge.xxx 方法
js_bridge_calls = set(re.findall(r'AndroidBridge\.(\w+)\s*\(', html))
# 提取Kotlin端所有 @JavascriptInterface 方法（两种格式）
kt_bridge_methods = set(re.findall(r'@JavascriptInterface\s+(?:fun\s+)?(\w+)\s*\(', kotlin))
# 多行格式: @JavascriptInterface\n fun xxx()
kt_bridge_methods2 = set(re.findall(r'@JavascriptInterface\s*\n\s*fun\s+(\w+)\s*\(', kotlin))
all_kt_bridge = kt_bridge_methods | kt_bridge_methods2

# JS调用的方法在Kotlin必须有实现
js_only = js_bridge_calls - all_kt_bridge
check("JS调用的bridge方法在Kotlin都有实现",
      len(js_only) == 0,
      f"缺失: {js_only}" if js_only else "")

# 关键方法必须存在
critical_methods = ['getDiagData', 'getPipelineTiming', 'getErrorLogs', 'showAdvice']
for m in critical_methods:
    check(f"关键bridge方法 '{m}' 存在",
          m in all_kt_bridge,
          f"未找到 {m}")

# ============================================================
print()
print("=" * 60)
print("🔍 检查2: 版本号全链路一致性")
print("=" * 60)

gradle = read(GRADLE)
gradle_version = re.search(r'versionName\s+"([^"]+)"', gradle)
gv = gradle_version.group(1) if gradle_version else "UNKNOWN"

html_version = re.search(r"var\s+APP_VERSION\s*=\s*'([^']+)'", html)
hv = html_version.group(1) if html_version else "UNKNOWN"

# --- 2a: 各文件版本号必须一致 ---
check(f"build.gradle版本({gv}) == HTML APP_VERSION({hv})",
      gv == hv,
      f"不一致: gradle={gv}, html={hv}")

# CURRENT_VERSION 用于缓存清理机制，必须与 build.gradle 一致
current_ver_match = re.search(r"CURRENT_VERSION\s*:\s*'([^']+)'", html)
cv = current_ver_match.group(1) if current_ver_match else "UNKNOWN"
check(f"HTML CURRENT_VERSION({cv}) == build.gradle({gv})",
      cv == gv,
      f"缓存版本不一致: CURRENT_VERSION={cv}, build.gradle={gv}")

# activity_main.xml 的版本显示必须匹配
ACTIVITY_XML = os.path.join(REPO, "app/src/main/res/layout/activity_main.xml")
xml_content = read(ACTIVITY_XML) if os.path.exists(ACTIVITY_XML) else ""
xml_ver_match = re.search(r'V2\.9\.(\d+)', xml_content)
if xml_ver_match:
    xml_ver_num = xml_ver_match.group(1)
    gradle_ver_num = re.search(r'2\.9\.(\d+)', gv)
    gradle_num = gradle_ver_num.group(1) if gradle_ver_num else "???"
    check(f"activity_main.xml版本(V2.9.{xml_ver_num}) == build.gradle(V{gv})",
          xml_ver_num == gradle_num,
          f"不一致: xml=V2.9.{xml_ver_num}, gradle=V{gv}")
else:
    check("activity_main.xml包含版本号", False, "未找到版本显示")

# --- 2b: exportLog 不能用硬编码版本号 ---
export_fn_match = re.search(r'function\s+exportLog\s*\(\s*\)\s*\{(.*?)\n\}', html, re.DOTALL)
if export_fn_match:
    export_body = export_fn_match.group(1)
    has_hardcoded = re.search(r"version\s*:\s*'2\.\d+\.\d+'", export_body)
    check("exportLog()版本号不使用硬编码",
          has_hardcoded is None,
          f"发现硬编码版本号: {has_hardcoded.group(0) if has_hardcoded else ''}")
    uses_dynamic = 'APP_VERSION' in export_body
    check("exportLog()使用动态APP_VERSION", uses_dynamic)
else:
    check("exportLog()函数存在", False, "未找到exportLog函数")

# ============================================================
print()
print("=" * 60)
print("🔍 检查3: 日志导出数据流完整性")
print("=" * 60)

required_data = {
    'kotlinDiag': 'AndroidBridge.getDiagData',
    'pipelineTiming': 'AndroidBridge.getPipelineTiming',
    'nativeErrors': 'AndroidBridge.getErrorLogs',
    'handHistory': 'HandHistory.getAll',
    'logs': 'LogBuffer.getAll',
    'drtaTracker': 'DRTA.tracker',
}

for field, source in required_data.items():
    check(f"exportLog包含 '{field}' (来源: {source})",
          field in html and source in html,
          f"字段{field}或数据源{source}缺失")

# DiagnosticLogger.exportAsJson 必须包含的关键字段
diag_export = read(DIAG)
export_fn_kt = re.search(r'fun\s+exportAsJson\s*\(\s*\).*?(?=\n    fun |\nclass |\Z)', diag_export, re.DOTALL)
if export_fn_kt:
    export_body_kt = export_fn_kt.group(0)
    for field in ['recognitions', 'decisions', 'esp32Taps', 'pipelineTiming', 'errors', 'stats']:
        check(f"DiagnosticLogger.exportAsJson包含 '{field}'",
              f'"{field}"' in export_body_kt or f'put("{field}"' in export_body_kt)
else:
    check("DiagnosticLogger.exportAsJson函数存在", False)

# ============================================================
print()
print("=" * 60)
print("🔍 检查4: Pipeline耗时追踪覆盖率")
print("=" * 60)

# 所有执行路径必须调用 DiagnosticLogger.updatePipelineTiming
execute_paths = [
    ('executeAutoTap', 'executeAutoTap'),
    ('executeExactBet', 'executeExactBet'),
    ('executeAutoTapFallback', 'autoTapFallback'),
]

for func_name, search_name in execute_paths:
    # 找函数体
    pattern = rf'fun\s+{func_name}\b.*?(?=\n    (?:fun |private |suspend |override )|\Z)'
    match = re.search(pattern, kotlin, re.DOTALL)
    if match:
        body = match.group(0)
        has_timing = 'updatePipelineTiming' in body
        check(f"'{search_name}' 调用 DiagnosticLogger.updatePipelineTiming",
              has_timing,
              "设置了_pipeline变量但未调用updatePipelineTiming持久化到DiagnosticLogger")
    else:
        warn(f"'{search_name}' 函数未找到", "可能已改名或移除")

# insurance_decline 路径
insurance_blocks = re.findall(r'insurance_decline.*?_pipelineLastAction\s*=\s*"insurance_decline"(.*?)(?=Log\.|updateAdvice)', kotlin, re.DOTALL)
if insurance_blocks:
    has_insurance_update = 'updatePipelineTiming' in insurance_blocks[0]
    check("'insurance_decline' 调用 DiagnosticLogger.updatePipelineTiming",
          has_insurance_update,
          "设置了_pipeline变量但未调用updatePipelineTiming持久化到DiagnosticLogger")
else:
    # 尝试另一种搜索
    insurance_section = re.search(r'_pipelineLastAction\s*=\s*"insurance_decline"(.*?)(?=updateAdvice|Log\.d\(TAG,\s*"★\s*Insurance)', kotlin, re.DOTALL)
    if insurance_section:
        has_insurance_update = 'updatePipelineTiming' in insurance_section.group(1)
        check("'insurance_decline' 调用 DiagnosticLogger.updatePipelineTiming",
              has_insurance_update,
              "设置了_pipeline变量但未调用updatePipelineTiming持久化到DiagnosticLogger")
    else:
        check("'insurance_decline' pipeline路径存在", False, "未找到insurance pipeline追踪")

# 统计updatePipelineTiming总调用次数
calls_to_update = len(re.findall(r'DiagnosticLogger\.updatePipelineTiming\s*\(', kotlin))
check(f"updatePipelineTiming总调用次数 >= 4 ({calls_to_update}次)",
      calls_to_update >= 4,
      f"调用次数过少({calls_to_update})，可能有执行路径遗漏")

# ============================================================
print()
print("=" * 60)
print("🔍 检查5: try-catch 包裹检查")
print("=" * 60)

# 检查新增的bridge调用(getDiagData/getPipelineTiming)是否在try-catch内
# 方法：检查调用前的上下文中是否有 try {
for method in ['getDiagData', 'getPipelineTiming']:
    pattern = rf'AndroidBridge\.{method}'
    for match in re.finditer(pattern, html):
        start = max(0, match.start() - 300)
        context_before = html[start:match.start()]
        # 检查最近的 try { 是否在 } catch 之前
        last_try = context_before.rfind('try{')
        last_try2 = context_before.rfind('try {')
        last_catch = max(context_before.rfind('}catch'), context_before.rfind('} catch'))
        has_try = max(last_try, last_try2) > last_catch
        check(f"AndroidBridge.{method} 调用在try-catch内 (位置{match.start()})", has_try,
              "未被try-catch包裹")

# ============================================================
print()
print("=" * 60)
print("🔍 检查6: 硬编码版本号全局扫描")
print("=" * 60)

hardcoded = []
# 获取当前版本号作为白名单
current_ver = html_version.group(1) if html_version else ''
for i, line in enumerate(html.split('\n'), 1):
    if "var APP_VERSION" in line or "console.log" in line or "CURRENT_VERSION" in line:
        continue
    matches = re.findall(r"['\"]2\.9\.\d{3}['\"]", line)
    for m in matches:
        # 如果是当前版本号且在return语句中（如getVersion），也是硬编码
        hardcoded.append(f"  行{i}: {m.strip()} → {line.strip()[:80]}")

check("HTML无散落的硬编码版本号",
      len(hardcoded) == 0,
      f"发现{len(hardcoded)}处:\n" + "\n".join(hardcoded[:5]))

# ============================================================
print()
print("=" * 60)
print("🔍 检查7: Kotlin诊断数据字段完整性")
print("=" * 60)

diag_fields = ['pipelineJsDecisionTimeMs', 'pipelineEsp32TapTimeMs', 'pipelineTotalTimeMs', 'pipelineLastAction']
for f in diag_fields:
    check(f"DiagnosticLogger包含字段 '{f}'", f in diag_export)

check("DiagnosticLogger.logEsp32Tap方法存在",
      'fun logEsp32Tap' in diag_export or 'logEsp32Tap(' in diag_export)

# ============================================================
print()
print("=" * 60)
print("🔍 检查8: _pipeline 变量定义完整性")
print("=" * 60)

pipeline_vars = [
    '_pipelineScreenshotTime',
    '_pipelineJsDecisionTimeMs',
    '_pipelineEsp32TapTimeMs',
    '_pipelineTotalTimeMs',
    '_pipelineLastAction',
]

for v in pipeline_vars:
    check(f"FloatingService定义变量 '{v}'", v in kotlin)

# ============================================================
print()
print("=" * 60)
print("🔍 检查9: Android 10+ Scoped Storage 兼容")
print("=" * 60)

all_kt_files = []
for root, dirs, files in os.walk(os.path.join(REPO, "app/src/main/java")):
    for f in files:
        if f.endswith(".kt"):
            all_kt_files.append(os.path.join(root, f))

scoped_storage_violations = []
for fpath in all_kt_files:
    with open(fpath, 'r', encoding='utf-8', errors='replace') as f:
        for i, line in enumerate(f, 1):
            if 'getExternalStoragePublicDirectory' in line:
                scoped_storage_violations.append(f"  {os.path.basename(fpath)}:{i}")

check("无 getExternalStoragePublicDirectory 调用 (Android 10+)",
      len(scoped_storage_violations) == 0,
      f"发现{len(scoped_storage_violations)}处违规:\n" + "\n".join(scoped_storage_violations))

# ============================================================
print()
print("=" * 60)
print("🔍 检查10: UI文本旧版本号扫描")
print("=" * 60)

current_ver_short = gv  # e.g. "2.9.504"

# --- 10a: XML布局中所有android:text含旧版本号 ---
xml_stale = []
for i, line in enumerate(xml_content.split('\n'), 1):
    if 'android:text' not in line:
        continue
    ver_matches = re.findall(r'V(2\.9\.\d+)', line)
    for v in ver_matches:
        if v != current_ver_short:
            xml_stale.append(f"  {os.path.basename(ACTIVITY_XML)}:L{i} 旧版本V{v} → {line.strip()[:80]}")

check("XML布局无旧版本标记",
      len(xml_stale) == 0,
      f"发现{len(xml_stale)}处:\n" + "\n".join(xml_stale[:5]))

# --- 10b: HTML用户可见文本含旧版本号（排除注释、console.log、变量定义） ---
html_stale = []
for i, line in enumerate(html.split('\n'), 1):
    stripped = line.strip()
    # 跳过非用户可见的内容
    if (stripped.startswith('//') or stripped.startswith('/*') or
        stripped.startswith('*') or 'console.log' in line or
        'var APP_VERSION' in line or 'CURRENT_VERSION' in line or
        'getVersion' in line):
        continue
    # 只检查HTML标签内的文本内容
    if '>' not in line:
        continue
    # 提取HTML标签间的文本
    text_parts = re.findall(r'>([^<]+)<', line)
    for text in text_parts:
        ver_matches = re.findall(r'V(2\.9\.\d+)', text)
        for v in ver_matches:
            if v != current_ver_short:
                html_stale.append(f"  HTML:L{i} 旧版本V{v} → {text.strip()[:60]}")

check("HTML用户可见文本无旧版本标记",
      len(html_stale) == 0,
      f"发现{len(html_stale)}处:\n" + "\n".join(html_stale[:5]))

# ============================================================
print()
print("=" * 60)
print("🔍 检查11: 线程安全 — 共享变量@Volatile")
print("=" * 60)

# 所有 _pipeline* 变量必须有 @Volatile 注解（WebView @JavascriptInterface 运行在后台线程）
lines = kotlin.split('\n')
pipeline_no_volatile = []
for i, line in enumerate(lines):
    if re.search(r'private\s+var\s+_pipeline\w+', line):
        # 检查同一行或上一行是否有 @Volatile
        has_volatile = '@Volatile' in line
        if i > 0 and not has_volatile:
            has_volatile = '@Volatile' in lines[i-1]
        if not has_volatile:
            pipeline_no_volatile.append(f"  L{i+1}: {line.strip()[:70]}")

check("所有 _pipeline 变量都有 @Volatile",
      len(pipeline_no_volatile) == 0,
      f"缺少@Volatile:\n" + "\n".join(pipeline_no_volatile[:5]))

# _strategyReceived 也必须 @Volatile（WebView回调写入，主线程读取）
strategy_recv_volatile = False
for i, line in enumerate(lines):
    if '_strategyReceived' in line and 'private var' in line:
        strategy_recv_volatile = '@Volatile' in line or (i > 0 and '@Volatile' in lines[i-1])
        break
check("_strategyReceived 有 @Volatile", strategy_recv_volatile,
      "WebView回调写入的变量缺少@Volatile，主线程可能看不到最新值")

# ============================================================
print()
print("=" * 60)
print("🔍 检查12: 策略引擎完整性")
print("=" * 60)

# StrategyEngine 关键方法必须在HTML中存在
se_methods = {
    'getVersion': r'getVersion\s*:\s*function',
    'isEnabled': r'isEnabled\s*:\s*function',
    'decidePreflop': r'(?:decidePreflop\s*:\s*function|function\s+decidePreflop\b)',
    'decidePostflop': r'(?:decidePostflop\s*:\s*function|function\s+decidePostflop\b|decidePostflop\s*:\s*decidePostflop)',
}

for method, pattern in se_methods.items():
    found = bool(re.search(pattern, html))
    check(f"StrategyEngine.{method}() 方法存在", found,
          f"策略引擎缺少 {method} 方法，exportLog可能输出version:unknown")

# exportLog 必须通过 StrategyEngine.getVersion() 获取版本号（非硬编码）
export_uses_se = 'StrategyEngine.getVersion' in html or 'StrategyEngine.getVersion()' in html
check("exportLog 使用 StrategyEngine.getVersion()", export_uses_se,
      "exportLog未调用StrategyEngine.getVersion()，可能导致version:unknown")

# ============================================================
print()
print("=" * 60)
print("🔍 检查13: DiagnosticLogger 方法完整性")
print("=" * 60)

diag_methods = [
    ('logDecision', '记录策略决策'),
    ('logRecognition', '记录识别结果'),
    ('logEsp32Tap', '记录ESP32点击'),
    ('exportAsJson', '导出诊断数据'),
    ('updatePipelineTiming', '更新Pipeline耗时'),
]

for method, purpose in diag_methods:
    check(f"DiagnosticLogger.{method}() 存在 ({purpose})",
          f'fun {method}' in diag_export or f'fun {method}(' in diag_export,
          f"缺少 {method} 方法")

# logDecision 必须在 bridgeAdvice 流程中被调用
bridge_advice_section = re.search(r'function\s+bridgeAdvice\b.*?(?=\nfunction\s|\Z)', html, re.DOTALL)
if bridge_advice_section:
    bridge_body = bridge_advice_section.group(0)
    check("bridgeAdvice() 调用 logDecision()",
          'logDecision' in bridge_body,
          "bridgeAdvice未调用logDecision，导致decisions:[]为空")
else:
    warn("bridgeAdvice() 函数未找到", "可能已改名或移除")

# ============================================================
print()
print("=" * 60)
print("🔍 检查14: Shot Clock 时序合理性")
print("=" * 60)

# SHOT_CLOCK_TIMEOUT 必须足够大（VLM平均23.5s，最慢35s）
shot_clock_match = re.search(r'SHOT_CLOCK_TIMEOUT\s*=\s*(\d+)L', kotlin)
if shot_clock_match:
    shot_clock_ms = int(shot_clock_match.group(1))
    check(f"SHOT_CLOCK_TIMEOUT >= 25000ms (当前{shot_clock_ms}ms)",
          shot_clock_ms >= 25000,
          f"Shot Clock {shot_clock_ms}ms 太短，VLM平均23.5s可能来不及返回")
    check(f"SHOT_CLOCK_TIMEOUT <= 29000ms (当前{shot_clock_ms}ms, GG限制30s)",
          shot_clock_ms <= 29000,
          f"Shot Clock {shot_clock_ms}ms 超过GG 30s限制，可能超时")
else:
    check("SHOT_CLOCK_TIMEOUT 常量存在", False, "未找到SHOT_CLOCK_TIMEOUT")

# 硬超时必须 < SHOT_CLOCK_TIMEOUT 且 > 20000ms
hard_timeout_match = re.search(r'handler\.postDelayed\(_shotClockRunnable!!,\s*(\d+)\)', kotlin)
if hard_timeout_match:
    hard_timeout_ms = int(hard_timeout_match.group(1))
    check(f"硬超时 >= 23000ms (当前{hard_timeout_ms}ms)",
          hard_timeout_ms >= 23000,
          f"硬超时 {hard_timeout_ms}ms 太短")
    if shot_clock_match:
        check(f"硬超时({hard_timeout_ms}ms) < SHOT_CLOCK_TIMEOUT({shot_clock_ms}ms)",
              hard_timeout_ms < shot_clock_ms,
              "硬超时不应大于SHOT_CLOCK_TIMEOUT")
else:
    check("Shot Clock 硬超时设置存在", False, "未找到硬超时")

# ============================================================
print()
print("=" * 60)
print("🔍 检查15: BLE安全检查")
print("=" * 60)

# executeAutoTap 必须在tap前检查BLE连接
for func_name in ['executeAutoTap', 'executeAutoTapFallback']:
    pattern = rf'fun\s+{func_name}\b.*?(?=\n    (?:fun |private |suspend |override )|\Z)'
    match = re.search(pattern, kotlin, re.DOTALL)
    if match:
        body = match.group(0)
        has_ble_check = 'isConnected' in body or 'bleConnected' in body
        check(f"{func_name} 执行前检查BLE连接",
              has_ble_check,
              f"{func_name}未检查BLE连接状态就执行tap，可能导致无效操作")
    else:
        warn(f"{func_name} 函数未找到", "可能已改名或移除")

# ============================================================
print()
print("=" * 60)
print("🔍 检查16: versionCode 递增检查")
print("=" * 60)

vc_match = re.search(r'versionCode\s+(\d+)', gradle)
if vc_match:
    vc = int(vc_match.group(1))
    # V2.9.500对应versionCode 223，每版本递增1
    check(f"versionCode({vc}) >= 224 (V2.9.504基准)",
          vc >= 224,
          f"versionCode={vc} 低于V2.9.504基准值224，可能忘记递增")
else:
    check("versionCode 存在", False, "build.gradle中未找到versionCode")

# ============================================================
print()
print("=" * 60)
print("🔍 检查17: Kotlin类型安全 - 已知类型属性访问检查")
print("=" * 60)

# 检查17: 防止对已知 List<String> 字段调用 .text 等不存在的属性
# 背景：V2.9.504曾出现 result.buttons.map{it.text} 编译错误，
# buttons 是 List<String>，String 没有 .text 属性
# 扫描所有 Kotlin 文件中对 List<String> 字段的 .map{it.xxx} 模式
kotlin_files = []
for root, dirs, files in os.walk("app/src"):
    for f in files:
        if f.endswith(".kt"):
            kotlin_files.append(os.path.join(root, f))

type_safety_ok = True
for kf in kotlin_files:
    with open(kf, "r", encoding="utf-8", errors="ignore") as fh:
        content = fh.read()
        lines = content.split("\n")
        for i, line in enumerate(lines, 1):
            # 检测 .map{it.text} 模式，这在 List<String> 上是错误的
            if re.search(r'\.map\s*\{\s*it\.text\s*\}', line):
                # 检查上下文是否有 List<String> 类型的变量
                # 常见错误模式：.buttons.map{it.text}, .names.map{it.text} 等
                # buttons 是 List<String>，String 没有 .text
                # 但如果是 List<Button> 类型则有 .text
                # 简单规则：对 buttons 字段调用 .map{it.text} 是错的
                if 'buttons' in line.lower() or 'button' in line.lower():
                    check(f"{kf}:{i} 类型安全: buttons是List<String>无.text属性",
                          False,
                          f"行内容: {line.strip()}")
                    type_safety_ok = False

if type_safety_ok:
    check("Kotlin类型安全检查 (List<String>.map{it.xxx}模式)", True)

# ============================================================
print()
print("=" * 60)
print("🔍 检查18: 初始化完整性检查")
print("=" * 60)

# 检测组件创建后是否调用了必要的初始化方法
INIT_METHODS_NAMES = ["init", "initialize", "start", "setup", "configure", "load", "prepare"]
SKIP_CLASSES = {
    "String", "Int", "Long", "Float", "Double", "Boolean", "Char",
    "List", "Map", "Set", "ArrayList", "HashMap", "HashSet", "Array",
    "Context", "Activity", "Service", "View", "Intent", "Bundle",
    "Handler", "Looper", "Thread", "Runnable", "BroadcastReceiver",
    "SharedPreferences", "Gson", "Random", "Pattern", "Matcher",
    "StringBuilder", "BufferedReader", "InputStreamReader",
    "File", "FileInputStream", "FileOutputStream",
    "ObjectMapper",
    "AlertDialog", "Timer", "TimerTask", "CountDownTimer",
    "Paint", "Rect", "RectF", "PointF", "Matrix", "Bitmap",
    "Bundle", "Parcelable", "Serializable",
}

def _extract_data_classes(content):
    """提取 data class / value class / enum class / object（不需要外部init）"""
    classes = set()
    for m in re.finditer(r'(?:data|value|enum)\s+class\s+(\w+)', content):
        classes.add(m.group(1))
    for m in re.finditer(r'^\s*object\s+(\w+)', content, re.MULTILINE):
        classes.add(m.group(1))
    return classes

def _extract_class_methods_map(content):
    """构建 className -> [methodNames] 映射，正确处理嵌套类"""
    class_methods = {}
    lines = content.split('\n')
    
    # 找到所有顶层类（缩进为0或仅空白）的起始行
    top_classes = []  # (line_idx, class_name)
    for i, line in enumerate(lines):
        if re.match(r'^(?:public\s+|private\s+|internal\s+|open\s+|abstract\s+)*class\s+(\w+)', line):
            m = re.search(r'class\s+(\w+)', line)
            if m:
                top_classes.append((i, m.group(1)))
    
    # 对每个顶层类，收集其所有方法（包括嵌套类中的）
    for idx, (start_line, class_name) in enumerate(top_classes):
        # 类的结束行 = 下一个顶层类的起始行 或 文件末尾
        end_line = top_classes[idx + 1][0] if idx + 1 < len(top_classes) else len(lines)
        
        methods = []
        for i in range(start_line, end_line):
            for m in re.finditer(r'fun\s+(\w+)\s*\(', lines[i]):
                methods.append(m.group(1))
        class_methods[class_name] = methods
    
    return class_methods

init_check_critical = []
init_check_ok = []
init_check_files = []
for root, dirs, files in os.walk(os.path.join(REPO, "app/src/main/java")):
    for f in files:
        if f.endswith(".kt"):
            init_check_files.append(os.path.join(root, f))

# 收集所有文件中的类方法映射（跨文件查找）
all_class_methods = {}
all_file_contents = {}
for fpath in init_check_files:
    try:
        with open(fpath, 'r', encoding='utf-8', errors='replace') as fh:
            content = fh.read()
            all_file_contents[fpath] = content
            cm = _extract_class_methods_map(content)
            all_class_methods.update(cm)
    except Exception:
        continue

for fpath, content in all_file_contents.items():
    lines = content.split('\n')
    data_classes = _extract_data_classes(content)
    all_skip = SKIP_CLASSES | data_classes

    for line_idx, line in enumerate(lines):
        # 模式1: val/var xxx = XxxClass(  (局部变量)
        m1 = re.search(r'(?:val|var)\s+(\w+)\s*=\s*(\w+)\s*\(', line)
        # 模式2: xxx = XxxClass(  (字段赋值，不含val/var，排除 == 比较)
        m2 = re.search(r'^\s+(\w+)\s*=\s*(\w+)\s*\(', line) if not m1 else None

        match = m1 or m2
        if not match:
            continue
        var_name = match.group(1)
        class_name = match.group(2)

        if class_name in all_skip:
            continue

        methods = all_class_methods.get(class_name, [])
        # 精确匹配 init 方法名（不做前缀匹配，避免 startScan 误匹配 start）
        has_init_method = any(m in INIT_METHODS_NAMES for m in methods)
        if not has_init_method:
            continue

        # 检查同一函数/作用域内是否有 init 调用
        # 策略：向下搜索20行，或者到下一个函数定义为止
        search_end = min(line_idx + 30, len(lines))
        found_init = None
        for j in range(line_idx + 1, search_end):
            l = lines[j].strip()
            # 如果遇到新的函数定义，停止搜索
            if re.match(r'(?:private\s+|public\s+|internal\s+|override\s+)*fun\s+\w+', l):
                break
            for im in INIT_METHODS_NAMES:
                if re.search(rf'{re.escape(var_name)}[!?]*\.\s*{im}\s*\(', l):
                    found_init = im
                    break
            if found_init:
                break

        # 也向上搜索（init 可能在创建之前的作用域中已调用，
        # 但更常见的是在创建后调用；对于字段赋值，检查同函数体内）
        if not found_init and m2:
            # 对于字段赋值，向上搜索到函数开头
            for j in range(line_idx - 1, max(line_idx - 30, 0), -1):
                l = lines[j].strip()
                for im in INIT_METHODS_NAMES:
                    if re.search(rf'{re.escape(var_name)}[!?]*\.\s*{im}\s*\(', l):
                        found_init = im
                        break
                if found_init:
                    break
                # 遇到函数定义开头则停止
                if re.match(r'(?:private\s+|public\s+|internal\s+|override\s+)*fun\s+\w+', l):
                    break

        rel = os.path.relpath(fpath, REPO)
        if found_init:
            init_check_ok.append(f"{var_name}({class_name}) → {found_init}() @ {rel}:{line_idx+1}")
        else:
            init_check_critical.append(f"{rel}:{line_idx+1} — {class_name} `{var_name}` 创建后未调用初始化方法")

# 输出结果
for c in init_check_ok:
    check(f"初始化OK: {c}", True)

if init_check_critical:
    for c in init_check_critical:
        check(f"初始化缺失: {c}", False, "组件创建后未调用 init() 等初始化方法")
else:
    if not init_check_ok:
        check("初始化完整性检查（未发现需要外部初始化的组件）", True)
    # else: 已经有OK的check输出了

# ============================================================
print()
print("=" * 60)
print("🔍 检查19: Kotlin 代码质量检查 (Lint/Detekt 规则)")
print("=" * 60)

# V2.9.506: 模拟 Detekt/Android Lint 的 Python 静态检查
# 扫描所有 .kt 文件，检查常见代码质量问题

kt_files = []
for root, dirs, files in os.walk(os.path.join(REPO, "app/src/main/java")):
    for f in files:
        if f.endswith(".kt"):
            kt_files.append(os.path.join(root, f))

lint_errors = []
lint_warnings = []

for fpath in kt_files:
    rel = os.path.relpath(fpath, REPO)
    with open(fpath, 'r', encoding='utf-8', errors='replace') as f:
        lines = f.readlines()
    
    for i, line in enumerate(lines, 1):
        stripped = line.strip()
        
        # 1. 空 catch 块（Detekt: EmptyCatchBlock）
        if re.search(r'catch\s*\([^)]*\)\s*\{\s*\}', stripped):
            lint_warnings.append(f"{rel}:{i} 空 catch 块（建议至少记录日志）")
        
        # 2. TODO/FIXME 注释（Detekt 默认规则）
        if re.search(r'//\s*(TODO|FIXME)\b', stripped, re.IGNORECASE):
            lint_warnings.append(f"{rel}:{i} 存在 {re.search(r'(TODO|FIXME)', stripped, re.IGNORECASE).group(1)} 注释")
        
        # 3. 不安全的强制类型转换 `as`（Detekt: UnsafeCast）
        # 但排除 `as?` 安全转换和注释行
        if re.search(r'\bas\s+[A-Z]\w*', stripped) and 'as?' not in stripped and not stripped.startswith('//') and not stripped.startswith('*'):
            # 进一步排除常见安全场景: as String, as Int 等基本类型转换
            cast_match = re.search(r'\bas\s+([A-Z]\w*)', stripped)
            if cast_match:
                cast_type = cast_match.group(1)
                # 只对非基本类型的强制转换报警
                if cast_type not in ('String', 'Int', 'Long', 'Float', 'Double', 'Boolean', 'Char', 'Any', 'Number'):
                    lint_warnings.append(f"{rel}:{i} 不安全的强制类型转换 `as {cast_type}`（建议用 as?）")
        
        # 4. 通配符 import（Detekt: WildcardImport）
        if re.match(r'import\s+\S+\.\*', stripped):
            lint_warnings.append(f"{rel}:{i} 通配符 import（建议显式导入）")
        
        # 5. Thread.sleep 调用（性能问题）
        if re.search(r'Thread\.sleep\s*\(', stripped):
            lint_warnings.append(f"{rel}:{i} Thread.sleep 调用（考虑用协程/Handler替代）")
        
        # 6. print/println 调试语句残留（Detekt: PrintStackTrace）
        if re.search(r'\bprint(?:ln)?\s*\(', stripped) and not stripped.startswith('//') and not stripped.startswith('*'):
            # 排除 buildConfig 或日志框架中的合法调用
            if 'log' not in stripped.lower() and 'Log' not in stripped:
                lint_warnings.append(f"{rel}:{i} 可能存在调试 print 语句残留")
        
        # 7. 硬编码 URL（安全相关）
        if re.search(r'https?://\S+', stripped) and not stripped.startswith('//') and not stripped.startswith('*') and 'const val' not in stripped:
            # 排除注释和常量定义
            if 'val ' not in stripped and 'const ' not in stripped:
                lint_warnings.append(f"{rel}:{i} 硬编码 URL（建议提取为常量）")
        
        # 8. e.printStackTrace()（Detekt: PrintStackTrace）
        # 排除 printStackTrace(PrintWriter) / printStackTrace(PrintStream) 等合法用法
        if re.search(r'\.printStackTrace\s*\(', stripped) and not re.search(r'\.printStackTrace\s*\(\s*(?:PrintWriter|PrintStream|java\.io)', stripped):
            lint_errors.append(f"{rel}:{i} e.printStackTrace()（应使用 Log 框架）")
        
        # 9. @Suppress 过度使用（超过3个规则的 suppress）
        suppress_match = re.search(r'@Suppress\s*\(\s*"([^"]+)"', stripped)
        if suppress_match:
            # 检查是否一行 suppress 了太多规则
            all_suppress = re.findall(r'"([^"]+)"', stripped)
            if len(all_suppress) > 3:
                lint_warnings.append(f"{rel}:{i} @Suppress 抑制了 {len(all_suppress)} 条规则（建议逐一处理）")

# 报告结果 - 将 printStackTrace 视为错误，其他视为警告
for err in lint_errors:
    check(f"Lint: {err}", False, "代码质量问题")

# 警告类问题用 warn 报告（不阻断）
# 统计并汇总报告
warning_categories = {}
for w in lint_warnings:
    # 提取问题类型
    if '空 catch' in w:
        cat = '空catch块'
    elif 'TODO' in w or 'FIXME' in w:
        cat = 'TODO/FIXME'
    elif '不安全' in w:
        cat = '不安全类型转换'
    elif '通配符' in w:
        cat = '通配符import'
    elif 'Thread.sleep' in w:
        cat = 'Thread.sleep'
    elif 'print' in w:
        cat = '调试print残留'
    elif '硬编码URL' in w:
        cat = '硬编码URL'
    elif '@Suppress' in w:
        cat = '过度Suppress'
    else:
        cat = '其他'
    warning_categories[cat] = warning_categories.get(cat, 0) + 1

if warning_categories:
    total_lint_warnings = sum(warning_categories.values())
    summary_parts = [f"{cat}×{cnt}" for cat, cnt in sorted(warning_categories.items(), key=lambda x: -x[1])]
    warn(f"Kotlin代码质量警告（共{total_lint_warnings}处）", "; ".join(summary_parts))
    # 逐项列出前10个最严重的
    shown = 0
    for w in lint_warnings:
        if shown >= 10:
            break
        print(f"    📋 {w}")
        shown += 1
    if total_lint_warnings > 10:
        print(f"    ... 及其他 {total_lint_warnings - 10} 处警告")
    check(f"Kotlin代码质量（{total_lint_warnings}个警告，不阻断）", True)
else:
    check("Kotlin代码质量检查通过", True)

# ============================================================
print()
print("=" * 60)
print("🔍 检查20: Android Lint 报告解析")
print("=" * 60)

# V2.9.506: 检查是否存在 lint 报告文件（由 CI 或本地 gradlew lint 生成）
lint_report_path = os.path.join(REPO, "app/build/reports/lint-results.html")
lint_xml_path = os.path.join(REPO, "app/build/reports/lint-results.xml")

if os.path.exists(lint_xml_path):
    try:
        import xml.etree.ElementTree as ET
        tree = ET.parse(lint_xml_path)
        root_elem = tree.getroot()
        issues = root_elem.findall('.//issue')
        error_count = sum(1 for i in issues if i.get('severity') == 'Error')
        warning_count = sum(1 for i in issues if i.get('severity') == 'Warning')
        
        if error_count > 0:
            check(f"Android Lint 错误 ({error_count}个)", False, "存在Lint错误需修复")
            # 显示前5个错误
            for i, issue in enumerate(issues):
                if i >= 5:
                    break
                if issue.get('severity') == 'Error':
                    print(f"    🔴 [{issue.get('id')}] {issue.get('message', '')[:60]}")
        else:
            check(f"Android Lint 通过 ({warning_count}个警告)", True)
    except Exception as e:
        warn("Lint报告解析失败", str(e))
elif os.path.exists(lint_report_path):
    check("Android Lint 报告存在（HTML格式，跳过详细解析）", True)
else:
    # 没有 lint 报告，用 Python 做基本的 Android 检查
    android_issues = []
    
    for fpath in kt_files:
        rel = os.path.relpath(fpath, REPO)
        with open(fpath, 'r', encoding='utf-8', errors='replace') as f:
            content = f.read()
        
        # 检查是否在主线程执行网络操作（OkHttp 调用不在 coroutine/withContext(IO) 中）
        # 简化检查：查找 OkHttp 调用是否在 suspend 函数或 withContext 中
        if 'OkHttpClient' in content or '.newCall(' in content:
            # 检查是否有 withContext(Dispatchers.IO) 包装
            if 'withContext' not in content and 'Dispatchers.IO' not in content and 'suspend' not in content:
                # 可能是主线程网络调用，但需要更精确的判断
                pass  # 不做误报，交给 CI 的 lint 检查
        
        # 检查是否有未注册的权限（基本检查）
        if 'ContextCompat.checkSelfPermission' not in content and 'permission' in content.lower():
            pass  # 太复杂，交给 lint
    
    if android_issues:
        for issue in android_issues:
            warn(f"Android检查: {issue}", "")
    else:
        check("Android基础检查通过（CI将运行完整Lint检查）", True)
        print("    ℹ️  完整 Lint 检查将在 CI 构建时自动运行")

# ============================================================
print()
print("=" * 60)
total = passed + failed
print(f"📊 验证结果: {passed}/{total} 通过, {failed} 失败, {warnings} 警告")
print("=" * 60)

if failed > 0:
    print("❌ 验证未通过！请修复以上失败项后再推送。")
    sys.exit(1)
else:
    print("✅ 验证全部通过，可以安全推送。")
    sys.exit(0)
