package win.opt.view

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.content.pm.ServiceInfo
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URL

class HttpServerService : Service() {
    private val TAG = "HttpServerService"

    companion object {
        private const val CHANNEL_ID = "poker_http"
        private const val NOTIFICATION_ID = 3
        // v2.9.35: 热更新远程JS地址（ghfast代理 + 直连GitHub双保险）
        private const val HOTLOAD_URL = "https://ghfast.top/https://raw.githubusercontent.com/vd90ba4aof/poker-app/main/app/src/main/assets/poker_helper.html"
        private const val HOTLOAD_URL_FALLBACK = "https://raw.githubusercontent.com/vd90ba4aof/poker-app/main/app/src/main/assets/poker_helper.html"
        private const val HOTLOAD_FILE = "poker_helper_hot.html"
        private const val HOTLOAD_TIMEOUT = 15000 // 15秒超时
        // R6-fix: POST body大小限制（1MB），防止OOM攻击
        private const val MAX_POST_BODY_SIZE = 1 * 1024 * 1024
        // R6-fix: 热更新并发+频率限制
        private const val HOTLOAD_MIN_INTERVAL_MS = 5000L // 最小间隔5秒
        private const val HOTLOAD_DOWNLOAD_MAX_SIZE = 2 * 1024 * 1024 // 下载HTML最大2MB
    }

    // R6-fix: 热更新并发信号量（最多1个并发下载）
    private val hotloadSemaphore = java.util.concurrent.Semaphore(1)
    // R6-fix: 热更新原子标志，防止并发修改pokerHelperHtml
    private val hotloadInProgress = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var lastHotloadTime = 0L

    private var server: NanoHTTPD? = null
    private var pokerHelperHtml: String? = null
    private var hotloadSource: String = "local" // "local" or "remote"
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    private fun loadPokerHelperHtml(): String {
        if (pokerHelperHtml == null) {
            // V2.9.113: 先从assets加载当前版本号
            var assetsVer = ""
            try {
                val is_ = assets.open("poker_helper.html")
                val assetsHtml = java.io.InputStreamReader(is_, "UTF-8").readText()
                is_.close()
                val verMatch = Regex("""V(\d+\.\d+\.\d+)""").find(assetsHtml)
                assetsVer = verMatch?.groupValues?.get(1) ?: ""
            } catch (_: Exception) {}
            // v2.9.35: 优先加载热更新文件（持久化的远程版本）
            // V2.9.113: 但如果热更新版本比assets旧，删除热更新文件
            try {
                val hotFile = File(filesDir, HOTLOAD_FILE)
                if (hotFile.exists()) {
                    val hotHtml = hotFile.readText(Charsets.UTF_8)
                    val hotVerMatch = Regex("""V(\d+\.\d+\.\d+)""").find(hotHtml)
                    val hotVer = hotVerMatch?.groupValues?.get(1) ?: "0"
                    if (assetsVer.isNotEmpty() && hotVer < assetsVer) {
                        // 热更新版本比assets旧→删除，用assets的新版
                        hotFile.delete()
                        Log.w(TAG, "热更新版本$hotVer < assets版本$assetsVer，已删除旧热更新")
                    } else {
                        pokerHelperHtml = hotHtml
                        hotloadSource = "remote"
                    }
                }
            } catch (_: Exception) {}
            // 没有热更新文件则从assets加载
            if (pokerHelperHtml == null) {
                try {
                    val is_ = assets.open("poker_helper.html")
                    val reader = java.io.InputStreamReader(is_, "UTF-8")
                    pokerHelperHtml = reader.readText()
                    reader.close()
                    hotloadSource = "local"
                } catch (e: Exception) {
                    pokerHelperHtml = "<html><body><h2>策略引擎加载失败</h2><p>${e.message}</p></body></html>"
                }
            }
        }
        return pokerHelperHtml ?: ""
    }

    // R6-fix: 安全body解析——防止超大POST body导致OOM
    private fun safeParseBody(session: NanoHTTPD.IHTTPSession): Map<String, String> {
        // 检查Content-Length头
        val contentLength = session.headers["content-length"]?.toLongOrNull() ?: 0L
        if (contentLength > MAX_POST_BODY_SIZE) {
            throw IllegalStateException("Request body too large: ${contentLength}B (max ${MAX_POST_BODY_SIZE}B)")
        }
        val files = HashMap<String, String>()
        session.parseBody(files)
        // 二次检查：解析后实际大小
        val postData = files["postData"] ?: ""
        if (postData.length > MAX_POST_BODY_SIZE) {
            throw IllegalStateException("Post data too large: ${postData.length}B")
        }
        return files
    }

    // R6-fix: 增强热更新安全检查——防止黑名单绕过导致RCE
    private fun containsMaliciousCode(html: String): Boolean {
        return try {
            // 1. 去除JS注释和空白后检查（防注释绕过）
            val normalized = html
                .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")   // 移除多行注释
                .replace(Regex("//[^\\n]*"), "")              // 移除单行注释
                .replace(Regex("\\s+"), "")                   // 移除所有空白
            // 2. 增强正则黑名单（覆盖字符串拼接、方括号访问、编码绕过等）
            val patterns = listOf(
                Regex("eval\\s*\\(", RegexOption.IGNORE_CASE),
                Regex("Function\\s*\\(", RegexOption.IGNORE_CASE),
                Regex("setTimeout\\s*\\(\\s*['\"]"),
                Regex("setInterval\\s*\\(\\s*['\"]"),
                Regex("document\\.cookie"),
                Regex("localStorage"),
                Regex("sessionStorage"),
                Regex("XMLHttpRequest"),
                Regex("fetch\\s*\\(", RegexOption.IGNORE_CASE),
                Regex("WebSocket"),
                Regex("importScripts"),
                Regex("atob\\s*\\("),
                Regex("\\.src\\s*=\\s*['\"]https?://"),
                Regex("createElement\\s*\\(\\s*['\"]script"),
                Regex("innerHTML\\s*[+]?="),
                Regex("javascript\\s*:", RegexOption.IGNORE_CASE),
                Regex("\\[\\s*['\"]\\w+['\"]\\s*\\+"),       // 方括号+字符串拼接绕过
                Regex("String\\.fromCharCode"),
                Regex("new\\s+Function"),
                Regex("window\\s*\\["),                       // window["xxx"]间接访问
                Regex("AndroidBridge\\.\\s*(autoDecision|triggerMultiFrame|setAutoSpeed|triggerCapture)")
            )
            patterns.any { it.containsMatchIn(normalized) }
        } catch (_: Exception) {
            true  // 检查异常时保守拒绝
        }
    }

    // R6-fix: 安全读取URL内容——限制下载大小防止内存炸弹
    private fun safeReadUrl(urlStr: String, timeoutMs: Int): String? {
        return try {
            val conn = URL(urlStr).openConnection()
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            val inputStream = conn.getInputStream()
            val buffer = ByteArray(8192)
            val output = java.io.ByteArrayOutputStream()
            var totalRead = 0
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                totalRead += bytesRead
                if (totalRead > HOTLOAD_DOWNLOAD_MAX_SIZE) {
                    Log.w(TAG, "热更新下载超限: ${totalRead}B > ${HOTLOAD_DOWNLOAD_MAX_SIZE}B，终止")
                    inputStream.close()
                    return null
                }
                output.write(buffer, 0, bytesRead)
            }
            inputStream.close()
            output.toString(Charsets.UTF_8.name())
        } catch (_: Exception) {
            null
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            server?.stop()
            server = null
            stopSelf()
            return START_NOT_STICKY
        }
        // V2.9.113: 清空HTML缓存，强制重新加载（防止升级后仍用旧版HTML）
        pokerHelperHtml = null

        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        if (server == null) {
            server = object : NanoHTTPD(8666) {
                override fun serve(session: IHTTPSession): Response {
                    // V2.9.114: CORS preflight——WebViewAssetLoader跨域请求需OPTIONS预检
                    if (session.method == Method.OPTIONS) {
                        return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "").apply {
                            addHeader("Access-Control-Allow-Origin", "*")
                            addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
                            addHeader("Access-Control-Allow-Headers", "Content-Type")
                            addHeader("Access-Control-Max-Age", "86400")
                        }
                    }
                    return when {
                        session.uri == "/" || session.uri == "/poker" || session.uri == "/helper" || session.uri == "/index.html" -> {
                            // V2.9.15: 不再每次请求清空缓存！pokerHelperHtml只加载一次到内存
                            val html = loadPokerHelperHtml()
                            newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html).apply {
                                addHeader("Access-Control-Allow-Origin", "*")
                                addHeader("Cache-Control", "no-cache, no-store")
                            }
                        }
                        session.uri == "/api/screenshot" -> {
                            val data = ScreenCaptureService.latestScreenshot
                            if (data != null) {
                                newFixedLengthResponse(
                                    Response.Status.OK,
                                    "image/jpeg",
                                    ByteArrayInputStream(data),
                                    data.size.toLong()
                                ).apply {
                                    addHeader("Access-Control-Allow-Origin", "*")
                                    addHeader("Cache-Control", "no-cache, no-store")
                                }
                            } else {
                                newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "no screenshot yet").apply {
                                    addHeader("Access-Control-Allow-Origin", "*")
                                }
                            }
                        }
                        session.uri == "/api/status" -> {
                            val capture = ScreenCaptureService
                            val timeSinceLast = if (capture.lastCaptureTime > 0) 
                                (System.currentTimeMillis() - capture.lastCaptureTime) / 1000 else -1
                            val panelW = FloatingService.currentPanelWidth
                            val json = JSONObject().apply {
                                put("running", FloatingService.isRunning)
                                put("accessibilityRunning", ScreenOptService.isServiceRunning())
                                put("hasScreenshot", capture.latestScreenshot != null)
                                put("captureCount", capture.captureCount)
                                put("timeSinceLast", timeSinceLast)
                                put("error", capture.lastError)
                                put("panelWidth", panelW)
                                put("version", BuildConfig.VERSION_NAME)
                                put("htmlSource", hotloadSource)
                                put("chipStatus", capture.lastChipStatus)
                                // V2.9.164: 热更新后通知WebView重载
                                val hotloadUpdated = try {
                                    getSharedPreferences("poker_prefs", MODE_PRIVATE).getBoolean("hotload_updated", false)
                                } catch (_: Exception) { false }
                                put("hotloadReload", hotloadUpdated)
                                if (hotloadUpdated) {
                                    try {
                                        getSharedPreferences("poker_prefs", MODE_PRIVATE).edit().putBoolean("hotload_updated", false).apply()
                                    } catch (_: Exception) {}
                                }
                            }.toString()
                            newFixedLengthResponse(Response.Status.OK, "application/json", json).apply {
                                addHeader("Access-Control-Allow-Origin", "*")
                                addHeader("Cache-Control", "no-cache, no-store")
                            }
                        }
                        // V1.2 新增：筹码识别状态API
                        session.uri == "/api/chips" -> {
                            val json = ChipTracker.getStatusJson()
                            newFixedLengthResponse(Response.Status.OK, "application/json", json).apply {
                                addHeader("Access-Control-Allow-Origin", "*")
                                addHeader("Cache-Control", "no-cache, no-store")
                            }
                        }
                        // V1.2 新增：重置筹码追踪
                        session.uri == "/api/chips/reset" -> {
                            ChipTracker.reset()
                            ScreenCaptureService.lastChipStatus = "已重置"
                            newFixedLengthResponse(Response.Status.OK, "application/json", """{"ok":true}""").apply {
                                addHeader("Access-Control-Allow-Origin", "*")
                            }
                        }
                        // v2.9.35: 热更新——从GitHub下载最新poker_helper.html
                        // V2.9.164: 双URL保险——ghfast代理失败时直连GitHub
                        // R6-fix: 频率限制+并发限制+增强安全检查+下载大小限制
                        session.uri == "/api/hotload" -> {
                            val now = System.currentTimeMillis()
                            // R6-fix: 频率限制（5秒最小间隔）
                            if (now - lastHotloadTime < HOTLOAD_MIN_INTERVAL_MS) {
                                val retryAfter = (HOTLOAD_MIN_INTERVAL_MS - (now - lastHotloadTime)) / 1000
                                val json = JSONObject().apply {
                                    put("ok", false)
                                    put("error", "too_frequent")
                                    put("retryAfter", retryAfter)
                                }.toString()
                                newFixedLengthResponse(Response.Status.TOO_MANY_REQUESTS, "application/json", json).apply {
                                    addHeader("Access-Control-Allow-Origin", "*")
                                    addHeader("Retry-After", retryAfter.toString())
                                }
                            } else if (!hotloadSemaphore.tryAcquire()) {
                                // R6-fix: 并发限制（最多1个并发下载）
                                val json = JSONObject().apply {
                                    put("ok", false)
                                    put("error", "too_many_concurrent")
                                }.toString()
                                newFixedLengthResponse(Response.Status.TOO_MANY_REQUESTS, "application/json", json).apply {
                                    addHeader("Access-Control-Allow-Origin", "*")
                                }
                            } else {
                                lastHotloadTime = now
                                Thread({
                                    try {
                                        // R6-fix: 原子标志防止并发修改
                                        if (!hotloadInProgress.compareAndSet(false, true)) {
                                            Log.w(TAG, "热更新已在进行中，跳过本次")
                                            return@Thread
                                        }
                                        try {
                                            var html: String? = null
                                            // R6-fix: 使用safeReadUrl限制下载大小
                                            html = safeReadUrl(HOTLOAD_URL, HOTLOAD_TIMEOUT / 2)
                                            // ghfast失败→直连GitHub
                                            if (html == null || html.isEmpty() || !html.contains("poker")) {
                                                html = safeReadUrl(HOTLOAD_URL_FALLBACK, HOTLOAD_TIMEOUT)
                                            }
                                            // R6-fix: 增强安全检查（替代旧黑名单）
                                            if (html != null && containsMaliciousCode(html)) {
                                                Log.w(TAG, "热更新内容包含可疑代码，已拒绝")
                                                html = null
                                            }
                                            if (html != null && html.isNotEmpty() && html.contains("poker") && html.length > 1000) {
                                                pokerHelperHtml = html
                                                hotloadSource = "remote"
                                                try { File(filesDir, HOTLOAD_FILE).writeText(html, Charsets.UTF_8) } catch (_: Exception) {}
                                                try {
                                                    getSharedPreferences("poker_prefs", MODE_PRIVATE).edit().putBoolean("hotload_updated", true).apply()
                                                } catch (_: Exception) {}
                                            }
                                        } finally {
                                            hotloadInProgress.set(false)
                                        }
                                    } catch (_: Exception) {}
                                    finally { hotloadSemaphore.release() }
                                }, "HotloadThread").start()
                                // 立即返回"已触发"响应
                                val json = JSONObject().apply {
                                    put("ok", true)
                                    put("status", "downloading")
                                }.toString()
                                newFixedLengthResponse(Response.Status.OK, "application/json", json).apply {
                                    addHeader("Access-Control-Allow-Origin", "*")
                                }
                            }
                        }
                        // v2.9.35: 恢复本地版本
                        session.uri == "/api/hotload/revert" -> {
                            pokerHelperHtml = null // 清空缓存，下次请求重新从assets加载
                            hotloadSource = "local"
                            try {
                                val hotFile = File(filesDir, HOTLOAD_FILE)
                                if (hotFile.exists()) hotFile.delete()
                            } catch (_: Exception) {}
                            newFixedLengthResponse(Response.Status.OK, "application/json",
                                """{"ok":true,"source":"local"}""").apply {
                                addHeader("Access-Control-Allow-Origin", "*")
                            }
                        }
                        // V2.1: 按需截屏+API识别（仅无障碍截图，绝不走MediaProjection）
                        session.uri == "/api/capture" -> {
                            try {
                                // P2-R3-5: 使用同步截屏方法，避免回调覆盖竞态
                                if (ScreenOptService.isServiceRunning()) {
                                    val captureSuccess = ScreenOptService.captureScreenSync(3000)
                                    val json = JSONObject().apply {
                                        put("ok", ScreenCaptureService.latestScreenshot != null)
                                        put("method", if (captureSuccess) "accessibility" else "failed")
                                        put("chipStatus", ScreenCaptureService.lastChipStatus)
                                        put("captureCount", ScreenCaptureService.captureCount)
                                    }.toString()
                                    newFixedLengthResponse(Response.Status.OK, "application/json", json).apply {
                                        addHeader("Access-Control-Allow-Origin", "*")
                                    }
                                } else {
                                    // V2.1: 无障碍服务未开启 → 返回错误，绝不降级MediaProjection
                                    val json = JSONObject().apply {
                                        put("ok", false)
                                        put("error", "accessibility_not_enabled")
                                        put("message", "请先开启无障碍服务")
                                    }.toString()
                                    newFixedLengthResponse(Response.Status.OK, "application/json", json).apply {
                                        addHeader("Access-Control-Allow-Origin", "*")
                                    }
                                }
                            } catch (e: Exception) {
                                newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", 
                                    """{"error":"${e.message}"}""").apply {
                                    addHeader("Access-Control-Allow-Origin", "*")
                                }
                            }
                        }
                        // V2.1: API视觉识别（仅无障碍截图）
                        session.uri == "/api/analyze" -> {
                            try {
                                val screenshot = ScreenCaptureService.latestScreenshot
                                if (screenshot == null) {
                                    // V2.1: 无截图 → 返回错误提示，绝不降级MediaProjection
                                    newFixedLengthResponse(Response.Status.OK, "application/json",
                                        """{"error":"no_screenshot","message":"请先点击🎯截屏"}""").apply {
                                        addHeader("Access-Control-Allow-Origin", "*")
                                    }
                                } else if (VisionApiClient.apiKey.isEmpty()) {
                                    newFixedLengthResponse(Response.Status.OK, "application/json",
                                        """{"error":"no_api_key","message":"请在设置中配置API Key"}""").apply {
                                        addHeader("Access-Control-Allow-Origin", "*")
                                    }
                                } else {
                                    val result = VisionApiClient.analyzeScreenshot(screenshot)
                                    if (result != null) {
                                        newFixedLengthResponse(Response.Status.OK, "application/json",
                                            VisionApiClient.toJson(result)).apply {
                                            addHeader("Access-Control-Allow-Origin", "*")
                                        }
                                    } else {
                                        newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json",
                                            """{"error":"${VisionApiClient.lastError}"}""").apply {
                                            addHeader("Access-Control-Allow-Origin", "*")
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json",
                                    """{"error":"${e.message}"}""").apply {
                                    addHeader("Access-Control-Allow-Origin", "*")
                                }
                            }
                        }
                        // V1.3 新增：获取/设置API配置
                        session.uri == "/api/config" -> {
                            when (session.method) {
                                Method.GET -> {
                                    val json = JSONObject().apply {
                                        put("provider", VisionApiClient.apiProvider)
                                        put("apiKey", if (VisionApiClient.apiKey.isNotEmpty()) "***${VisionApiClient.apiKey.takeLast(4)}" else "")
                                        put("apiUrl", VisionApiClient.apiUrl)
                                        put("model", VisionApiClient.modelName)
                                        put("hasKey", VisionApiClient.apiKey.isNotEmpty())
                                        put("compact_prompt", VisionApiClient.useCompactPrompt)
                                        put("prompt_mode", VisionApiClient.lastPromptMode)
                                        put("compact_success", VisionApiClient.compactSuccessCount)
                                        put("compact_fail", VisionApiClient.compactFailCount)
                                        put("fallback_success", VisionApiClient.fallbackSuccessCount)
                                    }.toString()
                                    newFixedLengthResponse(Response.Status.OK, "application/json", json).apply {
                                        addHeader("Access-Control-Allow-Origin", "*")
                                    }
                                }
                                Method.POST -> {
                                    try {
                                        val files = safeParseBody(session)
                                        val postData = files["postData"] ?: ""
                                        val config = JSONObject(postData)
                                        val provider = config.optString("provider", "")
                                        val key = config.optString("apiKey", "")
                                        if (key.isNotEmpty() && provider.isNotEmpty()) {
                                            VisionApiClient.updateConfig(provider, key)
                                            val json = JSONObject().apply {
                                                put("ok", true)
                                                put("provider", VisionApiClient.apiProvider)
                                                put("model", VisionApiClient.modelName)
                                            }.toString()
                                            newFixedLengthResponse(Response.Status.OK, "application/json", json).apply {
                                                addHeader("Access-Control-Allow-Origin", "*")
                                            }
                                        } else {
                                            newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json",
                                                """{"error":"need provider and apiKey"}""").apply {
                                                addHeader("Access-Control-Allow-Origin", "*")
                                            }
                                        }
                                    } catch (e: Exception) {
                                        newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json",
                                            """{"error":"${e.message}"}""").apply {
                                            addHeader("Access-Control-Allow-Origin", "*")
                                        }
                                    }
                                }
                                else -> newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "method not allowed")
                            }
                        }
                        // V2.9.47: 导出日志——接收JSON数据，触发Android分享
                        session.uri == "/api/export/history" && session.method == Method.POST -> {
                            try {
                                val files = safeParseBody(session)
                                // V2.9.75: NanoHTTPD 2.3.1默认UTF-8解码，直接用即可（旧代码ISO-8859-1→UTF-8转换反而把中文变成?）
                                val postData = files["postData"] ?: ""
                                // 保存到应用私有目录（Android 10+兼容，避免Scoped Storage限制）
                                val downloadDir = getExternalFilesDir(null) ?: filesDir
                                val fileName = "poker_log_${java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())}.json"
                                val exportFile = File(downloadDir, fileName)
                                exportFile.writeText(postData, Charsets.UTF_8)
                                // 同时复制到剪贴板（方便直接粘贴发送）
                                try {
                                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("poker_log", postData))
                                } catch (_: Exception) {}
                                // 弹出分享菜单
                                val fileUri = androidx.core.content.FileProvider.getUriForFile(
                                    this@HttpServerService,
                                    "${packageName}.fileprovider",
                                    exportFile
                                )
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/json"
                                    putExtra(Intent.EXTRA_STREAM, fileUri)
                                    putExtra(Intent.EXTRA_SUBJECT, "显示优化日志")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                startActivity(Intent.createChooser(shareIntent, "分享日志(已复制到剪贴板)").apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                })
                                val json = JSONObject().apply {
                                    put("ok", true)
                                    put("size", postData.length)
                                    put("file", exportFile.absolutePath)
                                    put("clipboard", true)
                                }.toString()
                                newFixedLengthResponse(Response.Status.OK, "application/json", json).apply {
                                    addHeader("Access-Control-Allow-Origin", "*")
                                }
                            } catch (e: Exception) {
                                newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json",
                                    """{"error":"${e.message}"}""").apply {
                                    addHeader("Access-Control-Allow-Origin", "*")
                                }
                            }
                        }
                        else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "not found")
                    }
                }
            }
            try {
                server?.start()
            } catch (e: Exception) {
                Log.e("HttpServerService", "Failed to start HTTP server", e)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "显示优化", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "HTTP服务运行中" }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("截屏优化 v${BuildConfig.VERSION_NAME}")
                .setContentText("HTTP服务运行中")
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("截屏优化 v${BuildConfig.VERSION_NAME}")
                .setContentText("HTTP服务运行中")
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .setOngoing(true)
                .build()
        }
    }
}
