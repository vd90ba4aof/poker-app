"""
更精确地定位社区牌 - 只在屏幕中央区域找白色卡片
"""
from PIL import Image
import numpy as np
import os

os.chdir(os.path.dirname(os.path.abspath(__file__)))

SRC = "../用户上传/Screenshot_2026-08-05-21-28-13-92_1d58bed7e226cc412b0128cc8fb4cf50.jpg"
img = Image.open(SRC)
arr = np.array(img)

# 社区牌在屏幕中央偏上，牌是纯白色矩形
# 严格白色: R,G,B 都 > 230
strict_white = (arr[:,:,0] > 230) & (arr[:,:,1] > 230) & (arr[:,:,2] > 230)

# 只在中央水平条带搜索
y_start, y_end = 800, 1300
x_start, x_end = 100, 1000

region = strict_white[y_start:y_end, x_start:x_end]

# 按列统计
col_count = region.sum(axis=0)

# 打印前50个最白的列
top_cols = np.argsort(col_count)[-50:]
print("Top 50 whitest columns (relative to x=100):")
for c in sorted(top_cols):
    print(f"  col {c+x_start}: {col_count[c]} white pixels")

print("\n--- Column histogram (every 10px) ---")
for i in range(0, x_end-x_start, 10):
    bar = "#" * (col_count[i] // 5)
    print(f"  x={i+x_start:4d}: {col_count[i]:4d} {bar}")

# 找连续高白色区域（牌）
threshold = 50
above = col_count > threshold
# 找连续段
in_card = False
card_starts = []
card_ends = []
for i, v in enumerate(above):
    if v and not in_card:
        card_starts.append(i + x_start)
        in_card = True
    elif not v and in_card:
        card_ends.append(i + x_start)
        in_card = False
if in_card:
    card_ends.append(x_end)

print(f"\n--- Card regions (>{threshold} white px/col) ---")
cards_found = []
for s, e in zip(card_starts, card_ends):
    w = e - s
    if w > 50:  # 至少50px宽才算一张牌
        cards_found.append((s, e))
        print(f"  Card: x={s} to x={e}, width={w}")
