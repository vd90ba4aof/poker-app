package win.opt.view

/**
 * 截图数据共享存储（V2.9.541精简）
 *
 * 唯一截图方案：ScreenOptService（AccessibilityService.takeScreenshot）。
 * 本类仅作为截图数据的跨组件共享容器，不再是Android Service。
 */
object ScreenCaptureService {

    @Volatile var isRunning = false
    @Volatile var latestScreenshot: ByteArray? = null
    // V2.9.638 fix: @Volatile缺失——captureCount由ScreenOptService(回调线程)写、HttpServerService(主线程)读
    @Volatile var captureCount: Int = 0
    @Volatile var lastCaptureTime: Long = 0
    @Volatile var lastError: String = ""
    // V2.9.638 fix: @Volatile缺失——screenshotWidth/Height由分析线程写、其他线程读，无@Volatile可能读到陈旧值
    @Volatile var lastChipStatus: String = ""
    @Volatile var screenshotWidth: Int = 0
    @Volatile var screenshotHeight: Int = 0
}
