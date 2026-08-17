#!/usr/bin/env python3
"""
青云扑克 代码完整性自动验证脚本 v1.1
检测类型：
  1. AndroidBridge 方法一致性（JS调用 ↔ Kotlin实现）
  2. 版本号一致性（build.gradle / HTML / Kotlin 三端对齐）
  3. 日志导出数据流完整性（exportLog必须包含所有关键数据）
  4. Pipeline耗时追踪覆盖率（所有执行路径必须调用updatePipelineTiming）
  5. try-catch 包裹检查（新增bridge调用）
  6. 硬编码版本号检测
  7. Kotlin诊断数据字段完整性
  8. _pipeline 变量定义完整性
"""
import re, sys, os

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
print("🔍 检查2: 版本号一致性")
print("=" * 60)

gradle = read(GRADLE)
gradle_version = re.search(r'versionName\s+"([^"]+)"', gradle)
gv = gradle_version.group(1) if gradle_version else "UNKNOWN"

html_version = re.search(r"var\s+APP_VERSION\s*=\s*'([^']+)'", html)
hv = html_version.group(1) if html_version else "UNKNOWN"

check(f"build.gradle版本({gv}) == HTML版本({hv})",
      gv == hv,
      f"不一致: gradle={gv}, html={hv}")

# exportLog 不能用硬编码版本号
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
total = passed + failed
print(f"📊 验证结果: {passed}/{total} 通过, {failed} 失败, {warnings} 警告")
print("=" * 60)

if failed > 0:
    print("❌ 验证未通过！请修复以上失败项后再推送。")
    sys.exit(1)
else:
    print("✅ 验证全部通过，可以安全推送。")
    sys.exit(0)
