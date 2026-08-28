package com.pokerhelper.app

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * ESP32 WiFi TCP通信管理器 (v2.0.0)
 *
 * 手机做TCP Server监听8888端口，ESP32做TCP Client连上来。
 * ESP32通过手机热点连接：192.168.43.1:8888
 *
 * 替代旧的Esp32BleManager，接口保持兼容。
 * - TCP协议层保证命令到达（重传+排序）
 * - 无GATT脏状态，断线由ESP32主动重连
 * - sendTap同步等ACK，确认HID点击真的执行了
 */
class Esp32TcpManager {
    companion object {
        private const val TAG = "Esp32Tcp"
        private const val TCP_PORT = 8888
        private const val ACK_TIMEOUT_MS = 2000L
    }

    var onStatusChanged: ((Boolean, String) -> Unit)? = null
    var onCommandResult: ((String) -> Unit)? = null
    var onRssiUpdate: ((Int) -> Unit)? = null

    @Volatile
    var isConnected = false
        private set

    var lastRssi: Int = 0
        private set

    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var writer: PrintWriter? = null
    private var reader: BufferedReader? = null
    private val running = AtomicBoolean(false)
    private var acceptThread: Thread? = null

    // ACK同步等待
    private val ackLock = Object()
    @Volatile private var pendingAckCommand: String? = null
    @Volatile private var ackResult: String? = null

    /**
     * 启动TCP Server
     */
    fun start() {
        if (running.getAndSet(true)) {
            Log.w(TAG, "start: already running")
            return
        }

        acceptThread = thread(start = true, name = "tcp-server") {
            try {
                Log.i(TAG, "Starting TCP server on port $TCP_PORT")
                notifyStatus(false, "等待ESP32连接...")

                ServerSocket(TCP_PORT).apply {
                    reuseAddress = true
                    serverSocket = this
                }

                while (running.get()) {
                    try {
                        Log.d(TAG, "Waiting for ESP32 on port $TCP_PORT...")
                        val socket = serverSocket!!.accept()
                        socket.tcpNoDelay = true

                        closeClient()

                        clientSocket = socket
                        writer = PrintWriter(socket.getOutputStream(), true)
                        reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                        isConnected = true

                        val addr = socket.inetAddress?.hostAddress ?: "unknown"
                        Log.i(TAG, "★ ESP32 connected from $addr")
                        notifyStatus(true, "已连接 $addr")

                        // 启动读取循环（在当前线程阻塞读取）
                        readLoop()

                    } catch (e: Exception) {
                        if (running.get()) {
                            Log.w(TAG, "Client handling error: ${e.message}")
                            handleDisconnect()
                            try { Thread.sleep(1000) } catch (_: Exception) {}
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server error", e)
                notifyStatus(false, "服务启动失败")
            }
        }
    }

    private fun readLoop() {
        val r = reader ?: return
        try {
            while (running.get() && isConnected) {
                val line = try {
                    r.readLine()
                } catch (e: Exception) {
                    Log.w(TAG, "Read error: ${e.message}")
                    null
                } ?: break

                if (line.isBlank()) continue
                Log.d(TAG, "RX: ${line.take(200)}")
                handleMessage(line.trim())
            }
        } catch (e: Exception) {
            Log.w(TAG, "readLoop error: ${e.message}")
        }
        if (running.get()) handleDisconnect()
    }

    private fun handleMessage(msg: String) {
        // RSSI解析（hb和status中都可能有）
        val rssiMatch = Regex("""rssi[=:]\s*(-?\d+)""").find(msg)
        if (rssiMatch != null) {
            try {
                lastRssi = rssiMatch.groupValues[1].toInt()
                onRssiUpdate?.invoke(lastRssi)
            } catch (_: Exception) {}
        }

        // 检查是否是等待中的ACK
        val pending = pendingAckCommand
        if (pending != null) {
            val isTapAck = pending.startsWith("tap:") &&
                           (msg.startsWith("ok:tap") || msg.startsWith("err:"))
            if (isTapAck) {
                synchronized(ackLock) {
                    ackResult = msg
                    pendingAckCommand = null
                    ackLock.notifyAll()
                }
                return
            }
        }

        // 非ACK消息，通过回调分发
        onCommandResult?.invoke(msg)
    }

    /**
     * 发送tap并同步等待ACK
     * @return true=ESP32确认HID点击成功
     */
    fun sendTap(x: Int, y: Int, durationMs: Int = 50): Boolean {
        val cmd = "tap:$x,$y,$durationMs"
        if (!isConnected || writer == null) {
            Log.w(TAG, "sendTap: not connected")
            onCommandResult?.invoke("err:not_connected")
            return false
        }

        synchronized(ackLock) {
            pendingAckCommand = cmd
            ackResult = null
        }

        try {
            val w = writer ?: return false
            w.println(cmd)
            if (w.checkError()) {
                Log.w(TAG, "sendTap: write error")
                handleDisconnect()
                return false
            }
            Log.d(TAG, "TX: $cmd, waiting ACK...")
        } catch (e: Exception) {
            Log.e(TAG, "sendTap error", e)
            return false
        }

        // 等待ACK
        val deadline = System.currentTimeMillis() + ACK_TIMEOUT_MS
        synchronized(ackLock) {
            while (ackResult == null && System.currentTimeMillis() < deadline) {
                try {
                    ackLock.wait(100)
                } catch (_: InterruptedException) {
                    return false
                }
            }
        }

        val result = ackResult
        pendingAckCommand = null
        if (result == null) {
            Log.w(TAG, "sendTap ACK timeout (${ACK_TIMEOUT_MS}ms)")
            return false
        }
        val success = result.startsWith("ok:")
        Log.d(TAG, "sendTap ACK: $result, success=$success")
        return success
    }

    fun sendStatus() = sendCommand("status")
    fun sendPing() = sendCommand("ping")
    fun sendSelftest() = sendCommand("selftest")

    /**
     * 快速发送tap（不等ACK），用于连续点击场景（如数字键盘输入）
     * 主流程的tap请用sendTap()同步等ACK
     */
    fun sendTapFast(x: Int, y: Int, durationMs: Int = 50) {
        sendCommand("tap:$x,$y,$durationMs")
    }

    fun sendCommand(cmd: String) {
        try {
            val w = writer
            if (!isConnected || w == null) {
                onCommandResult?.invoke("err:not_connected")
                return
            }
            Log.d(TAG, "TX: $cmd")
            w.println(cmd)
            if (w.checkError()) handleDisconnect()
        } catch (e: Exception) {
            Log.e(TAG, "sendCommand error", e)
        }
    }

    fun startHeartbeatMonitor() {
        // WiFi TCP不需要应用层心跳，ESP32每5秒发hb:
        // TCP keepalive由协议栈处理
        Log.d(TAG, "startHeartbeatMonitor: no-op (TCP handles this)")
    }

    fun stopHeartbeatMonitor() {
        Log.d(TAG, "stopHeartbeatMonitor: no-op")
    }

    fun startScan() {
        // 兼容旧接口：TCP Server已在运行，等ESP32连上来即可
        Log.d(TAG, "startScan: no-op (TCP server auto-accepts)")
    }

    fun disconnect() {
        Log.i(TAG, "disconnect: stopping")
        running.set(false)
        closeClient()
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        acceptThread?.interrupt()
        isConnected = false
        notifyStatus(false, "已断开")
    }

    private fun closeClient() {
        try { reader?.close() } catch (_: Exception) {}
        try { writer?.close() } catch (_: Exception) {}
        try { clientSocket?.close() } catch (_: Exception) {}
        reader = null
        writer = null
        clientSocket = null
    }

    private fun handleDisconnect() {
        val wasConnected = isConnected
        isConnected = false
        closeClient()
        // 唤醒可能在等ACK的线程
        synchronized(ackLock) {
            if (pendingAckCommand != null) {
                ackResult = "err:disconnected"
                pendingAckCommand = null
                ackLock.notifyAll()
            }
        }
        if (wasConnected) {
            Log.w(TAG, "Disconnected, waiting for ESP32 to reconnect...")
            notifyStatus(false, "已断开，等待重连...")
        }
    }

    private fun notifyStatus(connected: Boolean, message: String) {
        try { onStatusChanged?.invoke(connected, message) }
        catch (e: Exception) { Log.e(TAG, "notifyStatus error", e) }
    }
}
