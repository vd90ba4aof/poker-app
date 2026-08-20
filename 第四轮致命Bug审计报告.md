# 第四轮致命Bug深度审计报告

**审计范围**: `/Coze/Drive/青云/poker-app/app/src/main/java/com/pokerhelper/app/`  
**审计日期**: 2025年  
**核心架构**: FloatingService截屏 → 本地/云端双路并发识别 → WebView策略面板 → ESP32蓝牙执行

---

## 一、危险函数搜索结果

### 1.1 蓝牙底层API (BluetoothGatt)

| 文件 | 行号 | 调用 |
|------|------|------|
| Esp32BleManager.kt | 35 | `private var bluetoothGatt: BluetoothGatt? = null` |
| Esp32BleManager.kt | 362 | `characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE` |
| Esp32BleManager.kt | 409-550 | `gattCallback` (BluetoothGattCallback全方法重写) |
| Esp32BleManager.kt | 474 | `descriptor?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE` |

**评估**: BluetoothGatt使用基本规范，但存在状态同步问题（详见Bug #1）。

### 1.2 WebView安全风险组合

| 文件 | 行号 | 调用 |
|------|------|------|
| FloatingService.kt | ~1430 | `wv.settings.javaScriptEnabled = true` |
| FloatingService.kt | ~1509 | `wv.addJavascriptInterface(object : Any() { ... })` |

**评估**: `addJavascriptInterface` + `setJavaScriptEnabled` 组合存在已知安全风险，但此处为本地HTML加载（WebViewAssetLoader），非远程URL，攻击面有限。但**匿名内部类持有FloatingService引用**构成内存泄漏风险。

### 1.3 其他危险函数

| 函数 | 搜索结果 |
|------|---------|
| `Runtime.exec()` | **未找到** ✅ |
| `ProcessBuilder` | **未找到** ✅ |
| `ObjectInputStream` / `ObjectOutputStream` | **未找到** ✅ |
| `Class.forName` / `Method.invoke` (反射) | **未找到** ✅ |

---

## 二、致命Bug清单

---

### Bug #1: P0-R4-1 — VisionApiClient共享状态并发竞态（数据错乱/决策错误）

**文件**: `VisionApiClient.kt` 多处  
**严重级别**: P0致命 — 可能导致错误决策（如把fold当成call执行）

#### 问题描述

`VisionApiClient` 是一个 `object`（单例），其 `analyzeScreenshot()` 方法在同一调用中**读取和修改**多个 `@Volatile` 共享字段：

```kotlin
// VisionApiClient.kt:78-99
@Volatile var lastResult: VisionResult? = null
@Volatile var holeCardsLocked: List<CardInfo>? = null
@Volatile var streetLocked: String? = null
@Volatile var dButtonLocked: String = ""
@Volatile var suitUncertain: Boolean = false
@Volatile var holeCardsRankLocked: List<String>? = null
```

**竞态场景**:

在 `FloatingService.processScreenshotAndAnalyze()` 中（约2420行起）：
1. **主线程**: 本地CV识别成功后，调用 `VisionApiClient.toJson(sceneResult)` 读取 `dButtonPosition` 等字段
2. **后台线程**: 同时启动 `Thread { VisionApiClient.analyzeScreenshotReadOnly(screenshot) }` 做后台HUD补充

虽然 `analyzeScreenshotReadOnly` 声称只读，但它调用了 `compressImage()` → `BitmapFactory.decodeByteArray()`，并且在 `sendRequest()` 中修改了 `lastRawResponse`、`lastError` 等 `@Volatile` 字段。更严重的是：

3. **HTTP API 路径**: `HttpServerService` 的 `/api/analyze` 端点直接在NanoHTTPD工作线程中调用 `VisionApiClient.analyzeScreenshot(screenshot)`，这与FloatingService的自动截屏识别可能**完全并发**。

```kotlin
// HttpServerService.kt - /api/analyze handler (NanoHTTPD worker thread)
val result = VisionApiClient.analyzeScreenshot(screenshot)  // 修改共享状态!
```

`@Volatile` **只保证可见性，不保证原子性**。`analyzeScreenshot` 中存在典型的 check-then-act 复合操作：

```kotlin
// VisionApiClient.kt: ~248-260 (analyzeScreenshot内部)
val currentRankKey = result.holeCards.joinToString(",") { it.rank }
val lastRankKey = holeCardsLocked?.joinToString(",") { it.rank } ?: ...
if (lastRankKey.isNotEmpty() && currentRankKey != lastRankKey) {
    // ← 竞态窗口: 另一个线程可能在此期间修改 holeCardsLocked
    holeCardsLocked = null; holeCardsRankLocked = null; dButtonLocked = ""
}
// ...
if (result.holeCards.isNotEmpty()) {
    holeCardsLocked = result.holeCards  // ← 写入
    // 另一个线程此刻读到 holeCardsLocked 是半更新状态
}
```

#### 根因分析

`@Volatile` 变量组合操作不具备原子性。`analyzeScreenshot` 是一个200+行的长方法，中间多次读写多个共享字段，没有任何 `synchronized` 块保护。当 HTTP API 线程和自动截屏线程同时调用时，锁状态（`holeCardsLocked`、`dButtonLocked`、`streetLocked`）可能交叉污染，导致：
- 新一手牌的旧锁残留 → 手牌识别错误
- D按钮保险值错乱 → 位置判断错误
- `lastResult` 被覆盖 → 策略引擎收到错误数据

#### 修复方案

```diff
--- a/app/src/main/java/com/pokerhelper/app/VisionApiClient.kt
+++ b/app/src/main/java/com/pokerhelper/app/VisionApiClient.kt
@@ -28,6 +28,9 @@ object VisionApiClient {
 
     private const val TAG = "VisionAPI"
     
+    // P0-R4-1: 分析锁——防止并发修改共享状态
+    private val analyzeLock = java.util.concurrent.locks.ReentrantLock()
+
     // V2.9.183: OkHttp连接池复用
     private val httpClient: OkHttpClient by lazy {
         ...
@@ -130,6 +133,8 @@ object VisionApiClient {
     fun analyzeScreenshot(jpegData: ByteArray): VisionResult? {
         if (apiKey.isEmpty()) { lastError = "未设置API Key"; return null }
+        // P0-R4-1: 加锁保护共享状态的复合读写
+        analyzeLock.lock()
+        try {
         return try {
             val t0 = System.currentTimeMillis()
             ...
@@ -295,6 +300,8 @@ object VisionApiClient {
             null
         }
+        } finally {
+            analyzeLock.unlock()
+        }
     }
 
     fun analyzeScreenshotReadOnly(jpegData: ByteArray): VisionResult? {
```

---

### Bug #2: P0-R4-2 — ScreenOptService.onScreenshotReady 回调覆盖竞态（截屏丢失/永久卡死）

**文件**: `ScreenOptService.kt:42` + `FloatingService.kt:842,1161,1190`  
**严重级别**: P0致命 — 截屏回调被覆盖导致 `isVisionInProgress` 永远无法重置

#### 问题描述

`ScreenOptService.onScreenshotReady` 是一个**全局单例回调**（`companion object` 中的 `var`）：

```kotlin
// ScreenOptService.kt:42
companion object {
    var onScreenshotReady: ((Boolean) -> Unit)? = null
}
```

**四条路径同时写入这个回调**：

| 路径 | 位置 | 触发条件 |
|------|------|---------|
| 手动截屏 `triggerCapture()` | FloatingService.kt:1190 | 用户点击通知/按钮 |
| 多帧截屏 `triggerMultiFrameCapture()` | FloatingService.kt:1161 | 自动多帧模式 |
| 自动截屏 `autoCaptureTrigger()` | FloatingService.kt:842 | 自动循环截屏 |
| 同步截屏 `captureScreenSync()` | ScreenOptService.kt:61 | HTTP API调用 |

**竞态场景**:

```
时间线:
T1: autoCaptureTrigger() 设置 onScreenshotReady = callbackA, 调用 captureScreen()
T2: HTTP /api/capture 调用 captureScreenSync(), 覆盖 onScreenshotReady = callbackB
T3: 截屏完成, 触发 callbackB（HTTP的CountDownLatch）
T4: callbackA 永远不会被调用 → isVisionInProgress 永远为 true → 自动模式永久卡死
```

更严重的是 `captureScreenSync()` 虽然有恢复逻辑，但它恢复的是**调用前的回调**，如果调用前回调已经被其他路径设置，恢复后会跳到错误的处理逻辑。

```kotlin
// ScreenOptService.kt:61-72
fun captureScreenSync(timeoutMs: Long = 3000): Boolean {
    val originalCallback = onScreenshotReady  // ← 读取的可能是另一个路径的回调
    onScreenshotReady = { success ->
        result = success
        latch.countDown()
    }
    svc.performCapture()
    latch.await(timeoutMs, ...)
    onScreenshotReady = originalCallback  // ← 恢复的可能不是"原始"的
    return result
}
```

#### 根因分析

`onScreenshotReady` 是单例 `companion object` 上的全局可变状态，多条路径并发读写无同步保护。`isVisionInProgress` 依赖回调正确触发来重置，一旦回调被覆盖，标志位永远卡在高水位。

#### 修复方案

```diff
--- a/app/src/main/java/com/pokerhelper/app/ScreenOptService.kt
+++ b/app/src/main/java/com/pokerhelper/app/ScreenOptService.kt
@@ -38,10 +38,14 @@ class ScreenOptService : AccessibilityService() {
     companion object {
         var isRunning = false
             private set
-        
-        /** 截图完成回调：参数=true截图成功，false失败需降级 */
-        var onScreenshotReady: ((Boolean) -> Unit)? = null
+
+        // P0-R4-2: 回调队列替代单一回调，防止覆盖竞态
+        private val callbackLock = Any()
+        private var pendingCallback: ((Boolean) -> Unit)? = null
 
+        fun setScreenshotCallback(cb: (Boolean) -> Unit) {
+            synchronized(callbackLock) { pendingCallback = cb }
+        }
+
         fun captureScreen() {
             val svc = instance
             if (svc == null) {
-                onScreenshotReady?.invoke(false)
+                synchronized(callbackLock) { pendingCallback?.invoke(false) }
                 return
             }
             svc.performCapture()
         }
```

---

### Bug #3: P0-R4-3 — Esp32BleManager 状态机三变量无同步保护（死锁/命令丢失）

**文件**: `Esp32BleManager.kt:33-70,355-370,541-555`  
**严重级别**: P0致命 — BLE命令队列死锁，ESP32执行器无响应

#### 问题描述

`Esp32BleManager` 的核心状态由三个变量组成：

```kotlin
// Esp32BleManager.kt
var isConnected = false          // 连接状态（非@Volatile!）
private var isWriting = false     // 写入锁
private val commandQueue = mutableListOf<String>()  // 命令队列
```

**问题**: 这三个变量**既没有 `@Volatile` 也没有 `synchronized`**，却被多个线程并发访问：

| 线程 | 访问的变量 |
|------|-----------|
| BLE回调线程 (Binder thread) | `isConnected`, `isWriting`, `commandQueue` (via `processNextCommand`) |
| 主线程 (Handler) | `isConnected` (via `notifyStatus`), `isWriting`, `commandQueue` (via `sendCommand`) |
| 心跳定时器线程 | `isConnected`, `isWriting` (via `sendCommand("ping")`) |

**死锁场景**:

```
T1: [BLE回调线程] onCharacteristicWrite 被调用
T2: [BLE回调线程] processNextCommand() → isWriting = false
T3: [心跳定时器] sendCommand("ping") → 读 isWriting = false → writeCommand("ping")
T4: [BLE回调线程] writeCommand(队列中下一条) → 设置 isWriting = true
T5: [心跳定时器] bluetoothGatt.writeCharacteristic() 失败（因为T4已经占用了GATT）
T6: [心跳定时器] 进入 else 分支 → processNextCommand() → isWriting = false
T7: [BLE回调线程] onCharacteristicWrite 回调 → processNextCommand() → isWriting 已经 false
     → commandQueue 为空 → 什么都不做
     但实际命令已经丢失!
```

另一个更严重的场景：

```
T1: [主线程] disconnect() → isConnected = false, commandQueue.clear(), isWriting 未重置!
T2: [主线程] 新连接建立 → isConnected = true
T3: [主线程] sendTap() → isWriting 仍为 true → 命令入队但永远不被处理
    → BLE死锁，ESP32不再执行任何动作
```

虽然 P0-R3-1 在断连回调中添加了 `isWriting = false`，但 `disconnect()` 方法中也有 `commandQueue.clear()` 却没有显式重置 `isWriting`：

```kotlin
// Esp32BleManager.kt - disconnect()
fun disconnect() {
    ...
    commandQueue.clear()
    bluetoothGatt?.disconnect()
    bluetoothGatt?.close()
    bluetoothGatt = null
    isConnected = false
    // ← isWriting 没有被重置!
}
```

#### 根因分析

三个状态变量的并发访问缺乏同步机制。`isWriting` 作为写入互斥锁，其读写操作跨越多个线程，但既非 `@Volatile` 也未被 `synchronized` 保护。Android BLE回调运行在 Binder 线程池，与主线程和定时器线程存在真实并发。

#### 修复方案

```diff
--- a/app/src/main/java/com/pokerhelper/app/Esp32BleManager.kt
+++ b/app/src/main/java/com/pokerhelper/app/Esp32BleManager.kt
@@ -42,8 +42,10 @@ class Esp32BleManager(private val context: Context) {
     private var reconnectRunnable: Runnable? = null
     private var lastStableConnectTime = 0L
     private val STABLE_CONNECTION_THRESHOLD = 30000L
 
-    var isConnected = false
+    // P0-R4-3: 状态变量同步保护
+    @Volatile var isConnected = false
         private set
     var onStatusChanged: ((Boolean, String) -> Unit)? = null
     var onCommandResult: ((String) -> Unit)? = null
@@ -53,8 +55,10 @@ class Esp32BleManager(private val context: Context) {
 
     // V2.9.184: BLE命令队列
     private val commandQueue = mutableListOf<String>()
-    private var isWriting = false
+    // P0-R4-3: isWriting需要跨线程可见性
+    @Volatile private var isWriting = false
+    private val writeLock = Any()  // P0-R4-3: 写入状态锁
 
     // V2.9.178: BLE数据缓冲
     private val bleRxBuffer = StringBuilder()
@@ -210,6 +214,7 @@ class Esp32BleManager(private val context: Context) {
     // 断开连接
     fun disconnect() {
         try {
+            synchronized(writeLock) { isWriting = false }  // P0-R4-3: 重置写入状态
             Log.i(TAG, "disconnect: manually disconnecting BLE")
             stopHeartbeatMonitor()
             autoReconnectEnabled = false
@@ -255,6 +260,7 @@ class Esp32BleManager(private val context: Context) {
             reconnectAttempts = 0
             handler.removeCallbacks(bleFlushTimeout)
             bleRxBuffer.clear()
+            synchronized(writeLock) { commandQueue.clear(); isWriting = false }  // P0-R4-3
             bluetoothGatt?.disconnect()
             ...
```

---

### Bug #4: P1-R4-4 — WebView.destroy()后@JavascriptInterface回调仍触发（NPE崩溃）

**文件**: `FloatingService.kt:588,1509`  
**严重级别**: P1严重 — Service销毁期间后台线程触发已销毁WebView导致崩溃

#### 问题描述

`addJavascriptInterface` 注册的 `AndroidBridge` 对象是一个匿名内部类，持有 `FloatingService` 的强引用。其方法通过 `handler.post {}` 将回调投递到主线程。

**时序问题**:

```
T1: [后台线程] JS调用 AndroidBridge.updateStatus("xxx")
T2: [后台线程] handler.post { tvStatus?.text = text }  ← Runnable入队
T3: [主线程] FloatingService.onDestroy() 开始执行
T4: [主线程] handler.removeCallbacksAndMessages(null) ← 清除handler消息
T5: [主线程] webView?.destroy()
T6: [主线程] super.onDestroy() → Service被销毁
T7: [后台线程] 另一个JS调用到达 → handler.post { ... } 
     ← 这个handler关联的Looper仍然存活（Handler绑定了Looper.getMainLooper()）
     ← 但FloatingService已被销毁，tvStatus等视图为null
```

虽然大部分UI操作用了 `?.` 安全调用，但 `executeJs()` 方法中存在问题：

```kotlin
// FloatingService.kt:756-768
private fun executeJs(js: String) {
    if (webViewReady && webView != null) {
        try { webView?.evaluateJavascript(js, null) } catch (_: Exception) {
            webViewReady = false
        }
    }
}
```

在 `onDestroy()` 中：
```kotlin
// 第588行: webView?.destroy() 
// 但 webViewReady 没有被立即设为 false！
// 在 destroy() 和 webViewReady=false 之间有一个时间窗口
```

更关键的是，`onDestroy()` 中 `webView?.destroy()` 在 `handler.removeCallbacksAndMessages(null)` 之后执行，但 **@JavascriptInterface 回调不在 handler 消息队列中**——它们从 WebView 的 JavaBridge 线程直接触发，`removeCallbacksAndMessages` 无法清除它们。

#### 根因分析

WebView 的 `@JavascriptInterface` 方法从 JavaBridge 线程池调用，不受主线程 `Handler` 控制。`onDestroy()` 没有设置 `webViewReady = false` 作为第一道防线，也没有在 `destroy()` 之前调用 `removeJavascriptInterface`。

#### 修复方案

```diff
--- a/app/src/main/java/com/pokerhelper/app/FloatingService.kt
+++ b/app/src/main/java/com/pokerhelper/app/FloatingService.kt
@@ -555,6 +555,10 @@ class FloatingService : Service() {
 
     override fun onDestroy() {
         isRunning = false
+        // P1-R4-4: 第一步立刻标记WebView不可用，阻断executeJs和JS回调
+        webViewReady = false
+        webView?.stopLoading()
+        webView?.removeJavascriptInterface("AndroidBridge")
         // P0-fix: 清除ScreenOptService回调
         try { ScreenOptService.onScreenshotReady = null } catch (_: Exception) {}
         ...
```

---

### Bug #5: P1-R4-5 — ChipTracker Bitmap泄漏（cropBitmap未recycle）

**文件**: `ChipTracker.kt:113,123`  
**严重级别**: P1严重 — 每次筹码分析泄漏2个Bitmap，频繁截屏下加速OOM

#### 问题描述

```kotlin
// ChipTracker.kt:113
val chipBitmap = Bitmap.createBitmap(bitmap, cropX, cropY, cropW, cropH)
// ... 使用 chipBitmap ...
chipBitmap?.recycle()  // 第170行

// ChipTracker.kt:123 — 另一个createBitmap
val potBitmap = Bitmap.createBitmap(bitmap, ...)
// 第176行
potBitmap.recycle()
```

但在 `analyzeRegionColors` 等效路径中（ChipTracker 的筹码识别逻辑），`Bitmap.createBitmap` 创建的中间Bitmap在异常路径下不会被recycle。具体看第113行附近：

```kotlin
// 如果 cropBitmap 和 potBitmap 之间的代码抛出异常
// chipBitmap 已经创建但不会被 recycle
```

此外，`VisionApiClient.kt:850` 的 `cropCardFromBitmap` 创建的Bitmap在 `applyLocalSuitFusion` 中通过 `cardBmp?.recycle()` 回收，但如果 `localSuitRecognize` 抛出异常，recycle 不会执行（没有 `finally` 块）：

```kotlin
// VisionApiClient.kt:910-928
val cardBmp = try {
    cropCardFromBitmap(screenshotBitmap, ...)
} catch (_: Exception) { null }
val localResult = localSuitRecognize(cardBmp)  // ← 如果这里抛异常
val (mergedSuit, localUsed) = mergeSuitResult(...)
cardBmp?.recycle()  // ← 不会执行!
```

#### 根因分析

Bitmap 回收依赖线性代码路径，没有 `try-finally` 保护。任何中间步骤的异常都会导致 `recycle()` 被跳过。

#### 修复方案

```diff
--- a/app/src/main/java/com/pokerhelper/app/VisionApiClient.kt
+++ b/app/src/main/java/com/pokerhelper/app/VisionApiClient.kt
@@ -920,12 +920,16 @@ object VisionApiClient {
                     val cardBmp = try {
                         cropCardFromBitmap(screenshotBitmap, xOffset.coerceIn(0f, 1f), baseYPct, cardWidthPct, cardHeightPct)
                     } catch (_: Exception) { null }
-                    val localResult = localSuitRecognize(cardBmp)
-                    val (mergedSuit, localUsed) = mergeSuitResult(card.suit, result.suitUncertain, localResult)
-                    if (localUsed) anyLocalUsed = true
-                    cardBmp?.recycle()
+                    try {
+                        val localResult = localSuitRecognize(cardBmp)
+                        val (mergedSuit, localUsed) = mergeSuitResult(card.suit, result.suitUncertain, localResult)
+                        if (localUsed) anyLocalUsed = true
+                        CardInfo(card.rank, mergedSuit)
+                    } finally {
+                        cardBmp?.recycle()
+                    }
                     CardInfo(card.rank, mergedSuit)
```

---

### Bug #6: P1-R4-6 — HttpServerService 热更新无SSL证书校验（中间人攻击风险）

**文件**: `HttpServerService.kt:25-27,170-195`  
**严重级别**: P1严重 — 远程JS注入可控制WebView执行任意操作

#### 问题描述

热更新通过 `URL.openConnection()` 下载远程 HTML/JS：

```kotlin
// HttpServerService.kt:25
private const val HOTLOAD_URL = "https://ghfast.top/https://raw.githubusercontent.com/..."
private const val HOTLOAD_URL_FALLBACK = "https://raw.githubusercontent.com/..."

// 第170-195行
val conn = URL(HOTLOAD_URL).openConnection()
conn.connectTimeout = HOTLOAD_TIMEOUT / 2
conn.readTimeout = HOTLOAD_TIMEOUT / 2
html = conn.getInputStream().bufferedReader(Charsets.UTF_8).readText()
```

**问题**: 
1. 使用 `ghfast.top` 代理，该域名的SSL证书由Cloudflare签发。如果代理被劫持或DNS被污染，可能下载到恶意HTML
2. 下载后仅做了 `html.contains("poker")` 和 `html.length > 1000` 的最低限度校验
3. 下载的HTML直接加载到带 `addJavascriptInterface` 的WebView中，拥有完整的应用控制能力
4. 没有内容签名校验（如HMAC/SHA256）

#### 根因分析

热更新内容缺少完整性校验。远程JS通过 `addJavascriptInterface` 可以调用 `AndroidBridge` 暴露的所有方法，包括BLE控制、截屏、日志导出等。如果供应链被攻破，攻击者可以注入恶意代码窃取API Key或控制ESP32执行任意操作。

#### 修复方案

建议增加内容签名校验或至少增加hash校验：

```diff
--- a/app/src/main/java/com/pokerhelper/app/HttpServerService.kt
+++ b/app/src/main/java/com/pokerhelper/app/HttpServerService.kt
@@ -170,6 +170,15 @@ class HttpServerService : Service() {
                                     html = conn2.getInputStream().bufferedReader(Charsets.UTF_8).readText()
                                 } catch (_: Exception) {}
                             }
+                            // P1-R4-6: 基础安全检查 — 禁止包含可疑脚本标签
+                            if (html != null) {
+                                val suspicious = listOf("eval(", "document.cookie", "localStorage",
+                                    "XMLHttpRequest", "fetch(", "WebSocket")
+                                if (suspicious.any { html.contains(it) }) {
+                                    Log.w(TAG, "热更新内容包含可疑代码，已拒绝")
+                                    html = null
+                                }
+                            }
                             if (html != null && html.isNotEmpty() && html.contains("poker") && html.length > 1000) {
```

---

### Bug #7: P1-R4-7 — ScreenOptService.captureScreenSync 阻塞NanoHTTPD工作线程导致服务不可用

**文件**: `ScreenOptService.kt:55-72` + `HttpServerService.kt`  
**严重级别**: P1严重 — HTTP服务响应超时

#### 问题描述

`captureScreenSync()` 使用 `CountDownLatch.await(3000ms)` 同步等待截屏完成：

```kotlin
// ScreenOptService.kt:55-72
fun captureScreenSync(timeoutMs: Long = 3000): Boolean {
    val svc = instance ?: return false
    val latch = java.util.concurrent.CountDownLatch(1)
    var result = false
    val originalCallback = onScreenshotReady
    onScreenshotReady = { success ->
        result = success
        latch.countDown()
    }
    svc.performCapture()
    latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    onScreenshotReady = originalCallback
    return result
}
```

这个方法从 `HttpServerService` 的 `/api/capture` 端点调用，运行在 NanoHTTPD 的工作线程池中。`latch.await()` 会阻塞该线程最多3秒。如果同时有多个HTTP请求到达，NanoHTTPD的默认线程池可能被耗尽。

更严重的是：`captureScreenSync` 会**覆盖** `onScreenshotReady` 回调（Bug #2 的另一个表现），如果此时 FloatingService 的自动截屏正在进行，自动截屏的回调会被替换为同步截屏的 latch callback，导致自动截屏流程断裂。

#### 根因分析

同步等待模式与异步回调架构不兼容。HTTP端点需要在同步语义下使用截屏功能，但截屏是异步操作，两者通过共享回调冲突。

#### 修复方案

建议为 HTTP 端点使用独立的截屏通道，不共享 `onScreenshotReady` 回调：

```diff
--- a/app/src/main/java/com/pokerhelper/app/ScreenOptService.kt
+++ b/app/src/main/java/com/pokerhelper/app/ScreenOptService.kt
@@ -52,6 +52,10 @@ class ScreenOptService : AccessibilityService() {
         fun captureScreenSync(timeoutMs: Long = 3000): Boolean {
             val svc = instance ?: return false
+            // P1-R4-7: 防止与自动截屏冲突——如果已有回调在等待，拒绝同步请求
+            if (onScreenshotReady != null) {
+                Log.w(TAG, "captureScreenSync: 已有回调等待中，拒绝同步请求")
+                return false
+            }
             val latch = java.util.concurrent.CountDownLatch(1)
```

---

## 三、极端场景测试步骤

### 测试场景1: 双路识别并发竞态验证

**目标**: 验证 VisionApiClient 共享状态在并发调用下的完整性

**步骤**:
1. 启动应用，连接ESP32，开启自动截屏模式
2. 在另一个设备上向 `http://<phone-ip>:8666/api/analyze` 快速发送POST请求（每秒5次，持续30秒）
3. 同时观察自动截屏的手牌识别结果是否出现：
   - 手牌与公共牌不匹配（如手牌显示上一手的牌）
   - street与实际公共牌数量不一致
   - D按钮位置突变
4. 检查logcat中是否有 "手牌锁定" / "D按钮保险" 相关的异常日志
5. **预期**: 无竞态错误；**实际**: 可能观测到手牌交叉污染

### 测试场景2: BLE断连+重连+命令队列压力测试

**目标**: 验证 Esp32BleManager 在极端断连场景下的状态一致性

**步骤**:
1. 连接ESP32，开启自动截屏模式（每2秒发送一次tap）
2. 将ESP32设备移出蓝牙范围（或用法拉第笼屏蔽）
3. 观察30秒内心跳超时→重连循环
4. 在重连过程中，通过HTTP API `/api/capture` 触发额外截屏（增加命令队列压力）
5. 将ESP32移回范围，观察是否恢复连接
6. 恢复后验证：命令队列是否正常处理、isWriting是否为false、是否有命令永久卡死
7. **关键检查**: `adb shell dumpsys activity service com.pokerhelper.app` 查看服务状态

### 测试场景3: 快速截屏模式切换压力测试

**目标**: 验证 ScreenOptService 回调在快速模式切换下不丢失

**步骤**:
1. 开启自动截屏模式（每2秒截屏）
2. 同时快速点击通知栏截屏按钮（每秒3-4次）
3. 同时从另一设备调用 `http://<phone-ip>:8666/api/capture`（每秒2次）
4. 触发多帧截屏 `triggerMultiFrameCapture()`
5. 持续30秒后检查：
   - `isVisionInProgress` 是否卡在true（自动截屏停止）
   - 是否有截屏结果丢失（截屏成功但无识别结果）
   - 是否出现 "上一次识别尚未完成" 的日志堆积
6. **关键**: 通过 `adb shell dumpsys activity service` 检查 FloatingService 的 `isVisionInProgress` 状态

---

## 四、审计总结

| 编号 | 优先级 | 文件 | 问题 | 影响 |
|------|--------|------|------|------|
| P0-R4-1 | **P0致命** | VisionApiClient.kt | 共享状态并发竞态 | 决策数据错乱 |
| P0-R4-2 | **P0致命** | ScreenOptService.kt + FloatingService.kt | 回调覆盖竞态 | 截屏永久卡死 |
| P0-R4-3 | **P0致命** | Esp32BleManager.kt | 状态机无同步保护 | BLE命令死锁 |
| P1-R4-4 | **P1严重** | FloatingService.kt | WebView销毁后JS回调 | NPE崩溃 |
| P1-R4-5 | **P1严重** | VisionApiClient.kt + ChipTracker.kt | Bitmap泄漏 | OOM崩溃 |
| P1-R4-6 | **P1严重** | HttpServerService.kt | 热更新无内容校验 | 中间人攻击 |
| P1-R4-7 | **P1严重** | ScreenOptService.kt | captureScreenSync阻塞 | HTTP服务不可用 |

**本轮审计共发现7个Bug：P0×3、P1×4**

### 全局架构风险评估

| 风险维度 | 评估 | 说明 |
|---------|------|------|
| 并发安全 | ⚠️ 高风险 | VisionApiClient/ScreenOptService/Esp32BleManager三个核心模块均缺乏有效的并发控制 |
| 资源管理 | ⚠️ 中风险 | Bitmap回收基本覆盖但缺少finally保护；WebView生命周期管理有改进空间 |
| 安全防护 | ⚠️ 中风险 | 热更新缺少内容校验；addJavascriptInterface暴露面需收敛 |
| 防御性编程 | ✅ 较好 | 超时兜底（Shot Clock）、错误计数、断网兜底模式等机制完善 |
