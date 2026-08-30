package win.opt.view

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import java.io.ByteArrayOutputStream

/**
 * ★ V2.1 核心截图服务 ★
 * 
 * 唯一截图方案：AccessibilityService.takeScreenshot()
 * - 不走 MediaProjection → 游戏检测不到 → 不会黑屏
 * - V2.1 彻底移除 MediaProjection 降级路径
 * 
 * 截图结果统一存入 ScreenCaptureService.latestScreenshot
 * FloatingService 通过 onScreenshotReady 回调获知截图完成
 */
class ScreenOptService : AccessibilityService() {

    companion object {
        var isRunning = false
            private set
        
        /** 截图完成回调：参数=true截图成功，false失败需降级 */
        @Volatile
        var onScreenshotReady: ((Boolean) -> Unit)? = null
        
        // P0-R4-2: 回调读写同步锁
        private val callbackLock = Any()
        // R9-9-fix: 回调代次——captureScreenSync超时后迟到帧不得投递到"已过期的等待方"
        // 也不得在回调被清空后的竞态窗口里，以null身份被误恢复成自动流水线回调
        @Volatile private var callbackGeneration = 0L

        /** 安全设置回调 */
        fun setScreenshotCallback(cb: ((Boolean) -> Unit)?) {
            synchronized(callbackLock) {
                onScreenshotReady = cb
                callbackGeneration++
            }
        }
        
        /** 安全获取回调 */
        fun getScreenshotCallback(): ((Boolean) -> Unit)? {
            synchronized(callbackLock) { return onScreenshotReady }
        }

        private var instance: ScreenOptService? = null

        fun isServiceRunning(): Boolean = instance != null

        /**
         * 发起无障碍截图
         * 结果通过 onScreenshotReady 回调通知
         */
        fun captureScreen() {
            val svc = instance
            if (svc == null) {
                onScreenshotReady?.invoke(false)
                return
            }
            svc.performCapture()
        }
        
        /**
         * P2-R3-5: 同步截屏方法，不依赖共享回调
         * 供HttpServerService等外部调用，避免回调覆盖
         * R6-fix: 使用callbackLock同步保护，防止与自动截屏并发冲突
         */
        fun captureScreenSync(timeoutMs: Long = 3000): Boolean {
            val svc = instance ?: return false
            // R6-fix: 使用同步方法检查回调，防止check-then-act竞态
            synchronized(callbackLock) {
                if (onScreenshotReady != null) {
                    android.util.Log.w("ScreenOptService", "captureScreenSync: 已有回调等待中，拒绝同步请求")
                    return false
                }
            }
            val latch = java.util.concurrent.CountDownLatch(1)
            var result = false
            val originalCallback: ((Boolean) -> Unit)?
            // R9-9-fix: myGen = 本等待方设置闭包时的代次；任何后续setScreenshotCallback
            // （超时恢复/自动流水线接管）都会推进代次，迟到帧回调读到代次不符即空转丢弃
            val myGen: Long
            synchronized(callbackLock) {
                originalCallback = onScreenshotReady
                callbackGeneration++
                myGen = callbackGeneration
                onScreenshotReady = { success ->
                    if (callbackGeneration != myGen) {
                        android.util.Log.w("ScreenOptService", "R9-9: 迟到帧到达，代次已变($callbackGeneration!=$myGen)，空转丢弃")
                        return@let
                    }
                    result = success
                    latch.countDown()
                }
            }
            svc.performCapture()
            val done = latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            synchronized(callbackLock) {
                if (callbackGeneration == myGen) {
                    // 期间没有新回调接管，恢复原等待方（正常情况为null）
                    // setScreenshotCallback内部会推进代次，迟到帧闭包随即失效
                    setScreenshotCallback(originalCallback)
                }
                // 代次已被新setScreenshotCallback推进 → 自动流水线已接管，不动
            }
            if (!done) android.util.Log.w("ScreenOptService", "captureScreenSync超时，迟到帧将被代次守卫丢弃")
            return result
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isRunning = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 不需要处理无障碍事件，此服务仅用于截图
    }

    override fun onInterrupt() {
        // 不需要处理中断
    }

    override fun onDestroy() {
        super.onDestroy()
        onScreenshotReady = null  // P2-fix: 清除回调防止泄漏和悬空引用
        instance = null
        isRunning = false
    }

    /**
     * ★ 核心方法：无障碍截图 ★
     * 
     * AccessibilityService.takeScreenshot() 走系统无障碍通道
     * 不创建 MediaProjection / VirtualDisplay → 游戏检测不到
     * 
     * 关键注意事项（风险#2，高严重度）：
     * HardwareBuffer → Bitmap 必须先 copy() 再 close()
     * 否则截图空白
     */
    private fun performCapture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // API 30以下不支持takeScreenshot，回退
            ScreenCaptureService.lastError = "无障碍截图需Android 11+"
            handler.post { onScreenshotReady?.invoke(false) }
            return
        }

        try {
            @Suppress("DEPRECATION")
            val displayId = android.view.Display.DEFAULT_DISPLAY

            takeScreenshot(
                displayId,
                mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshotResult: AccessibilityService.ScreenshotResult) {
                        try {
                            val hardwareBuffer = screenshotResult.hardwareBuffer

                            // ★★★ 关键：必须先copy再close，否则截图空白 ★★★
                            // 1. Wrap HardwareBuffer → Hardware Bitmap
                            //    豪哥手机Android 15(API35)，直接用双参数版本
                            val hardwareBitmap = Bitmap.wrapHardwareBuffer(
                                hardwareBuffer, screenshotResult.colorSpace
                            )

                            // 2. 复制为 ARGB_8888 软件Bitmap（独立于HardwareBuffer）
                            val softwareBitmap = hardwareBitmap?.copy(Bitmap.Config.ARGB_8888, false)

                            // 3. ★ 安全关闭硬件资源（copy之后才能close）★
                            hardwareBitmap?.recycle()
                            hardwareBuffer.close()
                            // ScreenshotResult没有close()方法，不需要关闭

                            if (softwareBitmap != null) {
                                // 4. 压缩为JPEG（V2.9.508: 质量85→95，减少识别损失）
                                val stream = ByteArrayOutputStream()
                                softwareBitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)
                                val jpegBytes = stream.toByteArray()
                                softwareBitmap.recycle()

                                // 5. ★ 统一存入 ScreenCaptureService.latestScreenshot ★
                                //    FloatingService 和 HttpServerService 都从这里读取
                                ScreenCaptureService.latestScreenshot = jpegBytes
                                ScreenCaptureService.captureCount++
                                ScreenCaptureService.lastCaptureTime = System.currentTimeMillis()
                                ScreenCaptureService.lastError = ""

                                handler.post { onScreenshotReady?.invoke(true) }
                            } else {
                                // HardwareBuffer → Bitmap 失败
                                ScreenCaptureService.lastError = "无障碍截图: Bitmap转换失败"
                                handler.post { onScreenshotReady?.invoke(false) }
                            }
                        } catch (e: Throwable) {
                            ScreenCaptureService.lastError = "无障碍截图处理失败: ${e.message}"
                            handler.post { onScreenshotReady?.invoke(false) }
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        val errMsg = when (errorCode) {
                            1 -> "内部错误"
                            2 -> "无无障碍权限"
                            3 -> "无效显示ID"
                            4 -> "窗口内容变化"
                            else -> "未知错误($errorCode)"
                        }
                        ScreenCaptureService.lastError = "无障碍截图失败: $errMsg"
                        handler.post { onScreenshotReady?.invoke(false) }
                    }
                }
            )
        } catch (e: Throwable) {
            ScreenCaptureService.lastError = "无障碍截图异常: ${e.message}"
            handler.post { onScreenshotReady?.invoke(false) }
        }
    }
}
