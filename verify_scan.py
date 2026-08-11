"""扫描每张牌完整的垂直宽度分布，找出 rank 和 suit 分界线"""
from PIL import Image
import numpy as np
import os

PATH = "/app/data/所有对话/主对话/用户上传/Screenshot_2026-06-13-02-20-54-87_1d58bed7e226cc412b0128cc8fb4cf50.jpg"
img = Image.open(PATH)
W, H = img.size
np_img = np.array(img)

def crop(x1_pct, y1_pct, x2_pct, y2_pct):
    x1,y1 = int(x1_pct*W), int(y1_pct*H)
    x2,y2 = int(x2_pct*W), int(y2_pct*H)
    return np_img[y1:y2, x1:x2]

def scan_vertical(region, name, label=""):
    """输出每5%行的宽度"""
    r = region[:,:,0].astype(int)
    g = region[:,:,1].astype(int)
    b = region[:,:,2].astype(int)
    red = ((r>130)&(r-g>50)&(r-b>50))
    black = ((r<90)&(g<90)&(b<90))
    colored = red | black
    sh, sw = colored.shape
    row_w = colored.sum(axis=1)
    print(f"\n  {name} ({sw}×{sh}px) total={int(colored.sum())}:")
    print(f"    row_width: ", end="")
    for i in range(0, sh, max(1, sh//20)):
        pct = i/sh*100
        w = row_w[i]
        if w > 0:
            print(f"y{pct:.0f}%={w}", end=" ")
    print()
    # 打印每10%的宽度
    print(f"    10%步长: ", end="")
    for i in range(0, sh, max(1, sh//10)):
        pct = i/sh*100
        w = row_w[i]
        print(f"{pct:.0f}%={w}", end=" ")
    print()

# 公共牌
cards = [
    ("8♣", 0.158, 0.445, 0.280, 0.545),
    ("8♥", 0.296, 0.445, 0.419, 0.545),
    ("A",  0.437, 0.445, 0.559, 0.545),
    ("3♦", 0.576, 0.445, 0.698, 0.545),
    ("9",  0.715, 0.445, 0.837, 0.545),
]

print("=== 公共牌垂直扫描 (仅suit区域 y=35%-75%) ===")
for name, x1, y1, x2, y2 in cards:
    region = crop(x1, y1, x2, y2)
    ch, cw = region.shape[:2]
    # 只取 suit 区域
    sy1 = int(ch*0.35); sy2 = int(ch*0.75)
    suit = region[sy1:sy2, :int(cw*0.65)]
    scan_vertical(suit, name)

# 手牌
print("\n=== 手牌垂直扫描 ===")
hand_cards = [
    ("7♠", 0.046, 0.746, 0.148, 0.873),
    ("7♦", 0.167, 0.746, 0.279, 0.873),
]
for name, x1, y1, x2, y2 in hand_cards:
    region = crop(x1, y1, x2, y2)
    ch, cw = region.shape[:2]
    sy1 = int(ch*0.45); sy2 = int(ch*0.75)
    suit = region[sy1:sy2, :int(cw*0.65)]
    scan_vertical(suit, name)

# 同时用旧截图对比
print("\n\n=== 旧截图对比 (K♣ Q♦ 8♣ 5♠ 7♦ 5♣ 7♥) ===")
PATH2 = "/app/data/所有对话/主对话/用户上传/Screenshot_2026-06-13-02-33-09-28_1d58bed7e226cc412b0128cc8fb4cf50.jpg"
img2 = Image.open(PATH2)
np_img2 = np.array(img2)
W2, H2 = img2.size

def crop2(x1_pct, y1_pct, x2_pct, y2_pct):
    x1,y1 = int(x1_pct*W2), int(y1_pct*H2)
    x2,y2 = int(x2_pct*W2), int(y2_pct*H2)
    return np_img2[y1:y2, x1:x2]

old_cards = [
    ("K♣", 0.046, 0.746, 0.171, 0.873, True),
    ("Q♦", 0.161, 0.746, 0.300, 0.873, True),
    ("8♣", 0.157, 0.459, 0.283, 0.541, False),
    ("5♠", 0.296, 0.459, 0.421, 0.541, False),
    ("7♦", 0.440, 0.459, 0.561, 0.541, False),
    ("5♣", 0.579, 0.459, 0.704, 0.541, False),
    ("7♥", 0.718, 0.459, 0.843, 0.541, False),
]
for name, x1, y1, x2, y2, is_hand in old_cards:
    region = crop2(x1, y1, x2, y2)
    ch, cw = region.shape[:2]
    sy1 = int(ch*0.45) if is_hand else int(ch*0.35)
    sy2 = int(ch*0.75)
    suit = region[sy1:sy2, :int(cw*0.65)]
    r = suit[:,:,0].astype(int); g = suit[:,:,1].astype(int); b = suit[:,:,2].astype(int)
    colored = ((r>130)&(r-g>50)&(r-b>50)) | ((r<90)&(g<90)&(b<90))
    sh, sw = colored.shape
    row_w = colored.sum(axis=1)
    print(f"  {name} ({sw}×{sh}): ", end="")
    for i in range(0, sh, max(1, sh//10)):
        print(f"{i/sh*100:.0f}%={row_w[i]}", end=" ")
    print()
