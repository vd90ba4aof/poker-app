package com.pokerhelper.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.util.ArrayDeque

/**
 * 本地操作区识别引擎
 * 纯像素操作，无第三方依赖，单帧识别<4ms
 *
 * 识别内容：
 * - 中间按钮：是否面对下注(facing_bet)、跟注金额(call_amount)
 * - 右侧按钮：最小加注金额(min_raise)
 * - 预设区域：3个快捷金额(presets)
 *
 * 技术方案：
 * - 黄色mask提取金额数字（白色是"加注"/"跟注"标签，不用）
 * - flood-fill连通区域 + 碎片吸收 + 列投影山谷分割
 * - 等比缩放居中模板匹配（10个数字模板）
 * - fragment-tolerant二次匹配：低置信度时裁剪侧边碎片再匹配
 *
 * V2.9.519: 替代VLM操作区识别，pipeline完全脱离网络
 */
class LocalActionRecognizer private constructor(private val context: Context) {

    companion object {
        private const val TAG = "LocalActionRecognizer"

        @Volatile
        private var instance: LocalActionRecognizer? = null

        fun getInstance(context: Context): LocalActionRecognizer {
            return instance ?: synchronized(this) {
                instance ?: LocalActionRecognizer(context.applicationContext).also {
                    it.loadTemplates()
                }
            }
        }

        // 基准坐标（1080x2344）
        private const val BTN_Y1 = 2215
        private const val BTN_Y2 = 2295
        private const val BTN2_X1 = 370
        private const val BTN2_X2 = 700
        private const val BTN3_X1 = 715
        private const val BTN3_X2 = 1050
        private const val PRESET_Y1 = 1680
        private const val PRESET_Y2 = 2200
        private const val PRESET_X1 = 830
        private const val PRESET_X2 = 955

        // 数字尺寸约束
        private const val DIGIT_MIN_W = 8
        private const val DIGIT_MAX_W = 42
        private const val DIGIT_MIN_H = 25
        private const val DIGIT_MAX_H = 48
        private const val DIGIT_MIN_AREA = 80

        // 模板归一化尺寸
        private const val TMPL_H = 36
        private const val TMPL_W = 26
    }

    private data class Template(val data: BooleanArray, val w: Int, val h: Int) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Template) return false
            return data.contentEquals(other.data) && w == other.w && h == other.h
        }
        override fun hashCode(): Int = data.contentHashCode() * 31 + w * 17 + h
    }

    // 10个数字模板，data=true表示内容像素
    private val digitTemplates = HashMap<Char, Template>()

    data class ActionResult(
        val facingBet: Boolean,
        val callAmount: Int?,
        val minRaise: Int?,
        val presets: List<Int>,
        val confidence: Float
    )

    @Volatile
    private var loaded = false

    fun loadTemplates() {
        if (loaded) return
        try {
            for (d in 0..9) {
                loadAssetTemplate("digit_templates/$d.png")?.let {
                    digitTemplates[d.toString()[0]] = it
                }
            }
            loaded = digitTemplates.size == 10
            Log.i(TAG, "Digit templates loaded: ${digitTemplates.size}/10")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load digit templates", e)
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
            // 灰度<128=内容(true)，白色=背景(false)
            val data = BooleanArray(w * h)
            for (i in pixels.indices) {
                val p = pixels[i]
                val gray = ((p shr 16 and 0xFF) * 30 + (p shr 8 and 0xFF) * 59 + (p and 0xFF) * 11) / 100
                data[i] = gray < 128
            }
            Template(data, w, h)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load template: $path", e)
            null
        }
    }

    // ========== 颜色mask ==========

    private fun isYellow(pixel: Int): Boolean {
        val r = pixel shr 16 and 0xFF
        val g = pixel shr 8 and 0xFF
        val b = pixel and 0xFF
        return r > 160 && g > 130 && b < 120 && r > b + 60
    }

    /** 提取区域内黄色像素的二值mask，true=黄色内容 */
    private fun yellowMask(pixels: IntArray, w: Int, h: Int, x1: Int, y1: Int, x2: Int, y2: Int): BooleanArray {
        // clamp到像素数组边界，防止缩放后越界
        val cx1 = x1.coerceIn(0, w - 1)
        val cx2 = x2.coerceIn(cx1 + 1, w)
        val cy1 = y1.coerceIn(0, h - 1)
        val cy2 = y2.coerceIn(cy1 + 1, h)
        val rw = cx2 - cx1
        val rh = cy2 - cy1
        val mask = BooleanArray(rw * rh)
        for (y in 0 until rh) {
            for (x in 0 until rw) {
                mask[y * rw + x] = isYellow(pixels[(cy1 + y) * w + (cx1 + x)])
            }
        }
        return mask
    }

    // ========== 连通区域 ==========

    private data class Comp(
        var x1: Int, var x2: Int,
        var y1: Int, var y2: Int,
        var area: Int
    ) {
        val w get() = x2 - x1 + 1
        val h get() = y2 - y1 + 1
        fun copy() = Comp(x1, x2, y1, y2, area)
    }

    /** flood-fill标记连通区域 */
    private fun findComponents(mask: BooleanArray, w: Int, h: Int): List<Comp> {
        val labels = IntArray(w * h)
        val comps = ArrayList<Comp>()
        var labelId = 0

        for (startY in 0 until h) {
            for (startX in 0 until w) {
                val idx = startY * w + startX
                if (labels[idx] != 0 || !mask[idx]) continue

                labelId++
                val queue = ArrayDeque<Int>()
                queue.add(idx)
                labels[idx] = labelId

                var area = 0
                var xMin = w; var xMax = 0; var yMin = h; var yMax = 0

                while (queue.isNotEmpty()) {
                    val cur = queue.poll()
                    val cx = cur % w
                    val cy = cur / w
                    area++
                    if (cx < xMin) xMin = cx
                    if (cx > xMax) xMax = cx
                    if (cy < yMin) yMin = cy
                    if (cy > yMax) yMax = cy

                    if (cx > 0) {
                        val ni = cur - 1
                        if (labels[ni] == 0 && mask[ni]) { labels[ni] = labelId; queue.add(ni) }
                    }
                    if (cx < w - 1) {
                        val ni = cur + 1
                        if (labels[ni] == 0 && mask[ni]) { labels[ni] = labelId; queue.add(ni) }
                    }
                    if (cy > 0) {
                        val ni = cur - w
                        if (labels[ni] == 0 && mask[ni]) { labels[ni] = labelId; queue.add(ni) }
                    }
                    if (cy < h - 1) {
                        val ni = cur + w
                        if (labels[ni] == 0 && mask[ni]) { labels[ni] = labelId; queue.add(ni) }
                    }
                }

                if (area >= 5) {
                    comps.add(Comp(xMin, xMax, yMin, yMax, area))
                }
            }
        }
        return comps
    }

    // ========== 数字分割流水线 ==========

    private fun verticalOverlap(a: Comp, b: Comp): Int {
        val yA = maxOf(a.y1, b.y1)
        val yB = minOf(a.y2, b.y2)
        return if (yB < yA) 0 else yB - yA + 1
    }

    /**
     * 分割数字：
     * 1. 合并水平gap<=1的组件（JPEG断裂）
     * 2. 吸收小碎片到最近的垂直对齐邻居
     * 3. 列投影山谷分割超宽块
     * 4. 尺寸过滤 + 填洞 + 紧bbox
     */
    private fun segmentDigits(mask: BooleanArray, w: Int, h: Int): List<Triple<BooleanArray, Int, Int>> {
        // 统计内容像素（mask中true=黄色内容）
        var contentPx = 0
        for (v in mask) if (v) contentPx++
        if (contentPx < 30) return emptyList()

        var comps = findComponents(mask, w, h)
        if (comps.isEmpty()) return emptyList()
        comps.sortBy { it.x1 }

        val maxH = comps.maxOf { it.h }

        // Step 1: 合并gap<=1
        val merged = ArrayList<Comp>()
        for (c in comps) {
            if (merged.isNotEmpty()) {
                val gap = c.x1 - merged.last().x2 - 1
                if (gap <= 1) {
                    val p = merged.last()
                    p.x1 = minOf(p.x1, c.x1)
                    p.x2 = maxOf(p.x2, c.x2)
                    p.y1 = minOf(p.y1, c.y1)
                    p.y2 = maxOf(p.y2, c.y2)
                    p.area += c.area
                    continue
                }
            }
            merged.add(c.copy())
        }

        // Step 2: 吸收小碎片
        repeat(10) {
            if (merged.size <= 1) return@repeat
            var si = -1
            for (i in merged.indices) {
                val c = merged[i]
                if (c.h < maxH * 0.75f || c.area < 150) {
                    if (si < 0 || c.area < merged[si].area) si = i
                }
            }
            if (si < 0) return@repeat

            val c = merged[si]
            val ch = c.h
            var bestJ = -1
            var bestScore = -999

            for (j in merged.indices) {
                if (j == si) continue
                val m = merged[j]
                val hgap = minOf(kotlin.math.abs(c.x1 - m.x2), kotlin.math.abs(m.x1 - c.x2))
                if (hgap > 20) continue
                val mh = m.h
                val overlap = verticalOverlap(c, m)
                val vo = overlap.toFloat() / maxOf(ch, 1)

                var absorb = false
                if (hgap <= 3) {
                    absorb = true
                } else if (hgap <= 8 && vo >= 0.80f) {
                    val fragBelow = c.y2 >= m.y2
                    val fragAbove = c.y1 <= m.y1
                    if (fragBelow || fragAbove) absorb = true
                }
                if (!absorb) continue

                val score = (vo * 1000 + mh - hgap * 5).toInt()
                if (score > bestScore) {
                    bestScore = score
                    bestJ = j
                }
            }

            if (bestJ >= 0) {
                val m = merged[bestJ]
                m.x1 = minOf(m.x1, c.x1)
                m.x2 = maxOf(m.x2, c.x2)
                m.y1 = minOf(m.y1, c.y1)
                m.y2 = maxOf(m.y2, c.y2)
                m.area += c.area
                merged.removeAt(si)
            } else {
                return@repeat
            }
        }

        // Step 3: 列投影山谷分割超宽块
        val resultComps = ArrayList<Comp>()
        for (c in merged) {
            val cw = c.w
            if (cw > 30) {
                val regionH = c.h
                val colSum = IntArray(cw)
                for (x in 0 until cw) {
                    var s = 0
                    for (y in 0 until regionH) {
                        if (mask[(c.y1 + y) * w + (c.x1 + x)]) s++
                    }
                    colSum[x] = s
                }
                val maxCol = colSum.maxOrNull() ?: 0
                if (maxCol == 0) {
                    resultComps.add(c)
                    continue
                }

                var bestX = -1
                var bestRatio = 1.0f
                val sx = (cw * 0.2f).toInt()
                val ex = (cw * 0.8f).toInt()
                for (x in sx until ex) {
                    val ratio = colSum[x].toFloat() / maxCol
                    if (ratio < bestRatio) {
                        bestRatio = ratio
                        bestX = x
                    }
                }

                if (bestX > 0 && bestRatio < 0.3f) {
                    // 检查山谷在中间60%高度是否为空
                    val midTop = (regionH * 0.20f).toInt()
                    val midBot = (regionH * 0.80f).toInt()
                    var valleyMidPx = 0
                    var leftMidPx = 0
                    var rightMidPx = 0
                    for (y in midTop until midBot) {
                        if (mask[(c.y1 + y) * w + (c.x1 + bestX)]) valleyMidPx++
                        for (x in 0 until bestX) {
                            if (mask[(c.y1 + y) * w + (c.x1 + x)]) leftMidPx++
                        }
                        for (x in bestX until cw) {
                            if (mask[(c.y1 + y) * w + (c.x1 + x)]) rightMidPx++
                        }
                    }
                    val midRows = midBot - midTop
                    val valleyEmpty = valleyMidPx.toFloat() / midRows < 0.15f
                    val bothExist = leftMidPx > 30 && rightMidPx > 30

                    if (valleyEmpty && bothExist) {
                        val c1 = c.copy()
                        c1.x2 = c.x1 + bestX - 1
                        val c2 = c.copy()
                        c2.x1 = c.x1 + bestX
                        resultComps.add(c1)
                        resultComps.add(c2)
                    } else {
                        resultComps.add(c)
                    }
                } else {
                    resultComps.add(c)
                }
            } else {
                resultComps.add(c)
            }
        }

        // Step 4: 提取 + 填洞 + 紧bbox + 尺寸过滤
        val result = ArrayList<Triple<BooleanArray, Int, Int>>()
        for (c in resultComps) {
            if (c.w < DIGIT_MIN_W) continue
            val rw = c.w
            val rh = c.h
            // 提取区域
            val d = BooleanArray(rw * rh)
            for (y in 0 until rh) {
                for (x in 0 until rw) {
                    d[y * rw + x] = mask[(c.y1 + y) * w + (c.x1 + x)]
                }
            }
            // 填洞（flood-fill背景，剩余的hole设为true）
            fillHoles(d, rw, rh)

            // 紧bbox
            var minX = rw; var maxX = -1; var minY = rh; var maxY = -1
            for (y in 0 until rh) {
                for (x in 0 until rw) {
                    if (d[y * rw + x]) {
                        if (x < minX) minX = x
                        if (x > maxX) maxX = x
                        if (y < minY) minY = y
                        if (y > maxY) maxY = y
                    }
                }
            }
            if (maxX < 0) continue
            val tw = maxX - minX + 1
            val th = maxY - minY + 1
            if (tw < DIGIT_MIN_W || tw > DIGIT_MAX_W) continue
            if (th < DIGIT_MIN_H || th > DIGIT_MAX_H) continue

            val trimmed = BooleanArray(tw * th)
            for (y in 0 until th) {
                System.arraycopy(d, (minY + y) * rw + minX, trimmed, y * tw, tw)
            }
            // 统计内容像素数（BooleanArray无count{}扩展，手写循环）
            var contentPx = 0
            for (v in trimmed) if (v) contentPx++
            if (contentPx < DIGIT_MIN_AREA) continue
            result.add(Triple(trimmed, tw, th))
        }
        return result
    }

    /** 填洞：从4个角flood-fill背景，未访问到的内容像素（hole）设为true */
    private fun fillHoles(mask: BooleanArray, w: Int, h: Int) {
        val visited = BooleanArray(w * h)
        val queue = ArrayDeque<Int>()

        // 从4条边的背景像素开始
        fun seed(x: Int, y: Int) {
            if (x < 0 || x >= w || y < 0 || y >= h) return
            val idx = y * w + x
            if (!visited[idx] && !mask[idx]) {
                visited[idx] = true
                queue.add(idx)
            }
        }
        for (x in 0 until w) { seed(x, 0); seed(x, h - 1) }
        for (y in 0 until h) { seed(0, y); seed(w - 1, y) }

        while (queue.isNotEmpty()) {
            val cur = queue.poll()
            val cx = cur % w
            val cy = cur / w
            if (cx > 0) { val ni = cur - 1; if (!visited[ni] && !mask[ni]) { visited[ni] = true; queue.add(ni) } }
            if (cx < w - 1) { val ni = cur + 1; if (!visited[ni] && !mask[ni]) { visited[ni] = true; queue.add(ni) } }
            if (cy > 0) { val ni = cur - w; if (!visited[ni] && !mask[ni]) { visited[ni] = true; queue.add(ni) } }
            if (cy < h - 1) { val ni = cur + w; if (!visited[ni] && !mask[ni]) { visited[ni] = true; queue.add(ni) } }
        }
        // 未访问到的非背景像素 = hole，设为内容
        for (i in mask.indices) {
            if (!visited[i]) mask[i] = true
        }
    }

    // ========== 模板匹配 ==========

    /** 最近邻缩放 */
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

    /** 居中到TMPL_W x TMPL_H画布 */
    private fun centerCanvas(mask: BooleanArray, w: Int, h: Int): BooleanArray {
        val canvas = BooleanArray(TMPL_W * TMPL_H)
        val y0 = maxOf(0, (TMPL_H - h) / 2)
        val x0 = maxOf(0, (TMPL_W - w) / 2)
        val bh = minOf(h, TMPL_H - y0)
        val bw = minOf(w, TMPL_W - x0)
        for (y in 0 until bh) {
            System.arraycopy(mask, y * w, canvas, (y0 + y) * TMPL_W + x0, bw)
        }
        return canvas
    }

    private fun iouScore(q: BooleanArray, t: BooleanArray): Float {
        var inter = 0
        var union = 0
        for (i in q.indices) {
            if (q[i] || t[i]) {
                union++
                if (q[i] && t[i]) inter++
            }
        }
        return if (union > 0) inter.toFloat() / union else 0f
    }

    /** 单数字匹配，带fragment-tolerant二次匹配 */
    private fun matchDigit(query: Triple<BooleanArray, Int, Int>): Pair<Char, Float> {
        val (qData, qW, qH) = query
        if (qH == 0 || qW == 0) return Pair('?', 0f)

        // 主匹配
        val scale = TMPL_H.toFloat() / qH
        val newW = (qW * scale).toInt().coerceAtLeast(1)
        val r = resizeBinary(qData, qW, qH, newW, TMPL_H)
        val q = centerCanvas(r, newW, TMPL_H)

        var bestCh = '?'
        var bestScore = 0f
        for ((ch, tmpl) in digitTemplates) {
            val sc = iouScore(q, tmpl.data)
            if (sc > bestScore) { bestScore = sc; bestCh = ch }
        }

        // 低置信度 + 超宽（含碎片）→ fragment-tolerant二次匹配
        if (bestScore < 0.55f && qW > 30) {
            val colSum = IntArray(qW)
            val rowSum = IntArray(qH)
            for (y in 0 until qH) {
                for (x in 0 until qW) {
                    if (qData[y * qW + x]) { colSum[x]++; rowSum[y]++ }
                }
            }
            val maxCol = colSum.maxOrNull() ?: 0
            val maxRow = rowSum.maxOrNull() ?: 0
            if (maxCol > 0 && maxRow > 0) {
                // 找最长连续dense列 = 主字形x范围
                val thresh = (maxCol * 0.30f).toInt()
                var bestS = 0; var bestE = -1; var curS = -1
                for (x in 0 until qW) {
                    if (colSum[x] >= thresh) {
                        if (curS < 0) curS = x
                        if (x - curS > bestE - bestS) { bestS = curS; bestE = x }
                    } else {
                        curS = -1
                    }
                }
                val left = maxOf(0, bestS - 1)
                val right = minOf(qW, bestE + 2)

                // y方向裁剪
                val rowThresh = (maxRow * 0.30f).toInt()
                var top = 0; var bot = qH - 1
                while (top < qH && rowSum[top] < rowThresh) top++
                while (bot > 0 && rowSum[bot] < rowThresh) bot--
                bot = minOf(qH - 1, bot + 1)

                val cw = right - left
                val ch2 = bot - top + 1
                if (ch2 >= 20 && cw >= 8) {
                    val cropped = BooleanArray(cw * ch2)
                    for (y in 0 until ch2) {
                        System.arraycopy(qData, (top + y) * qW + left, cropped, y * cw, cw)
                    }
                    val scale2 = TMPL_H.toFloat() / ch2
                    val newW2 = (cw * scale2).toInt().coerceAtLeast(1)
                    val r2 = resizeBinary(cropped, cw, ch2, newW2, TMPL_H)
                    val q2 = centerCanvas(r2, newW2, TMPL_H)
                    for ((ch, tmpl) in digitTemplates) {
                        val sc = iouScore(q2, tmpl.data)
                        if (sc > bestScore) { bestScore = sc; bestCh = ch }
                    }
                }
            }
        }
        return Pair(bestCh, bestScore)
    }

    // ========== 行检测（预设金额）==========

    private data class RowBox(val y1: Int, val y2: Int, val x1: Int, val x2: Int)

    /** 扫描区域内黄色像素行，按row_gap分割成独立行 */
    private fun findAmountRows(
        pixels: IntArray, sw: Int, sh: Int,
        y1: Int, y2: Int, x1: Int, x2: Int,
        minH: Int = 20, rowGap: Int = 30
    ): List<RowBox> {
        val rw = x2 - x1
        val rh = y2 - y1
        val rowSum = IntArray(rh)
        for (y in 0 until rh) {
            var s = 0
            for (x in 0 until rw) {
                if (isYellow(pixels[(y1 + y) * sw + (x1 + x)])) s++
            }
            rowSum[y] = s
        }

        val rows = ArrayList<RowBox>()
        var inRow = false
        var start = 0
        var gap = 0
        for (y in 0 until rh) {
            if (rowSum[y] > 10) {
                if (!inRow) { inRow = true; start = y }
                gap = 0
            } else if (inRow) {
                gap++
                if (gap >= rowGap) {
                    val end = y - gap
                    if (end - start >= minH) {
                        // 找列范围
                        var cMin = rw; var cMax = 0
                        for (ry in start..end) {
                            for (x in 0 until rw) {
                                if (isYellow(pixels[(y1 + ry) * sw + (x1 + x)])) {
                                    if (x < cMin) cMin = x
                                    if (x > cMax) cMax = x
                                }
                            }
                        }
                        if (cMax >= cMin) {
                            rows.add(RowBox(y1 + start, y1 + end, x1 + cMin, x1 + cMax + 1))
                        }
                    }
                    inRow = false; gap = 0
                }
            }
        }
        if (inRow) {
            val end = rh - 1
            if (end - start >= minH) {
                var cMin = rw; var cMax = 0
                for (ry in start..end) {
                    for (x in 0 until rw) {
                        if (isYellow(pixels[(y1 + ry) * sw + (x1 + x)])) {
                            if (x < cMin) cMin = x
                            if (x > cMax) cMax = x
                        }
                    }
                }
                if (cMax >= cMin) {
                    rows.add(RowBox(y1 + start, y1 + end, x1 + cMin, x1 + cMax + 1))
                }
            }
        }
        return rows
    }

    // ========== 识别数字串 ==========

    private fun recognizeNumber(
        pixels: IntArray, sw: Int, sh: Int,
        x1: Int, y1: Int, x2: Int, y2: Int
    ): Pair<Int?, Float> {
        val mask = yellowMask(pixels, sw, sh, x1, y1, x2, y2)
        val digits = segmentDigits(mask, x2 - x1, y2 - y1)
        if (digits.isEmpty()) return Pair(null, 0f)

        val sb = StringBuilder()
        var minConf = 1.0f
        for (d in digits) {
            val (ch, sc) = matchDigit(d)
            if (ch == '?') return Pair(null, 0f)
            sb.append(ch)
            if (sc < minConf) minConf = sc
        }
        return try {
            Pair(sb.toString().toInt(), minConf)
        } catch (_: Exception) {
            Pair(null, 0f)
        }
    }

    // ========== 公开API ==========

    fun isLoaded(): Boolean = loaded

    /**
     * 识别操作区
     * @param screenshot 完整截图Bitmap
     * @return ActionResult，识别失败时各字段为null/空列表
     */
    fun recognizeAction(screenshot: Bitmap): ActionResult? {
        if (!loaded) {
            Log.w(TAG, "Templates not loaded, skipping")
            return null
        }
        return try {
            val sw = screenshot.width
            val sh = screenshot.height
            val sx = sw / 1080f
            val sy = sh / 2344f

            val btnY1 = (BTN_Y1 * sy).toInt()
            val btnY2 = (BTN_Y2 * sy).toInt()
            val btn2X1 = (BTN2_X1 * sx).toInt()
            val btn2X2 = (BTN2_X2 * sx).toInt()
            val btn3X1 = (BTN3_X1 * sx).toInt()
            val btn3X2 = (BTN3_X2 * sx).toInt()
            val preY1 = (PRESET_Y1 * sy).toInt()
            val preY2 = (PRESET_Y2 * sy).toInt()
            val preX1 = (PRESET_X1 * sx).toInt()
            val preX2 = (PRESET_X2 * sx).toInt()

            // 裁剪整个操作区（从预设顶部到按钮底部）以减少getPixels调用
            val opY1 = preY1
            val opY2 = btnY2
            val opX1 = minOf(preX1, btn2X1)
            val opX2 = maxOf(preX2, btn3X2)
            val opW = opX2 - opX1
            val opH = opY2 - opY1
            if (opW <= 0 || opH <= 0) return null

            val pixels = IntArray(opW * opH)
            screenshot.getPixels(pixels, 0, opW, opX1, opY1, opW, opH)

            // 判断是否面对下注：中间按钮黄色像素数（坐标clamp防止缩放越界）
            var yellowCount = 0
            val yLo = (btnY1 - opY1).coerceIn(0, opH - 1)
            val yHi = (btnY2 - opY1).coerceIn(0, opH)
            val xLo = (btn2X1 - opX1).coerceIn(0, opW - 1)
            val xHi = (btn2X2 - opX1).coerceIn(0, opW)
            for (y in yLo until yHi) {
                for (x in xLo until xHi) {
                    if (isYellow(pixels[y * opW + x])) yellowCount++
                }
            }
            val facingBet = yellowCount > 100

            var callAmount: Int? = null
            var minConf = 1.0f

            if (facingBet) {
                val (call, conf) = recognizeNumber(
                    pixels, opW, opH,
                    btn2X1 - opX1, btnY1 - opY1,
                    btn2X2 - opX1, btnY2 - opY1
                )
                callAmount = call
                if (call != null && conf < minConf) minConf = conf
            }

            val (minRaise, mrConf) = recognizeNumber(
                pixels, opW, opH,
                btn3X1 - opX1, btnY1 - opY1,
                btn3X2 - opX1, btnY2 - opY1
            )
            if (minRaise != null && mrConf < minConf) minConf = mrConf

            // 预设金额行
            val presets = ArrayList<Int>()
            val rows = findAmountRows(
                pixels, opW, opH,
                preY1 - opY1, preY2 - opY1,
                preX1 - opX1, preX2 - opX1
            )
            for (row in rows) {
                val (amt, _) = recognizeNumber(
                    pixels, opW, opH,
                    row.x1, row.y1, row.x2, row.y2 + 1
                )
                if (amt != null) presets.add(amt)
            }

            ActionResult(
                facingBet = facingBet,
                callAmount = callAmount,
                minRaise = minRaise,
                presets = presets,
                confidence = if (minConf >= 1.0f) 1.0f else minConf
            )
        } catch (e: Exception) {
            Log.w(TAG, "recognizeAction failed: ${e.message}")
            null
        }
    }
}
