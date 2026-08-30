/**
 * ============================================================================
 * 青云扑克 ESP32-S3 - USB直连 固件 (v3.2.0)
 * ============================================================================
 *
 * v3.2.0：双接口架构——HID触摸屏接口 + Vendor专用接口，命令走 Vendor bulk 端点
 *   - 接口0：HID触摸屏（class=3），Report ID 1=触摸输入。系统 usbhid 自动绑定，
 *     鼠标光标/触摸功能照常，App 完全不去 claim 它（触摸路径与v3.1.0一致，一字未动）
 *   - 接口1：Vendor专用接口（class=0xFF，arduino USBVendor类，TUD_VENDOR_DESCRIPTOR
 *     自带一对 bulk 端点：ep=OUT、0x80|ep=IN，64字节）。命令通道走 bulk 端点：
 *       App bulk OUT 发 64 字节定长帧：[0:]=命令文本，尾部 zero-fill
 *       固件 bulk IN  回 64 字节定长帧：[0]=状态字节('O'/'E')，[1:]=响应文本，zero-fill
 *     固件 RX：tud_vendor_rx_cb → rx_queue(256B) → loop() 里 vendorIface.read()；
 *     固件 TX：processCommand() 产响应 → vendorIface.write() → IN bulk。
 *     这是 USBVendor 类的主数据流（官方 USBVendor example loop() 即用
 *     Vendor.write()/read() 透传；onRequest 控制回调仅应付 WebUSB 握手）。
 *
 * 版本沿革（均已废弃，仅供考古）：
 *   v3.0.0~v3.0.2：HID Feature Report 命令通道——class+接口定向控制传输被内核
 *     强制 check claim，usbhid 占用 HID 接口 → -EBUSY。证伪。
 *   v3.1.0：Vendor 接口 EP0 vendor 类型控制传输(0x41/0xC1)——枚举/claim/bcd 读取
 *     全正常，但 vendor 控制传输 OUT 即失败、IN 无响应、固件回调不触发，源码理论
 *     全通而实测黑洞。弃用。改走 bulk：Android USB host 最成熟 API（claim 后
 *     bulkTransfer），无控制传输三阶段时序/WebUSB 拦截等坑。
 *
 * Vendor bulk 响应字节契约（64字节定长帧）：
 *   [0]=状态字节 'O'=ok / 'E'=error
 *   [1:]=响应文本（如 "ok:tap(540,1172,50ms)"），尾部 zero-fill
 *   文本上限 63 字节（单包 64 字节内发完，不分包）
 *
 * 硬件：ESP32-S3 N16R8，USB OTG线连扑克手机
 * ============================================================================
 */

#include <Arduino.h>
#include <USB.h>
#include <USBHID.h>
#include <USBVendor.h>   // v3.2.0: Vendor专用接口(class=0xFF) bulk命令通道，需 -DCONFIG_TINYUSB_VENDOR_ENABLED=1
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
#define FW_VERSION "v3.2.2"  // v3.2.2: HID触摸描述符复刻v1.0.31（删Vendor Feature段/Report ID，
                            //         报告id=0无ID前缀发送）——修复Android不注册触摸设备导致点击不生效
                            // v3.2.1: tap非阻塞化（down即ACK，up由loop补发），连发吞吐~5ms/个
                            // v3.2.0: 命令通道走Vendor接口bulk端点（官方example主数据流）
                            // v3.1.0: Vendor EP0控制传输(0x41/0xC1)实测黑洞（枚举/claim/bcd正常，
                            //         控制传输OUT即失败、IN无响应、回调不触发），弃用
                            // v3.0.2: PHY假说已证伪（usb_hal_init本就切PHY），该方向作废
                            // v3.0.1: 修复GET_REPORT响应字节布局（TinyUSB已填report ID前缀，固件不重复写）

#define SCREEN_WIDTH  1080
#define SCREEN_HEIGHT 2344
#define HID_MAX 32767
#define JITTER_PX  5
#define JITTER_MS  20

// v3.2.2: HID触摸描述符不再声明任何Report ID（复刻v1.0.31，触摸报告id=0发送）。
// 命令通道自v3.2.0起完全走Vendor接口bulk端点，HID Feature Report已删除。
#define VENDOR_FRAME_SIZE  64   // bulk端点定长帧：64字节
#define CMD_BUF_SIZE       63   // 命令文本上限63字节（bulk帧64字节，文本从[0]起）

// ============================================================================
// HID 报告描述符
// v3.2.2: 单接口触摸屏（复刻v1.0.31——当年实测Android触摸注入生效的版本）
// ============================================================================
static const uint8_t touch_report_descriptor[] = {
    // ===== 触摸屏输入报告（无Report ID声明，报告id=0发送；与v1.0.31固件二进制逐字节一致） =====
    0x05, 0x0D,             // Usage Page (Digitizers)
    0x09, 0x04,             // Usage (Touch Screen)
    0xA1, 0x01,             // Collection (Application)

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
    0xC0                    // End Collection (Application)
};

// ============================================================================
// 数据结构
// ============================================================================
struct __attribute__((packed)) TouchReport {
    uint8_t  contact_id;
    uint8_t  flags;
    uint16_t x;
    uint16_t y;
};  // v3.2.2: 6字节无report_id前缀——描述符未声明Report ID，SendReport(0,...)不前置ID字节

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
    // v3.2.1: 非阻塞tap状态——down立即发出，up由loop里servicePendingTapUp()到点补发
    bool     _tapUpPending = false;
    uint32_t _tapUpAtMs = 0;

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
            // v3.2.2: id=0——描述符无Report ID声明，库不前置ID字节；6字节整发（复刻v1.0.31）
            if (hid.SendReport(0, (const uint8_t*)&r, sizeof(r))) return true;
            delay(5); yield();
        }
        _failCount++; _lastFailReason = "send_failed";
        return false;
    }

    bool touchDown(uint16_t sx, uint16_t sy) {
        TouchReport r = {};
        r.contact_id = 1;
        r.flags = 0x03;
        r.x = (uint16_t)((uint32_t)sx * HID_MAX / SCREEN_WIDTH);
        r.y = (uint16_t)((uint32_t)sy * HID_MAX / SCREEN_HEIGHT);
        return sendTouchReport(r);
    }
    bool touchUp() {
        TouchReport r = {};
        r.contact_id = 1;
        r.flags = 0x00;
        return sendTouchReport(r);
    }
    // v3.2.1: 非阻塞tap——down立即发出并返回，up由loop的servicePendingTapUp()补发。
    // 连发tap时先补发悬挂up，保证Android看到完整的down→up序列（点与点间距正常）。
    // 按压时长durMs在down成功后即记录，up时刻=down时刻+durMs（±loop粒度10ms）。
    bool tapAsync(uint16_t sx, uint16_t sy, uint32_t durMs) {
        servicePendingTapUp();  // 先抬起上一个（若悬挂且未到点，也立即up——连发场景）
        int16_t jx = (int16_t)(esp_random() % (JITTER_PX * 2 + 1)) - JITTER_PX;
        int16_t jy = (int16_t)(esp_random() % (JITTER_PX * 2 + 1)) - JITTER_PX;
        int16_t ax = (int16_t)sx + jx; if (ax < 0) ax = 0; if (ax >= SCREEN_WIDTH) ax = SCREEN_WIDTH - 1;
        int16_t ay = (int16_t)sy + jy; if (ay < 0) ay = 0; if (ay >= SCREEN_HEIGHT) ay = SCREEN_HEIGHT - 1;
        int32_t ad = (int32_t)durMs + ((int32_t)(esp_random() % (JITTER_MS * 2 + 1)) - JITTER_MS);
        if (ad < 10) ad = 10;
        if (!touchDown((uint16_t)ax, (uint16_t)ay)) { _tapUpPending = false; return false; }
        _tapUpAtMs = millis() + (uint32_t)ad;  // millis()回绕安全：无符号差值比较
        _tapUpPending = true;
        return true;
    }

    // loop每个周期调用：到点补发up（非阻塞，SendReport重试在sendTouchReport内部，
    // 最坏5×delay(5)=25ms只在USB故障时出现；正常一次即成功）
    void servicePendingTapUp() {
        if (!_tapUpPending) return;
        if ((int32_t)(millis() - _tapUpAtMs) >= 0) {
            _tapUpPending = false;
            if (!touchUp()) { _failCount++; _lastFailReason = "up_failed"; }
        }
    }
};

static USBHIDTouchpad touchpad;

// ============================================================================
// v3.2.0: Vendor专用接口（class=0xFF）——bulk 命令通道
// ----------------------------------------------------------------------------
// 全局实例构造时即向TinyUSB注册vendor接口（USBVendor构造函数内
// tinyusb_enable_interface(USB_INTERFACE_VENDOR,...)）。描述符装配顺序按枚举
// USB_INTERFACE_HID(2) < USB_INTERFACE_VENDOR(3)（esp32-hal-tinyusb.h），
// 故配置描述符里 HID=接口0、Vendor=接口1。
// TUD_VENDOR_DESCRIPTOR 自带一对 bulk 端点（ep_num=OUT, 0x80|ep_num=IN, 64B）。
//
// 命令/响应数据通路（bulk 端点，USBVendor 主数据流）：
//   RX：主机 bulk OUT 发64B帧 → TinyUSB tud_vendor_rx_cb → USBVendor._onRX
//       → rx_queue(256B) → loop() 里 vendorIface.read(vendorRxBuf,64) 排空，
//       按null截断入cmdBuf、置cmdPending → processCommand()；
//   TX：processCommand() 经setResponse()把响应写入respBuf（内部布局不变：
//       [1]=状态字节, [2:]=文本），loop() 随后平移成bulk帧respVendor
//       （[0]=状态, [1:]=文本, zero-fill到64）→ vendorIface.write(,64)
//       → tud_vendor_n_write → 主机 bulk IN 读走。
//   不挂 onRequest 控制回调：v3.2.0不使用EP0 vendor控制传输；主机若误发
//   vendor控制请求，USBVendor _onRequest 无cb返回false → TinyUSB STALL，无害。
// ============================================================================
static USBVendor vendorIface;

// bulk通道帧缓冲（64字节定长；与HID Feature时代的cmdBuf/respBuf内部布局分离）
static uint8_t vendorRxBuf[VENDOR_FRAME_SIZE];
static uint8_t respVendor[VENDOR_FRAME_SIZE];
static volatile bool bulkRespPending = false;  // respVendor有帧待发（IN FIFO满时loop重试）

// ============================================================================
// v3.2.2 根因记录（教训：历史已修好的坑不得在重写时回归）
// v3.0.2 曾用 HID Feature Report 做命令通道，描述符里加了 Vendor 页
// (0x06,0x00,0xFF) + Report ID 2 的 63 字节 Feature 段；v3.2.0 命令通道
// 迁至 Vendor 接口 bulk 端点后该段未拆，当时注释误判"不影响枚举，系统不会
// 访问"。实测（v3.2.1，2026-08-30）：bulk 命令通道全通、fails=0、SendReport
// 返回 true，但 Android 不把 if0 注册为触摸输入设备，触摸注入完全不生效。
// 机理：Android 枚举触摸设备时 GET_REPORT 访问 Feature，arduino-esp32 2.0.8
// USBHID 后端 tinyusb_get_device_by_report_id() 未匹配返回 NULL → 回调返回0
// → TinyUSB STALL，设备注册失败。v1.0.31 描述符无此段、无 Report ID 声明，
// 当年实测触摸注入正常。v3.2.2 已复刻 v1.0.31：描述符 68 字节纯净触摸段、
// 报告 id=0 发送、_onSetFeature/_onGetFeature 死回调删除。
// bulk 命令通道（if1, Vendor class）与 HID 描述符无关，零改动。
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
    // respBuf内部布局（自HID时代沿用，v3.2.0不变）：
    //   [0]未用, [1]=状态字节, [2:]=响应文本
    // HID时代GET_REPORT由TinyUSB在[0]填report ID；v3.2.0 bulk在loop()里把
    //   [1]/[2:]平移成帧respVendor[0]/[1:]发出（见loop bulk TX段）。
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
        // v3.2.1: 非阻塞——down成功即回ACK，up由loop在durMs后补发（不阻塞命令通道）
        bool ok = touchpad.tapAsync((uint16_t)x, (uint16_t)y, (uint32_t)dur);
        if (ok) {
            setResponse("ok:tap(%d,%d,%dms)", x, y, dur);
        } else {
            setResponse("err:tap_fail(%s,fails=%d)",
                        touchpad.hidLastFailReason(), touchpad.hidFailCount());
        }

    } else if (strcmp(cmd, "status") == 0) {
        // v3.2.0: 响应必须在单bulk包内（文本≤63字节），字段精简
        setResponse("ok:ver=%s,heap=%u,usb=%s,hid=%s,fails=%d,up=%lus",
                    FW_VERSION,
                    ESP.getFreeHeap(),
                    ((bool)USB) ? "ok" : "no",
                    touchpad.ready() ? "ok" : "no",
                    touchpad.hidFailCount(),
                    (unsigned long)(millis() / 1000));

    } else if (strcmp(cmd, "selftest") == 0) {
        if (!touchpad.ready()) { setResponse("err:hid_not_ready"); return; }
        touchpad.servicePendingTapUp();  // v3.2.1: 先清掉可能悬挂的非阻塞up，避免序列交错
        bool down = touchpad.touchDown(540, 1172);
        delay(50);
        bool up = touchpad.touchUp();
        setResponse("ok:selftest down=%s up=%s fails=%d",
                    down ? "ok" : "no", up ? "ok" : "no", touchpad.hidFailCount());

    } else if (strcmp(cmd, "ping") == 0) {
        setResponse("pong:uptime=%lus,heap=%u", (unsigned long)(millis()/1000), ESP.getFreeHeap());

    } else if (strncmp(cmd, "log", 3) == 0) {
        // log:<offset> — 分段读取日志；v3.2.0单bulk包文本≤63字节，
        // 前缀"ok:log:"占7字节，chunk=54 → 整帧 ≤ 1(状态)+7+54 = 62字节
        int offset = 0;
        if (strlen(cmd) > 4) offset = atoi(cmd + 4);
        if (offset < 0) offset = 0;
        if (offset >= (int)log_buf.length()) {
            setResponse("ok:log_end");
        } else {
            int chunk = 54;
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
    // 1. 卸载 USB-Serial-JTAG 驱动（若有）：关中断、释放 ISR/缓冲。
    //    官方 IDF4.4 注释明确：uninstall 故意不停模块时钟、不清 usb_pad_enable，
    //    PHY/pad 交由调用者处理；未安装时返回 ESP_OK。故下面手动清 pad。
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
    qlog("  QingYun ESP32-S3 USB Firmware " FW_VERSION);
    qlog("  Dual-Interface: HID Touch (if0) + Vendor bulk Cmd (if1)");
    qlog("========================================================");
    qlogf("  Chip Rev %d | %d MHz | %d cores | SDK %s",
          ESP.getChipRevision(), ESP.getCpuFreqMHz(),
          ESP.getChipCores(), ESP.getSdkVersion());
    qlogf("  Flash %.1fMB | PSRAM %.1fMB (free %.1fMB) | Heap %.1fKB",
          ESP.getFlashChipSize()/1048576.0f,
          ESP.getPsramSize()/1048576.0f,
          ESP.getFreePsram()/1048576.0f,
          ESP.getFreeHeap()/1024.0f);

    // USB初始化：HID触摸屏接口 + Vendor命令接口
    // vendorIface.begin() 建rx_queue（256B），必须在 USB.begin() 之前。
    // v3.2.0 不挂 onRequest：命令走 bulk 端点，EP0 vendor 控制传输不再使用
    vendorIface.begin();

    USB.VID(0x303A);
    USB.PID(0x8266);
    USB.manufacturerName("QingYun");
    USB.productName("QingYun Touch Screen");
    USB.serialNumber("QY000001");
    USB.firmwareVersion(0x0322);  // v3.2.2 bcdDevice=0x0322（HID触摸描述符复刻v1.0.31；App门槛0x0320不变，兼容v3.2.0起bulk协议）
    touchpad.begin();
    USB.begin();

    disableCore0WDT();
    disableCore1WDT();

    // 初始化命令/响应缓冲区为idle状态
    memset((void*)cmdBuf, 0, sizeof(cmdBuf));
    memset((void*)respBuf, 0, sizeof(respBuf));

    qlog("[USB] Waiting for host... (USB plug-in detection)");
    qlog("  Commands via Vendor iface(if1) bulk endpoints (64B frames):");
    qlog("    OUT->[cmd text, zero-fill] / IN<-[status byte, text, zero-fill]");
    qlog("    tap:x,y,ms | status | selftest | ping | log[:offset]");
}

void loop() {
    // ===== v3.2.1: 非阻塞tap抬起服务（每loop先跑，≤10ms粒度）=====
    // 到点补发touchUp，不阻塞命令通道；连发时由tapAsync()自行提前补发。
    touchpad.servicePendingTapUp();

    // ===== v3.2.0 bulk RX：从 Vendor 接口 OUT bulk 端点收命令帧 =====
    // App 发 64 字节定长帧：[0:]=命令文本，尾部 zero-fill。
    // available()<0 = rx_queue未建（begin未调用，不应发生）；read(buf,64)
    // 排空rx_queue返回字节数（一帧64B由tud_vendor_rx_cb整包入队，一次排空）。
    int avail = vendorIface.available();
    if (avail > 0) {
        // read返回int：>=0为字节数，-1为rx_queue异常（avail>0时不会发生）
        int n = (int)vendorIface.read(vendorRxBuf, VENDOR_FRAME_SIZE);
        if (n > 0) {
            // App已zero-fill；这里按null截断并强制补null，命令文本上限63字节
            vendorRxBuf[VENDOR_FRAME_SIZE - 1] = 0;
            size_t cmdLen = strnlen((const char*)vendorRxBuf, VENDOR_FRAME_SIZE);
            if (cmdLen > CMD_BUF_SIZE) cmdLen = CMD_BUF_SIZE;
            portENTER_CRITICAL(&cmdMux);
            memcpy((void*)cmdBuf, vendorRxBuf, cmdLen);
            ((uint8_t*)cmdBuf)[cmdLen] = 0;
            cmdPending = true;
            respReady = false;
            bulkRespPending = false;  // 丢弃尚未发出的上一帧（仅在IN FIFO满时可能存在）
            memset((void*)respBuf, 0, CMD_BUF_SIZE + 1);
            portEXIT_CRITICAL(&cmdMux);
            Serial.printf("[BULK-CMD] RX(%u): %s\n", (unsigned)cmdLen, (const char*)cmdBuf);
        }
    }

    // ===== 命令执行（main loop，不阻塞USB） =====
    if (cmdPending) {
        char cmdLocal[CMD_BUF_SIZE + 1];
        portENTER_CRITICAL(&cmdMux);
        memcpy(cmdLocal, (const void*)cmdBuf, CMD_BUF_SIZE);
        cmdLocal[CMD_BUF_SIZE] = 0;
        cmdPending = false;
        portEXIT_CRITICAL(&cmdMux);

        if (strlen(cmdLocal) > 0) {
            processCommand(cmdLocal);
            // 组装 bulk IN 响应帧：respBuf[1]=状态, [2:]=文本（setResponse布局）
            // → respVendor[0]=状态, [1:]=文本，尾部zero-fill，交loop下方TX段发出
            portENTER_CRITICAL(&cmdMux);
            if (respReady) {
                size_t textLen = strlen((const char*)respBuf + 2);
                if (textLen > VENDOR_FRAME_SIZE - 1) textLen = VENDOR_FRAME_SIZE - 1;
                respVendor[0] = respBuf[1];
                memcpy(respVendor + 1, (const void*)(respBuf + 2), textLen);
                memset(respVendor + 1 + textLen, 0, VENDOR_FRAME_SIZE - 1 - textLen);
                bulkRespPending = true;
            }
            portEXIT_CRITICAL(&cmdMux);
        }
    }

    // ===== v3.2.0 bulk TX：把响应帧从 Vendor IN bulk 端点发出 =====
    // write(,64) 非阻塞：IN FIFO（TinyUSB vendor TX buffer，64B）满时返回0，
    // 帧留在respVendor里下个loop重试；FIFO空时整包64B入队（全有或全无）。
    // 已知边角：sendTapFast连发且App不读IN时，FIFO里最多积1帧，新命令到来会
    // 丢弃未发帧（上方RX段 bulkRespPending=false）——fast点击不读ACK，无影响。
    if (bulkRespPending && vendorIface.mounted()) {
        size_t w = vendorIface.write(respVendor, VENDOR_FRAME_SIZE);
        if (w == VENDOR_FRAME_SIZE) {
            bulkRespPending = false;
            Serial.printf("[BULK-CMD] TX: %c %s\n",
                          respVendor[0], (const char*)(respVendor + 1));
        }
        // w==0：IN FIFO满，下轮重试，不阻塞
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
