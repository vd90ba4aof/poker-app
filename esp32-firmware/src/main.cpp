/**
 * ============================================================================
 * 青云扑克 ESP32-S3 - 经典蓝牙SPP + USB HID 固件 (v1.0.38)
 * ============================================================================
 *
 * v1.0.38：BLE → 经典蓝牙SPP (Serial Port Profile)
 *   - 彻底抛弃BLE，改用经典蓝牙SPP（BluetoothSerial）
 *   - 配对后系统自动连接，不再需要App主动发起连接
 *   - 连接由Android系统维持，断连后自动重连
 *   - USB等待改为非阻塞，SPP先启动不阻塞
 *   - 通信协议完全不变（tap:x,y,ms / status / log / ping / diag / selftest）
 *
 * 经典蓝牙SPP优势：
 *   - 配对后自动连接（像XCMG设备一样）
 *   - 系统维持连接，不需要App管理
 *   - 连接稳定性远高于BLE
 *   - 代码更简单（不需要GATT、心跳、自动重连等复杂逻辑）
 *
 * 兼容App：Serial Bluetooth Terminal (Kai Morich), 青云APP
 *
 * 核心实现：
 *   - USBHID 触摸屏模拟（Digitizer HID Report）
 *   - 经典蓝牙SPP（Serial Port Profile）
 *   - platformio.ini: ARDUINO_USB_MODE=0 + ARDUINO_USB_CDC_ON_BOOT=0
 */

#include <Arduino.h>
#include <USB.h>
#include <USBHID.h>

// 经典蓝牙SPP库
#include "BluetoothSerial.h"

// 禁用 brownout detector
#include "soc/soc.h"
#include "soc/rtc_cntl_reg.h"

// ============================================================================
// 常量配置
// ============================================================================
#define FW_VERSION "v1.0.38"

// 蓝牙设备名
#define BT_DEVICE_NAME "QingYun-ESP32"

// 屏幕分辨率（一加13T）
#define SCREEN_WIDTH  1080
#define SCREEN_HEIGHT 2344

// HID 坐标范围
#define HID_MAX 32767

// 点击随机抖动（降低行为检测风险）
#define JITTER_PX  5     // 坐标±5px随机偏移
#define JITTER_MS  20    // 时长±20ms随机变化

// HID Report ID
#define HID_REPORT_ID_TOUCH 0

// ============================================================================
// 日志缓冲区（Serial + BT log指令可用）
// ============================================================================
static String log_buf = "";
static const size_t LOG_BUF_MAX = 6144;  // 6KB上限
static int log_skip_count = 0;
static bool log_skip_warned = false;

static void qlog(const char* msg) {
    Serial.println(msg);
    if (log_buf.length() < LOG_BUF_MAX) {
        log_buf += msg;
        log_buf += '\n';
    } else if (!log_skip_warned) {
        log_skip_warned = true;
        log_buf += "[LOG BUFFER FULL]\n";
    }
    log_skip_count++;
}

static void qlogf(const char* fmt, ...) {
    char buf[256];
    va_list args;
    va_start(args, fmt);
    vsnprintf(buf, sizeof(buf), fmt, args);
    va_end(args);
    Serial.println(buf);
    if (log_buf.length() < LOG_BUF_MAX) {
        log_buf += buf;
        log_buf += '\n';
    } else if (!log_skip_warned) {
        log_skip_warned = true;
        log_buf += "[LOG BUFFER FULL]\n";
    }
    log_skip_count++;
}

// ============================================================================
// HID 报告描述符（触摸屏 Digitizer - 6字节带Contact ID）
// ============================================================================
static const uint8_t touch_report_descriptor[] = {
    0x05, 0x0D,             // Usage Page (Digitizers)
    0x09, 0x04,             // Usage (Touch Screen)
    0xA1, 0x01,             // Collection (Application)

    0x09, 0x22,             //   Usage (Finger)
    0xA1, 0x02,             //   Collection (Logical)

    // Contact Identifier (8 bits) - Android靠此区分触摸屏
    0x09, 0x51,             //     Usage (Contact Identifier)
    0x15, 0x00,             //     Logical Minimum (0)
    0x25, 0x01,             //     Logical Maximum (1)
    0x75, 0x08,             //     Report Size (8)
    0x95, 0x01,             //     Report Count (1)
    0x81, 0x02,             //     Input (Data,Var,Abs)

    // Tip Switch (bit0) + In Range (bit1) + 6 bits padding
    0x09, 0x42,             //     Usage (Tip Switch)
    0x09, 0x32,             //     Usage (In Range)
    0x15, 0x00,             //     Logical Minimum (0)
    0x25, 0x01,             //     Logical Maximum (1)
    0x75, 0x01,             //     Report Size (1 bit)
    0x95, 0x02,             //     Report Count (2) = Tip + InRange
    0x81, 0x02,             //     Input (Data,Var,Abs)
    0x95, 0x06,             //     Report Count (6) padding bits
    0x81, 0x03,             //     Input (Const,Var,Abs)

    // X (16 bits, absolute)
    0x05, 0x01,             //     Usage Page (Generic Desktop)
    0x09, 0x30,             //     Usage (X)
    0x15, 0x00,             //     Logical Minimum (0)
    0x26, 0xFF, 0x7F,      //     Logical Maximum (32767)
    0x75, 0x10,             //     Report Size (16 bits)
    0x95, 0x01,             //     Report Count (1)
    0x81, 0x02,             //     Input (Data,Var,Abs)

    // Y (16 bits, absolute)
    0x09, 0x31,             //     Usage (Y)
    0x26, 0xFF, 0x7F,      //     Logical Maximum (32767)
    0x75, 0x10,             //     Report Size (16 bits)
    0x95, 0x01,             //     Report Count (1)
    0x81, 0x02,             //     Input (Data,Var,Abs)

    0xC0,                   //   End Collection (Logical)
    0xC0                    // End Collection (Application)
};

struct __attribute__((packed)) TouchReport {
    uint8_t  contact_id;     // byte 0
    uint8_t  flags;          // byte 1
    uint16_t x;              // byte 2-3
    uint16_t y;              // byte 4-5
};

// ============================================================================
// USB HID 触控设备类
// ============================================================================
class USBHIDTouchpad : public USBHIDDevice {
private:
    USBHID hid;
    TouchReport _report;
    bool _everMounted = false;
    int _failCount = 0;
    const char* _lastFailReason = "none";

public:
    USBHIDTouchpad() : hid() {
        static bool initialized = false;
        if (!initialized) {
            initialized = true;
            hid.addDevice(this, sizeof(touch_report_descriptor));
        }
    }

    void begin() {
        hid.begin();
    }

    bool ready() {
        bool r = hid.ready();
        if (r) _everMounted = true;
        return r;
    }

    bool wasEverMounted() const { return _everMounted; }
    int hidFailCount() const { return _failCount; }
    const char* hidLastFailReason() const { return _lastFailReason; }

    uint16_t _onGetDescriptor(uint8_t* buffer) override {
        memcpy(buffer, touch_report_descriptor, sizeof(touch_report_descriptor));
        return sizeof(touch_report_descriptor);
    }

    bool touchDown(uint16_t screenX, uint16_t screenY) {
        yield();
        if (!hid.ready()) {
            _failCount++;
            _lastFailReason = "not_ready";
            return false;
        }
        _report.contact_id = 1;
        _report.flags = 0x03;  // bit0=Tip | bit1=InRange
        _report.x     = (uint16_t)((uint32_t)screenX * HID_MAX / SCREEN_WIDTH);
        _report.y     = (uint16_t)((uint32_t)screenY * HID_MAX / SCREEN_HEIGHT);
        Serial.printf("[HID] down raw(%u,%u) hid(%u,%u) cid=%u flags=0x%02x\n",
                      screenX, screenY, _report.x, _report.y, _report.contact_id, _report.flags);
        for (int retry = 0; retry < 5; retry++) {
            if (hid.SendReport(HID_REPORT_ID_TOUCH, &_report, sizeof(_report))) return true;
            delay(10);
            yield();
        }
        _failCount++;
        _lastFailReason = "send_failed";
        return false;
    }

    bool touchUp() {
        yield();
        if (!hid.ready()) {
            _failCount++;
            _lastFailReason = "not_ready";
            return false;
        }
        _report.contact_id = 1;
        _report.flags = 0x00;
        Serial.printf("[HID] up cid=%u\n", _report.contact_id);
        for (int retry = 0; retry < 5; retry++) {
            if (hid.SendReport(HID_REPORT_ID_TOUCH, &_report, sizeof(_report))) return true;
            delay(10);
            yield();
        }
        _failCount++;
        _lastFailReason = "send_failed";
        return false;
    }

    bool tap(uint16_t screenX, uint16_t screenY, uint32_t durationMs) {
        // 坐标±5px + 时长±20ms随机抖动，降低行为检测风险
        int16_t jx = (int16_t)(esp_random() % (JITTER_PX * 2 + 1)) - JITTER_PX;
        int16_t jy = (int16_t)(esp_random() % (JITTER_PX * 2 + 1)) - JITTER_PX;
        int16_t adjX = (int16_t)screenX + jx;
        int16_t adjY = (int16_t)screenY + jy;
        if (adjX < 0) adjX = 0;
        if (adjX >= SCREEN_WIDTH) adjX = SCREEN_WIDTH - 1;
        if (adjY < 0) adjY = 0;
        if (adjY >= SCREEN_HEIGHT) adjY = SCREEN_HEIGHT - 1;

        int32_t jms = (int32_t)(esp_random() % (JITTER_MS * 2 + 1)) - JITTER_MS;
        int32_t adjDur = (int32_t)durationMs + jms;
        if (adjDur < 10) adjDur = 10;

        uint16_t hidX = (uint16_t)((uint32_t)adjX * HID_MAX / SCREEN_WIDTH);
        uint16_t hidY = (uint16_t)((uint32_t)adjY * HID_MAX / SCREEN_HEIGHT);
        Serial.printf("[TAP] raw=(%u,%u) jitter=(%d,%d) adj=(%d,%d) hid=(%u,%u) dur=%dms (jitter=%dms)\n",
                      screenX, screenY, jx, jy, adjX, adjY, hidX, hidY, adjDur, jms);

        if (!touchDown((uint16_t)adjX, (uint16_t)adjY)) return false;
        delay(adjDur);
        return touchUp();
    }
};

static USBHIDTouchpad touchpad;

// ============================================================================
// HID 自检状态
// ============================================================================
static bool    g_selftestDone   = false;
static bool    g_selftestDown   = false;
static bool    g_selftestUp     = false;
static int     g_selftestFails  = 0;
static String  g_selftestResult = "waiting";

static void runHidSelfTest() {
    qlog("[SELFTEST] Starting HID self-test...");
    g_selftestDone = false;
    g_selftestDown = false;
    g_selftestUp   = false;
    g_selftestFails = 0;

    if (!touchpad.ready()) {
        g_selftestResult = "HID_not_ready";
        g_selftestDone = true;
        qlog("[SELFTEST] FAIL: HID not ready");
        return;
    }

    g_selftestDown = touchpad.touchDown(540, 1172);
    if (!g_selftestDown) {
        g_selftestFails = touchpad.hidFailCount();
        g_selftestResult = "down_failed";
        g_selftestDone = true;
        qlogf("[SELFTEST] FAIL: touchDown failed (fails=%d, reason=%s)",
              g_selftestFails, touchpad.hidLastFailReason());
        return;
    }

    delay(50);

    g_selftestUp = touchpad.touchUp();
    g_selftestFails = touchpad.hidFailCount();
    g_selftestDone = true;

    if (g_selftestUp) {
        g_selftestResult = "ok";
        qlog("[SELFTEST] PASS: tap(540,1172,50) sent successfully");
    } else {
        g_selftestResult = "up_failed";
        qlogf("[SELFTEST] FAIL: touchUp failed (fails=%d, reason=%s)",
              g_selftestFails, touchpad.hidLastFailReason());
    }

    qlogf("[SELFTEST] Axis detail - X: screen=540 hid_max=%d ratio=%.4f | Y: screen=1172 hid_max=%d ratio=%.4f | USB: %s | HID: %s",
          HID_MAX, (float)HID_MAX / SCREEN_WIDTH,
          HID_MAX, (float)HID_MAX / SCREEN_HEIGHT,
          (bool)USB ? "mounted" : "not-mounted",
          touchpad.ready() ? "ready" : "not-ready");
}

// ============================================================================
// 经典蓝牙SPP
// ============================================================================
static BluetoothSerial SerialBT;

// SPP连接状态追踪
static bool g_btConnected = false;
static bool g_lastBtState = false;
static uint32_t g_disconnectCount = 0;
static unsigned long g_lastDisconnect = 0;
static unsigned long g_lastConnectTime = 0;

// --- 发送回复到手机 ---
static void btReply(const char* msg) {
    if (SerialBT.hasClient()) {
        SerialBT.println(msg);
        qlogf("[BT] TX: %s", msg);
    } else {
        qlogf("[BT] TX skipped (not connected): %s", msg);
    }
}

// --- 处理指令 ---
static void processCommand(const String& cmd) {
    if (cmd.startsWith("tap:")) {
        // 格式: tap:x,y,duration
        String params = cmd.substring(4);
        int c1 = params.indexOf(',');
        int c2 = params.indexOf(',', c1 + 1);

        if (c1 > 0 && c2 > c1) {
            int x = params.substring(0, c1).toInt();
            int y = params.substring(c1 + 1, c2).toInt();
            int dur = params.substring(c2 + 1).toInt();
            if (dur < 10) dur = 50;

            if (x < 0 || x >= SCREEN_WIDTH || y < 0 || y >= SCREEN_HEIGHT) {
                char buf[128];
                snprintf(buf, sizeof(buf),
                         "err:coords_out_of_range(x:0-%d,y:0-%d,got=%d,%d)",
                         SCREEN_WIDTH - 1, SCREEN_HEIGHT - 1, x, y);
                btReply(buf);
                return;
            }

            bool ok = touchpad.tap(x, y, dur);
            if (ok) {
                char buf[128];
                snprintf(buf, sizeof(buf), "ok:tap(%d,%d,%dms)", x, y, dur);
                btReply(buf);
            } else {
                btReply("err:tap_fail(hid_send_failed)");
            }
        } else {
            btReply("err:bad_format,use:tap:x,y,ms");
        }

    } else if (cmd == "status") {
        bool usbMounted = (bool)USB;
        bool hidReady = touchpad.ready();
        bool btConnected = SerialBT.hasClient();
        
        char buf[512];
        snprintf(buf, sizeof(buf),
            "ok:ver=%s,heap=%u,psram=%u,usb=%s,hid=%s,ever=%s,fails=%d,reason=%s,bt=%s,uptime=%lus,mnt=%d,st=%s,st_down=%s,st_up=%s,dc=%lu",
            FW_VERSION,
            ESP.getFreeHeap(),
            (unsigned)ESP.getFreePsram(),
            usbMounted ? "ok" : "no",
            hidReady ? "ok" : "no",
            touchpad.wasEverMounted() ? "yes" : "no",
            touchpad.hidFailCount(),
            touchpad.hidLastFailReason(),
            btConnected ? "connected" : "disconnected",
            (unsigned long)(millis() / 1000),
            usbMounted ? 1 : 0,
            g_selftestDone ? g_selftestResult.c_str() : "waiting",
            g_selftestDown ? "ok" : "no",
            g_selftestUp ? "ok" : "no",
            g_disconnectCount);
        btReply(buf);

    } else if (cmd == "log") {
        if (log_buf.length() == 0) {
            btReply("ok:log_empty");
        } else {
            char hdr[64];
            snprintf(hdr, sizeof(hdr), "ok:log_len=%d", (int)log_buf.length());
            btReply(hdr);
            delay(100);

            // 分段发送（SPP没有MTU限制，但分段更稳）
            const int CHUNK = 200;
            int totalLen = log_buf.length();
            int sent = 0;
            while (sent < totalLen && SerialBT.hasClient()) {
                int end = sent + CHUNK;
                if (end > totalLen) end = totalLen;
                String chunk = log_buf.substring(sent, end);
                btReply(chunk.c_str());
                sent = end;
                delay(30);
            }
            btReply("[END]");
        }

    } else if (cmd == "ping") {
        char pongBuf[128];
        snprintf(pongBuf, sizeof(pongBuf), "pong:dc=%lu,uptime=%lus",
                 g_disconnectCount, (unsigned long)(millis() / 1000));
        btReply(pongBuf);

    } else if (cmd == "diag") {
        char diagBuf[256];
        snprintf(diagBuf, sizeof(diagBuf),
            "ok:ver=%s,heap=%u,usb=%s,hid=%s,bt=%s,dc=%lu,uptime=%lus,fails=%d",
            FW_VERSION, ESP.getFreeHeap(),
            (bool)USB ? "ok" : "no",
            touchpad.ready() ? "ok" : "no",
            SerialBT.hasClient() ? "conn" : "disc",
            g_disconnectCount,
            (unsigned long)(millis() / 1000),
            touchpad.hidFailCount());
        btReply(diagBuf);

    } else if (cmd == "selftest") {
        runHidSelfTest();
        char stBuf[256];
        snprintf(stBuf, sizeof(stBuf),
            "ok:selftest=%s,down=%s,up=%s,fails=%d",
            g_selftestResult.c_str(),
            g_selftestDown ? "ok" : "no",
            g_selftestUp ? "ok" : "no",
            g_selftestFails);
        btReply(stBuf);

    } else if (cmd == "version") {
        char verBuf[64];
        snprintf(verBuf, sizeof(verBuf), "ok:version=%s,type=SPP,heap=%u",
                 FW_VERSION, ESP.getFreeHeap());
        btReply(verBuf);

    } else {
        btReply("err:unknown_cmd. cmds: tap:x,y,ms | status | log | selftest | ping | diag | version");
    }
}

// ============================================================================
// setup()
// ============================================================================
void setup() {
    WRITE_PERI_REG(RTC_CNTL_BROWN_OUT_REG, 0);

    Serial.begin(115200);
    delay(1000);

    qlog("");
    qlog("========================================================");
    qlog("  QingYun ESP32-S3 SPP+HID Firmware " FW_VERSION);
    qlog("  经典蓝牙SPP + USB HID Touch (No WiFi, No Camera)");
    qlog("========================================================");
    qlog("");

    qlogf("  ESP32-S3 Rev %d | %d MHz | %d cores | SDK %s",
          ESP.getChipRevision(), ESP.getCpuFreqMHz(),
          ESP.getChipCores(), ESP.getSdkVersion());
    qlogf("  Flash: %.1f MB mode=%d speed=%uMHz",
          ESP.getFlashChipSize() / (1024.0f * 1024.0f),
          ESP.getFlashChipMode(), ESP.getFlashChipSpeed());
    qlogf("  PSRAM: %.1f MB (free: %.1f MB)",
          ESP.getPsramSize() / (1024.0f * 1024.0f),
          ESP.getFreePsram() / (1024.0f * 1024.0f));
    qlogf("  Heap: %.1f KB", ESP.getFreeHeap() / 1024.0f);
    qlog("");

    // ---- 经典蓝牙SPP初始化（先启动，不阻塞） ----
    qlog("---- 经典蓝牙SPP Init ----");
    
    // 初始化SPP，设置为可被发现和连接
    if (!SerialBT.begin(BT_DEVICE_NAME)) {
        qlog("[BT] ERROR: BluetoothSerial.begin() failed!");
    } else {
        qlogf("[BT] SPP started as '%s'", BT_DEVICE_NAME);
        qlog("[BT] 配对后系统自动连接，无需App主动发起");
    }

    // ---- USB HID ----
    qlog("---- USB HID Init ----");

    USB.VID(0x303A);
    USB.PID(0x8266);
    USB.manufacturerName("QingYun");
    USB.productName("QingYun Touch Screen");
    USB.serialNumber("QY000001");
    USB.firmwareVersion(0x0100);

    qlogf("[USB] USB vendorID=0x%04X productID=0x%04X (Touch Screen)", USB.VID(), USB.PID());

    qlog("[USB] Calling touchpad.begin()...");
    touchpad.begin();
    qlog("[USB] touchpad.begin() done");

    qlog("[USB] Calling USB.begin()...");
    bool usbResult = USB.begin();
    qlogf("[USB] USB.begin() returned: %s", usbResult ? "true" : "false");

    disableCore0WDT();
    disableCore1WDT();
    qlog("[TWDT] Dual-core Task WDT disabled");

    // USB非阻塞检查（不等待，直接继续）
    bool usbMounted = (bool)USB && touchpad.ready();
    if (usbMounted) {
        qlog("[USB] *** USB Touch Screen already mounted! ***");
    } else {
        qlog("[USB] USB not yet mounted, will work after OTG plug-in");
    }

    qlogf("[Status] Heap after init: %u (%.1f KB)",
          ESP.getFreeHeap(), ESP.getFreeHeap() / 1024.0f);

    qlog("");
    qlog("==========================================");
    qlog("  Setup COMPLETE. Entering loop...");
    qlogf("  BT: '%s' (SPP) - 配对后自动连接", BT_DEVICE_NAME);
    qlog("  >>> Commands: tap:x,y,ms | status | log | ping | diag | version <<<");
    qlogf("  USB: %s | HID: %s | BT: waiting...",
          usbMounted ? "MOUNTED" : "NOT MOUNTED",
          touchpad.ready() ? "READY" : "NOT READY");
    qlog("==========================================");
}

// ============================================================================
// loop()
// ============================================================================
void loop() {
    // ---- 处理蓝牙收到的命令 ----
    if (SerialBT.hasClient()) {
        // 连接状态变化追踪
        if (!g_btConnected) {
            g_btConnected = true;
            g_lastConnectTime = millis();
            qlog("[BT] === Client CONNECTED ===");
        }

        // 读取命令
        if (SerialBT.available()) {
            String cmd = SerialBT.readStringUntil('\n');
            cmd.trim();
            if (cmd.length() > 0) {
                qlogf("[BT] CMD received: len=%d raw='%s'", cmd.length(), cmd.c_str());
                processCommand(cmd);
            }
        }
    } else {
        // 断连检测
        if (g_btConnected) {
            g_btConnected = false;
            g_disconnectCount++;
            g_lastDisconnect = millis();
            qlogf("[BT] === Client DISCONNECTED (dc=%lu) ===", g_disconnectCount);
            qlogf("[BT] Heap=%u uptime=%lus", ESP.getFreeHeap(), (unsigned long)(millis() / 1000));
        }
    }

    // ---- USB状态变化监控 ----
    static bool lastUsbState = false;
    bool curUsbState = (bool)USB;
    if (curUsbState != lastUsbState) {
        qlogf("[USB] State change: %s -> %s",
              lastUsbState ? "MOUNTED" : "not-mounted",
              curUsbState ? "MOUNTED" : "not-mounted");
        lastUsbState = curUsbState;

        // USB刚挂载时自动触发HID自检
        if (curUsbState && !g_selftestDone) {
            runHidSelfTest();
        }
    }

    // ---- 定期状态日志（每5秒） ----
    static unsigned long lastStatusLog = 0;
    if (millis() - lastStatusLog >= 5000) {
        lastStatusLog = millis();
        qlogf("[%s] Heap:%u USB:%s HID:%s BT:%s DC:%lu Uptime:%lus",
              FW_VERSION,
              ESP.getFreeHeap(),
              curUsbState ? "OK" : "NO",
              touchpad.ready() ? "OK" : "NO",
              g_btConnected ? "CONN" : "DISC",
              g_disconnectCount,
              (unsigned long)(millis() / 1000));
    }

    delay(50);  // SPP读取间隔
}
