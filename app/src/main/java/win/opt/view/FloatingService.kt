package win.opt.view

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import java.io.File
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import android.widget.LinearLayout
import android.widget.TextView
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable

class FloatingService : Service() {

    companion object {
        private const val TAG = "FloatingService"
        var isRunning = false
        var currentPanelWidth: Int = 0
        var currentPanelHeight: Int = 0
        private const val CHANNEL_ID = "screen_opt_v2"
        private const val NOTIFICATION_ID = 2
        private const val PREFS_NAME = "poker_floating_prefs"
        private const val KEY_LANDSCAPE_WIDTH = "landscape_width"
        private const val KEY_LANDSCAPE_HEIGHT_RATIO = "landscape_height_ratio"
        const val KEY_STEALTH_MODE = "stealth_mode"
        const val ACTION_CAPTURE = "win.opt.view.CAPTURE"
        const val ACTION_OPEN = "win.opt.view.OPEN"
        const val ACTION_EXPORT = "win.opt.view.EXPORT"
    }

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var webView: WebView? = null
    private var tvStatus: TextView? = null
    private var tvRecResult: TextView? = null
    private var tvRecDetail: TextView? = null  // V2.9.43: 识别详情（底池/跟注/盲注）
    private var tvAction: TextView? = null
    private var resizeHandleLeft: View? = null
    private var resizeHandleBottom: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isExpanded = true
    private var prefs: SharedPreferences? = null
    private var isStealthMode = false

    // V2.9.40: 悬浮球 — 一键截屏
    private var floatingBall: TextView? = null
    private var ballParams: WindowManager.LayoutParams? = null
    private val BALL_SIZE_DP = 64  // V2.9.107: 死代码清理+HTTP复用
    private val KEY_BALL_X = "ball_x"
    private val KEY_BALL_Y = "ball_y"

    // V2.9.68: WakeLock保活，防止CPU休眠导致服务被杀
    private var wakeLock: PowerManager.WakeLock? = null

    // V2.9.541: 本地CV为主识别链路（手牌/公共牌/操作区/筹码全像素匹配），VLM仅兜底

    // V2.9.153: AutoCapture
    private var autoCaptureEnabled = false
    private var autoCaptureRunnable: Runnable? = null
    private var autoCaptureInterval = 3500L  // V2.9.569: 固定3.5秒间隔（AntiDetection±15%抖动≈3-4秒）
    // V2.9.180: 最新按钮坐标（Vision API返回，用于全自动执行）
    private var latestButtonPositions = emptyList<VisionApiClient.ButtonPosition>()
    // V2.9.207: 缓存场景数据——用于本地CV快速通道（跳过VLM）
    private var cachedPotSize: Int = 0
    private var cachedToCall: Int = 0
    private var cachedMinRaise: Int = 0
    private var cachedBlindSB: Int = 0
    private var cachedBlindBB: Int = 0
    private var cachedTotalPlayers: Int = 6
    private var cachedActivePlayers: Int = 3
    private var cachedMyPosition: String = "BTN"
    private var cachedPlayerChips: Int = 0
    private val FAST_PATH_MIN_CONFIDENCE = 0.85f
    private var screenWidth = 1080
    private var screenHeight = 2344
    // V3.50: Pipeline状态机 — 统一管理截屏→识别→策略→执行的全流程状态
    // 替代原有的 isVisionInProgress 标志位，消除竞态条件和幽灵状态
    private val pipelineFSM = PipelineStateMachine()
    // isVisionInProgress 保留为兼容别名，实际逻辑全部走 pipelineFSM
    // 读取：pipelineFSM.isPipelineActive()
    // 设置：pipelineFSM.transition(event)
    private var autoConsecutiveErrors = 0
    private val AUTO_MAX_ERRORS = 3
    // V2.9.206: Shot Clock保护——记录上次决策时间，超时强制行动
    private var lastDecisionTime: Long = 0
    private val SHOT_CLOCK_TIMEOUT = 28000L // V2.9.503: 28秒超时（VLM平均23.5s，留2s余量给GG 30s限制）
    // V2.9.207: 记录当前手牌开始分析时间——修复Shot Clock新牌局永远不触发的bug
    private var handStartTime: Long = 0
    private var manualErrorCount = 0  // V2.9.184: 手动截屏连续失败计数
    private var multiFrameDelay = 1500L  // V2.9.184: 200→1500ms，给API调用留足时间

    // V2.9.4: WebView加载追踪 + JS调用队列
    private var webViewReady = false
    private var _webViewRetryCount = 0  // P0-fix: WebView加载重试计数
    private val _WEBVIEW_MAX_RETRY = 3  // P0-fix: 最多重试3次
    private val _PENDING_JS_MAX = 50    // P0-fix: JS队列上限，防止内存堆积
    @Volatile private var _strategyReceived = false  // V2.9.113: 策略引擎是否已回调
    private var _strategyTimeoutRunnable: Runnable? = null  // V2.9.125: 策略超时定时器引用
    // V2.9.207: Shot Clock硬超时定时器——16秒强制弃牌（比SHOT_CLOCK_TIMEOUT早2秒，留缓冲）
    private var _shotClockRunnable: Runnable? = null
    // R1-fix: 拟人延迟点击Runnable引用——延迟窗口(600-2200ms)内停自动/硬超时fold/重初始化时必须撤销，
    // 否则"停自动后仍点击""超时fold后迟到Runnable双点"
    private var _pendingTapRunnable: Runnable? = null
    // R8-fix: 策略回调代次——策略超时恢复后迟到的showAdvice/autoDecision不再生效，消除竞态跳帧
    @Volatile private var _strategyGeneration = 0L
    // P0-fix #6: 截屏超时兜底——MediaProjection不回调时强制恢复
    private var _screenshotTimeoutRunnable: Runnable? = null
    // V2.9.547: 保留变量仅用于cancelBleAckTimeout()安全清理（USB同步ACK后不再schedule）
    private var _bleAckTimeoutRunnable: Runnable? = null
    // P0-fix #8: 截屏串行化门闩——防止自动/手动截屏并发覆盖回调
    private val _screenshotGate = java.util.concurrent.atomic.AtomicBoolean(false)
    private var _lastStrategyAdvice = ""   // V2.9.113: 最后策略结果
    // V2.9.155: 崩溃状态——JS ReferenceError/未捕获异常时悬浮球显示「崩」+红+快闪
    private var _isCrashed = false
    private var _lastCrashReason = ""
    private val pendingJsCalls = java.util.Collections.synchronizedList(mutableListOf<String>())  // P2-fix: WebView线程与主线程并发安全
    // V2.9.167: 诊断日志变量——记录每次识别的完整信息
    private var _diagStartTime = 0L
    // V2.9.503: Pipeline耗时追踪（@Volatile保证@JavascriptInterface后台线程可见性）
    @Volatile private var _pipelineScreenshotTime = 0L
    @Volatile private var _pipelineJsDecisionTimeMs = 0L
    @Volatile private var _pipelineEsp32TapTimeMs = 0L
    @Volatile private var _pipelineTotalTimeMs = 0L
    @Volatile private var _pipelineLastAction = ""
    // V2.9.114: WebViewAssetLoader——Google官方推荐的本地HTML加载方案
    private lateinit var assetLoader: WebViewAssetLoader
    // V2.9.70: 错误日志——API/截屏失败时记录，豪哥可导出反馈
    // P2-fix: 线程安全的errorLogs，防止并发写入导致ConcurrentModificationException
    private val errorLogs = java.util.Collections.synchronizedList(mutableListOf<String>())
    private val ERROR_LOG_FILE = "error_logs.txt"
    private val MAX_ERROR_LOGS = 50
    private var isBlinkingError = false
    // R6-fix: addErrorLog文件写入频率限制（防I/O风暴导致ANR）
    @Volatile private var lastErrorLogWriteTime = 0L
    private val ERROR_LOG_MIN_INTERVAL_MS = 1000L // 最少1秒写一次文件
    
    // V2.9.183: errorLogs文件持久化——防止重启丢失
    private fun loadErrorLogs() {
        try {
            val file = File(filesDir, ERROR_LOG_FILE)
            if (file.exists()) {
                errorLogs.clear()
                errorLogs.addAll(file.readLines().takeLast(MAX_ERROR_LOGS))
            }
        } catch (_: Exception) {}
    }
    
    private fun addErrorLog(entry: String) {
        // P2-R3-8: 使用同步块保证复合操作原子性
        synchronized(errorLogs) {
            errorLogs.add(entry)
            if (errorLogs.size > MAX_ERROR_LOGS) errorLogs.removeAt(0)
        }
        // R6-fix: 文件写入频率限制，防止高频错误导致I/O风暴ANR
        val now = System.currentTimeMillis()
        if (now - lastErrorLogWriteTime < ERROR_LOG_MIN_INTERVAL_MS) return
        lastErrorLogWriteTime = now
        try {
            File(filesDir, ERROR_LOG_FILE).appendText(entry + "\n", Charsets.UTF_8)
            // 滚动保留最近200行
            val file = File(filesDir, ERROR_LOG_FILE)
            if (file.length() > 32768) {
                val lines = file.readLines()
                file.writeText(lines.takeLast(MAX_ERROR_LOGS).joinToString("\n") + "\n")
            }
        } catch (_: Exception) {}
    }

    // V2.9.547: USB直连ESP32（替代WiFi TCP/BLE）
    private var bleManager: Esp32UsbManager? = null
    private var tvBle: TextView? = null
    private var tvBleStatus: TextView? = null
    private var bleStatusPending = false
    private var _lastRssi = 0
    private var _bleConnectTime = 0L

    // V2.9.38: 隐身模式通知广播接收器
    private val notificationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_CAPTURE -> triggerCapture()
                ACTION_OPEN -> {
                    // V2.9.570: getLaunchIntentForPackage在系统冻结/组件异常时可能返回null，
                    // 直接startActivity(null)会NPE闪退；显式Intent兜底+try-catch
                    val openIntent = packageManager.getLaunchIntentForPackage(packageName)
                        ?: Intent(this@FloatingService, MainActivity::class.java)
                    openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        startActivity(openIntent)
                    } catch (e: Exception) {
                        Log.e(TAG, "ACTION_OPEN 打开主界面失败", e)
                    }
                }
                ACTION_EXPORT -> exportLogFromNotification()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopSelf()
            return START_NOT_STICKY
        }
        // V2.9.125: 处理START action——系统杀后台后重启走这里而非onCreate
        // 如果窗口/悬浮球未创建，必须重新初始化，否则策略引擎链路断裂
        if (floatingView == null || floatingBall == null) {
            Log.i("FloatingService", "onStartCommand: window/ball null, re-initializing")
            try {
                showFloatingWindow()
                showFloatingBall()
                reinitializeComponents()  // V2.9.184: 恢复CardRecognizer/BLE/广播接收器
            } catch (e: Exception) {
                Log.e("FloatingService", "onStartCommand re-init failed", e)
            }
        }
        // 确保前台服务通知存在
        try {
            val notification = createNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e("FloatingService", "onStartCommand startForeground failed", e)
        }
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        
        // V2.9.182: 崩溃保护——兜底写入日志到文件
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "★ 应用崩溃: ${throwable.message}", throwable)
            DiagnosticLogger.flushCrashLog(throwable)
            // 交给系统默认处理器（显示崩溃对话框）
            defaultHandler?.uncaughtException(thread, throwable)
        }
        createNotificationChannel()
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        isStealthMode = prefs?.getBoolean(KEY_STEALTH_MODE, false) ?: false
        loadErrorLogs()  // V2.9.183: 加载持久化错误日志

        // V2.9.200: 初始化游戏模式配置（读取用户上次选择的平台）
        GameModeConfig.init(this)

        // V2.9.503: 初始化诊断日志器（获取Context以使用应用私有目录）
        DiagnosticLogger.init(this)

        // V2.9.518: 初始化本地CV识别引擎
        VisionApiClient.initContext(this)

        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        isRunning = true

        // V2.9.68: WakeLock保活——防止一加/小米等杀后台
        // P0-fix: 先释放旧WakeLock防止re-create时泄漏（旧引用丢失导致无法release）
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {}
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "viewopt::FloatingService")
            wakeLock?.acquire(4 * 60 * 60 * 1000L) // 最长4小时
        } catch (e: Exception) {
            Log.w("FloatingService", "WakeLock acquire failed", e)
        }

        // V2.9.38: 注册通知按钮广播接收器
        val filter = IntentFilter().apply {
            addAction(ACTION_CAPTURE)
            addAction(ACTION_OPEN)
            addAction(ACTION_EXPORT)
        }
        // V2.9.38: Android 14+必须指定RECEIVER_EXPORTED/RECEIVER_NOT_EXPORTED
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            registerReceiver(notificationReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(notificationReceiver, filter)
        }

        showFloatingWindow()
        showFloatingBall()

        // V2.9.547: 初始化USB直连管理器（替代WiFi TCP）
        bleManager = Esp32UsbManager(this)
        setupBleCallbacks()
        bleManager?.start()
    }

    private fun setupBleCallbacks() {
        bleManager?.onStatusChanged = { connected, message ->
            try { DiagnosticLogger.setBleConnected(connected) } catch (_: Exception) {}
            handler.post {
                try {
                    Log.i(TAG, "ESP32 USB: connected=$connected, msg=$message")
                    // R10-fix: USB直连没有RSSI概念（Esp32UsbManager.onRssiUpdate为no-op从不回调），
                    // 旧BLE遗留的"0dBm"显示让豪哥误判信号差；改为显示USB链接状态，连通即绿色
                    tvBle?.text = if (connected) "🔗 USB" else "📡"
                    tvBle?.setTextColor(if (connected) 0xFF888888.toInt() else 0xFFBDBDBD.toInt())
                    tvStatus?.text = "ESP32: $message"
                    if (connected) {
                        _bleConnectTime = System.currentTimeMillis()
                        updateBleIndicator()
                        tvBleStatus?.text = "查询ESP32状态..."
                        tvBleStatus?.visibility = View.VISIBLE
                        bleStatusPending = true
                        handler.postDelayed({ bleManager?.sendStatus() }, 500)
                        handler.postDelayed({
                            try {
                                if (bleStatusPending) {
                                    tvBleStatus?.text = "ESP32: status无响应"
                                    bleStatusPending = false
                                }
                            } catch (_: Exception) {}
                        }, 5500)
                    } else {
                        _lastRssi = 0
                        _bleConnectTime = 0L
                        updateBleIndicator()
                        // V2.9.547: USB断连不杀自动模式，等USB重新插入
                        // FSM如果在EXECUTING状态，强制RESET避免卡死
                        val fsmState = pipelineFSM.getCurrentState()
                        if (fsmState != PipelineStateMachine.PipelineState.IDLE &&
                            fsmState != PipelineStateMachine.PipelineState.COOLDOWN &&
                            fsmState != PipelineStateMachine.PipelineState.ERROR_RECOVERY) {
                            Log.w(TAG, "★ USB断连时FSM在$fsmState，强制RESET→IDLE")
                            cancelBleAckTimeout()
                            pipelineFSM.transition(PipelineStateMachine.PipelineEvent.RESET)
                        }
                        updateAdviceNotification("⚠️ ESP32断开", "请检查USB连接...")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "ESP32 USB onStatusChanged error", e)
                }
            }
        }

        bleManager?.onRssiUpdate = { rssi ->
            handler.post {
                try {
                    _lastRssi = rssi
                    tvBle?.text = "🔗 ${rssi}dBm"
                    tvBle?.setTextColor(when {
                        rssi > -50 -> 0xFF888888.toInt()
                        rssi >= -70 -> 0xFFFFEB3B.toInt()
                        else -> 0xFFFF5252.toInt()
                    })
                } catch (e: Exception) {
                    Log.w(TAG, "onRssiUpdate error", e)
                }
            }
        }

        bleManager?.onCommandResult = { result ->
            handler.post {
                try {
                    Log.d(TAG, "ESP32 USB result: ${result.take(200)}")
                    bleStatusPending = false
                    if (result.startsWith("ok:")) {
                        // 取消可能存在的乐观超时
                        if (_bleAckTimeoutRunnable != null) {
                            cancelBleAckTimeout()
                            if (pipelineFSM.getCurrentState() == PipelineStateMachine.PipelineState.EXECUTING) {
                                Log.d(TAG, "★ ESP32 ACK收到→BLE_EXEC_OK")
                                pipelineFSM.transition(PipelineStateMachine.PipelineEvent.BLE_EXEC_OK)
                                handler.postDelayed({ endCooldownAndScheduleNext() }, 1500)
                            }
                        }
                        // 格式化status显示
                        if (result.startsWith("ok:ver") || result.startsWith("ok:selftest") ||
                            result.startsWith("ok:rssi") || result.startsWith("ok:log")) {
                            val fields = result.removePrefix("ok:")
                            tvBleStatus?.text = "ESP32状态:\n" + fields.split(",").joinToString("\n") { "  $it" }
                            tvBleStatus?.visibility = View.VISIBLE
                        }
                    } else if (result.startsWith("err:")) {
                        Log.w(TAG, "ESP32 error: $result")
                        if (result.contains("old_firmware")) {
                            // ESP32里是旧固件（v2.x WiFi版），Feature通道不存在，必须刷v3.0.0
                            bleStatusPending = false
                            tvBleStatus?.text = "⚠️ ESP32固件过旧\n请用ESPFlasher刷入v3.0.0固件\n(当前${result.substringAfter("(").substringBefore(")")})"
                            tvBleStatus?.visibility = View.VISIBLE
                            tvStatus?.text = "ESP32固件需升级到v3.0.0"
                        } else if (result.contains("not_connected") || result.contains("disconnected")) {
                            // USB已断开，请重新插入，不杀自动模式
                            updateAdviceNotification("⚠️ ESP32断开", "等待重连...")
                            tvStatus?.text = "ESP32: $result"
                        } else {
                            tvStatus?.text = "ESP32: $result"
                        }
                    } else if (result.startsWith("hb:")) {
                        // 心跳，不显示
                    } else if (result.startsWith("hello:")) {
                        Log.i(TAG, "ESP32 hello: $result")
                    } else {
                        tvStatus?.text = "ESP32: $result"
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "ESP32 onCommandResult error", e)
                }
            }
        }
    }

    private fun reinitializeComponents() {
        // R1-fix: 重初始化时撤销所有在途延迟点击/策略回调，旧管线对象全部作废
        cancelPendingTap()
        _strategyGeneration++
        _shotClockRunnable?.let { handler.removeCallbacks(it) }
        _shotClockRunnable = null
        _strategyTimeoutRunnable?.let { handler.removeCallbacks(it) }
        _strategyTimeoutRunnable = null
        // R2复核: reinit同时撤销多帧截屏超时Runnable并释放残留门闩——
        // 否则旧第二拍超时Runnable会在新管线运行中触发，误清新回调/误释放门闩
        _screenshotTimeoutRunnable?.let { handler.removeCallbacks(it) }
        _screenshotTimeoutRunnable = null
        _screenshotGate.set(false)
        // R3-fix: 必须先stop旧USB管理器——注销旧usbReceiver+释放旧连接，
        // 否则双接收器注册、双连接claim抢占、ACK串扰
        try { bleManager?.stop() } catch (_: Exception) {}
        bleManager = null

        // R6-fix(本次复核): reinit由onStartCommand在showFloatingWindow()之后调用，
        // WebView是刚重建的新实例，绝不能destroy置null（否则全文件无第二处创建，策略引擎永久空白）。
        // 旧WebView（若View树已被系统拆）已被showFloatingWindow的新实例替代；这里仅阻断旧回调入队。
        webViewReady = false
        // R6-fix: 清空待发送JS队列，防止旧回调触发后执行已失效的JS
        synchronized(pendingJsCalls) { pendingJsCalls.clear() }
        // R9-8-fix: 新WebView重试计数归零，防止旧计数耗尽后新页面一次失败就判崩溃
        _webViewRetryCount = 0
        // 若WebView存在（新实例），重新加载策略页面确保内容就绪
        try { webView?.loadUrl("https://appassets.androidplatform.net/assets/poker_helper.html") } catch (_: Exception) {}

        // P1-fix: 先反注册再重新注册，防止重复注册导致IllegalArgumentException
        try { unregisterReceiver(notificationReceiver) } catch (_: Exception) {}
        try {
            val filter = IntentFilter().apply {
                addAction(ACTION_CAPTURE)
                    addAction(ACTION_OPEN)
                addAction(ACTION_EXPORT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                registerReceiver(notificationReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(notificationReceiver, filter)
            }
        } catch (_: Exception) {}
        
        
        
        // V2.9.547: 重新初始化USB直连
        bleManager = Esp32UsbManager(this)
        setupBleCallbacks()
        bleManager?.start()

        Log.i(TAG, "reinit: all components restored")
    }

    override fun onDestroy() {
        isRunning = false
        // P1-R4-4: 第一步立刻标记WebView不可用，阻断executeJs和JS回调
        webViewReady = false
        try {
            webView?.stopLoading()
            webView?.removeJavascriptInterface("AndroidBridge")
        } catch (_: Exception) {}
        // P0-fix: 清除ScreenOptService回调，防止引用已死FloatingService实例导致NPE/内存泄漏
        try { ScreenOptService.onScreenshotReady = null } catch (_: Exception) {}
        // V2.9.68: 释放WakeLock
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        wakeLock = null
        currentPanelWidth = 0
        currentPanelHeight = 0
        handler.removeCallbacksAndMessages(null)
        // P2-R3-6: 清理闪烁信号Handler，防止Service销毁后仍执行Runnable
        ballSignalRunnable?.let { ballSignalHandler?.removeCallbacks(it) }
        ballSignalRunnable = null
        ballSignalHandler = null

        // V2.9.547: 停止USB管理器（注销广播+释放连接）
        bleManager?.stop()
        bleManager = null
        removeFloatingBall()
        try {
            unregisterReceiver(notificationReceiver)
        } catch (_: Exception) {}
        try {
            webView?.destroy()
        } catch (_: Exception) {}
        try {
            floatingView?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        handler.postDelayed({ resizeFloatingWindow() }, 500)
    }


    private fun getScreenSize(): Pair<Int, Int> {
        val realW: Int
        val realH: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager?.currentWindowMetrics?.bounds
            realW = bounds?.width() ?: 1080
            realH = bounds?.height() ?: 2344
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager?.defaultDisplay?.getRealMetrics(dm)
            realW = dm.widthPixels
            realH = dm.heightPixels
        }
        return Pair(realW, realH)
    }

    private fun getSavedLandscapeWidth(): Int {
        return prefs?.getInt(KEY_LANDSCAPE_WIDTH, -1) ?: -1
    }

    private fun saveLandscapeWidth(width: Int) {
        prefs?.edit()?.putInt(KEY_LANDSCAPE_WIDTH, width)?.apply()
    }

    private fun getSavedHeightRatio(): Float {
        return prefs?.getFloat(KEY_LANDSCAPE_HEIGHT_RATIO, 0.70f) ?: 0.70f
    }

    private fun saveHeightRatio(ratio: Float) {
        prefs?.edit()?.putFloat(KEY_LANDSCAPE_HEIGHT_RATIO, ratio)?.apply()
    }

    private fun resizeFloatingWindow() {
        if (isStealthMode) return // V2.9.38: 隐身模式不调整窗口
        try {
            val params = floatingView?.layoutParams as? WindowManager.LayoutParams ?: return
            val (screenWidth, screenHeight) = getScreenSize()
            val isLandscape = screenWidth > screenHeight
            applyWindowSize(params, screenWidth, screenHeight, isLandscape)
            windowManager?.updateViewLayout(floatingView, params)
        } catch (e: Exception) {}
    }

    private fun applyWindowSize(params: WindowManager.LayoutParams, screenWidth: Int, screenHeight: Int, isLandscape: Boolean) {
        if (isExpanded) {
            if (isLandscape) {
                val savedW = getSavedLandscapeWidth()
                params.width = if (savedW > 0) savedW else (screenWidth * 0.42).toInt().coerceIn(380, 780)
                val heightRatio = getSavedHeightRatio()
                params.height = (screenHeight * heightRatio).toInt().coerceIn(screenHeight / 3, screenHeight - 150)
                params.gravity = Gravity.END or Gravity.TOP
                params.x = 0
                params.y = 0
                currentPanelWidth = params.width
                currentPanelHeight = params.height
                resizeHandleLeft?.visibility = View.VISIBLE
                resizeHandleBottom?.visibility = View.VISIBLE
            } else {
                params.width = screenWidth
                params.height = screenHeight
                params.gravity = Gravity.TOP or Gravity.START
                params.x = 0
                params.y = 0
                currentPanelWidth = 0
                currentPanelHeight = 0
                resizeHandleLeft?.visibility = View.GONE
                resizeHandleBottom?.visibility = View.GONE
            }
        } else {
            params.width = if (isLandscape) 80 else (screenWidth * 0.4).toInt()
            params.height = WindowManager.LayoutParams.WRAP_CONTENT
            currentPanelWidth = 0
            currentPanelHeight = 0
            resizeHandleLeft?.visibility = View.GONE
            resizeHandleBottom?.visibility = View.GONE
        }
    }

    // V2.9.4: 统一JS调用入口 — WebView未就绪时自动排队
    private fun executeJs(js: String) {
        if (webViewReady && webView != null) {
            // P2-fix: WebView可能已destroy但未置null，try-catch防崩溃
            try { webView?.evaluateJavascript(js, null) } catch (_: Exception) {
                webViewReady = false
                Log.w(TAG, "★ executeJs失败,WebView可能已销毁")
            }
        } else {
            // R6-fix: 原子化的check-then-act，防止竞态突破上限
            synchronized(pendingJsCalls) {
                if (pendingJsCalls.size < _PENDING_JS_MAX) {
                    pendingJsCalls.add(js)
                } else {
                    Log.w(TAG, "★ pendingJsCalls已满($_PENDING_JS_MAX)，丢弃JS调用")
                }
            }
        }
    }

    
    // V2.9.153: AutoCapture
    fun toggleAutoCapture() {
        if (autoCaptureEnabled) {
            stopAutoCapture()
            updateBallAdvice("COLOR:CHECK|SIGNAL:NONE|REASON:自动关闭")
            updateAdviceNotification("⏸ 全自动已停止", "手动模式")
            floatingBall?.let { b ->
                (b.background as? GradientDrawable)?.let { shape ->
                    shape.setColor(0xDD333333.toInt())
                    shape.setStroke(0, 0)
                }
                b.text = "♠"; b.textSize = 18f
            }
        } else {
            startAutoCapture()
            updateAdviceNotification("🟢 全自动运行中", "截屏→分析→自动执行")
            floatingBall?.let { b ->
                (b.background as? GradientDrawable)?.let { shape ->
                    shape.setColor(0xDD00C853.toInt())
                    val density = resources.displayMetrics.density
                    shape.setStroke((3 * density).toInt(), 0xFF00E676.toInt())
                }
                b.text = "▶"; b.textSize = 16f
            }
        }
    }
    // V3.0: AntiDetection — 截屏间隔抖动，避免被检测为机器人
    private object AntiDetection {
        fun getSuggestedInterval(baseInterval: Long): Long {
            try {
                var jittered = baseInterval
                // ±15%随机抖动
                val factor = 1.0 + (Math.random() * 0.3 - 0.15)
                jittered = (baseInterval * factor).toLong()
                // 深夜降速50%（0:00-6:00）
                val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                if (hour >= 0 && hour < 6) {
                    jittered = (jittered * 1.5).toLong()
                }
                return jittered
            } catch (e: Exception) {
                return baseInterval
            }
        }
    }
    private fun startAutoCapture() { autoCaptureEnabled=true; autoConsecutiveErrors=0; lastDecisionTime=0; handStartTime=0; pipelineFSM.reset(); autoCaptureInterval=3500L; executeJs("if(typeof enableAutoExec==='function')enableAutoExec()"); scheduleNextAutoCapture() }
    private fun stopAutoCapture() { autoCaptureEnabled=false; autoCaptureRunnable?.let{handler.removeCallbacks(it)}; autoCaptureRunnable=null; _shotClockRunnable?.let{handler.removeCallbacks(it)}; _shotClockRunnable=null; _screenshotTimeoutRunnable?.let{handler.removeCallbacks(it)}; _screenshotTimeoutRunnable=null; _bleAckTimeoutRunnable?.let{handler.removeCallbacks(it)}; _bleAckTimeoutRunnable=null; cancelPendingTap(); _screenshotGate.set(false); handStartTime=0; latestButtonPositions=emptyList(); cachedPotSize=0; cachedToCall=0; cachedMinRaise=0; pipelineFSM.reset(); executeJs("if(typeof disableAutoExec==='function')disableAutoExec()") }  // R1-fix: cancelPendingTap撤销延迟点击; R9-7-fix: 清空场景缓存
    private fun scheduleNextAutoCapture() {
        if(!autoCaptureEnabled)return; autoCaptureRunnable?.let{handler.removeCallbacks(it)}
        val r=Runnable{if(!autoCaptureEnabled)return@Runnable;if(pipelineFSM.isPipelineActive()){scheduleNextAutoCapture();return@Runnable};val pm=getSystemService(Context.POWER_SERVICE)as PowerManager;if(!pm.isScreenOn){scheduleNextAutoCapture();return@Runnable};autoCaptureTrigger()}
        autoCaptureRunnable=r
        // V3.0: AntiDetection截屏间隔抖动（±15%随机+深夜降速50%）
        val jittered = try { AntiDetection.getSuggestedInterval(autoCaptureInterval) } catch (e: Exception) { autoCaptureInterval }
        handler.postDelayed(r,jittered)
    }
    private fun autoCaptureTrigger() {
        if(!ScreenOptService.isServiceRunning()){
            autoConsecutiveErrors++
            // V3.44: 自动截屏失败也记录日志+悬浮球提示（之前静默重试，用户看不到原因）
            if (autoConsecutiveErrors % 5 == 0) {
                addErrorLog("${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())} 自动截屏失败×${autoConsecutiveErrors}: 无障碍服务未运行")
                updateBallAdvice("COLOR:FOLD|SIGNAL:COUNTER|REASON:无障碍未开")
                updateAdviceNotification("⚠️ 无障碍未运行", "连续${autoConsecutiveErrors}次截屏失败，请检查无障碍权限")
            }
            checkAutoErrors();scheduleNextAutoCapture();return
        }
        // P0-fix #8: 截屏串行化门闩——防止自动/手动截屏并发覆盖回调
        if (!_screenshotGate.compareAndSet(false, true)) {
            Log.w(TAG, "★ P0-fix#8: 截屏门闩已锁，忽略本次autoCaptureTrigger")
            scheduleNextAutoCapture()
            return
        }
        pipelineFSM.transition(PipelineStateMachine.PipelineEvent.START_CAPTURE)  // V3.50: IDLE→CAPTURING（进入截屏态）
        hideOverlay()  // V2.9.190: 截屏前隐藏悬浮层
        // P0-fix #8: 使用安全方法设置回调
        ScreenOptService.setScreenshotCallback { s -> handler.post {
            _screenshotGate.set(false)  // P0-fix#8: 释放门闩
            // P0-fix #6: 截屏回调到达→取消超时
            _screenshotTimeoutRunnable?.let { handler.removeCallbacks(it) }
            _screenshotTimeoutRunnable = null
            showOverlay()  // V2.9.190: 截屏后恢复悬浮层
            if(s){pipelineFSM.transition(PipelineStateMachine.PipelineEvent.SCREENSHOT_OK);processScreenshotAndAnalyze(isAutoCapture=true)}else{
                pipelineFSM.transition(PipelineStateMachine.PipelineEvent.SCREENSHOT_FAIL);autoConsecutiveErrors++  // V3.50: CAPTURING→ERROR_RECOVERY
                // V3.44: 截屏回调失败也记录
                if (autoConsecutiveErrors % 5 == 0) {
                    addErrorLog("${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())} 自动截屏回调失败×${autoConsecutiveErrors}: ${ScreenCaptureService.lastError}")
                }
                checkAutoErrors();scheduleNextAutoCapture()
            }
        }}
        // P0-fix #6: 截屏超时兜底——7秒无回调强制恢复
        _screenshotTimeoutRunnable = Runnable {
            if (pipelineFSM.getCurrentState() == PipelineStateMachine.PipelineState.CAPTURING) {
                Log.w(TAG, "★ P0-fix#6: 截屏超时7s无回调，强制恢复")
                _screenshotGate.set(false)
                ScreenOptService.setScreenshotCallback(null)
                showOverlay()
                pipelineFSM.transition(PipelineStateMachine.PipelineEvent.SCREENSHOT_FAIL)
                autoConsecutiveErrors++
                checkAutoErrors()
                if (autoCaptureEnabled) scheduleNextAutoCapture()
            }
        }
        handler.postDelayed(_screenshotTimeoutRunnable!!, 7000)
        handler.postDelayed({ScreenOptService.captureScreen()}, 100)  // V2.9.192: 延迟100ms等View渲染
    }
// V2.9.190: 截屏前隐藏悬浮层，避免日志面板遮挡扑克桌面
    private fun hideOverlay() {
        try {
            floatingView?.visibility = android.view.View.GONE
            floatingBall?.visibility = android.view.View.GONE
        } catch (_: Exception) {}
    }
    
    private fun showOverlay() {
        try {
            floatingView?.visibility = android.view.View.VISIBLE
            floatingBall?.visibility = android.view.View.VISIBLE
        } catch (_: Exception) {}
    }
    fun onAutoCaptureVisionDone(success:Boolean){val state=pipelineFSM.getCurrentState();when(state){PipelineStateMachine.PipelineState.ERROR_RECOVERY->{pipelineFSM.transition(PipelineStateMachine.PipelineEvent.RECOVERY_DONE)};PipelineStateMachine.PipelineState.STRATEGY_COMPUTING->{};PipelineStateMachine.PipelineState.EXECUTING->{/* R10-fix: verify完成时primary已进入EXECUTING等待tap ACK——若此处抢先转BLE_EXEC_OK→COOLDOWN，tap ACK的EXECUTING→COOLDOWN转换变非法(COOLDOWN无BLE_EXEC_OK)，1.5s收尾调度endCooldownAndScheduleNext丢失，COOLDOWN死锁。EXECUTING→COOLDOWN只能由tap ACK/fallback回调独占 */};else->{pipelineFSM.transition(PipelineStateMachine.PipelineEvent.RESET)}};if(success)autoConsecutiveErrors=0 else{autoConsecutiveErrors++;checkAutoErrors()};if(success)executeJs("if(typeof FrameDiffEngine!=='undefined')FrameDiffEngine.onAutoFrameDone()");scheduleNextAutoCapture()}  // V3.50: state-aware FSM (Bug#2); R10-fix: EXECUTING分支不动FSM
    // V2.9.546: BLE乐观确认已移除——sendTap()同步等ACK，不再需要2s超时
    private fun cancelBleAckTimeout() {
        _bleAckTimeoutRunnable?.let { handler.removeCallbacks(it) }
        _bleAckTimeoutRunnable = null
    }
    // P0-fix #4: 统一cooldown结束→调度下一轮（消除busy-wait）
    private fun endCooldownAndScheduleNext() {
        pipelineFSM.transition(PipelineStateMachine.PipelineEvent.COOLDOWN_END)
        if (autoCaptureEnabled) {
            scheduleNextAutoCapture()
        }
    }
    // V2.9.180: 全自动执行tap——根据action匹配按钮坐标并发送到ESP32
    // v2.9.553 防检测: 拟人随机延迟600-2200ms——真人看到决策再动手的反应时间，打散操作节奏
    // R1-fix: Runnable持引用，触发时刻再校验生命周期/FSM——延迟窗口内状态变化（停自动/
    // 硬超时fold/重初始化/决策迟到）则放弃点击，杜绝"停自动后仍点"和"fold后双点"
    private fun cancelPendingTap() {
        _pendingTapRunnable?.let { handler.removeCallbacks(it) }
        _pendingTapRunnable = null
    }

    private fun executeAutoTapWithHumanDelay(action: String, decisionData: org.json.JSONObject) {
        val humanDelay = (600 + Math.random() * 1600).toLong()
        // 新决策入队前，先撤掉上一个尚未触发的延迟点击（同一时刻只允许一个pending tap）
        cancelPendingTap()
        val r = Runnable {
            _pendingTapRunnable = null
            // R1守卫1：自动已关（用户暂停）→ 不点
            if (!autoCaptureEnabled) {
                Log.w(TAG, "★ R1守卫: 延迟点击触发时自动已关，放弃 $action")
                try { DiagnosticLogger.logError(DiagnosticLogger.ErrorCategory.AUTO_EXEC, DiagnosticLogger.Severity.MEDIUM, "R1守卫拦截: 延迟点击时自动已关", "action=$action") } catch (_: Exception) {}
                return@Runnable
            }
            // R1守卫2：FSM不在EXECUTING（已被硬超时fold/超时重置/错误恢复带走）→ 不点
            if (pipelineFSM.getCurrentState() != PipelineStateMachine.PipelineState.EXECUTING) {
                Log.w(TAG, "★ R1守卫: 延迟点击触发时FSM=${pipelineFSM.getCurrentState()}非EXECUTING，放弃 $action")
                try { DiagnosticLogger.logError(DiagnosticLogger.ErrorCategory.AUTO_EXEC, DiagnosticLogger.Severity.MEDIUM, "R1守卫拦截: 延迟点击时FSM=${pipelineFSM.getCurrentState()}非EXECUTING", "action=$action") } catch (_: Exception) {}
                return@Runnable
            }
            executeAutoTap(action, decisionData)
        }
        _pendingTapRunnable = r
        Log.d(TAG, "[HumanDelay] ${action} 延迟${humanDelay}ms后执行")
        handler.postDelayed(r, humanDelay)
    }

    private fun executeAutoTap(actionIn: String, decisionData: org.json.JSONObject) {
        var action = actionIn  // V2.9.573 物理闸可降级动作(allin/raise→call, check→fold)，故var
        try {
            // V2.9.503: BLE连接检查——未连接时记录警告并跳过，避免无效tap
            if (bleManager?.isConnected != true) {
                Log.w(TAG, "★ executeAutoTap跳过: ESP32未连接 (action=$action)")
                try { DiagnosticLogger.logEsp32Tap("autoTap_${action}_SKIPPED", 0, 0, action, "NOT_CONNECTED") } catch (_: Exception) {}
                try { DiagnosticLogger.logError(DiagnosticLogger.ErrorCategory.COMMUNICATION, DiagnosticLogger.Severity.MEDIUM, "ESP32未连接，${action}自动点击跳过", "connected=false") } catch (_: Exception) {}
                // V2.9.546: 不能只return——FSM会卡在EXECUTING导致下一帧被挡
                // RESET回IDLE，等ESP32重连后下一帧继续
                cancelBleAckTimeout()
                pipelineFSM.transition(PipelineStateMachine.PipelineEvent.RESET)
                if (autoCaptureEnabled) scheduleNextAutoCapture()
                return
            }
            // V2.9.207: Shot Clock保护——检查从手牌开始分析是否超时
            val now = System.currentTimeMillis()
            if (handStartTime > 0 && (now - handStartTime) > SHOT_CLOCK_TIMEOUT) {
                Log.w(TAG, "★ Shot Clock timeout! ${(now - handStartTime)}ms since hand start, forcing emergency fold")
                pipelineFSM.transition(PipelineStateMachine.PipelineEvent.SHOT_CLOCK_TIMEOUT)  // V3.50: 强制fold
                executeAutoTapFallback("fold")
                pipelineFSM.transition(PipelineStateMachine.PipelineEvent.RESET)  // V3.50: 完成→IDLE
                handStartTime = 0; _shotClockRunnable?.let { handler.removeCallbacks(it) }
                lastDecisionTime = now
                updateAdviceNotification("⏰ Shot Clock", "超时强制弃牌")
                updateBallAdvice("COLOR:FOLD|SIGNAL:TIMEOUT|REASON:Shot Clock超时")
                return
            }
            
            // V2.9.573 闸1(物理动作可行性闸): 屏幕按钮集合=动作白名单。
            //   物理真相: 全押面对方时我们屏幕只有【弃牌+跟注】两按钮(右侧加注行不存在)，
            //   永远不可能raise/allin——与识别准确率无关、100%可证(16日志铁证: btn3Y=0全押帧
            //   鱼偏移call→raise+翻后bet sizing路径点右侧空气=豪哥看到的"没点击")。
            //   判据全部来自本帧latestButtonPositions(vision精准坐标，无按钮文字时btns为空)：
            //     hasRaiseRow=屏幕存在"加注/下注/全押/全下"按钮(3按钮或主动全下局面)
            //     hasCallRow =屏幕存在"跟注X"按钮(X>0，面对真下注)
            //     hasCheckRow=屏幕存在"让牌/过牌"按钮(free-check局面)
            val physRaise = latestButtonPositions.any { bp ->
                val t = bp.text ?: ""
                t.contains("加注") || t.contains("下注") || t.contains("raise", true) ||
                t.contains("bet", true) || t.contains("全押") || t.contains("全下") || t.contains("all", true)
            }
            val physCall = latestButtonPositions.any { bp ->
                val t = bp.text ?: ""
                val amt = t.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0
                (t.contains("跟注") || t.contains("call", true)) && amt > 0
            }
            val physCheck = latestButtonPositions.any { bp ->
                val t = bp.text ?: ""
                t.contains("让牌") || t.contains("过牌") || t.contains("check", true)
            }
            run physGate@{
                if (action == "allin") {
                    // 主动全下/纳什push: 屏幕必须有加注/全下按钮行；面对全押(只有跟注)→只能call
                    if (!physRaise) {
                        if (physCall) {
                            Log.w(TAG, "★物理闸: allin但屏幕无加注/全下按钮(面对全押)→降级call | btns=${latestButtonPositions.joinToString(","){it.text}}")
                            try { DiagnosticLogger.logError(DiagnosticLogger.ErrorCategory.AUTO_EXEC, DiagnosticLogger.Severity.HIGH, "物理闸拦截: allin无加注按钮→降级call(面对全押)", "btns=${latestButtonPositions.joinToString(","){it.text}}") } catch (_: Exception) {}
                            action = "call"
                        } else {
                            Log.w(TAG, "★物理闸: allin但屏幕无任何加注/跟注按钮(过渡帧?)→取消点击 | btns=${latestButtonPositions.size}")
                            physicalGateAbort("allin", "屏幕无加注/全下按钮且无跟注按钮", latestButtonPositions)
                            return@executeAutoTap
                        }
                    }
                }
                if (action == "raise" || action == "raise_big") {
                    if (!physRaise) {
                        // 屏幕无加注行: 全押面→call(有跟注)；free-check/过渡无跟注→不点击(raise无意义)
                        if (physCall) {
                            Log.w(TAG, "★物理闸: $action 但屏幕无加注按钮(面对全押)→降级call | btns=${latestButtonPositions.joinToString(","){it.text}}")
                            try { DiagnosticLogger.logError(DiagnosticLogger.ErrorCategory.AUTO_EXEC, DiagnosticLogger.Severity.HIGH, "物理闸拦截: ${action}无加注按钮→降级call(面对全押)", "btns=${latestButtonPositions.joinToString(","){it.text}}") } catch (_: Exception) {}
                            action = "call"
                        } else {
                            Log.w(TAG, "★物理闸: $action 但屏幕无加注按钮且无跟注(free-check误判/过渡帧)→取消点击 | btns=${latestButtonPositions.size}")
                            physicalGateAbort(action, "屏幕无加注按钮且无跟注按钮", latestButtonPositions)
                            return@executeAutoTap
                        }
                    }
                }
                // 面对真下注(有跟注按钮)时check不存在——check类指令保守改fold(v571 free-check保护在
                //   autoDecision层已防fold误点check；这里反向: 真bet面check=错误动作→fold安全方向)
                if (action == "check" && physCall && !physCheck) {
                    Log.w(TAG, "★物理闸: check但屏幕是跟注按钮(面对下注无让牌)→保守fold | btns=${latestButtonPositions.joinToString(","){it.text}}")
                    try { DiagnosticLogger.logError(DiagnosticLogger.ErrorCategory.AUTO_EXEC, DiagnosticLogger.Severity.HIGH, "物理闸拦截: check面对下注→保守fold", "btns=${latestButtonPositions.joinToString(","){it.text}}") } catch (_: Exception) {}
                    action = "fold"
                }
                // call指令但屏幕无跟注、有让牌→check(v566回归红线: free-check帧call必须能落到让牌按钮，
                //   when分支本有call→check fallback，这里仅显式标注，不改动作)
                if (action == "call" && !physCall && physCheck) {
                    Log.d(TAG, "★物理闸: call但屏幕为让牌(free-check)→check按钮匹配 | btns=${latestButtonPositions.joinToString(","){it.text}}")
                }
            }

            // V2.9.206: GG扑克下注金额自动输入——先点预设%按钮，再点加注确认
            if (action == "raise" || action == "raise_big" || action == "allin") {
                val sizing = decisionData.optInt("sizing", 0)
                val pot = decisionData.optInt("pot", 0)
                val phase = decisionData.optString("phase", "post")
                val isNash = decisionData.optBoolean("nash", false)
                // V3.25: 纳什push → 直接点全押按钮
                if (isNash) {
                    Log.d(TAG, "★ 纳什push: 点全押按钮")
                    executeAutoTapFallback("allin")
                    handStartTime = 0; _shotClockRunnable?.let { handler.removeCallbacks(it) }; lastDecisionTime = System.currentTimeMillis()
                    return
                }
                // V3.16: 翻前raise → 直接点GG加注按钮(默认2.5x min-raise)
                //        翻后raise → 四档按钮(33/50/75/100%)
                if (phase == "pre") {
                    // V3.44: 用isStandardPreflopRaise判断加注量是否可用标准按钮
                    val blindBB = decisionData.optInt("blindBB", 0)
                    if (GameModeConfig.isStandardPreflopRaise(sizing, blindBB)) {
                        Log.d(TAG, "★ GG翻前加注: 标准按钮近似 (size=${sizing} BB=${blindBB})")
                        executeAutoTapFallback("raise")
                    } else {
                        Log.d(TAG, "★ GG翻前加注过大(${sizing}/${blindBB}BB)，走全押")
                        executeAutoTapFallback("allin")
                    }
                    handStartTime = 0; _shotClockRunnable?.let { handler.removeCallbacks(it) }; lastDecisionTime = System.currentTimeMillis()
                    return
                }
                if (sizing > 0 && pot > 0 && GameModeConfig.currentPlatform == GamePlatform.GGPOKER) {
                    val betBtnAction = GameModeConfig.getBetButtonAction(sizing, pot)
                    Log.d(TAG, "★ GG bet sizing: action=$action sizing=$sizing pot=$pot → $betBtnAction")
                    // V3.14: 优先尝试精确金额输入（配置了键盘坐标时）
                    // P1-fix: 精确输入含Thread.sleep(~1秒)，移后台线程防ANR
                    Thread({
                        var exactDone = false
                        try {
                            exactDone = executeExactBet(sizing)
                        } catch (e: Exception) {
                            Log.w(TAG, "精确输入异常，fallback预设按钮", e)
                        }
                        if (!exactDone) {
                            // 先点击下注预设按钮
                            executeAutoTapFallback(betBtnAction)
                        }
                        // 延迟150ms后点击加注按钮确认（v3.2.1: 固件非阻塞后200→150ms）
                        try { Thread.sleep(150) } catch (_: InterruptedException) {}
                        handler.post {
                            try {
                                executeAutoTapFallback("raise")
                                handStartTime = 0; _shotClockRunnable?.let { handler.removeCallbacks(it) }; lastDecisionTime = System.currentTimeMillis()
                                Log.d(TAG, "★ GG bet confirm: raise button tapped")
                            } catch (e: Exception) {
                                Log.e(TAG, "GG bet confirm error", e)
                            }
                        }
                    }, "ExactBetThread").start()
                    return
                }
            }
            
            val btns = latestButtonPositions
            Log.d(TAG, "executeAutoTap: action=$action, availableButtons=${btns.size}")
            // V2.9.573 闸1: raise/allin无按钮坐标时禁止fallback点固定坐标
            //   (raise兜底0.819,0.960在全押两按钮局面=空气点，16日志铁证"没点击"根因之一)。
            //   fold/check/call的固定坐标保留(安全方向且GG坐标经实测)。
            if (btns.isEmpty()) {
                if (action == "raise" || action == "raise_big" || action == "allin") {
                    Log.w(TAG, "★物理闸: $action 无按钮坐标→禁止固定坐标兜底，取消点击")
                    physicalGateAbort(action, "无按钮坐标(vision本帧未识别到按钮)，加注类禁止盲点点空气", btns)
                    return
                }
                Log.w(TAG, "autoTap: 无按钮坐标，回退固定位置")
                executeAutoTapFallback(action)
                return
            }
            
            // 根据action匹配按钮
            // V2.9.205: 增强按钮文字匹配——GG扑克按钮文字会变(如加注→All In)，需识别内容再匹配
            val targetBtn = when (action) {
                "fold" -> btns.find { it.text.contains("弃牌") || it.text.contains("fold", true) }
                "check" -> btns.find { it.text.contains("让牌") || it.text.contains("过牌") || it.text.contains("check", true) }
                    ?: btns.find { it.text.contains("跟注") || it.text.contains("call", true) } // check→call fallback
                "call", "weak_call" -> btns.find { it.text.contains("跟注") || it.text.contains("call", true) }
                    ?: btns.find { it.text.contains("让牌") || it.text.contains("过牌") || it.text.contains("check", true) } // call→check fallback
                "raise", "raise_big" -> btns.find { it.text.contains("加注") || it.text.contains("下注") || it.text.contains("raise", true) || it.text.contains("bet", true) }
                    ?: btns.find { it.text.contains("全押") || it.text.contains("全下") || it.text.contains("all", true) } // raise→allin fallback (短码时)
                "allin" -> {
                    // V2.9.573 闸1: allin优先找"全押/全下"文字；没有则fallback"加注"按钮(短码时加注钮
                    //   即为all-in，且翻后bet sizing路径已先点100%预设——静默降级min-raise已由
                    //   sizing路径+入口物理闸消除：到达这里时physRaise=true，点加注钮不再是错误方向)
                    btns.find { it.text.contains("全押") || it.text.contains("全下") || it.text.contains("all", true) }
                        ?: btns.find { it.text.contains("加注") || it.text.contains("下注") || it.text.contains("raise", true) || it.text.contains("bet", true) }
                }
                else -> btns.find { it.text.contains(action, true) }
            }
            
            if (targetBtn != null) {
                val x = (targetBtn.xPct * screenWidth).toInt().coerceIn(0, screenWidth - 1)
                val y = (targetBtn.yPct * screenHeight).toInt().coerceIn(0, screenHeight - 1)
                Log.i(TAG, "★ executeAutoTap: $action → ($x, $y) btn=${targetBtn.text}")
                try {
                    DiagnosticLogger.logEsp32Tap(action, x, y, targetBtn.text.toString(), "sendTap")
                } catch (_: Exception) {}
                // V2.9.546: 同步等ACK，确认ESP32真的执行了HID点击
                // R4-fix: bulkTransfer最坏阻塞800ms，移后台线程（ExactBet同款模式），ACK回主线程驱动FSM
                val tapAction = action
                Thread({
                    val tapOk = bleManager?.sendTap(x, y, 50) ?: false
                    handler.post {
                        // R4-fix: 后台等待期间状态可能已变（停自动/新帧接管），非EXECUTING则丢弃结果
                        if (pipelineFSM.getCurrentState() != PipelineStateMachine.PipelineState.EXECUTING) {
                            Log.w(TAG, "★ R4守卫: tap($tapAction)回主线程时FSM=${pipelineFSM.getCurrentState()}非EXECUTING，丢弃ACK结果")
                            try { DiagnosticLogger.logError(DiagnosticLogger.ErrorCategory.AUTO_EXEC, DiagnosticLogger.Severity.MEDIUM, "R4守卫拦截: tap回主线程时FSM=${pipelineFSM.getCurrentState()}非EXECUTING", "tapAction=$tapAction x=$x y=$y") } catch (_: Exception) {}
                            return@post
                        }
                        _pipelineEsp32TapTimeMs = System.currentTimeMillis() - _pipelineScreenshotTime
                        _pipelineTotalTimeMs = _pipelineEsp32TapTimeMs
                        _pipelineLastAction = tapAction
                        try { DiagnosticLogger.updatePipelineTiming(_pipelineJsDecisionTimeMs, _pipelineEsp32TapTimeMs, _pipelineTotalTimeMs, tapAction) } catch (_: Exception) {}
                        handStartTime = 0; _shotClockRunnable?.let { handler.removeCallbacks(it) }; lastDecisionTime = System.currentTimeMillis()
                        if (tapOk) {
                            Log.i(TAG, "★ Pipeline: 截图→ESP32点击=${_pipelineEsp32TapTimeMs}ms ACK=OK")
                            cancelBleAckTimeout()
                            pipelineFSM.transition(PipelineStateMachine.PipelineEvent.BLE_EXEC_OK)
                            handler.postDelayed({ endCooldownAndScheduleNext() }, 1500)
                        } else {
                            Log.w(TAG, "★ Pipeline: ESP32 tap ACK失败")
                            pipelineFSM.transition(PipelineStateMachine.PipelineEvent.BLE_EXEC_FAIL)
                            if (autoCaptureEnabled) scheduleNextAutoCapture()
                        }
                    }
                }, "AutoTapThread").start()
            } else {
                // V2.9.573 闸1: 未匹配到目标按钮。raise/allin点固定坐标=点空气(全押面右侧无按钮)，
                //   绝不盲发；fold/check/call安全方向保留固定坐标兜底。
                if (action == "raise" || action == "raise_big" || action == "allin") {
                    Log.w(TAG, "★物理闸: $action 未匹配到对应按钮→禁止fallback点固定坐标，取消点击 | btns=${btns.joinToString(","){it.text}}")
                    physicalGateAbort(action, "未匹配到加注/全下按钮(屏幕按钮: ${btns.joinToString(","){it.text}})", btns)
                    return
                }
                Log.w(TAG, "executeAutoTap: 未匹配按钮 $action, 回退固定位置")
                executeAutoTapFallback(action)
            }
        } catch (e: Exception) {
            Log.e(TAG, "executeAutoTap error", e)
            executeAutoTapFallback(action)
            pipelineFSM.transition(PipelineStateMachine.PipelineEvent.BLE_EXEC_FAIL)  // V3.50: Bug#4 BLE执行失败→ERROR_RECOVERY
        }
    }

    /**
     * V2.9.573 闸1: 物理动作不可行时的安全中止——不点击(绝不点空气/错误按钮)、
     * 悬浮窗红底强提醒豪哥人工接管、FSM RESET回IDLE并续截图(不卡链路)。
     * 用于: 屏幕无加注/全下按钮却收到raise/allin指令等"数学上不可能"的动作。
     */
    private fun physicalGateAbort(action: String, detail: String, btns: List<VisionApiClient.ButtonPosition>) {
        try { DiagnosticLogger.logError(DiagnosticLogger.ErrorCategory.AUTO_EXEC, DiagnosticLogger.Severity.HIGH,
            "★物理闸中止: action=$action $detail", "btns=${btns.size}:${btns.joinToString(","){it.text}}") } catch (_: Exception) {}
        try { DiagnosticLogger.logEsp32Tap("physGate_${action}_ABORT", 0, 0, action, "PHYSICAL_GATE_BLOCK") } catch (_: Exception) {}
        try {
            updateAdviceNotification("⛔ 物理闸拦截: $action", "屏幕无对应按钮，请人工操作（$detail）")
            updateBallAdvice("COLOR:FOLD|SIGNAL:PHYS_GATE|EQ:0|REASON:物理闸拦截$action-请人工")
            tvRecResult?.text = "⛔ $action 不可执行"
            tvRecResult?.setBackgroundColor(0xFF8B0000.toInt())
            tvRecDetail?.text = "$detail\n按钮=${btns.joinToString(","){it.text}}"
            tvRecDetail?.visibility = View.VISIBLE
        } catch (_: Exception) {}
        // FSM回IDLE + 续截图(等下一帧物理现实与决策一致时再执行)
        cancelBleAckTimeout()
        pipelineFSM.transition(PipelineStateMachine.PipelineEvent.RESET)
        handStartTime = 0; _shotClockRunnable?.let { handler.removeCallbacks(it) }
        lastDecisionTime = System.currentTimeMillis()
        if (autoCaptureEnabled) scheduleNextAutoCapture()
    }
    // V3.14: 精确金额输入 — 点击输入框+逐个点数字键盘 (GG数字键盘坐标需实测)
    private fun executeExactBet(amount: Int): Boolean {
        try {
            Log.d(TAG, "executeExactBet: amount=$amount")
            val cfg = GameModeConfig.getCoordinateConfig()
            val inputBox = cfg.betInputBox
            val numpad = cfg.numpadKeys
            val confirm = cfg.numpadConfirm
            if (inputBox.isEmpty() || numpad.isEmpty() || confirm.isEmpty()) {
                Log.d(TAG, "executeExactBet: 未配置坐标(inputBox=${inputBox.isNotEmpty()}, numpad=${numpad.isNotEmpty()}, confirm=${confirm.isNotEmpty()})，fallback到4档按钮")
                return false
            }
            val (sw, sh) = getScreenSize()
            val sx = sw.toFloat() / cfg.referenceWidth
            val sy = sh.toFloat() / cfg.referenceHeight
            Log.d(TAG, "executeExactBet: screen=${sw}x${sh}, scaleX=$sx, scaleY=$sy, refW=${cfg.referenceWidth}")
            // 1. 点击金额输入框
            val boxX = ((inputBox[0] + inputBox[2]) / 2 * sx).toInt()
            val boxY = ((inputBox[1] + inputBox[3]) / 2 * sy).toInt()
            Log.d(TAG, "executeExactBet step1: 点击输入框 ($boxX, $boxY)")
            try { DiagnosticLogger.logEsp32Tap("exactBet_inputBox", boxX, boxY, "inputBox", "executeExactBet") } catch (_: Exception) {}
            bleManager?.sendTapFast(boxX, boxY, 50)
            Thread.sleep(250) // 等键盘弹出
            // 1.5 V2.9.370: 先清空已有输入 (消费 numpadBackspace)
            try {
                val backspace = cfg.numpadBackspace
                if (backspace.isNotEmpty()) {
                    val bsX = ((backspace[0] + backspace[2]) / 2 * sx).toInt()
                    val bsY = ((backspace[1] + backspace[3]) / 2 * sy).toInt()
                    try { DiagnosticLogger.logEsp32Tap("exactBet_backspace", bsX, bsY, "backspace", "executeExactBet") } catch (_: Exception) {}
                    repeat(10) { // 最多清10位，覆盖绝大多数下注金额
                        bleManager?.sendTapFast(bsX, bsY, 40)
                        Thread.sleep(25)  // v3.2.1: 固件tap非阻塞后连发间隔60→25ms
                    }
                    Log.d(TAG, "精确输入: 已清空旧值 (backspace x10)")
                }
            } catch (eBs: Exception) {
                Log.w(TAG, "清空旧值异常，继续输入", eBs)
            }
            // 2. 逐个点击数字键
            val digits = amount.toString()
            Log.d(TAG, "executeExactBet step2: 输入数字 ${digits} (${digits.length}位)")
            for ((idx, ch) in digits.withIndex()) {
                val key = numpad[ch.toString()] ?: continue
                val kx = (key[0] * sx).toInt()
                val ky = (key[1] * sy).toInt()
                Log.d(TAG, "executeExactBet step2[$idx]: 点击 '$ch' ($kx, $ky)")
                try { DiagnosticLogger.logEsp32Tap("exactBet_digit_$ch", kx, ky, ch.toString(), "executeExactBet") } catch (_: Exception) {}
                bleManager?.sendTapFast(kx, ky, 40)
                Thread.sleep(30) // v3.2.1: 固件tap非阻塞，按键间隔60→30ms
            }
            // 3. 点击确认
            val cx = ((confirm[0] + confirm[2]) / 2 * sx).toInt()
            val cy = ((confirm[1] + confirm[3]) / 2 * sy).toInt()
            Log.d(TAG, "executeExactBet step3: 点击确认 ($cx, $cy)")
            try { DiagnosticLogger.logEsp32Tap("exactBet_confirm", cx, cy, "confirm", "executeExactBet") } catch (_: Exception) {}
            bleManager?.sendTapFast(cx, cy, 50)
            // V2.9.503: pipeline耗时记录（精确下注路径）
            _pipelineEsp32TapTimeMs = System.currentTimeMillis() - _pipelineScreenshotTime
            _pipelineTotalTimeMs = _pipelineEsp32TapTimeMs
            _pipelineLastAction = "exactBet_$amount"
            try { DiagnosticLogger.updatePipelineTiming(_pipelineJsDecisionTimeMs, _pipelineEsp32TapTimeMs, _pipelineTotalTimeMs, _pipelineLastAction) } catch (_: Exception) {}
            Log.i(TAG, "★ executeExactBet 完成: $amount, Pipeline总耗时=${_pipelineTotalTimeMs}ms")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "精确输入失败", e)
            return false
        }
    }

    // V2.9.200: 回退动态坐标——使用GameModeConfig根据当前平台自动适配
    private fun executeAutoTapFallback(action: String) {
        // V2.9.546: ESP32 USB连接检查
        if (bleManager?.isConnected != true) {
            Log.w(TAG, "★ autoTapFallback跳过: ESP32未连接 (action=$action)")
            try { DiagnosticLogger.logEsp32Tap("fallback_${action}_SKIPPED", 0, 0, action, "NOT_CONNECTED") } catch (_: Exception) {}
            try { DiagnosticLogger.logError(DiagnosticLogger.ErrorCategory.COMMUNICATION, DiagnosticLogger.Severity.MEDIUM, "ESP32未连接，${action}操作跳过", "connected=false") } catch (_: Exception) {}
            // V2.9.546: RESET FSM + scheduleNext，不能卡住
            cancelBleAckTimeout()
            pipelineFSM.transition(PipelineStateMachine.PipelineEvent.RESET)
            if (autoCaptureEnabled) scheduleNextAutoCapture()
            return
        }
        // V3.42: 优先用截图真实尺寸（Android 15显示缩放时截图≠屏幕尺寸）
        val rawSw = ScreenCaptureService.screenshotWidth
        val rawSh = ScreenCaptureService.screenshotHeight
        val (sw, sh) = if (rawSw > 0 && rawSh > 0) Pair(rawSw, rawSh) else getScreenSize()
        val (x, y) = GameModeConfig.getAutoTapFallback(action, sw, sh)
        Log.d(TAG, "★ autoTapFallback: $action → ($x, $y) [screen=${sw}x${sh} platform=${GameModeConfig.currentPlatform}]")
        try { DiagnosticLogger.logEsp32Tap("fallback_$action", x, y, action, "autoTapFallback") } catch (_: Exception) {}
        // R4-fix: bulkTransfer最坏800ms移后台线程；FSM转换全部回主线程按"回时状态"处理——
        // 调用方（硬超时fold/异常路径）若已在主线程RESET，这里的迟到ACK不再做任何转换
        val fbAction = action
        Thread({
            val tapOk = bleManager?.sendTap(x, y, 50) ?: false
            handler.post {
                _pipelineEsp32TapTimeMs = System.currentTimeMillis() - _pipelineScreenshotTime
                _pipelineTotalTimeMs = _pipelineEsp32TapTimeMs
                _pipelineLastAction = "fallback_$fbAction"
                try { DiagnosticLogger.updatePipelineTiming(_pipelineJsDecisionTimeMs, _pipelineEsp32TapTimeMs, _pipelineTotalTimeMs, _pipelineLastAction) } catch (_: Exception) {}
                handStartTime = 0; _shotClockRunnable?.let { handler.removeCallbacks(it) }; lastDecisionTime = System.currentTimeMillis()
                if (pipelineFSM.getCurrentState() != PipelineStateMachine.PipelineState.EXECUTING) {
                    // 硬超时/异常路径的调用方已自行RESET，迟到ACK不做转换（硬超时fold已调度下一轮）
                    Log.d(TAG, "★ R4守卫: fallback tap($fbAction)回调时FSM=${pipelineFSM.getCurrentState()}非EXECUTING，不转换")
                    return@post
                }
                // 仅正常决策路径（STRATEGY_READY建立的EXECUTING）走到这里
                if (tapOk) {
                    cancelBleAckTimeout()
                    pipelineFSM.transition(PipelineStateMachine.PipelineEvent.BLE_EXEC_OK)
                    handler.postDelayed({ endCooldownAndScheduleNext() }, 1500)
                } else {
                    pipelineFSM.transition(PipelineStateMachine.PipelineEvent.BLE_EXEC_FAIL)
                    if (autoCaptureEnabled) scheduleNextAutoCapture()
                }
            }
        }, "AutoTapFallbackThread").start()
        // V3.10: 弃牌后重置识别状态 — 防止同rank不同suit的手牌锁定残留
        if (action == "fold") {
            try {
                VisionApiClient.resetLocks()
                cachedPotSize = 0; cachedToCall = 0; cachedMinRaise = 0
                Log.d(TAG, "★ 弃牌后重置识别状态和缓存")
            } catch (e: Exception) {
                Log.w(TAG, "弃牌重置失败", e)
            }
        }
    }
    // V2.9.210: D按钮座位号→Hero位置名称
    private fun seatIndexToPosition(dealerSeat: Int, totalPlayers: Int): String {
        // Hero固定在座位6，计算Hero相对于D按钮的位置偏移
        val offset = (6 - dealerSeat + 6) % 6
        return when (offset) {
            0 -> "BTN"
            1 -> "SB"
            2 -> "BB"
            3 -> "UTG"
            4 -> if (totalPlayers <= 4) "CO" else "MP"
            5 -> "CO"
            else -> "BTN"
        }
    }

    // V2.9.208: 根据toCall推断按钮坐标（当latestButtonPositions为空时使用GG固定坐标）
    private fun inferButtonPositions(toCall: Int): List<VisionApiClient.ButtonPosition> {
        val isGG = GameModeConfig.currentPlatform == GamePlatform.GGPOKER
        return if (isGG) {
            if (toCall > 0) {
                listOf(
                    VisionApiClient.ButtonPosition("Fold", 0.181, 0.960),
                    VisionApiClient.ButtonPosition("Call", 0.500, 0.960),
                    VisionApiClient.ButtonPosition("Raise", 0.819, 0.960)
                )
            } else {
                listOf(
                    VisionApiClient.ButtonPosition("Check", 0.500, 0.960),
                    VisionApiClient.ButtonPosition("Bet", 0.819, 0.960)
                )
            }
        } else {
            if (toCall > 0) {
                listOf(
                    VisionApiClient.ButtonPosition("弃牌", 0.17, 0.88),
                    VisionApiClient.ButtonPosition("跟注", 0.50, 0.88),
                    VisionApiClient.ButtonPosition("加注", 0.83, 0.88)
                )
            } else {
                listOf(
                    VisionApiClient.ButtonPosition("过牌", 0.50, 0.88),
                    VisionApiClient.ButtonPosition("下注", 0.50, 0.88)
                )
            }
        }
    }

    private fun checkAutoErrors(){if(autoConsecutiveErrors>=AUTO_MAX_ERRORS){stopAutoCapture();updateBallAdvice("COLOR:FOLD|SIGNAL:COUNTER|REASON:自动暂停");updateAdviceNotification("⚠️ 自动模式暂停","连续${AUTO_MAX_ERRORS}次错误")}}
    fun triggerMultiFrameCapture(){
        if(!ScreenOptService.isServiceRunning())return;if(!pipelineFSM.canCapture())return  // V3.50: FSM判断是否可截屏
        // P0-fix #8: 截屏串行化门闩
        if(!_screenshotGate.compareAndSet(false,true))return
        pipelineFSM.transition(PipelineStateMachine.PipelineEvent.START_CAPTURE)  // V3.50: IDLE→CAPTURING
        hideOverlay()  // V2.9.190: 截屏前隐藏悬浮层
        ScreenOptService.setScreenshotCallback { s -> handler.post {
            // P0-fix #6: 取消第一拍超时
            _screenshotTimeoutRunnable?.let { handler.removeCallbacks(it) }; _screenshotTimeoutRunnable = null
            if(s){
                pipelineFSM.transition(PipelineStateMachine.PipelineEvent.SCREENSHOT_OK)
                processScreenshotAndAnalyze(isMultiFrame1=true)
                // R2-fix: 第二拍（verify帧）——原代码第二拍无独立超时兜底：
                // 回调一旦丢失，_screenshotGate永久锁死+悬浮层永久隐藏+自动/手动截屏全停摆。
                // 这里挂第二拍独立7s超时，CAS门闩保证超时与迟到回调只有一方生效。
                handler.postDelayed({
                    if (ScreenOptService.isServiceRunning()) {
                        ScreenOptService.setScreenshotCallback { s2 -> handler.post {
                            // R2-fix: CAS门闩——若超时恢复已先发生(门闩=false)，迟到帧直接空转丢弃
                            if (!_screenshotGate.compareAndSet(true, false)) {
                                Log.w(TAG, "★ R2守卫: 第二拍迟到帧(门闩已释放)，丢弃")
                                return@post
                            }
                            showOverlay()  // V2.9.190: 第二帧截屏后恢复悬浮层
                            _screenshotTimeoutRunnable?.let { handler.removeCallbacks(it) }
                            _screenshotTimeoutRunnable = null
                            if (s2) {
                                // R2-fix-v2: 第二拍成功——只走verify分析，FSM由JS autoCaptureVisionComplete推进，
                                // 绝不发SCREENSHOT_OK（此刻FSM在分析/执行态，该转换非法）
                                processScreenshotAndAnalyze(isMultiFrame2=true)
                            } else {
                                // R2-fix-v2: 第二拍失败——verify帧只是辅助确认，主决策不依赖它。
                                // 只恢复现场(门闩/悬浮层)；FSM推进交给primary既有链路
                                // （成功→onAutoCaptureVisionDone；primary丢失→R8策略超时8+2.5s兜底RESET+调度），
                                // 不发SCREENSHOT_FAIL（分析/执行态为非法转换）、不越权scheduleNext
                                Log.w(TAG, "★ R2-fix: 第二拍截屏失败，仅恢复现场，FSM交由主链路")
                                showOverlay()
                            }
                        }}
                        // R2-fix: 第二拍独立超时——7s无回调则释放门闩+恢复悬浮层+复位FSM+继续调度
                        _screenshotTimeoutRunnable = Runnable {
                            if (_screenshotGate.compareAndSet(true, false)) {
                                // R2-fix-v2: 第二拍超时=verify帧丢失（辅助确认，非主链路）。
                                // 只做防卡死的现场恢复(门闩/回调/悬浮层)；FSM推进交给primary既有链路
                                // （R8策略超时8+2.5s兜底或onAutoCaptureVisionDone），不发非法转换/不越权调度
                                Log.w(TAG, "★ R2-fix: 多帧第二拍超时7s无verify帧，恢复现场(门闩/回调/悬浮层)，FSM交主链路")
                                ScreenOptService.setScreenshotCallback(null)
                                showOverlay()
                            }
                        }
                        handler.postDelayed(_screenshotTimeoutRunnable!!, 7000)
                        ScreenOptService.captureScreen()
                    } else {
                        // 第二拍发起前服务已死：释放门闩恢复现场（FSM由primary主链路/R8兜底，不做转换）
                        Log.w(TAG, "★ R2-fix: 第二拍发起前无障碍服务已死，释放门闩恢复悬浮层")
                        _screenshotGate.set(false)
                        showOverlay()
                    }
                }, multiFrameDelay)
            } else {
                // R9-1-fix: 第一拍失败也要释放门闩+调度下一轮（原代码缺scheduleNext，自动静默停摆）
                showOverlay()
                _screenshotGate.set(false)
                pipelineFSM.transition(PipelineStateMachine.PipelineEvent.SCREENSHOT_FAIL)
                autoConsecutiveErrors++
                checkAutoErrors()
                if (autoCaptureEnabled) scheduleNextAutoCapture()
            }
        }}  // V3.50: FSM替代isVisionInProgress
        // P0-fix #6: 第一拍截屏超时兜底
        _screenshotTimeoutRunnable=Runnable{if(pipelineFSM.getCurrentState()==PipelineStateMachine.PipelineState.CAPTURING){Log.w(TAG,"★ P0-fix#6: 多帧截屏超时7s");_screenshotGate.set(false);ScreenOptService.setScreenshotCallback(null);showOverlay();pipelineFSM.transition(PipelineStateMachine.PipelineEvent.SCREENSHOT_FAIL);autoConsecutiveErrors++;checkAutoErrors();if(autoCaptureEnabled)scheduleNextAutoCapture()}}
        handler.postDelayed(_screenshotTimeoutRunnable!!, 7000)
        handler.postDelayed({ScreenOptService.captureScreen()}, 100)  // V2.9.192: 延迟100ms等View渲染
    }
    fun setAutoCaptureSpeed(ms:Long){autoCaptureInterval=ms.coerceIn(1500L,10000L);if(autoCaptureEnabled)scheduleNextAutoCapture()}

/**
     * V2.9.38: 触发截屏（通知栏按钮调用）
     */
    private fun triggerCapture() {
        // P2-fix: 防止手动截屏与自动截屏/多帧截屏同时触发导致回调被覆盖
        // V3.50: 用状态机判断pipeline是否活跃
        if (!pipelineFSM.canCapture()) {
            Log.w(TAG, "★ triggerCapture被忽略: 上一次识别尚未完成 (state=${pipelineFSM.getCurrentState()})")
            return
        }
        Log.d(TAG, "★ triggerCapture开始: webViewReady=$webViewReady, stealth=$isStealthMode, screenOpt=${ScreenOptService.isServiceRunning()}, apiKey=${VisionApiClient.apiKey.takeLast(4)}")
        executeJs("if(typeof clr==='function'){clr()}")
        tvRecResult?.text = ""
        tvRecResult?.visibility = View.GONE
        tvRecDetail?.text = ""
        tvRecDetail?.visibility = View.GONE
        tvStatus?.text = "🎯 截屏中..."
        executeJs("document.body.classList.add('api-processing')")
        tvAction?.alpha = 0.5f
        // V2.9.109: 诊断通知（不分stealth，始终更新通知方便排查）
        updateAdviceNotification("1/4 截屏中", "无障碍=${ScreenOptService.isServiceRunning()}")

        if (ScreenOptService.isServiceRunning()) {
            // P0-fix #8: 截屏串行化门闩
            if (!_screenshotGate.compareAndSet(false, true)) {
                Log.w(TAG, "★ P0-fix#8: 截屏门闩已锁，忽略triggerCapture")
                return
            }
            pipelineFSM.transition(PipelineStateMachine.PipelineEvent.START_CAPTURE)  // V3.50: IDLE→CAPTURING
            hideOverlay()  // V2.9.190: 截屏前隐藏悬浮层
            ScreenOptService.setScreenshotCallback { success ->
                handler.post {
                    _screenshotGate.set(false)  // P0-fix#8: 释放门闩
                    // P0-fix #6: 取消截屏超时
                    _screenshotTimeoutRunnable?.let { handler.removeCallbacks(it) }
                    _screenshotTimeoutRunnable = null
                    showOverlay()  // V2.9.190: 截屏后恢复悬浮层
                    if (success) {
                        pipelineFSM.transition(PipelineStateMachine.PipelineEvent.SCREENSHOT_OK)  // V3.50: CAPTURING→RECOGNIZING_LOCAL
                        Log.d(TAG, "★ 截屏成功，进入processScreenshotAndAnalyze (state=${pipelineFSM.getCurrentState()})")
                        manualErrorCount = 0  // V2.9.184: 重置手动截屏错误计数
                        updateAdviceNotification("2/4 截屏成功", "正在调用API识别...")
                        processScreenshotAndAnalyze()
                    } else {
                        pipelineFSM.transition(PipelineStateMachine.PipelineEvent.SCREENSHOT_FAIL)  // V3.50: CAPTURING→ERROR_RECOVERY
                        Log.e(TAG, "★ 截屏失败: ${ScreenCaptureService.lastError}")
                        manualErrorCount++  // V2.9.184: 手动截屏失败计数
                        val errMsg = if (manualErrorCount >= 5) "⚠️ 连续${manualErrorCount}次失败，请检查无障碍" else "❌ 截图失败，请重试"
                        tvStatus?.text = errMsg
                        tvAction?.alpha = 1.0f
                        executeJs("document.body.classList.remove('api-processing')")
                        updateAdviceNotification("❌ 截屏失败", ScreenCaptureService.lastError.take(30))
                        // V2.9.109: 失败也变红
                        updateBallAdvice("COLOR:FOLD|SIGNAL:ERROR")

                    }
                }
            }
            // P0-fix #6: 截屏超时兜底——7秒
            _screenshotTimeoutRunnable = Runnable {
                if (pipelineFSM.getCurrentState() == PipelineStateMachine.PipelineState.CAPTURING) {
                    Log.w(TAG, "★ P0-fix#6: 手动截屏超时7s无回调，强制恢复")
                    _screenshotGate.set(false)
                    ScreenOptService.setScreenshotCallback(null)
                    showOverlay()
                    pipelineFSM.transition(PipelineStateMachine.PipelineEvent.SCREENSHOT_FAIL)
                    tvStatus?.text = "❌ 截屏超时"
                    tvAction?.alpha = 1.0f
                    executeJs("document.body.classList.remove('api-processing')")
                }
            }
            handler.postDelayed(_screenshotTimeoutRunnable!!, 7000)
            handler.postDelayed({ScreenOptService.captureScreen()}, 100)  // V2.9.192: 延迟100ms等View渲染
        } else {
            Log.e(TAG, "★ 无障碍服务未运行！")
            tvStatus?.text = "⚠️ 请先开启无障碍服务！"
            tvAction?.alpha = 1.0f
            executeJs("document.body.classList.remove('api-processing')")
            updateAdviceNotification("❌ 无障碍未开启", "请回App开启后重试")
            // V2.9.109: 无障碍没开也变红
            updateBallAdvice("COLOR:FOLD|SIGNAL:NO_TABLE")
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun showFloatingWindow() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val (screenWidth, screenHeight) = getScreenSize()
        val isLandscape = screenWidth > screenHeight

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xEE0a0a0a.toInt())
        }

        // Top bar with buttons - V2.9.177: 增大内边距
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0x990a0a0a.toInt())
            setPadding(6, 4, 6, 4)
        }

        tvStatus = TextView(this).apply {
            text = "青云 v${BuildConfig.VERSION_NAME}"  // V3.44: 动态版本号，不再硬编码
            setTextColor(0xFFe8edf5.toInt())
            textSize = 12f
            setPadding(4, 2, 4, 2)
        }

        // V2.0: 识别结果展示行 - v2.9.35: 紧凑半透明
        // V2.9.43: 第一行显示手牌/桌型/阶段，第二行显示底池/跟注/盲注详情
        tvRecResult = TextView(this).apply {
            text = ""
            setTextColor(0xFFE0E0E0.toInt())
            textSize = 11f  // V2.9.43: 9f→11f 更醒目
            setPadding(6, 2, 6, 2)
            setBackgroundColor(0x990a0a0a.toInt())
            visibility = View.GONE
        }

        // V2.9.43: 识别详情行（底池/跟注/盲注）
        tvRecDetail = TextView(this).apply {
            text = ""
            setTextColor(0xFFB0BEC5.toInt())
            textSize = 8f
            setPadding(6, 0, 6, 2)
            setBackgroundColor(0x990a0a0a.toInt())
            visibility = View.GONE
        }

        // V2.9.1: 🎯截图按钮
        tvAction = TextView(this)
        tvAction?.text = "🎯"
        tvAction?.setTextColor(0xFFFFFFFF.toInt())
        tvAction?.textSize = 14f
        tvAction?.gravity = Gravity.CENTER
        tvAction?.setPadding(6, 2, 6, 2)
        tvAction?.setBackgroundColor(0x00000000)
        tvAction?.setOnClickListener {
            triggerCapture()
        }

        // 筹码重置按钮
        val tvReset = TextView(this).apply {
            text = "🔄"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 12f
            setPadding(6, 2, 6, 2)
            setBackgroundColor(0x00000000)
            setOnClickListener {
                ChipTracker.reset()
                ScreenCaptureService.lastChipStatus = "已重置"
                executeJs("if(typeof onChipReset==='function'){onChipReset()}")
                tvStatus?.text = "筹码已重置"
            }
        }

        val tvCollapse = TextView(this).apply {
            text = "▼"
            setTextColor(0xFF666666.toInt())
            textSize = 10f
            setPadding(4, 2, 4, 2)
            setOnClickListener { toggleExpand() }
        }

        // V2.9.112: BLE连接按钮
        tvBle = TextView(this).apply {
            text = "📡"
            setTextColor(0xFFBDBDBD.toInt())
            textSize = 14f
            setPadding(6, 2, 6, 2)
            setBackgroundColor(0x00000000)
            setOnClickListener {
                if (bleManager?.isConnected == true) {
                    try { DiagnosticLogger.logEsp32Tap("manual_test", 540, 1172, "testTap", "usbIconClick") } catch (_: Exception) {}
                    Thread {
                        val ok = bleManager?.sendTap(540, 1172, 50) ?: false
                        handler.post { tvStatus?.text = if (ok) "tap测试成功" else "tap测试失败" }
                    }.start()
                    tvStatus?.text = "发送tap测试..."
                } else {
                    // V2.9.549: 点📡主动重新枚举，并把总线上所有USB设备显示出来
                    val mgr = bleManager as? Esp32UsbManager
                    val bus = try { mgr?.enumerateBus() } catch (_: Exception) { null }
                    tvStatus?.text = if (bus.isNullOrEmpty() || bus == "总线上无USB设备")
                        "等待ESP32 USB连接...（总线上无USB设备：检查OTG线/供电/插紧）"
                    else
                        "未找到ESP32，USB总线设备:\n$bus"
                    mgr?.startScan()
                }
            }
            setOnLongClickListener {
                bleManager?.disconnect()
                tvStatus?.text = "ESP32已断开"
                true
            }
        }

        // V2.9.200: 平台切换按钮（标准/GG/短牌）
        val tvPlatform = TextView(this).apply {
            text = "🎮"
            setTextColor(0xFF666666.toInt())
            textSize = 14f
            setPadding(6, 2, 6, 2)
            setBackgroundColor(0x00000000)
            setOnClickListener {
                // V2.9.508: 仅支持GGPOKER，禁用平台切换
                tvStatus?.text = "仅支持GGPOKER"
                Log.i(TAG, "平台切换已禁用: 仅支持GGPOKER")
                VisionApiClient.streetLocked = null
                latestButtonPositions = emptyList()
            }
            setOnLongClickListener {
                // 长按显示当前平台全名
                tvStatus?.text = "当前平台: ${GameModeConfig.currentPlatform.displayName}"
                true
            }
        }

        // V2.9.177: BLE诊断信息独立显示行，增大字体+多行显示，区域加大
        tvBleStatus = TextView(this).apply {
            text = ""
            setTextColor(0xFF90caf9.toInt())
            textSize = 14f
            setPadding(8, 4, 8, 4)
            maxLines = 10
            setSingleLine(false)
            setBackgroundColor(0xCC001a33.toInt())
            visibility = View.GONE
        }

        // R9-3-fix: 视图刚在本函数内创建，用局部val传递替代成员!!断言——
        // 初始化中途异常或函数重入时不再命中未赋值视图导致NPE
        topBar.addView(tvStatus, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        tvAction?.let { topBar.addView(it) }
        topBar.addView(tvReset)
        tvBle?.let { topBar.addView(it) }
        topBar.addView(tvPlatform)  // V2.9.200: 平台切换按钮
        topBar.addView(tvCollapse)
        container.addView(topBar)
        tvBleStatus?.let { container.addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)) }
        tvRecResult?.let { container.addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)) }
        tvRecDetail?.let { container.addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)) }  // V2.9.43: 详情行

        // Content row
        val contentRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        resizeHandleLeft = View(this).apply {
            setBackgroundColor(0x40FFFFFF.toInt())
            setOnTouchListener(ResizeWidthTouchListener())
        }
        val leftHandleParams = LinearLayout.LayoutParams(16, LinearLayout.LayoutParams.MATCH_PARENT)
        contentRow.addView(resizeHandleLeft, leftHandleParams)

        val wv = WebView(this)
        wv.setBackgroundColor(0x00000000)
        webView = wv
        val wvParams = LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.MATCH_PARENT,
            1f
        )
        contentRow.addView(wv, wvParams)

        val contentRowParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        )
        container.addView(contentRow, contentRowParams)

        resizeHandleBottom = View(this).apply {
            setBackgroundColor(0x40FFFFFF.toInt())
            setOnTouchListener(ResizeHeightTouchListener())
        }
        val bottomHandleParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 16)
        container.addView(resizeHandleBottom, bottomHandleParams)

        // WebView settings
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            setSupportZoom(false)
            builtInZoomControls = false
        }

        // V2.9.114: WebViewAssetLoader——Google官方推荐方案，无竞态、JS完整保留
        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .setDomain("appassets.androidplatform.net")
            .build()

        // V2.9.109: 清除WebView缓存，防止加载旧版HTML
        wv.clearCache(true)
        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: android.webkit.WebResourceRequest?): android.webkit.WebResourceResponse? {
                // V2.9.114: 用WebViewAssetLoader拦截本地资源请求
                val url = request?.url
                if (url != null) {
                    val response = assetLoader.shouldInterceptRequest(url)
                    if (response != null) return response
                }
                return super.shouldInterceptRequest(view, request)
            }
            override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                Log.e(TAG, "WebView加载失败: code=$errorCode desc=$description url=$failingUrl")
                addErrorLog("${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())} WebView错误: $errorCode $description")
                // P0-fix: 限制重试次数，防止无限重试循环
                _webViewRetryCount++
                if (_webViewRetryCount <= _WEBVIEW_MAX_RETRY) {
                    Log.w(TAG, "★ WebView重试($_webViewRetryCount/$_WEBVIEW_MAX_RETRY)")
                    wv.postDelayed({ wv.loadUrl("https://appassets.androidplatform.net/assets/poker_helper.html") }, 1000)
                } else {
                    Log.e(TAG, "★ WebView重试次数耗尽，标记为不可用")
                    addErrorLog("WebView加载重试${_WEBVIEW_MAX_RETRY}次仍失败，需手动重启服务")
                    // 标记崩溃态让用户知道
                    handler.post {
                        _isCrashed = true
                        _lastCrashReason = "WebView加载失败(${_WEBVIEW_MAX_RETRY}次)"
                        try { renderCrashBall() } catch (_: Exception) {}
                    }
                }
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.d(TAG, "★ WebView加载完成: url=$url")
                _webViewRetryCount = 0  // P0-fix: 成功加载，重置重试计数
                if (!webViewReady) {
                    webViewReady = true
                    // R9-2-fix: copy+clear必须在同一synchronized块内——
                    // 否则刷新瞬间入队的JS会被clear误伤（漏发）或并发遍历异常
                    val calls = synchronized(pendingJsCalls) {
                        val c = ArrayList(pendingJsCalls)
                        pendingJsCalls.clear()
                        c
                    }
                    for (js in calls) {
                        view?.evaluateJavascript(js, null)
                    }
                }
                // V2.9.114: 验证策略引擎是否真的加载了
                view?.evaluateJavascript("if(typeof onVisionResult==='function'){console.log('[V2.9.125] ✅策略引擎就绪')}else{console.log('[V2.9.125] ❌策略引擎未加载！')}", null)
            }
        }
        wv.webChromeClient = object : WebChromeClient() {
            // V2.9.182: 捕获JS端console.log，写入文件日志
            override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                consoleMessage?.let {
                    val msg = "[${it.sourceId() ?: "JS"}:${it.lineNumber()}] ${it.message()}"
                    Log.d("WebViewConsole", msg)
                    DiagnosticLogger.logJsConsole(msg)
                }
                return super.onConsoleMessage(consoleMessage)
            }
        }

        // V2.9.300: HUD对手记忆系统初始化 — 本地持久化 + Gitee云同步
        try {
            val giteeToken = prefs?.getString("gitee_hud_token", null)
            HudLearner.init(this, giteeToken)
            Log.d(TAG, "★ HudLearner初始化完成: ${if (giteeToken != null) "云端模式" else "本地模式"}")
        } catch (e: Exception) {
            Log.e(TAG, "HudLearner初始化失败: ${e.message}", e)
        }

        // V2.9.516: SelfLearner自我学习初始化（SQLite，后台IO）
        try {
            SelfLearner.init(this)
            Log.d(TAG, "★ SelfLearner初始化完成")
        } catch (e: Exception) {
            Log.e(TAG, "SelfLearner初始化失败: ${e.message}", e)
        }

        // ★ 关键：addJavascriptInterface必须在loadUrl之前注册 ★
        wv.addJavascriptInterface(object : Any() {
            @JavascriptInterface
            fun updateStatus(text: String) {
                handler.post { tvStatus?.text = text }
            }
            
            @JavascriptInterface
            fun getChipStatus(): String {
                return ChipTracker.getStatusJson()
            }
            
            // V2.9.155: JS策略引擎崩溃回调——立即把悬浮球变「崩」+FOLD红+4Hz快闪+通知栏警告
            // 用户原话:「崩了能不能显示在悬浮球, 这样我就不会在崩溃的情况下, 还在游戏中」
            @JavascriptInterface
            fun notifyCrash(reason: String) {
                Log.e(TAG, "★ 策略引擎崩溃: " + reason)
                handler.post {
                    _isCrashed = true
                    _lastCrashReason = reason
                    isBlinkingError = false  // 让位给崩溃信号
                    // 1. 通知栏
                    try {
                        updateAdviceNotification("⚠️ 策略引擎崩溃", reason.take(60))
                    } catch (_: Exception) {}
                    // 2. 悬浮球立刻变「崩」——红+4Hz快闪
                    renderCrashBall()
                }
            }
            // V2.9.155: 清除崩溃态（用户重启WebView/重载HTML后调用）
            @JavascriptInterface
            fun clearCrash() {
                Log.d(TAG, "★ 清除崩溃状态")
                handler.post {
                    _isCrashed = false
                    _lastCrashReason = ""
                }
            }
            @JavascriptInterface
            fun resetChips() {
                handler.post {
                    ChipTracker.reset()
                    ScreenCaptureService.lastChipStatus = "已重置"
                }
            }
            
            @JavascriptInterface
            fun showAdvice(advice: String) {
                Log.d(TAG, "showAdvice调用: advice=" + advice)
                handler.post {
                    // V2.9.155: 崩溃态最高优先级——崩溃时只认显式 RELOAD_AFTER_CRASH 才清, 否则忽略
                    if (_isCrashed && !advice.contains("CLEAR_CRASH")) {
                        Log.w(TAG, "★ 策略崩溃态生效, 忽略 advice=" + advice.take(50))
                        return@post
                    }
                    if (advice.contains("CLEAR_CRASH")) {
                        _isCrashed = false
                        _lastCrashReason = ""
                        Log.d(TAG, "★ 收到 CLEAR_CRASH, 崩溃态已清")
                    }
                    // V2.9.70: 收到正常建议→停止错误闪烁
                    isBlinkingError = false
                    // V2.9.113: 标记策略已回调
                    _lastStrategyAdvice = advice
                    _strategyReceived = true
                    // V2.9.125: 策略正常返回→取消超时定时器，防止正常建议被覆盖
                    _strategyTimeoutRunnable?.let { handler.removeCallbacks(it) }
                    _strategyTimeoutRunnable = null
                    // P0-fix #1: 收到策略建议后RESET→IDLE，防止STRATEGY_COMPUTING永久卡死
                    // V2.9.553-rev9: 恢复无条件RESET（撤销rev8的autoCaptureEnabled守卫）。
                    //   rev9方案：autoDecision自带IDLE恢复——当FSM=IDLE且代次匹配时，
                    //   autoDecision跳过STRATEGY_COMPUTING直接进入EXECUTING执行点击。
                    //   这样彻底消除showAdvice与autoDecision之间的竞态。
                    if (pipelineFSM.getCurrentState() == PipelineStateMachine.PipelineState.STRATEGY_COMPUTING) {
                        Log.d(TAG, "★ P0-fix#1: showAdvice触发RESET（STRATEGY_COMPUTING→IDLE）")
                        pipelineFSM.transition(PipelineStateMachine.PipelineEvent.RESET)
                    }
                    if (advice.isNotEmpty()) {
                        tvRecResult?.text = advice  // V2.9.64: 只显示最新建议,不累积
                        tvRecResult?.visibility = View.VISIBLE
                        when {
                            advice.contains("COLOR:FOLD") -> tvRecResult?.setTextColor(0xFFFF5252.toInt())
                            advice.contains("COLOR:WEAK_CALL") -> tvRecResult?.setTextColor(0xFFFF8C00.toInt())
                            advice.contains("COLOR:CALL") -> tvRecResult?.setTextColor(0xFFFFAB40.toInt())
                            advice.contains("COLOR:RAISE_BIG") -> tvRecResult?.setTextColor(0xFF00E676.toInt())
                            advice.contains("COLOR:RAISE") -> tvRecResult?.setTextColor(0xFF69F0AE.toInt())
                            advice.contains("COLOR:ALL_IN") -> tvRecResult?.setTextColor(0xFFCE93D8.toInt())
                            advice.contains("COLOR:CHECK") -> tvRecResult?.setTextColor(0xFFBDBDBD.toInt())
                            // fallback: 旧5色兼容
                            advice.contains("弃牌") -> tvRecResult?.setTextColor(0xFFFF5252.toInt())
                            advice.contains("跟注") -> tvRecResult?.setTextColor(0xFFFFAB40.toInt())
                            advice.contains("全押") -> tvRecResult?.setTextColor(0xFFCE93D8.toInt())
                            advice.contains("加注") -> tvRecResult?.setTextColor(0xFF69F0AE.toInt())
                            advice.contains("让牌") || advice.contains("过牌") -> tvRecResult?.setTextColor(0xFFBDBDBD.toInt())
                        }
                        // V2.9.40: 悬浮球边框也跟着变
                        updateBallAdvice(advice)
                    }
                }
            }
            
            // V2.9.38: 隐身模式通知更新
            @JavascriptInterface
            fun updateNotification(title: String, detail: String) {
                handler.post {
                    // V2.9.116: 所有模式都更新通知栏——让用户看到策略动作
                    updateAdviceNotification(title, detail)
                }
            }
            // V2.9.113: onVisionResult执行成功回调
            @JavascriptInterface
            fun confirmVisionReceived() { Log.d(TAG, "✅ onVisionResult已执行") }
            @JavascriptInterface fun autoCaptureVisionComplete(s:Boolean){handler.post{onAutoCaptureVisionDone(s)}}
            @JavascriptInterface fun triggerMultiFrame(){handler.post{triggerMultiFrameCapture()}}
            @JavascriptInterface fun setAutoSpeed(ms:Long){setAutoCaptureSpeed(ms)}
            @JavascriptInterface fun isAutoCaptureOn():Boolean=autoCaptureEnabled
            // V2.9.180: 全自动决策执行——JS计算置信度后回调
            @JavascriptInterface
            fun autoDecision(jsonData: String) {
                // R8-fix: 在调用线程捕获当前策略代次（@JavascriptInterface在Binder线程，主线程post前取快照）
                val gen = _strategyGeneration
                handler.post {
                    try {
                        val data = org.json.JSONObject(jsonData)
                        var action = data.optString("action", "fold")
                        val auto = data.optBoolean("auto", false)
                        val confidence = data.optString("confidence", "medium")
                        val reason = data.optString("reason", "")
                        val eq = data.optInt("eq", 0)
                        // V2.9.553-rev9-fix-v3: free check局面fold→check（豪哥规则）。
                        //   无需跟注(toCall=0)时弃牌=白白放弃底池，是策略错误；且GG左按钮在check局面文字就是"让牌/弃牌"
                        //   点下去实际执行check，系统却记成fold造成状态错乱。任何"free+fold"一律改为check过牌。
                        // V2.9.571 P0修复：toCallJs是BB归一化值，JS侧BB被无按钮帧垃圾锁存污染时会误判free
                        //   （v570实机铁证：跟注100帧toCallJs=0→fold改check→中间按钮点中"跟注 100"白输钱×2）。
                        //   屏幕真相优先：按钮文字含"跟注X"=面对真下注，绝不是free check；cachedToCall(Kotlin
                        //   原始chips值，不经BB归一化)>0同理。三者都确认无跟注时才允许fold→check。
                        val toCallJs = data.optInt("toCall", -1)
                        if (action == "fold" && (toCallJs == 0 || (toCallJs < 0 && cachedToCall == 0))) {
                            val screenCallBtn = latestButtonPositions.any { bp ->
                                val t = bp.text ?: ""
                                val hasCallText = t.contains("跟注") || t.contains("call", ignoreCase = true)
                                val amt = t.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0
                                hasCallText && amt > 0
                            }
                            if (screenCallBtn || cachedToCall > 0) {
                                Log.w(TAG, "★ free-check保护拦截: 屏幕有跟注按钮(screenCall=$screenCallBtn)/cachedToCall=$cachedToCall>0→保持fold不转check (toCallJs=$toCallJs)")
                                try { DiagnosticLogger.logError(DiagnosticLogger.ErrorCategory.AUTO_EXEC, DiagnosticLogger.Severity.HIGH, "free-check保护误触发拦截: 屏幕显示跟注按钮但JS判free(BB污染?)", "toCallJs=$toCallJs cachedToCall=$cachedToCall btns=${latestButtonPositions.joinToString(","){it.text}}") } catch (_: Exception) {}
                            } else {
                                Log.w(TAG, "★ free-check保护: action=fold但toCall=$toCallJs/cached=$cachedToCall(无需跟注)→改执行check")
                                action = "check"
                            }
                        }
                        Log.d(TAG, "★ autoDecision收到决策: action=$action auto=$auto conf=$confidence reason=$reason eq=$eq% json=${jsonData.take(200)} | state=${pipelineFSM.getCurrentState()}")

                        // R7-fix: 必须校验转换结果——迟到/重复回调时转换非法（状态不变），不得继续点击
                        // R10-fix: transition()非法时返回的是当前态本身，newState!=EXECUTING判断虽能拦截
                        //   "不在EXECUTING"，但语义不够硬；显式校验前置态==STRATEGY_COMPUTING，
                        //   保险起见两道判断并列（RECOGNIZING_LOCAL等态绝不可直接点击）
                        // V2.9.553-rev9-fix: showAdvice的P0-fix#1会同步把FSM→IDLE（比autoDecision的handler.post快），
                        //   自动模式下若FSM=IDLE但代次未变（说明是同一手牌，非新周期），允许恢复→直接执行点击
                        // V2.9.553-rev9-fix-v2: executeAutoTapWithHumanDelay有R1守卫(要求FSM=EXECUTING)，
                        //   IDLE恢复路径FSM=IDLE→R1丢弃→ESP32收不到点击。改用executeAutoTapFallback（无FSM守卫）。
                        // V2.9.555-fix(P0-B): rev9-fix-v2让IDLE恢复一律走executeAutoTapFallback（GG固定坐标
                        //   fold/check/raise 3点），完全绕过latestButtonPositions的vision精准按钮坐标——
                        //   真机实测11次自动点击100% fallback、翻后raise点错到滑块/4006金额。
                        //   修复：IDLE恢复时先补一次STRATEGY_READY转换把FSM推回EXECUTING（语义上"策略已就绪"，
                        //   与正常路径一致），再走executeAutoTap精准坐标路径（含翻后raise的33/50%预设+sizing逻辑）；
                        //   仅当latestButtonPositions为空（vision本帧没识别到按钮）时，才回退固定坐标兜底。
                        if (pipelineFSM.getCurrentState() == PipelineStateMachine.PipelineState.IDLE
                            && auto && gen == _strategyGeneration) {
                            // 取消Shot Clock（精准路径/兜底路径内部都会维护FSM与调度）
                            handStartTime = 0; _shotClockRunnable?.let { handler.removeCallbacks(it) }
                            lastDecisionTime = System.currentTimeMillis()
                            val havePreciseButtons = latestButtonPositions.isNotEmpty()
                            if (havePreciseButtons) {
                                // 补转换：IDLE→EXECUTING，让精准路径内部的R1/R4守卫放行
                                val recoverState = pipelineFSM.transition(PipelineStateMachine.PipelineEvent.STRATEGY_READY)
                                Log.i(TAG, "★ V2.9.555-fix: autoDecision IDLE恢复→补STRATEGY_READY($recoverState)→精准点击 $action (按钮${latestButtonPositions.size}个: ${latestButtonPositions.joinToString(","){it.text}})")
                                if (recoverState != PipelineStateMachine.PipelineState.EXECUTING) {
                                    // 补转换失败（极端竞态）→固定坐标兜底
                                    Log.w(TAG, "★ IDLE恢复补转换失败(=$recoverState)，回退固定坐标 $action")
                                    executeAutoTapFallback(action)
                                    if (autoCaptureEnabled) scheduleNextAutoCapture()
                                    return@post
                                }
                                updateAdviceNotification(
                                    if (confidence == "low" && action == "fold") "🛑 低置信→弃牌" else "🎯 自动执行：$action",
                                    "$reason (eq=$eq%)")
                                // 精准路径：直接执行（不走WithHumanDelay，IDLE恢复本身已在handler.post后；
                                //   executeAutoTap内部自带BLE后台线程+ACK回主线程驱动FSM/调度）
                                executeAutoTap(action, data)
                            } else {
                                // vision本帧未提供按钮坐标→固定坐标兜底（rev9原逻辑）
                                Log.d(TAG, "★ V2.9.553-rev9-fix: autoDecision IDLE恢复→无精准按钮坐标→executeAutoTapFallback")
                                if (confidence == "low" && action == "fold") {
                                    updateAdviceNotification("⚠️ 低置信度强制弃牌", "$reason (eq=$eq%)")
                                    updateBallAdvice("COLOR:FOLD|SIGNAL:LOW_CONF|EQ:$eq|REASON:低置信度强制弃牌")
                                    Log.i(TAG, "autoDecision IDLE恢复：低置信度，强制fold")
                                    executeAutoTapFallback("fold")
                                } else {
                                    Log.i(TAG, "autoDecision IDLE恢复：固定坐标点击 $action (conf=$confidence, eq=$eq%)")
                                    updateAdviceNotification("🎯 自动执行：$action", "$reason (eq=$eq%)")
                                    executeAutoTapFallback(action)
                                }
                                // executeAutoTapFallback的R4守卫在FSM≠EXECUTING时不转换也不调度，这里必须补上
                                if (autoCaptureEnabled) scheduleNextAutoCapture()
                            }
                            return@post
                        } else if (pipelineFSM.getCurrentState() != PipelineStateMachine.PipelineState.STRATEGY_COMPUTING) {
                            Log.w(TAG, "★ R7守卫: autoDecision前置态非STRATEGY_COMPUTING(当前=${pipelineFSM.getCurrentState()})，丢弃不点击 action=$action")
                            try { DiagnosticLogger.logError(DiagnosticLogger.ErrorCategory.AUTO_EXEC, DiagnosticLogger.Severity.HIGH, "R7守卫拦截: 前置态非STRATEGY_COMPUTING(当前=${pipelineFSM.getCurrentState()})", "action=$action") } catch (_: Exception) {}
                            return@post
                        }
                        val newState = pipelineFSM.transition(PipelineStateMachine.PipelineEvent.STRATEGY_READY)  // STRATEGY_COMPUTING→EXECUTING
                        if (newState != PipelineStateMachine.PipelineState.EXECUTING) {
                            Log.w(TAG, "★ R7守卫: autoDecision迟到/重复(当前=$newState)，丢弃不点击 action=$action")
                            try { DiagnosticLogger.logError(DiagnosticLogger.ErrorCategory.AUTO_EXEC, DiagnosticLogger.Severity.HIGH, "R7守卫拦截: 转换结果非EXECUTING(当前=$newState)", "action=$action") } catch (_: Exception) {}
                            return@post
                        }
                        // R8-fix: FSM进入EXECUTING后再查代次——属于已作废旧帧（超时恢复已推进代次）则RESET回去并丢弃
                        if (gen != _strategyGeneration) {
                            Log.w(TAG, "★ R8守卫: autoDecision代次过期($gen!=$_strategyGeneration)，丢弃 action=$action")
                            try { DiagnosticLogger.logError(DiagnosticLogger.ErrorCategory.AUTO_EXEC, DiagnosticLogger.Severity.HIGH, "R8守卫拦截: 代次过期($gen!=$_strategyGeneration)", "action=$action") } catch (_: Exception) {}
                            pipelineFSM.transition(PipelineStateMachine.PipelineEvent.RESET)
                            return@post
                        }
                        
                        // V2.9.503: pipeline耗时——JS决策完成时刻
                        if (_diagStartTime > 0) {
                            _pipelineJsDecisionTimeMs = System.currentTimeMillis() - _diagStartTime
                            _pipelineLastAction = action
                            Log.d(TAG, "★ Pipeline: JS决策耗时=${_pipelineJsDecisionTimeMs}ms (总=${System.currentTimeMillis()-_diagStartTime}ms)")
                        }
                        
                        if (!auto) {
                            // 需要人工确认（如中置信+全押）→ 不执行BLE，回到空闲
                            pipelineFSM.transition(PipelineStateMachine.PipelineEvent.RESET)  // V3.50: 需确认→IDLE（等待下一帧）
                            updateAdviceNotification("⚠️ 需确认: $action", "$reason (eq=$eq%)")
                            updateBallAdvice("COLOR:CHECK|SIGNAL:ALLIN_NEED|EQ:$eq|REASON:全押需确认")
                            return@post
                        }
                        
                        if (confidence == "low" && action == "fold") {
                            // 低置信→强制弃牌
                            updateAdviceNotification("🛑 低置信→弃牌", "$reason (eq=$eq%)")
                            updateBallAdvice("COLOR:FOLD|SIGNAL:LOW_CONF|EQ:$eq|REASON:低置信弃牌")
                            // 执行弃牌tap
                            Log.i(TAG, "autoDecision执行: 低置信→弃牌, eq=$eq%")
                            executeAutoTapWithHumanDelay("fold", data)
                            Log.d(TAG, "autoDecision执行完成: fold")
                        } else if (confidence == "low") {
                            // low置信但free-check保护已把fold改成check→按check执行
                            Log.i(TAG, "autoDecision执行: 低置信但free局面→$action, eq=$eq%")
                            updateAdviceNotification("🎯 自动执行：$action", "$reason (eq=$eq%)")
                            executeAutoTapWithHumanDelay(action, data)
                        } else {
                            // 高/中置信自动执行
                            Log.i(TAG, "autoDecision执行: $action (conf=$confidence, eq=$eq%)")
                            updateAdviceNotification("🤖 自动执行: $action", "$reason (eq=$eq%)")
                            executeAutoTapWithHumanDelay(action, data)
                            Log.d(TAG, "autoDecision执行完成: $action")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "autoDecision error", e)
                    }
                }
            }
            // V2.9.215: 策略决策回传——记录每次决策供复盘学习
            @JavascriptInterface
            fun logDecision(jsonData: String) {
                try {
                    val data = org.json.JSONObject(jsonData)
                    DiagnosticLogger.logDecision(
                        street = data.optString("street", ""),
                        holeCards = data.optString("holeCards", ""),
                        communityCards = data.optString("communityCards", ""),
                        potSize = data.optInt("pot", 0),
                        myChips = data.optInt("myChips", 0),
                        toCall = data.optInt("toCall", 0),
                        totalPlayers = data.optInt("totalPlayers", 0),
                        activePlayers = data.optInt("activePlayers", 0),
                        position = data.optString("position", ""),
                        action = data.optString("action", "fold"),
                        sizing = data.optInt("sizing", 0),
                        eq = data.optInt("eq", 0),
                        confidence = data.optString("confidence", "medium"),
                        reason = data.optString("reason", ""),
                        hClass = data.optString("hClass", "UNKNOWN"),
                        isAuto = data.optBoolean("auto", false),
                        autoExecResult = data.optString("execResult", "skip"),
                        oppStats = data.optString("oppStats", "")
                    )
                    Log.d(TAG, "📝 决策已记录: ${data.optString("action")} eq=${data.optInt("eq")}% conf=${data.optString("confidence")}")
                } catch (e: Exception) {
                    Log.e(TAG, "logDecision error: ${e.message}", e)
                }
            }
            // V2.9.516: SelfLearner — 记录自己的决策（每条街道一条，只记自己不记对手）
            @JavascriptInterface
            fun logSelfDecision(jsonData: String) {
                try {
                    val d = org.json.JSONObject(jsonData)
                    SelfLearner.recordDecision(
                        handId = d.optString("hand_id", ""),
                        holeCards = d.optString("hole_cards", ""),
                        position = d.optString("position", ""),
                        street = d.optString("street", ""),
                        communityCards = d.optString("community_cards", ""),
                        pot = d.optInt("pot", 0),
                        toCall = d.optInt("to_call", 0),
                        action = d.optString("action", "fold"),
                        sizing = d.optInt("sizing", 0),
                        eq = d.optInt("eq", 0),
                        hClass = d.optString("h_class", "UNKNOWN"),
                        confidence = d.optString("confidence", "medium"),
                        reason = d.optString("reason", ""),
                        bb = d.optInt("bb", 200)
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "logSelfDecision error: ${e.message}")
                }
            }
            // V2.9.516: SelfLearner — 一手牌结果（赢/输BB数，由JS筹码差检测触发）
            @JavascriptInterface
            fun selfHandResult(handId: String, resultBb: Float, netChips: Long, resultType: String) {
                try {
                    SelfLearner.handResult(handId, resultBb, netChips, resultType)
                } catch (e: Exception) {
                    Log.e(TAG, "selfHandResult error: ${e.message}")
                }
            }
            // V2.9.516: SelfLearner — 获取复盘数据（最近N手+Leak检测+汇总）
            @JavascriptInterface
            fun getSelfLearnData(limit: Int): String {
                return try {
                    val result = org.json.JSONObject()
                    result.put("total_hands", SelfLearner.getTotalHands())
                    result.put("total_bb", Math.round(SelfLearner.getTotalBb() * 10f) / 10f)
                    result.put("recent", SelfLearner.getRecentHands(limit))
                    result.put("leaks", SelfLearner.detectLeaks())
                    result.toString()
                } catch (e: Exception) {
                    Log.e(TAG, "getSelfLearnData error: ${e.message}")
                    "{}"
                }
            }
            // V2.9.516: SelfLearner — 清空学习数据
            @JavascriptInterface
            fun resetSelfLearn() {
                try { SelfLearner.reset() } catch (_: Exception) {}
            }
            // V2.9.300: 接收JS端对手统计数据并持久化到HudLearner
            @JavascriptInterface
            fun opponentStats(jsonData: String) {
                try {
                    val data = org.json.JSONObject(jsonData)
                    val level = data.optString("level", "micro_nl2")
                    val statsMap = mutableMapOf<String, Float>()
                    val keys = arrayOf("vpip", "pfr", "threeBet", "ats", "foldTo3Bet",
                        "cbetFlop", "cbetTurn", "foldToCBetFlop", "foldToCBetTurn",
                        "callRiver", "checkRaiseFlop", "handsObserved")
                    for (k in keys) {
                        if (data.has(k)) {
                            statsMap[k] = (data.optDouble(k, -1.0)).toFloat()
                        }
                    }
                    HudLearner.recordHand(statsMap, level)
                    Log.d(TAG, "👤 对手统计已记录: level=$level vpip=${statsMap["vpip"]} hands=${statsMap["handsObserved"]}")
                } catch (e: Exception) {
                    Log.e(TAG, "opponentStats error: ${e.message}", e)
                }
            }
            // V2.9.300: 获取HudLearner中已学习的对手画像
            @JavascriptInterface
            fun getLearnedProfile(level: String): String {
                return try {
                    val profile = HudLearner.getOpponentProfile(level)
                    val json = org.json.JSONObject()
                    json.put("vpip", profile.vpip.toDouble())
                    json.put("pfr", profile.pfr.toDouble())
                    json.put("threeBet", profile.threeBet.toDouble())
                    json.put("ats", profile.ats.toDouble())
                    json.put("foldTo3Bet", profile.foldTo3Bet.toDouble())
                    json.put("cbetFlop", profile.cbetFlop.toDouble())
                    json.put("cbetTurn", profile.cbetTurn.toDouble())
                    json.put("foldToCBetFlop", profile.foldToCBetFlop.toDouble())
                    json.put("foldToCBetTurn", profile.foldToCBetTurn.toDouble())
                    json.put("callRiver", profile.callRiver.toDouble())
                    json.put("checkRaiseFlop", profile.checkRaiseFlop.toDouble())
                    json.put("confidence", profile.confidence.toDouble())
                    json.put("totalHandsObserved", profile.totalHandsObserved)
                    json.put("type", profile.type)
                    json.toString()
                } catch (e: Exception) {
                    Log.e(TAG, "getLearnedProfile error: ${e.message}", e)
                    "{}"
                }
            }
            // V2.9.300: 获取当前级别已学习的手数
            @JavascriptInterface
            fun getLearnedHandCount(level: String): Int {
                return try {
                    HudLearner.getHandCount(level)
                } catch (e: Exception) {
                    0
                }
            }
            // V2.9.300: 手动触发HUD云同步
            @JavascriptInterface
            fun syncHudData() {
                try {
                    HudLearner.sync()
                } catch (e: Exception) {
                    Log.e(TAG, "syncHudData error: ${e.message}", e)
                }
            }
            // V2.9.70: JS可获取Kotlin端错误日志，导出时一并带走
            @JavascriptInterface
            fun getErrorLogs(): String {
                return errorLogs.joinToString("\n")
            }
            // V2.9.503: JS端导出日志时获取Kotlin诊断数据（识别/决策/ESP32点击/pipeline耗时）
            @JavascriptInterface
            fun getDiagData(): String {
                return DiagnosticLogger.exportAsJson()
            }
            // V2.9.503: pipeline耗时追踪查询
            @JavascriptInterface
            fun getPipelineTiming(): String {
                return org.json.JSONObject().apply {
                    put("screenshotTime", _pipelineScreenshotTime)
                    put("localCVTimeMs", 0L)
                    put("jsDecisionTimeMs", _pipelineJsDecisionTimeMs)
                    put("esp32TapTimeMs", _pipelineEsp32TapTimeMs)
                    put("totalTimeMs", _pipelineTotalTimeMs)
                    put("lastAction", _pipelineLastAction)
                }.toString()
            }
            // V2.9.560: JS导出路径获取poker_log.txt中的JS console日志
            @JavascriptInterface
            fun getJsConsoleEntries(): String {
                return try {
                    val jsLogFile = File(getExternalFilesDir(null) ?: filesDir, "poker_log.txt")
                    if (jsLogFile.exists()) {
                        val jsLines = jsLogFile.readLines().takeLast(500)
                            .filter { it.contains("] [JS") }
                        org.json.JSONArray(jsLines).toString()
                    } else {
                        "[]"
                    }
                } catch (e: Exception) {
                    "[]"
                }
            }
            
            // P3-R3-9: 将setBlinkFreq移到JS接口对象中，修复JS调用静默失败
            @JavascriptInterface
            fun setBlinkFreq(freq: Int) {
                handler.post {
                    try { startBallSignal(freq) } catch (_: Exception) {}
                }
            }
        }, "AndroidBridge")

        // V2.9.114: WebViewAssetLoader加载——Google官方推荐，零竞态风险
        // HttpServerService已在MainActivity中启动，不重复启动
        wv.loadUrl("https://appassets.androidplatform.net/assets/poker_helper.html")
        Log.d(TAG, "★ WebView通过AssetLoader加载poker_helper.html")

        floatingView = container

        // V2.9.38: ★ 隐身模式 — 1x1像素不可见覆盖层 ★
        if (isStealthMode) {
            // 1x1像素透明覆盖层，FLAG_NOT_TOUCHABLE不接收触摸事件
            // 不用alpha=0和负坐标，某些Android版本会拒绝
            val stealthParams = WindowManager.LayoutParams(
                1, 1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            stealthParams.gravity = Gravity.TOP or Gravity.START
            stealthParams.x = 0
            stealthParams.y = 0
            try {
                windowManager?.addView(floatingView, stealthParams)
            } catch (e: Exception) {
                // 添加失败也不崩溃，WebView仍在后台运行
            }
            // 立即更新通知显示隐身模式已开启
            updateAdviceNotification("显示优化运行中", "点击截屏识别")
        } else {
            // 正常模式：标准悬浮窗
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )

            applyWindowSize(params, screenWidth, screenHeight, isLandscape)

            var initialX = 0
            var initialY = 0
            var initialTouchX = 0f
            var initialTouchY = 0f
            var isDragging = false

            topBar.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isDragging = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - initialTouchX
                        val dy = event.rawY - initialTouchY
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) isDragging = true
                        params.x = initialX + dx.toInt()
                        params.y = initialY + dy.toInt()
                        windowManager?.updateViewLayout(floatingView, params)
                        true
                    }
                    MotionEvent.ACTION_UP -> { !isDragging }
                    else -> false
                }
            }

            windowManager?.addView(floatingView, params)
        }
    }

    /**
     * V2.9.40: 悬浮球 — 一键截屏识别
     * 点击→截屏, 长按→展开/收起面板, 拖动→移动位置
     * 自动吸附到最近的屏幕边缘, 位置记忆
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun showFloatingBall() {
        if (floatingBall != null) return

        val density = resources.displayMetrics.density
        val sizePx = (BALL_SIZE_DP * density).toInt()
        val (screenWidth, screenHeight) = getScreenSize()

        val ball = TextView(this).apply {
            text = "🎯"
            // V2.9.132: 加粗+允许两行(EQ小字+动作大字), 字为王色为辅
            setTypeface(Typeface.DEFAULT_BOLD)
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            setSingleLine(false)
            setLineSpacing(0f, 0.85f)
            val shape = GradientDrawable()
            shape.shape = GradientDrawable.OVAL
            shape.setColor(0xDD111111.toInt())
            shape.setStroke((2 * density).toInt(), 0xFF333333.toInt())
            background = shape
            elevation = 8f
        }
        floatingBall = ball

        val savedX = prefs?.getInt(KEY_BALL_X, -1) ?: -1
        val savedY = prefs?.getInt(KEY_BALL_Y, -1) ?: -1

        val params = WindowManager.LayoutParams(
            sizePx, sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = if (savedX >= 0) savedX else screenWidth - sizePx - 8
        params.y = if (savedY >= 0) savedY else screenHeight / 2 - sizePx / 2
        ballParams = params

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false
        var isLongPressed = false
        var longPressRunnable: Runnable? = null
        var lastClickTime = 0L

        ball.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    isLongPressed = false
                    longPressRunnable = Runnable {
                        isLongPressed = true
                        if (!isStealthMode) {
                            toggleExpand()
                        } else {
                            // V2.9.570: getLaunchIntentForPackage可能返回null（系统冻结/组件异常），
                            // startActivity(null)会NPE闪退（22:17:06实机崩溃根因）；显式Intent兜底+try-catch
                            val openIntent = packageManager.getLaunchIntentForPackage(packageName)
                                ?: Intent(this@FloatingService, MainActivity::class.java)
                            openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            try {
                                startActivity(openIntent)
                            } catch (e: Exception) {
                                Log.e(TAG, "长按悬浮球打开主界面失败", e)
                            }
                        }
                    }
                    handler.postDelayed(longPressRunnable!!, 500)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                        isDragging = true
                        longPressRunnable?.let { handler.removeCallbacks(it) }
                    }
                    if (isDragging) {
                        params.x = initialX + dx.toInt()
                        params.y = initialY + dy.toInt()
                        try {
                            windowManager?.updateViewLayout(floatingBall, params)
                        } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    longPressRunnable?.let { handler.removeCallbacks(it) }
                    if (isDragging) {
                        // 吸附到最近的屏幕边缘
                        val (sw, sh) = getScreenSize()
                        val centerX = params.x + sizePx / 2
                        params.x = if (centerX < sw / 2) 0 else sw - sizePx
                        params.y = params.y.coerceIn(0, sh - sizePx)
                        try {
                            windowManager?.updateViewLayout(floatingBall, params)
                        } catch (_: Exception) {}
                        prefs?.edit()?.putInt(KEY_BALL_X, params.x)?.putInt(KEY_BALL_Y, params.y)?.apply()
                    } else if (!isLongPressed) {
                        val clickTime = System.currentTimeMillis()
                        if (clickTime - lastClickTime < 350) {
                            Log.d(TAG, "★ 悬浮球双击: 手动截屏"); triggerCapture(); lastClickTime = 0L
                        } else {
                            lastClickTime = clickTime
                            handler.postDelayed({
                                if (lastClickTime == clickTime) {
                                    Log.d(TAG, "★ 悬浮球单击: 切换全自动模式") // 缩放反馈
                        floatingBall?.let { b ->
                            b.animate().scaleX(0.85f).scaleY(0.85f).setDuration(80).withEndAction {
                                b.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                            }.start()
                        }
                        toggleAutoCapture()
                                }
                            }, 350)
                        }
                    }
                    true
                }
                else -> false
            }
        }

        try {
            windowManager?.addView(ball, params)
        } catch (e: Exception) {
            // 添加失败不影响主功能
        }
    }

    /**
     * V2.9.40: 更新悬浮球背景色
     */
    private fun updateBallColor(bgColor: Int) {
        try {
            val ball = floatingBall ?: return
            val shape = ball.background as? GradientDrawable ?: return
            shape.setColor(bgColor)
        } catch (_: Exception) {}
    }

    /**
     * V2.9.63: 7色信号系统 — 悬浮球颜色+闪烁信号
     * 🔴红=弃牌 🟠深橙=勉强跟 🟡黄=跟注 🟢绿=加注 💚青绿=重锤 🟣紫=全押 ⚪灰=过牌
     * 🔥慢闪=Tilt对手 ⚔️快闪=反剥削 ⚠️双闪=底池不确定
     *
     * V2.9.132: 7色保留+字为王 — 动作字18f加粗居中, EQ小字(0.5x)在上,
     *           颜色作为情绪背景, 文字作为决策识别(用户原话:「悬浮球里有字, 就什么都清楚」)
     */
    // V2.9.132: 动作字+EQ两行排版(EQ小字+动作大字), 集中管理
    private fun setBallText(ball: TextView, action: String, eqText: String) {
        if (eqText.isNotEmpty()) {
            val text = eqText + "\n" + action
            val sb = SpannableString(text)
            sb.setSpan(RelativeSizeSpan(0.5f), 0, eqText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            ball.text = sb
        } else {
            ball.text = action
        }
        ball.textSize = 18f
    }
    // V2.9.155: 渲染崩溃悬浮球——「崩」字+红+4Hz快闪
    private fun renderCrashBall() {
        try {
            val ball = floatingBall ?: return
            val shape = ball.background as? GradientDrawable ?: return
            val density = resources.displayMetrics.density
            val stroke = (3 * density).toInt()
            shape.setColor(0xBBFF5252.toInt()); shape.setStroke(stroke, 0xFFFF5252.toInt())
            ball.text = "崩"
            ball.textSize = 22f  // 比正常18f略大(单字无EQ)
            ball.setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
            startBallSignal(4)  // 4Hz快闪(V2.9.131换桌闪烁档)
        } catch (e: Exception) {
            Log.e(TAG, "renderCrashBall失败: " + e.message)
        }
    }
    // V1.0.35: 根据BLE心跳状态更新悬浮球边框颜色指示
    private fun updateBleIndicator() {
        try {
            val ball = floatingBall ?: return
            val shape = ball.background as? GradientDrawable ?: return
            val density = resources.displayMetrics.density
            val stroke = (3 * density).toInt()
            // V2.9.546: 基于USB连接状态
            val color = when {
                bleManager?.isConnected == true && _lastRssi > -70 -> 0xFF666666.toInt()  // 灰
                bleManager?.isConnected == true -> 0xFFFFEB3B.toInt()  // 黄
                else -> 0xFFFF5252.toInt()  // 红 - 未连接
            }
            shape.setStroke(stroke, color)
        } catch (e: Exception) {
            Log.w(TAG, "updateBleIndicator error", e)
        }
    }

        fun updateBallAdvice(advice: String) {
        Log.d(TAG, "updateBallAdvice: advice=$advice, ball=${if(floatingBall!=null)"存在" else "null"}")
        // V2.9.155: 崩溃态最高优先级——忽略任何普通 advice, 强制显示「崩」
        if (_isCrashed) {
            renderCrashBall()
            return
        }
        try {
            val ball = floatingBall ?: return
            val shape = ball.background as? GradientDrawable ?: return
            val density = resources.displayMetrics.density
            val stroke = (3 * density).toInt()
            // V2.9.107: 解析equity数值显示在悬浮球
            var eqText = ""
            val eqMatch = Regex("\\|EQ:(\\d+)").find(advice)
            if (eqMatch != null) {
                eqText = eqMatch.groupValues[1] + "%"
            }
            // V2.9.63: 7色+3信号(V2.9.132: 颜色保留, 文字加大加粗)
            when {
                advice.contains("COLOR:ALL_IN") -> {
                    shape.setColor(0xBBCE93D8.toInt()); shape.setStroke(stroke, 0xFFCE93D8.toInt())
                    setBallText(ball, "全押", eqText)
                    startBallSignal(0) // 无闪烁
                }
                advice.contains("COLOR:RAISE_BIG") -> {
                    shape.setColor(0xBB00E676.toInt()); shape.setStroke(stroke, 0xFF00E676.toInt())
                    setBallText(ball, "重锤", eqText)
                    startBallSignal(0)
                }
                advice.contains("COLOR:RAISE") -> {
                    shape.setColor(0xBB69F0AE.toInt()); shape.setStroke(stroke, 0xFF69F0AE.toInt())
                    setBallText(ball, "加", eqText)
                    startBallSignal(0)
                }
                advice.contains("COLOR:CALL") -> {
                    shape.setColor(0xBBFFAB40.toInt()); shape.setStroke(stroke, 0xFFFFAB40.toInt())
                    setBallText(ball, "跟", eqText)
                    startBallSignal(0)
                }
                advice.contains("COLOR:WEAK_CALL") -> {
                    shape.setColor(0xBBFF8C00.toInt()); shape.setStroke(stroke, 0xFFFF8C00.toInt())
                    setBallText(ball, "弱跟", eqText)
                    startBallSignal(0)
                }
                advice.contains("COLOR:FOLD") -> {
                    shape.setColor(0xBBFF5252.toInt()); shape.setStroke(stroke, 0xFFFF5252.toInt())
                    // V2.9.70: 悬浮球显示建议文字(V2.9.132: 加大加粗, NO_TABLE特殊)
                    if(advice.contains("NO_TABLE")){
                        ball.text = if(eqText.isNotEmpty()) eqText else "❓"
                        ball.textSize = 20f
                    } else {
                        setBallText(ball, "弃", eqText)
                    }
                    startBallSignal(0)
                }
                advice.contains("COLOR:CHECK") -> {
                    shape.setColor(0xBBBDBDBD.toInt()); shape.setStroke(stroke, 0xFFBDBDBD.toInt())
                    setBallText(ball, "过", eqText)
                    startBallSignal(0)
                }
                // fallback: 旧5色兼容(V2.9.132: 同样加大加粗)
                advice.contains("全押") -> {
                    shape.setColor(0xBBCE93D8.toInt()); shape.setStroke(stroke, 0xFFCE93D8.toInt())
                    setBallText(ball, "全押", eqText)
                    startBallSignal(0)
                }
                advice.contains("加注") -> {
                    shape.setColor(0xBB69F0AE.toInt()); shape.setStroke(stroke, 0xFF69F0AE.toInt())
                    setBallText(ball, "加", eqText)
                    startBallSignal(0)
                }
                advice.contains("跟注") -> {
                    shape.setColor(0xBBFFAB40.toInt()); shape.setStroke(stroke, 0xFFFFAB40.toInt())
                    setBallText(ball, "跟", eqText)
                    startBallSignal(0)
                }
                advice.contains("弃牌") -> {
                    shape.setColor(0xBBFF5252.toInt()); shape.setStroke(stroke, 0xFFFF5252.toInt())
                    setBallText(ball, "弃", eqText)
                    startBallSignal(0)
                }
                advice.contains("让牌") || advice.contains("过牌") -> {
                    shape.setColor(0xBBBDBDBD.toInt()); shape.setStroke(stroke, 0xFFBDBDBD.toInt())
                    setBallText(ball, "过", eqText)
                    startBallSignal(0)
                }
                else -> {
                    shape.setColor(0xBB222222.toInt()); shape.setStroke(stroke, 0xFF333333.toInt())
                    ball.text="🎯";ball.textSize=18f
                    startBallSignal(0)
                }
            }
            // V2.9.546: 覆盖边框颜色为USB连接状态色
            try {
                val bleColor = when {
                    bleManager?.isConnected == true && _lastRssi > -70 -> 0xFF666666.toInt()
                    bleManager?.isConnected == true -> 0xFFFFEB3B.toInt()
                    else -> 0xFFFF5252.toInt()
                }
                shape.setStroke(stroke, bleColor)
            } catch (_: Exception) {}
            // V2.9.63: 信号闪烁
            when {
                advice.contains("SIGNAL:COUNTER") -> startBallSignal(3)   // 快闪: 反剥削
                advice.contains("SIGNAL:TILT") -> startBallSignal(1)     // 慢闪: Tilt对手
                advice.contains("SIGNAL:UNCERTAIN") -> startBallSignal(-1) // 双闪: 底池不确定
                // V2.9.131: 换桌建议信号
                advice.contains("SIGNAL:CHANGE_TABLE_L1") -> startBallSignal(2) // 2s慢闪: 建议换桌
                advice.contains("SIGNAL:CHANGE_TABLE_L2") -> startBallSignal(4) // 0.8s快闪: 强建议换桌
            }
        } catch (_: Exception) {}
    }

    // V2.9.63: 悬浮球信号闪烁
    private var ballSignalRunnable: Runnable? = null
    private var ballSignalHandler: android.os.Handler? = null
    private var ballSignalCount = 0

    private fun startBallSignal(freqHz: Int) {
        // 停止之前的信号
        ballSignalRunnable?.let { ballSignalHandler?.removeCallbacks(it) }
        ballSignalRunnable = null
        if (freqHz == 0) {
            // 无信号,恢复正常透明度
            floatingBall?.alpha = 1.0f
            return
        }
        if (ballSignalHandler == null) ballSignalHandler = android.os.Handler(android.os.Looper.getMainLooper())
        ballSignalCount = 0
        val intervalMs = when {
            freqHz == -1 -> 150L  // 双闪
            freqHz == 1 -> 1000L  // 慢闪1Hz
            freqHz == 2 -> 2000L  // V2.9.131: 2s慢闪(L1建议换桌)
            freqHz == 3 -> 167L   // 快闪3Hz
            freqHz == 4 -> 800L   // V2.9.131: 0.8s快闪(L2强建议换桌)
            else -> return
        }
        val runnable = object : Runnable {
            override fun run() {
                val ball = floatingBall ?: return
                if (freqHz == -1) {
                    // 双闪: 闪2下停
                    ballSignalCount++
                    ball.alpha = if (ballSignalCount % 2 == 1) 0.2f else 1.0f
                    if (ballSignalCount >= 4) {
                        // 闪完2次,暂停1.2秒
                        ballSignalCount = 0
                        ballSignalHandler?.postDelayed(this, 1200)
                        return
                    }
                } else {
                    // 正常闪烁
                    ball.alpha = if (ball.alpha < 0.5f) 1.0f else 0.2f
                }
                ballSignalHandler?.postDelayed(this, intervalMs)
            }
        }
        ballSignalRunnable = runnable
        ballSignalHandler?.post(runnable)
    }

    private fun removeFloatingBall() {
        try {
            floatingBall?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {}
        floatingBall = null
        ballParams = null
    }

    private inner class ResizeWidthTouchListener : View.OnTouchListener {
        private var startWidth = 0
        private var startTouchX = 0f

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View?, event: MotionEvent): Boolean {
            val params = floatingView?.layoutParams as? WindowManager.LayoutParams ?: return false
            val (screenWidth, screenHeight) = getScreenSize()
            if (screenWidth <= screenHeight) return false

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startWidth = params.width
                    startTouchX = event.rawX
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = startTouchX - event.rawX
                    val newWidth = (startWidth + dx.toInt()).coerceIn(280, screenWidth - 200)
                    params.width = newWidth
                    currentPanelWidth = newWidth
                    try {
                        windowManager?.updateViewLayout(floatingView, params)
                    } catch (_: Exception) {}
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    saveLandscapeWidth(params.width)
                    currentPanelWidth = params.width
                    return true
                }
            }
            return false
        }
    }

    private inner class ResizeHeightTouchListener : View.OnTouchListener {
        private var startHeight = 0
        private var startTouchY = 0f

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View?, event: MotionEvent): Boolean {
            val params = floatingView?.layoutParams as? WindowManager.LayoutParams ?: return false
            val (screenWidth, screenHeight) = getScreenSize()
            if (screenWidth <= screenHeight) return false

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startHeight = params.height
                    startTouchY = event.rawY
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = event.rawY - startTouchY
                    val newHeight = (startHeight + dy.toInt()).coerceIn(screenHeight / 3, screenHeight - 150)
                    params.height = newHeight
                    currentPanelHeight = newHeight
                    try {
                        windowManager?.updateViewLayout(floatingView, params)
                    } catch (_: Exception) {}
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val ratio = params.height.toFloat() / screenHeight.toFloat()
                    saveHeightRatio(ratio)
                    currentPanelHeight = params.height
                    return true
                }
            }
            return false
        }
    }

    /**
     * V1.9: 处理截图并调用API分析
     * 从 ScreenCaptureService.latestScreenshot 读取截图数据
     * 数据来自ScreenOptService.takeScreenshot()（唯一截图路径）
     */
    private fun processScreenshotAndAnalyze(isAutoCapture:Boolean=false,isMultiFrame1:Boolean=false,isMultiFrame2:Boolean=false) {
        _diagStartTime = System.currentTimeMillis()
        _pipelineScreenshotTime = _diagStartTime
        val screenshot = ScreenCaptureService.latestScreenshot
        val ssInfo = if (screenshot != null) "${screenshot.size/1024}KB" else "null"
        Log.d(TAG, "★ processScreenshotAndAnalyze: screenshot=$ssInfo, apiKey=${VisionApiClient.apiKey.takeLast(4)}, webViewReady=$webViewReady")
        if (screenshot == null) {
            val diag = when {
                !ScreenOptService.isServiceRunning() ->
                    "❌ 截屏失败：无障碍服务未开，请回App开启"
                ScreenCaptureService.lastError.isNotEmpty() ->
                    "❌ 截屏失败: ${ScreenCaptureService.lastError.take(30)}"
                else -> "❌ 截屏失败，请重试"
            }
            tvStatus?.text = diag
            tvAction?.alpha = 1.0f
            executeJs("document.body.classList.remove('api-processing')")
            updateAdviceNotification("❌ 2/4 截图为空", diag)
            // V2.9.70: 截图失败→悬浮球闪烁红 + 记录错误日志
            updateBallAdvice("COLOR:FOLD|SIGNAL:COUNTER")
            isBlinkingError = true
            floatingBall?.text="⚠️";floatingBall?.textSize=14f
            addErrorLog("${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())} 截屏失败: $diag")
            return
        }
        // V2.9.109: 诊断——截图成功，始终更新通知
        Log.d(TAG, "截图成功: ${screenshot.size / 1024}KB, apiKey=${if(VisionApiClient.apiKey.isNotEmpty()) "已配置" else "空"}")
        updateAdviceNotification("2/4 截图OK", "${screenshot.size / 1024}KB, API识别中...")

        if (VisionApiClient.apiKey.isEmpty()) {
            executeJs("if(typeof onActionCapture==='function'){onActionCapture()};document.body.classList.add('speed-mode');document.body.classList.remove('api-processing')")
            tvAction?.alpha = 1.0f
            tvStatus?.text = ScreenCaptureService.lastChipStatus.ifEmpty { "🎯 已更新(无API)" }
            updateAdviceNotification("已更新(无API)", ScreenCaptureService.lastChipStatus)
            return
        }

        // V3.42: 提取截图真实尺寸（供executeAutoTapFallback使用）
        try {
            val opts = android.graphics.BitmapFactory.Options()
            opts.inJustDecodeBounds = true
            android.graphics.BitmapFactory.decodeByteArray(screenshot, 0, screenshot.size, opts)
            ScreenCaptureService.screenshotWidth = opts.outWidth
            ScreenCaptureService.screenshotHeight = opts.outHeight
        } catch (_: Exception) {}

        // V2.9.541: 本地CV为主+VLM兜底
        // 有API Key → 调用视觉模型识别牌面（本地CV已锁牌时只补充场景信息）
        tvStatus?.text = "🎯 API识别中..."
        tvAction?.alpha = 0.5f
        updateAdviceNotification("识别中...", "正在分析牌面")
        val tAnalyzeStart = System.currentTimeMillis()
        // V2.9.207: 记录当前手牌分析开始时间（Shot Clock基准）
        if (autoCaptureEnabled && handStartTime == 0L) {
            handStartTime = tAnalyzeStart
            Log.d(TAG, "★ handStartTime set: $handStartTime")
            // V2.9.503: 调度Shot Clock硬超时——26秒后强制弃牌（适配VLM平均23.5s延迟）
            _shotClockRunnable?.let { handler.removeCallbacks(it) }
            _shotClockRunnable = Runnable {
                if (handStartTime > 0 && autoCaptureEnabled) {
                    Log.w(TAG, "★ Shot Clock HARD TIMEOUT! ${(System.currentTimeMillis() - handStartTime)}ms, forcing fold")
                    handStartTime = 0
                    lastDecisionTime = System.currentTimeMillis()
                    cancelPendingTap()  // R1-fix: 硬超时fold前撤销迟到的延迟点击，防fold后双点
                    _strategyGeneration++  // R8-fix: 作废在途策略回调
                    pipelineFSM.transition(PipelineStateMachine.PipelineEvent.SHOT_CLOCK_TIMEOUT)  // V3.50: 任意状态→EXECUTING(强制fold)
                    executeAutoTapFallback("fold")
                    pipelineFSM.transition(PipelineStateMachine.PipelineEvent.RESET)  // V3.50: 执行完毕→IDLE
                    updateAdviceNotification("⏰ Shot Clock", "超时强制弃牌(硬超时)")
                    updateBallAdvice("COLOR:FOLD|SIGNAL:TIMEOUT|REASON:Shot Clock超时")
                    scheduleNextAutoCapture()
                }
            }
            handler.postDelayed(_shotClockRunnable!!, 26000)
        }
        // V2.9.503: 预检——如果handStartTime已超28秒，跳过VLM直接弃牌
        if (autoCaptureEnabled && handStartTime > 0 && (tAnalyzeStart - handStartTime) > SHOT_CLOCK_TIMEOUT) {
            Log.w(TAG, "★ Shot Clock pre-check: ${(tAnalyzeStart - handStartTime)}ms, skip VLM and force fold")
            handStartTime = 0
            lastDecisionTime = tAnalyzeStart
            cancelPendingTap()  // R1-fix: 硬超时fold前撤销迟到的延迟点击，防fold后双点
            _strategyGeneration++  // R8-fix: 作废在途策略回调
            pipelineFSM.transition(PipelineStateMachine.PipelineEvent.SHOT_CLOCK_TIMEOUT)  // V3.50: 强制fold
            executeAutoTapFallback("fold")
            pipelineFSM.transition(PipelineStateMachine.PipelineEvent.RESET)  // V3.50: 完成→IDLE
            updateAdviceNotification("⏰ Shot Clock", "超时强制弃牌(预检)")
            updateBallAdvice("COLOR:FOLD|SIGNAL:TIMEOUT|REASON:Shot Clock超时")
            scheduleNextAutoCapture()
            return
        }
        Thread {
            try {
                // V2.9.541: 使用并发区域识别方案（本地CV多区域并行）
                val result = VisionApiClient.analyzeScreenshotConcurrent(
                    screenshot,
                    screenWidth = ScreenCaptureService.screenshotWidth.takeIf { it > 0 } ?: 1080,
                    screenHeight = ScreenCaptureService.screenshotHeight.takeIf { it > 0 } ?: 2344
                )
                val tAnalyzeEnd = System.currentTimeMillis()
                Log.d(TAG, "⏱ analyzeSnapshot: ${tAnalyzeEnd-tAnalyzeStart}ms")
                Log.d(TAG, "★ VisionAPI result=${if(result!=null)"成功" else "null"}, lastError=${VisionApiClient.lastError}")
                
                // V2.9.520: 修复上报硬编码，传入VisionApiClient本地CV真实数据
                DiagnosticLogger.logRecognition(
                    localCVEnabled = VisionApiClient.lastLocalCVEnabled,
                    localCVTimeMs = VisionApiClient.lastLocalCVTimeMs,
                    localHandCards = VisionApiClient.lastLocalHandCards,
                    localCommunityCards = VisionApiClient.lastLocalCommCards,
                    localStreet = VisionApiClient.streetLocked,
                    streetLocked = VisionApiClient.streetLocked,
                    holeCardsLocked = VisionApiClient.holeCardsLocked != null,
                    vlmTimeMs = tAnalyzeEnd - tAnalyzeStart,
                    vlmResult = result,
                    totalTimeMs = System.currentTimeMillis() - _diagStartTime,
                    hasError = result == null || result.holeCards.isEmpty(),
                    errorMessage = if (result == null) VisionApiClient.lastError else if (result.holeCards.isEmpty()) "VLM返回空手牌" else null,
                    strategySent = result != null && result.isPokerTable && result.holeCards.isNotEmpty(),
                    rawResponse = if (result == null) VisionApiClient.lastRawResponse else if (result.holeCards.isEmpty()) "VLM返回空手牌" else null,  // V2.9.193
                    localDiag = VisionApiClient.lastLocalDiag
                )
                
                if (result != null) {
                    // V2.9.526: 没轮到我——预处理按钮状态，不发送策略、不点击、不启动Shot Clock
                    if (!result.isMyTurn) {
                        Log.d(TAG, "★ NOT_MY_TURN: 绿色进度条未亮，预处理状态，跳过策略和点击")
                        handStartTime = 0
                        _shotClockRunnable?.let { handler.removeCallbacks(it) }
                        pipelineFSM.transition(PipelineStateMachine.PipelineEvent.RESET)
                        handler.post {
                            updateBallAdvice("COLOR:WAIT|SIGNAL:NOT_MY_TURN|REASON:等待其他玩家")
                            tvStatus?.text = "⏳ 等待中"
                            tvRecResult?.text = "不是我的回合"
                            tvRecResult?.visibility = View.VISIBLE
                            tvRecResult?.setBackgroundColor(0xFF37474F.toInt())
                            tvRecDetail?.text = "底池=${result.potSize} 筹码=${result.playerChips}"
                            tvRecDetail?.visibility = View.VISIBLE
                        }
                        scheduleNextAutoCapture()
                        return@Thread
                    }
                    // V2.9.111: NO_TABLE检测——优先看isPokerTable，其次3信号联合判断
                    val modelSaysNoTable = !result.isPokerTable
                    val noHoleCards = result.holeCards.size < 2
                    val noDButton = result.dButtonPosition.isEmpty() || result.dButtonPosition == "not_found"
                    val noPokerButtons = result.buttons.isEmpty() || result.buttons.none { b ->
                        b.contains("弃牌") || b.contains("跟注") || b.contains("加注") || b.contains("过牌") || b.contains("让牌") || b.contains("下注") || b.contains("全下") || b.contains("全押")
                    }
                    val noTableSignals = (if(noHoleCards) 1 else 0) + (if(noDButton) 1 else 0) + (if(noPokerButtons) 1 else 0)
                    val isNoTable = modelSaysNoTable || noTableSignals >= 2
                    Log.d(TAG, "★ NO_TABLE检测: isPokerTable=${result.isPokerTable} noHole=$noHoleCards noD=$noDButton noBtn=$noPokerButtons signals=$noTableSignals result=$isNoTable")
                    if (isNoTable) {
                        pipelineFSM.transition(PipelineStateMachine.PipelineEvent.NO_TABLE_DETECTED)  // V3.50: 任意状态→IDLE
                        Log.w(TAG, "★ NO_TABLE判定: 不在牌桌(signals=$noTableSignals), dButton=${result.dButtonPosition}, buttons=${result.buttons} | state=${pipelineFSM.getCurrentState()}")
                        // V2.9.207: NO_TABLE时重置handStartTime和场景缓存，避免累积超时和使用过期数据
                        handStartTime = 0
                        _shotClockRunnable?.let { handler.removeCallbacks(it) }
                        latestButtonPositions = emptyList()
                        cachedPotSize = 0; cachedToCall = 0; cachedMinRaise = 0
                        handler.post {
                            updateBallAdvice("COLOR:FOLD|SIGNAL:NO_TABLE|EQ:0|REASON:未检测到牌桌")
                            tvStatus?.text = "❓ 未检测到牌桌"
                            tvRecResult?.text = "🔍 不在游戏桌面"
                            tvRecResult?.visibility = View.VISIBLE
                            tvRecResult?.setBackgroundColor(0xFF8B0000.toInt())
                            tvRecDetail?.text = "D按钮=${result.dButtonPosition} 按钮=${result.buttons}"
                            tvRecDetail?.visibility = View.VISIBLE
                            updateAdviceNotification("❓ 不在牌桌", "D=${result.dButtonPosition} 按钮=${result.buttons.joinToString(",")} WV:$webViewReady")
                        }
                        // V2.9.553-rev9-fix-v3: NO_TABLE分支补调度——旧版此处只更新界面没续截图，
                        // fold后观战帧走入此分支→autoCaptureRunnable被置空无任何东西再触发→截图循环永久停止(日志7分半空白根因)
                        if (autoCaptureEnabled) scheduleNextAutoCapture()
                    } else {
                    val resultJson = VisionApiClient.toJson(result)
                    Log.d(TAG, "★ resultJson长度=${resultJson.length}, webViewReady=$webViewReady")
                    // V2.9.180: 存储按钮坐标供全自动执行使用
                    if (result.buttonPositions.isNotEmpty()) {
                        latestButtonPositions = result.buttonPositions
                        Log.d(TAG, "★ 按钮坐标已存储: ${result.buttonPositions.map { "${it.text}(${it.xPct},${it.yPct})" }}")
                    }
                    // V2.9.207: 缓存场景数据——供后续本地CV快速通道使用
                    cachedPotSize = result.potSize
                    cachedToCall = result.toCall
                    cachedMinRaise = result.minRaise.toInt()
                    cachedBlindSB = result.blindSB
                    cachedBlindBB = result.blindBB
                    cachedTotalPlayers = result.totalPlayers
                    cachedActivePlayers = result.activePlayers
                    cachedMyPosition = result.myPosition
                    cachedPlayerChips = result.playerChips
                    Log.d(TAG, "★ 场景数据已缓存: pot=$cachedPotSize toCall=$cachedToCall")
                    // V2.9.207: 移除旧的手牌重置逻辑——handStartTime在executeAutoTap决策后重置，不再这里重置
                    // V2.9.206: 特殊状态处理——Insurance自动拒绝 / 搓牌等待
                    // R10-fix: verify帧(多帧第二拍)是辅助确认帧——FSM推进/物理点击/等待全部归primary主链路，
                    // 这里绝不能介入（否则verify截到保险弹窗会发"拒绝保险"物理点击、甚至与主决策双点）
                    val skipStrategyCalc: Boolean = when {
                        isMultiFrame2 -> {
                            Log.d(TAG, "★ R10: verify帧跳过特殊状态处理(Insurance/Squeeze)，仅投递JS做共识合并")
                            false
                        }
                        result.isInsurance && autoCaptureEnabled -> {
                            Log.d(TAG, "★ Insurance detected, auto-declining")
                            // P0-fix #5: Insurance路径补充FSM流转——STRATEGY_COMPUTING→EXECUTING→COOLDOWN→IDLE
                            // R7-fix(复核): 必须校验转换结果——过期保险帧（FSM已被硬超时/新帧带走）时转换非法，
                            // 绝不发物理点击（与autoDecision的R7守卫一致）
                            // R10-fix: 显式校验前置态——STRATEGY_READY仅在STRATEGY_COMPUTING合法，
                            // verify帧已在上方skipStrategyCalc提前分流，此处双保险防任何异常帧误点保险按钮
                            if (pipelineFSM.getCurrentState() != PipelineStateMachine.PipelineState.STRATEGY_COMPUTING) {
                                Log.w(TAG, "★ R7守卫: Insurance前置态非STRATEGY_COMPUTING(当前=${pipelineFSM.getCurrentState()})，跳过自动拒绝点击")
                            } else {
                            val insState = pipelineFSM.transition(PipelineStateMachine.PipelineEvent.STRATEGY_READY)  // →EXECUTING
                            if (insState != PipelineStateMachine.PipelineState.EXECUTING) {
                                Log.w(TAG, "★ R7守卫: Insurance过期帧(当前=$insState)，跳过自动拒绝点击")
                            } else {
                            // R4-fix: tap阻塞移后台线程，ACK回主线程驱动FSM（与自动点击同款）
                            Thread {
                                val (ix, iy) = GameModeConfig.getInsuranceDeclinePosition(screenWidth, screenHeight)
                                try { DiagnosticLogger.logEsp32Tap("insurance_decline", ix, iy, "insuranceBtn", "autoCapture") } catch (_: Exception) {}
                                val tapOk = bleManager?.sendTap(ix, iy, 50) ?: false
                                handler.post {
                                    try {
                                        // R4守卫：回主线程时若已不在EXECUTING（状态被超时/新帧带走），丢弃
                                        if (pipelineFSM.getCurrentState() != PipelineStateMachine.PipelineState.EXECUTING) {
                                            Log.w(TAG, "★ R4守卫: Insurance tap回调时FSM=${pipelineFSM.getCurrentState()}，丢弃")
                                            return@post
                                        }
                                        _pipelineEsp32TapTimeMs = System.currentTimeMillis() - _pipelineScreenshotTime
                                        _pipelineTotalTimeMs = _pipelineEsp32TapTimeMs
                                        _pipelineLastAction = "insurance_decline"
                                        try { DiagnosticLogger.updatePipelineTiming(_pipelineJsDecisionTimeMs, _pipelineEsp32TapTimeMs, _pipelineTotalTimeMs, _pipelineLastAction) } catch (_: Exception) {}
                                        if (tapOk) {
                                            cancelBleAckTimeout()
                                            pipelineFSM.transition(PipelineStateMachine.PipelineEvent.BLE_EXEC_OK)
                                            handler.postDelayed({ endCooldownAndScheduleNext() }, 1500)
                                        } else {
                                            pipelineFSM.transition(PipelineStateMachine.PipelineEvent.BLE_EXEC_FAIL)
                                            if (autoCaptureEnabled) scheduleNextAutoCapture()
                                        }
                                        updateAdviceNotification("Insurance", "已自动拒绝")
                                        updateBallAdvice("COLOR:CHECK|SIGNAL:INSURANCE|REASON:自动拒绝")
                                        Log.d(TAG, "★ Insurance declined at ($ix, $iy)")
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Insurance decline error", e)
                                    }
                                }
                            }.start()
                            }  // R7-fix: insState==EXECUTING 才点击
                            }  // R10-fix: 前置态==STRATEGY_COMPUTING 才允许转换
                            true
                        }
                        result.suitUncertain && autoCaptureEnabled -> {
                            Log.d(TAG, "★ Squeeze detected (suit_uncertain), waiting 3s")
                            updateAdviceNotification("搓牌中", "等待牌面完全揭示")
                            updateBallAdvice("COLOR:CHECK|SIGNAL:SQUEEZE|REASON:搓牌动画中")
                            handler.postDelayed({
                                if (autoCaptureEnabled && ScreenOptService.isServiceRunning()) {
                                    Log.d(TAG, "★ Squeeze wait done, re-capturing")
                                    processScreenshotAndAnalyze(isAutoCapture=true)
                                }
                            }, 3000)
                            true
                        }
                        else -> false
                    }
                        if (!skipStrategyCalc) {
                    val frameTag=when{isMultiFrame2->",_frameTag:'verify'";isMultiFrame1->",_frameTag:'primary'";isAutoCapture->",_frameTag:'auto'";else->""}
                    // V2.9.125: 策略超时保险——8秒超时+灰色等待（非红色FOLD）
                    // 7000+行JS首次加载+MC模拟2-3秒，5秒根本不够
                    // R10-fix: verify帧不建立策略生命周期——不重置_strategyReceived、不推进代次（会误杀
                    // primary在途autoDecision的R8比对）、不重挂8s定时器（primary的定时器/26s硬超时继续兜底）。
                    // verify帧JS端autoCaptureVisionComplete(true)直接return，本就不回调autoDecision。
                    if (!isMultiFrame2) {
                    _strategyReceived = false
                    // 先取消之前的超时定时器（防止重复截图时叠加）
                    _strategyTimeoutRunnable?.let { handler.removeCallbacks(it) }
                    // R8-fix: 本帧代次——超时定时器只认自己这一代；迟到的JS回调在代次推进后全部失效
                    _strategyGeneration++
                    val frameGen = _strategyGeneration
                    val timeoutRunnable = Runnable {
                        if (_strategyGeneration != frameGen) {
                            Log.d(TAG, "★ R8守卫: 策略超时定时器代次过期($frameGen!=$_strategyGeneration)，跳过")
                            return@Runnable
                        }
                        if (!_strategyReceived) {
                            Log.w(TAG, "★ 策略引擎超时8s，标记等待状态（非FOLD）")
                            // R8-fix: 超时即刻推进代次——8~10.5s恢复窗口内到达的迟到回调全部作废
                            _strategyGeneration++
                            val recoveryGen = _strategyGeneration
                            pipelineFSM.transition(PipelineStateMachine.PipelineEvent.STRATEGY_TIMEOUT)  // V3.50: Bug#6 策略超时→ERROR_RECOVERY
                            updateBallAdvice("COLOR:CHECK|SIGNAL:TIMEOUT|EQ:0|REASON:策略计算中")
                            updateAdviceNotification("⏳ 策略计算中", "8s未回调→等待而非FOLD")
                            // P0-fix #2: 策略超时后自动恢复——延迟2.5s后RESET回IDLE，自动模式调度下一轮
                            handler.postDelayed({
                                // R8-fix: 恢复前查代次——2.5s窗口内若已被新帧/硬超时/重初始化接管则放弃恢复
                                if (_strategyGeneration != recoveryGen) {
                                    Log.d(TAG, "★ R8守卫: 超时恢复代次过期，跳过RESET（已由新帧接管）")
                                    return@postDelayed
                                }
                                if (pipelineFSM.getCurrentState() == PipelineStateMachine.PipelineState.ERROR_RECOVERY) {
                                    pipelineFSM.transition(PipelineStateMachine.PipelineEvent.RESET)
                                    Log.d(TAG, "★ P0-fix#2: 策略超时恢复 RESET→IDLE")
                                }
                                if (autoCaptureEnabled) {
                                    scheduleNextAutoCapture()
                                }
                            }, 2500)
                        }
                    }
                    _strategyTimeoutRunnable = timeoutRunnable
                    handler.postDelayed(timeoutRunnable, 8000)
                    }  // R10-fix: !isMultiFrame2 策略生命周期结束（verify帧跳过）
                    handler.post {
                        val taggedJson=if(frameTag.isNotEmpty()) resultJson.dropLast(1)+frameTag+"}" else resultJson
                        // V3.3: 摊牌结果检测 — 记录赢/输到HudLearner (EV闭环)
                        try {
                            if (result.showdownCards.isNotEmpty()) {
                                val level = when {
                                    result.blindBB <= 10 -> "micro_nl2"
                                    result.blindBB <= 25 -> "low_nl10"
                                    else -> "mid_nl50"
                                }
                                val heroWon = result.showdownCards.none { it.won }
                                HudLearner.recordResult(heroWon, result.potSize, level)
                                Log.d(TAG, "★ 摊牌记录: hero${if(heroWon) "赢" else "输"} 底池${result.potSize} (亮牌${result.showdownCards.size}个对手)")
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "摊牌结果记录失败", e)
                        }
                        // V3.1: HudLearner桥接 — 把自积累记忆注入JS的applyHudData
                        try {
                            val level = when {
                                result.blindBB <= 10 -> "micro_nl2"
                                result.blindBB <= 25 -> "low_nl10"
                                else -> "mid_nl50"
                            }
                            val profile = HudLearner.getOpponentProfile(level)
                            if (profile.totalHandsObserved >= 200 && profile.type == "self") {
                                val hudJson = "[" + (1..6).joinToString(",") { s ->
                                    "{seat:$s,vpip:${profile.vpip},pfr:${profile.pfr},threeBet:${profile.threeBet},ats:${profile.ats}}"
                                } + "]"
                                executeJs("if(typeof PostValidation!=='undefined')PostValidation.applyHudData($hudJson);")
                                Log.d(TAG, "★ HudLearner桥接: ${profile.totalHandsObserved}手记忆注入策略引擎 (VPIP=${String.format("%.1f", profile.vpip*100)}%)")
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "HudLearner桥接失败", e)
                        }
                        // V2.9.113: 先检测WebView是否就绪，再调onVisionResult
                        executeJs("(function(){try{if(typeof onVisionResult==='function'){onVisionResult($taggedJson);if(typeof AndroidBridge!=='undefined'&&AndroidBridge.confirmVisionReceived){AndroidBridge.confirmVisionReceived()}}else{console.log('[V2.9.125] onVisionResult不存在,尝试重载HTML');if(typeof AndroidBridge!=='undefined'&&AndroidBridge.showAdvice){AndroidBridge.showAdvice('COLOR:FOLD|SIGNAL:ERROR|REASON:策略引擎未加载');}setTimeout(function(){location.reload();},1000);}}catch(e){console.log('[V2.9.125] onVisionResult异常:'+e.message);if(typeof AndroidBridge!=='undefined'&&AndroidBridge.showAdvice){AndroidBridge.showAdvice('COLOR:FOLD|SIGNAL:ERROR|REASON:JS异常:'+e.message.substring(0,30));}}})()")
                        tvAction?.alpha = 1.0f
                        Log.d(TAG, "★ onVisionResult已调用")
                        // V3.50: 结果已发给JS → 进入策略计算状态
                        // R10-fix(2026-08-30豪哥实测三手全部26s硬超时fold): V2.9.541本地CV为主后，
                        // 截屏成功走的是CAPTURING→RECOGNIZING_LOCAL，此处若发API_RECOG_OK：
                        //   RECOGNIZING_LOCAL+API_RECOG_OK 为非法转换（转换表只接受LOCAL_RECOG_OK），
                        //   FSM卡在RECOGNIZING_LOCAL → JS回autoDecision时STRATEGY_READY非法被R7守卫丢弃
                        //   → 决策到了也不点击、不取消26s硬超时 → 每手都被误fold。
                        // 修复：按当前FSM状态选择合法事件（RECOGNIZING_LOCAL→LOCAL_RECOG_OK / RECOGNIZING_API→API_RECOG_OK；
                        //       verify帧时FSM已在分析/执行态，两事件均非法→保持不变，符合verify不驱动主FSM设计）
                        val preStrategyState = pipelineFSM.getCurrentState()
                        when (preStrategyState) {
                            PipelineStateMachine.PipelineState.RECOGNIZING_LOCAL ->
                                pipelineFSM.transition(PipelineStateMachine.PipelineEvent.LOCAL_RECOG_OK)   // →STRATEGY_COMPUTING
                            PipelineStateMachine.PipelineState.RECOGNIZING_API ->
                                pipelineFSM.transition(PipelineStateMachine.PipelineEvent.API_RECOG_OK)     // →STRATEGY_COMPUTING
                            else ->
                                Log.d(TAG, "★ R10: 跳过→STRATEGY_COMPUTING转换(当前=$preStrategyState，verify帧/重复帧)")
                        }
                        autoConsecutiveErrors = 0
                        // V2.9.70: 正常识别→停止闪烁
                        isBlinkingError = false
                        // V2.9.569: 截屏间隔固定3.5秒，不再按street/人数动态调整
                        updateAdviceNotification("3/4 API识别OK", "策略计算中... WV:$webViewReady")
                        val hole = result.holeCards.map { (if(it.rank=="T") "10" else it.rank) + it.suit }.joinToString(" ")
                        tvStatus?.text = "✅ $hole | ${result.street} | ${result.totalPlayers}人"
                        val suitSym = mapOf("s" to "♠", "h" to "♥", "d" to "♦", "c" to "♣")
                        val streetCN = mapOf("preflop" to "翻前", "flop" to "翻牌", "turn" to "转牌", "river" to "河牌")
                        val holeStr = result.holeCards.map { "${if(it.rank=="T") "10" else it.rank}${suitSym[it.suit] ?: it.suit}" }.joinToString(" ")
                        val commStr = result.communityCards.map { "${if(it.rank=="T") "10" else it.rank}${suitSym[it.suit] ?: it.suit}" }.joinToString(" ")
                        val streetStr = streetCN[result.street] ?: result.street
                        var recText = "🔍 $holeStr | $streetStr | ${result.totalPlayers}人桌"
                        if (result.ante > 0) recText += " | Ante:${result.ante}"
                        var detailText = "BB=${result.blindBB}"
                        if (result.blindSB > 0) detailText += " SB=${result.blindSB}"
                        if (result.potSize > 0) detailText += " | 底池${result.potSize}"
                        if (result.toCall > 0) detailText += " | 跟注${result.toCall}"
                        val apiError = VisionApiClient.lastError
                        if (apiError.isNotEmpty()) {
                            recText += " ⚠️$apiError"
                            tvRecResult?.setBackgroundColor(0xFF8B0000.toInt())
                        } else {
                            tvRecResult?.setBackgroundColor(0xFF1A237E.toInt())
                        }
                        tvRecResult?.text = recText
                        tvRecResult?.visibility = View.VISIBLE
                        tvRecDetail?.text = detailText
                        tvRecDetail?.visibility = View.VISIBLE
                        // V2.9.114: 通知栏增加诊断信息——webViewReady+手牌数+策略回调状态
                        val notifyDetail = buildString {
                            append("手牌:$holeStr")
                            if (commStr.isNotEmpty()) append(" 公牌:$commStr")
                            append(" | WV:$webViewReady")
                            append(" | 牌数:${result.holeCards.size}")
                            append(" | isPoker:${result.isPokerTable}")
                        }
                        updateAdviceNotification("✅ $holeStr $streetStr ${result.totalPlayers}人", notifyDetail)
                    }
                        } // end of skipStrategyCalc check
                    } // V2.9.111: end of NO_TABLE else (正常牌桌才执行策略)
                } else {
                    handler.post {
                        // V3.50: API失败→进入错误恢复
                        pipelineFSM.transition(PipelineStateMachine.PipelineEvent.API_RECOG_FAIL)  // RECOGNIZING_API→ERROR_RECOVERY
                        // V3.44: API失败→自动重试防卡死（与lxpk对齐）
                        autoConsecutiveErrors++
                        checkAutoErrors()
                        scheduleNextAutoCapture()
                        tvAction?.alpha = 1.0f
                        tvStatus?.text = "❌ API: ${VisionApiClient.lastError.take(30)}"
                        executeJs("document.body.classList.remove('api-processing')")
                        updateBallAdvice("COLOR:FOLD|SIGNAL:COUNTER")
                        isBlinkingError = true
                        floatingBall?.text="⚠️";floatingBall?.textSize=14f
                        // V2.9.193: 错误日志包含API原始响应前200字符——直接定位根因
                        val rawResp = VisionApiClient.lastRawResponse.take(200)
                        addErrorLog("${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())} API失败: ${VisionApiClient.lastError.take(100)} | raw: $rawResp")
                        Log.e(TAG, "★ API失败, error=${VisionApiClient.lastError}, raw=${VisionApiClient.lastRawResponse.take(300)}")
                        updateAdviceNotification("❌ 3/4 API失败", VisionApiClient.lastError.take(40))
                    }
                }
            } catch (e: Exception) {
                handler.post {
                    // V3.50: 异常→进入错误恢复
                    pipelineFSM.transition(PipelineStateMachine.PipelineEvent.API_RECOG_FAIL)  // 任意识别状态→ERROR_RECOVERY
                    tvAction?.alpha = 1.0f
                    tvStatus?.text = "❌ API错误"
                    executeJs("document.body.classList.remove('api-processing')")
                    updateBallAdvice("COLOR:FOLD|SIGNAL:COUNTER")
                    isBlinkingError = true
                    floatingBall?.text="⚠️";floatingBall?.textSize=14f
                    addErrorLog("${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())} API异常: ${e.message?.take(100) ?: "未知"}")
                    updateAdviceNotification("API错误", e.message?.take(50) ?: "")
                    // V2.9.553-rev9-fix-v3: 异常分支也必须RESET+续调度，否则FSM留ERROR_RECOVERY/识别态→截图循环停止
                    autoConsecutiveErrors++; checkAutoErrors()
                    pipelineFSM.transition(PipelineStateMachine.PipelineEvent.RESET)
                    if (autoCaptureEnabled) scheduleNextAutoCapture()
                }
            }
        }.start()
    }

    private fun toggleExpand() {
        isExpanded = !isExpanded
        webView?.visibility = if (isExpanded) View.VISIBLE else View.GONE
        resizeHandleLeft?.visibility = if (isExpanded && getScreenSize().first > getScreenSize().second) View.VISIBLE else View.GONE
        resizeHandleBottom?.visibility = if (isExpanded && getScreenSize().first > getScreenSize().second) View.VISIBLE else View.GONE

        val (screenWidth, screenHeight) = getScreenSize()
        val isLandscape = screenWidth > screenHeight

        val params = floatingView?.layoutParams as? WindowManager.LayoutParams ?: return
        applyWindowSize(params, screenWidth, screenHeight, isLandscape)
        windowManager?.updateViewLayout(floatingView, params)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "截屏优化", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "点击通知截屏识别"
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        // V2.9.39: 点击通知直接截屏 + 快捷按钮
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val builder = Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("点击截屏识别")
                .setContentText("点一下即可截屏分析")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setStyle(Notification.BigTextStyle()
                    .setBigContentTitle("点击即可截屏")
                    .bigText("点击通知直接截屏分析，也可用通知栏顶部「截屏优化」瓷砖"))

            // ★ 点击通知本身 → 触发截屏（最直观的操作方式）
            val captureIntent = Intent(ACTION_CAPTURE)
            captureIntent.setPackage(packageName)
            val capturePending = PendingIntent.getBroadcast(this, 0, captureIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.setContentIntent(capturePending)

            val openIntent = Intent(ACTION_OPEN)
            val openPending = PendingIntent.getBroadcast(this, 3, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(android.R.drawable.ic_menu_view, "打开App", openPending)

            val exportIntent = Intent(ACTION_EXPORT)
            exportIntent.setPackage(packageName)
            val exportPending = PendingIntent.getBroadcast(this, 4, exportIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(android.R.drawable.ic_menu_save, "导出", exportPending)

            builder.build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("点击截屏识别")
                .setContentText("点一下即可截屏分析")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_HIGH)
                .build()
        }
    }

    // V2.9.113: 纯Kotlin端导出日志，不依赖WebView
    // V2.9.167: 增强版诊断日志导出
    private fun exportLogFromNotification() {
        try {
            // V2.9.215: 导出完整日志（识别+决策+错误）+ 复盘日志
            val logData = DiagnosticLogger.exportAsJson()
            val reviewData = DiagnosticLogger.exportReview()
            // V2.9.558: 附加error_logs.txt到JSON导出（排查连续错误根因）
            val enhancedLogData = try {
                val jsonObj = org.json.JSONObject(logData)
                val errorLogFile = File(filesDir, ERROR_LOG_FILE)
                if (errorLogFile.exists()) {
                    val errorLines = errorLogFile.readLines().takeLast(200)
                    jsonObj.put("errorLogEntries", org.json.JSONArray(errorLines.toList()))
                    jsonObj.put("errorLogCount", errorLines.size)
                } else {
                    jsonObj.put("errorLogEntries", org.json.JSONArray())
                    jsonObj.put("errorLogCount", 0)
                }
                // V2.9.559: 附加poker_log.txt中的JS console日志（WebChromeClient.onConsoleMessage捕获，
                //   含JS异常原文+行号——排查0决策根因：JS静默失败路径只console.log不走error_logs.txt）
                //   文件: DiagnosticLogger.getLogFile() = getExternalFilesDir(null)/poker_log.txt
                //   JS行固定格式 "[HH:mm:ss] [JS_xxx] [来源:行号] 消息"，用 "] [JS" 精确过滤
                //   （同文件识别日志为JSON行、ERROR为{type:ERROR...}行，均不含此前缀）
                try {
                    val jsLogFile = File(getExternalFilesDir(null) ?: filesDir, "poker_log.txt")
                    if (jsLogFile.exists()) {
                        val jsLines = jsLogFile.readLines().takeLast(500)
                            .filter { it.contains("] [JS") }
                        jsonObj.put("jsConsoleEntries", org.json.JSONArray(jsLines))
                        jsonObj.put("jsConsoleCount", jsLines.size)
                    } else {
                        jsonObj.put("jsConsoleEntries", org.json.JSONArray())
                        jsonObj.put("jsConsoleCount", 0)
                    }
                } catch (je: Exception) {
                    jsonObj.put("jsConsoleEntries", org.json.JSONArray())
                    jsonObj.put("jsConsoleCount", 0)
                }
                jsonObj.toString(2)
            } catch (e: Exception) {
                logData  // fallback: enhancement失败不影响正常导出
            }
            val downloadDir = getExternalFilesDir(null) ?: filesDir
            val fileName = "poker_log_${java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())}.json"
            val exportFile = File(downloadDir, fileName)
            exportFile.writeText(enhancedLogData, Charsets.UTF_8)
            
            // V2.9.215: 同时导出复盘日志
            try {
                val reviewFile = File(downloadDir, "poker_review_${java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())}.json")
                reviewFile.writeText(reviewData, Charsets.UTF_8)
            } catch (_: Exception) {}

            // 复制到剪贴板
            try {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("poker_log", enhancedLogData))
            } catch (_: Exception) {}

            // 弹出分享
            val fileUri = androidx.core.content.FileProvider.getUriForFile(
                this@FloatingService,
                "${packageName}.fileprovider",
                exportFile
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, fileUri)
                putExtra(Intent.EXTRA_SUBJECT, "青云扑克日志")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(Intent.createChooser(shareIntent, "分享日志(已复制到剪贴板)").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            updateAdviceNotification("✅ 日志已导出", "保存到Download/${fileName}")
        } catch (e: Exception) {
            updateAdviceNotification("❌ 导出失败", e.message?.take(30) ?: "")
        }
    }

    /**
     * V2.9.38: 更新通知栏显示建议内容
     */
    fun updateAdviceNotification(title: String, detail: String) {
        try {
            val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val builder = Notification.Builder(this, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_menu_compass)
                    .setOngoing(true)
                    .setCategory(Notification.CATEGORY_SERVICE)
                    .setVisibility(Notification.VISIBILITY_PUBLIC)

                builder.setContentTitle(title)
                    .setContentText(detail.ifEmpty { "点击截屏识别" })

                // ★ 点击通知 → 触发截屏
                val captureIntent = Intent(ACTION_CAPTURE)
                captureIntent.setPackage(packageName)
                val capturePending = PendingIntent.getBroadcast(this, 0, captureIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                builder.setContentIntent(capturePending)

                val openIntent = Intent(ACTION_OPEN)
                val openPending = PendingIntent.getBroadcast(this, 3, openIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                builder.addAction(android.R.drawable.ic_menu_view, "打开App", openPending)

            val exportIntent = Intent(ACTION_EXPORT)
            exportIntent.setPackage(packageName)
            val exportPending = PendingIntent.getBroadcast(this, 4, exportIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(android.R.drawable.ic_menu_save, "导出", exportPending)

                builder.setStyle(Notification.BigTextStyle()
                    .setBigContentTitle(title)
                    .bigText(detail.ifEmpty { "点击截屏识别牌面" }))

                builder.build()
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
                    .setContentTitle(title)
                    .setContentText(detail)
                    .setSmallIcon(android.R.drawable.ic_menu_compass)
                    .setOngoing(true)
                    .setPriority(Notification.PRIORITY_HIGH)
                    .build()
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {}
    }
}