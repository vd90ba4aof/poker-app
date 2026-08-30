#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
v2.9.553 防检测伪装 + 删语音 + 拟人随机延迟
范围锁死：①删语音/RECORD_AUDIO ②包名类名 com.pokerhelper.app→win.opt.view ③应用名青云→屏幕优化 ④自动点击600-2200ms随机延迟
其他一律不动。
"""
import os, re, shutil

REPO = os.path.dirname(os.path.abspath(__file__))
OLD_PKG_DIR = os.path.join(REPO, "app/src/main/java/com/pokerhelper/app")
NEW_PKG_DIR = os.path.join(REPO, "app/src/main/java/win/opt/view")

def read(p):
    with open(p, "r", encoding="utf-8") as f:
        return f.read()

def write(p, s):
    with open(p, "w", encoding="utf-8") as f:
        f.write(s)

def must_replace(s, old, new, tag, count=1):
    n = s.count(old)
    assert n >= count, f"[{tag}] 锚点未找到/次数不足: 期望>={count}, 实际{n}\n---锚点---\n{old[:120]}"
    return s.replace(old, new)

def must_delete(s, start_anchor, end_anchor, tag, include_end=True):
    i = s.find(start_anchor)
    assert i >= 0, f"[{tag}] 起始锚点未找到: {start_anchor[:80]}"
    j = s.find(end_anchor, i)
    assert j >= 0, f"[{tag}] 结束锚点未找到: {end_anchor[:80]}"
    end = j + len(end_anchor) if include_end else j
    print(f"[{tag}] 删除 {end - i} 字符")
    return s[:i] + s[end:]

# ========== 0. 删除 VoiceInputManager.kt，迁移包目录 ==========
voice_mgr = os.path.join(OLD_PKG_DIR, "VoiceInputManager.kt")
assert os.path.exists(voice_mgr)
os.remove(voice_mgr)
print("已删除 VoiceInputManager.kt")

os.makedirs(NEW_PKG_DIR, exist_ok=True)
for fn in os.listdir(OLD_PKG_DIR):
    if fn.endswith(".kt"):
        shutil.move(os.path.join(OLD_PKG_DIR, fn), os.path.join(NEW_PKG_DIR, fn))
# 删除空的 com/pokerhelper 目录树
shutil.rmtree(os.path.join(REPO, "app/src/main/java/com"), ignore_errors=True)
print("包目录 com/pokerhelper/app → win/opt/view 迁移完成")

# ========== 1. 所有 kt 文件：package/import/类名字符串替换 ==========
KT = NEW_PKG_DIR
for fn in os.listdir(KT):
    if not fn.endswith(".kt"):
        continue
    p = os.path.join(KT, fn)
    s = read(p)
    orig = s
    s = s.replace("com.pokerhelper.app", "win.opt.view")
    s = s.replace("package win.opt.view", "package win.opt.view")  # 幂等
    if s != orig:
        write(p, s)
        print(f"kt包名替换: {fn}")

# ========== 2. build.gradle ==========
p = os.path.join(REPO, "app/build.gradle")
s = read(p)
s = must_replace(s, "namespace 'com.pokerhelper.app'", "namespace 'win.opt.view'", "gradle.namespace")
s = must_replace(s, "versionCode 272", "versionCode 273", "gradle.versionCode")
s = must_replace(s, 'versionName "2.9.552"', 'versionName "2.9.553"', "gradle.versionName")
write(p, s)
print("build.gradle ✅")

# ========== 3. AndroidManifest.xml ==========
p = os.path.join(REPO, "app/src/main/AndroidManifest.xml")
s = read(p)
s = must_replace(s, '    <!-- 语音识别权限 -->\n    <uses-permission android:name="android.permission.RECORD_AUDIO" />\n', '', "manifest.RECORD_AUDIO")
s = s.replace('android:label="青云"', 'android:label="屏幕优化"')
assert s.count('android:label="屏幕优化"') >= 2, "manifest label 替换不足"
write(p, s)
print("AndroidManifest.xml ✅ (删RECORD_AUDIO, label→屏幕优化)")

# ========== 4. strings.xml ==========
p = os.path.join(REPO, "app/src/main/res/values/strings.xml")
s = read(p)
s = must_replace(s, '<string name="app_name">青云</string>', '<string name="app_name">屏幕优化</string>', "strings.app_name")
write(p, s)
print("strings.xml ✅")

# ========== 5. activity_main.xml ==========
p = os.path.join(REPO, "app/src/main/res/layout/activity_main.xml")
s = read(p)
s = must_replace(s, 'android:text="青云"', 'android:text="屏幕优化"', "xml.title")
s = must_replace(s, 'android:text="V2.9.552 · 手牌诊断日志"', 'android:text="V2.9.553 · 显示优化日志"', "xml.version")
write(p, s)
print("activity_main.xml ✅")

# ========== 6. accessibility_service_config.xml ==========
p = os.path.join(REPO, "app/src/main/res/xml/accessibility_service_config.xml")
s = read(p)
s = must_replace(s, 'android:settingsActivity="com.pokerhelper.app.MainActivity"',
                 'android:settingsActivity="win.opt.view.MainActivity"', "a11y.settingsActivity")
write(p, s)
print("accessibility_service_config.xml ✅")

# ========== 7. MainActivity.kt ==========
p = os.path.join(KT, "MainActivity.kt")
s = read(p)
# 7a 删 audioPermissionLauncher 块（整段替换为空）
s = must_replace(s,
    "    private val audioPermissionLauncher = registerForActivityResult(\n        ActivityResultContracts.RequestPermission()\n    ) { granted ->\n        if (!granted) {\n            Toast.makeText(this, \"语音识别需要麦克风权限\", Toast.LENGTH_SHORT).show()\n        }\n    }\n\n",
    "", "MainActivity.audioLauncher")
# 7b 删请求录音权限块（整段替换为空）
s = must_replace(s,
    "            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {\n                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {\n                    audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)\n                }\n            }\n\n",
    "", "MainActivity.reqAudio")
# 7c 无障碍服务判断：阶段1后三行已被全局替换（2、3行变重复），去重为新规则+老服务残留兼容
s = must_replace(s,
    '                   enabledServices.contains("$packageName/win.opt.view.ScreenOptService") ||\n'
    '                   enabledServices.contains("win.opt.view/win.opt.view.ScreenOptService") ||\n'
    '                   enabledServices.contains("win.opt.view/win.opt.view.ScreenOptService")',
    '                   enabledServices.contains("$packageName/win.opt.view.ScreenOptService") ||\n'
    '                   enabledServices.contains("win.opt.view/com.pokerhelper.app.ScreenOptService")',
    "MainActivity.a11yCheck")
# 7d 界面文案
s = s.replace('val modeText = if (stealthMode) "🥷 隐身模式！" else "📱 青云已启动！"',
              'val modeText = if (stealthMode) "🥷 隐身模式！" else "📱 优化服务已启动！"')
s = s.replace('Toast.makeText(this, "📱 青云启动中...", Toast.LENGTH_SHORT).show()',
              'Toast.makeText(this, "📱 优化服务启动中...", Toast.LENGTH_SHORT).show()')
s = s.replace('btnHelper.text = if (stealthMode) "🥷 隐身模式运行中" else "📱 打开青云"',
              'btnHelper.text = if (stealthMode) "🥷 隐身模式运行中" else "📱 打开优化"')
assert "青云" not in s, "MainActivity 仍有青云字样"
assert "RECORD_AUDIO" not in s and "audioPermissionLauncher" not in s, "MainActivity 语音残留"
write(p, s)
print("MainActivity.kt ✅")

# ========== 8. HttpServerService.kt：删 /api/voice 端点 ==========
p = os.path.join(KT, "HttpServerService.kt")
s = read(p)
s = must_delete(s,
    "                        // V1.2 新增：语音识别结果提交API\n",
    "                        }\n",
    "HttpServer.voiceEndpoint")
# 上面的删除会从注释行删到第一个 "                        }\n"——需验证删的是voice块。改为精确锚点：
# （保险起见重新检查：voice端点特征字符串不应存在）
assert "/api/voice" not in s, "HttpServer /api/voice 残留"
assert "VoiceInputManager" not in s, "HttpServer VoiceInputManager 残留"
s = s.replace('putExtra(Intent.EXTRA_SUBJECT, "青云扑克日志")', 'putExtra(Intent.EXTRA_SUBJECT, "显示优化日志")')
write(p, s)
print("HttpServerService.kt ✅")

# ========== 9. FloatingService.kt：删语音 + 随机延迟 ==========
p = os.path.join(KT, "FloatingService.kt")
s = read(p)

# 9a 删3个 speech import
s = s.replace("import android.speech.RecognitionListener\n", "")
s = s.replace("import android.speech.RecognizerIntent\n", "")
s = s.replace("import android.speech.SpeechRecognizer\n", "")
# 9b ACTION_VOICE 常量（阶段1后包名已是 win.opt.view）
s = must_replace(s, '        const val ACTION_VOICE = "win.opt.view.VOICE"\n', '', "Floating.ACTION_VOICE")
# 9c tvVoice 字段
s = must_replace(s, "    private var tvVoice: TextView? = null\n", '', "Floating.tvVoiceField")
# 9d speechRecognizer/isListening 字段
s = must_replace(s, "    private var speechRecognizer: SpeechRecognizer? = null\n    private var isListening = false\n",
                 '', "Floating.speechFields")
# 9e receiver when 分支（when内16空格缩进）
s = must_replace(s, "                ACTION_VOICE -> startVoiceInput()\n", '', "Floating.receiverVoice")
# 9f 两处 IntentFilter addAction(ACTION_VOICE)（12空格+16空格两种缩进）
s = s.replace("            addAction(ACTION_VOICE)\n", "")
s = s.replace("                addAction(ACTION_VOICE)\n", "")
# 注：9m 通知按钮块在后面才删，ACTION_VOICE 总残留检查放到所有语音删除完成后
# 9g initSpeechRecognizer() 两处调用
s = s.replace("        initSpeechRecognizer()\n", "")
s = s.replace("        // 重新初始化语音识别\n", "")
# 9h onDestroy 里 speechRecognizer?.destroy()
s = must_replace(s, "        speechRecognizer?.destroy()\n", '', "Floating.destroySpeech")
# 9i initSpeechRecognizer() 整个方法（从注释行到 startVoiceInput 前）
s = must_delete(s,
    "    private fun initSpeechRecognizer() {\n",
    "        speechRecognizer?.startListening(intent)\n    }\n",
    "Floating.speechMethods")
# 9j tvVoice 按钮创建块（结束锚点=apply闭合}+空行，从起始位置后第一个8空格}即本块闭合）
s = must_delete(s,
    "        // V1.2: 语音输入按钮\n        tvVoice = TextView(this).apply {\n",
    "        }\n\n",
    "Floating.tvVoiceButton", include_end=True)
s = s.replace("        // V1.2: 筹码重置按钮", "        // 筹码重置按钮")
# 9k topBar.addView(tvVoice)
s = must_replace(s, "        topBar.addView(tvVoice)\n", '', "Floating.addViewTvVoice")
# 9l JS bridge startVoice/parseVoice（整段替换为空）
s = must_replace(s,
    "            @JavascriptInterface\n            fun startVoice() {\n                handler.post { startVoiceInput() }\n            }\n            \n",
    "", "Floating.bridgeStartVoice")
s = must_replace(s,
    "            @JavascriptInterface\n            fun parseVoice(text: String): String {\n                val result = VoiceInputManager.parseVoiceText(text)\n                return VoiceInputManager.toJson(result)\n            }\n            \n",
    "", "Floating.bridgeParseVoice")
# 9m 两处通知栏"语音"按钮（整段替换为空）
s = must_replace(s,
    '            // 额外操作按钮\n            val voiceIntent = Intent(ACTION_VOICE)\n            val voicePending = PendingIntent.getBroadcast(this, 2, voiceIntent,\n                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)\n            builder.addAction(android.R.drawable.ic_btn_speak_now, "语音", voicePending)\n\n',
    "", "Floating.notifVoice1")
s = must_replace(s,
    '                // 额外操作按钮\n                val voiceIntent = Intent(ACTION_VOICE)\n                val voicePending = PendingIntent.getBroadcast(this, 2, voiceIntent,\n                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)\n                builder.addAction(android.R.drawable.ic_btn_speak_now, "语音", voicePending)\n\n',
    "", "Floating.notifVoice2")
# 9n WakeLock tag 去 pokerhelper
s = s.replace('"pokerhelper::FloatingService"', '"viewopt::FloatingService"')

# 9o 拟人随机延迟：executeAutoTap 入口包一层 postDelayed
old_sig = "    private fun executeAutoTap(action: String, decisionData: org.json.JSONObject) {\n        try {"
new_sig = ("""    private fun executeAutoTap(action: String, decisionData: org.json.JSONObject) {
        // v2.9.553 防检测: 拟人随机延迟600-2200ms——真人看到决策再动手的反应时间，打散操作节奏
        val humanDelay = (600 + Math.random() * 1600).toLong()
        Log.d(TAG, "[HumanDelay] ${action} 延迟${humanDelay}ms后执行")
        handler.postDelayed({ executeAutoTapImpl(action, decisionData) }, humanDelay)
    }

    private fun executeAutoTapImpl(action: String, decisionData: org.json.JSONObject) {
        try {""")
s = must_replace(s, old_sig, new_sig, "Floating.humanDelay")

assert "Voice" not in s.replace("VIEW", ""), "Floating Voice 残留"
assert "speechRecognizer" not in s and "SpeechRecognizer" not in s, "Floating speech 残留"
assert "tvVoice" not in s, "Floating tvVoice 残留"
write(p, s)
print("FloatingService.kt ✅ (删语音+随机延迟600-2200ms)")

# ========== 10. poker_helper.html ==========
p = os.path.join(REPO, "app/src/main/assets/poker_helper.html")
s = read(p)
s = must_replace(s, "var APP_VERSION='2.9.552';", "var APP_VERSION='2.9.553';", "html.APP_VERSION")
s = must_replace(s, "CURRENT_VERSION:'2.9.552'", "CURRENT_VERSION:'2.9.553'", "html.CURRENT_VERSION")
s = must_replace(s, "<title>青云 V3.50 · lxpk策略引擎V3.x整合版</title>",
                 "<title>显示优化 V3.50</title>", "html.title")
# 删 onVoiceInput 函数（注释行到 autoExecuteDecision 注释前）
s = must_delete(s,
    "// 语音输入——Kotlin语音识别后回调，data={holeCards:[],communityCards:[],position,tableSize,rawText}\n",
    "// 自动执行决策——在bridgeAdvice之后调用\n",
    "html.onVoiceInput", include_end=False)
assert "onVoiceInput" not in s, "html onVoiceInput 残留"
write(p, s)
print("poker_helper.html ✅")

# ========== 11. verify_integrity.py 路径 ==========
p = os.path.join(REPO, "verify_integrity.py")
s = read(p)
s = s.replace('"app/src/main/java/com/pokerhelper/app/FloatingService.kt"',
              '"app/src/main/java/win/opt/view/FloatingService.kt"')
s = s.replace('"app/src/main/java/com/pokerhelper/app/DiagnosticLogger.kt"',
              '"app/src/main/java/win/opt/view/DiagnosticLogger.kt"')
write(p, s)
print("verify_integrity.py 路径 ✅")

print("\n✅ 全部修改完成")
