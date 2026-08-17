#!/usr/bin/env python3
"""
青云扑克 代码完整性自动验证脚本 v1.3
检测类型（16大类）：
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
total = passed + failed
print(f"📊 验证结果: {passed}/{total} 通过, {failed} 失败, {warnings} 警告")
print("=" * 60)

if failed > 0:
    print("❌ 验证未通过！请修复以上失败项后再推送。")
    sys.exit(1)
else:
    print("✅ 验证全部通过，可以安全推送。")
    sys.exit(0)
