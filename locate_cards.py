"""
先定位社区牌的精确位置
"""
from PIL import Image
import numpy as np
import os

os.chdir(os.path.dirname(os.path.abspath(__file__)))

SRC = "../用户上传/Screenshot_2026-08-05-21-28-13-92_1d58bed7e226cc412b0128cc8fb4cf50.jpg"
img = Image.open(SRC)
arr = np.array(img)
print(f"Image: {img.size}")

# 社区牌是白色的卡片，在绿色背景上
# 通过检测白色/浅色区域来定位
# 先裁出可能的社区牌区域（屏幕中部）
# 从截图看，社区牌大约在 y=800-1200 范围

# 检测白色像素（牌面）
# 白色: R>200, G>200, B>200
white_mask = (arr[:,:,0] > 200) & (arr[:,:,1] > 200) & (arr[:,:,2] > 200)

# 只在中间区域找
search_y_start, search_y_end = 700, 1300
search_x_start, search_x_end = 0, 1080

region_white = white_mask[search_y_start:search_y_end, search_x_start:search_x_end]

# 按列统计白色像素数量
col_white_count = region_white.sum(axis=0)

# 找到白色密集的列范围（牌的区域）
threshold = 20  # 至少20个白色像素的列
white_cols = np.where(col_white_count > threshold)[0]

if len(white_cols) > 0:
    print(f"White columns range: {white_cols[0]} to {white_cols[-1]} (relative to x={search_x_start})")
    print(f"Absolute x range: {search_x_start + white_cols[0]} to {search_x_start + white_cols[-1]}")
    
    # 找列的间断点来分割各张牌
    gaps = np.diff(white_cols)
    gap_positions = np.where(gaps > 5)[0]
    
    print(f"\nNumber of gap positions: {len(gap_positions)}")
    
    card_x_ranges = []
    start_idx = 0
    for gap_pos in gap_positions:
        card_start = search_x_start + white_cols[start_idx]
        card_end = search_x_start + white_cols[gap_pos]
        card_x_ranges.append((card_start, card_end))
        start_idx = gap_pos + 1
    
    # 最后一张牌
    card_start = search_x_start + white_cols[start_idx]
    card_end = search_x_start + white_cols[-1]
    card_x_ranges.append((card_start, card_end))
    
    print(f"\nDetected {len(card_x_ranges)} card x-ranges:")
    for i, (cs, ce) in enumerate(card_x_ranges):
        print(f"  Card {i+1}: x={cs} to x={ce}, width={ce-cs}")

# 找y范围
row_white_count = region_white.sum(axis=1)
white_rows = np.where(row_white_count > 20)[0]
if len(white_rows) > 0:
    print(f"\nWhite rows range (relative): {white_rows[0]} to {white_rows[-1]}")
    print(f"Absolute y range: {search_y_start + white_rows[0]} to {search_y_start + white_rows[-1]}")
