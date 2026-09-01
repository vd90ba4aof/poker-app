package win.opt.view

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
        private const val PRESET_X1 = 815  // V2.9.538: 830→815，左右加宽覆盖完整预设数字
        private const val PRESET_X2 = 970  // V2.9.538: 955→970
        // V2.9.536: 我的回合指示器——改用黄色下注数字+按钮数量检测
        // 豪哥逻辑：有黄色下注数字或>=2个按钮=轮到我；只有2个按钮+灰色数字=预处理
        // 底池金额区域（黄色数字）
        private const val POT_X1 = 460
        private const val POT_X2 = 620
        private const val POT_Y1 = 975
        private const val POT_Y2 = 1050
        private const val POT_YELLOW_THRESHOLD = 100
        // 按钮区域黄色像素阈值
        private const val BTN_YELLOW_THRESHOLD = 100
        // V2.9.553-rev9-fix-v3: 右侧下注预设面板黄色金额像素阈值（判定"轮到我"）
        // free check局面主按钮灰色、只有下注预设面板亮黄色金额（豪哥规则：有黄色数字按钮=轮到我）
        private const val PRESET_YELLOW_THRESHOLD = 400

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

    // V2.9.526: 底池/筹码自然尺寸模板（不强制26×36，匹配时动态缩放query到模板高度）
    // 底池数字h≈30-31px黄色，筹码数字h≈28-29px白色，两套模板跨集互补缺失数字
    private val potTemplates = HashMap<Char, Template>()
    private val chipsTemplates = HashMap<Char, Template>()

    data class ActionResult(
        val facingBet: Boolean,
        val callAmount: Int?,
        val minRaise: Int?,
        val presets: List<Int>,
        val confidence: Float
    )

    @Volatile
    private var loaded = false

    // V2.9.524: 操作区诊断信息
    @Volatile var lastDiag: String = ""
        private set

    fun loadTemplates() {
        if (loaded) return
        try {
            for (d in 0..9) {
                loadAssetTemplate("digit_templates/$d.png")?.let {
                    digitTemplates[d.toString()[0]] = it
                }
            }
            // V2.9.526: 底池模板（自然尺寸，缺7）
            for (d in 0..9) {
                loadAssetTemplate("digit_templates_pot/$d.png")?.let {
                    potTemplates[d.toString()[0]] = it
                }
            }
            // V2.9.526: 筹码模板（自然尺寸，缺2和9）
            for (d in 0..9) {
                loadAssetTemplate("digit_templates_chips/$d.png")?.let {
                    chipsTemplates[d.toString()[0]] = it
                }
            }
            loaded = digitTemplates.size == 10
            Log.i(TAG, "Digit templates loaded: btn=${digitTemplates.size}/10 pot=${potTemplates.size} chips=${chipsTemplates.size}")
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
     * 4. 尺寸过滤 + 紧bbox（V2.9.525: 去掉fillHoles）
     */
    private fun segmentDigits(mask: BooleanArray, w: Int, h: Int): List<Triple<BooleanArray, Int, Int>> {
        // 统计内容像素（mask中true=黄色内容）
        var contentPx = 0
        for (v in mask) if (v) contentPx++
        if (contentPx < 30) return emptyList()

        var comps = findComponents(mask, w, h)
        if (comps.isEmpty()) return emptyList()
        comps = comps.sortedBy { it.x1 }

        // V2.9.525: 过滤逗号和碎片（逗号area<100, h<20；数字area>280, h>=34）
        comps = comps.filter { it.area >= 100 && it.h >= 20 }
        if (comps.isEmpty()) return emptyList()

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

        // Step 4: 提取 + 紧bbox + 尺寸过滤（V2.9.525: 去掉fillHoles，它会把0/6/8/9的正常空心填实）
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
     * V2.9.536: 检测是否轮到我行动。
     * 豪哥逻辑：有黄色下注数字或>=2个按钮=轮到我；只有2个按钮+灰色数字=预处理。
     * 检测底池金额区域（黄色数字）和按钮区域黄色像素数量。
     *
     * @return true=轮到我，false=别人行动中或预处理状态
     */
    fun isMyTurn(screenshot: Bitmap): Boolean {
        return try {
            val sw = screenshot.width
            val sh = screenshot.height
            val sx = sw / 1080f
            val sy = sh / 2344f
            
            // 检测底池金额区域（黄色数字）
            val potX1 = (POT_X1 * sx).toInt().coerceIn(0, sw - 1)
            val potX2 = (POT_X2 * sx).toInt().coerceIn(potX1 + 1, sw)
            val potY1 = (POT_Y1 * sy).toInt().coerceIn(0, sh - 1)
            val potY2 = (POT_Y2 * sy).toInt().coerceIn(potY1 + 1, sh)
            val potW = potX2 - potX1
            val potH = potY2 - potY1
            val potPixels = IntArray(potW * potH)
            screenshot.getPixels(potPixels, 0, potW, potX1, potY1, potW, potH)
            var potYellow = 0
            for (p in potPixels) {
                if (isYellow(p)) potYellow++
            }
            
            // 检测按钮区域黄色像素（BTN2 + BTN3）
            val btn2X1 = (370 * sx).toInt().coerceIn(0, sw - 1)
            val btn2X2 = (700 * sx).toInt().coerceIn(btn2X1 + 1, sw)
            val btn3X1 = (715 * sx).toInt().coerceIn(0, sw - 1)
            val btn3X2 = (1050 * sx).toInt().coerceIn(btn3X1 + 1, sw)
            val btnY1 = (2215 * sy).toInt().coerceIn(0, sh - 1)
            val btnY2 = (2295 * sy).toInt().coerceIn(btnY1 + 1, sh)
            
            // BTN2 黄色像素
            val btn2W = btn2X2 - btn2X1
            val btn2H = btnY2 - btnY1
            val btn2Pixels = IntArray(btn2W * btn2H)
            screenshot.getPixels(btn2Pixels, 0, btn2W, btn2X1, btnY1, btn2W, btn2H)
            var btn2Yellow = 0
            for (p in btn2Pixels) {
                if (isYellow(p)) btn2Yellow++
            }
            
            // BTN3 黄色像素
            val btn3W = btn3X2 - btn3X1
            val btn3H = btnY2 - btnY1
            val btn3Pixels = IntArray(btn3W * btn3H)
            screenshot.getPixels(btn3Pixels, 0, btn3W, btn3X1, btnY1, btn3W, btn3H)
            var btn3Yellow = 0
            for (p in btn3Pixels) {
                if (isYellow(p)) btn3Yellow++
            }
            
            // V2.9.553-rev9-fix-v3: 判据重写（豪哥规则）。
            //   旧判据"底池黄色"错误：fold后观战底池金额一直亮→误判轮到我→观战帧盲发点击+循环断死。
            //   新判据只认行动信号：
            //     ①右侧下注预设面板黄色金额（X815-970/Y1680-2200）——free check局面主按钮灰色、
            //       只有下注预设面板亮黄金额（如5♥2♦7♠面 让牌灰按钮+黄色400/300/200/132）；
            //       需跟注/fold前该面板也在；fold后观战该面板消失。
            //     ②>=2个黄色行动按钮（call+raise/fold+call局面）
            //   底池黄色仅记日志，不参与判定。
            val preX1 = (PRESET_X1 * sx).toInt().coerceIn(0, sw - 1)
            val preX2 = (PRESET_X2 * sx).toInt().coerceIn(preX1 + 1, sw)
            val preY1 = (PRESET_Y1 * sy).toInt().coerceIn(0, sh - 1)
            val preY2 = (PRESET_Y2 * sy).toInt().coerceIn(preY1 + 1, sh)
            val preW = preX2 - preX1
            val preH = preY2 - preY1
            val prePixels = IntArray(preW * preH)
            screenshot.getPixels(prePixels, 0, preW, preX1, preY1, preW, preH)
            var presetYellow = 0
            for (p in prePixels) {
                if (isYellow(p)) presetYellow++
            }

            var yellowButtons = 0
            if (btn2Yellow >= BTN_YELLOW_THRESHOLD) yellowButtons++
            if (btn3Yellow >= BTN_YELLOW_THRESHOLD) yellowButtons++

            val presetActive = presetYellow >= PRESET_YELLOW_THRESHOLD
            val myTurn = presetActive || yellowButtons >= 1
            Log.d(TAG, "isMyTurn: presetYellow=$presetYellow(th=$PRESET_YELLOW_THRESHOLD,active=$presetActive) btn2Yellow=$btn2Yellow btn3Yellow=$btn3Yellow yellowButtons=$yellowButtons potYellow=$potYellow(仅参考) -> $myTurn")
            myTurn
        } catch (e: Exception) {
            Log.w(TAG, "isMyTurn failed: ${e.message}")
            true  // 检测失败时不阻断，保守认为轮到我（避免漏操作）
        }
    }

    /**
     * 识别操作区
     * @param screenshot 完整截图Bitmap
     * @return ActionResult，识别失败时各字段为null/空列表
     */
    fun recognizeAction(screenshot: Bitmap): ActionResult? {
        if (!loaded) {
            lastDiag = "templates_not_loaded(${digitTemplates.size}/10)"
            Log.w(TAG, "Templates not loaded, skipping")
            return null
        }
        val diagSb = StringBuilder()
        return try {
            val sw = screenshot.width
            val sh = screenshot.height
            val sx = sw / 1080f
            val sy = sh / 2344f
            diagSb.append("scr=${sw}x${sh};")

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

            val opY1 = preY1
            val opY2 = btnY2
            val opX1 = minOf(preX1, btn2X1)
            val opX2 = maxOf(preX2, btn3X2)
            val opW = opX2 - opX1
            val opH = opY2 - opY1
            if (opW <= 0 || opH <= 0) {
                lastDiag = "${diagSb}region_invalid ${opW}x${opH}"
                return null
            }
            diagSb.append("op=${opW}x${opH};")

            val pixels = IntArray(opW * opH)
            screenshot.getPixels(pixels, 0, opW, opX1, opY1, opW, opH)

            // BTN2 yellow count
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
            diagSb.append("btn2Y=$yellowCount,fb=${if(facingBet)1 else 0};")

            // BTN3 yellow count
            var btn3Yellow = 0
            val x3Lo = (btn3X1 - opX1).coerceIn(0, opW - 1)
            val x3Hi = (btn3X2 - opX1).coerceIn(0, opW)
            for (y in yLo until yHi) {
                for (x in x3Lo until x3Hi) {
                    if (isYellow(pixels[y * opW + x])) btn3Yellow++
                }
            }
            diagSb.append("btn3Y=$btn3Yellow;")

            var callAmount: Int? = null
            var minConf = 1.0f
            var callDiag = "skip"

            if (facingBet) {
                val (call, conf) = recognizeNumber(
                    pixels, opW, opH,
                    btn2X1 - opX1, btnY1 - opY1,
                    btn2X2 - opX1, btnY2 - opY1
                )
                callAmount = call
                callDiag = if (call != null) "${call}(c=%.2f)".format(conf) else "null(c=%.2f)".format(conf)
                if (call != null && conf < minConf) minConf = conf
            }
            diagSb.append("call=$callDiag;")

            val (minRaise, mrConf) = recognizeNumber(
                pixels, opW, opH,
                btn3X1 - opX1, btnY1 - opY1,
                btn3X2 - opX1, btnY2 - opY1
            )
            diagSb.append("mr=${if(minRaise!=null) "${minRaise}(c=%.2f)".format(mrConf) else "null(c=%.2f)".format(mrConf)};")
            if (minRaise != null && mrConf < minConf) minConf = mrConf

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
            diagSb.append("presets=${presets.size}:${presets.joinToString(",")};")
            diagSb.append("conf=%.2f".format(if (minConf >= 1.0f) 1.0f else minConf))

            val result = ActionResult(
                facingBet = facingBet,
                callAmount = callAmount,
                minRaise = minRaise,
                presets = presets,
                confidence = if (minConf >= 1.0f) 1.0f else minConf
            )
            lastDiag = diagSb.toString()
            Log.d(TAG, "🔍 Action diag: $lastDiag")
            result
        } catch (e: Exception) {
            lastDiag = "${diagSb}exception:${e.message}"
            Log.w(TAG, "recognizeAction failed: ${e.message}")
            null
        }
    }

    // ========== V2.9.526: 底池/筹码金额识别 ==========

    data class AmountResult(
        val value: Long,
        val confidence: Float,
        val digitCount: Int,
        val diag: String
    )

    private fun isWhite(pixel: Int): Boolean {
        val r = pixel shr 16 and 0xFF
        val g = pixel shr 8 and 0xFF
        val b = pixel and 0xFF
        return r > 200 && g > 200 && b > 200
    }

    /**
     * V2.9.543: 统一亮色mask——白色和灰色数字都提取。
     * GG筹码数字有白色(RGB~240)和灰色(RGB~165)两种，原isWhite阈值>200漏掉灰色数字(seat3等)。
     * 条件：亮度>140 且 R/G/B三通道差值<20（排除彩色像素）。
     */
    private fun isWhiteOrGray(pixel: Int): Boolean {
        val r = pixel shr 16 and 0xFF
        val g = pixel shr 8 and 0xFF
        val b = pixel and 0xFF
        val brightness = (r + g + b) / 3
        val maxDiff = maxOf(kotlin.math.abs(r - g), kotlin.math.abs(g - b), kotlin.math.abs(r - b))
        return brightness > 140 && maxDiff < 20
    }

    /**
     * 二值mask：暗底亮字——底池用isYellow提取黄色数字，筹码用isWhiteOrGray提取白/灰色数字。
     */
    private fun buildMask(bmp: Bitmap, useYellow: Boolean): BooleanArray {
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val mask = BooleanArray(w * h)
        for (i in pixels.indices) {
            mask[i] = if (useYellow) isYellow(pixels[i]) else isWhiteOrGray(pixels[i])
        }
        return mask
    }

    /**
     * row-band clipping：只保留row_sum>=5的行范围，裁掉逗号向下延伸的细尾巴
     * 对应Python: row_sum=mask.sum(axis=1); digit_rows=np.where(row_sum>=5)[0]
     */
    private fun rowBandClip(mask: BooleanArray, w: Int, h: Int): Triple<BooleanArray, Int, Int> {
        val rowSum = IntArray(h)
        for (y in 0 until h) {
            var s = 0
            for (x in 0 until w) if (mask[y * w + x]) s++
            rowSum[y] = s
        }
        var top = -1
        var bot = -1
        for (y in 0 until h) {
            if (rowSum[y] >= 5) {
                if (top < 0) top = y
                bot = y
            }
        }
        if (top < 0) return Triple(mask, w, h)
        val ch = bot - top + 1
        val clipped = BooleanArray(w * ch)
        for (y in 0 until ch) {
            System.arraycopy(mask, (top + y) * w, clipped, y * w, w)
        }
        return Triple(clipped, w, ch)
    }

    /**
     * 动态尺寸模板匹配（Python 1:1复现）：
     * 对每个pot/chips模板，将query缩放到模板高度（保持宽高比，nearest-neighbor），
     * 在 max(nw,tw)+4 宽、th 高的画布上居中query和template，计算IoU。
     */
    private fun matchDigitNatural(
        qData: BooleanArray, qW: Int, qH: Int,
        templates: Map<Char, Template>
    ): Pair<Char, Float> {
        if (qW == 0 || qH == 0) return Pair('?', 0f)
        var bestCh = '?'
        var bestScore = 0f
        for ((ch, tmpl) in templates) {
            val tw = tmpl.w
            val th = tmpl.h
            // scale query to template height
            val scale = th.toFloat() / qH
            val nw = maxOf(1, (qW * scale).toInt())
            val rq = resizeBinary(qData, qW, qH, nw, th)
            // canvas
            val cw = maxOf(nw, tw) + 4
            val canvas = BooleanArray(cw * th)
            val qx0 = (cw - nw) / 2
            val tx0 = (cw - tw) / 2
            // place query
            for (y in 0 until th) {
                System.arraycopy(rq, y * nw, canvas, y * cw + qx0, nw)
            }
            // compute IoU against template
            var inter = 0
            var union = 0
            for (y in 0 until th) {
                for (x in 0 until tw) {
                    val ci = y * cw + (tx0 + x)
                    val ti = y * tw + x
                    val qOn = canvas[ci]
                    val tOn = tmpl.data[ti]
                    if (qOn || tOn) {
                        union++
                        if (qOn && tOn) inter++
                    }
                }
            }
            val sc = if (union > 0) inter.toFloat() / union else 0f
            if (sc > bestScore) {
                bestScore = sc
                bestCh = ch
            }
        }
        return Pair(bestCh, bestScore)
    }

    /**
     * 识别金额（底池/筹码）：
     * 1. 颜色mask（黄/白）
     * 2. row-band clipping去除逗号尾巴
     * 3. findComponents + area>=100 && h>=20 + gap<=1合并
     * 4. 紧bbox提取数字
     * 5. 跨pot+chips两套模板动态匹配，取最高分
     *
     * @param bmp 已裁剪的区域bitmap
     * @param isPot true=底池(黄色mask)，false=筹码(白色mask)
     */
    fun recognizeAmount(bmp: Bitmap, isPot: Boolean): AmountResult? {
        return try {
            val w0 = bmp.width
            val h0 = bmp.height
            if (w0 < 10 || h0 < 10) return AmountResult(0, 0f, 0, "region_too_small ${w0}x${h0}")

            // 1. mask
            var mask = buildMask(bmp, isPot)
            // 2. row-band clipping
            val clipResult = rowBandClip(mask, w0, h0)
            mask = clipResult.first
            val w = clipResult.second
            val h = clipResult.third

            // 3. find components
            var comps = findComponents(mask, w, h)
            if (comps.isEmpty()) return AmountResult(0, 0f, 0, "no_components")
            comps = comps.sortedBy { it.x1 }
            comps = comps.filter { it.area >= 100 && it.h >= 20 }
            if (comps.isEmpty()) return AmountResult(0, 0f, 0, "filtered_all(area/h)")

            // merge gap<=1
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

            // 4. tight bbox + extract digits
            val digits = ArrayList<Triple<BooleanArray, Int, Int>>()
            for (c in merged) {
                val rw = c.w
                val rh = c.h
                val d = BooleanArray(rw * rh)
                for (y in 0 until rh) {
                    for (x in 0 until rw) {
                        d[y * rw + x] = mask[(c.y1 + y) * w + (c.x1 + x)]
                    }
                }
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
                if (tw < 6 || th < 18) continue
                val trimmed = BooleanArray(tw * th)
                for (y in 0 until th) {
                    System.arraycopy(d, (minY + y) * rw + minX, trimmed, y * tw, tw)
                }
                digits.add(Triple(trimmed, tw, th))
            }
            if (digits.isEmpty()) return AmountResult(0, 0f, 0, "no_digits_after_bbox")

            // 5. match across BOTH pot and chips template sets, take best per digit
            val sb = StringBuilder()
            var minConf = 1.0f
            val diagParts = ArrayList<String>()
            for (d in digits) {
                val (qData, qW, qH) = d
                // try pot templates
                val (chPot, scPot) = matchDigitNatural(qData, qW, qH, potTemplates)
                // try chips templates
                val (chChips, scChips) = matchDigitNatural(qData, qW, qH, chipsTemplates)
                // V2.9.543: try btn templates (26x36原始尺寸，匹配效果最好)
                val (chBtn, scBtn) = matchDigitNatural(qData, qW, qH, digitTemplates)
                val (ch, sc, src) = when {
                    scBtn >= scPot && scBtn >= scChips -> Triple(chBtn, scBtn, "b")
                    scPot >= scChips -> Triple(chPot, scPot, "p")
                    else -> Triple(chChips, scChips, "c")
                }
                if (ch == '?') {
                    return AmountResult(0, 0f, digits.size, "digit_unmatched")
                }
                sb.append(ch)
                if (sc < minConf) minConf = sc
                diagParts.add("$ch(%s%.2f)".format(src, sc))
            }
            val value = try { sb.toString().toLong() } catch (_: Exception) { 0L }
            val diag = "n=${digits.size} ${diagParts.joinToString(" ")} v=$value"
            AmountResult(value, minConf, digits.size, diag)
        } catch (e: Exception) {
            Log.w(TAG, "recognizeAmount(${if (isPot) "pot" else "chips"}) failed: ${e.message}")
            AmountResult(0, 0f, 0, "exception:${e.message}")
        }
    }

    /**
     * V2.9.554: 盲注识别（牌桌中央"德州扑克, 100 / 200"暗灰绿水印文字）。
     * 难点：①文字是暗灰绿(lum~114)非白色，背景深绿felt(lum~44)，需亮度mask；
     *       ②中文"德州扑克"竖笔画拆成窄长条(w~13 h~29)，几何上与数字"1"无法区分，
     *         只能靠模板匹配分数挡（中文/斜杠匹配任何数字都低分<0.45）。
     * 流程：亮度mask → findComponents → 从右往左状态机读数：
     *       最右连续高分数字=BB → 首个低分=斜杠(切SB) → SB数字 → 再低分=中文(停)。
     * @return Pair(SB, BB)，识别失败返回(0,0)
     */
    fun recognizeBlinds(bmp: Bitmap): Pair<Int, Int> {
        return try {
            val w0 = bmp.width
            val h0 = bmp.height
            if (w0 < 10 || h0 < 10) return Pair(0, 0)

            // 1. 亮度mask：暗灰绿水印(lum 89-121) vs 深绿felt(lum 41-76)，阈值75居中
            val px = IntArray(w0 * h0)
            bmp.getPixels(px, 0, w0, 0, 0, w0, h0)
            val mask = BooleanArray(w0 * h0)
            for (i in px.indices) {
                val p = px[i]
                val lum = ((p shr 16 and 0xFF) + (p shr 8 and 0xFF) + (p and 0xFF)) / 3
                mask[i] = lum >= 75
            }

            // 2. 连通区域 + 基础过滤（去碎点；数字h~24-26、斜杠h~32、中文竖笔h~24-31都保留）
            var comps = findComponents(mask, w0, h0)
            if (comps.isEmpty()) return Pair(0, 0)
            comps = comps.sortedBy { it.x1 }.filter { it.h >= 15 && it.area >= 60 }
            if (comps.size < 2) return Pair(0, 0)

            // 3. 从右往左状态机读数（跨三套模板取最高分，数字真实匹配>0.6，中文/斜杠<0.45）
            val MIN_DIGIT_CONF = 0.45f
            val bbDigits = StringBuilder()
            val sbDigits = StringBuilder()
            var readingBB = true
            var lowStreak = 0
            for (c in comps.asReversed()) {
                val rw = c.w; val rh = c.h
                val d = BooleanArray(rw * rh)
                for (y in 0 until rh) for (x in 0 until rw) {
                    d[y * rw + x] = mask[(c.y1 + y) * w0 + (c.x1 + x)]
                }
                var mnX = rw; var mxX = -1; var mnY = rh; var mxY = -1
                for (y in 0 until rh) for (x in 0 until rw) {
                    if (d[y * rw + x]) {
                        if (x < mnX) mnX = x; if (x > mxX) mxX = x
                        if (y < mnY) mnY = y; if (y > mxY) mxY = y
                    }
                }
                if (mxX < 0) continue
                val tw = mxX - mnX + 1; val th = mxY - mnY + 1
                if (tw < 6 || th < 15) continue
                val trimmed = BooleanArray(tw * th)
                for (y in 0 until th) System.arraycopy(d, (mnY + y) * rw + mnX, trimmed, y * tw, tw)

                val (chPot, scPot) = matchDigitNatural(trimmed, tw, th, potTemplates)
                val (chChips, scChips) = matchDigitNatural(trimmed, tw, th, chipsTemplates)
                val (chBtn, scBtn) = matchDigitNatural(trimmed, tw, th, digitTemplates)
                val (ch, sc) = when {
                    scBtn >= scPot && scBtn >= scChips -> Pair(chBtn, scBtn)
                    scPot >= scChips -> Pair(chPot, scPot)
                    else -> Pair(chChips, scChips)
                }

                if (sc >= MIN_DIGIT_CONF && ch != '?') {
                    // 高分数字：从右往左读，插入当前组头部
                    if (readingBB) { bbDigits.insert(0, ch) } else { sbDigits.insert(0, ch) }
                    lowStreak = 0
                } else {
                    // 低分组件：斜杠或中文竖笔
                    if (readingBB) {
                        if (bbDigits.isNotEmpty()) { readingBB = false; lowStreak = 1 }  // BB读完→斜杠→切SB
                        // BB为空时低分=边缘噪音，忽略
                    } else {
                        lowStreak++
                        if (sbDigits.isNotEmpty()) break   // SB已读完，左侧是中文→停
                        if (lowStreak > 3) break           // 斜杠碎片过多，放弃SB
                    }
                }
            }

            val bb = bbDigits.toString().toIntOrNull() ?: 0
            val sb = sbDigits.toString().toIntOrNull() ?: 0
            if (bb <= 0) return Pair(0, 0)
            val finalSB = if (sb > 0) sb else (if (bb % 2 == 0) bb / 2 else 0)
            Log.d(TAG, "🎯 本地CV盲注: SB=$finalSB BB=$bb (raw: sb='$sbDigits' bb='$bbDigits' comps=${comps.size})")
            Pair(finalSB, bb)
        } catch (e: Exception) {
            Log.w(TAG, "recognizeBlinds failed: ${e.message}")
            Pair(0, 0)
        }
    }

    // ========== V2.9.527: D按钮(庄位)本地CV识别 ==========

    /**
     * D按钮搜索区域（1080x2344基准）。
     * 每个座位下方/内侧桌面felt上，D按钮出现的90x90搜索框。
     * 顺序对应seat 0-5（与chip_seat命名一致）。
     * 坐标依据：GG手机端6-max桌，D按钮在头像下方牌张旁边的felt上。
     */
    private data class DZone(
        val seat: Int,
        val cx: Int,
        val cy: Int
    )

    // V2.9.538: 6个D按钮位置全部由8/22六张真实截图实测校准（D按钮直径~55px）
    // GG竖屏6-max桌为偏右椭圆，正上/正下不在屏幕中线而在x≈375
    private val dZones = listOf(
        DZone(0, 149, 990),    // seat0 左上：D实测(149,990)✅
        DZone(1, 377, 570),    // seat1 正上：D实测(377,570)✅
        DZone(2, 929, 990),    // seat2 右上：D实测(929,990)✅
        DZone(3, 977, 1317),   // seat3 右中：D实测(977,1317)✅
        DZone(4, 374, 1881),   // seat4 正下Hero：D实测(374,1881)✅
        DZone(5, 101, 1317)    // seat5 左中：D实测(101,1317)✅
    )

    private val D_ZONE_RADIUS = 55
    // D按钮主体：金黄色（R>200, G>160, B<100）
    private val D_BODY_R_MIN = 200
    private val D_BODY_G_MIN = 160
    private val D_BODY_B_MAX = 100
    // D字母：深色（R<80, G<80, B<80）
    private val D_LETTER_MAX = 80
    private val D_MIN_BODY = 150
    private val D_MAX_BODY = 2500
    private val D_MIN_CLUSTER = 18
    private val D_MIN_DARK_IN = 30
    private val D_GREEN_RATIO = 0.35f

    data class DButtonResult(
        val seat: Int,
        val x: Int,
        val y: Int,
        val confidence: Float
    )

    private fun isDBody(pixel: Int): Boolean {
        val r = pixel shr 16 and 0xFF
        val g = pixel shr 8 and 0xFF
        val b = pixel and 0xFF
        return r > D_BODY_R_MIN && g > D_BODY_G_MIN && b < D_BODY_B_MAX
    }

    private fun isDLetter(pixel: Int): Boolean {
        val r = pixel shr 16 and 0xFF
        val g = pixel shr 8 and 0xFF
        val b = pixel and 0xFF
        return r < D_LETTER_MAX && g < D_LETTER_MAX && b < D_LETTER_MAX
    }

    private fun isGreenFelt(pixel: Int): Boolean {
        val r = pixel shr 16 and 0xFF
        val g = pixel shr 8 and 0xFF
        val b = pixel and 0xFF
        return g > r + 15 && g > b
    }

    /**
     * 检测D按钮（金黄色圆形+深色D字母，绿色felt背景）。
     * 6个小区域共~65k像素，纯像素扫描<0.5ms。
     */
    fun recognizeDButton(screenshot: Bitmap): DButtonResult {
        return try {
            val sw = screenshot.width
            val sh = screenshot.height
            val sx = sw / 1080f
            val sy = sh / 2344f
            val radius = (D_ZONE_RADIUS * (sx + sy) / 2f).toInt().coerceAtLeast(25)

            var bestSeat = -1
            var bestX = 0
            var bestY = 0
            var bestScore = 0f

            for (zone in dZones) {
                val zcx = (zone.cx * sx).toInt()
                val zcy = (zone.cy * sy).toInt()
                val x0 = (zcx - radius).coerceIn(0, sw - 1)
                val y0 = (zcy - radius).coerceIn(0, sh - 1)
                val x1 = (zcx + radius).coerceIn(x0 + 1, sw)
                val y1 = (zcy + radius).coerceIn(y0 + 1, sh)
                val zw = x1 - x0
                val zh = y1 - y0
                if (zw < 15 || zh < 15) continue

                val pixels = IntArray(zw * zh)
                screenshot.getPixels(pixels, 0, zw, x0, y0, zw, zh)

                // Flood-fill找金黄色主体连通块
                val visited = BooleanArray(zw * zh)
                val queue = IntArray(zw * zh)

                for (startIdx in pixels.indices) {
                    if (visited[startIdx]) continue
                    if (!isDBody(pixels[startIdx])) {
                        visited[startIdx] = true
                        continue
                    }

                    var qHead = 0
                    var qTail = 0
                    queue[qTail++] = startIdx
                    visited[startIdx] = true

                    var minX = zw; var maxX = -1
                    var minY = zh; var maxY = -1
                    var bodyCount = 0

                    while (qHead < qTail) {
                        val idx = queue[qHead++]
                        val px = idx % zw
                        val py = idx / zw
                        bodyCount++
                        if (px < minX) minX = px
                        if (px > maxX) maxX = px
                        if (py < minY) minY = py
                        if (py > maxY) maxY = py

                        // 4-邻接
                        if (px > 0) {
                            val ni = idx - 1
                            if (!visited[ni]) {
                                visited[ni] = true
                                if (isDBody(pixels[ni])) queue[qTail++] = ni
                            }
                        }
                        if (px < zw - 1) {
                            val ni = idx + 1
                            if (!visited[ni]) {
                                visited[ni] = true
                                if (isDBody(pixels[ni])) queue[qTail++] = ni
                            }
                        }
                        if (py > 0) {
                            val ni = idx - zw
                            if (!visited[ni]) {
                                visited[ni] = true
                                if (isDBody(pixels[ni])) queue[qTail++] = ni
                            }
                        }
                        if (py < zh - 1) {
                            val ni = idx + zw
                            if (!visited[ni]) {
                                visited[ni] = true
                                if (isDBody(pixels[ni])) queue[qTail++] = ni
                            }
                        }
                    }

                    val cw = maxX - minX + 1
                    val ch = maxY - minY + 1
                    if (bodyCount < D_MIN_BODY || bodyCount > D_MAX_BODY) continue
                    if (cw < D_MIN_CLUSTER || ch < D_MIN_CLUSTER) continue
                    val aspect = cw.toFloat() / ch
                    if (aspect < 0.55f || aspect > 1.8f) continue

                    // bbox内统计深色D字母像素
                    val pad = 4
                    val bx0 = (minX - pad).coerceIn(0, zw - 1)
                    val bx1 = (maxX + pad + 1).coerceIn(bx0 + 1, zw)
                    val by0 = (minY - pad).coerceIn(0, zh - 1)
                    val by1 = (maxY + pad + 1).coerceIn(by0 + 1, zh)
                    var darkCount = 0
                    for (yy in by0 until by1) {
                        for (xx in bx0 until bx1) {
                            if (isDLetter(pixels[yy * zw + xx])) darkCount++
                        }
                    }
                    if (darkCount < D_MIN_DARK_IN) continue

                    // 检查周边绿色felt占比
                    val border = 8
                    var greenCount = 0
                    var borderTotal = 0
                    for (xx in bx0..bx1) {
                        for (dd in 1..border) {
                            val yyTop = by0 - dd
                            val yyBot = by1 + dd - 1
                            if (yyTop in 0 until zh) {
                                borderTotal++
                                if (isGreenFelt(pixels[yyTop * zw + xx])) greenCount++
                            }
                            if (yyBot in 0 until zh) {
                                borderTotal++
                                if (isGreenFelt(pixels[yyBot * zw + xx])) greenCount++
                            }
                        }
                    }
                    for (yy in by0 until by1) {
                        for (dd in 1..border) {
                            val xxL = bx0 - dd
                            val xxR = bx1 + dd - 1
                            if (xxL in 0 until zw) {
                                borderTotal++
                                if (isGreenFelt(pixels[yy * zw + xxL])) greenCount++
                            }
                            if (xxR in 0 until zw) {
                                borderTotal++
                                if (isGreenFelt(pixels[yy * zw + xxR])) greenCount++
                            }
                        }
                    }
                    val greenRatio = if (borderTotal > 0) greenCount.toFloat() / borderTotal else 0f
                    if (greenRatio < D_GREEN_RATIO) continue

                    // 置信度：深色密度 + 绿色背景 + 尺寸合适度
                    val bboxArea = cw * ch
                    val darkDensity = darkCount.toFloat() / bboxArea
                    val sizeScore = (bodyCount.toFloat() / 600f).coerceAtMost(1f)
                    val score = 0.45f * darkDensity.coerceAtMost(0.6f) / 0.6f +
                                 0.30f * greenRatio +
                                 0.25f * sizeScore

                    if (score > bestScore) {
                        bestScore = score
                        bestSeat = zone.seat
                        bestX = x0 + (minX + maxX) / 2
                        bestY = y0 + (minY + maxY) / 2
                    }
                }
            }

            if (bestSeat >= 0) {
                Log.d(TAG, "🎲 D按钮检测: seat=$bestSeat pos=($bestX,$bestY) conf=%.2f".format(bestScore))
            }
            DButtonResult(bestSeat, bestX, bestY, bestScore)
        } catch (e: Exception) {
            Log.w(TAG, "D按钮检测失败: ${e.message}")
            DButtonResult(-1, 0, 0, 0f)
        }
    }
}