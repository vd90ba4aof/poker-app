package win.opt.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.util.ArrayDeque

/**
 * 本地扑克牌识别引擎
 * 纯像素操作，无第三方依赖，单卡识别<2ms
 *
 * 公共牌：固定坐标 + 颜色mask + 等比缩放模板匹配
 * 手牌：连通区域分析(flood-fill) + 手牌专用模板
 *
 * V2.9.518: 替代VLM牌面识别，从3-8秒降至毫秒级
 */
class LocalCardRecognizer private constructor(private val context: Context) {

    companion object {
        private const val TAG = "LocalCardRecognizer"

        @Volatile
        private var instance: LocalCardRecognizer? = null

        fun getInstance(context: Context): LocalCardRecognizer {
            return instance ?: synchronized(this) {
                instance ?: LocalCardRecognizer(context.applicationContext).also {
                    instance = it
                    it.loadTemplates()
                }
            }
        }
    }

    // 模板：key -> 二值数组(0=内容, 1=背景), width, height
    private data class Template(val data: BooleanArray, val w: Int, val h: Int) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Template) return false
            return data.contentEquals(other.data) && w == other.w && h == other.h
        }
        override fun hashCode(): Int = data.contentHashCode() * 31 + w * 17 + h
    }

    private val commRankTemplates = HashMap<String, Template>()
    private val commSuitTemplates = HashMap<String, Template>()
    private val handRankTemplates = HashMap<String, Template>()
    private val handSuitTemplates = HashMap<String, Template>()

    private val RANKS = arrayOf("A", "K", "Q", "J", "10", "9", "8", "7", "6", "5", "4", "3", "2")
    private val SUITS = arrayOf("s", "h", "d", "c")
    private val RED_SUITS = setOf("h", "d")
    private val BLACK_SUITS = setOf("s", "c")

    data class CardResult(
        val rank: String,
        val suit: String,
        val confidence: Float,
        val rankScore: Float,
        val suitScore: Float
    )

    @Volatile
    private var loaded = false

    // V2.9.521: 诊断信息——最近一次识别各步失败原因
    @Volatile var lastDiag: String = ""
        private set
    // V2.9.522: 每只手牌的失败原因（供diag输出）
    @Volatile var hand0FailReason: String = ""
        private set
    @Volatile var hand1FailReason: String = ""
        private set

    fun loadTemplates() {
        if (loaded) return
        try {
            for (rank in RANKS) {
                loadAssetTemplate("card_templates/comm/rank_$rank.png")?.let {
                    commRankTemplates[rank] = it
                }
                loadAssetTemplate("card_templates/hand/rank_$rank.png")?.let {
                    handRankTemplates[rank] = it
                }
            }
            for (suit in SUITS) {
                loadAssetTemplate("card_templates/comm/suit_$suit.png")?.let {
                    commSuitTemplates[suit] = it
                }
                loadAssetTemplate("card_templates/hand/suit_$suit.png")?.let {
                    handSuitTemplates[suit] = it
                }
            }
            loaded = true
            Log.i(TAG, "Templates loaded: comm ${commRankTemplates.size}r/${commSuitTemplates.size}s, " +
                    "hand ${handRankTemplates.size}r/${handSuitTemplates.size}s")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load templates", e)
        }
    }

    private fun loadAssetTemplate(path: String): Template? {
        return try {
            val bmp = context.assets.open(path).use { input ->
                BitmapFactory.decodeStream(input)
            } ?: return null
            val w = bmp.width
            val h = bmp.height
            val pixels = IntArray(w * h)
            bmp.getPixels(pixels, 0, w, 0, 0, w, h)
            bmp.recycle()
            // Grayscale -> binary (<128 = content)
            val data = BooleanArray(w * h)
            for (i in pixels.indices) {
                val p = pixels[i]
                val gray = ((p shr 16 and 0xFF) * 30 + (p shr 8 and 0xFF) * 59 + (p and 0xFF) * 11) / 100
                data[i] = gray >= 128
            }
            Template(data, w, h)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load template: $path", e)
            null
        }
    }

    // ========== 像素工具 ==========

    private fun getPixel(arr: IntArray, w: Int, x: Int, y: Int): Int {
        if (x < 0 || y < 0 || x >= w) return 0
        val idx = y * w + x
        return if (idx in arr.indices) arr[idx] else 0
    }

    private fun isRed(pixel: Int): Boolean {
        val r = pixel shr 16 and 0xFF
        val g = pixel shr 8 and 0xFF
        val b = pixel and 0xFF
        return r > 110 && r - g > 25 && r - b > 25
    }

    private fun isBlack(pixel: Int): Boolean {
        val r = pixel shr 16 and 0xFF
        val g = pixel shr 8 and 0xFF
        val b = pixel and 0xFF
        return r < 90 && g < 90 && b < 90
    }

    private fun isWhite(pixel: Int): Boolean {
        val r = pixel shr 16 and 0xFF
        val g = pixel shr 8 and 0xFF
        val b = pixel and 0xFF
        return r > 180 && g > 180 && b > 180
    }

    private fun detectColor(pixels: IntArray, w: Int, x1: Int, y1: Int, x2: Int, y2: Int): Boolean {
        var red = 0
        var black = 0
        for (y in y1 until y2) {
            for (x in x1 until x2) {
                val p = getPixel(pixels, w, x, y)
                if (isRed(p)) red++
                if (isBlack(p)) black++
            }
        }
        return red > black
    }

    /** 检测卡牌区域是否存在（白色像素占比） */
    private fun hasCard(pixels: IntArray, w: Int, h: Int, x1: Int, y1: Int, x2: Int, y2: Int): Boolean {
        var white = 0
        var total = 0
        for (y in y1 until y2) {
            for (x in x1 until x2) {
                if (isWhite(getPixel(pixels, w, x, y))) white++
                total++
            }
        }
        return total > 0 && white.toFloat() / total > 0.15f
    }

    // ========== 二值图像处理 ==========

    /** 从像素数组提取区域的二值mask (false=内容, true=背景) */
    private fun extractMask(
        pixels: IntArray, w: Int,
        x1: Int, y1: Int, x2: Int, y2: Int,
        isRed: Boolean
    ): BooleanArray {
        val rw = x2 - x1
        val rh = y2 - y1
        val mask = BooleanArray(rw * rh) { true } // true = background
        for (y in 0 until rh) {
            for (x in 0 until rw) {
                val p = getPixel(pixels, w, x1 + x, y1 + y)
                val content = if (isRed) isRed(p) else isBlack(p)
                mask[y * rw + x] = !content
            }
        }
        return mask
    }

    /** Trim二值图到内容边界 */
    private fun trim(mask: BooleanArray, w: Int, h: Int): Triple<BooleanArray, Int, Int>? {
        var minX = w; var maxX = -1; var minY = h; var maxY = -1
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (!mask[y * w + x]) { // content
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < 0) return null
        val tw = maxX - minX + 1
        val th = maxY - minY + 1
        val trimmed = BooleanArray(tw * th)
        for (y in 0 until th) {
            System.arraycopy(mask, (minY + y) * w + minX, trimmed, y * tw, tw)
        }
        return Triple(trimmed, tw, th)
    }

    /** 最近邻缩放二值图 */
    private fun resizeBinary(mask: BooleanArray, sw: Int, sh: Int, tw: Int, th: Int): BooleanArray {
        val result = BooleanArray(tw * th)
        for (y in 0 until th) {
            val sy = (y * sh / th).coerceIn(0, sh - 1)
            for (x in 0 until tw) {
                val sx = (x * sw / tw).coerceIn(0, sw - 1)
                result[y * tw + x] = mask[sy * sw + sx]
            }
        }
        return result
    }

    /** 模板匹配：缩放到模板高度，取min宽对齐，前景并集上的像素重合度 */
    private fun match(query: Triple<BooleanArray, Int, Int>?, templates: Map<String, Template>): Pair<String?, Float> {
        if (query == null) return Pair(null, 0f)
        val (qData, qW, qH) = query
        if (qH == 0 || qW == 0) return Pair(null, 0f)

        var bestLabel: String? = null
        var bestScore = 0f

        for ((label, tmpl) in templates) {
            val scale = tmpl.h.toFloat() / qH
            val newW = (qW * scale).toInt().coerceAtLeast(1)
            val qResized = resizeBinary(qData, qW, qH, newW, tmpl.h)

            // V2.9.528: 水平居中对齐（原左对齐，偏移2-3px即崩）
            val xOff = (newW - tmpl.w) / 2
            val overlapW = minOf(newW, tmpl.w)

            var fgUnion = 0
            var agree = 0
            var extraFg = 0
            for (y in 0 until tmpl.h) {
                // 1) 重叠区域：水平居中对齐
                for (i in 0 until overlapW) {
                    val qx = i + if (xOff > 0) xOff else 0
                    val tx = i + if (xOff < 0) -xOff else 0
                    if (qx in 0 until newW && tx in 0 until tmpl.w) {
                        val q = qResized[y * newW + qx]
                        val t = tmpl.data[y * tmpl.w + tx]
                        if (!q || !t) {
                            fgUnion++
                            if (q == t) agree++
                        }
                    }
                }
                // 2) 宽度不匹配惩罚：超出部分的前景像素计入union但不计入agree
                if (newW > tmpl.w) {
                    for (qx in 0 until xOff) {
                        if (!qResized[y * newW + qx]) extraFg++
                    }
                    for (qx in (xOff + tmpl.w) until newW) {
                        if (!qResized[y * newW + qx]) extraFg++
                    }
                } else if (tmpl.w > newW) {
                    for (tx in 0 until -xOff) {
                        if (!tmpl.data[y * tmpl.w + tx]) extraFg++
                    }
                    for (tx in (-xOff + newW) until tmpl.w) {
                        if (!tmpl.data[y * tmpl.w + tx]) extraFg++
                    }
                }
            }
            val totalUnion = fgUnion + extraFg
            if (totalUnion > 0) {
                val score = agree.toFloat() / totalUnion
                if (score > bestScore) {
                    bestScore = score
                    bestLabel = label
                }
            }
        }
        return Pair(bestLabel, bestScore)
    }

    // ========== 公共牌识别 ==========

    fun recognizeCommunityCard(screenshot: Bitmap, cardIndex: Int): CardResult? {
        return try {
            val w = screenshot.width
            val h = screenshot.height

            // 1080x2344基准坐标
            val scaleX = w / 1080f
            val scaleY = h / 2344f

            val baseX = intArrayOf(166, 316, 466, 616, 766)
            val x1 = (baseX[cardIndex] * scaleX).toInt().coerceIn(0, w - 1)
            val y1 = (1068 * scaleY).toInt().coerceIn(0, h - 1)
            val x2 = ((baseX[cardIndex] + 144) * scaleX).toInt().coerceIn(x1 + 1, w)
            val y2 = (1272 * scaleY).toInt().coerceIn(y1 + 1, h)

            val cw = x2 - x1
            val ch = y2 - y1
            if (cw <= 0 || ch <= 0) return null

            val pixels = IntArray(cw * ch)
            screenshot.getPixels(pixels, 0, cw, x1, y1, cw, ch)

            // Check card exists
            if (!hasCard(pixels, cw, ch, 0, 0, cw, ch)) return null

            // Detect color
            val isRed = detectColor(pixels, cw, 0, (8 * scaleX).toInt(), (60 * scaleX).toInt(), (110 * scaleY).toInt())

            // Coordinates within cropped card (scaled)
            val rx1 = (8 * scaleX).toInt()
            val ry1 = (12 * scaleY).toInt()
            val rx2 = (60 * scaleX).toInt()
            val ry2 = (66 * scaleY).toInt()
            val sx1 = (8 * scaleX).toInt()
            val sy1 = (72 * scaleY).toInt()
            val sx2 = (60 * scaleX).toInt()
            val sy2 = (106 * scaleY).toInt()

            val rankMask = extractMask(pixels, cw, rx1, ry1, rx2, ry2, isRed)
            val suitMask = extractMask(pixels, cw, sx1, sy1, sx2, sy2, isRed)

            val rankTrimmed = trim(rankMask, rx2 - rx1, ry2 - ry1)
            val suitTrimmed = trim(suitMask, sx2 - sx1, sy2 - sy1)

            val (bestRank, rankScore) = match(rankTrimmed, commRankTemplates)
            val candidateSuits = if (isRed)
                commSuitTemplates.filterKeys { it in RED_SUITS }
            else
                commSuitTemplates.filterKeys { it in BLACK_SUITS }
            val (bestSuit, suitScore) = match(suitTrimmed, candidateSuits)

            if (bestRank == null || bestSuit == null) return null
            CardResult(bestRank, bestSuit, (rankScore + suitScore) / 2, rankScore, suitScore)
        } catch (e: Exception) {
            Log.w(TAG, "Community card $cardIndex failed: ${e.message}")
            null
        }
    }

    // ========== 手牌识别（连通区域分析）==========

    private data class Component(
        val id: Int,
        val size: Int,
        val xMin: Int, val xMax: Int,
        val yMin: Int, val yMax: Int,
        val cx: Float, val cy: Float
    )

    /** Flood-fill连通区域标记 */
    private fun findComponents(
        pixels: IntArray, w: Int, h: Int, isRed: Boolean
    ): Pair<List<Component>, IntArray> {
        val labels = IntArray(w * h)
        val components = ArrayList<Component>()
        var labelId = 0

        for (startY in 0 until h) {
            for (startX in 0 until w) {
                val idx = startY * w + startX
                if (labels[idx] != 0) continue
                val p = pixels[idx]
                val isContent = if (isRed) isRed(p) else isBlack(p)
                if (!isContent) continue

                labelId++
                val queue = ArrayDeque<Int>()
                queue.add(idx)
                labels[idx] = labelId

                var size = 0
                var xMin = w; var xMax = 0; var yMin = h; var yMax = 0

                while (queue.isNotEmpty()) {
                    val cur = queue.poll()
                    val cx = cur % w
                    val cy = cur / w
                    size++
                    if (cx < xMin) xMin = cx
                    if (cx > xMax) xMax = cx
                    if (cy < yMin) yMin = cy
                    if (cy > yMax) yMax = cy

                    // 4-neighbors
                    if (cx > 0) {
                        val ni = cur - 1
                        if (labels[ni] == 0) {
                            val np = pixels[ni]
                            if (if (isRed) isRed(np) else isBlack(np)) {
                                labels[ni] = labelId
                                queue.add(ni)
                            }
                        }
                    }
                    if (cx < w - 1) {
                        val ni = cur + 1
                        if (labels[ni] == 0) {
                            val np = pixels[ni]
                            if (if (isRed) isRed(np) else isBlack(np)) {
                                labels[ni] = labelId
                                queue.add(ni)
                            }
                        }
                    }
                    if (cy > 0) {
                        val ni = cur - w
                        if (labels[ni] == 0) {
                            val np = pixels[ni]
                            if (if (isRed) isRed(np) else isBlack(np)) {
                                labels[ni] = labelId
                                queue.add(ni)
                            }
                        }
                    }
                    if (cy < h - 1) {
                        val ni = cur + w
                        if (labels[ni] == 0) {
                            val np = pixels[ni]
                            if (if (isRed) isRed(np) else isBlack(np)) {
                                labels[ni] = labelId
                                queue.add(ni)
                            }
                        }
                    }
                }

                if (size >= 80) {
                    components.add(Component(
                        id = labelId, size = size,
                        xMin = xMin, xMax = xMax, yMin = yMin, yMax = yMax,
                        cx = (xMin + xMax) / 2f, cy = (yMin + yMax) / 2f
                    ))
                }
            }
        }
        return Pair(components, labels)
    }

    /** 从连通区域提取二值图 */
    private fun componentToBinary(
        labels: IntArray, w: Int, comp: Component
    ): Triple<BooleanArray, Int, Int> {
        val tw = comp.xMax - comp.xMin + 1
        val th = comp.yMax - comp.yMin + 1
        val data = BooleanArray(tw * th)
        for (y in 0 until th) {
            for (x in 0 until tw) {
                val label = labels[(comp.yMin + y) * w + (comp.xMin + x)]
                data[y * tw + x] = label != comp.id
            }
        }
        return Triple(data, tw, th)
    }


    // ===== V2.9.530: 手牌角区识别方案 =====
    // 从卡片左上角裁出rank+suit索引区域，避免中心pips和底部遮挡物干扰
    // 自动band检测分离rank/suit，"10"双component检测，plateau_ratio黑色suit分类

    fun recognizeHandCard(screenshot: Bitmap, handIndex: Int): CardResult? {
        val failReason = fun(r: String): CardResult? {
            if (handIndex == 0) hand0FailReason = r else hand1FailReason = r
            Log.w(TAG, "H${handIndex} FAIL: $r")
            return null
        }
        return try {
            val sw = screenshot.width
            val sh = screenshot.height
            val sx = sw / 1080f
            val sy = sh / 2344f

            // V2.9.538: 基于8月22日实际游戏截图实测坐标
            val baseX1 = if (handIndex == 0) 30 else 110
            val baseY1 = if (handIndex == 0) 1690 else 1670
            val cardW = 140
            val cardH = 250
            val x1 = (baseX1 * sx).toInt().coerceIn(0, sw - 1)
            val y1 = (baseY1 * sy).toInt().coerceIn(0, sh - 1)
            val x2 = ((baseX1 + cardW) * sx).toInt().coerceIn(x1 + 1, sw)
            val y2 = ((baseY1 + cardH) * sy).toInt().coerceIn(y1 + 1, sh)

            val cw = x2 - x1
            val ch = y2 - y1
            if (cw <= 0 || ch <= 0) return failReason("region_invalid")

            val pixels = IntArray(cw * ch)
            screenshot.getPixels(pixels, 0, cw, x1, y1, cw, ch)

            if (!hasCard(pixels, cw, ch, 0, 0, cw, ch)) {
                return failReason("no_card")
            }

            // 计算缩放因子（相对250px基准高度，V2.9.537从200改为250）
            val scale = ch / 250.0

            // V2.9.537: 诊断日志——分辨率/缩放/裁切
            val whiteCount = pixels.count { isWhite(it) }
            Log.d(TAG, "H${handIndex} diag: sw=$sw sh=$sh sx=${String.format("%.3f", sx)} sy=${String.format("%.3f", sy)} cw=$cw ch=$ch scale=${String.format("%.3f", scale)} white%=${String.format("%.1f", whiteCount*100.0/pixels.size)}")

            // V2.9.538: tight per-card corner参数（8/22真实截图像素级校准）
            // per-card corner：H0/H1因扇出偏移分别校准，确保bands≥2
            // H0(5♥): rank偏左x≈39, corner suit y≈143-175; H1(3♦): rank偏右x≈58, corner suit y≈156-195
            val cornerX: Int
            val cornerY: Int
            val cornerW: Int
            val cornerH: Int
            if (handIndex == 0) {
                cornerX = (35 * scale).toInt().coerceAtLeast(1)
                cornerY = (75 * scale).toInt().coerceAtLeast(1)
                cornerW = (50 * scale).toInt().coerceAtLeast(10)
                cornerH = (115 * scale).toInt().coerceAtLeast(20)
            } else {
                cornerX = (44 * scale).toInt().coerceAtLeast(1)
                cornerY = (82 * scale).toInt().coerceAtLeast(1)
                cornerW = (51 * scale).toInt().coerceAtLeast(10)
                cornerH = (128 * scale).toInt().coerceAtLeast(20)
            }

            // 确保角区不超出卡片范围
            val cx2 = (cornerX + cornerW).coerceAtMost(cw)
            val cy2 = (cornerY + cornerH).coerceAtMost(ch)
            val actualCW = cx2 - cornerX
            val actualCH = cy2 - cornerY
            if (actualCW < 10 || actualCH < 20) return failReason("corner_too_small")

            // V2.9.535: 先检测角区颜色，再用正确的isRed提取mask（原硬编码false导致红色rank牌bands=0）
            val isBlackCard = detectBlackOrRed(pixels, cw, cornerX, cornerY, cx2, cy2)
            val cornerMask = extractMask(pixels, cw, cornerX, cornerY, cx2, cy2, !isBlackCard)
            val mw = actualCW
            val mh = actualCH

            // V2.9.537: 诊断日志——角区颜色+mask内容统计
            var contentPx = 0
            for (i in cornerMask.indices) if (!cornerMask[i]) contentPx++
            Log.d(TAG, "H${handIndex} corner: ($cornerX,$cornerY)-(${cx2},${cy2}) ${actualCW}x${actualCH} isBlack=$isBlackCard maskContent=$contentPx/${cornerMask.size}(${String.format("%.1f", contentPx*100.0/cornerMask.size)}%)")

            // 自动band检测：行投影找到rank和suit两个content band
            val bands = findHandContentBands(cornerMask, mw, mh, scale)
            if (bands.size < 2) {
                // V2.9.537: bands<2时输出投影详情
                val proj = IntArray(mh)
                for (row in 0 until mh) {
                    var cnt = 0
                    for (col in 0 until mw) if (!cornerMask[row * mw + col]) cnt++
                    proj[row] = cnt
                }
                val maxProj = proj.maxOrNull() ?: 0
                val threshold = maxOf(1, mw / 20)
                Log.w(TAG, "H${handIndex} bands=${bands.size} maxProj=$maxProj threshold=$threshold mw=$mw mh=$mh")
                return failReason("bands=${bands.size}")
            }

            val rankBands = bands.subList(0, 1)
            val suitBands = bands.subList(1, bands.size)

            // ===== Rank识别 =====
            // 提取rank band的mask
            val rStart = rankBands[0].first
            val rEnd = rankBands[0].second
            val rankSubmask = BooleanArray(mw * (rEnd - rStart))
            for (row in rStart until rEnd) {
                for (col in 0 until mw) {
                    rankSubmask[(row - rStart) * mw + col] = cornerMask[row * mw + col]
                }
            }
            val rankTrimmed = trim(rankSubmask, mw, rEnd - rStart)

            // 先检测"10"（双component特征）
            val tenResult = if (rankTrimmed != null)
                detectTenDualComp(rankTrimmed.first, rankTrimmed.second, rankTrimmed.third, scale)
            else null

            val bestRank: String?
            val rankConf: Double

            if (tenResult != null) {
                bestRank = "10"
                rankConf = tenResult
            } else {
                val rm = match(rankTrimmed, handRankTemplates)
                bestRank = rm.first
                rankConf = rm.second.toDouble()
            }

            // ===== Suit识别 =====
            val sStart = suitBands[0].first
            val sMergeRow = findSuitMergeRow(cornerMask, mw, sStart, suitBands[suitBands.size - 1].second)
            val sEnd = if (sMergeRow > sStart) sMergeRow else suitBands[suitBands.size - 1].second

            val suitSubmask = BooleanArray(mw * (sEnd - sStart))
            for (row in sStart until sEnd) {
                for (col in 0 until mw) {
                    suitSubmask[(row - sStart) * mw + col] = cornerMask[row * mw + col]
                }
            }
            val suitTrimmed = trim(suitSubmask, mw, sEnd - sStart)

            // V2.9.535: 复用前面已检测的颜色结果，不重复计算
            val isBlack = isBlackCard

            val suitTemplates = if (isBlack) {
                handSuitTemplates.filterKeys { it in BLACK_SUITS }
            } else {
                handSuitTemplates.filterKeys { it in RED_SUITS }
            }

            var bestSuit: String? = null
            var suitConf = 0.0

            if (isBlack && suitTrimmed != null) {
                // plateau_ratio分类：club>0.30, spade<0.20
                val ratio = computePlateauRatio(suitTrimmed.first, suitTrimmed.second, suitTrimmed.third)
                bestSuit = if (ratio > 0.30) "c" else "s"
                suitConf = if (ratio > 0.30)
                    0.5 + (ratio - 0.30) * 1.5
                else
                    0.5 + (0.30 - ratio) * 1.5
                suitConf = suitConf.coerceIn(0.5, 0.9)
            } else {
                // 红色suit用IoU匹配（♥ vs ♦）
                val sm = match(suitTrimmed, suitTemplates)
                bestSuit = sm.first
                suitConf = sm.second.toDouble()
            }

            if (bestRank == null || bestSuit == null) {
                return failReason("match_fail rank=$bestRank suit=$bestSuit")
            }

            val conf = (rankConf * 0.55 + suitConf * 0.45).coerceIn(0.0, 1.0)
            if (handIndex == 0) hand0FailReason = "" else hand1FailReason = ""
            Log.d(TAG, "H${handIndex}: ${bestRank}${bestSuit} conf=${String.format("%.2f", conf)} r=${String.format("%.2f", rankConf)} s=${String.format("%.2f", suitConf)}")
            CardResult(bestRank, bestSuit, conf.toFloat(), rankConf.toFloat(), suitConf.toFloat())
        } catch (e: Exception) {
            failReason("exception: ${e.message}")
        }
    }

    /**
     * V2.9.541: 自适应段高度过滤——不依赖gap跨间隙合并。
     * 1. 行投影找所有连续content段（不合并）
     * 2. 过滤高度<15px的噪点碎段（边缘抗锯齿产生的1-6px碎段）
     * 3. 前2个主体段 = rank + suit（按y顺序）
     * 根因：V2.9.538 gap=9跨过rank与suit间仅~2行的真实间隙，把两段合并成1个band。
     */
    private fun findHandContentBands(mask: BooleanArray, w: Int, h: Int, scale: Double): List<Pair<Int, Int>> {
        val proj = IntArray(h)
        for (row in 0 until h) {
            var cnt = 0
            for (col in 0 until w) if (!mask[row * w + col]) cnt++
            proj[row] = cnt
        }

        val threshold = maxOf(1, w / 20)
        val content = BooleanArray(h) { proj[it] >= threshold }

        // 1. 找所有连续content段（不跨间隙合并）
        val rawBands = mutableListOf<Pair<Int, Int>>()
        var start = -1
        for (i in 0 until h) {
            if (content[i]) {
                if (start < 0) start = i
            } else {
                if (start >= 0) {
                    rawBands.add(Pair(start, i))
                    start = -1
                }
            }
        }
        if (start >= 0) rawBands.add(Pair(start, h))

        // 2. 过滤高度<minBandH的噪点碎段（rank高52-59px, suit高40-49px, 噪点1-6px）
        val minBandH = maxOf(8, (15 * scale).toInt())
        val majorBands = rawBands.filter { (it.second - it.first) >= minBandH }

        if (majorBands.size < 2) {
            val rawInfo = rawBands.joinToString(",") { "${it.first}-${it.second}(h=${it.second - it.first})" }
            val majorInfo = majorBands.joinToString(",") { "${it.first}-${it.second}(h=${it.second - it.first})" }
            Log.w(TAG, "bands V3: raw=${rawBands.size}[$rawInfo] major=${majorBands.size}[$majorInfo] minH=$minBandH")
        }

        // 3. 前2个主体段按y顺序
        return majorBands.take(2)
    }

    /** 在suit band中找到与中心pips合并的行（宽度开始显著增长的位置） */
    private fun findSuitMergeRow(mask: BooleanArray, w: Int, suitStart: Int, suitEnd: Int): Int {
        val widths = IntArray(suitEnd - suitStart)
        var maxWidth = 0
        for (i in 0 until widths.size) {
            var cnt = 0
            val row = suitStart + i
            for (col in 0 until w) if (!mask[row * w + col]) cnt++  // V2.9.534-fix: mask极性修正
            widths[i] = cnt
            if (cnt > maxWidth) maxWidth = cnt
        }
        if (maxWidth <= 2) return suitEnd

        // 找平台：连续≥3行宽度稳定（差≤2）且≥max的50%
        var plateauEnd = -1
        var runLen = 1
        for (i in 1 until widths.size) {
            if (kotlin.math.abs(widths[i] - widths[i - 1]) <= 2 && widths[i] >= maxWidth * 0.5f) {
                runLen++
                if (runLen >= 3 && plateauEnd < 0) plateauEnd = i
            } else {
                runLen = 1
                plateauEnd = -1
            }
            if (plateauEnd >= 0 && i > plateauEnd) {
                if (widths[i] > widths[plateauEnd] * 1.15f) {
                    return suitStart + i
                }
            }
        }
        return suitEnd
    }

    /** "10"双component检测：左窄(aspect<0.45)+gap≥2+总宽>右×1.3 */
    private fun detectTenDualComp(mask: BooleanArray, w: Int, h: Int, scale: Double): Double? {
        if (w <= 0 || h <= 0) return null

        val visited = BooleanArray(w * h)
        val comps = mutableListOf<IntArray>()  // [xMin, xMax, size]

        for (startIdx in 0 until w * h) {
            if (!mask[startIdx] && !visited[startIdx]) {  // V2.9.534-fix: !mask=内容
                var xMin = startIdx % w; var xMax = xMin
                var size = 0
                val stack = mutableListOf(startIdx)
                visited[startIdx] = true
                while (stack.isNotEmpty()) {
                    val idx = stack.removeAt(stack.size - 1)
                    size++
                    val cx = idx % w; val cy = idx / w
                    if (cx < xMin) xMin = cx
                    if (cx > xMax) xMax = cx
                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            if (dx == 0 && dy == 0) continue
                            val nx = cx + dx; val ny = cy + dy
                            if (nx in 0 until w && ny in 0 until h) {
                                val ni = ny * w + nx
                                if (!mask[ni] && !visited[ni]) {  // V2.9.534-fix: !mask=内容
                                    visited[ni] = true
                                    stack.add(ni)
                                }
                            }
                        }
                    }
                }
                if (size >= maxOf(10, (h * 0.15).toInt())) {
                    comps.add(intArrayOf(xMin, xMax, size))
                }
            }
        }

        if (comps.size != 2) return null
        comps.sortBy { it[0] }

        val leftW = comps[0][1] - comps[0][0] + 1
        val rightW = comps[1][1] - comps[1][0] + 1
        val leftAspect = leftW.toFloat() / h.toFloat()
        val gap = comps[1][0] - comps[0][1] - 1
        val totalW = comps[1][1] - comps[0][0] + 1

        if (leftAspect >= 0.45f) return null
        if (gap < maxOf(2, (2 * scale).toInt())) return null
        if (totalW <= (rightW * 1.3).toInt()) return null

        return 0.85
    }

    /** 计算suit顶部宽度平台占比（club>0.30, spade<0.20） */
    private fun computePlateauRatio(mask: BooleanArray, w: Int, h: Int): Double {
        if (h <= 0 || w <= 0) return 0.0

        val heights = IntArray(h)
        var maxW = 0
        for (i in 0 until h) {
            var left = -1; var right = -1
            for (x in 0 until w) {
                if (!mask[i * w + x]) { if (left < 0) left = x; right = x }  // V2.9.534-fix: !mask=内容
            }
            heights[i] = if (left >= 0) right - left + 1 else 0
            if (heights[i] > maxW) maxW = heights[i]
        }
        if (maxW <= 2) return 0.0

        // 找平台期：连续≥3行宽度稳定（差≤2）且≥max的60%
        var plateauStart = -1; var plateauEnd = -1
        var runStart = 0
        for (i in 1 until h) {
            if (kotlin.math.abs(heights[i] - heights[i - 1]) <= 2 && heights[i] >= (maxW * 0.6).toInt()) {
                if (plateauStart < 0) plateauStart = runStart
                plateauEnd = i
            } else {
                if (plateauStart >= 0 && plateauEnd - plateauStart >= 2) break
                plateauStart = -1; plateauEnd = -1; runStart = i
            }
        }

        if (plateauStart < 0 || plateauEnd - plateauStart < 2) return 0.0
        val plateauRows = plateauEnd - plateauStart + 1
        val totalRows = maxOf(1, (h * 0.5).toInt()).coerceAtMost(h)
        return plateauRows.toDouble() / totalRows.toDouble()
    }

    /**
     * V2.9.542: 手牌角区颜色检测，直接复用公共牌的detectColor逻辑。
     * detectColor返回true=红色（红桃/方块），手牌需要true=黑色（黑桃/梅花），
     * 所以取反。旧版独立实现因采样范围错误+白色背景计入light导致黑桃/梅花全部误判。
     */
    private fun detectBlackOrRed(pixels: IntArray, stride: Int, x1: Int, y1: Int, x2: Int, y2: Int): Boolean {
        return !detectColor(pixels, stride, x1, y1, x2, y2)
    }

    /** 一次性识别所有牌：2张手牌 + 最多5张公共牌 */
    fun recognizeAllCards(screenshot: Bitmap): Pair<List<CardResult>, List<CardResult>> {
        val holeCards = ArrayList<CardResult>()
        val communityCards = ArrayList<CardResult>()
        val diag = StringBuilder()

        diag.append("bitmap=${screenshot.width}x${screenshot.height};")
        Log.d(TAG, "本地CV开始: bitmap=${screenshot.width}x${screenshot.height}")

        // 手牌（角区识别 V2.9.530）
        for (i in 0..1) {
            val result = recognizeHandCard(screenshot, i)
            if (result != null) {
                holeCards.add(result)
                diag.append("H$i=OK(${result.rank}${result.suit},c=%.2f);".format(result.confidence))
            } else {
                val reason = if (i == 0) hand0FailReason else hand1FailReason
                diag.append("H$i=FAIL($reason);")
            }
        }

        // 公共牌
        for (i in 0..4) {
            val result = recognizeCommunityCard(screenshot, i)
            if (result != null) {
                communityCards.add(result)
                diag.append("C$i=OK(${result.rank}${result.suit});")
            }
        }

        diag.append("hand=${holeCards.size}/2,comm=${communityCards.size}/5")
        lastDiag = diag.toString()
        Log.d(TAG, "本地CV完成: hand=${holeCards.size}/2 comm=${communityCards.size}/5")
        return Pair(holeCards, communityCards)
    }

    /** 检查手牌是否在缓存中变化（用于判断是否需要走API） */
    fun quickCardHash(screenshot: Bitmap): Long {
        return try {
            val sw = screenshot.width
            val sh = screenshot.height
            val scaleX = sw / 1080f
            val scaleY = sh / 2344f
            var hash = 0L
            val samples = intArrayOf(
                (60*scaleX).toInt(), (1800*scaleY).toInt(),
                (200*scaleX).toInt(), (1800*scaleY).toInt(),
                (100*scaleX).toInt(), (1850*scaleY).toInt(),
                (250*scaleX).toInt(), (1850*scaleY).toInt()
            )
            var i = 0
            while (i < samples.size) {
                val x = samples[i].coerceIn(0, sw - 1)
                val y = samples[i+1].coerceIn(0, sh - 1)
                hash = hash * 31 + screenshot.getPixel(x, y)
                i += 2
            }
            hash
        } catch (_: Exception) { 0L }
    }
}
