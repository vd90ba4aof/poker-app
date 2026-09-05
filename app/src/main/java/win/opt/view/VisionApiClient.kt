package win.opt.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.util.Base64
import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * V2.9.108: 视觉API客户端 - 单行格式提速+双prompt保险
 * 核心改动：
 * 1. 新增useCompactPrompt开关——true=单行紧凑格式(快26%), false=原格式(兜底)
 * 2. 单行格式生成更少token(~150 vs ~210)，实测2.0s vs 2.8s
 * 3. parseResponse自动适配两种JSON格式，无需手动切换
 * 4. 单行格式解析失败时自动fallback用原格式重试
 * 5. 保留所有已有保险：手牌锁定、D按钮保险、street纠错、校验纠错
 */
object VisionApiClient {

    private const val TAG = "VisionAPI"

    // V2.9.518: Application context for LocalCardRecognizer
    @Volatile
    private lateinit var appContext: Context

    fun initContext(context: Context) {
        if (!::appContext.isInitialized) {
            appContext = context.applicationContext
            // 预加载本地CV模板
            try { LocalCardRecognizer.getInstance(appContext) } catch (_: Exception) {}
            try { LocalActionRecognizer.getInstance(appContext) } catch (_: Exception) {}
        }
    }

    private val context: Context
        get() = if (::appContext.isInitialized) appContext
                else throw IllegalStateException("VisionApiClient.initContext() not called")

    // P0-R4-1: 分析锁——防止并发修改共享状态
    private val analyzeLock = java.util.concurrent.locks.ReentrantLock()

    // R9-5-fix: 供HTTP调试接口快速探测——自动流水线正在分析时，/api/analyze直接503，
    // 避免NanoHTTPD单线程被~28s的VLM调用占满、所有轮询请求排队
    fun isAnalyzeBusy(): Boolean = analyzeLock.isLocked
    
    // V2.9.183: OkHttp连接池复用——避免每次TCP握手浪费100-200ms
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(5, 2, TimeUnit.MINUTES))
            .build()
    }
    
    // V2.9.108: prompt格式开关——true=单行紧凑(快), false=原格式(稳)
    var useCompactPrompt = true

    // V2.9.541: 本地CV为主识别链路（手牌/公共牌/操作区/筹码全像素匹配），VLM仅兜底
    var useLocalRecognition: Boolean = true
    
    var apiProvider = "siliconflow"
    var apiKey = ""
    var apiUrl = "https://api.siliconflow.cn/v1/chat/completions"
    var modelName = "Qwen/Qwen3-VL-8B-Instruct"
    @Volatile var lastError = ""
    // V2.9.193: 保存API原始响应——用于诊断识别失败根因
    @Volatile var lastRawResponse = ""
    @Volatile var lastResult: VisionResult? = null
        private set
    @Volatile var lastResultTime: Long = 0
        private set

    // V2.9.520: 本地CV结果缓存（供DiagnosticLogger上报真实数据）
    @Volatile var lastLocalCVEnabled: Boolean = true
        private set
    @Volatile var lastLocalCVTimeMs: Long = 0
        private set
    @Volatile var lastLocalHandCards: List<CardInfo> = emptyList()
        private set
    @Volatile var lastLocalCommCards: List<CardInfo> = emptyList()
        private set
    // V2.9.521: 本地CV诊断详情
    @Volatile var lastLocalDiag: String = ""
        private set

    // V2.9.230: 断网兜底模式标志位——当API调用失败时是否启用本地识别兜底
    @Volatile var isOfflineFallback: Boolean = false

    // V2.9.240: 平台自动检测——连续3次一致时自动切换
    @Volatile private var _detectedPlatformCount = 0
    @Volatile private var _lastDetectedPlatform = ""

    @Volatile var dButtonPosition: String = ""
    @Volatile var dButtonLocked: String = ""
        private set

    @Volatile var holeCardsLocked: List<CardInfo>? = null
    @Volatile var streetLocked: String? = null  // V2.9.165: 本地CV根据公共牌数量锁定的street
    @Volatile var suitUncertain: Boolean = false
    @Volatile var lockReason: String = ""
    // V2.9.197: 混合方案 — 仅锁定rank（本地CV高置信度），suit仍由API识别
    @Volatile var holeCardsRankLocked: List<String>? = null

    // V2.9.108: 统计信息（AtomicInteger防并发丢失）
    private val _compactSuccessCount = java.util.concurrent.atomic.AtomicInteger(0)
    var compactSuccessCount: Int get() = _compactSuccessCount.get(); private set(value) { _compactSuccessCount.set(value) }
    private val _compactFailCount = java.util.concurrent.atomic.AtomicInteger(0)
    var compactFailCount: Int get() = _compactFailCount.get(); private set(value) { _compactFailCount.set(value) }
    private val _fallbackSuccessCount = java.util.concurrent.atomic.AtomicInteger(0)
    var fallbackSuccessCount: Int get() = _fallbackSuccessCount.get(); private set(value) { _fallbackSuccessCount.set(value) }
    @Volatile var lastPromptMode = ""
        private set

    // V2.9.540: 动态玩家检测——追踪在座/离场
    @Volatile private var prevPlayerSeats: Set<Int> = emptySet()
    @Volatile var seatedPlayerCount: Int = 0
        private set
    @Volatile var lastJoinedSeats: List<Int> = emptyList()
        private set
    @Volatile var lastLeftSeats: List<Int> = emptyList()
        private set

    data class VisionResult(
        val isPokerTable: Boolean,
        val holeCards: List<CardInfo>,
        val communityCards: List<CardInfo>,
        val potSize: Int,
        val playerChips: Int,
        val totalPlayers: Int,
        val activePlayers: Int,
        val myPosition: String,
        val street: String,
        val toCall: Int,
        val minRaise: Int,
        val buttons: List<String>,
        val blindSB: Int,
        val blindBB: Int,
        val ante: Int,
        val players: List<PlayerInfo>,
        val dButtonPosition: String,
        val rawResponse: String,
        // V2.9.143: 摊牌检测——对手亮出的牌
        val showdownCards: List<ShowdownInfo>,
        val oppHud: List<OppHudInfo>,
        // V2.9.180: 按钮坐标（用于全自动执行）
        val buttonPositions: List<ButtonPosition>,
        // V2.9.206: 搓牌检测——花色不确定时可能正在squeeze
        val suitUncertain: Boolean = false,
        // V2.9.200: GG扑克特有字段
        val isStraddle: Boolean = false,           // 是否Straddle阶段
        val isBombPot: Boolean = false,            // 是否Bomb Pot
        val isInsurance: Boolean = false,          // 是否出现Insurance/Cashout按钮
        val isPKO: Boolean = false,                // 是否PKO赏金赛
        // V2.9.212: 游戏模式检测——现金桌vs锦标赛
        val gameMode: String = "cash",             // 游戏模式: cash=现金桌, tournament=锦标赛(MTT)
        // V2.9.xxx: 游戏类型与抽水（数据链补齐）
        val gameType: String = "normal",           // 游戏类型: normal/rush_cash
        val rakeCap: Int = 0,                      // 抽水上限（单位同筹码）
        // V2.9.220: 自动检测到的平台（V2.9.508: 仅支持GGPOKER）
        val detectedPlatform: String = "GGPOKER",  // 自动检测平台
        // V2.9.230: 本地suit识别标记——标记最终suit是否来自本地推断（用于前端判断可信度）
        val localSuitUsed: Boolean = false,
        // V2.9.526: 是否轮到我行动（绿色进度条检测，预处理状态不点击）
        val isMyTurn: Boolean = true
    )

    data class CardInfo(val rank: String, val suit: String)
    data class PlayerInfo(val position: String, val bet: Int, val chips: Int, val active: Boolean, val nickname: String = "")
    // V2.9.143: 摊牌信息——对手亮牌+输赢
    data class ShowdownInfo(val seat: Int, val cards: List<CardInfo>, val won: Boolean)
    // V2.9.153: Smart HUD
    data class OppHudInfo(val seat: Int, val vpip: Int, val pfr: Int, val ats: Int, val threeBet: Int)
    // V2.9.180: 按钮坐标
    data class ButtonPosition(val text: String, val xPct: Double, val yPct: Double)
    // V2.9.xxx: 多桌并行分析结果
    data class MultiTableResult(
        val tableId: Int,
        val result: VisionResult?,
        val success: Boolean,
        val errorMessage: String
    )

    fun analyzeScreenshot(jpegData: ByteArray): VisionResult? {
        if (apiKey.isEmpty()) { lastError = "未设置API Key"; return null }
        // P0-R4-1: 加锁保护共享状态的复合读写
        // R6-fix: 使用tryLock超时，防止主线程被无限阻塞导致ANR
        if (!analyzeLock.tryLock(25, java.util.concurrent.TimeUnit.SECONDS)) {
            Log.w(TAG, "analyzeLock获取超时(25s)，放弃本次分析")
            lastError = "分析锁超时"
            return null
        }
        try {
        return try {
            val t0 = System.currentTimeMillis()
            // V2.9.541: 本地CV为主+VLM兜底（analyzeScreenshotConcurrent路径）

            val compressedJpeg = compressImage(jpegData, maxWidth = 960)
            val t1 = System.currentTimeMillis()
            val base64Image = Base64.encodeToString(compressedJpeg, Base64.NO_WRAP)
            val t2 = System.currentTimeMillis()
            val dataUri = "data:image/jpeg;base64,$base64Image"
            Log.d(TAG, "⏱ Image: ${jpegData.size/1024}KB→${compressedJpeg.size/1024}KB compress=${t1-t0}ms encode=${t2-t1}ms")

            // V2.9.156: 统一用新分层prompt，不再区分compact/legacy
            var result: VisionResult? = null
            var rawResponse: String? = null
            try {
                val requestJson = buildRequest(dataUri, compact = true)
                val tApi0 = System.currentTimeMillis()
                rawResponse = sendRequest(requestJson)
                lastRawResponse = rawResponse  // V2.9.193: 保存原始响应用于诊断
                result = parseResponse(rawResponse)
                val tApi1 = System.currentTimeMillis()
                if (result != null) {
                    _compactSuccessCount.incrementAndGet(); lastPromptMode = "v156_schema"
                    Log.d(TAG, "⏱ v156 API: ${tApi1-tApi0}ms 成功(${compactSuccessCount}/${compactFailCount})")
                    // V2.9.230: 应用本地suit识别融合（仅对suit_uncertain=true的牌生效）
                    // 使用原始JPEG解码的bitmap进行本地花色识别
                    val screenshotBmp = try {
                        android.graphics.BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size)
                    } catch (_: Exception) { null }
                    result = applyLocalSuitFusion(result, screenshotBmp)
                    screenshotBmp?.recycle()
                }
            } catch (e: Exception) { 
                Log.w(TAG, "v156异常: ${e.message}")
                lastRawResponse = "EXCEPTION: ${e.message}"  // V2.9.193: 记录异常
            }
            
            if (result == null) {
                _compactFailCount.incrementAndGet()
                Log.e(TAG, "★ API解析失败, 原始响应前300字符: ${rawResponse?.take(300) ?: "null"}")
                Log.d(TAG, "v156失败(${compactFailCount}次)")
                lastError = "API错误: 识别失败"; return null
            }
            val t3 = System.currentTimeMillis()
            Log.d(TAG, "⏱ 全链路: compress=${t1-t0}ms encode=${t2-t1}ms api+parse=${t3-t2}ms total=${t3-t0}ms")
            if (result == null) { lastError = "API返回空结果"; return null }

            val currentRankKey = result.holeCards.joinToString(",") { it.rank }
            val lastRankKey = holeCardsLocked?.joinToString(",") { it.rank }
                ?: holeCardsRankLocked?.joinToString(",") ?: ""
            if (lastRankKey.isNotEmpty() && currentRankKey != lastRankKey) {
                Log.d(TAG, "手牌锁定: 新一手牌(rank: $lastRankKey→$currentRankKey)，重置")
                holeCardsLocked = null; holeCardsRankLocked = null; dButtonLocked = ""; streetLocked = null
            }
            // V2.9.197: 混合方案 — 本地CV锁定rank + API补充suit
            // 当本地CV高置信度识别手牌rank时，rank锁定，suit由API提供
            if (holeCardsRankLocked != null && holeCardsRankLocked!!.size == 2 && result.holeCards.size == 2) {
                val mergedHoleCards = result.holeCards.mapIndexed { idx, apiCard ->
                    CardInfo(holeCardsRankLocked!![idx], apiCard.suit)
                }
                lockReason = "混合锁定(本地rank+API suit)"
                suitUncertain = false
                var mergedResult = result.copy(holeCards = mergedHoleCards)
                val dPosInsured = applyDButtonInsurance(mergedResult.dButtonPosition, mergedHoleCards)
                dButtonPosition = dPosInsured; mergedResult = mergedResult.copy(dButtonPosition = dPosInsured)
                lastResult = mergedResult; lastResultTime = System.currentTimeMillis(); lastError = ""
                var corrected = applyStreetCorrection(mergedResult)
                corrected = applyValidationCorrections(corrected); lastResult = corrected
                Log.d(TAG, "识别成功(混合rank锁定): hand=${mergedHoleCards.map{"${it.rank}${it.suit}"}} | comm=${corrected.communityCards.map{it.rank}.joinToString()} | 底池${corrected.potSize} | D=$dPosInsured")
                return corrected
            }
            // V2.9.114: 空手牌不应被锁定——如果之前锁定了空列表，必须重置
            // V2.9.134: 保留suit（vision已识别花色），不再抹掉
            if (holeCardsLocked != null && holeCardsLocked!!.isNotEmpty()) {
                lockReason = "已锁定，跳过重识"; suitUncertain = false
                var lockedResult = result.copy(holeCards = holeCardsLocked!!, communityCards = result.communityCards)
                val dPosInsured = applyDButtonInsurance(lockedResult.dButtonPosition, holeCardsLocked!!)
                dButtonPosition = dPosInsured; lockedResult = lockedResult.copy(dButtonPosition = dPosInsured)
                lastResult = lockedResult; lastResultTime = System.currentTimeMillis()
                var corrected = applyStreetCorrection(lockedResult)
                corrected = applyValidationCorrections(corrected); lastResult = corrected
                Log.d(TAG, "识别成功(锁定,$lastPromptMode): ${corrected.holeCards.map{it.rank}.joinToString()} | comm=${corrected.communityCards.map{it.rank}.joinToString()} | 底池${corrected.potSize} | D=$dPosInsured")
                return corrected
            }
            // V2.9.114: 只锁定非空手牌，防止空列表锁死
            // V2.9.134: 保留suit（vision已识别花色），不再抹掉
            if (result.holeCards.isNotEmpty()) {
                holeCardsLocked = result.holeCards; lockReason = "首次识别锁定"; suitUncertain = false
            } else {
                holeCardsLocked = null; lockReason = "手牌为空不锁定"; suitUncertain = false
            }
            var correctedResult = result.copy(holeCards = result.holeCards, communityCards = result.communityCards)
            val dPosInsured = applyDButtonInsurance(correctedResult.dButtonPosition, correctedResult.holeCards)
            dButtonPosition = dPosInsured; correctedResult = correctedResult.copy(dButtonPosition = dPosInsured)
            lastResult = correctedResult; lastResultTime = System.currentTimeMillis(); lastError = ""
            correctedResult = applyStreetCorrection(correctedResult)
            correctedResult = applyValidationCorrections(correctedResult); lastResult = correctedResult
            // V2.9.240: 详细识别结果日志
            try {
                val holeStr = correctedResult.holeCards.joinToString(",") { "${it.rank}${it.suit}" }
                val commStr = correctedResult.communityCards.joinToString(",") { "${it.rank}${it.suit}" }
                Log.i(TAG, "识别结果($lastPromptMode): 手牌=[$holeStr] | 公共牌=[$commStr] | street=${correctedResult.street} | 底池=${correctedResult.potSize} | 跟注=${correctedResult.toCall} | 位置=${correctedResult.myPosition} | 平台=${correctedResult.detectedPlatform} | D=$dPosInsured")
            } catch (_: Exception) {}
            Log.d(TAG, "识别成功($lastPromptMode): ${correctedResult.holeCards.joinToString()} | comm=${correctedResult.communityCards.map{it.rank}.joinToString()} | 底池${correctedResult.potSize} | ${correctedResult.totalPlayers}桌 | D=$dPosInsured")
            correctedResult
        } catch (e: Exception) {
            lastError = "API错误: ${e.message}"
            Log.e(TAG, "analyzeScreenshot failed: ${e.message}", e)
            Log.e(TAG, "完整异常堆栈: ${android.util.Log.getStackTraceString(e)}")
            // V2.9.230: 断网兜底模式——API调用失败时，尝试返回部分结果
            // 如果有本地CV锁定的rank信息，返回一个带有rank但suit不确定的结果
            // 保证游戏过程不会因为断网完全中断
            if (isOfflineFallback) {
                Log.w(TAG, "启用断网兜底模式: API调用失败，尝试返回本地rank兜底结果")
                return try {
                    val fallbackHoleCards = holeCardsRankLocked?.map { rank ->
                        CardInfo(rank, "")  // suit为空，表示不确定
                    } ?: emptyList()
                    val fallbackStreet = streetLocked ?: "preflop"
                    // 使用上一次成功结果中的部分信息（如果有）
                    // P2-fix: 超过60秒的lastResult视为过期，不使用其数据防止误导
                    val last = if (System.currentTimeMillis() - lastResultTime < 60_000) lastResult else null
                    VisionResult(
                        isPokerTable = false,  // 标记为非牌桌，表示这是兜底结果
                        holeCards = fallbackHoleCards,
                        communityCards = last?.communityCards ?: emptyList(),
                        potSize = last?.potSize ?: 0,
                        playerChips = last?.playerChips ?: 0,
                        totalPlayers = last?.totalPlayers ?: 6,
                        activePlayers = last?.activePlayers ?: 2,
                        myPosition = last?.myPosition ?: "",
                        street = fallbackStreet,
                        toCall = last?.toCall ?: 0,
                        minRaise = last?.minRaise ?: 0,
                        buttons = last?.buttons ?: emptyList(),
                        blindSB = last?.blindSB ?: 0,
                        blindBB = last?.blindBB ?: 0,
                        ante = last?.ante ?: 0,
                        players = last?.players ?: emptyList(),
                        dButtonPosition = last?.dButtonPosition ?: dButtonPosition,
                        rawResponse = "OFFLINE_FALLBACK: ${e.message}",
                        showdownCards = emptyList(),
                        oppHud = emptyList(),
                        buttonPositions = emptyList(),
                        suitUncertain = true,  // 兜底模式下suit全部不确定
                        isStraddle = last?.isStraddle ?: false,
                        isBombPot = last?.isBombPot ?: false,
                        isInsurance = last?.isInsurance ?: false,
                        isPKO = last?.isPKO ?: false,
                        gameMode = last?.gameMode ?: "cash",
                        detectedPlatform = last?.detectedPlatform ?: "GGPOKER",
                        localSuitUsed = false  // 兜底模式下suit为空，未使用本地推断
                    )
                } catch (ex: Exception) {
                    Log.e(TAG, "断网兜底也失败: ${ex.message}")
                    null
                }
            }
            null
        }
        } catch (e: Error) {
            // R6-fix: 捕获StackOverflowError/OutOfMemoryError等Error级异常
            // 防止深度嵌套JSON解析导致StackOverflowError绕过try-catch(Exception)
            Log.e(TAG, "analyzeScreenshot Error: ${e.javaClass.simpleName}: ${e.message}")
            lastError = "内部错误: ${e.javaClass.simpleName}"
            return null
        } finally {
            analyzeLock.unlock()
        }
    }

    /**
     * P1-R3-2: 只读分析方法，不修改任何共享状态
     * 专供后台HUD线程使用，避免与主线程识别逻辑产生竞态
     */
    fun analyzeScreenshotReadOnly(jpegData: ByteArray): VisionResult? {
        if (apiKey.isEmpty()) return null
        return try {
            val compressedJpeg = compressImage(jpegData, maxWidth = 960)
            val base64Image = android.util.Base64.encodeToString(compressedJpeg, android.util.Base64.NO_WRAP)
            val dataUri = "data:image/jpeg;base64,$base64Image"
            val requestJson = buildRequest(dataUri, compact = true)
            val rawResponse = sendRequest(requestJson)
            parseResponse(rawResponse)
        } catch (e: Exception) {
            Log.w(TAG, "ReadOnly分析失败: ${e.message}")
            null
        }
    }

    /**
     * V3.0.0: 本地识别结果应用D按钮保险
     * 复用与API路径相同的保险逻辑
     */
    private fun applyDButtonInsuranceToLocal(result: VisionResult): VisionResult {
        val dPosInsured = applyDButtonInsurance(result.dButtonPosition, result.holeCards)
        dButtonPosition = dPosInsured
        return result.copy(dButtonPosition = dPosInsured)
    }

    private fun applyStreetCorrection(result: VisionResult): VisionResult {
        // V2.9.165: 优先使用本地CV锁定的street（最可靠）
        if (streetLocked != null && result.street.lowercase() != streetLocked!!.lowercase()) {
            Log.w(TAG, "street锁定覆盖: ${result.street}→${streetLocked!!} (本地CV)")
            return result.copy(street = streetLocked!!)
        }
        // 兜底：根据VLM返回的公共牌数量纠正street
        val commCount = result.communityCards.size
        val correctStreet = when { commCount == 0 -> "preflop"; commCount == 3 -> "flop"; commCount == 4 -> "turn"; commCount == 5 -> "river"; else -> null }
        return if (correctStreet != null && result.street.lowercase() != correctStreet) { Log.w(TAG, "street纠正: ${result.street}→$correctStreet"); result.copy(street = correctStreet) } else result
    }

    private fun applyDButtonInsurance(rawPos: String, currentCards: List<CardInfo>): String {
        val rankKey = currentCards.joinToString(",") { it.rank }
        val lastRankKey = lastResult?.holeCards?.joinToString(",") { it.rank } ?: ""
        if (rankKey != lastRankKey && lastRankKey.isNotEmpty()) { dButtonLocked = ""; Log.d(TAG, "D按钮保险: 新一手牌，重置锁定") }
        if (rawPos.isEmpty() || rawPos == "not_found") { if (dButtonLocked.isNotEmpty()) return dButtonLocked; return rawPos }
        if (dButtonLocked.isEmpty()) { dButtonLocked = rawPos; return rawPos }
        if (rawPos == dButtonLocked) return rawPos
        if (isNeighborPosition(rawPos, dButtonLocked)) return dButtonLocked
        Log.w(TAG, "D按钮保险: 突变(${dButtonLocked}→${rawPos})，保留锁定值"); return dButtonLocked
    }

    private fun isNeighborPosition(pos1: String, pos2: String): Boolean {
        if (pos1.isEmpty() || pos2.isEmpty()) return false
        val side = { p: String -> when { p.contains("left") -> "left"; p.contains("right") -> "right"; p.contains("top-center") -> "top"; p.contains("bottom-center") -> "bottom"; else -> p } }
        return side(pos1) == side(pos2)
    }

    // V2.9.156: 1080px/Q80 + 底部裁切 + 对比度增强
    private fun compressImage(jpegData: ByteArray, maxWidth: Int): ByteArray {
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size) ?: return jpegData
        // P2-fix: try-finally保证异常路径也能recycle，防止Bitmap泄漏
        var current: android.graphics.Bitmap = bitmap
        try {
            // 裁切顶部2%（标题栏）和底部8%（系统栏）
            val cropTop = (bitmap.height * 0.02).toInt()
            val cropBottom = (bitmap.height * 0.92).toInt()
            val cropLeft = (bitmap.width * 0.02).toInt()
            val cropRight = (bitmap.width * 0.98).toInt()
            val cropped = try {
                android.graphics.Bitmap.createBitmap(bitmap, cropLeft, cropTop,
                    cropRight - cropLeft, cropBottom - cropTop)
            } catch (_: Exception) {
                // fallback: 只裁顶部
                try { android.graphics.Bitmap.createBitmap(bitmap, 0, cropTop, bitmap.width, bitmap.height - cropTop) } catch (_: Exception) { bitmap }
            }
            if (cropped !== bitmap) bitmap.recycle()
            current = cropped
            // 分辨率提升: 960→1080
            val targetWidth = 1080
            val scale = if (cropped.width > targetWidth) targetWidth.toFloat() / cropped.width else 1f
            val scaled = if (scale < 1f) {
                val s = android.graphics.Bitmap.createScaledBitmap(cropped, (cropped.width * scale).toInt(), (cropped.height * scale).toInt(), true)
                cropped.recycle(); s
            } else cropped
            current = scaled
            // V2.9.156: 对比度增强(1.2x)帮助花色识别
            val enhanced = enhanceContrast(scaled, 1.2f)
            if (enhanced !== scaled) scaled.recycle()
            current = enhanced
            val stream = ByteArrayOutputStream()
            enhanced.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, stream)
            return stream.toByteArray()
        } finally {
            try { current.recycle() } catch (_: Exception) {}
        }
    }

    // V2.9.156: 对比度增强——帮助VLM区分♠♣和♥♦
    private fun enhanceContrast(bmp: android.graphics.Bitmap, factor: Float): android.graphics.Bitmap {
        return try {
            val matrix = android.graphics.ColorMatrix(floatArrayOf(
                factor, 0f, 0f, 0f, (1f - factor) * 128f,
                0f, factor, 0f, 0f, (1f - factor) * 128f,
                0f, 0f, factor, 0f, (1f - factor) * 128f,
                0f, 0f, 0f, 1f, 0f
            ))
            val result = android.graphics.Bitmap.createBitmap(bmp.width, bmp.height, bmp.config ?: android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(result)
            val paint = android.graphics.Paint()
            paint.colorFilter = android.graphics.ColorMatrixColorFilter(matrix)
            canvas.drawBitmap(bmp, 0f, 0f, paint)
            result
        } catch (_: Exception) { bmp }
    }

    /**
     * V2.9.220: 根据OCR识别文本自动检测平台
     * V2.9.508: 简化为仅返回GGPOKER
     */
    private fun detectPlatform(content: String): String {
        // V2.9.508: 仅支持GGPOKER，直接返回
        return "GGPOKER"
    }

    /**
     * V2.9.200: 根据当前平台生成Prompt差异化描述
     * 返回 Pair(平台前缀描述, 按钮描述文本)
     */
    private fun buildPlatformPromptHint(): Pair<String, String> {
        // V2.9.508: 仅支持GGPOKER
        return Pair(
            "GG扑克(GGPoker)。特征:深蓝/深绿色桌面,竖屏布局,按钮可能是英文(Fold/Check/Call/Raise/All In)或中文,行动时按钮放大10%,可能有Straddle/Bomb Pot/Insurance/PKO等特殊模式。",
            "Fold/Check/Call含金额/Raise含金额/All In,可能含预设加注额(1/2Pot,2/3Pot,Pot),中英文都可能"
        )
    }

    // V2.9.156: 分层Prompt+Schema+Few-Shot+JSON Mode+temperature=0
    private fun buildRequest(base64Image: String, model: String? = null, compact: Boolean = true): String {
        val streetHint = streetLocked?.let { "\n【已知street】当前street已确认为${it}，phase字段直接填${it}，buttons识别必须与此street的场景一致。\n" } ?: ""
        // V2.9.197: 混合方案 — 传递rank锁定提示给API，让API专注suit识别
        val rankHint = holeCardsRankLocked?.let {
            if (it.size == 2) "\n【手牌rank已锁定】手牌点数已确认为[${it[0]}, ${it[1]}]，hole_cards的rank字段必须填这两个值，你只需识别suit（花色）。\n" else ""
        } ?: ""
        // V2.9.196: 精简prompt减少input token加速推理
        // V2.9.200: 根据当前平台动态调整prompt描述（GG/标准/短牌）
        val platformHint = buildPlatformPromptHint()
        val prompt = """${platformHint.first}5-max识别引擎。只输出JSON。
Schema(缺填null):{"is_poker_table":bool,"hole_cards":[{"rank":"A","suit":"s"}],"community_cards":[],"pot":数字,"my_chips":数字,"bet_to_call":数字,"dealer_seat":1-5,"my_seat":1-5,"blinds":"100/200","phase":"preflop","opp_seats":[{"seat":2,"nickname":"P1","chips":"3000","action":"fold"}],"buttons":["弃牌","跟注500"],"button_positions":[{"text":"弃牌","xPct":0.17,"yPct":0.88}],"d_button_pos":"left-top","total_players":5,"active_players":3,"showdown_cards":[],"opp_hud":[],"is_straddle":false,"is_bomb_pot":false,"is_insurance":false,"is_pko":false,"game_mode":"cash","detected_platform":"GGPOKER"}
花色:s=♠黑 h=♥红心 d=♦方块 c=♣梅花。对子花色须不同。
pot展开简写:1.2K=1200,1.5M=1500000。底池=桌面中央筹码堆。
active_players=仅有牌(明/暗)的玩家,弃牌/空座不计。
buttons=底部全部按钮(${platformHint.second}),不可遗漏!
button_positions=每按钮{text与buttons一致,xPct=中心X/屏宽,yPct=中心Y/屏高},加注可能横排多坐标。
opp_seats须含nickname(头像旁用户名)。showdown_cards=摊牌对手牌,看不到填[]。opp_hud=对手统计,看不到填[]。
GG特有字段:is_straddle=是否Straddle(第三盲注);is_bomb_pot=是否BombPot(所有玩家ante后直接翻牌);is_insurance=是否出现Insurance/EV Cashout按钮;is_pko=是否PKO赏金赛(牌桌有赏金标识)。
game_mode=现金桌填cash,锦标赛填tournament。判断依据:有"锦标赛/报名费/奖池/剩余人数/盲注倒计时"填tournament,否则填cash。
detected_platform=根据桌面logo/品牌文字自动识别平台。判断依据:看到GGPoker/GG标志填GGPOKER,仅支持GGPOKER。
示例:{"is_poker_table":true,"hole_cards":[{"rank":"A","suit":"s"},{"rank":"K","suit":"h"}],"community_cards":[{"rank":"Q","suit":"d"}],"pot":1500,"my_chips":25000,"bet_to_call":0,"dealer_seat":3,"my_seat":1,"blinds":"100/200","phase":"flop","opp_seats":[{"seat":2,"nickname":"King","chips":"18000","action":"check"}],"buttons":["让牌","下注500"],"button_positions":[{"text":"让牌","xPct":0.50,"yPct":0.88},{"text":"下注500","xPct":0.83,"yPct":0.88}],"d_button_pos":"left-top","total_players":5,"active_players":3,"showdown_cards":[],"opp_hud":[],"is_straddle":false,"is_bomb_pot":false,"is_insurance":false,"is_pko":false,"game_mode":"cash","detected_platform":"GGPOKER"}
${streetHint}${rankHint}识别:"""

        return JSONObject().apply {
            put("model", model ?: modelName)
            put("max_tokens", 800)  // V2.9.196: 1500→800减少输出等待
            put("temperature", 0.0)  // V2.9.156: 确定性输出
            // V2.9.320: 固定使用JSON Mode（硅基流动Qwen3-VL支持）
            put("response_format", JSONObject().put("type", "json_object"))
            put("messages", JSONArray().apply { put(JSONObject().apply {
                put("role", "user"); put("content", JSONArray().apply {
                    put(JSONObject().apply { put("type", "text"); put("text", prompt) })
                    put(JSONObject().apply { put("type", "image_url"); put("image_url", JSONObject().apply { put("url", base64Image); put("detail", "low") }) })  // V2.9.196: high→low提速
                })
            }) })
        }.toString()
    }

    private fun sendRequest(requestJson: String): String {
        val reqTime = System.currentTimeMillis()
        Log.d(TAG, "sendRequest: 请求开始, url=$apiUrl, model=$modelName, payload=${requestJson.take(80)}...")
        var lastException: Exception? = null
        // V2.9.184: 网络波动重试1次，间隔500ms
        // R6-fix: 仅重试5xx服务端错误，4xx不重试；增加响应大小限制
        repeat(2) { attempt ->
            try {
                val body = requestJson.toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url(apiUrl)
                    .post(body)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .build()
                val response = httpClient.newCall(request).execute()
                val respTime = System.currentTimeMillis()
                val elapsed = respTime - reqTime
                return if (response.isSuccessful) {
                    val respBody = response.body?.string() ?: throw Exception("Empty response body")
                    // R6-fix: 响应大小限制（10MB），防止恶意API响应导致OOM
                    if (respBody.length > 10 * 1024 * 1024) {
                        throw Exception("Response too large: ${respBody.length}B")
                    }
                    Log.i(TAG, "sendRequest: 响应成功, 耗时=${elapsed}ms, 响应大小=${respBody.length}字节, attempt=${attempt + 1}")
                    respBody
                } else {
                    val errBody = response.body?.string() ?: ""
                    Log.e(TAG, "sendRequest: HTTP错误, code=${response.code}, 耗时=${elapsed}ms, body=${errBody.take(200)}")
                    if (attempt == 0 && response.code >= 500) {
                        Log.w(TAG, "HTTP ${response.code}, retrying in 500ms...")
                        lastException = Exception("HTTP ${response.code}: $errBody")
                        Thread.sleep(500)
                        return@repeat
                    }
                    throw Exception("HTTP ${response.code}: $errBody")
                }
            } catch (e: Exception) {
                lastException = e
                val errTime = System.currentTimeMillis()
                if (attempt == 0) {
                    Log.w(TAG, "sendRequest: 第${attempt + 1}次失败 (${errTime - reqTime}ms): ${e.message}")
                    Thread.sleep(500)
                } else {
                    Log.e(TAG, "sendRequest: 全部重试失败 (${errTime - reqTime}ms): ${e.message}", e)
                }
            }
        }
        throw lastException ?: Exception("Unknown error")
    }

    private fun parseResponse(responseBody: String): VisionResult? {
        // R6-fix: 响应大小预检，防止超深嵌套JSON导致StackOverflowError
        if (responseBody.length > 5 * 1024 * 1024) {
            Log.w(TAG, "parseResponse: response too large (${responseBody.length}B), skipping")
            return null
        }
        val content = JSONObject(responseBody).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
        val jsonStr = extractJson(content) ?: return null
        val data = JSONObject(jsonStr)
        val isCompact = data.has("phase") || data.has("bet_to_call") || data.has("blinds")
        val buttonsJson = data.optJSONArray("buttons")
        val buttons = if (buttonsJson != null) {
            (0 until buttonsJson.length()).map { buttonsJson.getString(it) }
        } else emptyList()
        // V2.9.180: 解析按钮坐标
        val btnPosJson = data.optJSONArray("button_positions")
        val buttonPositions = if (btnPosJson != null) {
            (0 until btnPosJson.length()).mapNotNull { i ->
                try {
                    val obj = btnPosJson.getJSONObject(i)
                    ButtonPosition(obj.getString("text"), obj.getDouble("x_pct"), obj.getDouble("y_pct"))
                } catch (_: Exception) { null }
            }
        } else emptyList()
        val players = if (isCompact) parseOppSeats(data.optJSONArray("opp_seats")) else parseLegacyPlayers(data.optJSONArray("players"))
        val callFromButtons = parseCallAmountFromButtons(buttons)
        val finalToCall = if (callFromButtons >= 0) callFromButtons else if (isCompact) parseChipValue(data, "bet_to_call") else data.optInt("to_call", 0)
        val (blindSB, blindBB) = if (isCompact) parseBlindsString(data.optString("blinds", "")) else Pair(parseChipValue(data, "blind_sb"), parseChipValue(data, "blind_bb"))
        val street = if (isCompact) data.optString("phase", "preflop") else data.optString("street", "preflop")
        val potSize = if (isCompact) parseChipValue(data, "pot") else parsePotSize(data, "pot_size")
        val insuredPot = if (potSize == 0 && data.has("pot_size")) { val v = parsePotSize(data, "pot_size"); if (v > 0) v else potSize } else potSize
        val isPokerTable = data.optBoolean("is_poker_table", true) // V2.9.111: 默认true兼容旧格式
        // V2.9.143: 解析摊牌信息
        val showdownCards = parseShowdownCards(data.optJSONArray("showdown_cards"))
        val oppHud = parseOppHud(data.optJSONArray("opp_hud"))
// V2.9.200: GG特有字段
        val isStraddle = data.optBoolean("is_straddle", false)
        val isBombPot = data.optBoolean("is_bomb_pot", false)
        val isInsurance = data.optBoolean("is_insurance", false)
        val isPKO = data.optBoolean("is_pko", false)
        // V2.9.212: 游戏模式检测——现金桌vs锦标赛
        val gameMode = data.optString("game_mode", "cash").takeIf { it.isNotEmpty() } ?: "cash"
        // V2.9.508: 仅支持GGPOKER，跳过平台自动切换逻辑
        val detectedPlatform = "GGPOKER"
        Log.i(TAG, "平台检测: 当前仅支持GGPOKER")
return VisionResult(isPokerTable, parseCards(data.optJSONArray("hole_cards")), parseCards(data.optJSONArray("community_cards")), insuredPot, parseChipValue(data, "my_chips"), data.optInt("total_players", 6), data.optInt("active_players", 2), data.optString("my_position", ""), street, finalToCall, data.optInt("min_raise", 0), buttons, blindSB, blindBB, parseChipValue(data, "ante"), players, data.optString("d_button_pos", ""), content, showdownCards, oppHud, buttonPositions, suitUncertain, isStraddle, isBombPot, isInsurance, isPKO, gameMode, detectedPlatform)
    }

    private fun parseOppSeats(arr: JSONArray?): List<PlayerInfo> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            try { val o = arr.optJSONObject(i) ?: return@mapNotNull null; val s = o.optInt("seat", 0); val c = parseChipValue(o, "chips"); val a = o.optString("action", ""); val nick = o.optString("nickname", ""); if (s > 0) PlayerInfo(seatToPosition(s), if (a == "raise" || a == "call" || a == "allin") c else 0, c, a != "fold", nick) else null } catch (_: Exception) { null }
        }
    }
    private fun seatToPosition(s: Int) = when(s) { 1->"bottom"; 2->"left-bottom"; 3->"left-top"; 4->"top-center"; 5->"right-top"; 6->"right-bottom"; else->"seat_$s" }
    // V2.9.143: 解析摊牌信息——对手亮出的牌
    private fun parseShowdownCards(arr: JSONArray?): List<ShowdownInfo> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            try {
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val seat = o.optInt("seat", 0)
                val cards = parseCards(o.optJSONArray("cards"))
                val won = o.optBoolean("won", false)
                if (seat > 0 && cards.isNotEmpty()) ShowdownInfo(seat, cards, won) else null
            } catch (_: Exception) { null }
        }
    }
    // V2.9.153
    private fun parseOppHud(arr: JSONArray?): List<OppHudInfo> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            try { val o = arr.optJSONObject(i) ?: return@mapNotNull null; val s = o.optInt("seat", 0); val v = o.optInt("vpip", 0); val p = o.optInt("pfr", 0); if (s > 0 && (v > 0 || p > 0)) OppHudInfo(s, v, p, o.optInt("ats", 0), o.optInt("three_bet", 0)) else null } catch (_: Exception) { null }
        }
    }
    private fun parseLegacyPlayers(arr: JSONArray?): List<PlayerInfo> {
        if (arr == null) return emptyList()
        return try { (0 until arr.length()).mapNotNull { i -> val o = arr.optJSONObject(i) ?: return@mapNotNull null; val p = o.optString("position", ""); if (p.isNotEmpty()) PlayerInfo(p, o.optInt("bet", 0), o.optInt("chips", 0), o.optBoolean("active", true)) else null } } catch (_: Exception) { emptyList() }
    }
    private fun parseBlindsString(blinds: String): Pair<Int, Int> = try { val p = blinds.split("/"); if (p.size == 2) Pair(parseChipString(p[0].trim()), parseChipString(p[1].trim())) else Pair(0, 0) } catch (_: Exception) { Pair(0, 0) }

    // V2.9.194: extractJson 重写——逐个尝试每个{位置+详细日志
    private fun extractJson(text: String): String? {
        Log.d(TAG, "extractJson: len=${text.length}, head=${text.take(80).replace("\n","\\n")}")
        // 1. 整体解析
        try { JSONObject(text); Log.d(TAG, "extractJson: step1 whole-text OK"); return text } catch (_: Exception) {}
        // 2. markdown 代码块提取
        val m = Regex("```(?:json)?\\s*\\n?([\\s\\S]*?)\\n?```").find(text)
        if (m != null) try { val v = m.groupValues[1].trim(); JSONObject(v); Log.d(TAG, "extractJson: step2 markdown OK"); return v } catch (_: Exception) {}
        // 3. V2.9.194: 逐个{位置尝试括号匹配——不轻易放弃
        var startIdx = text.indexOf('{')
        while (startIdx >= 0) {
            var depth = 0
            var inString = false
            var escaped = false
            var matched = false
            for (i in startIdx until text.length) {
                val c = text[i]
                when {
                    escaped -> escaped = false
                    c == '\\' && inString -> escaped = true
                    c == '"' -> inString = !inString
                    inString -> {}
                    c == '{' -> depth++
                    c == '}' -> {
                        depth--
                        if (depth == 0) {
                            val candidate = text.substring(startIdx, i + 1)
                            try { JSONObject(candidate); Log.d(TAG, "extractJson: step3 brace-match OK at idx=$startIdx, len=${candidate.length}"); return candidate } catch (e: Exception) {
                                Log.d(TAG, "extractJson: step3 idx=$startIdx invalid: ${e.message?.take(60)}")
                            }
                            matched = true; break
                        }
                    }
                }
            }
            if (!matched) {
                // 没找到匹配的}，用fallback: 从startIdx到最后一个}
                val lastBrace = text.lastIndexOf('}')
                if (lastBrace > startIdx) {
                    val candidate = text.substring(startIdx, lastBrace + 1)
                    try { JSONObject(candidate); Log.d(TAG, "extractJson: step3 fallback OK at idx=$startIdx"); return candidate } catch (_: Exception) {}
                }
            }
            // 继续找下一个{
            startIdx = text.indexOf('{', startIdx + 1)
        }
        Log.e(TAG, "extractJson: ALL FAILED, text=${text.take(200).replace("\n","\\n")}")
        return null
    }

    private fun parseCards(arr: JSONArray?): List<CardInfo> {
        if (arr == null) return emptyList()
        val valid = setOf("A","K","Q","J","T","9","8","7","6","5","4","3","2"); val norm = mapOf("10" to "T", "1" to "A")
        return (0 until arr.length()).mapNotNull { i -> try {
            val el = arr.get(i)
            when (el) {
                is JSONObject -> { var r = el.optString("rank", ""); r = norm[r] ?: r; val s = el.optString("suit", ""); if (r in valid) CardInfo(r, s) else null }
                is String -> { if (el.length >= 2) { val sc = el.last().lowercaseChar(); val sm = mapOf('s' to "s",'h' to "h",'d' to "d",'c' to "c",'♠' to "s",'♥' to "h",'♦' to "d",'♣' to "c"); val s = sm[sc] ?: ""; var r = el.substring(0, el.length-1).trim().uppercase(); r = norm[r] ?: r; if (r in valid && s.isNotEmpty()) CardInfo(r, s) else null } else null }
                else -> null
            }
        } catch (_: Exception) { null } }
    }

    private fun parseCallAmountFromButtons(buttons: List<String>): Int {
        for (btn in buttons) {
            // V2.9.200: 同时支持中英文按钮（GG扑克英文按钮）
            if (btn.contains("过牌") || btn.contains("让牌") ||
                btn.contains("check", ignoreCase = true)) return 0
            if (btn.contains("跟注") || btn.contains("call", ignoreCase = true)) {
                val n = btn.replace(Regex("(?i)call|跟注"),"").trim().replace(",","")
                return try { if (n.isEmpty()) 0 else if (n.endsWith("K",true)) (n.dropLast(1).toFloat()*1000).toInt() else if (n.endsWith("M",true)) (n.dropLast(1).toFloat()*1000000).toInt() else n.toInt() } catch (_: Exception) { -1 }
            }
            if (btn.contains("全押") || btn.contains("全下") ||
                btn.contains("all in", ignoreCase = true) ||
                btn.contains("all-in", ignoreCase = true)) {
                val n = btn.replace(Regex("(?i)all[\\s-]?in|全押|全下"),"").trim().replace(",","")
                return try { if (n.isEmpty()) -1 else if (n.endsWith("K",true)) (n.dropLast(1).toFloat()*1000).toInt() else if (n.endsWith("M",true)) (n.dropLast(1).toFloat()*1000000).toInt() else n.toInt() } catch (_: Exception) { -1 }
            }
        }
        return -1
    }

    private fun parsePotSize(data: JSONObject, key: String): Int { val r = data.opt(key) ?: return 0; return when(r) { is Int -> r; is Long -> r.toInt(); is Double -> r.toInt(); is String -> parseChipString(r); else -> data.optInt(key, 0) } }
    private fun parseChipValue(data: JSONObject, key: String): Int { val r = data.opt(key) ?: return 0; return when(r) { is Int -> r; is Long -> r.toInt(); is Double -> r.toInt(); is String -> parseChipString(r); else -> data.optInt(key, 0) } }
    private fun parseChipString(s: String): Int { val t = s.trim().replace(",",""); return try { when { t.endsWith("K",true) -> (t.dropLast(1).toFloat()*1000).toInt(); t.endsWith("M",true) -> (t.dropLast(1).toFloat()*1000000).toInt(); t.contains(".") -> t.toFloat().toInt(); else -> t.toInt() } } catch (_: Exception) { 0 } }

    // V2.9.230: 本地suit识别结果
    data class LocalSuitResult(val suit: String, val confidence: Float)

    /**
     * V2.9.230: 本地花色识别——基于颜色分布+形状特征推断suit
     * 输入: 卡牌区域的bitmap
     * 输出: 推断的suit字符串(s/h/d/c) + 置信度，失败返回null
     *
     * 识别思路:
     * 1. 颜色判断: 红色像素占比高 → hearts或diamonds；黑色像素占比高 → spades或clubs
     * 2. 形状辅助: 宽长比偏宽(接近正方形) → diamonds/clubs；偏长(长方形) → hearts/spades
     * 3. 红色牌面 + 偏宽 → diamonds；红色牌面 + 偏长 → hearts
     *    黑色牌面 + 偏宽 → clubs；黑色牌面 + 偏长 → spades
     *
     * 注意: 本函数仅作为API不确定时的兜底补充，不替换API确定结果
     */
    private fun localSuitRecognize(cardBitmap: android.graphics.Bitmap?): LocalSuitResult? {
        if (cardBitmap == null || cardBitmap.width <= 0 || cardBitmap.height <= 0) return null
        return try {
            val width = cardBitmap.width
            val height = cardBitmap.height
            // 采样点数量限制，避免大图性能问题
            val sampleStep = maxOf(1, minOf(width, height) / 40)
            var redPixels = 0
            var blackPixels = 0
            var totalSampled = 0
            // 遍历采样像素，统计红/黑像素占比
            for (y in 0 until height step sampleStep) {
                for (x in 0 until width step sampleStep) {
                    val pixel = cardBitmap.getPixel(x, y)
                    val r = android.graphics.Color.red(pixel)
                    val g = android.graphics.Color.green(pixel)
                    val b = android.graphics.Color.blue(pixel)
                    val alpha = android.graphics.Color.alpha(pixel)
                    // 跳过透明或接近白色的背景像素
                    if (alpha < 128) continue
                    if (r > 240 && g > 240 && b > 240) continue
                    // 判断红色: R明显高于G和B，且R足够大
                    val isRed = r > 120 && r > g * 1.4f && r > b * 1.4f
                    // 判断黑色: RGB都偏低
                    val isBlack = r < 80 && g < 80 && b < 80
                    if (isRed) redPixels++
                    if (isBlack) blackPixels++
                    totalSampled++
                }
            }
            if (totalSampled == 0) return null
            val redRatio = redPixels.toFloat() / totalSampled
            val blackRatio = blackPixels.toFloat() / totalSampled
            // 判断主色系
            val isRedSuit = redRatio > blackRatio && redRatio > 0.08f
            val isBlackSuit = blackRatio > redRatio && blackRatio > 0.08f
            if (!isRedSuit && !isBlackSuit) return null
            // 形状辅助: 宽高比
            val aspectRatio = width.toFloat() / height.toFloat()
            // 扑克牌标准比例约 0.71 (宽/高=63.5/88.9)
            // 方块/梅花图案相对更"宽扁"，红桃/黑桃相对更"瘦长"
            // 这里用宽高比做辅助区分，阈值需要根据实际裁剪区域调整
            val isWideShape = aspectRatio > 0.75f
            // 计算置信度
            val colorConfidence = if (isRedSuit) {
                redRatio / (redRatio + blackRatio)
            } else {
                blackRatio / (redRatio + blackRatio)
            }
            val shapeConfidence = if (isWideShape) {
                (aspectRatio - 0.65f) / 0.2f  // 0.65→0, 0.85→1
            } else {
                (0.85f - aspectRatio) / 0.2f  // 0.85→0, 0.65→1
            }.coerceIn(0f, 1f)
            val totalConfidence = (colorConfidence * 0.7f + shapeConfidence * 0.3f).coerceIn(0f, 1f)
            // 最终推断
            val suit = when {
                isRedSuit && isWideShape -> "d"     // 方块 diamonds
                isRedSuit && !isWideShape -> "h"    // 红桃 hearts
                isBlackSuit && isWideShape -> "c"   // 梅花 clubs
                isBlackSuit && !isWideShape -> "s"  // 黑桃 spades
                else -> return null
            }
            Log.d(TAG, "本地suit识别: $suit 置信度=$totalConfidence (红=$redRatio 黑=$blackRatio 宽高比=$aspectRatio)")
            LocalSuitResult(suit, totalConfidence)
        } catch (e: Exception) {
            Log.w(TAG, "本地suit识别异常: ${e.message}")
            null
        }
    }

    /**
     * V2.9.230: 从完整截图中裁剪指定位置的卡牌区域
     * 用于本地suit识别时获取单张牌的bitmap
     * 位置使用百分比坐标 (xPct, yPct, wPct, hPct)，范围 0.0~1.0
     */
    private fun cropCardFromBitmap(bitmap: android.graphics.Bitmap, xPct: Float, yPct: Float, wPct: Float, hPct: Float): android.graphics.Bitmap? {
        return try {
            val x = (bitmap.width * xPct).toInt().coerceIn(0, bitmap.width - 1)
            val y = (bitmap.height * yPct).toInt().coerceIn(0, bitmap.height - 1)
            val w = (bitmap.width * wPct).toInt().coerceIn(1, bitmap.width - x)
            val h = (bitmap.height * hPct).toInt().coerceIn(1, bitmap.height - y)
            android.graphics.Bitmap.createBitmap(bitmap, x, y, w, h)
        } catch (e: Exception) {
            Log.w(TAG, "裁剪卡牌区域异常: ${e.message}")
            null
        }
    }

    /**
     * V2.9.230: suit置信度融合逻辑
     * 输入: API返回的suit, suit_uncertain标记, 本地推断的suit
     * 输出: Pair(最终suit, 是否使用了本地推断)
     *
     * 融合策略:
     * 1. API确定(suit_uncertain=false) → 直接用API结果，完全信任
     * 2. API不确定(suit_uncertain=true) → 用本地推断结果作为补充
     * 3. 本地推断也为null → 保持API原始suit（可能为空）
     */
    private fun mergeSuitResult(apiSuit: String, apiUncertain: Boolean, localResult: LocalSuitResult?): Pair<String, Boolean> {
        return try {
            // API确定 → 完全信任API，不使用本地推断
            if (!apiUncertain) {
                return Pair(apiSuit, false)
            }
            // API不确定，尝试用本地推断补充
            if (localResult != null && localResult.confidence >= 0.4f) {
                Log.d(TAG, "suit融合: API不确定(${apiSuit.ifEmpty { "空" }}), 本地推断=${localResult.suit}(置信度=${localResult.confidence})，采用本地结果")
                return Pair(localResult.suit, true)
            }
            // 本地推断也不可用，保持API原始结果
            Log.d(TAG, "suit融合: API不确定(${apiSuit.ifEmpty { "空" }}), 本地推断不可用，保持API结果")
            Pair(apiSuit, false)
        } catch (e: Exception) {
            Log.w(TAG, "suit融合异常: ${e.message}")
            Pair(apiSuit, false)
        }
    }

    /**
     * V2.9.230: 对VisionResult中suit不确定的牌应用本地suit识别融合
     * 输入: 原始VisionResult + 原始截图bitmap
     * 输出: 融合后的VisionResult（可能更新了suit和localSuitUsed标记）
     *
     * 注意: 只对suit_uncertain=true时的牌进行本地推断补充
     *       API确定的牌(suit_uncertain=false)完全保持不变
     */
    private fun applyLocalSuitFusion(result: VisionResult, screenshotBitmap: android.graphics.Bitmap?): VisionResult {
        if (screenshotBitmap == null) return result
        if (!result.suitUncertain) return result  // API全部确定，无需本地补充
        return try {
            var anyLocalUsed = false
            // 处理手牌
            val mergedHoleCards = result.holeCards.map { card ->
                if (card.suit.isEmpty() || result.suitUncertain) {
                    // 尝试从截图中裁剪手牌区域进行本地识别
                    // 注意: 由于缺少精确的卡牌位置坐标，这里使用启发式位置估算
                    // 手牌通常在屏幕底部中央区域，两张牌并排
                    // 实际应用中可以根据UI布局调整这些百分比参数
                    val cardBmp = try {
                        // 估算单张手牌位置（底部中央偏左/偏右）
                        // 这里只是示意位置，实际需要根据具体UI调整
                        val cardWidthPct = 0.08f
                        val cardHeightPct = 0.14f
                        val baseYPct = 0.78f
                        // 由于不知道具体是哪张牌，使用整个手牌区域近似
                        // 更精确的方案是将裁剪位置与card index对应
                        val xOffset = if (result.holeCards.size > 1) {
                            val centerX = 0.5f
                            val gap = 0.09f
                            centerX - gap / 2 + result.holeCards.indexOf(card) * gap - cardWidthPct / 2
                        } else {
                            0.5f - cardWidthPct / 2
                        }
                        cropCardFromBitmap(screenshotBitmap, xOffset.coerceIn(0f, 1f), baseYPct, cardWidthPct, cardHeightPct)
                    } catch (_: Exception) { null }
                    // P1-R4-5: try-finally保证Bitmap回收
                    try {
                        val localResult = localSuitRecognize(cardBmp)
                        val (mergedSuit, localUsed) = mergeSuitResult(card.suit, result.suitUncertain, localResult)
                        if (localUsed) anyLocalUsed = true
                        CardInfo(card.rank, mergedSuit)
                    } finally {
                        cardBmp?.recycle()
                    }
                } else card
            }
            // 处理公共牌（如果也标记为suit不确定）
            val mergedCommunityCards = result.communityCards.map { card ->
                if (card.suit.isEmpty() || result.suitUncertain) {
                    val cardBmp = try {
                        // 公共牌通常在屏幕中央区域
                        val cardWidthPct = 0.07f
                        val cardHeightPct = 0.12f
                        val centerYPct = 0.45f
                        val totalWidth = cardWidthPct * result.communityCards.size + 0.02f * (result.communityCards.size - 1)
                        val startXPct = 0.5f - totalWidth / 2
                        val cardIdx = result.communityCards.indexOf(card)
                        val xPct = startXPct + cardIdx * (cardWidthPct + 0.02f)
                        cropCardFromBitmap(screenshotBitmap, xPct.coerceIn(0f, 1f), centerYPct - cardHeightPct / 2, cardWidthPct, cardHeightPct)
                    } catch (_: Exception) { null }
                    // P1-R4-5: try-finally保证Bitmap回收
                    try {
                        val localResult = localSuitRecognize(cardBmp)
                        val (mergedSuit, localUsed) = mergeSuitResult(card.suit, result.suitUncertain, localResult)
                        if (localUsed) anyLocalUsed = true
                        CardInfo(card.rank, mergedSuit)
                    } finally {
                        cardBmp?.recycle()
                    }
                } else card
            }
            if (anyLocalUsed) {
                Log.d(TAG, "本地suit融合完成: 至少一张牌使用了本地推断结果")
                result.copy(holeCards = mergedHoleCards, communityCards = mergedCommunityCards, localSuitUsed = true)
            } else {
                result.copy(holeCards = mergedHoleCards, communityCards = mergedCommunityCards)
            }
        } catch (e: Exception) {
            Log.w(TAG, "本地suit融合异常: ${e.message}")
            result  // 失败时保持原始结果
        }
    }

    private fun validateResult(result: VisionResult): List<String> {
        val w = mutableListOf<String>(); val vr = setOf("A","K","Q","J","T","9","8","7","6","5","4","3","2")
        for (c in result.holeCards) { if (c.rank !in vr) w.add("无效点数:${c.rank}"); if (c.suit.isNotEmpty() && c.suit !in setOf("s","h","d","c")) w.add("无效花色:${c.suit}") }
        for (c in result.communityCards) { if (c.rank !in vr) w.add("公共牌无效点数:${c.rank}") }
        if (result.holeCards.size != 2) w.add("手牌数${result.holeCards.size}≠2")
        if (result.totalPlayers < 2 || result.totalPlayers > 20) w.add("人数${result.totalPlayers}异常")
        return w
    }

    private fun applyValidationCorrections(result: VisionResult): VisionResult {
        var c = result; val cor = mutableListOf<String>()
        if (c.activePlayers > c.totalPlayers) { c = c.copy(activePlayers = c.totalPlayers); cor.add("active>total") }
        if (c.activePlayers < 2) { c = c.copy(activePlayers = c.totalPlayers); cor.add("active<2") }
        if (c.totalPlayers < 2) { c = c.copy(totalPlayers = 6, activePlayers = 6); cor.add("total<2→6") }
        if (c.totalPlayers > 20) { c = c.copy(totalPlayers = 9, activePlayers = minOf(c.activePlayers, 9)); cor.add("total>20→9") }
        if (c.street.lowercase() in listOf("preflop","pre") && c.activePlayers < c.totalPlayers) { c = c.copy(activePlayers = c.totalPlayers); cor.add("preflop active=total") }
        if (c.potSize < 0) { c = c.copy(potSize = 0); cor.add("pot<0→0") }
        if (c.potSize == 0 && c.communityCards.isNotEmpty()) { val lp = lastResult?.potSize ?: 0; if (lp > 0) { c = c.copy(potSize = lp); cor.add("🔧翻后pot=0→用上轮pot=$lp") } else if (c.blindBB > 0) { val ep = c.blindBB * 3; c = c.copy(potSize = ep); cor.add("🔧翻后pot=0→BB*3=$ep") } }
        if (c.potSize > 0 && c.playerChips > 0 && c.potSize > c.playerChips * 5) { val sw = c.copy(potSize = c.playerChips, playerChips = c.potSize); c = sw; cor.add("🔧pot/chips互换") }
        val bb = if (c.blindBB > 0) c.blindBB else if (c.blindSB > 0) c.blindSB * 2 else 0
        if (bb > 0 && c.potSize > 0 && c.playerChips > 0 && c.potSize < c.playerChips && c.playerChips > bb * 3 && c.communityCards.isNotEmpty()) { c = c.copy(potSize = c.playerChips, playerChips = c.potSize); cor.add("🔧翻后pot/chips互换") }
        if (c.toCall < 0) { c = c.copy(toCall = 0); cor.add("to_call<0→0") }
        if (c.holeCards.size != 2) cor.add("⚠️手牌数${c.holeCards.size}≠2")
        if (cor.isNotEmpty()) { lastError = cor.joinToString("; "); Log.w(TAG, "校验纠错: $lastError") } else Log.d(TAG, "校验纠错: 无需纠正")
        return c
    }

    fun toJson(result: VisionResult): String {
        val warnings = validateResult(result)
        return JSONObject().apply {
            put("hole_cards", JSONArray(result.holeCards.map { JSONObject().apply { put("rank", it.rank); put("suit", it.suit) } }))
            put("community_cards", JSONArray(result.communityCards.map { JSONObject().apply { put("rank", it.rank); put("suit", it.suit) } }))
            put("pot_size", result.potSize); put("my_chips", result.playerChips); put("total_players", result.totalPlayers); put("active_players", result.activePlayers)
            put("my_position", result.myPosition); put("street", result.street); put("to_call", result.toCall); put("min_raise", result.minRaise)
            put("buttons", JSONArray(result.buttons)); put("blind_sb", result.blindSB); put("blind_bb", result.blindBB); put("ante", result.ante)
            put("button_positions", JSONArray(result.buttonPositions.map { JSONObject().apply { put("text", it.text); put("x_pct", it.xPct); put("y_pct", it.yPct) } }))
            put("players", JSONArray(result.players.map { JSONObject().apply { put("position", it.position); put("bet", it.bet); put("chips", it.chips); put("active", it.active); if(it.nickname.isNotEmpty()) put("nickname", it.nickname) } }))
            // V2.9.168: 同时输出opp_seats格式供JS OppProfiler使用
            put("opp_seats", JSONArray(result.players.map { p -> JSONObject().apply {
                val seatNum = when(p.position) { "bottom"->1; "left-bottom"->2; "left-top"->3; "top-center"->4; "right-top"->5; "right-bottom"->6; else->0 }
                val bbThreshold = if(result.blindBB > 0) result.blindBB * 3 else 600
                put("seat", seatNum)
                put("chips", p.chips.toString())
                put("bet", p.bet)
                put("stack", p.chips)
                put("action", if(p.bet > bbThreshold) "raise" else if(p.bet > 0) "call" else if(p.active) "" else "fold")
                put("active", p.active)
                if(p.nickname.isNotEmpty()) put("nickname", p.nickname)
            } }))
            put("is_poker_table", result.isPokerTable); put("d_button_position", result.dButtonPosition); put("suit_uncertain", result.suitUncertain); put("hole_cards_locked", holeCardsLocked != null); put("rank_locked", holeCardsRankLocked != null); put("rank_lock_values", holeCardsRankLocked?.joinToString(",") ?: ""); put("lock_reason", lockReason)
            put("prompt_mode", lastPromptMode)
            // V2.9.143: 摊牌信息
            if (result.showdownCards.isNotEmpty()) {
                put("showdown_cards", JSONArray(result.showdownCards.map { JSONObject().apply {
                    put("seat", it.seat)
                    put("cards", JSONArray(it.cards.map { c -> JSONObject().apply { put("rank", c.rank); put("suit", c.suit) } }))
                    put("won", it.won)
                } }))
            }
            if (result.oppHud.isNotEmpty()) { put("opp_hud", JSONArray(result.oppHud.map { JSONObject().apply { put("seat", it.seat); put("vpip", it.vpip); put("pfr", it.pfr); put("ats", it.ats); put("three_bet", it.threeBet) } })) }
            // V2.9.200: GG扑克特有字段
            put("platform", GameModeConfig.currentPlatform.name)
            put("is_straddle", result.isStraddle)
            put("is_bomb_pot", result.isBombPot)
            put("is_insurance", result.isInsurance)
            put("is_pko", result.isPKO)
            // V2.9.212: 游戏模式——现金桌/锦标赛
            put("game_mode", result.gameMode)
            // V2.9.xxx: 游戏类型与抽水上限（数据链补齐）
            put("game_type", result.gameType)
            put("rake_cap", result.rakeCap)
            // V2.9.220: 自动检测到的平台（仅供参考，不自动切换）
            put("detected_platform", result.detectedPlatform)
            // V2.9.230: 本地suit识别标记——供前端/JS引擎判断数据可信度
            put("local_suit_used", result.localSuitUsed)
            put("is_offline_fallback", isOfflineFallback && result.rawResponse.startsWith("OFFLINE_FALLBACK"))
            if (warnings.isNotEmpty()) put("_warnings", JSONArray(warnings))
        }.toString()
    }

    // V3.10: 弃牌/新一手重置所有锁定状态（供FloatingService调用）
    fun resetLocks() {
        holeCardsLocked = null
        holeCardsRankLocked = null
        streetLocked = null
        dButtonLocked = ""
        suitUncertain = false
    }

    // V2.9.320: 固定硅基流动，不再支持多供应商切换
    fun updateConfig(provider: String, key: String) {
        apiProvider = "siliconflow"
        apiKey = key
        apiUrl = "https://api.siliconflow.cn/v1/chat/completions"
        modelName = "Qwen/Qwen3-VL-8B-Instruct"
    }

    // V2.9.xxx: 多桌截图并行分析
    // 入参为各桌截图文件路径列表，返回与输入顺序一致的分析结果列表
    suspend fun analyzeMultipleScreenshots(screenshotPaths: List<String>): List<MultiTableResult> {
        // 空列表提前返回
        if (screenshotPaths.isEmpty()) {
            Log.d(TAG, "analyzeMultipleScreenshots: 截图列表为空，直接返回")
            return emptyList()
        }
        // 无API Key提前返回全失败
        if (apiKey.isEmpty()) {
            Log.w(TAG, "analyzeMultipleScreenshots: 未设置API Key，全部返回失败")
            return screenshotPaths.mapIndexed { index, _ ->
                MultiTableResult(
                    tableId = index,
                    result = null,
                    success = false,
                    errorMessage = "未设置API Key"
                )
            }
        }
        return try {
            // P1-fix: 多桌分析改串行——analyzeScreenshot修改单例共享状态(holeCardsLocked/lastResult等)
            // 并行执行会导致多表识别结果交叉污染
            screenshotPaths.mapIndexed { index, path ->
                analyzeSingleScreenshotSafe(index, path)
            }
        } catch (e: Exception) {
            Log.e(TAG, "analyzeMultipleScreenshots 整体异常: ${e.message}", e)
            screenshotPaths.mapIndexed { index, _ ->
                MultiTableResult(
                    tableId = index,
                    result = null,
                    success = false,
                    errorMessage = "整体异常: ${e.message}"
                )
            }
        }
    }

    // V2.9.xxx: 单桌截图安全分析（独立try-catch，不影响其他桌）
    private fun analyzeSingleScreenshotSafe(tableId: Int, screenshotPath: String): MultiTableResult {
        return try {
            // 校验文件存在性
            val file = java.io.File(screenshotPath)
            if (!file.exists()) {
                Log.w(TAG, "桌${tableId}文件不存在: $screenshotPath")
                return MultiTableResult(
                    tableId = tableId,
                    result = null,
                    success = false,
                    errorMessage = "文件不存在: $screenshotPath"
                )
            }
            // 读取文件字节
            val jpegData = file.readBytes()
            if (jpegData.isEmpty()) {
                Log.w(TAG, "桌${tableId}文件为空: $screenshotPath")
                return MultiTableResult(
                    tableId = tableId,
                    result = null,
                    success = false,
                    errorMessage = "文件为空: $screenshotPath"
                )
            }
            // 复用现有 analyzeScreenshot 逻辑
            val result = analyzeScreenshot(jpegData)
            if (result != null) {
                Log.d(TAG, "桌${tableId}分析成功")
                MultiTableResult(
                    tableId = tableId,
                    result = result,
                    success = true,
                    errorMessage = ""
                )
            } else {
                Log.w(TAG, "桌${tableId}分析失败: $lastError")
                MultiTableResult(
                    tableId = tableId,
                    result = null,
                    success = false,
                    errorMessage = lastError
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "桌${tableId}分析异常: ${e.message}", e)
            MultiTableResult(
                tableId = tableId,
                result = null,
                success = false,
                errorMessage = "异常: ${e.message}"
            )
        }
    }

    // ========================================
    // V2.9.516: 并发区域识别 V2 — 识别第一，按钮必识别
    // 2路并发：牌面（手牌+公共牌+底池，智能缓存）+ 操作区（按钮+筹码+D位置）
    // ========================================

    // 操作区识别结果
    private data class ActionAreaResult(
        val buttons: List<String>,
        val buttonPositions: List<ButtonPosition>,
        val toCall: Int,
        val myChips: Int,
        val dButtonSeat: Int,        // D按钮在哪个座位 0-5, -1=未检测到
        val activePlayers: Int,
        val isInsurance: Boolean,
        val rawResponse: String
    )

    /**
     * 并发区域识别 V2 — 核心方法
     * 2路并发：牌面（缓存优化）+ 操作区（每帧必识别）
     */
    fun analyzeScreenshotConcurrent(
        jpegData: ByteArray,
        screenWidth: Int = 1080,
        screenHeight: Int = 2344
    ): VisionResult? {
        if (apiKey.isEmpty()) { lastError = "未设置API Key"; return null }
        if (!analyzeLock.tryLock(25, java.util.concurrent.TimeUnit.SECONDS)) {
            Log.w(TAG, "analyzeLock获取超时(25s)，放弃本次分析")
            lastError = "分析锁超时"
            return null
        }
        try {
            return try {
                val t0 = System.currentTimeMillis()

                // 1. 解码截图
                val screenshotBmp = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size)
                if (screenshotBmp == null) {
                    lastError = "截图解码失败"
                    return null
                }

                RegionCropper.init(screenWidth, screenHeight)

                // ===== V2.9.518: 本地CV牌面识别（毫秒级）=====
                val localRecognizer = LocalCardRecognizer.getInstance(context)
                var localHoleCards: List<CardInfo>? = null
                var localCommCards: List<CardInfo>? = null
                var localMinConfidence = 0f  // V2.9.528: 本地CV手牌最低置信度
                var localCommConfOk = true   // V2.9.569: 公共牌置信度独立标志
                // V2.9.544: 分离操作区/牌面置信度阈值——操作区数字0.62+即正确，牌面黑色花色plateau分类天然0.50+
                val LOCAL_CONFIDENCE_THRESHOLD = 0.60f  // 操作区按钮金额阈值
                val CARD_CONFIDENCE_THRESHOLD = 0.50f   // 牌面阈值（黑色花色plateau_ratio置信度上限0.90）
                val LOCAL_LOW_CONFIDENCE = 0.50f
                val AMOUNT_CONFIDENCE_THRESHOLD = 0.60f

                try {
                    val tLocal = System.currentTimeMillis()
                    val (localHands, localComms) = localRecognizer.recognizeAllCards(screenshotBmp)
                    val localCVElapsed = System.currentTimeMillis() - tLocal
                    lastLocalCVTimeMs = localCVElapsed
                    if (localHands.size == 2) {
                        localHoleCards = localHands.map { CardInfo(it.rank, it.suit) }
                    }
                    // 公共牌：本地CV能稳定识别0-5张，空列表=翻前无公共牌也是有效结果
                    localCommCards = localComms.map { CardInfo(it.rank, it.suit) }

                    // V2.9.521: 本地CV失败时，保存截图和裁剪区域到存储供诊断
                    if (localHands.size < 2) {
                        try {
                            val diagDir = java.io.File(context.getExternalFilesDir(null), "diag")
                            diagDir.mkdirs()
                            val ts = System.currentTimeMillis()
                            // 保存全截图
                            java.io.File(diagDir, "screenshot_$ts.jpg").outputStream().use {
                                screenshotBmp.compress(Bitmap.CompressFormat.JPEG, 90, it)
                            }
                            // 保存手牌裁剪区域
                            val (handStitchDiag, _) = RegionCropper.cropHandCards(screenshotBmp)
                            handStitchDiag?.let {
                                java.io.File(diagDir, "handcrop_$ts.png").outputStream().use { out ->
                                    it.compress(Bitmap.CompressFormat.PNG, 100, out)
                                }
                            }
                            Log.w(TAG, "🔍 诊断截图已保存: ${diagDir.absolutePath}/screenshot_$ts.jpg")
                        } catch (e: Exception) {
                            Log.w(TAG, "诊断截图保存失败: ${e.message}")
                        }
                    }
                    val minCommConf = if (localComms.isNotEmpty()) localComms.minOf { it.confidence } else 1.0f
                    val minHandConf = if (localHands.isNotEmpty()) localHands.minOf { it.confidence } else 1.0f
                    // V2.9.569: 手牌和公共牌置信度独立判断，互不连坐
                    val handConfOk = localHands.size == 2 && minHandConf >= CARD_CONFIDENCE_THRESHOLD
                    val commConfOk = localComms.isEmpty() || minCommConf >= CARD_CONFIDENCE_THRESHOLD
                    localCommConfOk = commConfOk
                    localMinConfidence = minHandConf
                    if (handConfOk && commConfOk) {
                        Log.d(TAG, "🔍 本地CV HIGH: ${localCVElapsed}ms | " +
                                "hand=${localHands.map { "${it.rank}${it.suit}(${it.confidence})" }} | " +
                                "comm=${localComms.map { "${it.rank}${it.suit}(${it.confidence})" }} minHand=$minHandConf minComm=$minCommConf")
                    } else if (handConfOk && !commConfOk) {
                        // 手牌置信度够，公共牌置信度不够——丢弃公共牌，保留手牌
                        Log.w(TAG, "🔍 公共牌置信度过低(minComm=%.2f<0.50)，丢弃公共牌，手牌保留".format(minCommConf))
                        localCommCards = emptyList()
                    } else {
                        // 手牌置信度不够——丢弃手牌（公共牌也不可靠）
                        Log.w(TAG, "🔍 手牌置信度过低(minHand=%.2f<0.50)，丢弃，VLM兜底".format(minHandConf))
                        localHoleCards = null
                        localCommCards = emptyList()
                    }
                } catch (e: Exception) {
                    lastLocalCVTimeMs = 0
                    Log.w(TAG, "本地CV失败，将使用VLM: ${e.message}")
                }

                // V2.9.520: 缓存本地CV结果供DiagnosticLogger上报
                lastLocalCVEnabled = true
                lastLocalHandCards = localHoleCards ?: emptyList()
                lastLocalCommCards = localCommCards ?: emptyList()
                lastLocalDiag = localRecognizer.lastDiag

                // V2.9.544: 牌面用CARD_CONFIDENCE_THRESHOLD(0.50)，操作区用LOCAL_CONFIDENCE_THRESHOLD(0.60)
                val localHandOk = localHoleCards != null && localHoleCards!!.size == 2 &&
                        localMinConfidence >= CARD_CONFIDENCE_THRESHOLD
                // V2.9.569: 公共牌独立判断——手牌HIGH且公共牌置信度OK时才信任本地结果
                val localCommOk = localHandOk && localCommConfOk
                // LOW置信标志：本地有结果但置信度不够高，VLM返回空时回退本地
                val localHandLowFallback = localHoleCards != null && localHoleCards!!.size == 2 &&
                        localMinConfidence >= LOCAL_LOW_CONFIDENCE && !localHandOk

                // V2.9.526: 本地CV底池/筹码金额识别（毫秒级，成功则不调VLM底池API）
                var localPotValue: Long = 0L
                var localPotOk = false
                var localChipsValue: Int = 0
                var amountDiag = "skipped"
                // V2.9.554: 本地CV盲注识别（牌桌中央"100/200"白灰小字），作为inferredBB的兜底
                var localBlindSB = 0
                var localBlindBB = 0
                // V2.9.526: 检测是否轮到我行动（绿色进度条）
                val isMyTurn = try {
                    LocalActionRecognizer.getInstance(context).isMyTurn(screenshotBmp)
                } catch (_: Exception) { true }
                // V2.9.527: D按钮(庄位)本地CV识别（<0.5ms，6个小区域像素扫描）
                var dButtonSeatLocal = -1
                try {
                    val tDB = System.currentTimeMillis()
                    val dbRes = LocalActionRecognizer.getInstance(context).recognizeDButton(screenshotBmp)
                    if (dbRes.seat >= 0 && dbRes.confidence >= 0.3f) {
                        dButtonSeatLocal = dbRes.seat
                        Log.d(TAG, "🎲 D按钮本地CV: seat=${dbRes.seat} conf=%.2f %dms".format(dbRes.confidence, System.currentTimeMillis() - tDB))
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "D按钮本地CV失败: ${e.message}")
                }
                try {
                    val tAmt = System.currentTimeMillis()
                    val larInstance = LocalActionRecognizer.getInstance(context)
                    val potBmpLocal = RegionCropper.cropPotAmount(screenshotBmp)
                    val chipsBmp = RegionCropper.cropMyChips(screenshotBmp)
                    if (potBmpLocal != null) {
                        val potRes = larInstance.recognizeAmount(potBmpLocal, isPot = true)
                        if (potRes != null && potRes.value > 0 && potRes.confidence >= AMOUNT_CONFIDENCE_THRESHOLD) {
                            localPotValue = potRes.value
                            localPotOk = true
                            RegionCropper.updatePotCache(potBmpLocal, potRes.value)
                            Log.d(TAG, "💰 本地CV底池: ${System.currentTimeMillis() - tAmt}ms | pot=${potRes.value} conf=%.2f %s".format(potRes.confidence, potRes.diag))
                        } else {
                            amountDiag = "pot:${potRes?.diag ?: "null"}"
                            Log.w(TAG, "💰 本地CV底池失败(conf=%.2f<%s) %s".format(potRes?.confidence ?: 0f, AMOUNT_CONFIDENCE_THRESHOLD, potRes?.diag))
                        }
                    }
                    if (chipsBmp != null) {
                        val chipsRes = larInstance.recognizeAmount(chipsBmp, isPot = false)
                        if (chipsRes != null && chipsRes.value > 0 && chipsRes.confidence >= AMOUNT_CONFIDENCE_THRESHOLD) {
                            localChipsValue = chipsRes.value.toInt()
                            amountDiag += ";chips=${chipsRes.value}(c=%.2f)".format(chipsRes.confidence)
                            Log.d(TAG, "🪙 本地CV筹码: chips=${chipsRes.value} conf=%.2f %s".format(chipsRes.confidence, chipsRes.diag))
                        } else {
                            amountDiag += ";chips_fail:${chipsRes?.diag ?: "null"}"
                            Log.w(TAG, "🪙 本地CV筹码失败(conf=%.2f<%.2f) %s".format(chipsRes?.confidence ?: 0f, AMOUNT_CONFIDENCE_THRESHOLD, chipsRes?.diag))
                        }
                    }
                    RegionCropper.recycleBitmaps(potBmpLocal, chipsBmp)
                } catch (e: Exception) {
                    amountDiag = "exception:${e.message}"
                    Log.w(TAG, "本地CV底池/筹码失败，VLM兜底: ${e.message}")
                }

                // V2.9.539: 对手筹码本地CV识别（5个座位，纯像素模板匹配，~5ms）
                // seatIndex 0-4 对应 dZones seat 0,1,2,3,5（跳过seat4=Hero）
                val oppChipsMap = HashMap<Int, Int>()
                try {
                    val tOC = System.currentTimeMillis()
                    val larInstance = LocalActionRecognizer.getInstance(context)
                    val oppSeatIds = listOf(0, 1, 2, 3, 5)
                    for ((idx, seatId) in oppSeatIds.withIndex()) {
                        val oppBmp = RegionCropper.cropOpponentChips(screenshotBmp, idx)
                        if (oppBmp != null) {
                            val oppRes = larInstance.recognizeAmount(oppBmp, isPot = false)
                            if (oppRes != null && oppRes.value > 0 && oppRes.confidence >= AMOUNT_CONFIDENCE_THRESHOLD) {
                                oppChipsMap[seatId] = oppRes.value.toInt()
                            }
                            oppBmp.recycle()
                        }
                    }
                    if (oppChipsMap.isNotEmpty()) {
                        Log.d(TAG, "👥 对手筹码: ${oppChipsMap.size}/5 %dms".format(System.currentTimeMillis() - tOC))
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "对手筹码识别失败: ${e.message}")
                }

                // V2.9.554: 盲注本地CV识别（牌桌中央"德州扑克, 100/200"白灰小字，~5ms）
                // 独立try-catch，失败不影响主流程；结果作为inferredBB=0时的兜底
                try {
                    val blindBmp = RegionCropper.cropBlindText(screenshotBmp)
                    if (blindBmp != null) {
                        val (sbBlind, bbBlind) = LocalActionRecognizer.getInstance(context).recognizeBlinds(blindBmp)
                        blindBmp.recycle()
                        if (bbBlind > 0) {
                            localBlindBB = bbBlind
                            localBlindSB = sbBlind
                            Log.d(TAG, "🎯 本地CV盲注: SB=$localBlindSB BB=$localBlindBB")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "盲注识别失败: ${e.message}")
                }

                // 2. 裁剪操作区（每帧必识别）
                val actionBmp = RegionCropper.cropActionArea(screenshotBmp)
                val actionBase64 = actionBmp?.let { bitmapToBase64(it, quality = 80) }

                // V2.9.519: 本地CV操作区识别（毫秒级，成功则跳过VLM操作区API）
                var localAction: ActionAreaResult? = null
                var localPresets: List<Int> = emptyList()
                var actionDiag = "skipped"
                // V2.9.572 根因①: 非行动帧确认改用按钮行物理信号（黄色像素计数），
                //   旧v571用内容信号(无跟注额/无加注额/无预设)被非行动帧残影污染：
                //   18日志双证22帧btn2Y=btn3Y=0(按钮行物理不存在)却识别出presets=3:500,375,250假阳性
                //   →门控漏网→云VLM 3~24s+垃圾数据上游。物理判据：两行按钮区黄像素均<300
                //   (真按钮帧实测最小681，2倍余量；free-check帧btn3Y=1262/1583天然放行，不重演v566误杀)。
                var localNoActionWindow = false
                // V2.9.567: 移除v566过渡帧标志——本地CV无法区分过渡帧和free-check，判据误杀free-check让牌按钮
                try {
                    val tLA = System.currentTimeMillis()
                    val larInstance = LocalActionRecognizer.getInstance(context)
                    val lar = larInstance.recognizeAction(screenshotBmp)
                    actionDiag = larInstance.lastDiag
                    if (lar != null) {
                        localPresets = lar.presets
                        // V2.9.572: 物理判据——按钮行(跟注/让牌行+加注行)黄像素均<300=非行动窗口。
                        //   残影帧(btn2Y=587/btn3Y=1160等)物理信号>=300一律放行VLM兜底，宁慢勿漏；
                        //   presets/mr等内容信号一律不作门控依据(会被残影污染)。
                        if (lar.btn2Yellow < 300 && lar.btn3Yellow < 300) {
                            localNoActionWindow = true
                        }
                    }
                    // V2.9.573 闸1: useLocal物理一致性——按钮集合必须与物理行数严格一致：
                    //   A 三按钮(free-check/面对下注可加注)：加注行(btn3)物理存在(>=300黄像素) 且 mr OCR有效
                    //     (LocalActionRecognizer内mr已绑定btn3黄像素门控，minRaise!=null隐含canRaise)；
                    //   B 全押两按钮(弃牌+跟注)：跟注行(btn2)物理存在(facingBet=btn2>=100) 且 callAmount>0；
                    //   加注行不存在时绝不组"加注"按钮(16日志铁证: 全押帧btn3Y=0只有弃牌+跟注)。
                    val useLocal = isMyTurn && lar != null && lar.confidence >= LOCAL_CONFIDENCE_THRESHOLD && (
                        // A: 三按钮正常——加注行物理存在且mr有效(闸1门控已在LocalActionRecognizer内)
                        (lar.btn3Yellow >= LocalActionRecognizer.BTN_PHYSICAL_THRESHOLD && lar.minRaise != null) ||
                        // B: 全押两按钮——跟注行物理存在(btn2黄像素>=300硬判据)，且加注行物理不存在
                        (lar.facingBet && lar.btn2Yellow >= LocalActionRecognizer.BTN_PHYSICAL_THRESHOLD
                            && lar.callAmount != null && lar.callAmount > 0
                            && lar.btn3Yellow < LocalActionRecognizer.BTN_PHYSICAL_THRESHOLD)
                    )
                    if (lar != null && useLocal) {
                        val buttons = mutableListOf<String>()
                        buttons.add("弃牌")
                        if (lar.facingBet && lar.callAmount != null && lar.callAmount > 0) {
                            buttons.add("跟注 ${lar.callAmount}")
                        } else {
                            buttons.add("让牌")
                        }
                        if (lar.minRaise != null) {
                            buttons.add("加注 ${lar.minRaise}")
                        }
                        val positions = mapButtonsToPositions(buttons)
                        localAction = ActionAreaResult(
                            buttons = buttons,
                            buttonPositions = positions,
                            toCall = lar.callAmount ?: 0,
                            myChips = localChipsValue,
                            dButtonSeat = dButtonSeatLocal,
                            activePlayers = oppChipsMap.size + 1,
                            isInsurance = false,
                            rawResponse = "local: fb=${lar.facingBet} call=${lar.callAmount} mr=${lar.minRaise} p=${lar.presets} btns=${buttons.size} c=%.2f".format(lar.confidence)
                        )
                        actionDiag += ";USED(btns=${buttons.size})"
                        Log.d(TAG, "🔍 本地CV操作区: ${System.currentTimeMillis() - tLA}ms | " +
                                "fb=${lar.facingBet} call=${lar.callAmount} mr=${lar.minRaise} " +
                                "presets=${lar.presets} btns=${buttons.size} conf=%.2f".format(lar.confidence))
                    } else if (lar != null) {
                        actionDiag += ";LOW_CONF(%.2f<%s)".format(lar.confidence, LOCAL_CONFIDENCE_THRESHOLD)
                        // V2.9.567 FIX(P0): 移除v566过渡帧判据——本地CV无法区分过渡帧和free-check局面
                        //   （两者特征完全相同：fb=0无跟注按钮/mr=None无加注额/presets非空底池百分比），
                        //   v566判据(!fb && mr==null && presets非空)完美匹配free-check→误跳过VLM→让牌按钮永远找不到。
                        //   v566日志铁证：13:17:34-13:17:54连续5帧free-check跳VLM(buttons=[])，
                        //   而13:17:26同一手VLM正确返回['弃牌','让牌','加注 400']。
                        //   过渡帧让VLM兜底（返回空按钮=等下帧），free-check让VLM识别（返回让牌按钮）→两者都正确。
                        //   性能代价：少数过渡帧多一次VLM调用(2~23s)，但free-check局面更常见且影响更大。
                        Log.w(TAG, "🔍 本地CV操作区置信度不足(conf=%.2f<%.2f)，VLM兜底".format(lar.confidence, LOCAL_CONFIDENCE_THRESHOLD))
                    } else {
                        actionDiag += ";NULL_RESULT"
                    }
                } catch (e: Exception) {
                    actionDiag = "exception:${e.message}"
                    Log.w(TAG, "本地CV操作区失败，VLM兜底: ${e.message}")
                }
                // V2.9.524: 将操作区诊断追加到手牌diag
                lastLocalDiag = "${lastLocalDiag}|ACT:$actionDiag|AMT:$amountDiag,pot=$localPotOk,chips=$localChipsValue"

                // 3. 裁剪牌面（本地CV已识别手牌/公共牌，仅VLM兜底时需要）
                val needHandApi = !localHandOk
                val needBoardApi = needHandApi || !localCommOk

                val (handStitch, handBitmaps) = RegionCropper.cropHandCards(screenshotBmp)
                val (boardMerged, commBitmaps, potBmp) = RegionCropper.cropBoardArea(screenshotBmp)

                val t1 = System.currentTimeMillis()
                Log.d(TAG, "⏱ 区域裁剪: ${t1 - t0}ms")

                // 4. 手牌缓存检查（本地CV成功时跳过）
                var holeCards: List<CardInfo>? = if (localHandOk) localHoleCards else RegionCropper.checkHandCache(handStitch)
                var needHandApiFinal = holeCards == null
                val handHash = if (needHandApiFinal && handStitch != null) RegionCropper.bitmapHash(handStitch) else null
                if (needHandApiFinal) {
                    Log.d(TAG, "♠ 手牌需API识别 (本地CV=${if (localHandOk) "OK" else "失败"})")
                }

                // 5. 新手牌检测
                if (needHandApiFinal && RegionCropper.isNewHand(handHash)) {
                    RegionCropper.clearBoardCache()
                    // V2.9.569: 新手牌时清空手牌锁定，防止旧手锁定值跨手污染
                    holeCardsLocked = null
                    holeCardsRankLocked = null
                    streetLocked = null
                    Log.d(TAG, "🆕 新手牌检测到，公共牌/底池/手牌锁定已清空")
                }

                // 6. 公共牌增量检查（本地CV成功时直接使用本地结果）
                val newCommIndices = if (localCommOk) emptyList() else RegionCropper.findNewCommCards(commBitmaps)
                val newCommHashes = newCommIndices.map { idx ->
                    RegionCropper.bitmapHash(commBitmaps.getOrNull(idx))
                }

                // 7. 底池缓存检查（V2.9.526: 本地CV底池成功时已updatePotCache，缓存应命中）
                var potValue = RegionCropper.checkPotCache(potBmp)
                // 本地CV成功时直接使用本地值，不调VLM底池API
                if (localPotOk) potValue = localPotValue
                var needPotApi = potValue == null
                val potHash = if (needPotApi && potBmp != null) RegionCropper.bitmapHash(potBmp) else null

                // 7.5 构建牌面API图（仅在本地CV失败时）
                // V2.9.571: ★ 非行动帧门控（v570实机21慢帧根因）★
                //   慢帧全在非行动窗口（本地btn2Y=0无按钮+LOW_CONF）：手牌区动画/切桌hash抖动→
                //   缓存未命中→云VLM 3~24s，占着链路拖累后续行动帧；且VLM对非行动帧回读的是
                //   旧手残影牌+错乱pot（JS侧pot×2/3误锁垃圾BB，v570两次fold误点check白输钱的上游）。
                //   V2.9.572门控：物理双行无按钮(btn2Y<300&btn3Y<300，22帧漏网残影全拦,
                //   真按钮帧最小681/free-check帧1262+零误杀)且本手已有锁定手牌 → 跳过牌面+操作区
                //   两个云VLM，行动数据沿用锁定手牌+公牌/底池缓存。新一手（isNewHand已清锁）不触发。
                val skipCloudVlm = localNoActionWindow && localAction == null &&
                        holeCardsLocked != null && holeCardsLocked!!.size == 2
                if (skipCloudVlm) {
                    Log.w(TAG, "⏭ V2.9.572非行动帧: 按钮行物理不存在(btn2Y/btn3Y<300)+手牌已锁定→跳过云VLM(沿用锁定/缓存)")
                }
                val boardParts = mutableListOf<Bitmap>()
                if (!skipCloudVlm && needHandApiFinal && handStitch != null) boardParts.add(handStitch)
                val newCommBitmaps = newCommIndices.mapNotNull { idx -> commBitmaps.getOrNull(idx) }
                if (newCommBitmaps.isNotEmpty()) {
                    val newCommStitch = stitchBitmapsHorizontally(newCommBitmaps, gap = 6)
                    if (newCommStitch != null) boardParts.add(newCommStitch)
                }
                if (needPotApi && potBmp != null) boardParts.add(potBmp)

                val boardApiBitmap = if (boardParts.size > 1) {
                    stitchBitmapsVertically(boardParts, gap = 8)
                } else boardParts.firstOrNull()
                val boardBase64 = boardApiBitmap?.let { bitmapToBase64(it, quality = 75) }

                val t2 = System.currentTimeMillis()
                Log.d(TAG, "⏱ 缓存+编码: ${t2 - t1}ms (本地CV hand=$localHandOk comm=$localCommOk, 手API=$needHandApiFinal, 新公共牌=${newCommIndices.size}张, 底池API=$needPotApi)")
                Log.d(TAG, "🔍 boardBase64=${if(boardBase64!=null) "${boardBase64.length/1024}KB" else "null"} handStitch=${handStitch?.width}x${handStitch?.height} boardParts=${boardParts.size}")

                // 8. 释放中间bitmap（保留screenshotBmp直到不需要）
                // boardApiBitmap在多部件拼接时是新建bitmap，需回收；单部件时与boardParts元素同一对象，isRecycled检查保证安全
                RegionCropper.recycleBitmaps(boardApiBitmap)
                RegionCropper.recycleBitmaps(*boardParts.toTypedArray())
                RegionCropper.recycleBitmaps(actionBmp, boardMerged, potBmp)
                handBitmaps.forEach { RegionCropper.recycleBitmaps(it) }
                commBitmaps.forEach { RegionCropper.recycleBitmaps(it) }
                RegionCropper.recycleBitmaps(handStitch)
                screenshotBmp.recycle()

                // 9. 并发API调用（牌面仅本地CV失败时才调API）
                var boardResult: BoardRecognitionResult? = null
                var actionResult: ActionAreaResult? = null

                runBlocking {
                    coroutineScope {
                        val boardJob = if (!skipCloudVlm && boardBase64 != null && needBoardApi) {
                            async(Dispatchers.IO) { recognizeBoardArea(boardBase64, needHandApiFinal, newCommIndices.size, needPotApi) }
                        } else null
                        val actionJob = if (!skipCloudVlm && actionBase64 != null && localAction == null) {
                            async(Dispatchers.IO) { recognizeActionArea(actionBase64) }
                        } else null

                        boardResult = boardJob?.await()
                        actionResult = actionJob?.await()
                    }
                }

                val t3 = System.currentTimeMillis()
                Log.d(TAG, "⏱ 并发API: ${t3 - t2}ms (牌面=${boardResult != null}, 操作区=${actionResult != null}, 本地CV=hand:$localHandOk/comm:$localCommOk)")

                // 10. 更新缓存
                val board = boardResult
                if (board != null) {
                    if (needHandApiFinal && board.handCards.size == 2 && handHash != null) {
                        RegionCropper.updateHandCacheWithHash(handHash, board.handCards)
                        holeCards = board.handCards
                    }
                    for ((i, idx) in newCommIndices.withIndex()) {
                        if (i < board.commCards.size && i < newCommHashes.size) {
                            RegionCropper.updateCommCacheWithHash(idx, newCommHashes[i], board.commCards[i])
                        }
                    }
                    if (needPotApi && board.potAmount > 0 && potHash != null) {
                        RegionCropper.updatePotCacheWithHash(potHash, board.potAmount)
                        potValue = board.potAmount
                    }
                }

                // 11. 获取最终牌面数据（V2.9.528: 三级优先级 + LOW回退）
                var handFromLockedFallback = false  // V2.9.570: 标记手牌是否来自locked回退，公共牌需成套回退缓存
                val finalHoleCards = when {
                    localHandOk -> localHoleCards!!
                    holeCards != null -> holeCards!!                        // 缓存命中
                    board != null && board.handCards.size == 2 -> board.handCards  // VLM返回完整手牌
                    localHandLowFallback -> {
                        Log.w(TAG, "🔍 VLM空手牌，回退本地CV LOW结果: ${localHoleCards!!.map{"${it.rank}${it.suit}"}}")
                        localHoleCards!!
                    }
                    // V2.9.569/570: 本地CV+VLM双失败时，回退之前帧已锁定的手牌（截图过渡帧兜底）
                    // 必须在"board != null -> board.handCards"之前：VLM解析失败也会返回空手牌的非空对象，
                    // 放后面会被短路导致回退永不生效（22:15:57实机帧 holeCardsLocked=true 但final手牌为空）
                    holeCardsLocked != null && holeCardsLocked!!.size == 2 -> {
                        handFromLockedFallback = true
                        Log.w(TAG, "🔍 本地CV+VLM双失败，回退锁定手牌: ${holeCardsLocked!!.map{"${it.rank}${it.suit}"}}")
                        holeCardsLocked!!
                    }
                    board != null -> board.handCards                        // VLM有部分结果（空/1张）
                    else -> emptyList()
                }
                val finalCommCards = when {
                    localCommOk -> localCommCards!!
                    // V2.9.570: 手牌走锁定回退的帧=截图过渡帧，公共牌必须成套回退缓存（上一VLM成功帧局面），
                    // 否则公共牌空→determineStreet误判preflop→postflop局面按preflop决策，
                    // 且streetLocked被错误覆盖为preflop连锁污染后续帧。缓存与锁定手牌同手（fold/新一手同时清锁清缓存）
                    handFromLockedFallback -> {
                        val cachedComm = RegionCropper.getCachedCommunityCards()
                        Log.w(TAG, "🔍 手牌锁定回退帧，公共牌成套沿用缓存(${cachedComm.size}张)保证街/牌面一致")
                        cachedComm
                    }
                    newCommIndices.isEmpty() && !needHandApiFinal -> RegionCropper.getCachedCommunityCards()
                    localHandLowFallback && (board == null || board.commCards.isEmpty()) -> localCommCards ?: emptyList()
                    else -> mergeCommCards(newCommIndices, board?.commCards ?: emptyList())
                }
                val finalPot = (potValue ?: board?.potAmount ?: 0L).toInt()

                // 12. 构建结果（本地CV操作区优先）
                val action = localAction ?: actionResult
                val finalToCall = action?.toCall ?: 0
                val finalMinRaise = action?.let { a ->
                    a.buttons.firstOrNull { it.contains("加注") }
                        ?.replace(Regex("[^0-9]"), "")?.toIntOrNull() ?: 0
                } ?: 0

                // V2.9.526: 盲注推断。
                // 首选：翻前预设快捷按钮和主加注额都是BB整数倍，取GCD即为BB（能抗有人open/3bet/straddle）。
                // 兜底：预设缺失且无人加注时，主加注额=2BB；用toCall校验BB位/SB位/未入场位。
                val isPreflop = finalHoleCards.size == 2 && finalCommCards.isEmpty()
                var inferredBB = 0
                var inferredSB = 0
                if (isPreflop && finalMinRaise > 0) {
                    fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
                    val gcdInputs = (localPresets.filter { it > 0 } + finalMinRaise).distinct()
                    var g = gcdInputs.first()
                    for (v in gcdInputs.drop(1)) g = gcd(g, v)

                    if (g > 0 && finalMinRaise % g == 0 && finalMinRaise / g >= 2) {
                        inferredBB = g
                    } else if (finalMinRaise % 2 == 0) {
                        val candidateBB = finalMinRaise / 2
                        if (finalToCall == 0 || finalToCall == candidateBB || finalToCall * 2 == candidateBB) {
                            inferredBB = candidateBB
                        }
                    }
                    if (inferredBB > 0 && inferredBB % 2 == 0) inferredSB = inferredBB / 2
                }

                val result = VisionResult(
                    isPokerTable = finalHoleCards.size == 2,
                    holeCards = finalHoleCards,
                    communityCards = finalCommCards,
                    potSize = finalPot,
                    // V2.9.526: 本地CV筹码优先，VLM操作区次之，都没有则0
                    playerChips = if (localChipsValue > 0) localChipsValue else (action?.myChips ?: 0),
                    totalPlayers = 6,
                    activePlayers = oppChipsMap.size + 1,
                    myPosition = "",
                    street = determineStreet(finalCommCards),
                    toCall = finalToCall,
                    minRaise = finalMinRaise,
                    buttons = action?.buttons ?: emptyList(),
                    // V2.9.554: 盲注三级——本地GCD推断(翻前加注时最准) > 本地CV盲注文字(每帧可读) > 0
                    blindSB = if (inferredSB > 0) inferredSB else localBlindSB,
                    blindBB = if (inferredBB > 0) inferredBB else localBlindBB,
                    ante = 0,
                    players = buildOppPlayerList(oppChipsMap, localChipsValue, dButtonSeatLocal),
                    dButtonPosition = mapDSeatToPosition(
                        if (dButtonSeatLocal >= 0) dButtonSeatLocal else (action?.dButtonSeat ?: -1)
                    ),
                    rawResponse = "V2: board=${boardResult?.rawResponse?.take(100)} | action=${action?.rawResponse?.take(100)}",
                    showdownCards = emptyList(),
                    oppHud = emptyList(),
                    buttonPositions = action?.buttonPositions ?: emptyList(),
                    suitUncertain = false,
                    isStraddle = false,
                    isBombPot = false,
                    isInsurance = action?.isInsurance ?: false,
                    isPKO = false,
                    gameMode = "cash",
                    detectedPlatform = "GGPOKER",
                    localSuitUsed = false,
                    isMyTurn = isMyTurn
                )

                // V2.9.540: 动态玩家追踪——对比前后帧，检测谁加入/谁离场
                val currentSeats = (oppChipsMap.keys + 4).toSet()  // oppChipsMap的keys + Hero(seat4)
                val joined = currentSeats - prevPlayerSeats
                val left = prevPlayerSeats - currentSeats
                lastJoinedSeats = joined.toList().sorted()
                lastLeftSeats = left.toList().sorted()
                seatedPlayerCount = currentSeats.size
                prevPlayerSeats = currentSeats
                if (joined.isNotEmpty() || left.isNotEmpty()) {
                    Log.d(TAG, "🪑 玩家变化: 在座=$seatedPlayerCount 加入=$lastJoinedSeats 离场=$lastLeftSeats")
                }

                // 同步锁定状态
                if (finalHoleCards.size == 2) {
                    holeCardsLocked = finalHoleCards
                    streetLocked = result.street
                }
                if (result.dButtonPosition.isNotEmpty() && result.dButtonPosition != "not_found") {
                    applyDButtonInsurance(result.dButtonPosition, finalHoleCards)
                }

                val t4 = System.currentTimeMillis()
                Log.i(TAG, "⏱ V2识别完成: ${t4 - t0}ms | 手牌=${finalHoleCards.map { "${it.rank}${it.suit}" }} 公共牌=${finalCommCards.size}张 底池=$finalPot 按钮=[${action?.buttons?.joinToString(",")}] toCall=${action?.toCall} chips=${action?.myChips} D=${result.dButtonPosition}")

                lastResult = result
                lastResultTime = System.currentTimeMillis()
                lastError = ""
                result
            } catch (e: Exception) {
                lastError = "V2识别异常: ${e.message}"
                Log.e(TAG, "analyzeScreenshotConcurrent V2 failed", e)
                null
            }
        } finally {
            analyzeLock.unlock()
        }
    }

    // 牌面API识别结果
    private data class BoardRecognitionResult(
        val handCards: List<CardInfo>,
        val commCards: List<CardInfo>,
        val potAmount: Long,
        val rawResponse: String
    )

    /**
     * 识别牌面区域（手牌+新公共牌+底池，根据需要组合）
     */
    private suspend fun recognizeBoardArea(
        base64Image: String,
        hasHand: Boolean,
        newCommCount: Int,
        hasPot: Boolean
    ): BoardRecognitionResult? {
        return withContext(Dispatchers.IO) {
            try {
                val sb = StringBuilder("识别扑克牌面，输出JSON。\n")
                if (hasHand) sb.append("上方是2张手牌，输出hand_cards数组。\n")
                if (newCommCount > 0) sb.append("中间是${newCommCount}张新出现的公共牌，按从左到右顺序输出community_cards数组。\n")
                if (hasPot) sb.append("下方是底池金额数字，输出pot数字（去掉逗号）。\n")
                sb.append("""格式：{"hand_cards":[{"rank":"A","suit":"s"}],"community_cards":[{"rank":"Q","suit":"d"}],"pot":13271}
花色：s=♠ h=♥ d=♦ c=♣。rank: A,K,Q,J,10,9-2。
只输出JSON：""")

                val requestJson = buildSimpleRequest(base64Image, sb.toString(), maxTokens = 300)
                val raw = sendRequest(requestJson)
                Log.d(TAG, "🔍 牌面API原始响应(${raw.length}字): ${raw.take(300)}")
                val jsonStr = extractJson(raw) ?: run {
                    Log.w(TAG, "🔍 牌面API: JSON提取失败")
                    return@withContext null
                }
                val json = JSONObject(jsonStr)

                val handCards = parseCards(json.optJSONArray("hand_cards"))
                val commCards = parseCards(json.optJSONArray("community_cards"))
                val pot = json.optLong("pot", 0)
                Log.d(TAG, "🔍 牌面API结果: hand=${handCards.map{"${it.rank}${it.suit}"}} comm=${commCards.size}张 pot=$pot")

                BoardRecognitionResult(handCards, commCards, pot, raw)
            } catch (e: Exception) {
                Log.e(TAG, "牌面识别失败: ${e.message}")
                null
            }
        }
    }

    /**
     * 识别操作区（按钮+筹码+D位置+活跃玩家）
     */
    private suspend fun recognizeActionArea(base64Image: String): ActionAreaResult? {
        return withContext(Dispatchers.IO) {
            try {
                val prompt = """这是德州扑克手机游戏底部操作区截图。识别并输出JSON：
{"buttons":["按钮文字"],"to_call":数字,"my_chips":数字,"d_seat":0-5或-1,"active_players":数字,"is_insurance":布尔}
- buttons: 底部所有可见操作按钮的文字，如"弃牌","跟注 500","加注","让牌","全下"。必须完整识别按钮上的文字和数字。
- to_call: 需要跟注的金额（从"跟注 XXX"按钮文字中提取数字），如果是"让牌"则0。
- my_chips: 自己的剩余筹码数字（左下角头像旁边）。
- d_seat: Dealer(D按钮)在哪个座位。自己=0，正上方=1，右上=2，右下=3，左下对面=4，左上=5。没看到D按钮输出-1。
- active_players: 还在牌局中的玩家数量（数头像/座位）。
- is_insurance: 是否出现Insurance或Cashout按钮。
只输出JSON："""

                val requestJson = buildSimpleRequest(base64Image, prompt, maxTokens = 350)
                val raw = sendRequest(requestJson)
                val jsonStr = extractJson(raw) ?: return@withContext null
                val json = JSONObject(jsonStr)

                val buttonsArr = json.optJSONArray("buttons")
                val buttons = mutableListOf<String>()
                if (buttonsArr != null) {
                    for (i in 0 until buttonsArr.length()) {
                        buttons.add(buttonsArr.getString(i))
                    }
                }

                val toCall = json.optInt("to_call", 0)
                val myChips = json.optInt("my_chips", 0)
                val dSeat = json.optInt("d_seat", -1)
                val activePlayers = json.optInt("active_players", 2)
                val isInsurance = json.optBoolean("is_insurance", false)

                // 映射按钮文字到固定坐标（GG竖屏布局）
                val buttonPositions = mapButtonsToPositions(buttons)

                ActionAreaResult(buttons, buttonPositions, toCall, myChips, dSeat, activePlayers, isInsurance, raw)
            } catch (e: Exception) {
                Log.e(TAG, "操作区识别失败: ${e.message}")
                null
            }
        }
    }

    /**
     * 将按钮文字映射到GG竖屏固定坐标
     * 坐标基于GameModeConfig实测：fold=18.1%, check/call=50%, raise=81.9%, y=96.0%
     */
    private fun mapButtonsToPositions(buttons: List<String>): List<ButtonPosition> {
        val positions = mutableListOf<ButtonPosition>()
        var rightSlotUsed = false
        // V2.9.526: 两按钮（弃牌+跟注，全押/短筹码场景）时，弃牌占左1/3，跟注占右2/3
        // 实测1080宽：弃牌中心x≈180(0.167)，跟注中心x≈660(0.611)
        val isTwoBtnCallAllin = buttons.size == 2 &&
            buttons.any { it.contains("弃牌") } &&
            buttons.any { it.contains("跟注") }

        for (btn in buttons) {
            val b = btn.trim()
            when {
                // 弃牌 — 两按钮时占左1/3；否则左1/4
                b.contains("弃牌") || b.contains("fold", ignoreCase = true) -> {
                    positions.add(ButtonPosition(b, if (isTwoBtnCallAllin) 0.167 else 0.181, 0.974))
                }
                // 让牌/过牌 — 中
                b.contains("让牌") || b.contains("过牌") || b.equals("check", ignoreCase = true) -> {
                    positions.add(ButtonPosition(b, 0.500, 0.974))
                }
                // 跟注 — 全押两按钮场景占右侧宽按钮；否则中
                b.contains("跟注") || b.contains("call", ignoreCase = true) -> {
                    if (isTwoBtnCallAllin) {
                        positions.add(ButtonPosition(b, 0.611, 0.974))
                    } else {
                        positions.add(ButtonPosition(b, 0.500, 0.974))
                    }
                }
                // 加注/下注 — 右
                (b.contains("加注") || b.contains("下注") || b.contains("bet", ignoreCase = true) ||
                     b.contains("raise", ignoreCase = true)) && !b.contains("%") -> {
                    positions.add(ButtonPosition(b, 0.819, 0.974))
                    rightSlotUsed = true
                }
                // 全下/全押
                b.contains("全下") || b.contains("全押") || b.contains("all in", ignoreCase = true) -> {
                    positions.add(ButtonPosition(b, 0.819, 0.751))
                }
                // 下注预设按钮 — 右侧竖排，从上到下
                b.contains("100%") || b.contains("100％") -> {
                    positions.add(ButtonPosition(b, 0.819, 0.751))
                }
                b.contains("75%") || b.contains("75％") -> {
                    positions.add(ButtonPosition(b, 0.819, 0.821))
                }
                b.contains("50%") || b.contains("50％") -> {
                    positions.add(ButtonPosition(b, 0.819, 0.890))
                }
                b.contains("33%") || b.contains("33％") -> {
                    positions.add(ButtonPosition(b, 0.819, 0.937))
                }
                // 其他按钮（Insurance/Cashout等）— 放到右侧
                else -> {
                    if (!rightSlotUsed) {
                        positions.add(ButtonPosition(b, 0.819, 0.974))
                        rightSlotUsed = true
                    } else {
                        positions.add(ButtonPosition(b, 0.500, 0.974))
                    }
                }
            }
        }
        return positions
    }

    /**
     * D按钮座位号映射到位置字符串（与旧逻辑兼容）
     */
    private fun mapDSeatToPosition(seat: Int): String {
        // Kotlin座位编号→JS位置名称（必须与poker_helper.html seatMap一致）
        // seat0=左上, seat1=正上, seat2=右上, seat3=右中, seat4=正下(hero), seat5=左中
        return when (seat) {
            0 -> "left-top"
            1 -> "top-center"
            2 -> "right-top"
            3 -> "right-bottom"
            4 -> "bottom-center"
            5 -> "left-bottom"
            else -> ""
        }
    }

    /**
     * V2.9.539: 根据本地CV识别的对手筹码构建PlayerInfo列表
     * @param oppChipsMap seatId→筹码值（仅含识别成功的座位）
     * @param myChips 自己的筹码
     * @param dSeat D按钮座位号
     */
    private fun buildOppPlayerList(
        oppChipsMap: Map<Int, Int>,
        myChips: Int,
        dSeat: Int
    ): List<PlayerInfo> {
        val players = mutableListOf<PlayerInfo>()
        // 按seat顺序0-5构建
        for (seat in 0..5) {
            val pos = mapDSeatToPosition(seat)
            if (seat == 4) {
                // Hero自己
                if (myChips > 0) {
                    players.add(PlayerInfo(pos, 0, myChips, true))
                }
            } else {
                val chips = oppChipsMap[seat]
                if (chips != null && chips > 0) {
                    // 有筹码的座位视为活跃玩家
                    players.add(PlayerInfo(pos, 0, chips, true))
                }
            }
        }
        return players
    }

    /**
     * 合并缓存的公共牌和新识别的公共牌
     * 缓存数组按格位0-4存储，新识别结果按newIndices顺序对应
     */
    private fun mergeCommCards(newIndices: List<Int>, newCards: List<CardInfo>): List<CardInfo> {
        val merged = RegionCropper.getCachedCommunitySlots()
        for ((i, idx) in newIndices.withIndex()) {
            if (i < newCards.size && idx in merged.indices) {
                merged[idx] = newCards[i]
            }
        }
        return merged.filterNotNull()
    }

    private fun stitchBitmapsHorizontally(bitmaps: List<Bitmap>, gap: Int = 8): Bitmap? {
        if (bitmaps.isEmpty()) return null
        if (bitmaps.size == 1) return bitmaps[0]
        val totalWidth = bitmaps.sumOf { it.width } + gap * (bitmaps.size - 1)
        val maxHeight = bitmaps.maxOf { it.height }
        return try {
            val result = Bitmap.createBitmap(totalWidth, maxHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(result)
            var offsetX = 0
            for (bmp in bitmaps) {
                canvas.drawBitmap(bmp, offsetX.toFloat(), 0f, null)
                offsetX += bmp.width + gap
            }
            result
        } catch (e: Exception) { null }
    }

    private fun stitchBitmapsVertically(bitmaps: List<Bitmap>, gap: Int = 8): Bitmap? {
        if (bitmaps.isEmpty()) return null
        if (bitmaps.size == 1) return bitmaps[0]
        val totalHeight = bitmaps.sumOf { it.height } + gap * (bitmaps.size - 1)
        val maxWidth = bitmaps.maxOf { it.width }
        return try {
            val result = Bitmap.createBitmap(maxWidth, totalHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(result)
            var offsetY = 0
            for (bmp in bitmaps) {
                canvas.drawBitmap(bmp, 0f, offsetY.toFloat(), null)
                offsetY += bmp.height + gap
            }
            result
        } catch (e: Exception) { null }
    }

    // ========================================
    // 通用工具
    // ========================================

    private fun buildSimpleRequest(base64Image: String, prompt: String, maxTokens: Int = 200): String {
        return JSONObject().apply {
            put("model", modelName)
            put("max_tokens", maxTokens)
            put("temperature", 0.0)
            put("response_format", JSONObject().put("type", "json_object"))
            put("messages", JSONArray().apply { put(JSONObject().apply {
                put("role", "user"); put("content", JSONArray().apply {
                    put(JSONObject().apply { put("type", "text"); put("text", prompt) })
                    put(JSONObject().apply { put("type", "image_url"); put("image_url", JSONObject().apply { put("url", "data:image/jpeg;base64,$base64Image"); put("detail", "low") }) })
                })
            }) })
        }.toString()
    }

    private fun bitmapToBase64(bitmap: Bitmap, quality: Int = 80): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
        val bytes = outputStream.toByteArray()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun determineStreet(communityCards: List<CardInfo>): String {
        return when (communityCards.size) {
            0 -> "preflop"
            3 -> "flop"
            4 -> "turn"
            5 -> "river"
            else -> "preflop"  // 容错：1-2张异常情况按preflop处理
        }
    }
}
