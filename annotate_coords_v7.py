from PIL import Image, ImageDraw, ImageFont
import json, os

img_path = "/app/data/所有对话/主对话/用户上传/Screenshot_2026-06-13-02-33-09-28_1d58bed7e226cc412b0128cc8fb4cf50.jpg"
img = Image.open(img_path).convert("RGBA")
draw = ImageDraw.Draw(img)

font_path = None
for p in ["/usr/share/fonts/truetype/droid/DroidSansFallbackFull.ttf",
          "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
          "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc"]:
    if os.path.exists(p):
        font_path = p
        break
font_md = ImageFont.truetype(font_path, 18) if font_path else ImageFont.load_default()

elements = {
    # ===== 公共牌 5张 =====
    "公共牌1_8♣": [180, 1030, 325, 1290, "公共牌1: 8♣", (255, 200, 0)],
    "公共牌2_5♠": [325, 1030, 460, 1290, "公共牌2: 5♠", (255, 200, 0)],
    "公共牌3_7♦": [460, 1030, 595, 1290, "公共牌3: 7♦", (255, 200, 0)],
    "公共牌4_5": [595, 1030, 730, 1290, "公共牌4: 5♣", (255, 200, 0)],
    "公共牌5_7♥": [730, 1030, 870, 1290, "公共牌5: 7♥", (255, 200, 0)],
    # ===== 手牌 2张 =====
    "手牌1_K♣": [75, 1760, 200, 2000, "手牌1: K♣", (0, 255, 100)],
    "手牌2_Q♦": [170, 1760, 310, 2000, "手牌2: Q♦", (0, 255, 100)],
    # ===== 6名玩家 =====
    "HeinGeerken1_name":  [440, 500, 620, 540,  "HeinGeerken1",  (0, 200, 255)],
    "HeinGeerken1_chips": [460, 545, 620, 580,  "5,100",         (0, 200, 255)],
    "Nass__name":         [10,  870, 200, 910,  "Nass_",         (0, 200, 255)],
    "Nass__chips":        [10,  910, 200, 950,  "24,553",        (0, 200, 255)],
    "hhhxxxxxxx_name":    [810, 850, 1030, 890, "hhhxxxxxxx",    (0, 200, 255)],
    "hhhxxxxxxx_chips":   [860, 895, 1030, 935, "5,000",         (0, 200, 255)],
    "Lunde@1_name":       [10,  1440, 200, 1480,"Lunde@1",       (0, 200, 255)],
    "Lunde@1_chips":      [25,  1485, 200, 1520,"7,139",         (0, 200, 255)],
    "pralomge29_name":    [800, 1470, 1010, 1510,"pralomge29",   (0, 200, 255)],
    "pralomge29_chips":   [830, 1515, 1010, 1555,"15,611",       (0, 200, 255)],
    "zhcyc_name":         [135, 1920, 265, 1960,"zhcyc(Hero)",   (0, 200, 255)],
    "zhcyc_chips":        [170, 1965, 295, 2005,"2,492",         (0, 200, 255)],
    # ===== 底池 =====
    "底池_label":  [415, 955, 540, 1000, "底池",  (255, 100, 100)],
    "底池_amount": [430, 995, 550, 1050, "2,200", (255, 100, 100)],
    # ===== 下注按钮 - 再往下移130px =====
    "下注100%": [730, 1690, 1080, 1820, "100%下注2,200", (255, 150, 0)],
    "下注75%":  [730, 1885, 1080, 2005, "75%下注1,650",  (255, 150, 0)],
    "下注50%":  [730, 2020, 1080, 2140, "50%下注1,100",  (255, 150, 0)],
    "下注33%":  [730, 2195, 1080, 2320, "33%下注726",    (255, 150, 0)],
    # ===== 底部操作按钮 =====
    "让牌/弃牌": [20,  2190, 370, 2340, "让牌/弃牌", (255, 150, 0)],
    "让牌":     [390, 2190, 710, 2340, "让牌",      (255, 150, 0)],
    # ===== D(dealer) =====
    "D(dealer)": [920, 1340, 980, 1390, "D(dealer)", (255, 255, 0)],
    # ===== 顶部导航 =====
    "KQ提示":  [155, 130, 305, 220,  "K♦Q♣提示", (200, 200, 200)],
    "暂停按钮": [375, 140, 455, 210,  "暂停",     (200, 200, 200)],
    "关闭按钮": [480, 140, 555, 210,  "关闭",     (200, 200, 200)],
    "首页按钮": [580, 140, 660, 210,  "首页",     (200, 200, 200)],
    "+号按钮":  [685, 140, 765, 210,  "+",        (200, 200, 200)],
}

for name, (x1, y1, x2, y2, label, color) in elements.items():
    draw.rectangle([x1, y1, x2, y2], outline=color, width=3)
    text_bbox = draw.textbbox((0, 0), label, font=font_md)
    tw = text_bbox[2] - text_bbox[0]
    th = text_bbox[3] - text_bbox[1]
    label_y = max(0, y1 - th - 4)
    draw.rectangle([x1, label_y, x1 + tw + 8, label_y + th + 4], fill=color)
    draw.text((x1 + 4, label_y + 2), label, fill=(0, 0, 0), font=font_md)

stats = [
    "6名玩家 | 公共牌5张 | 手牌2张",
    "4个下注按钮 + 让牌/弃牌 + 让牌",
    "底池: 2,200 | 青云悬浮按钮已排除",
]
for i, s in enumerate(stats):
    draw.text((10, 10 + i * 26), s, fill=(255, 255, 0), font=font_md)

out_path = "/app/data/所有对话/主对话/poker-app-latest/annotated_coords.jpg"
img.convert("RGB").save(out_path, "JPEG", quality=92)
print(f"Saved: {out_path}")

coord_json = {name: {"bbox": [x1, y1, x2, y2], "label": label}
              for name, (x1, y1, x2, y2, label, _) in elements.items()}
with open("/app/data/所有对话/主对话/poker-app-latest/coords.json", "w") as f:
    json.dump(coord_json, f, ensure_ascii=False, indent=2)
print("Saved coords.json")
