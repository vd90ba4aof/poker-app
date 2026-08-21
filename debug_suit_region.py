"""
调试：可视化一张公共牌的区域，找到大花色符号的位置
"""
import os
from PIL import Image, ImageDraw

UPLOAD_DIR = "/app/data/所有对话/主对话/用户上传"

# 用图1 公共牌 3♥ 测试
filepath = os.path.join(UPLOAD_DIR, "Screenshot_2026-06-12-22-45-58-77_1d58bed7e226cc412b0128cc8fb4cf50.jpg")
img = Image.open(filepath)
sw, sh = img.size
print(f"Image size: {sw}x{sh}")

# 公共牌0 在 (180, 1030) to (325, 1290) 基准坐标
BASE_W, BASE_H = 1080, 2344

def sc(v, base, screen):
    return int(v * screen / base)

bx1, bx2 = 180, 325
by1, by2 = 1030, 1290
sx1, sx2 = sc(bx1, BASE_W, sw), sc(bx2, BASE_W, sw)
sy1, sy2 = sc(by1, BASE_H, sh), sc(by2, BASE_H, sh)

print(f"Card0 region: ({sx1},{sy1}) to ({sx2},{sy2})")

# 裁剪显示
crop = img.crop((sx1, sy1, sx2, sy2))
crop.save("/app/data/所有对话/主对话/poker-app-latest/debug_card0.png")

# 在图中标记不同区域
draw_img = img.copy()
draw = ImageDraw.Draw(draw_img)
# 标记整牌区域
draw.rectangle([sx1, sy1, sx2, sy2], outline="red", width=3)

# 标记花色子区域候选：排除上半 rank 区域
# rank indicator 大约占 35% 高度
rank_end = sy1 + int((sy2 - sy1) * 0.35)
draw.line([(sx1, rank_end), (sx2, rank_end)], fill="yellow", width=2)

# 花色分析区域：rank_end 到 sy2
suit_y1 = rank_end
suit_y2 = sy2
draw.rectangle([sx1, suit_y1, sx2, suit_y2], outline="green", width=2)

draw_img.save("/app/data/所有对话/主对话/poker-app-latest/debug_card0_marked.png")
print("Saved debug_card0.png and debug_card0_marked.png")

# 分析花色子区域内的像素分布
pixels = list(crop.convert("RGB").getdata())
cw, ch = crop.size
print(f"Crop size: {cw}x{ch}")

# 分行打印红色/黑色像素分布
for y in range(0, ch, 8):
    red_count = 0
    black_count = 0
    for x in range(cw):
        p = pixels[y * cw + x]
        cr, cg, cb = p
        if cr > 130 and cr - cg > 45 and cr - cb > 45:
            red_count += 1
        elif cr < 70 and cg < 70 and cb < 70 and abs(cg - cr) < 30:
            black_count += 1
    bar_r = "█" * (red_count // 3)
    bar_b = "" * (black_count // 3)
    print(f"  y={y:3d}: R={red_count:4d} {bar_r}  B={black_count:4d} {bar_b}")
