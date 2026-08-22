package com.pokerhelper.app

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
        return arr[y * w + x]
    }

    private fun isRed(pixel: Int): Boolean {
        val r = pixel shr 16 and 0xFF
        val g = pixel shr 8 and 0xFF
        val b = pixel and 0xFF
        return r > 100 && g < 90 && b < 90
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
            // Resize query to template height
            val qResized = resizeBinary(qData, qW, qH, newW, tmpl.h)
            val minW = minOf(newW, tmpl.w)

            var fgUnion = 0
            var agree = 0
            for (y in 0 until tmpl.h) {
                for (x in 0 until minW) {
                    val q = qResized[y * newW + x]
                    val t = tmpl.data[y * tmpl.w + x]
                    if (!q || !t) {
                        fgUnion++
                        if (q == t) agree++
                    }
                }
            }
            if (fgUnion > 0) {
                val score = agree.toFloat() / fgUnion
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
            val x1 = (baseX[cardIndex] * scaleX).toInt()
            val y1 = (1068 * scaleY).toInt()
            val x2 = ((baseX[cardIndex] + 144) * scaleX).toInt()
            val y2 = (1272 * scaleY).toInt()

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

    fun recognizeHandCard(screenshot: Bitmap, handIndex: Int): CardResult? {
        return try {
            val sw = screenshot.width
            val sh = screenshot.height
            val scaleX = sw / 1080f
            val scaleY = sh / 2344f

            val x1: Int; val y1: Int; val x2: Int; val y2: Int
            if (handIndex == 0) {
                x1 = (35 * scaleX).toInt()
                y1 = (1745 * scaleY).toInt()
                x2 = (175 * scaleX).toInt()
                y2 = (1945 * scaleY).toInt()
            } else {
                x1 = (165 * scaleX).toInt()
                y1 = (1745 * scaleY).toInt()
                x2 = (325 * scaleX).toInt()
                y2 = (1945 * scaleY).toInt()
            }

            val cw = x2 - x1
            val ch = y2 - y1
            if (cw <= 0 || ch <= 0) return null

            val pixels = IntArray(cw * ch)
            screenshot.getPixels(pixels, 0, cw, x1, y1, cw, ch)

            // Check card exists
            if (!hasCard(pixels, cw, ch, 0, 0, cw, ch)) return null

            // Detect color from top-left area
            val isRed = detectColor(pixels, cw, 0, 0, cw / 2, ch / 3)

            // Find connected components
            val (components, labels) = findComponents(pixels, cw, ch, isRed)

            // Select rank (topmost left component) and suit (below rank)
            var rankComp: Component? = null
            var suitComp: Component? = null

            for (c in components) {
                if (c.xMin < cw * 0.55f) {
                    if (c.cy < ch * 0.42f && c.size >= 200) {
                        if (rankComp == null || c.size > rankComp!!.size) {
                            rankComp = c
                        }
                    } else if (c.cy < ch * 0.65f && c.size >= 200) {
                        if (suitComp == null || c.xMin < suitComp!!.xMin) {
                            suitComp = c
                        }
                    }
                }
            }

            if (rankComp == null || suitComp == null) {
                Log.d(TAG, "Hand$handIndex: could not find rank/suit components (${components.size} found)")
                return null
            }

            val rankBinary = componentToBinary(labels, cw, rankComp!!)
            val suitBinary = componentToBinary(labels, cw, suitComp!!)

            val (bestRank, rankScore) = match(rankBinary, handRankTemplates)
            val candidateSuits = if (isRed)
                handSuitTemplates.filterKeys { it in RED_SUITS }
            else
                handSuitTemplates.filterKeys { it in BLACK_SUITS }
            val (bestSuit, suitScore) = match(suitBinary, candidateSuits)

            if (bestRank == null || bestSuit == null) return null
            CardResult(bestRank, bestSuit, (rankScore + suitScore) / 2, rankScore, suitScore)
        } catch (e: Exception) {
            Log.w(TAG, "Hand card $handIndex failed: ${e.message}")
            null
        }
    }

    /** 一次性识别所有牌：2张手牌 + 最多5张公共牌 */
    fun recognizeAllCards(screenshot: Bitmap): Pair<List<CardResult>, List<CardResult>> {
        val holeCards = ArrayList<CardResult>()
        val communityCards = ArrayList<CardResult>()

        // 手牌
        for (i in 0..1) {
            recognizeHandCard(screenshot, i)?.let { holeCards.add(it) }
        }

        // 公共牌
        for (i in 0..4) {
            recognizeCommunityCard(screenshot, i)?.let { communityCards.add(it) }
        }

        return Pair(holeCards, communityCards)
    }

    /** 检查手牌是否在缓存中变化（用于判断是否需要走API） */
    fun quickCardHash(screenshot: Bitmap): Long {
        // 简单采样手牌区域像素作为hash
        return try {
            val sw = screenshot.width
            val sh = screenshot.height
            val scaleX = sw / 1080f
            val scaleY = sh / 2344f
            var hash = 0L
            // 采样手牌区域关键点
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
