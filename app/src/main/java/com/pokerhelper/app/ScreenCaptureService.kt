package com.pokerhelper.app

/**
 * 截图数据共享存储（V2.9.541精简）
 *
 * 唯一截图方案：ScreenOptService（AccessibilityService.takeScreenshot）。
 * 本类仅作为截图数据的跨组件共享容器，不再是Android Service。
 */
object ScreenCaptureService {

    @Volatile var isRunning = false
    @Volatile var latestScreenshot: ByteArray? = null
    var captureCount: Int = 0
    @Volatile var lastCaptureTime: Long = 0
    @Volatile var lastError: String = ""
    var lastChipStatus: String = ""
    var screenshotWidth: Int = 0
    var screenshotHeight: Int = 0
}
