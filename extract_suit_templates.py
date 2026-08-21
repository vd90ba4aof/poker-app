"""
提取花色 indicator 模板（最终正确坐标版）
社区牌: 136x192, y=1075~1267
"""
from PIL import Image
import numpy as np
import os

os.chdir(os.path.dirname(os.path.abspath(__file__)))

SRC = "../用户上传/Screenshot_2026-08-05-21-28-13-92_1d58bed7e226cc412b0128cc8fb4cf50.jpg"
OUT_DIR = "templates"

img = Image.open(SRC)

board_y = 1075
board_h = 192

cards = [
    ("spade",   "3♠",  170, 306),
    ("club",    "A♣",  320, 456),
    ("diamond", "10♦", 470, 606),
    ("heart",   "A♥",  620, 756),
]

for suit_name, card_label, x0, x1 in cards:
    card = img.crop((x0, board_y, x1, board_y + board_h))
    cw, ch = card.size
    print(f"{card_label}: card size={cw}x{ch}")
    card.save(f"{OUT_DIR}/card_{suit_name}.png")
    
    # indicator: 左上 ~30% 宽, ~30% 高
    ind_w = int(cw * 0.32)  # ~43px
    ind_h = int(ch * 0.30)  # ~57px
    ind = card.crop((2, 2, ind_w, ind_h))
    ind.save(f"{OUT_DIR}/indicator_{suit_name}.png")
    print(f"  indicator: {ind.size}")
    
    # suit symbol only: indicator 的下半部分
    # 在 indicator 中，rank 数字在上半，suit 符号在下半
    suit_y0 = int(ind_h * 0.50)
    suit = ind.crop((0, suit_y0, ind_w, ind_h))
    suit.save(f"{OUT_DIR}/suit_only_{suit_name}.png")
    print(f"  suit_only: {suit.size}")

print("\nDone!")
