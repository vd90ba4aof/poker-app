package com.pokerhelper.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import java.security.MessageDigest

/**
 * V2.9.516: 区域裁剪与拼接工具
 * 基于GG扑克竖屏截图（1080x2344基准），支持按屏幕尺寸自动缩放
 *
 * 核心方案（V2: 识别第一，按钮必识别）：
 * 1. 裁剪牌面区域（手牌+公共牌+底池），拼接成一张图
 * 2. 裁剪操作区（底部按钮+筹码+预设），单独识别
 * 3. 牌面支持hash缓存——手牌一手不变，公共牌增量识别
 * 4. 操作区每帧必识别——判断是否轮到自己行动
 */
object RegionCropper {
    private const val TAG = "RegionCropper"

    // 基准分辨率
    private const val BASE_WIDTH = 1080
    private const val BASE_HEIGHT = 2344

    data class RegionRect(val x1: Int, val y1: Int, val x2: Int, val y2: Int)

    // ===== 手牌区域（基于Screenshot_2026-08-14干净截图实测）=====
    // 2张手牌在底部偏左，重叠式扇出，直接裁整体区域
    // V2.9.523: H0坐标修正（原35起裁入了卡片左侧黑边，导致rank+suit连通成巨型组件）
    private val HAND_0 = RegionRect(55, 1745, 195, 1945)
    private val HAND_1 = RegionRect(150, 1745, 290, 1945)

    // ===== 公共牌区域（5张，基于Screenshot_2026-08-14干净截图实测）=====
    // 每张宽约130px，间距约20px，y≈1068-1272
    private val COMM_0 = RegionRect(166, 1068, 310, 1272)
    private val COMM_1 = RegionRect(316, 1068, 460, 1272)
    private val COMM_2 = RegionRect(466, 1068, 610, 1272)
    private val COMM_3 = RegionRect(616, 1068, 760, 1272)
    private val COMM_4 = RegionRect(766, 1068, 910, 1272)

    // ===== 底池金额区域（公共牌上方，黄色数字）=====
    // V2.9.526: 坐标修正。旧值(380,1240,700,1310)裁到的是公共牌下方，实际底池在y≈975-1050
    // 实测11张训练截图：底池"500"~"6,469"黄色数字，h≈30-31px
    private val POT_AMOUNT = RegionRect(460, 975, 620, 1050)

    // ===== 我的筹码区域（左下角，白色数字）=====
    // V2.9.526: 新增本地CV筹码识别。实测白色数字h≈28-29px
    // 注意y>2055有绿色进度条，白色mask+row-band clipping会自动排除
    private val MY_CHIPS = RegionRect(95, 1990, 275, 2070)

    // ===== 操作区（底部按钮+筹码+预设）=====
    // 包含：主操作按钮（y≈2140-2340）、预设按钮（x≈730-1060, y≈1640-2130）、
    //       我的筹码（x≈45-310, y≈1935-2020）、底部玩家信息
    private val ACTION_AREA = RegionRect(0, 1600, 1080, 2344)

    // 缩放比例
    private var scaleX: Float = 1.0f
    private var scaleY: Float = 1.0f

    // ===== 缓存：牌面区域hash =====
    @Volatile private var cachedHandHash: String? = null
    @Volatile private var cachedHandCards: List<VisionApiClient.CardInfo>? = null
    @Volatile private var cachedCommHashes: Array<String?> = arrayOfNulls(5)
    @Volatile private var cachedCommCards: Array<VisionApiClient.CardInfo?> = arrayOfNulls(5)
    @Volatile private var cachedPotHash: String? = null
    @Volatile private var cachedPotValue: Long = 0L
    @Volatile private var handCacheValid: Boolean = false

    fun init(screenWidth: Int, screenHeight: Int) {
        scaleX = screenWidth.toFloat() / BASE_WIDTH
        scaleY = screenHeight.toFloat() / BASE_HEIGHT
        Log.d(TAG, "缩放初始化: ${screenWidth}x${screenHeight}, scaleX=$scaleX, scaleY=$scaleY")
    }

    private fun RegionRect.scaled(): RegionRect {
        return RegionRect(
            (x1 * scaleX).toInt(),
            (y1 * scaleY).toInt(),
            (x2 * scaleX).toInt(),
            (y2 * scaleY).toInt()
        )
    }

    private fun cropRegion(bitmap: Bitmap, region: RegionRect): Bitmap? {
        val r = region.scaled()
        val x = r.x1.coerceIn(0, bitmap.width - 1)
        val y = r.y1.coerceIn(0, bitmap.height - 1)
        val w = (r.x2 - r.x1).coerceIn(1, bitmap.width - x)
        val h = (r.y2 - r.y1).coerceIn(1, bitmap.height - y)
        return try {
            Bitmap.createBitmap(bitmap, x, y, w, h)
        } catch (e: Exception) {
            Log.e(TAG, "裁剪失败: $region", e)
            null
        }
    }

    private fun stitchHorizontally(bitmaps: List<Bitmap>, gap: Int = 8): Bitmap? {
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
        } catch (e: Exception) {
            Log.e(TAG, "拼接失败", e)
            null
        }
    }

    private fun stitchVertically(bitmaps: List<Bitmap>, gap: Int = 8): Bitmap? {
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
        } catch (e: Exception) {
            Log.e(TAG, "垂直拼接失败", e)
            null
        }
    }

    /**
     * 计算Bitmap内容的MD5 hash（用于缓存判断）
     * 采样像素而非全量，提高速度
     */
    fun bitmapHash(bitmap: Bitmap?): String {
        if (bitmap == null || bitmap.width == 0 || bitmap.height == 0) return "null"
        return try {
            val w = bitmap.width
            val h = bitmap.height
            // 采样步长：大图每5个像素采一个，小图全采
            val step = maxOf(1, minOf(w, h) / 32)
            val sb = StringBuilder()
            for (y in 0 until h step step) {
                for (x in 0 until w step step) {
                    sb.append(bitmap.getPixel(x, y))
                }
            }
            val md = MessageDigest.getInstance("MD5")
            val digest = md.digest(sb.toString().toByteArray())
            digest.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            "error_${System.currentTimeMillis()}"
        }
    }

    // ===== 牌面裁剪 =====

    /**
     * 裁剪手牌（2张），返回拼接图和单张列表
     */
    fun cropHandCards(bitmap: Bitmap): Pair<Bitmap?, List<Bitmap?>> {
        val hand0 = cropRegion(bitmap, HAND_0)
        val hand1 = cropRegion(bitmap, HAND_1)
        val validCards = listOfNotNull(hand0, hand1)
        val stitch = if (validCards.isNotEmpty()) stitchHorizontally(validCards, gap = 10) else null
        return Pair(stitch, listOf(hand0, hand1))
    }

    /**
     * 裁剪全部公共牌区域（5格），返回拼接图和单张列表
     */
    fun cropCommunityCards(bitmap: Bitmap): Pair<Bitmap?, List<Bitmap?>> {
        val cards = (0 until 5).map { cropRegion(bitmap, getCommRegion(it)) }
        val validCards = cards.filterNotNull()
        val stitch = if (validCards.isNotEmpty()) stitchHorizontally(validCards, gap = 6) else null
        return Pair(stitch, cards)
    }

    /**
     * 裁剪底池金额区域
     */
    fun cropPotAmount(bitmap: Bitmap): Bitmap? {
        return cropRegion(bitmap, POT_AMOUNT)
    }

    /**
     * 裁剪我的筹码区域（V2.9.526新增，用于本地CV数字识别）
     */
    fun cropMyChips(bitmap: Bitmap): Bitmap? {
        return cropRegion(bitmap, MY_CHIPS)
    }

    /**
     * 裁剪操作区（底部条带，包含按钮/筹码/预设）
     */
    fun cropActionArea(bitmap: Bitmap): Bitmap? {
        return cropRegion(bitmap, ACTION_AREA)
    }

    /**
     * 裁剪牌面合并图：公共牌 + 底池
     * 手牌单独处理（因为要缓存）
     * @return Triple(牌面合并图, 各公共牌格bitmap列表, 底池bitmap)
     */
    fun cropBoardArea(bitmap: Bitmap): Triple<Bitmap?, List<Bitmap?>, Bitmap?> {
        val commCards = (0 until 5).map { cropRegion(bitmap, getCommRegion(it)) }
        val potBmp = cropRegion(bitmap, POT_AMOUNT)

        // 拼接：公共牌横排 + 下方底池
        val validComm = commCards.filterNotNull()
        val commStitch = if (validComm.isNotEmpty()) stitchHorizontally(validComm, gap = 6) else null

        val parts = mutableListOf<Bitmap>()
        commStitch?.let { parts.add(it) }
        potBmp?.let { parts.add(it) }
        val merged = if (parts.size >= 2) stitchVertically(parts, gap = 10) else parts.firstOrNull()

        return Triple(merged, commCards, potBmp)
    }

    private fun getCommRegion(index: Int): RegionRect = when (index) {
        0 -> COMM_0; 1 -> COMM_1; 2 -> COMM_2; 3 -> COMM_3; 4 -> COMM_4
        else -> COMM_0
    }

    // ===== 缓存逻辑 =====

    /**
     * 检查手牌缓存是否命中
     * @return 缓存的手牌列表，未命中返回null
     */
    fun checkHandCache(handStitch: Bitmap?): List<VisionApiClient.CardInfo>? {
        if (handStitch == null) return null
        val hash = bitmapHash(handStitch)
        // 始终更新prevHandHash为本帧hash（在被updateHandCache覆盖前先读取旧的cachedHandHash）
        if (handCacheValid && hash == cachedHandHash && cachedHandCards != null) {
            // 缓存命中：prevHandHash和cachedHandHash一致
            Log.d(TAG, "♠ 手牌缓存命中: ${cachedHandCards?.map { "${it.rank}${it.suit}" }}")
            return cachedHandCards
        }
        // 缓存未命中：prevHandHash保存旧值，等updateHandCacheWithHash时比较
        return null
    }

    /**
     * 判断是否是新手牌（上一帧的手牌hash与当前缓存不同）
     * 在checkHandCache未命中后、调用updateHandCacheWithHash之前调用。
     * @param currentHash 当前帧手牌hash（由调用方预计算）
     */
    fun isNewHand(currentHash: String?): Boolean {
        if (currentHash == null) return false
        val old = cachedHandHash
        return old != null && old != currentHash
    }

    /**
     * 更新手牌缓存
     */
    fun updateHandCache(handStitch: Bitmap?, cards: List<VisionApiClient.CardInfo>) {
        if (handStitch != null && cards.size == 2) {
            cachedHandHash = bitmapHash(handStitch)
            cachedHandCards = cards
            handCacheValid = true
            Log.d(TAG, "♠ 手牌缓存已更新: ${cards.map { "${it.rank}${it.suit}" }}")
        }
    }

    /**
     * 用预计算的hash更新手牌缓存（bitmap已recycle时使用）
     */
    fun updateHandCacheWithHash(hash: String, cards: List<VisionApiClient.CardInfo>) {
        if (cards.size == 2) {
            cachedHandHash = hash
            cachedHandCards = cards
            handCacheValid = true
            Log.d(TAG, "♠ 手牌缓存已更新(hash): ${cards.map { "${it.rank}${it.suit}" }}")
        }
    }

    /**
     * 检查哪些公共牌格是新出现的（需要识别）
     * @return 需要识别的格子索引列表
     */
    fun findNewCommCards(commBitmaps: List<Bitmap?>): List<Int> {
        val newIndices = mutableListOf<Int>()
        for (i in commBitmaps.indices) {
            val bmp = commBitmaps[i] ?: continue
            val hash = bitmapHash(bmp)
            // 检测是否是"有牌"的格子：非空区域像素方差大
            val hasContent = detectCardPresence(bmp)
            if (hasContent) {
                if (cachedCommHashes[i] != hash) {
                    newIndices.add(i)
                }
            } else {
                // 空格子，清除缓存
                if (cachedCommHashes[i] != null) {
                    cachedCommHashes[i] = null
                    cachedCommCards[i] = null
                }
            }
        }
        return newIndices
    }

    /**
     * 检测格子里是否有牌（简单的像素方差判断）
     */
    private fun detectCardPresence(bmp: Bitmap): Boolean {
        return try {
            if (bmp.width < 10 || bmp.height < 10) return false
            var nonWhite = 0
            var sampled = 0
            val step = maxOf(1, minOf(bmp.width, bmp.height) / 20)
            for (y in 0 until bmp.height step step) {
                for (x in 0 until bmp.width step step) {
                    val p = bmp.getPixel(x, y)
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val b = p and 0xFF
                    // 非白色/非纯绿色桌面背景
                    if (!(r > 200 && g > 200 && b > 200) &&
                        !(r in 0..60 && g in 80..180 && b in 30..100)) {
                        nonWhite++
                    }
                    sampled++
                }
            }
            val ratio = nonWhite.toFloat() / maxOf(1, sampled)
            ratio > 0.05f  // 超过5%非背景像素 = 有牌
        } catch (_: Exception) { false }
    }

    /**
     * 获取当前缓存的公共牌（仅返回有牌的）
     */
    fun getCachedCommunityCards(): List<VisionApiClient.CardInfo> {
        return cachedCommCards.filterNotNull()
    }

    /**
     * 获取当前缓存的公共牌5格数组（含null，按位置0-4）
     */
    fun getCachedCommunitySlots(): Array<VisionApiClient.CardInfo?> {
        return cachedCommCards.clone()
    }

    /**
     * 更新某格公共牌缓存
     */
    fun updateCommCache(index: Int, bmp: Bitmap?, card: VisionApiClient.CardInfo?) {
        if (index in 0 until 5) {
            if (bmp != null && card != null) {
                cachedCommHashes[index] = bitmapHash(bmp)
                cachedCommCards[index] = card
            } else {
                cachedCommHashes[index] = null
                cachedCommCards[index] = null
            }
        }
    }

    /**
     * 用预计算hash更新公共牌格缓存
     */
    fun updateCommCacheWithHash(index: Int, hash: String, card: VisionApiClient.CardInfo) {
        if (index in 0 until 5) {
            cachedCommHashes[index] = hash
            cachedCommCards[index] = card
        }
    }

    /**
     * 检查底池缓存
     */
    fun checkPotCache(potBmp: Bitmap?): Long? {
        if (potBmp == null) return null
        val hash = bitmapHash(potBmp)
        return if (hash == cachedPotHash) {
            cachedPotValue
        } else null
    }

    fun updatePotCache(potBmp: Bitmap?, value: Long) {
        if (potBmp != null) {
            cachedPotHash = bitmapHash(potBmp)
            cachedPotValue = value
        }
    }

    /**
     * 用预计算hash更新底池缓存
     */
    fun updatePotCacheWithHash(hash: String, value: Long) {
        cachedPotHash = hash
        cachedPotValue = value
    }

    /**
     * 清空所有牌面缓存（新手牌开始时调用）
     */
    fun clearBoardCache() {
        // 注意：不清cachedHandHash/HandCards，手牌缓存仍有效
        // 只清空公共牌和底池缓存（新的一手牌，公共牌重新发）
        for (i in 0 until 5) {
            cachedCommHashes[i] = null
            cachedCommCards[i] = null
        }
        cachedPotHash = null
        cachedPotValue = 0L
        Log.d(TAG, "公共牌/底池缓存已清空（新手牌）")
    }

    /**
     * 释放Bitmap资源
     */
    fun recycleBitmaps(vararg bitmaps: Bitmap?) {
        for (bmp in bitmaps) {
            try {
                if (bmp != null && !bmp.isRecycled) {
                    bmp.recycle()
                }
            } catch (_: Exception) {}
        }
    }
}
