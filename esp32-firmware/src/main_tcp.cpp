/**
 * ============================================================================
 * 青云扑克 ESP32-S3 - WiFi TCP + USB HID 固件 (v2.0.0)
 * ============================================================================
 *
 * 从BLE迁移到WiFi TCP：
 *   - ESP32连手机热点，做TCP客户端，连手机192.168.43.1:8888
 *   - TCP自带重传/排序/连接检测，彻底解决BLE断连问题
 *   - 每条命令执行后回ACK，App确认后才继续
 *
 * 硬件：ESP32-S3 N16R8，USB OTG线连扑克手机
 *
 * 指令格式（\n分隔）：
 *   tap:x,y,duration  → 执行触摸点击 → ok:tap(x,y,ms)
 *   status            → 查询设备状态   → ok:ver=...,...
 *   selftest          → HID自检       → ok:selftest=...
 *   diag              → 诊断信息       → ok:...
 *   log               → 获取日志       → 分段发送
 *
 * v2.0.0：BLE → WiFi TCP
 *   - 删除全部BLE代码（GATT/NUS/广播/扫描）
 *   - WiFi STA连手机热点
 *   - TCP客户端连手机网关IP:8888
 *   - 断线无限重连，指数退避30s封顶
 *   - 每5秒发心跳 hb:
 *   - 命令执行后立即回ACK
 *   - 保留全部USB HID触摸屏功能（抖动/自检/坐标转换）
 */

#include <Arduino.h>
#include <USB.h>
#include <USBHID.h>
#include <WiFi.h>
#include "soc/soc.h"
#include "soc/rtc_cntl_reg.h"

// ============================================================================
// 常量配置
// ============================================================================
#define FW_VERSION "v2.0.0"

// WiFi配置（手机热点）
#define WIFI_SSID "OnePlus 13T CA92"
#define WIFI_PASS "Juh123000QY"
#define TCP_PORT 8888

// 屏幕分辨率（一加13T）
#define SCREEN_WIDTH  1080
#define SCREEN_HEIGHT 2344
#define HID_MAX 32767
#define JITTER_PX  5
#define JITTER_MS  20
#define HID_REPORT_ID_TOUCH 0

// TCP重连参数
#define RECONNECT_DELAY_MIN  1000L   // 1秒
#define RECONNECT_DELAY_MAX  30000L  // 30秒
#define HEARTBEAT_INTERVAL   5000L   // 5秒
#define TCP_READ_TIMEOUT_MS  3000    // connect超时

// ============================================================================
// 日志缓冲区
// ============================================================================
static String log_buf = "";
static const size_t LOG_BUF_MAX = 6144;
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
// 与v1.0.31~v1.0.39完全一致，Android可识别为触摸屏
// ============================================================================
static const uint8_t touch_report_descriptor[] = {
    0x05, 0x0D,             // Usage Page (Digitizers)
    0x09, 0x04,             // Usage (Touch Screen)
    0xA1, 0x01,             // Collection (Application)
    0x09, 0x22,             //   Usage (Finger)
    0xA1, 0x02,             //   Collection (Logical)

    // Contact Identifier
    0x09, 0x51,
    0x15, 0x00,
    0x25, 0x01,
    0x75, 0x08,
    0x95, 0x01,
    0x81, 0x02,

    // Tip Switch + In Range + padding
    0x09, 0x42,
    0x09, 0x32,
    0x15, 0x00,
    0x25, 0x01,
    0x75, 0x01,
    0x95, 0x02,
    0x81, 0x02,
    0x95, 0x06,
    0x81, 0x03,

    // X
    0x05, 0x01,
    0x09, 0x30,
    0x15, 0x00,
    0x26, 0xFF, 0x7F,
    0x75, 0x10,
    0x95, 0x01,
    0x81, 0x02,

    // Y
    0x09, 0x31,
    0x26, 0xFF, 0x7F,
    0x75, 0x10,
    0x95, 0x01,
    0x81, 0x02,

    0xC0,
    0xC0
};

struct __attribute__((packed)) TouchReport {
    uint8_t  contact_id;
    uint8_t  flags;
    uint16_t x;
    uint16_t y;
};

// ============================================================================
// USB HID 触控设备类（与v1.0.39完全一致）
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

    void begin() { hid.begin(); }
    bool ready() { bool r = hid.ready(); if (r) _everMounted = true; return r; }
    bool wasEverMounted() const { return _everMounted; }
    int hidFailCount() const { return _failCount; }
    const char* hidLastFailReason() const { return _lastFailReason; }

    uint16_t _onGetDescriptor(uint8_t* buffer) override {
        memcpy(buffer, touch_report_descriptor, sizeof(touch_report_descriptor));
        return sizeof(touch_report_descriptor);
    }

    bool touchDown(uint16_t screenX, uint16_t screenY) {
        yield();
        if (!hid.ready()) { _failCount++; _lastFailReason = "not_ready"; return false; }
        _report.contact_id = 1;
        _report.flags = 0x03;
        _report.x = (uint16_t)((uint32_t)screenX * HID_MAX / SCREEN_WIDTH);
        _report.y = (uint16_t)((uint32_t)screenY * HID_MAX / SCREEN_HEIGHT);
        Serial.printf("[HID] down raw(%u,%u) hid(%u,%u)\n", screenX, screenY, _report.x, _report.y);
        for (int retry = 0; retry < 5; retry++) {
            if (hid.SendReport(HID_REPORT_ID_TOUCH, &_report, sizeof(_report))) return true;
            delay(10); yield();
        }
        _failCount++; _lastFailReason = "send_failed";
        return false;
    }

    bool touchUp() {
        yield();
        if (!hid.ready()) { _failCount++; _lastFailReason = "not_ready"; return false; }
        _report.contact_id = 1;
        _report.flags = 0x00;
        for (int retry = 0; retry < 5; retry++) {
            if (hid.SendReport(HID_REPORT_ID_TOUCH, &_report, sizeof(_report))) return true;
            delay(10); yield();
        }
        _failCount++; _lastFailReason = "send_failed";
        return false;
    }

    bool tap(uint16_t screenX, uint16_t screenY, uint32_t durationMs) {
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

        Serial.printf("[TAP] raw=(%u,%u) adj=(%d,%d) dur=%dms\n", screenX, screenY, adjX, adjY, adjDur);

        if (!touchDown((uint16_t)adjX, (uint16_t)adjY)) return false;
        delay(adjDur);
        return touchUp();
    }
};

static USBHIDTouchpad touchpad;

// ============================================================================
// HID 自检
// ============================================================================
static bool g_selftestDone = false;
static bool g_selftestDown = false;
static bool g_selftestUp = false;
static int g_selftestFails = 0;
static String g_selftestResult = "waiting";

static void runHidSelfTest() {
    qlog("[SELFTEST] Starting HID self-test...");
    g_selftestDone = false;
    g_selftestDown = false;
    g_selftestUp = false;
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
        return;
    }
    delay(50);
    g_selftestUp = touchpad.touchUp();
    g_selftestFails = touchpad.hidFailCount();
    g_selftestDone = true;

    if (g_selftestUp) {
        g_selftestResult = "ok";
        qlog("[SELFTEST] PASS");
    } else {
        g_selftestResult = "up_failed";
        qlogf("[SELFTEST] FAIL: touchUp failed (fails=%d)", g_selftestFails);
    }
}

// ============================================================================
// WiFi TCP 通信
// ============================================================================
static WiFiClient tcpClient;
static volatile bool tcpConnected = false;
static unsigned long lastTcpActivity = 0;
static unsigned long lastHeartbeat = 0;
static uint32_t heartbeatCount = 0;
static uint32_t disconnectCount = 0;
static unsigned long lastConnectAttempt = 0;
static long reconnectDelay = RECONNECT_DELAY_MIN;
static IPAddress serverIP;

// TCP发送（带\n分隔符）
static bool tcpSend(const char* msg) {
    if (!tcpConnected || !tcpClient.connected()) return false;
    size_t len = strlen(msg);
    size_t sent = tcpClient.write((const uint8_t*)msg, len);
    tcpClient.write('\n');
    tcpClient.flush();
    if (sent == len) {
        lastTcpActivity = millis();
        return true;
    }
    return false;
}

static bool tcpSendf(const char* fmt, ...) {
    char buf[512];
    va_list args;
    va_start(args, fmt);
    vsnprintf(buf, sizeof(buf), fmt, args);
    va_end(args);
    return tcpSend(buf);
}

// 处理命令
static void processCommand(const String& cmd) {
    if (cmd.startsWith("tap:")) {
        String params = cmd.substring(4);
        int c1 = params.indexOf(',');
        int c2 = params.indexOf(',', c1 + 1);

        if (c1 > 0 && c2 > c1) {
            int x = params.substring(0, c1).toInt();
            int y = params.substring(c1 + 1, c2).toInt();
            int dur = params.substring(c2 + 1).toInt();
            if (dur < 10) dur = 50;

            if (x < 0 || x >= SCREEN_WIDTH || y < 0 || y >= SCREEN_HEIGHT) {
                tcpSendf("err:coords_out_of_range(x:0-%d,y:0-%d,got=%d,%d)",
                         SCREEN_WIDTH - 1, SCREEN_HEIGHT - 1, x, y);
                return;
            }

            bool ok = touchpad.tap(x, y, dur);
            if (ok) {
                tcpSendf("ok:tap(%d,%d,%dms)", x, y, dur);
            } else {
                tcpSend("err:tap_fail(hid_send_failed)");
            }
        } else {
            tcpSend("err:bad_format,use:tap:x,y,ms");
        }

    } else if (cmd == "status") {
        bool usbMounted = (bool)USB;
        bool hidReady = touchpad.ready();
        tcpSendf("ok:ver=%s,heap=%u,psram=%u,usb=%s,hid=%s,ever=%s,fails=%d,reason=%s,"
                 "wifi=%s,ip=%s,rssi=%d,uptime=%lus,mnt=%d,st=%s,st_down=%s,st_up=%s,dc=%lu",
                 FW_VERSION,
                 ESP.getFreeHeap(),
                 (unsigned)ESP.getFreePsram(),
                 usbMounted ? "ok" : "no",
                 hidReady ? "ok" : "no",
                 touchpad.wasEverMounted() ? "yes" : "no",
                 touchpad.hidFailCount(),
                 touchpad.hidLastFailReason(),
                 WiFi.status() == WL_CONNECTED ? "connected" : "disconnected",
                 WiFi.localIP().toString().c_str(),
                 WiFi.RSSI(),
                 (unsigned long)(millis() / 1000),
                 usbMounted ? 1 : 0,
                 g_selftestDone ? g_selftestResult.c_str() : "waiting",
                 g_selftestDown ? "ok" : "no",
                 g_selftestUp ? "ok" : "no",
                 disconnectCount);

    } else if (cmd == "log") {
        if (log_buf.length() == 0) {
            tcpSend("ok:log_empty");
        } else {
            tcpSendf("ok:log_len=%d", (int)log_buf.length());
            delay(50);
            const int CHUNK = 480;  // TCP无MTU限制，一次发更多
            int totalLen = log_buf.length();
            int sent = 0;
            while (sent < totalLen && tcpClient.connected()) {
                int end = sent + CHUNK;
                if (end > totalLen) end = totalLen;
                String chunk = log_buf.substring(sent, end);
                tcpSend(chunk.c_str());
                sent = end;
                delay(20);
            }
            tcpSend("[END]");
        }

    } else if (cmd == "diag") {
        tcpSendf("ok:ver=%s,heap=%u,usb=%s,hid=%s,wifi=%s,ip=%s,rssi=%d,hb=%lu,dc=%lu,uptime=%lus,fails=%d",
                 FW_VERSION, ESP.getFreeHeap(),
                 (bool)USB ? "ok" : "no",
                 touchpad.ready() ? "ok" : "no",
                 WiFi.status() == WL_CONNECTED ? "conn" : "disc",
                 WiFi.localIP().toString().c_str(),
                 WiFi.RSSI(),
                 heartbeatCount, disconnectCount,
                 (unsigned long)(millis() / 1000),
                 touchpad.hidFailCount());

    } else if (cmd == "selftest") {
        runHidSelfTest();
        tcpSendf("ok:selftest=%s,down=%s,up=%s,fails=%d",
                 g_selftestResult.c_str(),
                 g_selftestDown ? "ok" : "no",
                 g_selftestUp ? "ok" : "no",
                 g_selftestFails);

    } else if (cmd == "ping") {
        tcpSendf("pong:hb=%lu,dc=%lu,uptime=%lus,rssi=%d",
                 heartbeatCount, disconnectCount,
                 (unsigned long)(millis() / 1000), WiFi.RSSI());

    } else {
        tcpSend("err:unknown_cmd. cmds: tap:x,y,ms | status | log | selftest | ping | diag");
    }
}

// WiFi+TCP连接维护
static void maintainConnection() {
    // WiFi未连接
    if (WiFi.status() != WL_CONNECTED) {
        if (tcpConnected) {
            tcpConnected = false;
            tcpClient.stop();
            qlog("[WiFi] Disconnected");
        }
        if (millis() - lastConnectAttempt > (unsigned long)reconnectDelay) {
            lastConnectAttempt = millis();
            if (reconnectDelay < RECONNECT_DELAY_MAX) {
                reconnectDelay = min(reconnectDelay * 2, RECONNECT_DELAY_MAX);
            }
            qlogf("[WiFi] Connecting to '%s'...", WIFI_SSID);
            WiFi.reconnect();
        }
        return;
    }

    // WiFi已连接，TCP未连接
    if (!tcpConnected || !tcpClient.connected()) {
        if (tcpConnected) {
            tcpConnected = false;
            tcpClient.stop();
            disconnectCount++;
            qlogf("[TCP] Disconnected (dc=%lu)", disconnectCount);
        }
        if (millis() - lastConnectAttempt > (unsigned long)reconnectDelay) {
            lastConnectAttempt = millis();
            if (reconnectDelay < RECONNECT_DELAY_MAX) {
                reconnectDelay = min(reconnectDelay * 2, RECONNECT_DELAY_MAX);
            }
            serverIP = WiFi.gatewayIP();
            qlogf("[TCP] Connecting to %s:%d...", serverIP.toString().c_str(), TCP_PORT);
            if (tcpClient.connect(serverIP, TCP_PORT, TCP_READ_TIMEOUT_MS)) {
                tcpConnected = true;
                reconnectDelay = RECONNECT_DELAY_MIN;
                lastTcpActivity = millis();
                qlogf("[TCP] Connected to %s:%d!", serverIP.toString().c_str(), TCP_PORT);
                tcpSendf("hello:esp32,ver=%s,ip=%s,rssi=%d",
                         FW_VERSION, WiFi.localIP().toString().c_str(), WiFi.RSSI());
            } else {
                qlog("[TCP] Connect failed, will retry");
            }
        }
        return;
    }

    // TCP已连接，读取数据
    while (tcpClient.available()) {
        String line = tcpClient.readStringUntil('\n');
        line.trim();
        if (line.length() > 0) {
            lastTcpActivity = millis();
            qlogf("[TCP] RX: %s", line.c_str());
            processCommand(line);
        }
    }

    // 心跳
    if (millis() - lastHeartbeat >= HEARTBEAT_INTERVAL) {
        lastHeartbeat = millis();
        heartbeatCount++;
        tcpSendf("hb:%lu,heap=%u,hid=%s,usb=%s,rssi=%d,dc=%lu",
                 heartbeatCount,
                 ESP.getFreeHeap(),
                 touchpad.ready() ? "ok" : "no",
                 (bool)USB ? "ok" : "no",
                 WiFi.RSSI(),
                 disconnectCount);
    }
}

// ============================================================================
// setup()
// ============================================================================
void setup() {
    WRITE_PERI_REG(RTC_CNTL_BROWN_OUT_REG, 0);

    Serial.begin(115200);
    delay(2000);

    qlog("");
    qlog("========================================================");
    qlog("  QingYun ESP32-S3 WiFi TCP + USB HID Firmware " FW_VERSION);
    qlog("  WiFi TCP (port 8888) + USB HID Touch");
    qlog("========================================================");
    qlog("");

    qlogf("  ESP32-S3 Rev %d | %d MHz | %d cores | SDK %s",
          ESP.getChipRevision(), ESP.getCpuFreqMHz(),
          ESP.getChipCores(), ESP.getSdkVersion());
    qlogf("  Flash: %.1f MB | PSRAM: %.1f MB (free: %.1f MB)",
          ESP.getFlashChipSize() / (1024.0f * 1024.0f),
          ESP.getPsramSize() / (1024.0f * 1024.0f),
          ESP.getFreePsram() / (1024.0f * 1024.0f));
    qlogf("  Heap: %.1f KB", ESP.getFreeHeap() / 1024.0f);
    qlog("");

    // ---- USB HID ----
    qlog("---- USB HID Init ----");
    USB.VID(0x303A);
    USB.PID(0x8266);
    USB.manufacturerName("QingYun");
    USB.productName("QingYun Touch Screen");
    USB.serialNumber("QY000001");
    USB.firmwareVersion(0x0200);  // v2.0

    touchpad.begin();
    bool usbResult = USB.begin();
    qlogf("[USB] begin()=%s | mounted=%s | hid=%s",
          usbResult ? "true" : "false",
          ((bool)USB) ? "YES" : "no",
          touchpad.ready() ? "READY" : "not-ready");

    disableCore0WDT();
    disableCore1WDT();

    // 等待USB挂载（最多15秒，同时开始WiFi连接）
    qlog("[USB] Waiting for USB mount (15s)...");
    int waitCount = 0;
    bool wasMounted = false;
    while (waitCount < 150) {
        delay(100);
        waitCount++;
        bool nowMounted = (bool)USB && touchpad.ready();
        if (nowMounted && !wasMounted) {
            qlogf("[USB] *** MOUNTED at %d.%ds! ***", waitCount / 10, waitCount % 10);
            runHidSelfTest();
        }
        wasMounted = nowMounted;
    }

    if ((bool)USB && touchpad.ready()) {
        qlog("[USB] *** SUCCESS: USB Touch Screen MOUNTED! ***");
    } else {
        qlog("[USB] WARNING: Host not detected after 15s (can plug in later)");
    }

    // ---- WiFi Init ----
    qlog("---- WiFi Init ----");
    WiFi.mode(WIFI_STA);
    WiFi.setSleep(false);  // 关闭省电，降低延迟
    WiFi.begin(WIFI_SSID, WIFI_PASS);
    qlogf("[WiFi] Connecting to '%s'...", WIFI_SSID);

    // 等待WiFi连接（最多10秒）
    int wifiWait = 0;
    while (WiFi.status() != WL_CONNECTED && wifiWait < 100) {
        delay(100);
        wifiWait++;
        if (wifiWait % 20 == 0) {
            qlogf("[WiFi] Waiting... status=%d", WiFi.status());
        }
    }

    if (WiFi.status() == WL_CONNECTED) {
        serverIP = WiFi.gatewayIP();
        qlogf("[WiFi] Connected! IP=%s Gateway=%s RSSI=%ddBm",
              WiFi.localIP().toString().c_str(),
              serverIP.toString().c_str(),
              WiFi.RSSI());
    } else {
        qlog("[WiFi] Connection timeout, will keep retrying in background");
    }

    qlog("");
    qlog("==========================================");
    qlog("  Setup COMPLETE. Entering loop...");
    qlogf("  WiFi: %s | TCP target: %s:%d",
          WiFi.status() == WL_CONNECTED ? WiFi.localIP().toString().c_str() : "connecting...",
          serverIP.toString().c_str(), TCP_PORT);
    qlogf("  USB: %s | HID: %s",
          ((bool)USB) ? "MOUNTED" : "NOT MOUNTED",
          touchpad.ready() ? "READY" : "NOT READY");
    qlog("  Commands: tap:x,y,ms | status | log | ping | diag | selftest");
    qlog("==========================================");
}

// ============================================================================
// loop()
// ============================================================================
void loop() {
    maintainConnection();

    // USB状态监控
    static bool lastUsbState = false;
    static int loopCounter = 0;
    loopCounter++;
    bool curUsbState = (bool)USB;
    if (curUsbState != lastUsbState) {
        qlogf("[USB] State change: %s -> %s",
              lastUsbState ? "MOUNTED" : "not-mounted",
              curUsbState ? "MOUNTED" : "not-mounted");
        lastUsbState = curUsbState;
        if (curUsbState && !g_selftestDone) {
            runHidSelfTest();
        }
    }

    // 状态日志（每10秒）
    if (loopCounter % 100 == 0) {
        qlogf("[%s] heap=%u usb=%s hid=%s wifi=%s tcp=%s rssi=%d hb=%lu dc=%lu",
              FW_VERSION,
              ESP.getFreeHeap(),
              curUsbState ? "OK" : "NO",
              touchpad.ready() ? "OK" : "NO",
              WiFi.status() == WL_CONNECTED ? "OK" : "NO",
              tcpConnected ? "OK" : "NO",
              WiFi.RSSI(),
              heartbeatCount, disconnectCount);
    }

    delay(100);
}
