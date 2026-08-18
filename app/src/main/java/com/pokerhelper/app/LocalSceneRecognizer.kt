package com.pokerhelper.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * V3.0.0: 本地场景识别引擎（GG扑克专用）
 *
 * 完全本地的场景识别器，替代云端VLM API，目标<500ms全流程。
 * 仅针对GG扑克(GGPoker)平台，基于1080×2344竖屏基准坐标。
 *
 * 功能模块：
 * 1. 卡牌识别 - 复用 CardRecognizer（rank模板匹配 + suit颜色/形状分析）
 * 2. 数字OCR - ML Kit TextRecognition 读取底池/筹码/下注/toCall/盲注
 * 3. 按钮检测 - 像素颜色分类 + OCR读取按钮文字金额
 * 4. 特殊状态检测 - Straddle / Insurance / 游戏模式
 * 5. 场景组装 - 组装为 VisionResult 对象
 *
 * 坐标缩放机制与 CardRecognizer 一致：基于 GameModeConfig 基准坐标按屏幕尺寸缩放。
 */
class LocalSceneRecognizer(
    private val context: Context,
    private val cardRecognizer: CardRecognizer
) {

    companion object {
        private const val TAG = "LocalScene"

        // 基准分辨率（GG扑克竖屏）
        private const val REF_WIDTH = 1080
        private const val REF_HEIGHT = 2344

        // 座位位置名称映射（与VisionApiClient一致）
        private val SEAT_POSITIONS = arrayOf(
            "top-center",     // seat 0
            "left-top",       // seat 1
            "right-top",      // seat 2
            "right-bottom",   // seat 3
            "bottom-center",  // seat 4 (对手)
            "left-bottom"     // seat 5 (hero自己)
        )
    }

    // 屏幕缩放因子
    private var scaleX = 1.0f
    private var scaleY = 1.0f
    private var screenW = REF_WIDTH
    private var screenH = REF_HEIGHT

    // 复用TextRecognition客户端（避免频繁创建销毁）
    private val textRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    // 是否初始化
    private var isInitialized = false

    /**
     * 初始化：更新屏幕尺寸 + 初始化cardRecognizer模板
     */
    fun init(width: Int, height: Int) {
        updateScreenSize(width, height)
        cardRecognizer.init()
        isInitialized = true
        Log.i(TAG, "初始化完成: 屏幕=${width}x${height}, scaleX=$scaleX, scaleY=$scaleY")
    }

    /**
     * 更新屏幕尺寸，重新计算缩放因子
     */
    fun updateScreenSize(width: Int, height: Int) {
        screenW = width
        screenH = height
        scaleX = width.toFloat() / REF_WIDTH
        scaleY = height.toFloat() / REF_HEIGHT
        Log.d(TAG, "屏幕尺寸更新: ${width}x${height}, scaleX=$scaleX, scaleY=$scaleY")
    }

    /**
     * 将基准坐标（1080×2344）缩放到实际屏幕坐标
     */
    private fun scaleRegion(x1: Int, y1: Int, x2: Int, y2: Int): IntArray {
        return intArrayOf(
            (x1 * scaleX).toInt(),
            (y1 * scaleY).toInt(),
            (x2 * scaleX).toInt(),
            (y2 * scaleY).toInt()
        )
    }

    /**
     * 裁剪bitmap指定区域
     */
    private fun cropBitmap(bmp: Bitmap, region: IntArray): Bitmap? {
        val x1 = region[0].coerceIn(0, bmp.width - 1)
        val y1 = region[1].coerceIn(0, bmp.height - 1)
        val x2 = region[2].coerceIn(x1 + 1, bmp.width)
        val y2 = region[3].coerceIn(y1 + 1, bmp.height)
        return try {
            Bitmap.createBitmap(bmp, x1, y1, x2 - x1, y2 - y1)
        } catch (e: Exception) {
            Log.e(TAG, "裁剪失败: [${region.joinToString()}]", e)
            null
        }
    }

    // ============================================================
    // OCR 工具方法
    // ============================================================

    /**
     * 对指定区域执行OCR，返回识别到的文本
     * 使用 CountDownLatch 同步等待（参考 CardRecognizer.readPotSize）
     */
    private fun ocrRegion(screenshot: Bitmap, region: IntArray): String {
        val cropped = cropBitmap(screenshot, region) ?: return ""
        val latch = CountDownLatch(1)
        var resultText = ""
        try {
            val image = InputImage.fromBitmap(cropped, 0)
            textRecognizer.process(image)
                .addOnSuccessListener { visionText ->
                    resultText = visionText.text
                    latch.countDown()
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "OCR失败 region=[${region.joinToString()}]: ${e.message}")
                    latch.countDown()
                }
            latch.await(1500, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            Log.e(TAG, "OCR异常", e)
        } finally {
            cropped.recycle()
        }
        return resultText
    }

    /**
     * 解析筹码数字字符串（支持K/M简写）
     * 例如: "1.5K" -> 1500, "2M" -> 2000000, "1,234" -> 1234
     */
    private fun parseChipNumber(text: String): Int {
        if (text.isEmpty()) return 0
        val cleaned = text
            .replace(Regex("(?i)(pot|底池|chips|筹码|bet|下注|call|跟注|raise|加注|all[\\s-]?in|全押|sb|bb|blind|盲注)"), "")
            .replace(Regex("[\\$€£¥]"), "")
            .replace(",", "")
            .replace(" ", "")
            .trim()

        // 找数字序列（可能含小数点和K/M后缀）
        val matchResult = Regex("(?i)(\\d+(?:\\.\\d+)?)([km]?)").find(cleaned)
        if (matchResult != null) {
            val numStr = matchResult.groupValues[1]
            val suffix = matchResult.groupValues[2].uppercase()
            return try {
                val num = numStr.toFloat()
                when (suffix) {
                    "K" -> (num * 1000).toInt()
                    "M" -> (num * 1000000).toInt()
                    else -> num.toInt()
                }
            } catch (_: Exception) { 0 }
        }
        return 0
    }

    /**
     * 解析盲注文本，返回 (SB, BB)
     * 支持格式: "50/100", "SB 50 BB 100", "100/200 ante 10"
     */
    private fun parseBlinds(text: String): Pair<Int, Int> {
        if (text.isEmpty()) return 0 to 0
        // 匹配 "数字/数字" 格式
        val slashMatch = Regex("(\\d+(?:\\.\\d+)?[kKmM]?)\\s*/\\s*(\\d+(?:\\.\\d+)?[kKmM]?)").find(text)
        if (slashMatch != null) {
            val sb = parseChipNumber(slashMatch.groupValues[1])
            val bb = parseChipNumber(slashMatch.groupValues[2])
            if (sb > 0 && bb > 0) return sb to bb
        }
        // 分别查找 SB 和 BB
        val sbMatch = Regex("(?i)(?:sb|small blind|小盲)\\s*:?\\s*(\\d+(?:\\.\\d+)?[kKmM]?)").find(text)
        val bbMatch = Regex("(?i)(?:bb|big blind|大盲)\\s*:?\\s*(\\d+(?:\\.\\d+)?[kKmM]?)").find(text)
        val sb = sbMatch?.let { parseChipNumber(it.groupValues[1]) } ?: 0
        val bb = bbMatch?.let { parseChipNumber(it.groupValues[1]) } ?: 0
        return sb to bb
    }

    /**
     * 从按钮文本中解析toCall金额
     * 支持: "Call 500", "跟注500", "Call 1.2K"
     * 返回 0 表示 Check（无需跟注），-1 表示未识别到
     */
    private fun parseCallAmount(buttonText: String): Int {
        val text = buttonText.trim()
        if (text.isEmpty()) return -1
        // Check/过牌 → 0
        if (Regex("(?i)check|过牌|让牌").containsMatchIn(text)) return 0
        // Call/跟注 → 解析金额
        val callMatch = Regex("(?i)(?:call|跟注)\\s*([\\d.,]+[kKmM]?)").find(text)
        if (callMatch != null) {
            val amount = parseChipNumber(callMatch.groupValues[1])
            if (amount > 0) return amount
        }
        // Raise/加注 → 也包含toCall信息（通常是加注后总额，需处理）
        val raiseMatch = Regex("(?i)(?:raise|加注)\\s*([\\d.,]+[kKmM]?)").find(text)
        if (raiseMatch != null) {
            // 加注按钮显示的是总下注额，toCall需要结合其他信息推断
            // 这里返回-1表示无法从单个按钮确定toCall
            return -1
        }
        return -1
    }

    /**
     * 检测区域主色调，用于判断按钮类型
     * 返回 (redRatio, greenRatio, blueRatio, yellowRatio)
     */
    private fun analyzeRegionColors(screenshot: Bitmap, region: IntArray): ColorAnalysis {
        val x1 = region[0].coerceIn(0, screenshot.width - 1)
        val y1 = region[1].coerceIn(0, screenshot.height - 1)
        val x2 = region[2].coerceIn(x1 + 1, screenshot.width)
        val y2 = region[3].coerceIn(y1 + 1, screenshot.height)
        val w = x2 - x1
        val h = y2 - y1
        val pixels = IntArray(w * h)
        try {
            screenshot.getPixels(pixels, 0, w, x1, y1, w, h)
        } catch (_: Exception) {
            return ColorAnalysis(0f, 0f, 0f, 0f, 0f, 0)
        }

        var redCount = 0
        var greenCount = 0
        var blueCount = 0
        var yellowCount = 0
        var whiteCount = 0
        var total = 0

        // 采样（避免全量计算，每4个像素采1个）
        val step = 4
        for (y in 0 until h step step) {
            for (x in 0 until w step step) {
                val p = pixels[y * w + x]
                val r = Color.red(p)
                val g = Color.green(p)
                val b = Color.blue(p)
                total++

                // 白色（按钮文字区域可能很多白色，跳过统计主要背景色）
                if (r > 220 && g > 220 && b > 220) { whiteCount++; continue }
                // 黑色/接近黑色（文字、阴影）
                if (r < 50 && g < 50 && b < 50) continue

                // 红色按钮（Fold）
                if (r > 160 && g < 100 && b < 100) redCount++
                // 绿色按钮（Check/All-in）
                else if (g > 130 && r < 120 && b < 120) greenCount++
                // 蓝色按钮（Call/Raise）
                else if (b > 130 && r < 120 && g < 140) blueCount++
                // 黄色（保险等）
                else if (r > 180 && g > 150 && b < 100) yellowCount++
            }
        }

        val totalColored = (redCount + greenCount + blueCount + yellowCount).coerceAtLeast(1)
        return ColorAnalysis(
            redRatio = redCount.toFloat() / totalColored,
            greenRatio = greenCount.toFloat() / totalColored,
            blueRatio = blueCount.toFloat() / totalColored,
            yellowRatio = yellowCount.toFloat() / totalColored,
            whiteRatio = whiteCount.toFloat() / total.coerceAtLeast(1),
            sampleCount = total
        )
    }

    private data class ColorAnalysis(
        val redRatio: Float,
        val greenRatio: Float,
        val blueRatio: Float,
        val yellowRatio: Float,
        val whiteRatio: Float,
        val sampleCount: Int
    ) {
        /** 判断是否为红色主按钮 */
        fun isRed(): Boolean = redRatio > 0.4f && redRatio > greenRatio && redRatio > blueRatio
        /** 判断是否为绿色主按钮 */
        fun isGreen(): Boolean = greenRatio > 0.4f && greenRatio > redRatio && greenRatio > blueRatio
        /** 判断是否为蓝色主按钮 */
        fun isBlue(): Boolean = blueRatio > 0.4f && blueRatio > redRatio && blueRatio > greenRatio
        /** 判断是否为黄色主按钮 */
        fun isYellow(): Boolean = yellowRatio > 0.3f
    }

    // ============================================================
    // 模块一：卡牌识别
    // ============================================================

    private data class CardRecognitionData(
        val handCards: List<VisionApiClient.CardInfo>,
        val communityCards: List<VisionApiClient.CardInfo>,
        val minConfidence: Float,
        val street: String
    )

    /**
     * 调用 CardRecognizer 识别手牌+公共牌
     */
    private fun recognizeCards(screenshot: Bitmap): CardRecognitionData {
        val result = cardRecognizer.recognizeAll(screenshot)

        val handCards = result.handCards
            .sortedBy { it.position }
            .map { VisionApiClient.CardInfo(it.rank, it.suit) }

        val communityCards = result.communityCards
            .sortedBy { it.position }
            .map { VisionApiClient.CardInfo(it.rank, it.suit) }

        val street = when (communityCards.size) {
            0 -> "preflop"
            3 -> "flop"
            4 -> "turn"
            5 -> "river"
            else -> "preflop"
        }

        Log.d(TAG, "卡牌识别: hand=${handCards.size}张 comm=${communityCards.size}张 street=$street minConf=${String.format("%.2f", result.minConfidence)}")

        return CardRecognitionData(handCards, communityCards, result.minConfidence, street)
    }

    // ============================================================
    // 模块二：底池 & 筹码 OCR
    // ============================================================

    /**
     * 读取底池金额
     */
    private fun readPotAmount(screenshot: Bitmap): Int {
        val potRegion = scaleRegion(
            GameModeConfig.getPotAmountRegion()[0],
            GameModeConfig.getPotAmountRegion()[1],
            GameModeConfig.getPotAmountRegion()[2],
            GameModeConfig.getPotAmountRegion()[3]
        )
        val text = ocrRegion(screenshot, potRegion)
        val pot = parseChipNumber(text)
        Log.d(TAG, "底池OCR: '$text' -> $pot")
        return pot
    }

    /**
     * 读取6个座位的玩家筹码
     */
    private fun readPlayerChips(screenshot: Bitmap): IntArray {
        val chipRegions = GameModeConfig.getPlayerChipRegions()
        val chips = IntArray(6) { 0 }
        for (i in chipRegions.indices) {
            if (i >= 6) break
            val region = scaleRegion(
                chipRegions[i][0], chipRegions[i][1],
                chipRegions[i][2], chipRegions[i][3]
            )
            val text = ocrRegion(screenshot, region)
            chips[i] = parseChipNumber(text)
        }
        Log.d(TAG, "玩家筹码: [${chips.joinToString(", ")}]")
        return chips
    }

    /**
     * 读取玩家下注金额（筹码前方的bet显示）
     * 在GG扑克中，玩家下注显示在筹码和桌面中央之间
     * 使用一个比筹码更靠内（靠近桌面中心）的区域
     */
    private fun readPlayerBets(screenshot: Bitmap): IntArray {
        // GG扑克：下注金额通常显示在头像/筹码与底池之间
        // 基于玩家筹码区域，向内（桌面中心方向）偏移一个bet区域
        val chipRegions = GameModeConfig.getPlayerChipRegions()
        val bets = IntArray(6) { 0 }

        // 6个座位bet区域的偏移（相对筹码区域，向桌面中心方向扩展）
        // 简化处理：在筹码区域靠桌面中心一侧取一个窄条区域
        val betOffsets = arrayOf(
            // seat 0 (top-center): bet在筹码下方
            intArrayOf(0, 20, 0, 50),
            // seat 1 (left-top): bet在筹码右侧
            intArrayOf(20, 0, 60, 0),
            // seat 2 (right-top): bet在筹码左侧
            intArrayOf(-60, 0, -20, 0),
            // seat 3 (right-bottom): bet在筹码左侧
            intArrayOf(-60, 0, -20, 0),
            // seat 4 (bottom-center): bet在筹码上方
            intArrayOf(0, -50, 0, -20),
            // seat 5 (left-bottom/Hero): bet在筹码右上方（朝桌面中心）
            intArrayOf(20, -60, 80, -20)
        )

        for (i in chipRegions.indices) {
            if (i >= 6) break
            val chip = chipRegions[i]
            val offset = betOffsets[i]
            val betRegion = intArrayOf(
                chip[0] + offset[0],
                chip[1] + offset[1],
                chip[2] + offset[2],
                chip[3] + offset[3]
            )
            val scaledRegion = scaleRegion(betRegion[0], betRegion[1], betRegion[2], betRegion[3])
            val text = ocrRegion(screenshot, scaledRegion)
            bets[i] = parseChipNumber(text)
        }
        Log.d(TAG, "玩家下注: [${bets.joinToString(", ")}]")
        return bets
    }

    /**
     * 读取盲注（从顶部导航栏/桌面信息区域）
     */
    private fun readBlinds(screenshot: Bitmap): Pair<Int, Int> {
        // GG扑克：盲注通常显示在顶部导航栏或桌面信息条
        // 尝试多个区域
        val regions = listOf(
            scaleRegion(100, 60, 400, 120),      // 顶部左侧（可能显示盲注）
            scaleRegion(400, 60, 680, 120),      // 顶部中央
            scaleRegion(430, 750, 650, 810)      // 桌面中央偏下（盲注显示区）
        )

        for (region in regions) {
            val text = ocrRegion(screenshot, region)
            val (sb, bb) = parseBlinds(text)
            if (sb > 0 && bb > 0) {
                Log.d(TAG, "盲注OCR: '$text' -> SB=$sb BB=$bb")
                return sb to bb
            }
        }
        Log.d(TAG, "盲注OCR: 未识别到")
        return 0 to 0
    }

    // ============================================================
    // 模块三：按钮检测
    // ============================================================

    private data class ButtonDetectionResult(
        val buttons: List<String>,
        val buttonPositions: List<VisionApiClient.ButtonPosition>,
        val toCall: Int
    )

    /**
     * 检测底部按钮状态
     * 左按钮区 [20,2190,370,2340]: 红色=Fold, 绿色=Check
     * 右按钮区 [390,2190,710,2340]: 蓝色=Call/Raise(含金额), 绿色=All-in
     */
    private fun detectButtons(screenshot: Bitmap): ButtonDetectionResult {
        val actionButtons = GameModeConfig.getActionButtons()
        if (actionButtons.size < 2) {
            return ButtonDetectionResult(emptyList(), emptyList(), -1)
        }

        val buttons = mutableListOf<String>()
        val positions = mutableListOf<VisionApiClient.ButtonPosition>()
        var toCall = -1

        // 左按钮
        val leftRegion = scaleRegion(
            actionButtons[0][0], actionButtons[0][1],
            actionButtons[0][2], actionButtons[0][3]
        )
        val leftColors = analyzeRegionColors(screenshot, leftRegion)
        val leftText = ocrRegion(screenshot, leftRegion).trim()

        val leftBtnText = when {
            leftColors.isRed() -> {
                // 红色 → Fold/弃牌
                if (leftText.isNotEmpty()) leftText else "Fold"
            }
            leftColors.isGreen() -> {
                // 绿色 → Check/过牌
                if (leftText.isNotEmpty()) leftText else "Check"
            }
            leftText.isNotEmpty() -> leftText
            else -> "Fold"
        }
        buttons.add(leftBtnText)
        positions.add(
            VisionApiClient.ButtonPosition(
                leftBtnText,
                (leftRegion[0] + leftRegion[2]) / 2.0 / screenW,
                (leftRegion[1] + leftRegion[3]) / 2.0 / screenH
            )
        )
        // 如果左按钮是Check，toCall=0
        if (leftColors.isGreen() || leftText.contains("check", true) || leftText.contains("过牌")) {
            toCall = 0
        }

        // 右按钮
        val rightRegion = scaleRegion(
            actionButtons[1][0], actionButtons[1][1],
            actionButtons[1][2], actionButtons[1][3]
        )
        val rightColors = analyzeRegionColors(screenshot, rightRegion)
        val rightText = ocrRegion(screenshot, rightRegion).trim()

        val rightBtnText = when {
            rightColors.isBlue() -> {
                // 蓝色 → Call/Raise
                if (rightText.isNotEmpty()) rightText else "Call"
            }
            rightColors.isGreen() -> {
                // 绿色 → All-in/全押
                if (rightText.isNotEmpty()) rightText else "All In"
            }
            rightText.isNotEmpty() -> rightText
            else -> "Call"
        }
        buttons.add(rightBtnText)
        positions.add(
            VisionApiClient.ButtonPosition(
                rightBtnText,
                (rightRegion[0] + rightRegion[2]) / 2.0 / screenW,
                (rightRegion[1] + rightRegion[3]) / 2.0 / screenH
            )
        )

        // 从右按钮文本解析toCall
        if (rightColors.isBlue() || rightText.contains("call", true) || rightText.contains("跟注")) {
            val callAmount = parseCallAmount(rightBtnText)
            if (callAmount >= 0) toCall = callAmount
        }

        // GG扑克可能有第三个按钮（中间或右侧的Raise/Bet）
        // 检测bet按钮区域（右侧4档下注按钮）
        val betButtons = GameModeConfig.getBetButtons()
        if (betButtons.isNotEmpty()) {
            // 只检测最上方的bet按钮是否可见（100%按钮）
            val topBetRegion = scaleRegion(
                betButtons[0][0], betButtons[0][1],
                betButtons[0][2], betButtons[0][3]
            )
            val topBetColors = analyzeRegionColors(screenshot, topBetRegion)
            val topBetText = ocrRegion(screenshot, topBetRegion).trim()
            if (topBetColors.sampleCount > 50 && (topBetColors.whiteRatio > 0.05f || topBetText.isNotEmpty())) {
                // 右侧下注按钮可见，说明当前是可以加注/下注的场景
                // 添加Raise/Bet按钮到列表
                val betBtnText = if (topBetText.isNotEmpty()) topBetText else {
                    if (toCall == 0) "Bet" else "Raise"
                }
                // 检查按钮是否有激活状态（有文字或颜色区分）
                if (topBetText.isNotEmpty() || topBetColors.whiteRatio > 0.1f) {
                    buttons.add(betBtnText)
                    positions.add(
                        VisionApiClient.ButtonPosition(
                            betBtnText,
                            (topBetRegion[0] + topBetRegion[2]) / 2.0 / screenW,
                            (topBetRegion[1] + topBetRegion[3]) / 2.0 / screenH
                        )
                    )
                }
            }
        }

        Log.d(TAG, "按钮检测: buttons=$buttons toCall=$toCall")
        return ButtonDetectionResult(buttons, positions, toCall)
    }

    // ============================================================
    // 模块四：特殊状态检测
    // ============================================================

    /**
     * 检测是否为Straddle（额外盲注显示）
     * 思路：除了SB/BB外，检测是否有第三个盲注级别的数字
     */
    private fun detectStraddle(screenshot: Bitmap): Boolean {
        // Straddle通常显示为第三个盲注金额
        // 检测盲注区域附近是否有第三个数字
        val region = scaleRegion(430, 750, 650, 820)
        val text = ocrRegion(screenshot, region)
        if (text.isEmpty()) return false

        // 统计数字数量，如果有3个独立的筹码数字，可能是Straddle
        val numbers = Regex("(?i)(\\d+(?:\\.\\d+)?[kKmM]?)").findAll(text)
            .map { parseChipNumber(it.groupValues[1]) }
            .filter { it > 0 }
            .toList()

        val hasStraddleText = text.contains("straddle", true) ||
                text.contains("Straddle", true) ||
                text.contains("Staddle", true) // 容忍OCR错误

        val result = hasStraddleText || numbers.size >= 3
        Log.d(TAG, "Straddle检测: $result (text='$text', numbers=$numbers)")
        return result
    }

    /**
     * 检测是否出现Insurance/Cashout按钮
     */
    private fun detectInsurance(screenshot: Bitmap): Boolean {
        // Insurance按钮通常出现在桌面中央或右侧
        // 检测多个可能区域
        val regions = listOf(
            scaleRegion(600, 1300, 900, 1450),    // 右侧中间区域
            scaleRegion(300, 1200, 780, 1350),    // 中央偏右
            scaleRegion(700, 1400, 1030, 1550)    // 右侧中下区域
        )

        for (region in regions) {
            val text = ocrRegion(screenshot, region)
            if (text.isNotEmpty()) {
                val hasInsurance = text.contains("insurance", true) ||
                        text.contains("cashout", true) ||
                        text.contains("EV", true) ||
                        text.contains("保险", true)
                if (hasInsurance) {
                    Log.d(TAG, "Insurance检测: true (text='$text')")
                    return true
                }
            }
        }
        Log.d(TAG, "Insurance检测: false")
        return false
    }

    /**
     * 检测游戏模式（现金桌 vs 锦标赛）
     */
    private fun detectGameMode(screenshot: Bitmap): String {
        // 锦标赛特征：报名费/奖池/剩余人数/盲注倒计时/级别
        val region = scaleRegion(100, 60, 980, 130)  // 顶部导航栏
        val text = ocrRegion(screenshot, region)

        val tournamentKeywords = listOf(
            "tournament", "Tournament", "TOURNAMENT",
            "buy-in", "Buy-In", "BUY-IN",
            "prize", "Prize", "PRIZE",
            "level", "Level", "LEVEL",
            "盲注", "倒计时", "锦标赛", "报名费", "奖池",
            "remaining", "Remaining", "REMAINING",
            "MTT", "mtt"
        )

        val tournamentScore = tournamentKeywords.count { text.contains(it, true) }
        val result = if (tournamentScore >= 2) "tournament" else "cash"
        Log.d(TAG, "游戏模式检测: $result (topText='$text', score=$tournamentScore)")
        return result
    }

    /**
     * 检测PKO赏金赛
     */
    private fun detectPKO(screenshot: Bitmap): Boolean {
        val region = scaleRegion(100, 60, 980, 130)
        val text = ocrRegion(screenshot, region)
        val result = text.contains("PKO", true) ||
                text.contains("bounty", true) ||
                text.contains("赏金", true)
        Log.d(TAG, "PKO检测: $result")
        return result
    }

    // ============================================================
    // 模块五：D按钮检测（复用 CardRecognizer）
    // ============================================================

    /**
     * 检测D按钮位置，返回座位索引
     */
    private fun detectDealerButton(screenshot: Bitmap): Int {
        val searchAreas = GameModeConfig.getDealerSearchAreas()
        // 缩放到实际屏幕尺寸
        val scaledAreas = searchAreas.map { area ->
            intArrayOf(
                (area[0] * scaleX).toInt(),
                (area[1] * scaleY).toInt(),
                (area[2] * scaleX).toInt(),
                (area[3] * scaleY).toInt()
            )
        }
        val seatIdx = CardRecognizer.detectDealerButton(screenshot, scaledAreas)
        Log.d(TAG, "D按钮检测: seat=$seatIdx")
        return seatIdx
    }

    // ============================================================
    // 模块六：玩家活跃状态检测（白色光圈）
    // ============================================================

    /**
     * 检测活跃玩家（行动者白色光圈）
     * 返回活跃玩家座位索引列表
     */
    private data class ActivePlayerInfo(
        val inHandSeats: List<Int>,      // 所有在局玩家座位索引
        val actingSeat: Int              // 当前行动者座位索引（-1=未检测到）
    )

    private fun detectActivePlayers(screenshot: Bitmap, chipValues: IntArray): ActivePlayerInfo {
        val nameRegions = GameModeConfig.getPlayerNameRegions()
        val chipRegions = GameModeConfig.getPlayerChipRegions()

        // 缩放到实际屏幕尺寸
        val scaledNames = nameRegions.map { area ->
            intArrayOf(
                (area[0] * scaleX).toInt(), (area[1] * scaleY).toInt(),
                (area[2] * scaleX).toInt(), (area[3] * scaleY).toInt()
            )
        }
        val scaledChips = chipRegions.map { area ->
            intArrayOf(
                (area[0] * scaleX).toInt(), (area[1] * scaleY).toInt(),
                (area[2] * scaleX).toInt(), (area[3] * scaleY).toInt()
            )
        }

        // 当前行动者（白色光圈检测）
        val actingSeat = CardRecognizer.detectActivePlayer(screenshot, scaledNames, scaledChips)

        // 所有有筹码的玩家视为在局玩家
        val inHandSeats = mutableListOf<Int>()
        for (i in chipValues.indices) {
            if (chipValues[i] > 0) inHandSeats.add(i)
        }

        Log.d(TAG, "活跃玩家检测: actingSeat=$actingSeat, inHand=${inHandSeats.size}")
        return ActivePlayerInfo(inHandSeats, actingSeat)
    }

    // ============================================================
    // 主入口：场景识别
    // ============================================================

    /**
     * 识别完整场景，返回 VisionResult
     * 全流程目标 < 500ms
     */
    fun recognizeScene(screenshot: Bitmap): VisionApiClient.VisionResult? {
        if (!isInitialized) {
            Log.w(TAG, "未初始化，跳过本地识别")
            return null
        }

        val t0 = System.currentTimeMillis()

        return try {
            // Step 1: 卡牌识别（约30-80ms）
            val cardData = recognizeCards(screenshot)
            val t1 = System.currentTimeMillis()

            // Step 2: 底池OCR（约50-100ms）
            val potSize = readPotAmount(screenshot)
            val t2 = System.currentTimeMillis()

            // Step 3: 玩家筹码OCR（6个区域，可能串行较慢）
            // 优化：只识别关键区域，Hero筹码必识，其他位置采样
            val playerChips = readPlayerChips(screenshot)
            val t3 = System.currentTimeMillis()

            // Step 4: 玩家下注OCR
            val playerBets = readPlayerBets(screenshot)
            val t4 = System.currentTimeMillis()

            // Step 5: 按钮检测 + toCall OCR
            val buttonResult = detectButtons(screenshot)
            val t5 = System.currentTimeMillis()

            // Step 6: 盲注OCR
            val (blindSB, blindBB) = readBlinds(screenshot)
            val t6 = System.currentTimeMillis()

            // Step 7: D按钮检测
            val dealerSeat = detectDealerButton(screenshot)
            val t7 = System.currentTimeMillis()

            // Step 8: 活跃玩家检测
            val activeInfo = detectActivePlayers(screenshot, playerChips)
            val t8 = System.currentTimeMillis()

            // Step 9: 特殊状态检测
            val isStraddle = detectStraddle(screenshot)
            val isInsurance = detectInsurance(screenshot)
            val gameMode = detectGameMode(screenshot)
            val isPKO = detectPKO(screenshot)
            val t9 = System.currentTimeMillis()

            // 组装结果
            val myChips = if (playerChips.size > 5) playerChips[5] else 0 // Hero = seat 5
            val totalPlayers = activeInfo.inHandSeats.size.coerceAtLeast(2)
            val activePlayers = totalPlayers // 在局人数即活跃人数

            // 组装玩家信息列表
            val playersList = mutableListOf<VisionApiClient.PlayerInfo>()
            for (i in 0 until minOf(playerChips.size, SEAT_POSITIONS.size)) {
                if (playerChips[i] <= 0 && playerBets[i] <= 0) continue // 跳过空座位
                playersList.add(
                    VisionApiClient.PlayerInfo(
                        position = SEAT_POSITIONS[i],
                        bet = playerBets[i],
                        chips = playerChips[i],
                        active = i == activeInfo.actingSeat
                    )
                )
            }

            // D按钮位置字符串
            val dButtonPos = if (dealerSeat >= 0 && dealerSeat < SEAT_POSITIONS.size) {
                SEAT_POSITIONS[dealerSeat]
            } else {
                "not_found"
            }

            // my position
            val myPosition = "left-bottom" // Hero固定在左下方(seat 5)

            // minRaise估算 = 2 * BB（如果toCall>0则为toCall + BB）
            val minRaise = if (buttonResult.toCall > 0 && blindBB > 0) {
                buttonResult.toCall + blindBB
            } else {
                blindBB * 2
            }

            // toCall最终值
            val finalToCall = buttonResult.toCall.coerceAtLeast(0)

            val result = VisionApiClient.VisionResult(
                isPokerTable = cardData.handCards.size == 2 || potSize > 0,
                holeCards = cardData.handCards,
                communityCards = cardData.communityCards,
                potSize = potSize,
                playerChips = myChips,
                totalPlayers = totalPlayers,
                activePlayers = activePlayers,
                myPosition = myPosition,
                street = cardData.street,
                toCall = finalToCall,
                minRaise = minRaise,
                buttons = buttonResult.buttons,
                blindSB = blindSB,
                blindBB = blindBB,
                ante = 0,
                players = playersList,
                dButtonPosition = dButtonPos,
                rawResponse = "LOCAL_SCENE_RECOGNIZER",
                showdownCards = emptyList(),
                oppHud = emptyList(),
                buttonPositions = buttonResult.buttonPositions,
                suitUncertain = cardData.minConfidence < 0.7f,
                isStraddle = isStraddle,
                isBombPot = false,
                isInsurance = isInsurance,
                isPKO = isPKO,
                gameMode = gameMode,
                gameType = "normal",
                rakeCap = 0,
                detectedPlatform = "GGPOKER",
                localSuitUsed = true
            )

            val totalTime = System.currentTimeMillis() - t0
            Log.i(TAG, "★ 本地场景识别完成: ${totalTime}ms " +
                    "| hand=${cardData.handCards.size} comm=${cardData.communityCards.size} " +
                    "| pot=$potSize chips=$myChips " +
                    "| toCall=$finalToCall buttons=${buttonResult.buttons.size} " +
                    "| street=${cardData.street} D=$dButtonPos")
            Log.d(TAG, "阶段耗时: cards=${t1-t0}ms pot=${t2-t1}ms chips=${t3-t2}ms " +
                    "bets=${t4-t3}ms buttons=${t5-t4}ms blinds=${t6-t5}ms " +
                    "dButton=${t7-t6}ms active=${t8-t7}ms special=${t9-t8}ms")

            result
        } catch (e: Exception) {
            Log.e(TAG, "本地场景识别异常", e)
            try { DiagnosticLogger.logError(DiagnosticLogger.ErrorCategory.RECOGNITION, DiagnosticLogger.Severity.HIGH, "LocalCV场景识别异常", "${e.javaClass.simpleName}: ${e.message}") } catch (_: Exception) {}
            null
        }
    }

    /**
     * 判断本地识别结果是否有效（用于决定是否降级到API）
     */
    fun isValidResult(result: VisionApiClient.VisionResult): Boolean {
        // 基本有效性：手牌2张 + 底池>0 + 有按钮
        val handValid = result.holeCards.size == 2
        val hasPot = result.potSize > 0
        val hasButtons = result.buttons.isNotEmpty()

        // 置信度检查：rank置信度
        val ranksValid = result.holeCards.all { it.rank.isNotEmpty() && it.rank != "?" }

        // V2.9.508修复：检查communityCards是否有重复牌
        val communityCardsUnique = checkCommunityCardsUnique(result.communityCards)

        val valid = handValid && hasPot && hasButtons && ranksValid && communityCardsUnique
        Log.d(TAG, "有效性检查: handValid=$handValid hasPot=$hasPot " +
                "hasButtons=$hasButtons ranksValid=$ranksValid communityUnique=$communityCardsUnique → valid=$valid")
        // V2.9.503: 详细诊断 - 失败时记录具体原因
        if (!valid) {
            Log.w(TAG, "★ LocalCV失败详情: handCards=${result.holeCards.map{"${it.rank}${it.suit}"}} " +
                    "communityCards=${result.communityCards.map{"${it.rank}${it.suit}"}} " +
                    "pot=${result.potSize} buttons=${result.buttons} " +
                    "street=${result.street} isPokerTable=${result.isPokerTable}")
            if (!handValid) Log.w(TAG, "  ❌ 手牌无效: 识别到${result.holeCards.size}张(需要2张)")
            if (!hasPot) Log.w(TAG, "  ❌ 底池无效: pot=${result.potSize}")
            if (!hasButtons) Log.w(TAG, "  ❌ 未检测到按钮")
            if (!ranksValid) Log.w(TAG, "  ❌ 手牌rank无效: ${result.holeCards.map{"${it.rank}${it.suit}"}}")
            if (!communityCardsUnique) Log.w(TAG, "  ❌ 公共牌有重复: ${result.communityCards.map{"${it.rank}${it.suit}"}}")
        }
        return valid
    }

    /**
     * V2.9.508修复：检查communityCards是否有重复牌
     * 德州扑克中5张公共牌必须全部不同
     */
    private fun checkCommunityCardsUnique(communityCards: List<VisionApiClient.CardInfo>): Boolean {
        if (communityCards.isEmpty()) return true
        val cardKeys = communityCards.map { "${it.rank}${it.suit}" }
        val uniqueKeys = cardKeys.toSet()
        val hasDuplicate = cardKeys.size != uniqueKeys.size
        if (hasDuplicate) {
            val duplicates = cardKeys.groupBy { it }.filter { it.value.size > 1 }.keys
            Log.w(TAG, "公共牌重复检测: $cardKeys, 重复: $duplicates")
        }
        return !hasDuplicate
    }

    fun release() {
        try {
            textRecognizer.close()
        } catch (_: Exception) {}
        isInitialized = false
    }
}
