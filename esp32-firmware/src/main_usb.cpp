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
// v3.0.2: USB PHY强制切换——见setup()中forcePhyToOtg()
// 只用寄存器宏（soc/*_reg.h 纯整数BIT宏，C++安全）；不 include hal/usb_phy_ll.h，
// 因为该头在 IDF4.4/arduino-2.0.8 下含 volatile 结构体拷贝，C++(.cpp)编译必炸。
#include "driver/usb_serial_jtag.h"
#include "soc/usb_wrap_reg.h"
#include "soc/usb_serial_jtag_reg.h"

// ============================================================================
// 常量
// ============================================================================
#define FW_VERSION "v3.0.2"  // v3.0.2: 修复USB PHY被JTAG抢占导致TinyUSB HID无法枚举（卸载JTAG驱动+硬切PHY到OTG）
                            // v3.0.1: 修复GET_REPORT响应字节布局（TinyUSB已填report ID前缀，固件不重复写）

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

    // 主机通过SET_REPORT(Feature)发送命令：report_id=2
    // 运行在TinyUSB回调上下文（main task），可以安全使用critical section
    // 注意：TinyUSB在调用前已剥除report ID前缀（hid_device.c SET_REPORT分支：
    // report_id==report_buf[0]时report_buf++/report_len--），
    // 因此buffer首字节即命令文本，len为文本长度（不含report ID前缀）
    void _onSetFeature(uint8_t report_id, const uint8_t* buffer, uint16_t len) override {
        if (report_id != REPORT_ID_COMMAND || len == 0) return;

        portENTER_CRITICAL(&cmdMux);
        size_t copyLen = len < CMD_BUF_SIZE ? len : CMD_BUF_SIZE;
        memcpy((void*)cmdBuf, buffer, copyLen);
        ((uint8_t*)cmdBuf)[copyLen] = 0;
        cmdPending = true;
        respReady = false;
        memset((void*)respBuf, 0, CMD_BUF_SIZE + 1);
        portEXIT_CRITICAL(&cmdMux);

        Serial.printf("[USB-CMD] RX(%d): %s\n", (int)copyLen, (const char*)cmdBuf);
    }

    // 主机通过GET_REPORT(Feature)读取ACK：report_id=2
    // ★字节布局契约（TinyUSB hid_device.c GET_REPORT分支）：
    //   TinyUSB已在响应首字节填入report ID（*report_buf++ = report_id; xferlen++），
    //   本回调写入的buffer[0]对应主机收到的第2字节，reqlen已减去1字节前缀。
    //   主机(App)最终收到：[0]=report ID(2, TinyUSB填), [1]=状态字节, [2:]=响应文本
    // 返回写入buffer的payload字节数（不含report ID前缀）
    uint16_t _onGetFeature(uint8_t report_id, uint8_t* buffer, uint16_t reqlen) override {
        if (report_id != REPORT_ID_COMMAND) return 0;

        uint16_t respLen = 1;  // payload至少1字节状态
        portENTER_CRITICAL(&cmdMux);
        if (respReady) {
            // respBuf[1]=状态字节('O'成功/'E'错误), respBuf[2:]=响应文本
            size_t textLen = strlen((const char*)respBuf + 2);
            if (reqlen < 1) {
                respLen = 0;  // 不可能发生：TinyUSB保证req_len>1才调用
            } else {
                if (textLen + 1 > reqlen) textLen = reqlen - 1;
                buffer[0] = respBuf[1];
                memcpy(buffer + 1, (const void*)(respBuf + 2), textLen);
                respLen = (uint16_t)(1 + textLen);
            }
        } else if (cmdPending) {
            buffer[0] = 'P';  // processing，命令还在执行
        } else {
            buffer[0] = 'I';  // idle，无待处理命令
        }
        portEXIT_CRITICAL(&cmdMux);
        return respLen;
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
// Feature Report 命令通道说明
// arduino-esp32 2.0.x的USBHID后端把tud_hid_set_report_cb/tud_hid_get_report_cb
// 定义为强符号，内部按report ID路由到USBHIDDevice的_onSetFeature/_onGetFeature。
// 因此命令通道的收发在USBHIDTouchpad类内override这两个虚函数实现，见上方。
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
    // respBuf布局(固件内部): [0]未用(TinyUSB在GET_REPORT响应时自动填report ID前缀),
    //   [1]=状态字节, [2:]=响应文本。App端收到: [0]=report ID(2),[1]=状态,[2:]=文本
    // 状态字节: 文本以"err:"开头→'E'，否则→'O'
    respBuf[1] = (buf[0]=='e' && buf[1]=='r' && buf[2]=='r' && buf[3]==':') ? 'E' : 'O';
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
// v3.0.2: USB PHY 强制切换到 OTG
// ----------------------------------------------------------------------------
// 根因：ESP32-S3 的 USB-Serial-JTAG 与 USB-OTG 共用一颗内部 PHY，由 RTC mux
//   (RTCCNTL.usb_conf.sw_usb_phy_sel) 选择：0=JTAG，1=OTG。
// PIO 预编译 sdkconfig 默认开启 secondary console
//   (CONFIG_ESP_CONSOLE_SECONDARY_USB_SERIAL_JTAG=y)，开机时 JTAG 驱动调用
//   usb_phy_ll_int_jtag_enable() 占用 PHY，并把 CONF0 的 usb_pad_enable/dp_pullup
//   置位——JTAG 控制器始终占着 D+/D- pad 和 D+ 上拉。
// 虽然 tinyusb_driver_install() 内部 usb_hal_init()→usb_ll_int_phy_enable() 会把
//   RTC mux 切到 OTG，但 JTAG 驱动未卸载、pad 仍被其占用，OTG 信号到不了物理层，
//   主机只枚举到 JTAG(303a:1001)，HID(303a:8266) 永远起不来
//   → usb=NO hid=NO fails=0，App 报"USB写失败"。
// 修复：USB.begin() 前，卸载 JTAG 驱动、清掉 JTAG 的 pad/上拉占用，再把 mux 硬切
//   OTG 并使能 OTG pad。寄存器操作与官方 usb_ll_int_phy_enable() 逐位等价
//   (IDF v4.4 components/hal/esp32s3/include/hal/usb_ll.h)。
// ============================================================================
static void forcePhyToOtg() {
    // 1. 卸载 secondary console 注册的 USB-Serial-JTAG VFS 驱动（释放中断/控制器）
    //    未安装时返回 ESP_ERR_INVALID_STATE，忽略即可
    usb_serial_jtag_driver_uninstall();

    // 2. 断开 JTAG 控制器对 USB pad(D+/D-) 的占用并关闭其 D+ 上拉（bit14/bit9），
    //    让出物理层，避免与 OTG 冲突
    CLEAR_PERI_REG_MASK(USB_SERIAL_JTAG_CONF0_REG,
                        USB_SERIAL_JTAG_USB_PAD_ENABLE | USB_SERIAL_JTAG_DP_PULLUP);

    // 3. 把共享 PHY 硬切到 USB-OTG（internal PHY）：
    //    USB_WRAP.otg_conf.pad_enable=1(OTG pad使能, bit18)、phy_sel=0(内部PHY, bit2)；
    //    RTC sw_hw_usb_phy_sel=1(bit20) + sw_usb_phy_sel=1(bit19) → PHY 接 OTG；
    //    RTC usb_pad_enable=1(bit12) 打开 RTC 侧 USB pad 通路（与 OTG pad_enable 双保险）
    SET_PERI_REG_MASK(USB_WRAP_OTG_CONF_REG,
                      USB_WRAP_USB_PAD_ENABLE);
    CLEAR_PERI_REG_MASK(USB_WRAP_OTG_CONF_REG, USB_WRAP_PHY_SEL);
    SET_PERI_REG_MASK(RTC_CNTL_USB_CONF_REG,
                      RTC_CNTL_SW_HW_USB_PHY_SEL | RTC_CNTL_SW_USB_PHY_SEL |
                      RTC_CNTL_USB_PAD_ENABLE);

    // 4. 稍等让总线稳定（主机检测到一次断开/重连）
    delay(50);
}

// ============================================================================
// setup / loop
// ============================================================================
void setup() {
    WRITE_PERI_REG(RTC_CNTL_BROWN_OUT_REG, 0);
    forcePhyToOtg();  // v3.0.2: 必须在 USB.begin() 之前把共享 PHY 切到 OTG
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
    USB.firmwareVersion(0x0302);  // v3.0.2 bcdDevice=0x0302
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
