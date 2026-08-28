package com.pokerhelper.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ESP32 USB直连通信管理器 (v3.0.0)
 *
 * ESP32-S3通过USB OTG线连手机，表现为单接口USB HID触摸屏。
 * 命令走HID Feature Report（Endpoint 0 control transfer）：
 *   - SET_REPORT(Feature, ID=2) 发送命令文本
 *   - GET_REPORT(Feature, ID=2) 读取ACK响应
 *
 * 不需要root，不需要claimInterface抢占触摸屏。
 * 控制传输走Endpoint 0，与触摸屏输入通道（Interrupt IN）完全正交。
 */
class Esp32UsbManager(private val context: Context) {
    companion object {
        private const val TAG = "Esp32Usb"
        private const val ESP32_VID = 0x303A
        private const val ESP32_PID = 0x8266
        private const val REPORT_ID_COMMAND = 2
        private const val REPORT_SIZE = 64       // 1字节report ID + 63字节数据
        private const val ACK_TIMEOUT_MS = 3000L
        private const val FAST_TIMEOUT_MS = 200L
        private const val POLL_INTERVAL_MS = 5L
        private const val ACTION_USB_PERMISSION = "com.pokerhelper.app.USB_PERMISSION"

        // HID class request constants
        private const val HID_REQ_SET_REPORT = 0x09
        private const val HID_REQ_GET_REPORT = 0x01
        private const val HID_REPORT_TYPE_FEATURE = 0x03
        // wValue = (reportType << 8) | reportId
        private const val WVALUE_SET = (HID_REPORT_TYPE_FEATURE shl 8) or REPORT_ID_COMMAND
        private const val WVALUE_GET = WVALUE_SET
    }

    @Volatile var isConnected = false
        private set

    var onStatusChanged: ((Boolean, String) -> Unit)? = null
    var onCommandResult: ((String) -> Unit)? = null
    var onRssiUpdate: ((Int) -> Unit)? = null  // no-op兼容旧接口

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var connection: UsbDeviceConnection? = null
    private var device: UsbDevice? = null
    private var interfaceIndex = -1
    private val running = AtomicBoolean(false)
    private val receiverRegistered = AtomicBoolean(false)

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    val dev = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    Log.i(TAG, "USB permission: granted=$granted device=${dev?.deviceName}")
                    if (granted && dev != null) {
                        openDevice(dev)
                    } else {
                        notifyStatus(false, "USB权限被拒绝")
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val dev = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    Log.i(TAG, "USB attached: ${dev?.vendorId}:${dev?.productId}")
                    if (dev != null && isOurDevice(dev)) {
                        tryConnect()
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val dev = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (dev != null && dev.deviceId == device?.deviceId) {
                        Log.w(TAG, "ESP32 detached")
                        handleDisconnect("USB已拔出")
                    }
                }
            }
        }
    }

    fun start() {
        if (running.getAndSet(true)) return
        registerReceiver()
        tryConnect()
        Log.i(TAG, "Esp32UsbManager started (VID=$ESP32_VID PID=$ESP32_PID)")
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        unregisterReceiver()
        closeConnection()
    }

    // 兼容旧接口
    fun startHeartbeatMonitor() {}
    fun stopHeartbeatMonitor() {}
    fun startScan() { tryConnect() }
    fun disconnect() { closeConnection(); notifyStatus(false, "已断开") }

    private fun registerReceiver() {
        if (receiverRegistered.getAndSet(true)) return
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(usbReceiver, filter)
            }
        } catch (e: Exception) {
            Log.e(TAG, "registerReceiver failed", e)
        }
    }

    private fun unregisterReceiver() {
        if (!receiverRegistered.getAndSet(false)) return
        try { context.unregisterReceiver(usbReceiver) } catch (_: Exception) {}
    }

    private fun isOurDevice(dev: UsbDevice): Boolean {
        return dev.vendorId == ESP32_VID && dev.productId == ESP32_PID
    }

    private fun tryConnect() {
        if (isConnected) return
        val dev = usbManager.deviceList.values.firstOrNull { isOurDevice(it) }
        if (dev == null) {
            notifyStatus(false, "未检测到ESP32")
            return
        }
        if (!usbManager.hasPermission(dev)) {
            Log.i(TAG, "Requesting USB permission for ${dev.deviceName}")
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            else
                PendingIntent.FLAG_UPDATE_CURRENT
            val intent = PendingIntent.getBroadcast(
                context, 0, Intent(ACTION_USB_PERMISSION).setPackage(context.packageName), flags
            )
            usbManager.requestPermission(dev, intent)
            notifyStatus(false, "等待USB授权...")
            return
        }
        openDevice(dev)
    }

    private fun openDevice(dev: UsbDevice) {
        try {
            // 查找HID接口
            var iface: android.hardware.usb.UsbInterface? = null
            var ifIdx = -1
            for (i in 0 until dev.interfaceCount) {
                val itf = dev.getInterface(i)
                if (itf.interfaceClass == UsbConstants.USB_CLASS_HID) {
                    iface = itf; ifIdx = i; break
                }
            }
            if (iface == null) {
                // 退而求其次：用接口0（ESP32-S3只有一个接口）
                iface = dev.getInterface(0)
                ifIdx = 0
            }

            val conn = usbManager.openDevice(dev)
            if (conn == null) {
                notifyStatus(false, "打开USB失败")
                return
            }

            // claimInterface但不抢占内核驱动（force=false），触摸屏继续工作
            // 我们只用Endpoint 0 control transfer
            val claimed = conn.claimInterface(iface, false)
            Log.i(TAG, "claimInterface($ifIdx, force=false)=$claimed")

            connection = conn
            device = dev
            interfaceIndex = ifIdx
            isConnected = true

            val ver = conn.rawDescriptors?.let { "descriptor_ok" } ?: "no_descriptor"
            Log.i(TAG, "★ ESP32 USB connected: ${dev.deviceName}, iface=$ifIdx, $ver")
            notifyStatus(true, "USB已连接")

            // 发送status命令验证命令通道（在后台线程，避免ANR）
            Thread {
                try { Thread.sleep(200) } catch (_: Exception) {}
                val status = sendCommandWaitAck("status", 2000L)
                if (status != null) {
                    onCommandResult?.invoke(status)
                }
            }.start()

        } catch (e: Exception) {
            Log.e(TAG, "openDevice error", e)
            notifyStatus(false, "USB打开异常: ${e.message}")
        }
    }

    /**
     * 发送tap并同步等待ACK（必须在后台线程调用）
     * @return true=ESP32确认HID点击执行成功
     */
    fun sendTap(x: Int, y: Int, durationMs: Int = 50): Boolean {
        val cmd = "tap:$x,$y,$durationMs"
        val result = sendCommandWaitAck(cmd, ACK_TIMEOUT_MS)
        if (result == null) {
            Log.w(TAG, "sendTap ACK timeout: $cmd")
            return false
        }
        val ok = result.startsWith("ok:")
        if (!ok) Log.w(TAG, "sendTap failed: $result")
        return ok
    }

    /**
     * 快速发送tap（不等ACK），用于数字键盘连续点击
     */
    fun sendTapFast(x: Int, y: Int, durationMs: Int = 50) {
        sendCommandOnly("tap:$x,$y,$durationMs")
    }

    fun sendStatus() {
        Thread { sendCommandWaitAck("status", 1000L)?.let { onCommandResult?.invoke(it) } }.start()
    }
    fun sendPing() {
        Thread { sendCommandWaitAck("ping", 1000L)?.let { onCommandResult?.invoke(it) } }.start()
    }
    fun sendSelftest() {
        Thread { sendCommandWaitAck("selftest", 5000L)?.let { onCommandResult?.invoke(it) } }.start()
    }

    /**
     * 发送命令并等待ACK（核心方法，必须在后台线程调用）
     */
    fun sendCommandWaitAck(cmd: String, timeoutMs: Long): String? {
        if (!sendCommandOnly(cmd)) return null

        val deadline = System.currentTimeMillis() + timeoutMs
        val buf = ByteArray(REPORT_SIZE)
        while (System.currentTimeMillis() < deadline) {
            val r = sendControlTransfer(
                UsbConstants.USB_DIR_IN or UsbConstants.USB_TYPE_CLASS or UsbConstants.USB_RECIP_INTERFACE,
                HID_REQ_GET_REPORT,
                WVALUE_GET,
                interfaceIndex,
                buf,
                REPORT_SIZE,
                100
            )
            if (r >= 2) {
                val status = buf[1].toInt().toChar()
                if (status == 'O' || status == 'E') {
                    val end = minOf(r, REPORT_SIZE - 1)
                    // 从buf[2]开始是响应文本
                    var len = 2
                    while (len < end && buf[len].toInt() != 0) len++
                    val resp = String(buf, 2, len - 2)
                    Log.d(TAG, "ACK($status): $resp")
                    return if (status == 'E') "err:$resp" else resp
                }
            }
            try { Thread.sleep(POLL_INTERVAL_MS) } catch (_: InterruptedException) { return null }
        }
        return null
    }

    /**
     * 只发送命令不等ACK（快速连续点击用）
     */
    fun sendCommandOnly(cmd: String): Boolean {
        val conn = connection ?: return false
        if (!isConnected) return false

        val report = ByteArray(REPORT_SIZE)
        report[0] = REPORT_ID_COMMAND.toByte()
        val bytes = cmd.toByteArray()
        val copyLen = minOf(bytes.size, REPORT_SIZE - 1)
        System.arraycopy(bytes, 0, report, 1, copyLen)

        val r = sendControlTransfer(
            UsbConstants.USB_TYPE_CLASS or UsbConstants.USB_RECIP_INTERFACE,
            HID_REQ_SET_REPORT,
            WVALUE_SET,
            interfaceIndex,
            report,
            REPORT_SIZE,
            500
        )
        if (r < 0) {
            Log.w(TAG, "SET_REPORT failed ($r) for cmd=$cmd")
            handleDisconnect("USB写失败")
            return false
        }
        Log.d(TAG, "TX: $cmd (sent=$r)")
        return true
    }

    private fun sendControlTransfer(
        requestType: Int, request: Int, value: Int, index: Int,
        buffer: ByteArray, length: Int, timeout: Int
    ): Int {
        val conn = connection ?: return -1
        return try {
            conn.controlTransfer(requestType, request, value, index, buffer, length, timeout)
        } catch (e: Exception) {
            Log.w(TAG, "controlTransfer error: ${e.message}")
            -1
        }
    }

    private fun closeConnection() {
        try { connection?.releaseInterface(device?.getInterface(interfaceIndex)) } catch (_: Exception) {}
        try { connection?.close() } catch (_: Exception) {}
        connection = null
        device = null
        isConnected = false
    }

    private fun handleDisconnect(reason: String) {
        if (!isConnected && connection == null) return
        Log.w(TAG, "Disconnect: $reason")
        closeConnection()
        notifyStatus(false, reason)
    }

    private fun notifyStatus(connected: Boolean, message: String) {
        try { onStatusChanged?.invoke(connected, message) }
        catch (e: Exception) { Log.e(TAG, "notifyStatus error", e) }
    }
}
