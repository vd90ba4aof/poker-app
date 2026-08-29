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
 * ESP32 USB直连通信管理器 (v3.1.0)
 *
 * ESP32-S3通过USB OTG线连手机，枚举为双接口复合设备：
 *   - 接口0：HID触摸屏（class=3），系统 usbhid 自动绑定（屏幕有鼠标光标），
 *     App 不 claim、不使用
 *   - 接口1：Vendor专用接口（class=0xFF），App 命令通道走 Endpoint 0
 *     VENDOR 类型控制传输：
 *       OUT bmRequestType=0x41 bRequest=0x01 wIndex=vendor接口 → 发命令文本
 *       IN  bmRequestType=0xC1 bRequest=0x02 wIndex=vendor接口 → 读ACK
 *     响应载荷：[0]=状态字节('O'/'E'/'P'/'I')，[1:]=响应文本
 *
 * 为什么不用HID Feature Report（v3.0.0~v3.0.2已证伪）：
 *   SET_REPORT/GET_REPORT 是 class类型+接口定向，Linux内核 devio.c
 *   check_ctrlrecip 强制 checkintf——接口必须被本进程claim；class=3 的HID
 *   接口被内核 usbhid 占用，无root抢不到（-EBUSY），controlTransfer 直接-1。
 *   vendor类型请求在 devio.c 中明文豁免（不查claim/驱动/配置态），免root。
 */
class Esp32UsbManager(private val context: Context) {
    companion object {
        private const val TAG = "Esp32Usb"
        private const val ESP32_VID = 0x303A
        private const val ESP32_PID = 0x8266
        private const val REPORT_SIZE = 64       // vendor EP0控制传输载荷
        private const val MIN_FW_BCD = 0x0310   // v3.1.0起才有vendor命令通道
        private const val ACK_TIMEOUT_MS = 3000L
        private const val FAST_TIMEOUT_MS = 200L
        private const val POLL_INTERVAL_MS = 5L
        private const val ACTION_USB_PERMISSION = "com.pokerhelper.app.USB_PERMISSION"

        // v3.1.0 Vendor控制传输常量
        private const val VENDOR_REQ_WRITE_CMD = 0x01
        private const val VENDOR_REQ_READ_ACK = 0x02
        // bmRequestType: VENDOR(0x40) | INTERFACE(0x01) | IN/OUT(0x80)
        private const val BREQ_SET = 0x41  // OUT | VENDOR | INTERFACE
        private const val BREQ_GET = 0xC1  // IN  | VENDOR | INTERFACE
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
    fun startScan() {
        if (isConnected) return
        tryConnect()
        // 枚举不到时tryConnect已notifyStatus带总线信息
    }
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

    /**
     * V2.9.549: 枚举USB总线上所有设备，用于诊断ESP32是否枚举/枚举成什么
     * 返回形如 "303a:8266(iface=1,cls=3)" 或 "无USB设备"
     */
    fun enumerateBus(): String {
        val list = usbManager.deviceList.values
        if (list.isEmpty()) return "总线上无USB设备"
        return list.joinToString(" | ") { d ->
            val cls = if (d.interfaceCount > 0) d.getInterface(0).interfaceClass else -1
            "%04x:%04x(iface=%d,cls=%d,name=%s)".format(
                d.vendorId, d.productId, d.interfaceCount, cls,
                d.deviceName.substringAfterLast('/'))
        }
    }

    private fun tryConnect() {
        if (isConnected) return
        val dev = usbManager.deviceList.values.firstOrNull { isOurDevice(it) }
        if (dev == null) {
            // V2.9.549: 把总线上所有USB设备列出来，区分：
            //  303a:1001 = ESP32卡在下载模式(bootloader)，需重新刷固件/复位
            //  其他VID:PID = 固件枚举异常
            //  无设备 = USB线/OTG/供电问题
            val bus = enumerateBus()
            Log.w(TAG, "ESP32 not found. USB bus: $bus")
            notifyStatus(false, "未检测到ESP32 [$bus]")
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
            // 查找Vendor专用接口（v3.1.0固件：if1, class=0xFF）。
            // vendor接口无内核驱动绑定，claimInterface必成功（不需要root）。
            var iface: android.hardware.usb.UsbInterface? = null
            var ifIdx = -1
            for (i in 0 until dev.interfaceCount) {
                val itf = dev.getInterface(i)
                if (itf.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC) {
                    iface = itf; ifIdx = i; break
                }
            }
            if (iface == null) {
                // 旧固件兼容回退：HID接口（v3.0.x单接口设备；vendor通道不可用）
                for (i in 0 until dev.interfaceCount) {
                    val itf = dev.getInterface(i)
                    if (itf.interfaceClass == UsbConstants.USB_CLASS_HID) {
                        iface = itf; ifIdx = i; break
                    }
                }
            }
            if (iface == null) {
                iface = dev.getInterface(0)
                ifIdx = 0
            }

            val conn = usbManager.openDevice(dev)
            if (conn == null) {
                notifyStatus(false, "打开USB失败")
                return
            }

            // 读设备描述符里的bcdDevice字段（固件版本）：v3.1.0=0x0310
            val bcd = readFirmwareBcd(conn)
            val fwVer = String.format("%x.%02x", (bcd shr 8) and 0xFF, bcd and 0xFF)
            val isV310 = bcd >= MIN_FW_BCD
            Log.i(TAG, "★ ESP32 bcdDevice=0x${String.format("%04x", bcd)} (firmware v$fwVer)")

            // claim vendor接口（force=false即可：该接口无内核驱动占用）。
            // vendor类型控制传输内核本就不查claim，claim主要为合法占用接口
            val claimed = conn.claimInterface(iface, false)
            Log.i(TAG, "claimInterface($ifIdx,cls=${iface.interfaceClass},force=false)=$claimed")

            connection = conn
            device = dev
            interfaceIndex = ifIdx
            isConnected = true

            val ifaceInfo = "iface=$ifIdx(cls=${iface.interfaceClass},eps=${iface.endpointCount}),claim=$claimed"
            Log.i(TAG, "★ ESP32 USB connected: ${dev.deviceName}, $ifaceInfo, fw=v$fwVer")

            // 固件版本决定vendor命令通道是否可用：v3.1.0(bcd>=0x0310)才有
            if (!isV310) {
                notifyStatus(true, "USB已连接，但固件是v$fwVer，需刷v3.1.0固件")
                onCommandResult?.invoke("err:old_firmware(v$fwVer),need_v3.1.0")
                return
            }
            notifyStatus(true, "USB已连接(fw v$fwVer,claim=$claimed)")

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
        var diagCount = 0
        var stallCount = 0
        var shortCount = 0
        while (System.currentTimeMillis() < deadline) {
            // vendor IN控制传输：0xC1, bRequest=0x02, wIndex=vendor接口号, wValue=0
            val r = sendControlTransfer(
                BREQ_GET,
                VENDOR_REQ_READ_ACK,
                0,
                interfaceIndex,
                buf,
                REPORT_SIZE,
                100
            )
            // 前3次轮询记录原始返回，用于区分 STALL(-1)/短包(0)/正常(>=1)
            if (diagCount < 3) {
                val hex = if (r >= 1) (0 until minOf(r, 8)).joinToString(" ") {
                    "%02x".format(buf[it].toInt() and 0xFF)
                } else ""
                Log.i(TAG, "VENDOR_READ poll#$diagCount r=$r head=[$hex]")
                diagCount++
            }
            if (r < 0) stallCount++ else if (r < 1) shortCount++
            if (r >= 1) {
                // vendor响应布局：[0]=状态字节，[1:]=响应文本
                val status = buf[0].toInt().toChar()
                if (status == 'O' || status == 'E') {
                    val end = minOf(r, REPORT_SIZE)
                    var len = 1
                    while (len < end && buf[len].toInt() != 0) len++
                    val resp = String(buf, 1, len - 1)
                    Log.d(TAG, "ACK($status): $resp")
                    return if (status == 'E') "err:$resp" else resp
                }
            }
            try { Thread.sleep(POLL_INTERVAL_MS) } catch (_: InterruptedException) { return null }
        }
        Log.w(TAG, "ACK timeout: stall=$stallCount short=$shortCount polled=$diagCount cmd=${cmd.take(20)}")
        return null
    }

    /**
     * 只发送命令不等ACK（快速连续点击用）
     */
    fun sendCommandOnly(cmd: String): Boolean {
        val conn = connection ?: return false
        if (!isConnected) return false

        // vendor OUT控制传输：载荷首字节起即命令文本（无report ID前缀），
        // 发64字节定长（zero-fill），固件按wLength截断
        val buf = ByteArray(REPORT_SIZE)
        val bytes = cmd.toByteArray()
        val copyLen = minOf(bytes.size, REPORT_SIZE - 1)  // 留1字节给固件补null
        System.arraycopy(bytes, 0, buf, 0, copyLen)

        // bmRequestType=0x41(OUT|VENDOR|INTERFACE), bRequest=0x01,
        // wValue=0, wIndex=vendor接口号；length必须等于wLength=64
        val r = sendControlTransfer(
            BREQ_SET,
            VENDOR_REQ_WRITE_CMD,
            0,
            interfaceIndex,
            buf,
            REPORT_SIZE,
            500
        )
        if (r < 0) {
            Log.w(TAG, "VENDOR_WRITE failed ($r) for cmd=$cmd")
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

    /**
     * 读取USB设备描述符的bcdDevice字段（固件版本）。
     * GET_DESCRIPTOR(DEVICE) 是标准请求，不需要claim接口，v2/v3固件都会正常响应。
     * 返回BCD编码的版本，如 0x0300 = v3.00；读不到返回0。
     */
    private fun readFirmwareBcd(conn: UsbDeviceConnection): Int {
        return try {
            val desc = ByteArray(18)
            // bmRequestType=0x80(IN|STANDARD|DEVICE), bRequest=0x06(GET_DESCRIPTOR),
            // wValue=0x0100(device descriptor,index0), wIndex=0
            val r = conn.controlTransfer(0x80, 0x06, 0x0100, 0, desc, 18, 500)
            if (r >= 18 && desc[0] == 0x12.toByte() && desc[1] == 0x01.toByte()) {
                // bcdDevice在offset 12-13（小端）
                val bcd = (desc[12].toInt() and 0xFF) or ((desc[13].toInt() and 0xFF) shl 8)
                Log.i(TAG, "GET_DESCRIPTOR(device) r=$r bcdDevice=0x${String.format("%04x", bcd)}")
                bcd
            } else {
                Log.w(TAG, "GET_DESCRIPTOR(device) r=$r, head=${desc[0]},${desc[1]}")
                0
            }
        } catch (e: Exception) {
            Log.w(TAG, "readFirmwareBcd error: ${e.message}")
            0
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
