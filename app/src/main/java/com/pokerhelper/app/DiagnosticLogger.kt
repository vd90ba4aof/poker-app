package com.pokerhelper.app

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.*

/**
 * 诊断日志记录器 V2.9.215
 * - 记录每次识别的完整信息
 * - 记录每次策略决策（供复盘学习）
 * - 错误分类与严重级别
 * - 复盘导出：按手牌维度组织完整决策链
 */
object DiagnosticLogger {
    
    private const val TAG = "DiagnosticLogger"
    private var appContext: Context? = null
    
    /** 初始化，必须在首次使用前调用（FloatingService.onCreate 中调用） */
    fun init(context: Context) {
        appContext = context.applicationContext
    }
    
    // ===== 错误分类 V2.9.215 =====
    enum class ErrorCategory(val label: String) {
        RECOGNITION("识别错误"),      // 图像识别失败/不准
        STRATEGY("策略错误"),         // JS策略引擎异常
        COMMUNICATION("通信错误"),    // WebView bridge 失败
        AUTO_EXEC("执行错误"),        // 自动点击/tap 失败
        TIMEOUT("超时错误"),          // Shot Clock / API超时
        NETWORK("网络错误"),          // 网络连接/API调用失败
        UNKNOWN("未知错误")
    }
    
    // ===== 严重级别 =====
    enum class Severity(val level: Int) {
        LOW(0),       // 不影响使用，信息性
        MEDIUM(1),    // 功能降级，有兜底
        HIGH(2),      // 功能受损，需要关注
        CRITICAL(3)   // 完全不可用
    }
    
    // ===== 决策日志 V2.9.215 =====
    data class DecisionLog(
        val timestamp: Long,
        val timeStr: String,
        // 牌局状态
        val street: String,
        val holeCards: String,       // "Ah,Kd"
        val communityCards: String,  // "Ts,9h,2c" 或空
        val potSize: Int,
        val myChips: Int,
        val toCall: Int,
        val totalPlayers: Int,
        val activePlayers: Int,
        val position: String,
        // 策略结果
        val action: String,          // fold/call/raise/check/allin
        val sizing: Int,             // 下注量
        val eq: Int,                 // 胜率
        val confidence: String,      // high/medium/low
        val reason: String,          // 决策理由
        val hClass: String,          // 手牌分类 NUTS/STRONG/TOP_PAIR/...
        val isAuto: Boolean,         // 是否自动执行
        val autoExecResult: String,  // 自动执行结果 success/fail/skip
        val oppStats: String = ""   // V2.9.220: 对手统计摘要
    )
    
    // ===== 错误日志条目 V2.9.215 =====
    data class ErrorEntry(
        val timestamp: Long,
        val timeStr: String,
        val category: ErrorCategory,
        val severity: Severity,
        val message: String,
        val detail: String? = null
    )
    
    private const val MAX_LOGS = 100          // 最多保留100次识别记录
    private const val MAX_DECISIONS = 200     // 最多保留200次决策记录
    private const val MAX_ERRORS = 200        // 最多保留200条错误
    
    private val recognitionLogs = mutableListOf<RecognitionLog>()
    private val decisionLogs = mutableListOf<DecisionLog>()
    private val errorEntries = mutableListOf<ErrorEntry>()
    
    // 手牌复盘追踪 V2.9.215
    private val currentHandDecisions = mutableListOf<DecisionLog>()
    private var currentHandId: String = ""
    
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val logFileLock = Any()
    private val decisionFileLock = Any()
    
    // ===== 文件路径 =====
    private fun getLogFile(): File {
        val dir = appContext?.getExternalFilesDir(null) ?: File("/data/local/tmp")
        return File(dir, "poker_log.txt")
    }
    
    private fun getDecisionLogFile(): File {
        val dir = appContext?.getExternalFilesDir(null) ?: File("/data/local/tmp")
        return File(dir, "poker_decisions.txt")
    }
    
    private fun getReviewFile(): File {
        val dir = appContext?.getExternalFilesDir(null) ?: File("/data/local/tmp")
        return File(dir, "poker_review.txt")
    }
    
    // ===== 识别日志持久化 =====
    private fun autoFlushToFile(log: RecognitionLog) {
        try {
            synchronized(logFileLock) {
                val file = getLogFile()
                val entry = logToJson(log).toString(2) + ",\n"
                file.appendText(entry, Charsets.UTF_8)
                // 滚动保留最近5MB
                if (file.length() > 5 * 1024 * 1024) {
                    val content = file.readText(Charsets.UTF_8)
                    val keepLength = content.length / 2
                    file.writeText(content.substring(content.length - keepLength), Charsets.UTF_8)
                }
            }
        } catch (_: Exception) {}
    }
    
    // ===== V2.9.215: 决策日志持久化 =====
    private fun autoFlushDecisionToFile(log: DecisionLog) {
        try {
            synchronized(decisionFileLock) {
                val file = getDecisionLogFile()
                val entry = decisionToJson(log).toString(2) + ",\n"
                file.appendText(entry, Charsets.UTF_8)
                // 滚动保留最近3MB
                if (file.length() > 3 * 1024 * 1024) {
                    val content = file.readText(Charsets.UTF_8)
                    val keepLength = content.length / 2
                    file.writeText(content.substring(content.length - keepLength), Charsets.UTF_8)
                }
            }
        } catch (_: Exception) {}
    }
    
    /**
     * V2.9.215: 记录一次策略决策——核心"学习"数据
     */
    fun logDecision(
        street: String,
        holeCards: String,
        communityCards: String,
        potSize: Int,
        myChips: Int,
        toCall: Int,
        totalPlayers: Int,
        activePlayers: Int,
        position: String,
        action: String,
        sizing: Int,
        eq: Int,
        confidence: String,
        reason: String,
        hClass: String,
        isAuto: Boolean,
        autoExecResult: String,
        oppStats: String = ""
    ) {
        val now = System.currentTimeMillis()
        val timeStr = timeFormat.format(Date(now))
        
        val log = DecisionLog(
            timestamp = now,
            timeStr = timeStr,
            street = street,
            holeCards = holeCards,
            communityCards = communityCards,
            potSize = potSize,
            myChips = myChips,
            toCall = toCall,
            totalPlayers = totalPlayers,
            activePlayers = activePlayers,
            position = position,
            action = action,
            sizing = sizing,
            eq = eq,
            confidence = confidence,
            reason = reason,
            hClass = hClass,
            isAuto = isAuto,
            autoExecResult = autoExecResult,
            oppStats = oppStats
        )
        
        synchronized(decisionLogs) {
            decisionLogs.add(log)
            if (decisionLogs.size > MAX_DECISIONS) {
                decisionLogs.removeAt(0)
            }
        }
        
        // 追踪当前手牌的决策链
        // 检测新牌局：手牌变化或street回到preflop
        val handKey = holeCards + "_" + (if (street == "preflop") now.toString() else currentHandId)
        if (currentHandId.isEmpty() || (street == "preflop" && currentHandDecisions.any { it.holeCards != holeCards })) {
            currentHandId = holeCards + "_" + now
            currentHandDecisions.clear()
        }
        currentHandDecisions.add(log)
        
        // 持久化
        autoFlushDecisionToFile(log)
    }
    
    /**
     * V2.9.215: 记录分类错误
     */
    fun logError(category: ErrorCategory, severity: Severity, message: String, detail: String? = null) {
        val now = System.currentTimeMillis()
        val timeStr = timeFormat.format(Date(now))
        val entry = ErrorEntry(now, timeStr, category, severity, message, detail)
        
        synchronized(errorEntries) {
            errorEntries.add(entry)
            if (errorEntries.size > MAX_ERRORS) {
                errorEntries.removeAt(0)
            }
        }
        
        // 同时写入文件日志
        try {
            synchronized(logFileLock) {
                val file = getLogFile()
                val json = JSONObject().apply {
                    put("type", "ERROR")
                    put("time", timeStr)
                    put("timestamp", now)
                    put("category", category.label)
                    put("severity", severity.name)
                    put("message", message)
                    if (detail != null) put("detail", detail)
                }
                file.appendText(json.toString() + ",\n", Charsets.UTF_8)
            }
        } catch (_: Exception) {}
    }
    
    /**
     * V2.9.182: 接收JS端console.log日志
     */
    fun logJsConsole(message: String) {
        try {
            synchronized(logFileLock) {
                val file = getLogFile()
                val timeStr = timeFormat.format(Date())
                // 智能分类JS日志
                val prefix = when {
                    message.contains("error", ignoreCase = true) || message.contains("err:", ignoreCase = true) -> "JS_ERR"
                    message.contains("warn", ignoreCase = true) -> "JS_WARN"
                    message.contains("策略") || message.contains("Strategy") -> "JS_STRATEGY"
                    message.contains("识别") || message.contains("recognize") -> "JS_RECOG"
                    else -> "JS"
                }
                file.appendText("[$timeStr] [$prefix] $message\n", Charsets.UTF_8)
            }
        } catch (_: Exception) {}
    }
    
    /**
     * V2.9.182: 崩溃时紧急写入日志
     */
    fun flushCrashLog(throwable: Throwable) {
        try {
            synchronized(logFileLock) {
                val file = getLogFile()
                val timeStr = dateFormat.format(Date())
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                file.appendText("\n=== CRASH $timeStr ===\n${sw.toString()}\n", Charsets.UTF_8)
                
                // 同步写入崩溃前的识别日志和决策日志
                synchronized(recognitionLogs) {
                    val recent = recognitionLogs.takeLast(5)
                    file.appendText("=== 崩溃前最后5条识别日志 ===\n", Charsets.UTF_8)
                    for (log in recent) {
                        file.appendText(logToJson(log).toString(2) + ",\n", Charsets.UTF_8)
                    }
                }
                synchronized(decisionLogs) {
                    val recent = decisionLogs.takeLast(10)
                    file.appendText("=== 崩溃前最后10条决策日志 ===\n", Charsets.UTF_8)
                    for (log in recent) {
                        file.appendText(decisionToJson(log).toString(2) + ",\n", Charsets.UTF_8)
                    }
                }
            }
        } catch (_: Exception) {}
    }
    
    /**
     * 单次识别记录的完整数据
     */
    data class RecognitionLog(
        val timestamp: Long,
        val timeStr: String,
        
        // 本地CV识别结果
        val localCVEnabled: Boolean,
        val localCVTimeMs: Long,
        val localHandCards: String,
        val localCommunityCards: String,
        val localStreet: String?,
        
        // 本地CV锁定的信息
        val streetLocked: String?,
        val holeCardsLocked: Boolean,
        
        // VisionAPI识别结果
        val vlmTimeMs: Long,
        val vlmHandCards: String,
        val vlmCommunityCards: String,
        val vlmStreet: String,
        val vlmPot: Int,
        val vlmMyChips: Int,
        val vlmBetToCall: Int,
        val vlmButtons: List<String>,
        val vlmDButtonPos: String,
        val vlmTotalPlayers: Int,
        val vlmActivePlayers: Int,
        val vlmBlinds: String,
        
        // 最终结果（经过纠正后）
        val finalStreet: String,
        val finalHandCards: String,
        val finalCommunityCards: String,
        
        // 筹码追踪
        val chipDelta: Long?,
        val chipStatus: String,
        val potDelta: Int,
        
        // 性能指标
        val totalTimeMs: Long,
        
        // 错误信息 V2.9.215: 增加分类
        val hasError: Boolean,
        val errorMessage: String?,
        val errorCategory: ErrorCategory = ErrorCategory.UNKNOWN,
        
        // 是否成功发送策略引擎
        val strategySent: Boolean,
        
        // V2.9.193: API原始响应
        val rawResponse: String?
    )
    
    /**
     * 记录一次识别的完整信息
     */

    // V2.9.503: ESP32 BLE点击执行记录
    data class Esp32TapLog(
        val timestamp: Long,
        val timeStr: String,
        val action: String,
        val x: Int,
        val y: Int,
        val buttonLabel: String,
        val method: String,
        val bleConnected: Boolean
    )
    
    private val esp32TapLogs = mutableListOf<Esp32TapLog>()
    private const val MAX_ESP32_TAPS = 100
    
    fun logEsp32Tap(action: String, x: Int, y: Int, buttonLabel: String, method: String) {
        val now = System.currentTimeMillis()
        val timeStr = timeFormat.format(Date(now))
        
        val tapLog = Esp32TapLog(
            timestamp = now,
            timeStr = timeStr,
            action = action,
            x = x,
            y = y,
            buttonLabel = buttonLabel,
            method = method,
            bleConnected = bleManagerConnected
        )
        
        synchronized(esp32TapLogs) {
            esp32TapLogs.add(tapLog)
            if (esp32TapLogs.size > MAX_ESP32_TAPS) {
                esp32TapLogs.removeAt(0)
            }
        }
        
        // 同时写入文件日志
        try {
            synchronized(logFileLock) {
                val file = getLogFile()
                val json = JSONObject().apply {
                    put("type", "ESP32_TAP")
                    put("time", timeStr)
                    put("timestamp", now)
                    put("action", action)
                    put("x", x)
                    put("y", y)
                    put("button", buttonLabel)
                    put("method", method)
                    put("bleConnected", bleManagerConnected)
                }
                file.appendText(json.toString() + "\n", Charsets.UTF_8)
            }
        } catch (_: Exception) {}
        
        Log.i(TAG, "📟 ESP32点击: $action → ($x,$y) btn=$buttonLabel method=$method ble=$bleManagerConnected")
    }
    
    @Volatile
    private var bleManagerConnected = false
    
    // V2.9.503: Pipeline耗时追踪字段
    private var pipelineJsDecisionTimeMs = 0L
    private var pipelineEsp32TapTimeMs = 0L
    private var pipelineTotalTimeMs = 0L
    private var pipelineLastAction = ""
    
    fun setBleConnected(connected: Boolean) {
        bleManagerConnected = connected
    }
    
    // V2.9.503: Pipeline耗时更新
    fun updatePipelineTiming(jsMs: Long, esp32Ms: Long, totalMs: Long, action: String) {
        pipelineJsDecisionTimeMs = jsMs
        pipelineEsp32TapTimeMs = esp32Ms
        pipelineTotalTimeMs = totalMs
        pipelineLastAction = action
    }
    
    fun logRecognition(
        localCVEnabled: Boolean,
        localCVTimeMs: Long,
        localHandCards: List<VisionApiClient.CardInfo>,
        localCommunityCards: List<VisionApiClient.CardInfo>,
        localStreet: String?,
        streetLocked: String?,
        holeCardsLocked: Boolean,
        vlmTimeMs: Long,
        vlmResult: VisionApiClient.VisionResult?,
        totalTimeMs: Long,
        hasError: Boolean,
        errorMessage: String?,
        strategySent: Boolean,
        rawResponse: String? = null,
        errorCategory: ErrorCategory = ErrorCategory.UNKNOWN
    ) {
        val now = System.currentTimeMillis()
        val timeStr = timeFormat.format(Date(now))
        
        val chipDelta = vlmResult?.let { calcChipDelta(it.playerChips) }
        val chipStatus = determineChipStatus(chipDelta)
        val potDelta = vlmResult?.let { calcPotDelta(it.potSize) } ?: 0
        
        // V2.9.215: 自动推断错误分类
        val cat = if (hasError) {
            when {
                errorMessage?.contains("timeout", ignoreCase = true) == true ||
                errorMessage?.contains("超时") == true -> ErrorCategory.TIMEOUT
                errorMessage?.contains("network", ignoreCase = true) == true ||
                errorMessage?.contains("connect", ignoreCase = true) == true ||
                errorMessage?.contains("网络") == true -> ErrorCategory.NETWORK
                errorMessage?.contains("parse", ignoreCase = true) == true ||
                errorMessage?.contains("识别") == true -> ErrorCategory.RECOGNITION
                else -> errorCategory
            }
        } else {
            ErrorCategory.UNKNOWN
        }
        
        val log = RecognitionLog(
            timestamp = now,
            timeStr = timeStr,
            localCVEnabled = localCVEnabled,
            localCVTimeMs = localCVTimeMs,
            localHandCards = localHandCards.joinToString(",") { "${it.rank}${it.suit}" },
            localCommunityCards = localCommunityCards.joinToString(",") { "${it.rank}${it.suit}" },
            localStreet = localStreet,
            streetLocked = streetLocked,
            holeCardsLocked = holeCardsLocked,
            vlmTimeMs = vlmTimeMs,
            vlmHandCards = vlmResult?.holeCards?.joinToString(",") { "${it.rank}${it.suit}" } ?: "",
            vlmCommunityCards = vlmResult?.communityCards?.joinToString(",") { "${it.rank}${it.suit}" } ?: "",
            vlmStreet = vlmResult?.street ?: "",
            vlmPot = vlmResult?.potSize ?: 0,
            vlmMyChips = vlmResult?.playerChips ?: 0,
            vlmBetToCall = vlmResult?.toCall ?: 0,
            vlmButtons = vlmResult?.buttons ?: emptyList(),
            vlmDButtonPos = vlmResult?.dButtonPosition ?: "",
            vlmTotalPlayers = vlmResult?.totalPlayers ?: 0,
            vlmActivePlayers = vlmResult?.activePlayers ?: 0,
            vlmBlinds = if (vlmResult != null) "${vlmResult.blindSB}/${vlmResult.blindBB}" else "",
            finalStreet = vlmResult?.street ?: "",
            finalHandCards = vlmResult?.holeCards?.joinToString(",") { "${it.rank}${it.suit}" } ?: "",
            finalCommunityCards = vlmResult?.communityCards?.joinToString(",") { "${it.rank}${it.suit}" } ?: "",
            chipDelta = chipDelta,
            chipStatus = chipStatus,
            potDelta = potDelta,
            totalTimeMs = totalTimeMs,
            hasError = hasError,
            errorMessage = errorMessage,
            errorCategory = cat,
            strategySent = strategySent,
            rawResponse = rawResponse
        )
        
        synchronized(recognitionLogs) {
            recognitionLogs.add(log)
            if (recognitionLogs.size > MAX_LOGS) {
                recognitionLogs.removeAt(0)
            }
        }
        
        autoFlushToFile(log)
        
        // V2.9.215: 如果有错误，同步记录到错误日志
        if (hasError) {
            logError(cat, Severity.HIGH, errorMessage ?: "unknown", "totalTimeMs=$totalTimeMs")
        }
    }
    
    private var lastChips = 0
    private var lastPot = 0
    
    private fun calcChipDelta(currentChips: Int): Long {
        val delta = if (lastChips > 0) (currentChips - lastChips).toLong() else null
        lastChips = currentChips
        return delta ?: 0L
    }
    
    private fun calcPotDelta(currentPot: Int): Int {
        val delta = if (lastPot > 0) currentPot - lastPot else 0
        lastPot = currentPot
        return delta
    }
    
    private fun determineChipStatus(delta: Long?): String {
        return when {
            delta == null -> "unknown"
            delta == 0L -> "active"
            delta < -100 -> "betting"
            delta > 100 -> "won"
            else -> "active"
        }
    }
    
    fun resetChipTracking() {
        lastChips = 0
        lastPot = 0
    }
    
    // ===== 导出功能 V2.9.215 =====
    
    /**
     * 导出所有诊断日志为JSON格式（识别+决策+错误）
     */
    fun exportAsJson(): String {
        val json = JSONObject()
        json.put("exportTime", dateFormat.format(Date()))
        json.put("version", com.pokerhelper.app.BuildConfig.VERSION_NAME)
        json.put("totalLogs", recognitionLogs.size)
        json.put("totalDecisions", decisionLogs.size)
        json.put("totalErrors", errorEntries.size)
        
        // 识别日志
        val logsArray = JSONArray()
        synchronized(recognitionLogs) {
            for (log in recognitionLogs) {
                logsArray.put(logToJson(log))
            }
        }
        json.put("recognitions", logsArray)
        
        // 决策日志 V2.9.215
        val decisionsArray = JSONArray()
        synchronized(decisionLogs) {
            for (log in decisionLogs) {
                decisionsArray.put(decisionToJson(log))
            }
        }
        json.put("decisions", decisionsArray)
        
        // 错误日志 V2.9.215
        val errorsArray = JSONArray()
        synchronized(errorEntries) {
            for (entry in errorEntries) {
                errorsArray.put(errorToJson(entry))
            }
        }
        json.put("errors", errorsArray)
        
        // ESP32点击日志 V2.9.503
        val tapsArray = JSONArray()
        synchronized(esp32TapLogs) {
            for (tap in esp32TapLogs) {
                tapsArray.put(JSONObject().apply {
                    put("time", tap.timeStr)
                    put("timestamp", tap.timestamp)
                    put("action", tap.action)
                    put("x", tap.x)
                    put("y", tap.y)
                    put("button", tap.buttonLabel)
                    put("method", tap.method)
                    put("bleConnected", tap.bleConnected)
                })
            }
        }
        json.put("esp32Taps", tapsArray)
        
        // 统计信息
        json.put("stats", generateStats())
        
        // V2.9.503: Pipeline耗时
        json.put("pipelineTiming", JSONObject().apply {
            put("jsDecisionTimeMs", pipelineJsDecisionTimeMs)
            put("esp32TapTimeMs", pipelineEsp32TapTimeMs)
            put("totalTimeMs", pipelineTotalTimeMs)
            put("lastAction", pipelineLastAction)
        })
        
        return json.toString(2)
    }
    
    /**
     * V2.9.215: 复盘导出——按手牌维度组织完整决策链
     * 输出格式：每手牌一个完整的timeline
     */
    fun exportReview(): String {
        val json = JSONObject()
        json.put("exportTime", dateFormat.format(Date()))
        json.put("version", com.pokerhelper.app.BuildConfig.VERSION_NAME)
        
        // 按手牌分组决策
        val hands = JSONArray()
        synchronized(decisionLogs) {
            var currentHand: JSONObject? = null
            var lastCards = ""
            var actions = JSONArray()
            
            for (log in decisionLogs) {
                // 新牌局判断
                if (log.holeCards != lastCards || (log.street == "preflop" && actions.length() > 0)) {
                    // 保存上一手
                    if (currentHand != null) {
                        currentHand.put("actions", actions)
                        hands.put(currentHand)
                    }
                    currentHand = JSONObject().apply {
                        put("startTime", timeFormat.format(Date(log.timestamp)))
                        put("holeCards", log.holeCards)
                        put("totalPlayers", log.totalPlayers)
                        put("position", log.position)
                    }
                    actions = JSONArray()
                    lastCards = log.holeCards
                }
                
                // 添加决策动作
                actions.put(JSONObject().apply {
                    put("time", log.timeStr)
                    put("street", log.street)
                    put("communityCards", log.communityCards)
                    put("pot", log.potSize)
                    put("toCall", log.toCall)
                    put("activePlayers", log.activePlayers)
                    put("action", log.action)
                    put("sizing", log.sizing)
                    put("eq", log.eq)
                    put("confidence", log.confidence)
                    put("hClass", log.hClass)
                    put("reason", log.reason)
                    put("auto", log.isAuto)
                    put("execResult", log.autoExecResult)
                })
            }
            
            // 最后一手
            if (currentHand != null) {
                currentHand.put("actions", actions)
                hands.put(currentHand)
            }
        }
        
        json.put("hands", hands)
        json.put("totalHands", hands.length())
        
        // 复盘统计
        json.put("reviewStats", generateReviewStats())
        
        return json.toString(2)
    }
    
    private fun generateReviewStats(): JSONObject {
        return JSONObject().apply {
            synchronized(decisionLogs) {
                put("totalDecisions", decisionLogs.size)
                put("autoExecuted", decisionLogs.count { it.isAuto && it.autoExecResult == "success" })
                put("manualConfirm", decisionLogs.count { !it.isAuto })
                put("lowConfFold", decisionLogs.count { it.confidence == "low" })
                
                // 按 action 统计
                val actionCounts = JSONObject()
                val actionGroups = decisionLogs.groupBy { it.action }
                for ((action, logs) in actionGroups) {
                    actionCounts.put(action, logs.size)
                }
                put("actionDistribution", actionCounts)
                
                // 按 street 统计
                val streetCounts = JSONObject()
                val streetGroups = decisionLogs.groupBy { it.street }
                for ((street, logs) in streetGroups) {
                    streetCounts.put(street, logs.size)
                }
                put("streetDistribution", streetCounts)
                
                // 按 confidence 统计
                val confCounts = JSONObject()
                val confGroups = decisionLogs.groupBy { it.confidence }
                for ((conf, logs) in confGroups) {
                    confCounts.put(conf, logs.size)
                }
                put("confidenceDistribution", confCounts)
                
                // 平均胜率
                if (decisionLogs.isNotEmpty()) {
                    val avgEq = decisionLogs.map { it.eq }.average()
                    put("avgEquity", String.format("%.1f%%", avgEq))
                }
            }
            
            // 错误统计
            synchronized(errorEntries) {
                val catCounts = JSONObject()
                val catGroups = errorEntries.groupBy { it.category }
                for ((cat, entries) in catGroups) {
                    catCounts.put(cat.label, entries.size)
                }
                put("errorByCategory", catCounts)
                
                val sevCounts = JSONObject()
                val sevGroups = errorEntries.groupBy { it.severity }
                for ((sev, entries) in sevGroups) {
                    sevCounts.put(sev.name, entries.size)
                }
                put("errorBySeverity", sevCounts)
            }
        }
    }
    
    // ===== JSON序列化 =====
    
    private fun logToJson(log: RecognitionLog): JSONObject {
        return JSONObject().apply {
            put("time", log.timeStr)
            put("timestamp", log.timestamp)
            
            val localCV = JSONObject().apply {
                put("enabled", log.localCVEnabled)
                put("timeMs", log.localCVTimeMs)
                put("handCards", log.localHandCards)
                put("communityCards", log.localCommunityCards)
                put("street", log.localStreet ?: JSONObject.NULL)
            }
            put("localCV", localCV)
            
            val locking = JSONObject().apply {
                put("streetLocked", log.streetLocked ?: JSONObject.NULL)
                put("holeCardsLocked", log.holeCardsLocked)
            }
            put("locking", locking)
            
            val vlm = JSONObject().apply {
                put("timeMs", log.vlmTimeMs)
                put("handCards", log.vlmHandCards)
                put("communityCards", log.vlmCommunityCards)
                put("street", log.vlmStreet)
                put("pot", log.vlmPot)
                put("myChips", log.vlmMyChips)
                put("betToCall", log.vlmBetToCall)
                put("buttons", JSONArray(log.vlmButtons))
                put("dButtonPos", log.vlmDButtonPos)
                put("totalPlayers", log.vlmTotalPlayers)
                put("activePlayers", log.vlmActivePlayers)
                put("blinds", log.vlmBlinds)
            }
            put("vlm", vlm)
            
            val finalResult = JSONObject().apply {
                put("street", log.finalStreet)
                put("handCards", log.finalHandCards)
                put("communityCards", log.finalCommunityCards)
            }
            put("final", finalResult)
            
            val chips = JSONObject().apply {
                put("delta", log.chipDelta ?: JSONObject.NULL)
                put("status", log.chipStatus)
                put("potDelta", log.potDelta)
            }
            put("chips", chips)
            
            put("totalTimeMs", log.totalTimeMs)
            put("hasError", log.hasError)
            if (log.errorMessage != null) {
                put("error", log.errorMessage)
                put("errorCategory", log.errorCategory.label)
            }
            put("strategySent", log.strategySent)
            if (log.rawResponse != null && log.hasError) {
                put("rawApiResponse", log.rawResponse.take(500))
            }
        }
    }
    
    private fun decisionToJson(log: DecisionLog): JSONObject {
        return JSONObject().apply {
            put("time", log.timeStr)
            put("timestamp", log.timestamp)
            // 牌局状态
            put("street", log.street)
            put("holeCards", log.holeCards)
            put("communityCards", log.communityCards)
            put("pot", log.potSize)
            put("myChips", log.myChips)
            put("toCall", log.toCall)
            put("totalPlayers", log.totalPlayers)
            put("activePlayers", log.activePlayers)
            put("position", log.position)
            // 策略结果
            put("action", log.action)
            put("sizing", log.sizing)
            put("eq", log.eq)
            put("confidence", log.confidence)
            put("reason", log.reason)
            put("hClass", log.hClass)
            put("auto", log.isAuto)
            put("execResult", log.autoExecResult)
            put("oppStats", log.oppStats)
        }
    }
    
    private fun errorToJson(entry: ErrorEntry): JSONObject {
        return JSONObject().apply {
            put("time", entry.timeStr)
            put("timestamp", entry.timestamp)
            put("category", entry.category.label)
            put("severity", entry.severity.name)
            put("message", entry.message)
            if (entry.detail != null) put("detail", entry.detail)
        }
    }
    
    private fun generateStats(): JSONObject {
        return JSONObject().apply {
            val total = recognitionLogs.size
            val errors = recognitionLogs.count { it.hasError }
            val successRate = if (total > 0) (total - errors) * 100 / total else 0
            
            put("totalRecognitions", total)
            put("errorCount", errors)
            put("successRate", "$successRate%")
            
            // 本地CV使用情况
            val cvUsed = recognitionLogs.count { it.localCVEnabled && it.localCVTimeMs > 0 }
            put("localCVUsedCount", cvUsed)
            
            // 平均耗时
            val avgLocalCV = if (cvUsed > 0) {
                recognitionLogs.filter { it.localCVEnabled && it.localCVTimeMs > 0 }
                    .map { it.localCVTimeMs }.average().toLong()
            } else 0L
            val avgVLM = if (total > 0) {
                recognitionLogs.filter { it.vlmTimeMs > 0 }
                    .map { it.vlmTimeMs }.average().toLong()
            } else 0L
            
            put("avgLocalCVTimeMs", avgLocalCV)
            put("avgVLMTimeMs", avgVLM)
            
            // 筹码变化统计
            val chipIncreases = recognitionLogs.count { (it.chipDelta ?: 0) > 100 }
            val chipDecreases = recognitionLogs.count { (it.chipDelta ?: 0) < -100 }
            put("chipIncreaseCount", chipIncreases)
            put("chipDecreaseCount", chipDecreases)
            
            // V2.9.215: 错误分类统计
            synchronized(errorEntries) {
                val catStats = JSONObject()
                for (cat in ErrorCategory.values()) {
                    catStats.put(cat.label, errorEntries.count { it.category == cat })
                }
                put("errorsByCategory", catStats)
            }
            
            // V2.9.215: 决策统计
            synchronized(decisionLogs) {
                put("totalDecisions", decisionLogs.size)
                put("autoExecCount", decisionLogs.count { it.isAuto })
                if (decisionLogs.isNotEmpty()) {
                    put("avgEquity", String.format("%.1f", decisionLogs.map { it.eq }.average()))
                }
            }
        }
    }
    
    fun clear() {
        synchronized(recognitionLogs) { recognitionLogs.clear() }
        synchronized(decisionLogs) { decisionLogs.clear() }
        synchronized(errorEntries) { errorEntries.clear() }
        currentHandDecisions.clear()
        currentHandId = ""
        resetChipTracking()
    }
    
    fun getRecentErrors(count: Int = 5): List<String> {
        return synchronized(errorEntries) {
            errorEntries.sortedByDescending { it.severity.level }
                .take(count)
                .map { "${it.timeStr} [${it.category.label}/${it.severity.name}] ${it.message}" }
        }
    }
    
    /**
     * V2.9.215: 获取最近决策摘要（供通知栏/悬浮球显示）
     */
    fun getRecentDecisionSummary(): String {
        return synchronized(decisionLogs) {
            if (decisionLogs.isEmpty()) return "暂无决策记录"
            val last = decisionLogs.last()
            "${last.holeCards} ${last.street} → ${last.action} (eq=${last.eq}% ${last.confidence})"
        }
    }
}
