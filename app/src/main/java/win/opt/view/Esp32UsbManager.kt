package win.opt.view

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
 * ESP32 USB直连通信管理器 (v3.2.0)
 *
 * ESP32-S3通过USB OTG线连手机，枚举为双接口复合设备：
 *   - 接口0：HID触摸屏（class=3），系统 usbhid 自动绑定（屏幕有鼠标光标），
 *     App 不 claim、不使用
 *   - 接口1：Vendor专用接口（class=0xFF），带一对 bulk 端点（OUT+IN, 64B）。
 *     App claim 该接口后用 UsbDeviceConnection.bulkTransfer() 收发 64 字节定长帧：
 *       bulk OUT：[0:]=命令文本，尾部 zero-fill（固件按null截断）
 *       bulk IN ：[0]=状态字节('O'/'E')，[1:]=响应文本，尾部 zero-fill
 *     bulk 是 USBVendor 类的主数据流（官方 USBVendor example loop() 用
 *     Vendor.write()/read()），也是 Android USB host 最成熟的 API。
 *
 * 版本沿革（均已废弃）：
 *   v3.0.0~v3.0.2：HID Feature Report——class+接口定向控制传输被内核强制
 *     check claim，usbhid 占用 HID 接口 → -EBUSY。证伪。
 *   v3.1.0：Vendor EP0 vendor 控制传输(0x41/0xC1)——枚举/claim/bcd 读取正常，
 *     但控制传输 OUT 即 -1、IN 无响应、固件回调不触发，实测黑洞。弃用。
 */
class Esp32UsbManager(private val context: Context) {
    companion object {
        private const val TAG = "Esp32Usb"
        private const val ESP32_VID = 0x303A
        private const val ESP32_PID = 0x8266
        private const val FRAME_SIZE = 64        // bulk端点定长帧
        private const val MIN_FW_BCD = 0x0320   // v3.2.0起命令走bulk端点
        private const val ACK_TIMEOUT_MS = 800L   // v3.2.1: 固件tap非阻塞，ACK正常~30ms；800ms留足余量，丢帧异常不再卡3s
        private const val POLL_INTERVAL_MS = 5L
        private const val ACTION_USB_PERMISSION = "win.opt.view.USB_PERMISSION"
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
    // v3.2.0: Vendor接口的 bulk 端点（claim后枚举得到）
    private var epOut: android.hardware.usb.UsbEndpoint? = null
    private var epIn: android.hardware.usb.UsbEndpoint? = null
    private val running = AtomicBoolean(false)
    private val receiverRegistered = AtomicBoolean(false)
    // R5-fix: bulk收发统一串行化锁——sendTapFast(不等ACK)与sendTap(等ACK)共用端点，
    // 不加锁会导致残留ok帧被下次ACK误读（ACK张冠李戴）
    private val ioLock = Any()

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
            // 查找Vendor专用接口（v3.2.0固件：if1, class=0xFF，带bulk端点）。
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
                // 旧固件兼容回退：HID接口（v3.0.x单接口设备；bulk通道不可用）
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

            // 读设备描述符里的bcdDevice字段（固件版本）：v3.2.0=0x0320。
            // 标准GET_DESCRIPTOR不需要claim，v2/v3固件都会正常响应。
            val bcd = readFirmwareBcd(conn)
            val fwVer = String.format("%x.%02x", (bcd shr 8) and 0xFF, bcd and 0xFF)
            val isV320 = bcd >= MIN_FW_BCD
            Log.i(TAG, "★ ESP32 bcdDevice=0x${String.format("%04x", bcd)} (firmware v$fwVer)")

            // 版本门槛：< v3.2.0 固件命令通道（v3.1.0控制传输/v3.0.x Feature）本就不通，
            // 不claim、不置isConnected，直接提示刷v3.2.0
            if (!isV320) {
                Log.w(TAG, "Firmware too old (bcd=0x${String.format("%04x", bcd)}), need v3.2.0")
                try { conn.close() } catch (_: Exception) {}
                notifyStatus(true, "USB已连接，但固件是v$fwVer，需刷v3.2.0固件")
                onCommandResult?.invoke("err:old_firmware(v$fwVer),need_v3.2.0")
                return
            }

            // claim vendor接口（force=false即可：该接口无内核驱动占用）
            val claimed = conn.claimInterface(iface, false)
            Log.i(TAG, "claimInterface($ifIdx,cls=${iface.interfaceClass},force=false)=$claimed")
            if (!claimed) {
                try { conn.close() } catch (_: Exception) {}
                notifyStatus(false, "USB接口占用失败(claim=false)")
                return
            }

            // 枚举vendor接口的 bulk OUT/IN 端点（TUD_VENDOR_DESCRIPTOR 自带一对）
            var outEp: android.hardware.usb.UsbEndpoint? = null
            var inEp: android.hardware.usb.UsbEndpoint? = null
            for (i in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(i)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == UsbConstants.USB_DIR_OUT && outEp == null) outEp = ep
                    if (ep.direction == UsbConstants.USB_DIR_IN && inEp == null) inEp = ep
                }
            }
            if (outEp == null || inEp == null) {
                // 端点缺失：描述符异常，记录诊断后拒绝（bulk无法收发）
                val eps = (0 until iface.endpointCount).joinToString(",") {
                    val ep = iface.getEndpoint(it)
                    "ep%d(type=%d,dir=%d)".format(ep.endpointNumber and 0x0F, ep.type, ep.direction)
                }
                Log.e(TAG, "bulk endpoints missing! iface=$ifIdx eps=[$eps]")
                try { conn.releaseInterface(iface) } catch (_: Exception) {}
                try { conn.close() } catch (_: Exception) {}
                notifyStatus(false, "USB端点异常(无bulk): $eps")
                return
            }
            Log.i(TAG, "★ bulk endpoints: OUT=0x%02x IN=0x%02x".format(
                outEp.endpointNumber, inEp.endpointNumber))

            connection = conn
            device = dev
            interfaceIndex = ifIdx
            epOut = outEp
            epIn = inEp
            isConnected = true

            val ifaceInfo = "iface=$ifIdx(cls=${iface.interfaceClass},eps=${iface.endpointCount}),claim=$claimed"
            Log.i(TAG, "★ ESP32 USB connected: ${dev.deviceName}, $ifaceInfo, fw=v$fwVer, bulk OK")
            notifyStatus(true, "USB已连接(fw v$fwVer,claim=$claimed)")

            // 发送status命令验证bulk通道（在后台线程，避免ANR）
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
     * v3.2.0：bulk OUT 发命令帧 → bulk IN 轮询读响应帧。
     * bulkTransfer 返回 -1 = 超时无数据（IN端点此刻无帧），非致命，继续轮询；
     * 返回 >=1 = 收到帧，[0]=状态字节('O'/'E')，[1:]=响应文本（null截断）。
     */
    fun sendCommandWaitAck(cmd: String, timeoutMs: Long): String? {
        // R5-fix: 全程持ioLock——发命令+等ACK原子化，防止fast tap残留帧串扰
        synchronized(ioLock) {
        // R5-fix-v2: drain必须在发送【之前】清残留帧——固件ACK回得很快，
        // 若先发后清，本命令自己的ACK会被drain当残留吞掉（曾导致status无响应/tap测试失败）
        val conn0 = connection ?: return null
        if (!isConnected) return null
        val inEp0 = epIn ?: return null
        drainInputPipe(conn0, inEp0)
        if (!sendCommandOnly(cmd)) return null

        val conn = conn0
        val inEp = inEp0
        val deadline = System.currentTimeMillis() + timeoutMs
        val buf = ByteArray(FRAME_SIZE)
        var diagCount = 0
        var timeoutCount = 0
        try {
        while (System.currentTimeMillis() < deadline) {
            val r = conn.bulkTransfer(inEp, buf, FRAME_SIZE, 100)
            // 前3次轮询记录原始返回，用于区分 超时(-1)/正常(>=1)
            if (diagCount < 3) {
                val hex = if (r >= 1) (0 until minOf(r, 8)).joinToString(" ") {
                    "%02x".format(buf[it].toInt() and 0xFF)
                } else ""
                Log.i(TAG, "BULK_READ poll#$diagCount r=$r head=[$hex]")
                diagCount++
            }
            if (r < 0) {
                timeoutCount++
            } else if (r >= 1) {
                // bulk响应布局：[0]=状态字节，[1:]=响应文本
                val status = buf[0].toInt().toChar()
                if (status == 'O' || status == 'E') {
                    val end = minOf(r, FRAME_SIZE)
                    var len = 1
                    while (len < end && buf[len].toInt() != 0) len++
                    val resp = String(buf, 1, len - 1)
                    Log.d(TAG, "ACK($status): $resp")
                    return if (status == 'E') "err:$resp" else resp
                }
            }
            try { Thread.sleep(POLL_INTERVAL_MS) } catch (_: InterruptedException) { return null }
        }
        Log.w(TAG, "ACK timeout: in_timeouts=$timeoutCount polled=$diagCount cmd=${cmd.take(20)}")
        return null
        } catch (e: Exception) {
            // R5复核: 持锁轮询期间连接被closeConnection关闭，bulkTransfer会抛IllegalStateException/IOException；
            // 不catch会让后台Thread静默死掉、FSM无人推进。返回null走ACK失败分支，由调用方决定重试/复位
            Log.w(TAG, "sendCommandWaitAck异常（连接可能已断开）: ${e.message}")
            return null
        }
        } // synchronized(ioLock)
    }

    /**
     * R5-fix: 读空IN端点残留帧（非阻塞，10ms超时，无数据立即返回）
     * 用于sendTapFast连发后清管道，防止残留ok帧被下次sendTap误当ACK
     */
    private fun drainInputPipe(conn: UsbDeviceConnection, inEp: android.hardware.usb.UsbEndpoint) {
        try {
            val buf = ByteArray(FRAME_SIZE)
            var drained = 0
            while (drained < 32) {  // 上限32帧，防异常固件刷屏
                val r = conn.bulkTransfer(inEp, buf, FRAME_SIZE, 10)
                if (r < 1) break
                drained++
            }
            if (drained > 0) Log.d(TAG, "R5 drainInputPipe: 清掉残留帧$drained")
        } catch (e: Exception) {
            Log.w(TAG, "R5 drainInputPipe异常（可能连接已关闭）: ${e.message}")
        }
    }

    /**
     * 只发送命令不等ACK（快速连续点击用）
     */
    fun sendCommandOnly(cmd: String): Boolean {
        // R5-fix: OUT发送也串行化，避免与sendCommandWaitAck的IN轮询并发
        synchronized(ioLock) {
        val conn = connection ?: return false
        if (!isConnected) return false
        val outEp = epOut ?: return false

        // bulk OUT 定长64字节帧：载荷首字节起即命令文本（无report ID前缀），
        // zero-fill，固件按null截断
        val buf = ByteArray(FRAME_SIZE)
        val bytes = cmd.toByteArray()
        val copyLen = minOf(bytes.size, FRAME_SIZE - 1)  // 留1字节给固件补null
        System.arraycopy(bytes, 0, buf, 0, copyLen)

        val r = try {
            conn.bulkTransfer(outEp, buf, FRAME_SIZE, 500)
        } catch (e: Exception) {
            // R5复核: 并发closeConnection时bulkTransfer抛异常，不catch会静默杀调用线程
            Log.w(TAG, "BULK_WRITE异常（连接可能已断开）: ${e.message}")
            -1
        }
        if (r < 0) {
            Log.w(TAG, "BULK_WRITE failed ($r) for cmd=$cmd")
            // R5复核: handleDisconnect→closeConnection释放USB句柄+notifyStatus通知UI断开；
            // ioLock可重入同线程嵌套不死锁，notifyStatus回调自带try-catch
            handleDisconnect("USB写失败")
            return false
        }
        Log.d(TAG, "TX: $cmd (sent=$r)")
        return true
        } // synchronized(ioLock)
    }

    /**
     * 读取USB设备描述符的bcdDevice字段（固件版本）。
     * GET_DESCRIPTOR(DEVICE) 是标准请求，走EP0，不需要claim接口，v2/v3固件都会正常响应。
     * 返回BCD编码的版本，如 0x0320 = v3.20；读不到返回0。
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
        // R9-4-fix: interfaceIndex=-1时getInterface(-1)会抛异常导致release被跳过，先判下标
        try {
            val conn = connection
            val dev = device
            val idx = interfaceIndex
            if (conn != null && dev != null && idx >= 0) {
                conn.releaseInterface(dev.getInterface(idx))
            }
        } catch (e: Exception) { Log.w(TAG, "releaseInterface异常: ${e.message}") }
        try { connection?.close() } catch (_: Exception) {}
        connection = null
        device = null
        epOut = null
        epIn = null
        interfaceIndex = -1
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
