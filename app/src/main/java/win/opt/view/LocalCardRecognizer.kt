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

    // 模板：key -> 二值数组(0=内容, 1=背景), width, height, topo=拓扑签名(V2.9.574)
    private data class Template(val data: BooleanArray, val w: Int, val h: Int, val topo: TopoSig) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Template) return false
            return data.contentEquals(other.data) && w == other.w && h == other.h
        }
        override fun hashCode(): Int = data.contentHashCode() * 31 + w * 17 + h
    }

    // V2.9.578: 多模板库——每个label挂多个真实字形模板(1-NN: 类内取最高分)。
    // 离线LOOCV+全链路回测(75张真实截图)实证替代v577的40%软投票合成单模板:
    //   稳定帧 comm_rank 99.1%→100%(消除5→6硬伤), hand_rank硬认102→110(真7/8/Q误拒全修复),
    //   硬伤0, 误拒干净牌8→0; 仅emoji遮挡脏字(fi34)被0.55双门槛正确拒认。
    private val commRankTemplates = HashMap<String, MutableList<Template>>()
    private val commSuitTemplates = HashMap<String, MutableList<Template>>()
    private val handRankTemplates = HashMap<String, MutableList<Template>>()
    private val handSuitTemplates = HashMap<String, MutableList<Template>>()

    private val RANKS = arrayOf("A", "K", "Q", "J", "10", "9", "8", "7", "6", "5", "4", "3", "2")
    private val SUITS = arrayOf("s", "h", "d", "c")
    private val RED_SUITS = setOf("h", "d")
    private val BLACK_SUITS = setOf("s", "c")

    data class CardResult(
        val rank: String,
        val suit: String,
        val confidence: Float,
        val rankScore: Float,
        val suitScore: Float,
        // V2.9.574: 槽位号（手牌0/1，公共牌0-4；默认-1=未标注）
        val slot: Int = -1,
        // V2.9.574: 双门槛不确定标志（IoU绝对分/分差不足 或 拓扑否决后残差候选），下游不得采信
        val rankUncertain: Boolean = false,
        val suitUncertain: Boolean = false
    )

    @Volatile
    private var loaded = false

    // V2.9.521: 诊断信息——最近一次识别各步失败原因
    @Volatile var lastDiag: String = ""
        private set

    fun loadTemplates() {
        if (loaded) return
        try {
            // V2.9.578: 多模板命名 {kind}_{label}_{idx}.png，idx 00..09，缺失即止
            for (rank in RANKS) {
                loadTemplateSeries("card_templates/comm/rank_$rank", commRankTemplates, rank)
                loadTemplateSeries("card_templates/hand/rank_$rank", handRankTemplates, rank)
            }
            for (suit in SUITS) {
                loadTemplateSeries("card_templates/comm/suit_$suit", commSuitTemplates, suit)
                loadTemplateSeries("card_templates/hand/suit_$suit", handSuitTemplates, suit)
            }
            loaded = true
            Log.i(TAG, "Templates loaded: comm ${countTemplates(commRankTemplates)}r/${countTemplates(commSuitTemplates)}s, " +
                    "hand ${countTemplates(handRankTemplates)}r/${countTemplates(handSuitTemplates)}s " +
                    "(${commRankTemplates.size}+${handRankTemplates.size} rank labels)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load templates", e)
        }
    }

    /** 加载某 label 的多模板系列：pathPrefix 如 "card_templates/comm/rank_8"，文件 pathPrefix_00.png.._09.png */
    private fun loadTemplateSeries(pathPrefix: String, into: HashMap<String, MutableList<Template>>, label: String) {
        for (idx in 0..9) {
            val path = "%s_%02d.png".format(pathPrefix, idx)
            val t = loadAssetTemplate(path) ?: break  // idx 连续，缺失即止
            into.getOrPut(label) { mutableListOf() }.add(t)
        }
    }

    private fun countTemplates(m: Map<String, MutableList<Template>>): Int =
        m.values.sumOf { it.size }

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
            // V2.9.574: 模板预裁剪过（fgBBox=全图），签名直接基于全图；模板换资产自动跟随
            val sig = computeTopoSig(data, w, h)
            Template(data, w, h, sig)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load template: $path", e)
            null
        }
    }

    // ========== V2.9.574: rank字形拓扑签名（孔数/孔面积比/宽高比/孔中心） ==========
    // 离线26张模板实测（2026-09-05 analyze_templates.py）：
    //   孔数: 2/3/5/7/J/K=0孔, 8=2孔, 4/6/9/10/A/Q=1孔
    //   孔面积比: Q最大(comm 0.184/hand 0.113)，A最小(0.055/0.036)，4/6/9≈0.07-0.09，10=0.08-0.10
    //   宽高比: Q=0.50最窄, A=1.04最宽(单字符), 10=1.62, 4=0.83, 6/9=0.75-0.77
    //   孔中心y: 9=0.32(上), 6=0.66(下), A/4/10/Q=0.43-0.50(中)
    // 作用：模板match()的IoU argmax对Q的圆形封闭笔画有机制性偏好（实机Q占41% vs 概率7.7%），
    //   拓扑是rank物理不变量，对IoU误认做一票否决。阈值全部按离线表最近对抗对2倍以上余量设定。
    private data class TopoSig(
        val holes: Int,          // 孔数（背景中不与边框连通的4连通域数）
        val mainHoleArea: Float, // 最大孔面积 / 裁剪框面积（无孔=0）
        val aspect: Float,       // 前景裁剪宽高比
        val holeCy: Float,       // 最大孔中心y比例（无孔=-1）
        val holeCx: Float,       // 最大孔中心x比例（无孔=-1）
        val ok: Boolean          // 前景量是否足够可信（噪声碎片不启用否决，fail-open）
    )

    /** 计算二值mask的拓扑签名（mask极性: false=内容, true=背景；模板与query同构） */
    private fun computeTopoSig(mask: BooleanArray, w: Int, h: Int): TopoSig {
        if (w < 6 || h < 10) return TopoSig(0, 0f, 0f, -1f, -1f, false)
        // 1. trim到内容边界
        var minX = w; var maxX = -1; var minY = h; var maxY = -1
        var fgCount = 0
        for (y in 0 until h) for (x in 0 until w) {
            if (!mask[y * w + x]) {
                fgCount++
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
            }
        }
        if (maxX < 0) return TopoSig(0, 0f, 0f, -1f, -1f, false)
        val tw = maxX - minX + 1
        val th = maxY - minY + 1
        if (fgCount < tw * th * 0.18f) return TopoSig(0, 0f, 0f, -1f, -1f, false) // 碎片不足，不启用否决
        // 2. 4连通flood fill从边框标记可达背景
        // reachable语义：背景中已确认与边框连通的像素（=外部背景）；孔=背景且reachable=false
        val reachable = BooleanArray(tw * th)
        val queue = ArrayDeque<Int>()
        // 邻居入队：仅当目标格是背景且尚未标记（fg/已标记一律不入队，禁止跨fg扩散）
        fun seed(x: Int, y: Int) {
            if (x in 0 until tw && y in 0 until th) {
                val idx = y * tw + x
                if (!reachable[idx] && mask[(minY + y) * w + (minX + x)]) { reachable[idx] = true; queue.add(idx) }
            }
        }
        for (x in 0 until tw) { seed(x, 0); seed(x, th - 1) }
        for (y in 0 until th) { seed(0, y); seed(tw - 1, y) }
        while (queue.isNotEmpty()) {
            val idx = queue.poll()
            val cx = idx % tw; val cy = idx / tw
            if (cx > 0) seed(cx - 1, cy)
            if (cx < tw - 1) seed(cx + 1, cy)
            if (cy > 0) seed(cx, cy - 1)
            if (cy < th - 1) seed(cx, cy + 1)
        }
        // 3. 不可达背景=孔，统计各孔（面积<裁剪框2%视为噪点不计）
        //    v574调参: 4%→2%——9/5真实截图验证0孔字J/K在2%下无假孔（H1方向安全）；
        //    2%可恢复hand组A孔(0.036)与8上孔(0.035)，避免小尺寸真实孔被滤导致签名退化
        var holes = 0
        var bestArea = 0; var bestCy = 0f; var bestCx = 0f
        val minHolePx = (tw * th * 0.02f).toInt().coerceAtLeast(3)
        for (sy in 0 until th) for (sx in 0 until tw) {
            val idx = sy * tw + sx
            if (!reachable[idx] && mask[(minY + sy) * w + (minX + sx)]) {
                // 新孔：flood fill统计（复用reachable标记，填true避免重复；seed同样不跨fg）
                var area = 0; var sumX = 0; var sumY = 0
                reachable[idx] = true
                queue.add(idx)
                while (queue.isNotEmpty()) {
                    val j = queue.poll()
                    val jx = j % tw; val jy = j / tw
                    area++; sumX += jx; sumY += jy
                    // 显式坐标4邻居（禁止用j±1索引：x=0时j-1跨行错位）
                    if (jx > 0) seed(jx - 1, jy)
                    if (jx < tw - 1) seed(jx + 1, jy)
                    if (jy > 0) seed(jx, jy - 1)
                    if (jy < th - 1) seed(jx, jy + 1)
                }
                if (area >= minHolePx) {
                    holes++
                    if (area > bestArea) { bestArea = area; bestCy = sumY.toFloat() / area / th; bestCx = sumX.toFloat() / area / tw }
                }
            }
        }
        return TopoSig(holes, bestArea.toFloat() / (tw * th), tw.toFloat() / th, bestCy, bestCx, true)
    }

    /**
     * 拓扑否决：query与candidate模板签名矛盾时返回true（跳过该候选）。
     * 触发规则（满足任意一条即否决；阈值均为离线表对抗对2倍+余量）：
     *  H1 孔数: query孔数>模板（孔不会被噪声"长"出来，query 2孔→Q(1)必错）或孔数差≥2
     *  H2 大孔: query 0孔 但模板有大孔(≥0.10)——Q/8类封闭字形冒充不了0孔字（5/K/2/3/7/J）
     *  H3 孔面积断层: query 1孔但模板孔显著更大（Q孔面积0.11-0.20为全rank最大，其余≤0.09；
     *      tmpl孔≥0.10且≥query孔1.3倍→小孔字冒充不了Q；真实9/4/6投Q比值1.42-2.08，真Q投自身≈1）
     *  H4 孔位+孔面积双矛盾: 同1孔时孔面积差>0.045 且 孔心垂直距离>0.10
     *      （9孔在上cy0.32/6在下0.66/Q居中0.43，孔位是跨字体物理不变量，真实牌面实测一致）
     *  注: aspect宽高比已废弃——模板为宽无衬线字体、真实GG为窄粗体，同字aspect系统偏差0.54-0.67
     *      会误杀真牌，且真9/4(asp0.46-0.56)与Q(0.50)无区分力（9/5真实截图实测）。
     *  阈值来源: 26张模板签名表 + 8张9/5真实公牌牌面（A/Q/4/9/8/J/K）实测对抗全绿。
     */
    private fun topoVeto(query: TopoSig, tmpl: TopoSig): Boolean {
        if (!query.ok || !tmpl.ok) return false // 签名不可信→fail-open，交给IoU
        // H1: 孔数硬矛盾
        if (query.holes > tmpl.holes || kotlin.math.abs(query.holes - tmpl.holes) >= 2) return true
        // H2: query无孔 vs 模板大封闭孔（Q/8类封闭字形冒充0孔字）
        if (query.holes == 0 && tmpl.holes >= 1 && tmpl.mainHoleArea >= 0.10f) return true
        // H3: 孔面积断层（小孔字投Q类大孔模板）
        if (query.holes == 1 && tmpl.holes == 1 && tmpl.mainHoleArea >= 0.10f &&
            tmpl.mainHoleArea >= query.mainHoleArea * 1.3f) return true
        // H4: 孔位+孔面积双矛盾（cy与面积都是跨字体稳定特征）
        if (query.holes == 1 && tmpl.holes == 1 &&
            kotlin.math.abs(query.mainHoleArea - tmpl.mainHoleArea) > 0.045f &&
            kotlin.math.abs(query.holeCy - tmpl.holeCy) > 0.10f) return true
        return false
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

    // V2.9.574: match结果——best/second双分数+不确定标志（拓扑否决残差候选或双门槛不过）
    private data class MatchOutcome(val label: String?, val score: Float, val uncertain: Boolean)

    /**
     * 模板匹配：缩放到模板高度，水平居中对齐，前景并集上的像素重合度。
     * V2.9.574: ①rank模板先过拓扑否决（孔数/孔面积比/宽高比/孔中心物理不变量）；
     *   ②双门槛——绝对分<0.55 或 与第二名分差<0.05 → uncertain=true（下游按不采信处理）。
     * @param useTopology true=rank模板启用拓扑否决；suit模板传false
     */
    private fun match(query: Triple<BooleanArray, Int, Int>?, templates: Map<String, out List<Template>>, useTopology: Boolean = true): MatchOutcome {
        if (query == null) return MatchOutcome(null, 0f, true)
        val (qData, qW, qH) = query
        if (qH == 0 || qW == 0) return MatchOutcome(null, 0f, true)

        val qSig = if (useTopology) computeTopoSig(qData, qW, qH) else null

        var bestLabel: String? = null
        var bestScore = 0f
        var secondScore = 0f
        // V2.9.578: 多模板1-NN——每个label挂多个真实字形，类内取最高分，再跨类比argmax+双门槛。
        // 单模板比对(resize到模板高+水平居中+IoU)逻辑与v577完全一致，仅外层多一层模板循环。
        for ((label, tmpls) in templates) {
            var labelBest = 0f
            for (tmpl in tmpls) {
                // V2.9.574: 拓扑一票否决——签名矛盾的候选模板不参与IoU argmax
                if (useTopology && qSig != null && topoVeto(qSig, tmpl.topo)) continue
                val score = iouWithTemplate(qData, qW, qH, tmpl)
                if (score > labelBest) labelBest = score
            }
            if (labelBest > bestScore) {
                secondScore = bestScore
                bestScore = labelBest
                bestLabel = label
            } else if (labelBest > secondScore) {
                secondScore = labelBest
            }
        }
        // 双门槛：绝对分0.55 + 与第二名分差0.05（实机误认0.55-0.80区间分差普遍<0.05，真牌0.85+）
        val uncertain = bestLabel == null || bestScore < 0.55f || (bestScore - secondScore) < 0.05f
        return MatchOutcome(bestLabel, bestScore, uncertain)
    }

    /**
     * query(trim后) 与单个模板(trim后)的IoU：query缩放到模板高，水平居中对齐，
     * 前景并集上的像素重合度；宽度不匹配部分计入union惩罚。V2.9.578从match()抽出(逻辑不变)。
     */
    private fun iouWithTemplate(qData: BooleanArray, qW: Int, qH: Int, tmpl: Template): Float {
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
        return if (totalUnion > 0) agree.toFloat() / totalUnion else 0f
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

            // V2.9.574: rank走拓扑否决+双门槛；suit只走双门槛（花色形状不走rank拓扑）
            val rm = match(rankTrimmed, commRankTemplates, useTopology = true)
            val candidateSuits = if (isRed)
                commSuitTemplates.filterKeys { it in RED_SUITS }
            else
                commSuitTemplates.filterKeys { it in BLACK_SUITS }
            val sm = match(suitTrimmed, candidateSuits, useTopology = false)

            if (rm.label == null || sm.label == null) return null
            // V2.9.574: 置信度取两门min（保守值，禁止花色分给rank背书）；不确定标志独立携带
            CardResult(rm.label, sm.label, minOf(rm.score, sm.score), rm.score, sm.score,
                slot = cardIndex, rankUncertain = rm.uncertain, suitUncertain = sm.uncertain)
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

            // 计算缩放因子（相对250px基准高度，从200改为250）
            val scale = ch / 250.0

            // tight per-card corner参数（8/22真实截图像素级校准）
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

            // 复用前面已检测的颜色结果，不重复计算
            val isBlackCard = detectBlackOrRed(pixels, cw, cornerX, cornerY, cx2, cy2)
            val cornerMask = extractMask(pixels, cw, cornerX, cornerY, cx2, cy2, !isBlackCard)
            val mw = actualCW
            val mh = actualCH

            // 自动band检测：行投影找到rank和suit两个content band
            val bands = findHandContentBands(cornerMask, mw, mh, scale)
            if (bands.size < 2) return failReason("bands=${bands.size}")

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
            // V2.9.574: rank不确定标志（双门槛/拓扑否决残差）；"10"双component特判视为确定
            var rankUnc = false

            if (tenResult != null) {
                bestRank = "10"
                rankConf = tenResult
            } else {
                val rm = match(rankTrimmed, handRankTemplates, useTopology = true)
                bestRank = rm.label
                rankConf = rm.score.toDouble()
                rankUnc = rm.uncertain
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

            // 复用前面已检测的颜色结果，不重复计算
            val isBlack = isBlackCard

            val suitTemplates = if (isBlack) {
                handSuitTemplates.filterKeys { it in BLACK_SUITS }
            } else {
                handSuitTemplates.filterKeys { it in RED_SUITS }
            }

            var bestSuit: String? = null
            var suitConf = 0.0
            // V2.9.574: suit不确定标志；黑色plateau分类天然0.50+地板，标记uncertain供下游独立判
            var suitUnc = false

            if (isBlack && suitTrimmed != null) {
                // plateau_ratio分类：club>0.30, spade<0.20
                val ratio = computePlateauRatio(suitTrimmed.first, suitTrimmed.second, suitTrimmed.third)
                bestSuit = if (ratio > 0.30) "c" else "s"
                suitConf = if (ratio > 0.30)
                    0.5 + (ratio - 0.30) * 1.5
                else
                    0.5 + (0.30 - ratio) * 1.5
                suitConf = suitConf.coerceIn(0.5, 0.9)
                suitUnc = true // plateau分类无IoU分差可验，置信地板0.50，保守标记
            } else {
                // 红色suit用IoU匹配（♥ vs ♦），双门槛验分差
                val sm = match(suitTrimmed, suitTemplates, useTopology = false)
                bestSuit = sm.label
                suitConf = sm.score.toDouble()
                suitUnc = sm.uncertain
            }

            if (bestRank == null || bestSuit == null) {
                return failReason("match_fail rank=$bestRank suit=$bestSuit")
            }

            // confidence取两门min（保守值，禁止rank/suit互相背书）；旧加权0.55/0.45废弃
            val conf = minOf(rankConf, suitConf).coerceIn(0.0, 1.0)
            CardResult(bestRank, bestSuit, conf.toFloat(), rankConf.toFloat(), suitConf.toFloat(),
                slot = handIndex, rankUncertain = rankUnc, suitUncertain = suitUnc)
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
            for (col in 0 until w) if (!mask[row * w + col]) cnt++  // mask极性修正
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
            if (!mask[startIdx] && !visited[startIdx]) {  // !mask=内容
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
                                if (!mask[ni] && !visited[ni]) {  // !mask=内容
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
                if (!mask[i * w + x]) { if (left < 0) left = x; right = x }  // !mask=内容
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
     * 手牌角区颜色检测，直接复用公共牌的detectColor逻辑。
     * detectColor返回true=红色（红桃/方块），手牌需要true=黑色（黑桃/梅花），
     * 所以取反。旧版因采样范围错误导致黑桃/梅花误判。
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

        // 手牌（角区识别）
        for (i in 0..1) {
            val result = recognizeHandCard(screenshot, i)
            if (result != null) {
                holeCards.add(result)
                // V2.9.574: r/s分别带不确定标记，便于diag定位是rank还是suit不可信
                val u = (if (result.rankUncertain) "rU" else "") + (if (result.suitUncertain) "sU" else "")
                diag.append("H$i=OK(${result.rank}${result.suit},c=%.2f%s);".format(result.confidence, if (u.isNotEmpty()) ",$u" else ""))
            } else {
                diag.append("H$i=FAIL;")
            }
        }

        // 公共牌
        for (i in 0..4) {
            val result = recognizeCommunityCard(screenshot, i)
            if (result != null) {
                communityCards.add(result)
                val u = (if (result.rankUncertain) "rU" else "") + (if (result.suitUncertain) "sU" else "")
                diag.append("C$i=OK(${result.rank}${result.suit},c=%.2f%s);".format(result.confidence, if (u.isNotEmpty()) ",$u" else ""))
            }
        }

        diag.append("hand=${holeCards.size}/2,comm=${communityCards.size}/5")
        lastDiag = diag.toString()
        return Pair(holeCards, communityCards)
    }
}
