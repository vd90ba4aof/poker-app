package com.pokerhelper.app

import android.bluetooth.*
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.UUID

/**
 * ESP32 BLE Client Manager
 * 连接ESP32的Nordic UART Service，发送tap指令
 */
class Esp32BleManager(private val context: Context) {
    
    companion object {
        private const val TAG = "Esp32BleManager"
        
        // Nordic UART Service UUIDs (与ESP32固件一致)
        private val NUS_SERVICE_UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DAB9E9")
        private val RX_CHAR_UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DAB9E9")  // Write
        private val TX_CHAR_UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DAB9E9")  // Notify
        
        // ESP32设备名
        private const val DEVICE_NAME = "QingYun-ESP32"
    }
    
    private val handler = Handler(Looper.getMainLooper())
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    
    // V2.9.183: BLE自动重连
    private var lastConnectedDevice: BluetoothDevice? = null
    private var autoReconnectEnabled = true
    private var reconnectAttempts = 0
    private val MAX_RECONNECT_ATTEMPTS = 5
    private val RECONNECT_DELAY_BASE = 2000L  // 基础重连延迟2秒
    private var reconnectRunnable: Runnable? = null
    
    var isConnected = false
        private set
    var onStatusChanged: ((Boolean, String) -> Unit)? = null
    var onCommandResult: ((String) -> Unit)? = null

    // V2.9.240: RSSI信号强度
    var lastRssi: Int = 0
        private set
    var onRssiUpdate: ((Int) -> Unit)? = null

    // V2.9.184: BLE命令队列——避免writeCharacteristic失败导致命令丢失
    private val commandQueue = mutableListOf<String>()
    private var isWriting = false

    // V2.9.178: BLE数据缓冲——ESP32 status响应120+字节，BLE单包最多20字节
    // 必须拼接多包才能拿到完整数据
    private val bleRxBuffer = StringBuilder()
    private val bleFlushTimeout = Runnable { flushBleBuffer() }
    
    private fun flushBleBuffer() {
        if (bleRxBuffer.isNotEmpty()) {
            val msg = bleRxBuffer.toString()
            bleRxBuffer.clear()
            Log.d(TAG, "BLE flush complete msg(${msg.length}): $msg")
            // V1.0.35: pong回复时重置心跳计数
            try {
                if (msg.startsWith("pong")) {
                    missedHeartbeats = 0
                    onHeartbeat?.invoke(true, msg)
                    Log.d(TAG, "Heartbeat pong received, missed reset to 0")
                }
                // V2.9.240: ESP32返回的status中包含rssi字段时更新
                if (msg.startsWith("ok:")) {
                    val rssiMatch = Regex(""".*rssi[:=]\s*(-?\d+)""").find(msg)
                    if (rssiMatch != null) {
                        try {
                            val espRssi = rssiMatch.groupValues[1].toInt()
                            lastRssi = espRssi
                            Log.d(TAG, "RSSI from ESP32 status: ${espRssi}dBm")
                            onRssiUpdate?.invoke(espRssi)
                        } catch (e: Exception) {
                            Log.w(TAG, "parse ESP32 RSSI error", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "pong/rssi handling error", e)
            }
            onCommandResult?.invoke(msg)
        }
    }

    // V1.0.35: BLE心跳监控
    var onHeartbeat: ((connected: Boolean, heartbeatData: String) -> Unit)? = null
    private var heartbeatHandler: Handler? = null
    private var heartbeatRunnable: Runnable? = null
    private var missedHeartbeats = 0

    // V2.9.171: 运行时权限检查
    private fun hasBlePermission(perm: String): Boolean {
        return ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
    }

    // 扫描/连接ESP32设备
    fun startScan() {
        Log.i(TAG, "startScan: beginning BLE scan for $DEVICE_NAME")
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter

        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
            Log.w(TAG, "startScan: Bluetooth not available or disabled")
            notifyStatus(false, "蓝牙未开启")
            return
        }

        // V2.9.171: BLUETOOTH_CONNECT权限检查
        if (!hasBlePermission(android.Manifest.permission.BLUETOOTH_CONNECT)) {
            Log.e(TAG, "BLUETOOTH_CONNECT not granted")
            notifyStatus(false, "蓝牙连接权限未授予，请打开App权限设置允许")
            return
        }

        // 策略1: 从已配对设备列表中查找ESP32
        try {
            val bondedDevices = bluetoothAdapter!!.bondedDevices
            for (device in bondedDevices) {
                val name = try { device.name } catch (e: SecurityException) { null }
                if (name == DEVICE_NAME) {
                    Log.i(TAG, "Found ESP32 in bonded devices: $name")
                    connectToDevice(device)
                    return
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "getBondedDevices SecurityException", e)
        } catch (e: Exception) {
            Log.w(TAG, "getBondedDevices error", e)
        }

        // V2.9.171: BLUETOOTH_SCAN权限检查
        if (!hasBlePermission(android.Manifest.permission.BLUETOOTH_SCAN)) {
            Log.e(TAG, "BLUETOOTH_SCAN not granted")
            notifyStatus(false, "蓝牙扫描权限未授予，请打开App权限设置允许")
            return
        }

        // 策略2: BLE扫描兜底
        notifyStatus(false, "扫描ESP32中...")

        val scanner = try {
            bluetoothAdapter?.bluetoothLeScanner
        } catch (e: SecurityException) {
            Log.e(TAG, "bluetoothLeScanner SecurityException", e)
            null
        }

        if (scanner == null) {
            notifyStatus(false, "蓝牙扫描器不可用")
            return
        }

        try {
            scanner.startScan(scanCallback)
        } catch (e: SecurityException) {
            Log.e(TAG, "startScan SecurityException", e)
            notifyStatus(false, "蓝牙扫描异常")
            return
        }

        // 10秒超时
        handler.postDelayed({
            if (!isConnected) {
                try { scanner.stopScan(scanCallback) } catch (_: Exception) {}
                notifyStatus(false, "未找到ESP32")
            }
        }, 10000)
    }
    
    // 停止扫描
    fun stopScan() {
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.w(TAG, "stopScan error", e)
        }
    }
    
    // 连接指定设备
    private fun connectToDevice(device: BluetoothDevice) {
        stopScan()
        val deviceName = try { device.name } catch (e: SecurityException) { "ESP32" }
        val deviceAddr = try { device.address } catch (e: Exception) { "unknown" }
        Log.i(TAG, "connectToDevice: name=$deviceName, address=$deviceAddr")
        notifyStatus(false, "连接${deviceName}...")
        
        try {
            // v1.0.39-fix: autoConnect=true，让Android系统在设备可用时自动连接
            // 首次连接由系统管理更稳定，断连后也能自动触发重连
            bluetoothGatt = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                device.connectGatt(context, true, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context, true, gattCallback)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "connectGatt SecurityException - need BLUETOOTH_CONNECT permission", e)
            notifyStatus(false, "需要蓝牙连接权限，请在App权限中允许")
        } catch (e: Exception) {
            Log.e(TAG, "connectGatt failed", e)
            notifyStatus(false, "连接失败: ${e.message}")
        }
    }
    
    // 断开连接
    fun disconnect() {
        try {
            Log.i(TAG, "disconnect: manually disconnecting BLE")
            stopHeartbeatMonitor()  // V1.0.35: 停止心跳监控
            autoReconnectEnabled = false  // V2.9.183: 主动断开时不自动重连
            reconnectRunnable?.let { handler.removeCallbacks(it) }
            reconnectRunnable = null
            reconnectAttempts = 0
            handler.removeCallbacks(bleFlushTimeout)
            bleRxBuffer.clear()
            commandQueue.clear()  // V2.9.240: 断开时清空命令队列
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
            bluetoothGatt = null
            txCharacteristic = null
            rxCharacteristic = null
            isConnected = false
            lastConnectedDevice = null
            lastRssi = 0  // V2.9.240: 重置RSSI
            notifyStatus(false, "已断开")
            Log.i(TAG, "disconnect: BLE disconnected and resources cleaned")
        } catch (e: Exception) {
            Log.w(TAG, "disconnect error", e)
        }
    }
    
    // 发送tap指令
    fun sendTap(x: Int, y: Int, duration: Int = 50) {
        if (!isConnected || txCharacteristic == null) {
            onCommandResult?.invoke("err:not_connected")
            return
        }
        
        val cmd = "tap:$x,$y,$duration"
        sendCommand(cmd)
    }
    
    // 发送status查询
    fun sendStatus() {
        if (!isConnected || txCharacteristic == null) {
            onCommandResult?.invoke("err:not_connected")
            return
        }
        sendCommand("status")
    }
    
    // 发送ping
    fun sendPing() {
        if (!isConnected || txCharacteristic == null) {
            Log.w(TAG, "sendPing: not connected, returning err")
            onCommandResult?.invoke("err:not_connected")
            return
        }
        // V2.9.240: 每次ping顺带读取RSSI
        try {
            bluetoothGatt?.readRemoteRssi()
            Log.d(TAG, "sendPing: requested RSSI read")
        } catch (e: Exception) {
            Log.w(TAG, "sendPing: readRemoteRssi error", e)
        }
        sendCommand("ping")
    }

    // V1.0.35: 启动心跳监控，每intervalMs发一次ping，连续3次无回复触发重连
    fun startHeartbeatMonitor(intervalMs: Long = 10000) {
        try {
            stopHeartbeatMonitor()
            heartbeatHandler = Handler(Looper.getMainLooper())
            missedHeartbeats = 0
            heartbeatRunnable = object : Runnable {
                override fun run() {
                    try {
                        if (isConnected) {
                            Log.d(TAG, "Heartbeat tick: sending ping, missedHeartbeats=$missedHeartbeats")
                            sendCommand("ping")
                            missedHeartbeats++
                            if (missedHeartbeats >= 3) {
                                Log.w(TAG, "BLE心跳超时(${missedHeartbeats}次)，触发重连")
                                onHeartbeat?.invoke(false, "timeout:${missedHeartbeats}")
                                // v1.0.39-fix: 用disconnectForReconnect()代替disconnect()，不杀autoReconnect
                                try { disconnectForReconnect() } catch (e: Exception) { Log.w(TAG, "heartbeat disconnect error", e) }
                                try { scheduleReconnect() } catch (e: Exception) { Log.w(TAG, "heartbeat reconnect error", e) }
                                return
                            } else if (missedHeartbeats >= 1) {
                                // V1.0.35: 通知心跳超时(黄色预警)
                                Log.d(TAG, "Heartbeat missed: count=$missedHeartbeats")
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
            Log.i(TAG, "Heartbeat monitor started (interval=${intervalMs}ms)")
        } catch (e: Exception) {
            Log.e(TAG, "startHeartbeatMonitor error", e)
        }
    }

    // V1.0.35: 停止心跳监控
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
    
    // V2.9.184: 命令队列——避免并发写入导致命令丢失
    private fun sendCommand(cmd: String) {
        Log.d(TAG, "sendCommand: cmd=$cmd, isWriting=$isWriting, queueSize=${commandQueue.size}")
        if (isWriting) {
            commandQueue.add(cmd)
            Log.d(TAG, "sendCommand: queued, new queueSize=${commandQueue.size}")
            return
        }
        writeCommand(cmd)
    }
    
    private fun writeCommand(cmd: String) {
        try {
            val characteristic = txCharacteristic ?: run {
                onCommandResult?.invoke("err:no_tx_char")
                processNextCommand()
                return
            }
            characteristic.value = cmd.toByteArray(Charsets.UTF_8)
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            val success = bluetoothGatt?.writeCharacteristic(characteristic) ?: false
            if (success) {
                isWriting = true
            } else {
                onCommandResult?.invoke("err:write_failed")
                processNextCommand()
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendCommand error", e)
            onCommandResult?.invoke("err:${e.message}")
            processNextCommand()
        }
    }
    
    private fun processNextCommand() {
        isWriting = false
        if (commandQueue.isNotEmpty()) {
            val next = commandQueue.removeAt(0)
            writeCommand(next)
        }
    }
    
    // BLE扫描回调
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            try {
                val device = result.device
                val name = device.name ?: return
                val rssi = result.rssi
                
                if (name == DEVICE_NAME) {
                    Log.i(TAG, "Found ESP32 on scan: name=$name, addr=${device.address}, rssi=${rssi}dBm")
                    connectToDevice(device)
                }
            } catch (e: Exception) {
                Log.w(TAG, "onScanResult error", e)
            }
        }
        
        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed: $errorCode")
            notifyStatus(false, "扫描失败: $errorCode")
        }
    }
    
    // GATT回调
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "onConnectionStateChange: STATE_CONNECTED, status=$status")
                    // V2.9.183: 保存设备用于自动重连
                    lastConnectedDevice = gatt.device
                    reconnectAttempts = 0
                    handler.post {
                        // V2.9.179: 请求最大MTU，减少分包
                        gatt.requestMtu(512)
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Disconnected from GATT server, stopping heartbeat monitor")
                    isConnected = false
                    stopHeartbeatMonitor()  // V2.9.240: 断连立即停止心跳，避免泄漏
                    notifyStatus(false, "已断开")
                    // V2.9.183: 自动重连
                    scheduleReconnect()
                }
            }
        }
        
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            try {
                Log.i(TAG, "onMtuChanged: mtu=$mtu, status=$status")
                // MTU协商完成后发现服务
                handler.post {
                    try {
                        gatt.discoverServices()
                        Log.d(TAG, "onMtuChanged: discoverServices requested")
                    } catch (e: Exception) {
                        Log.e(TAG, "onMtuChanged discoverServices error", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "onMtuChanged error", e)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(NUS_SERVICE_UUID)
                if (service != null) {
                    txCharacteristic = service.getCharacteristic(RX_CHAR_UUID)  // 手机写入→ESP32
                    rxCharacteristic = service.getCharacteristic(TX_CHAR_UUID)  // ESP32通知→手机
                    
                    if (txCharacteristic != null && rxCharacteristic != null) {
                        // 启用TX通知
                        gatt.setCharacteristicNotification(rxCharacteristic, true)
                        val descriptor = rxCharacteristic?.getDescriptor(
                            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
                        )
                        descriptor?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(descriptor)
                        
                        isConnected = true
                        notifyStatus(true, "已连接")
                        // V2.9.240: 连接成功后读取RSSI
                        try {
                            gatt.readRemoteRssi()
                            Log.d(TAG, "onServicesDiscovered: requested RSSI read")
                        } catch (e: Exception) {
                            Log.w(TAG, "onServicesDiscovered: readRemoteRssi error", e)
                        }
                    } else {
                        notifyStatus(false, "未找到NUS特征")
                    }
                } else {
                    notifyStatus(false, "未找到NUS服务")
                }
            } else {
                Log.e(TAG, "onServicesDiscovered failed: status=$status")
                notifyStatus(false, "服务发现失败: $status")
            }
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            try {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    lastRssi = rssi
                    Log.d(TAG, "onReadRemoteRssi: rssi=${rssi}dBm")
                    onRssiUpdate?.invoke(rssi)
                } else {
                    Log.w(TAG, "onReadRemoteRssi failed: status=$status")
                }
            } catch (e: Exception) {
                Log.w(TAG, "onReadRemoteRssi error", e)
            }
        }
        
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == TX_CHAR_UUID) {
                val value = try { characteristic.getStringValue(0) } catch (e: Exception) {
                    Log.w(TAG, "onCharacteristicChanged getStringValue error", e)
                    return
                }
                Log.d(TAG, "BLE rx chunk(${value.length}): $value")
                
                // V1.0.35: 检测ESP32主动心跳通知，不参与数据缓冲
                try {
                    if (value.startsWith("hb:")) {
                        missedHeartbeats = 0
                        onHeartbeat?.invoke(true, value)
                        Log.d(TAG, "Heartbeat notification: $value")
                        return
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "heartbeat detection error", e)
                }
                
                // V2.9.179 fix: 不再按\n flush，因为ESP32的status多包响应里自带\n
                // 第一包到\n就flush的话，后续包全丢了
                // 改为纯超时 flush，等所有分包的碎片全部拼完
                handler.removeCallbacks(bleFlushTimeout)
                bleRxBuffer.append(value)
                handler.postDelayed(bleFlushTimeout, 500)
            }
        }
        
        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            try {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    val cmdStr = try { characteristic.value?.toString(Charsets.UTF_8)?.take(40) } catch (_: Exception) { "?" }
                    Log.d(TAG, "onCharacteristicWrite: success, cmd=$cmdStr, queueSize=${commandQueue.size}")
                } else {
                    Log.w(TAG, "onCharacteristicWrite: failed, status=$status")
                }
            } catch (e: Exception) {
                Log.w(TAG, "onCharacteristicWrite log error", e)
            }
            processNextCommand()  // V2.9.184: 发送队列中的下一条命令
        }
    }
    
    // v1.0.39-fix: 内部断连（不杀autoReconnect），用于心跳超时等需要重连的场景
    private fun disconnectForReconnect() {
        try {
            stopHeartbeatMonitor()
            isConnected = false
            bluetoothGatt?.disconnect()
            // 不清除bluetoothGatt引用，scheduleReconnect需要用gatt.connect()直接重连
            Log.i(TAG, "disconnectForReconnect: BLE断连，保留gatt引用以待重连")
        } catch (e: Exception) {
            Log.e(TAG, "disconnectForReconnect error", e)
        }
    }

    // v1.0.39-fix: 自动重连调度（无次数上限，优先用gatt.connect()直连，不走重新扫描）
    private fun scheduleReconnect() {
        try {
            if (!autoReconnectEnabled) {
                Log.d(TAG, "scheduleReconnect: autoReconnect disabled, skipping")
                return
            }
            val device = lastConnectedDevice
            if (device == null) {
                Log.w(TAG, "scheduleReconnect: no saved device, falling back to scan")
                startScan()
                return
            }
            reconnectAttempts++
            // 递增延迟，上限30秒：2,4,6,...,30,30,30...
            val delay = minOf(RECONNECT_DELAY_BASE * reconnectAttempts, 30000L)
            Log.i(TAG, "BLE自动重连: 第${reconnectAttempts}次, ${delay}ms后, device=${device.address}")
            notifyStatus(false, "重连中(第${reconnectAttempts}次)...")
        
        reconnectRunnable?.let { handler.removeCallbacks(it) }
        reconnectRunnable = Runnable {
            try {
                Log.d(TAG, "Reconnect runnable fired: attempt=$reconnectAttempts")
                // 优先用gatt.connect()直接重连（更快，不重新扫描）
                val gatt = bluetoothGatt
                if (gatt != null) {
                    Log.i(TAG, "Using gatt.connect() for direct reconnect")
                    gatt.connect()
                } else {
                    // gatt为null时走完整连接流程
                    Log.i(TAG, "gatt is null, falling back to connectToDevice()")
                    connectToDevice(device)
                }
            } catch (e: Exception) {
                Log.e(TAG, "重连异常", e)
                scheduleReconnect()  // 失败后继续重试
            }
        }
        handler.postDelayed(reconnectRunnable!!, delay)
        } catch (e: Exception) {
            Log.e(TAG, "scheduleReconnect error", e)
        }
    }
    
    // 通知状态变化
    private fun notifyStatus(connected: Boolean, message: String) {
        handler.post {
            isConnected = connected
            onStatusChanged?.invoke(connected, message)
        }
    }
}
