package com.pokerhelper.app

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * ESP32 经典蓝牙SPP客户端管理器
 * v2.0.0: 从BLE GATT改为经典蓝牙SPP (Serial Port Profile)
 *
 * 改造原因：BLE GATT在Android上"配对后不自动连接"，导致全链路停摆。
 * 经典蓝牙SPP配对后由系统自动维持连接，稳定性远高于BLE。
 *
 * 通信协议完全不变：tap:x,y,ms / status / log / ping / diag / selftest / version
 * 公开接口完全不变：FloatingService无需任何修改
 *
 * 核心实现：
 *   - 从已配对设备列表查找 "QingYun-ESP32"
 *   - BluetoothSocket RFCOMM 连接（SPP UUID: 00001101-...）
 *   - 后台线程循环读取 InputStream，按 \n 分割处理命令回复
 *   - OutputStream 同步写入命令
 */
class Esp32BleManager(private val context: Context) {

    companion object {
        private const val TAG = "Esp32SppManager"

        // 标准SPP UUID（ESP32 BluetoothSerial库使用此UUID）
        private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        // ESP32设备名（与固件端BT_DEVICE_NAME一致）
        private const val DEVICE_NAME = "QingYun-ESP32"

        // 自动重连配置
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val RECONNECT_DELAY_BASE = 2000L  // 基础延迟2秒，递增
    }

    private val handler = Handler(Looper.getMainLooper())
    private var bluetoothAdapter: BluetoothAdapter? = null

    // SPP连接相关
    private var socket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    // 工作线程
    @Volatile private var connectThread: Thread? = null
    @Volatile private var readThread: Thread? = null

    // 连接状态与自动重连
    private var targetDevice: BluetoothDevice? = null
    private var autoReconnectEnabled = true
    private var reconnectAttempts = 0
    private var reconnectRunnable: Runnable? = null

    // 数据读取缓冲（拼接TCP流碎片，按\n分割）
    private val readBuffer = StringBuilder()
    private val bufferLock = Any()

    // === 公开属性（接口不变） ===

    @Volatile
    var isConnected = false
        private set

    var onStatusChanged: ((Boolean, String) -> Unit)? = null
    var onCommandResult: ((String) -> Unit)? = null

    // RSSI：经典蓝牙SPP不支持实时RSSI读取，保留接口兼容
    var lastRssi: Int = 0
        private set
    var onRssiUpdate: ((Int) -> Unit)? = null

    // 心跳监控（接口不变）
    var onHeartbeat: ((connected: Boolean, heartbeatData: String) -> Unit)? = null
    private var heartbeatHandler: Handler? = null
    private var heartbeatRunnable: Runnable? = null
    private var missedHeartbeats = 0

    // 连接诊断
    private var connectStartTime = 0L
    @Volatile var lastDisconnectReason = ""

    // 运行时权限检查
    private fun hasPermission(perm: String): Boolean {
        return ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
    }

    // ========================================================================
    // 公开方法（接口与BLE版本完全一致）
    // ========================================================================

    /**
     * 开始连接 - 从已配对设备中查找ESP32并通过SPP连接
     * （方法名保持startScan以兼容FloatingService调用）
     */
    fun startScan() {
        Log.i(TAG, "startScan: 查找已配对的SPP设备 $DEVICE_NAME")
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter

        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
            Log.w(TAG, "蓝牙未开启或不可用")
            notifyStatus(false, "蓝牙未开启")
            return
        }

        // BLUETOOTH_CONNECT权限检查（Android 12+）
        if (!hasPermission(android.Manifest.permission.BLUETOOTH_CONNECT)) {
            Log.e(TAG, "BLUETOOTH_CONNECT权限未授予")
            notifyStatus(false, "蓝牙连接权限未授予，请在App权限设置中允许")
            return
        }

        // 从已配对设备列表中查找ESP32
        try {
            val bondedDevices = bluetoothAdapter!!.bondedDevices
            for (device in bondedDevices) {
                val name = try { device.name } catch (e: SecurityException) { null }
                if (name == DEVICE_NAME) {
                    Log.i(TAG, "找到已配对的ESP32: addr=${device.address}")
                    targetDevice = device
                    connectToDevice(device)
                    return
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "getBondedDevices SecurityException", e)
            notifyStatus(false, "蓝牙权限异常")
            return
        } catch (e: Exception) {
            Log.e(TAG, "getBondedDevices error", e)
        }

        // 未找到已配对设备
        Log.w(TAG, "未找到已配对的ESP32设备 '$DEVICE_NAME'，请先在手机蓝牙设置中配对")
        notifyStatus(false, "未找到已配对的ESP32，请先在蓝牙设置中配对")
    }

    /**
     * 停止扫描（SPP模式下无需扫描，保持接口兼容）
     */
    fun stopScan() {
        // SPP无需扫描，no-op
    }

    /**
     * 断开连接并清理资源
     */
    fun disconnect() {
        Log.i(TAG, "disconnect: 手动断开SPP连接")
        autoReconnectEnabled = false
        reconnectRunnable?.let { handler.removeCallbacks(it) }
        reconnectRunnable = null
        reconnectAttempts = 0

        stopHeartbeatMonitor()

        isConnected = false

        // 关闭流和socket
        closeSocketQuietly()

        // 中断工作线程
        try { connectThread?.interrupt() } catch (_: Exception) {}
        try { readThread?.interrupt() } catch (_: Exception) {}
        connectThread = null
        readThread = null

        synchronized(bufferLock) { readBuffer.clear() }

        notifyStatus(false, "已断开")
        Log.i(TAG, "disconnect: 资源已清理")
    }

    /**
     * 发送tap指令
     */
    fun sendTap(x: Int, y: Int, duration: Int = 50) {
        if (!isConnected) {
            Log.w(TAG, "sendTap: SPP未连接")
            onCommandResult?.invoke("err:not_connected")
            return
        }
        writeCommand("tap:$x,$y,$duration")
    }

    /**
     * 发送status查询
     */
    fun sendStatus() {
        if (!isConnected) {
            Log.w(TAG, "sendStatus: SPP未连接")
            onCommandResult?.invoke("err:not_connected")
            return
        }
        writeCommand("status")
    }

    /**
     * 发送ping
     */
    fun sendPing() {
        if (!isConnected) {
            Log.w(TAG, "sendPing: SPP未连接")
            onCommandResult?.invoke("err:not_connected")
            return
        }
        writeCommand("ping")
    }

    /**
     * 启动心跳监控
     * 每隔intervalMs发送ping，连续3次无回复触发重连
     */
    fun startHeartbeatMonitor(intervalMs: Long = 10000) {
        try {
            stopHeartbeatMonitor()
            heartbeatHandler = Handler(Looper.getMainLooper())
            missedHeartbeats = 0
            heartbeatRunnable = object : Runnable {
                override fun run() {
                    try {
                        if (isConnected) {
                            Log.d(TAG, "心跳tick: 发送ping, missed=$missedHeartbeats")
                            writeCommand("ping")
                            missedHeartbeats++
                            if (missedHeartbeats >= 3) {
                                Log.w(TAG, "SPP心跳超时(${missedHeartbeats}次)，触发重连")
                                onHeartbeat?.invoke(false, "timeout:${missedHeartbeats}")
                                try {
                                    closeSocketQuietly()
                                    isConnected = false
                                } catch (e: Exception) { Log.w(TAG, "heartbeat disconnect error", e) }
                                try { startScan() } catch (e: Exception) { Log.w(TAG, "heartbeat startScan error", e) }
                                return
                            } else if (missedHeartbeats >= 1) {
                                Log.d(TAG, "心跳丢失: count=$missedHeartbeats")
                                onHeartbeat?.invoke(false, "missed:${missedHeartbeats}")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "heartbeat runnable error", e)
                    }
                    heartbeatHandler?.postDelayed(this, intervalMs)
                }
            }
            heartbeatHandler?.postDelayed(heartbeatRunnable!!, intervalMs)
            Log.i(TAG, "心跳监控已启动 (interval=${intervalMs}ms)")
        } catch (e: Exception) {
            Log.e(TAG, "startHeartbeatMonitor error", e)
        }
    }

    /**
     * 停止心跳监控
     */
    fun stopHeartbeatMonitor() {
        try {
            heartbeatHandler?.removeCallbacksAndMessages(null)
            heartbeatHandler = null
            heartbeatRunnable = null
            missedHeartbeats = 0
        } catch (e: Exception) {
            Log.w(TAG, "stopHeartbeatMonitor error", e)
        }
    }

    // ========================================================================
    // 内部实现
    // ========================================================================

    /**
     * 通过RFCOMM Socket连接ESP32
     */
    private fun connectToDevice(device: BluetoothDevice) {
        val deviceAddr = try { device.address } catch (e: Exception) { "unknown" }
        Log.i(TAG, "connectToDevice: addr=$deviceAddr")
        notifyStatus(false, "连接${DEVICE_NAME}...")

        // 关闭之前的连接（如果有残留）
        closeSocketQuietly()

        connectThread = Thread({
            try {
                // 优先尝试 secure RFCOMM socket
                var connected = false
                try {
                    Log.d(TAG, "尝试 secure RFCOMM 连接...")
                    val secureSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                    socket = secureSocket
                    // 取消蓝牙发现以加速连接
                    try { bluetoothAdapter?.cancelDiscovery() } catch (_: Exception) {}
                    secureSocket.connect()
                    connected = true
                    Log.i(TAG, "Secure RFCOMM 连接成功")
                } catch (e: Exception) {
                    Log.w(TAG, "Secure RFCOMM 连接失败: ${e.message}，尝试 insecure...")
                    closeSocketQuietly()

                    // Fallback: insecure RFCOMM socket
                    try {
                        val insecureSocket = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
                        socket = insecureSocket
                        try { bluetoothAdapter?.cancelDiscovery() } catch (_: Exception) {}
                        insecureSocket.connect()
                        connected = true
                        Log.i(TAG, "Insecure RFCOMM 连接成功")
                    } catch (e2: Exception) {
                        Log.e(TAG, "Insecure RFCOMM 也失败: ${e2.message}")
                        throw e2
                    }
                }

                if (!connected) throw Exception("连接失败")

                // 获取输入输出流
                val sock = socket ?: throw Exception("socket is null after connect")
                inputStream = sock.inputStream
                outputStream = sock.outputStream

                connectStartTime = System.currentTimeMillis()
                isConnected = true
                reconnectAttempts = 0
                lastDisconnectReason = ""
                Log.i(TAG, "SPP连接成功! inputStream/outputStream 已就绪")
                notifyStatus(true, "已连接")

                // 启动数据读取线程
                startReadThread()

            } catch (e: Exception) {
                Log.e(TAG, "SPP连接失败: ${e.message}", e)
                isConnected = false
                lastDisconnectReason = e.message ?: "unknown"
                closeSocketQuietly()
                notifyStatus(false, "连接失败: ${e.message ?: "未知错误"}")
                // 自动重连
                scheduleReconnect()
            }
        }, "SPP-Connect").also { it.start() }
    }

    /**
     * 后台读取线程 - 循环读取ESP32的SPP回复
     */
    private fun startReadThread() {
        readThread = Thread({
            val buf = ByteArray(1024)
            Log.i(TAG, "读取线程已启动")

            try {
                while (isConnected) {
                    val ins = inputStream ?: break
                    val bytes = try {
                        ins.read(buf)
                    } catch (e: java.io.IOException) {
                        Log.w(TAG, "读取IO异常: ${e.message}")
                        break
                    }

                    if (bytes <= 0) {
                        Log.w(TAG, "读取返回 $bytes，连接可能已断开")
                        break
                    }

                    val chunk = String(buf, 0, bytes, Charsets.UTF_8)
                    Log.d(TAG, "SPP rx (${bytes}B): ${chunk.take(200)}")

                    // 拼接到缓冲区，按\n分割处理完整行
                    synchronized(bufferLock) {
                        readBuffer.append(chunk)

                        while (true) {
                            val nlIdx = readBuffer.indexOf('\n')
                            if (nlIdx < 0) break
                            val line = readBuffer.substring(0, nlIdx).trim()
                            readBuffer.delete(0, nlIdx + 1)
                            if (line.isNotEmpty()) {
                                Log.d(TAG, "SPP 完整命令: $line")
                                handleResponse(line)
                            }
                        }
                    }
                }
            } catch (e: InterruptedException) {
                Log.i(TAG, "读取线程被中断")
            } catch (e: Exception) {
                Log.e(TAG, "读取线程异常", e)
            }

            Log.i(TAG, "读取线程退出")

            // 连接断开处理
            if (isConnected) {
                isConnected = false
                lastDisconnectReason = "read_thread_eof"
                closeSocketQuietly()
                handler.post {
                    stopHeartbeatMonitor()
                    notifyStatus(false, "连接已断开")
                }
                scheduleReconnect()
            }
        }, "SPP-Read").also { it.start() }
    }

    /**
     * 处理ESP32的回复
     */
    private fun handleResponse(msg: String) {
        handler.post {
            // 心跳pong处理
            if (msg.startsWith("pong")) {
                missedHeartbeats = 0
                onHeartbeat?.invoke(true, msg)
                Log.d(TAG, "心跳pong已收到, missed重置为0")
            }
            onCommandResult?.invoke(msg)
        }
    }

    /**
     * 发送命令到ESP32（SPP同步写入）
     */
    @Synchronized
    private fun writeCommand(cmd: String) {
        try {
            val os = outputStream
            if (os == null) {
                Log.w(TAG, "writeCommand: outputStream为null")
                handler.post { onCommandResult?.invoke("err:not_connected") }
                return
            }
            val data = (cmd + "\n").toByteArray(Charsets.UTF_8)
            os.write(data)
            os.flush()
            Log.d(TAG, "SPP TX: $cmd")
        } catch (e: Exception) {
            Log.e(TAG, "发送命令失败: ${e.message}", e)
            handler.post { onCommandResult?.invoke("err:write_failed") }
        }
    }

    /**
     * 自动重连调度
     */
    private fun scheduleReconnect() {
        try {
            if (!autoReconnectEnabled) {
                Log.d(TAG, "自动重连已禁用，跳过")
                return
            }
            if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
                Log.w(TAG, "重连已达上限($MAX_RECONNECT_ATTEMPTS)，停止重连")
                handler.post { notifyStatus(false, "重连失败(已达上限)") }
                return
            }
            val device = targetDevice
            if (device == null) {
                Log.w(TAG, "无保存的设备信息，无法重连")
                return
            }
            reconnectAttempts++
            val delay = RECONNECT_DELAY_BASE * reconnectAttempts
            Log.i(TAG, "SPP自动重连: 第${reconnectAttempts}/${MAX_RECONNECT_ATTEMPTS}次, ${delay}ms后")
            handler.post { notifyStatus(false, "重连中(${reconnectAttempts}/${MAX_RECONNECT_ATTEMPTS})...") }

            reconnectRunnable?.let { handler.removeCallbacks(it) }
            reconnectRunnable = Runnable {
                try {
                    connectToDevice(device)
                } catch (e: Exception) {
                    Log.e(TAG, "重连异常", e)
                    scheduleReconnect()
                }
            }
            handler.postDelayed(reconnectRunnable!!, delay)
        } catch (e: Exception) {
            Log.e(TAG, "scheduleReconnect error", e)
        }
    }

    /**
     * 安静地关闭socket和流
     */
    private fun closeSocketQuietly() {
        try { inputStream?.close() } catch (_: Exception) {}
        try { outputStream?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        inputStream = null
        outputStream = null
        socket = null
    }

    /**
     * 通知状态变化（切回主线程）
     */
    private fun notifyStatus(connected: Boolean, message: String) {
        handler.post {
            isConnected = connected
            onStatusChanged?.invoke(connected, message)
        }
    }
}
