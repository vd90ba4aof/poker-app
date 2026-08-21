package com.pokerhelper.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log

/**
 * V2.9.515: 区域裁剪与拼接工具
 * 基于标注v3坐标（1080x2344基准），支持按屏幕尺寸自动缩放
 * 
 * 核心方案：
 * 1. 裁剪手牌(2张)、公共牌(5张)、底池区域
 * 2. 拼接为hand_stitch和community_stitch
 * 3. 3路并发API调用（手牌/公共牌/底池）
 * 4. 整合结果
 */
object RegionCropper {
    private const val TAG = "RegionCropper"
    
    // 基准分辨率
    private const val BASE_WIDTH = 1080
    private const val BASE_HEIGHT = 2344
    
    // 标注v3坐标（1080x2344基准）
    data class RegionRect(val x1: Int, val y1: Int, val x2: Int, val y2: Int)
    
    // 手牌区域
    private val HAND_0 = RegionRect(30, 1700, 175, 1910)
    private val HAND_1 = RegionRect(130, 1730, 290, 1950)
    
    // 公共牌区域（每张133px宽，间距17px）
    private val COMM_0 = RegionRect(172, 1058, 305, 1275)
    private val COMM_1 = RegionRect(322, 1058, 455, 1275)
    private val COMM_2 = RegionRect(472, 1058, 605, 1275)
    private val COMM_3 = RegionRect(622, 1058, 755, 1275)
    private val COMM_4 = RegionRect(772, 1058, 905, 1275)
    
    // 底池金额区域
    private val POT_AMOUNT = RegionRect(440, 1065, 650, 1115)
    
    // 缩放比例
    private var scaleX: Float = 1.0f
    private var scaleY: Float = 1.0f
    
    /**
     * 初始化缩放比例（根据实际屏幕尺寸）
     */
    fun init(screenWidth: Int, screenHeight: Int) {
        scaleX = screenWidth.toFloat() / BASE_WIDTH
        scaleY = screenHeight.toFloat() / BASE_HEIGHT
        Log.d(TAG, "缩放初始化: ${screenWidth}x${screenHeight}, scaleX=$scaleX, scaleY=$scaleY")
    }
    
    /**
     * 缩放坐标
     */
    private fun RegionRect.scaled(): RegionRect {
        return RegionRect(
            (x1 * scaleX).toInt(),
            (y1 * scaleY).toInt(),
            (x2 * scaleX).toInt(),
            (y2 * scaleY).toInt()
        )
    }
    
    /**
     * 裁剪区域
     */
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
    
    /**
     * 拼接多张图片为横向长图
     * @param bitmaps 图片列表
     * @param gap 图片间距（像素）
     */
    private fun stitchHorizontally(bitmaps: List<Bitmap>, gap: Int = 10): Bitmap? {
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
    
    /**
     * 裁剪手牌区域（2张）
     * @return Pair(hand_stitch, listOf(hand_0, hand_1))
     */
    fun cropHandCards(bitmap: Bitmap): Pair<Bitmap?, List<Bitmap?>> {
        val hand0 = cropRegion(bitmap, HAND_0)
        val hand1 = cropRegion(bitmap, HAND_1)
        val validCards = listOfNotNull(hand0, hand1)
        val stitch = if (validCards.isNotEmpty()) stitchHorizontally(validCards, gap = 15) else null
        return Pair(stitch, listOf(hand0, hand1))
    }
    
    /**
     * 裁剪公共牌区域（5张）
     * @return Pair(community_stitch, listOf(comm_0..comm_4))
     */
    fun cropCommunityCards(bitmap: Bitmap): Pair<Bitmap?, List<Bitmap?>> {
        val comm0 = cropRegion(bitmap, COMM_0)
        val comm1 = cropRegion(bitmap, COMM_1)
        val comm2 = cropRegion(bitmap, COMM_2)
        val comm3 = cropRegion(bitmap, COMM_3)
        val comm4 = cropRegion(bitmap, COMM_4)
        val validCards = listOfNotNull(comm0, comm1, comm2, comm3, comm4)
        val stitch = if (validCards.isNotEmpty()) stitchHorizontally(validCards, gap = 8) else null
        return Pair(stitch, listOf(comm0, comm1, comm2, comm3, comm4))
    }
    
    /**
     * 裁剪底池金额区域
     */
    fun cropPotAmount(bitmap: Bitmap): Bitmap? {
        return cropRegion(bitmap, POT_AMOUNT)
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
