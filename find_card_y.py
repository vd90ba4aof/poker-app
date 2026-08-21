"""
检测完整牌面 y 范围 - 用更宽松的阈值
"""
from PIL import Image
import numpy as np
import os

os.chdir(os.path.dirname(os.path.abspath(__file__)))

SRC = "../用户上传/Screenshot_2026-08-05-21-28-13-92_1d58bed7e226cc412b0128cc8fb4cf50.jpg"
img = Image.open(SRC)
arr = np.array(img)

# 检测亮色区域（牌面白色 + 近白色）
bright = (arr[:,:,0] > 180) & (arr[:,:,1] > 180) & (arr[:,:,2] > 180)

# 在第一张牌 x 范围 (170~306) 内检测行的亮色像素
col_range = bright[:, 180:290]
row_count = col_range.sum(axis=1)

print("Row brightness in x=180~290:")
for y in range(700, 1400, 10):
    bar = "#" * (row_count[y] // 3)
    print(f"  y={y:4d}: {row_count[y]:4d} {bar}")

# 找连续高亮行
threshold = 40
above = row_count > threshold
in_card = False
card_y_starts = []
card_y_ends = []
for i, v in enumerate(above):
    if v and not in_card:
        card_y_starts.append(i)
        in_card = True
    elif not v and in_card:
        card_y_ends.append(i)
        in_card = False
if in_card:
    card_y_ends.append(len(above))

print(f"\nCard y-ranges (threshold={threshold}):")
for s, e in zip(card_y_starts, card_y_ends):
    if e - s > 50:
        print(f"  y={s} to y={e}, height={e-s}")
