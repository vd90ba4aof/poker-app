/**
 * ============================================================================
 * 青云扑克 ESP32-S3 - USB直连 + HID触摸屏 固件 (v3.0.0)
 * ============================================================================
 *
 * v3.0.0：WiFi TCP → USB直连（HID Feature Report）
 *   - 删除WiFi/TCP全部代码
 *   - 单接口HID触摸屏：Report ID 1 = 触摸输入，Report ID 2 = Feature命令通道
 *   - 命令通道走Endpoint 0 control transfer（SET_REPORT/GET_REPORT）
 *   - 不新增USB接口/端点，对外只表现为普通USB触摸屏
 *   - 手机端不需要root，用Android UsbManager API
 *
 * 命令协议（Report ID = 0x02，64字节，首字节为report ID）：
 *   SET_REPORT: 手机→ESP32 发送命令文本（如 "tap:540,1172,50"）
 *   GET_REPORT: 手机←ESP32 读取ACK响应（如 "ok:tap(540,1172,50ms)"）
 *
 *   状态字节约定（GET_REPORT第2字节）：
 *     'P' = processing，命令还在执行
 *     'O' = ok，响应就绪
 *     'E' = error，响应就绪
 *
 * 硬件：ESP32-S3 N16R8，USB OTG线连扑克手机
 * ============================================================================
 */

#include <Arduino.h>
#include <USB.h>
#include <USBHID.h>
#include "soc/soc.h"
#include "soc/rtc_cntl_reg.h"
#include "esp_random.h"

// ============================================================================
// 常量
// ============================================================================
#define FW_VERSION "v3.0.0"

#define SCREEN_WIDTH  1080
#define SCREEN_HEIGHT 2344
#define HID_MAX 32767
#define JITTER_PX  5
#define JITTER_MS  20

// Report IDs
#define REPORT_ID_TOUCH    0x01
#define REPORT_ID_COMMAND  0x02
#define CMD_BUF_SIZE       63   // 64字节report减去1字节report ID

// ============================================================================
// HID 报告描述符
// 单接口触摸屏 + 一个Feature Report（伪装成vendor配置页）
// ============================================================================
static const uint8_t touch_report_descriptor[] = {
    // ===== Report ID 1: 触摸屏输入（与v1.0.39~v2.0.0完全一致） =====
    0x05, 0x0D,             // Usage Page (Digitizers)
    0x09, 0x04,             // Usage (Touch Screen)
    0xA1, 0x01,             // Collection (Application)

    0x85, REPORT_ID_TOUCH,  //   Report ID (1)

    0x09, 0x22,             //   Usage (Finger)
    0xA1, 0x02,             //   Collection (Logical)

    0x09, 0x51,             //     Contact Identifier
    0x15, 0x00,
    0x25, 0x01,
    0x75, 0x08,
    0x95, 0x01,
    0x81, 0x02,

    0x09, 0x42,             //     Tip Switch
    0x09, 0x32,             //     In Range
    0x15, 0x00,
    0x25, 0x01,
    0x75, 0x01,
    0x95, 0x02,
    0x81, 0x02,
    0x95, 0x06,
    0x81, 0x03,

    0x05, 0x01,             //     Usage Page (Generic Desktop)
    0x09, 0x30,             //     Usage X
    0x15, 0x00,
    0x26, 0xFF, 0x7F,
    0x75, 0x10,
    0x95, 0x01,
    0x81, 0x02,

    0x09, 0x31,             //     Usage Y
    0x26, 0xFF, 0x7F,
    0x75, 0x10,
    0x95, 0x01,
    0x81, 0x02,

    0xC0,                   //   End Collection (Logical)

    // ===== Report ID 2: Feature Report（双向命令通道，vendor-defined配置） =====
    // 对外看起来像触摸屏的厂商配置/校准数据通道，工业触摸屏常见做法
    // SET_REPORT(Feature) = 手机写命令；GET_REPORT(Feature) = 手机读ACK
    0x06, 0x00, 0xFF,       //   Usage Page (Vendor Defined 0xFF00)
    0x85, REPORT_ID_COMMAND,//   Report ID (2)
    0x09, 0x01,             //   Usage (Vendor 1)
    0x15, 0x00,
    0x26, 0xFF, 0x00,
    0x75, 0x08,
    0x95, CMD_BUF_SIZE,     //   63 bytes
    0xB1, 0x02,             //   Feature (Data,Var,Abs)

    0xC0                    // End Collection (Application)
};

// ============================================================================
// 数据结构
// ============================================================================
struct __attribute__((packed)) TouchReport {
    uint8_t  report_id;
    uint8_t  contact_id;
    uint8_t  flags;
    uint16_t x;
    uint16_t y;
};

// 命令/响应缓冲区（由USB ISR和main loop共享）
static volatile uint8_t cmdBuf[CMD_BUF_SIZE + 1];  // +1 for null
static volatile uint8_t respBuf[CMD_BUF_SIZE + 1];
static volatile bool    cmdPending = false;
static volatile bool    respReady = false;
static portMUX_TYPE     cmdMux = portMUX_INITIALIZER_UNLOCKED;

// ============================================================================
// 日志缓冲
// ============================================================================
static String log_buf = "";
static const size_t LOG_BUF_MAX = 4096;

static void qlog(const char* msg) {
    Serial.println(msg);
    if (log_buf.length() < LOG_BUF_MAX) { log_buf += msg; log_buf += '\n'; }
}
static void qlogf(const char* fmt, ...) {
    char buf[256];
    va_list args; va_start(args, fmt);
    vsnprintf(buf, sizeof(buf), fmt, args);
    va_end(args);
    Serial.println(buf);
    if (log_buf.length() < LOG_BUF_MAX) { log_buf += buf; log_buf += '\n'; }
}

// ============================================================================
// USB HID 触摸屏
// ============================================================================
class USBHIDTouchpad : public USBHIDDevice {
private:
    USBHID hid;
    bool _everMounted = false;
    int  _failCount = 0;
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
    int  hidFailCount() const { return _failCount; }
    const char* hidLastFailReason() const { return _lastFailReason; }

    uint16_t _onGetDescriptor(uint8_t* buffer) override {
        memcpy(buffer, touch_report_descriptor, sizeof(touch_report_descriptor));
        return sizeof(touch_report_descriptor);
    }

    bool sendTouchReport(const TouchReport& r) {
        yield();
        if (!hid.ready()) { _failCount++; _lastFailReason = "not_ready"; return false; }
        for (int retry = 0; retry < 5; retry++) {
            if (hid.SendReport(REPORT_ID_TOUCH, (const uint8_t*)&r + 1, sizeof(r) - 1)) return true;
            delay(5); yield();
        }
        _failCount++; _lastFailReason = "send_failed";
        return false;
    }

    bool touchDown(uint16_t sx, uint16_t sy) {
        TouchReport r = {};
        r.report_id = REPORT_ID_TOUCH;
        r.contact_id = 1;
        r.flags = 0x03;
        r.x = (uint16_t)((uint32_t)sx * HID_MAX / SCREEN_WIDTH);
        r.y = (uint16_t)((uint32_t)sy * HID_MAX / SCREEN_HEIGHT);
        return sendTouchReport(r);
    }
    bool touchUp() {
        TouchReport r = {};
        r.report_id = REPORT_ID_TOUCH;
        r.contact_id = 1;
        r.flags = 0x00;
        return sendTouchReport(r);
    }
    bool tap(uint16_t sx, uint16_t sy, uint32_t durMs) {
        int16_t jx = (int16_t)(esp_random() % (JITTER_PX * 2 + 1)) - JITTER_PX;
        int16_t jy = (int16_t)(esp_random() % (JITTER_PX * 2 + 1)) - JITTER_PX;
        int16_t ax = (int16_t)sx + jx; if (ax < 0) ax = 0; if (ax >= SCREEN_WIDTH) ax = SCREEN_WIDTH - 1;
        int16_t ay = (int16_t)sy + jy; if (ay < 0) ay = 0; if (ay >= SCREEN_HEIGHT) ay = SCREEN_HEIGHT - 1;
        int32_t ad = (int32_t)durMs + ((int32_t)(esp_random() % (JITTER_MS * 2 + 1)) - JITTER_MS);
        if (ad < 10) ad = 10;
        if (!touchDown((uint16_t)ax, (uint16_t)ay)) return false;
        delay(ad);
        return touchUp();
    }
};

static USBHIDTouchpad touchpad;

// ============================================================================
// TinyUSB HID 回调（接收SET_REPORT / 响应GET_REPORT）
// arduino-esp32的USBHID后端把tud_hid_*_report_cb定义为weak，
// 我们在sketch里override它们。
// ============================================================================
extern "C" {

// 主机通过SET_REPORT(Output)发送命令：report_id=2
uint16_t tud_hid_set_report_cb(uint8_t instance, uint8_t report_id,
                               hid_report_type_t report_type,
                               const uint8_t* buffer, uint16_t size) {
    if (report_id != REPORT_ID_COMMAND) return size;  // 忽略其他report
    // 只处理Output类型（主机→设备的命令通道）
    if (report_type != HID_REPORT_TYPE_OUTPUT &&
        report_type != HID_REPORT_TYPE_FEATURE) {
        return size;
    }

    // buffer第0字节是report ID（取决于TinyUSB配置）
    // 为安全起见，自动跳过开头的report ID
    const uint8_t* data = buffer;
    uint16_t dataLen = size;
    if (size > 0 && buffer[0] == REPORT_ID_COMMAND) {
        data = buffer + 1;
        dataLen = size - 1;
    }

    if (dataLen == 0) return size;

    portENTER_CRITICAL(&cmdMux);
    size_t copyLen = dataLen < CMD_BUF_SIZE ? dataLen : CMD_BUF_SIZE;
    memcpy((void*)cmdBuf, data, copyLen);
    ((uint8_t*)cmdBuf)[copyLen] = 0;
    cmdPending = true;
    respReady = false;
    memset((void*)respBuf, 0, CMD_BUF_SIZE + 1);
    portEXIT_CRITICAL(&cmdMux);

    Serial.printf("[USB-CMD] RX(%d): %s\n", copyLen, (const char*)cmdBuf);
    return size;
}

// 主机通过GET_REPORT(Feature)读取ACK：report_id=2
uint16_t tud_hid_get_report_cb(uint8_t instance, uint8_t report_id,
                               hid_report_type_t report_type,
                               uint8_t* buffer, uint16_t reqlen) {
    if (report_id != REPORT_ID_COMMAND) return 0;

    uint16_t respLen = 0;
    portENTER_CRITICAL(&cmdMux);
    if (respReady) {
        respBuf[1] = 'O';  // [0]=report ID, [1]=status
        respLen = strlen((const char*)respBuf + 1) + 2;
        if (respLen > reqlen) respLen = reqlen;
        buffer[0] = REPORT_ID_COMMAND;
        memcpy(buffer + 1, (const void*)(respBuf + 1), respLen - 1);
    } else if (cmdPending) {
        buffer[0] = REPORT_ID_COMMAND;
        buffer[1] = 'P';  // processing
        respLen = 2;
    } else {
        buffer[0] = REPORT_ID_COMMAND;
        buffer[1] = 'I';  // idle
        respLen = 2;
    }
    portEXIT_CRITICAL(&cmdMux);
    return respLen;
}

} // extern "C"

// ============================================================================
// 命令处理（在main loop中执行，不阻塞USB ISR）
// ============================================================================
static void setResponse(const char* fmt, ...) {
    char buf[CMD_BUF_SIZE];
    va_list args; va_start(args, fmt);
    vsnprintf(buf, sizeof(buf), fmt, args);
    va_end(args);
    portENTER_CRITICAL(&cmdMux);
    size_t len = strlen(buf);
    if (len >= CMD_BUF_SIZE - 1) len = CMD_BUF_SIZE - 2;
    // respBuf[0]保留给report ID，由get_report_cb填充
    respBuf[1] = '?';  // 临时状态
    memcpy((void*)(respBuf + 2), buf, len);
    ((uint8_t*)respBuf)[2 + len] = 0;
    respReady = true;
    portEXIT_CRITICAL(&cmdMux);
    Serial.printf("[USB-CMD] TX: %s\n", buf);
}

static void processCommand(const char* cmd) {
    if (strncmp(cmd, "tap:", 4) == 0) {
        const char* p = cmd + 4;
        int x = atoi(p);
        const char* c1 = strchr(p, ',');
        if (!c1) { setResponse("err:bad_tap_format"); return; }
        int y = atoi(c1 + 1);
        const char* c2 = strchr(c1 + 1, ',');
        int dur = 50;
        if (c2) dur = atoi(c2 + 1);
        if (dur < 10) dur = 50;

        if (x < 0 || x >= SCREEN_WIDTH || y < 0 || y >= SCREEN_HEIGHT) {
            setResponse("err:coords(%d,%d)", x, y);
            return;
        }
        bool ok = touchpad.tap((uint16_t)x, (uint16_t)y, (uint32_t)dur);
        if (ok) {
            setResponse("ok:tap(%d,%d,%dms)", x, y, dur);
        } else {
            setResponse("err:tap_fail(%s,fails=%d)",
                        touchpad.hidLastFailReason(), touchpad.hidFailCount());
        }

    } else if (strcmp(cmd, "status") == 0) {
        setResponse("ok:ver=%s,heap=%u,psram=%u,usb=%s,hid=%s,ever=%s,fails=%d,reason=%s,uptime=%lus",
                    FW_VERSION,
                    ESP.getFreeHeap(),
                    (unsigned)ESP.getFreePsram(),
                    ((bool)USB) ? "ok" : "no",
                    touchpad.ready() ? "ok" : "no",
                    touchpad.wasEverMounted() ? "yes" : "no",
                    touchpad.hidFailCount(),
                    touchpad.hidLastFailReason(),
                    (unsigned long)(millis() / 1000));

    } else if (strcmp(cmd, "selftest") == 0) {
        if (!touchpad.ready()) { setResponse("err:hid_not_ready"); return; }
        bool down = touchpad.touchDown(540, 1172);
        delay(50);
        bool up = touchpad.touchUp();
        setResponse("ok:selftest down=%s up=%s fails=%d",
                    down ? "ok" : "no", up ? "ok" : "no", touchpad.hidFailCount());

    } else if (strcmp(cmd, "ping") == 0) {
        setResponse("pong:uptime=%lus,heap=%u", (unsigned long)(millis()/1000), ESP.getFreeHeap());

    } else if (strncmp(cmd, "log", 3) == 0) {
        // log:<offset> — 分段读取日志，每段60字节
        int offset = 0;
        if (strlen(cmd) > 4) offset = atoi(cmd + 4);
        if (offset < 0) offset = 0;
        if (offset >= (int)log_buf.length()) {
            setResponse("ok:log_end");
        } else {
            int chunk = 60;
            if (offset + chunk > (int)log_buf.length()) chunk = log_buf.length() - offset;
            String part = log_buf.substring(offset, offset + chunk);
            setResponse("ok:log:%s", part.c_str());
        }

    } else {
        setResponse("err:unknown_cmd");
    }
}

// ============================================================================
// setup / loop
// ============================================================================
void setup() {
    WRITE_PERI_REG(RTC_CNTL_BROWN_OUT_REG, 0);
    Serial.begin(115200);
    delay(1500);

    qlog("");
    qlog("========================================================");
    qlog("  QingYun ESP32-S3 USB直连 HID Firmware " FW_VERSION);
    qlog("  Single-Interface HID Touch Screen + Feature Cmd Channel");
    qlog("========================================================");
    qlogf("  Chip Rev %d | %d MHz | %d cores | SDK %s",
          ESP.getChipRevision(), ESP.getCpuFreqMHz(),
          ESP.getChipCores(), ESP.getSdkVersion());
    qlogf("  Flash %.1fMB | PSRAM %.1fMB (free %.1fMB) | Heap %.1fKB",
          ESP.getFlashChipSize()/1048576.0f,
          ESP.getPsramSize()/1048576.0f,
          ESP.getFreePsram()/1048576.0f,
          ESP.getFreeHeap()/1024.0f);

    // USB HID初始化
    USB.VID(0x303A);
    USB.PID(0x8266);
    USB.manufacturerName("QingYun");
    USB.productName("QingYun Touch Screen");
    USB.serialNumber("QY000001");
    USB.firmwareVersion(0x0300);
    touchpad.begin();
    USB.begin();

    disableCore0WDT();
    disableCore1WDT();

    // 初始化命令/响应缓冲区为idle状态
    memset((void*)cmdBuf, 0, sizeof(cmdBuf));
    memset((void*)respBuf, 0, sizeof(respBuf));

    qlog("[USB] Waiting for host... (USB plug-in detection)");
    qlog("  Commands via HID Feature Report ID 2:");
    qlog("    tap:x,y,ms | status | selftest | ping | log[:offset]");
}

void loop() {
    // 处理来自USB的待执行命令
    if (cmdPending) {
        char cmdLocal[CMD_BUF_SIZE + 1];
        portENTER_CRITICAL(&cmdMux);
        memcpy(cmdLocal, (const void*)cmdBuf, CMD_BUF_SIZE);
        cmdLocal[CMD_BUF_SIZE] = 0;
        cmdPending = false;
        portEXIT_CRITICAL(&cmdMux);

        if (strlen(cmdLocal) > 0) {
            processCommand(cmdLocal);
        }
    }

    // USB挂载监控（首挂载时自检）
    static bool lastMounted = false;
    static unsigned long lastStatusLog = 0;
    bool mounted = (bool)USB && touchpad.ready();
    if (mounted != lastMounted) {
        qlogf("[USB] State: %s -> %s",
              lastMounted ? "MOUNTED" : "not-mounted",
              mounted ? "MOUNTED" : "not-mounted");
        lastMounted = mounted;
    }

    if (millis() - lastStatusLog > 10000) {
        lastStatusLog = millis();
        qlogf("[%s] heap=%u usb=%s hid=%s fails=%d",
              FW_VERSION, ESP.getFreeHeap(),
              mounted ? "OK" : "NO",
              touchpad.ready() ? "OK" : "NO",
              touchpad.hidFailCount());
    }

    delay(10);  // 主循环10ms粒度，命令延迟≤10ms
}
