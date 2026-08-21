#!/usr/bin/env python3
"""用新截图验证当前 Kotlin 形状分析 + y35/y40 宽度决策树"""
from PIL import Image
import numpy as np
import os

PATH = "/app/data/所有对话/主对话/用户上传/Screenshot_2026-06-13-02-20-54-87_1d58bed7e226cc412b0128cc8fb4cf50.jpg"
OUTPUT = "/app/data/所有对话/主对话/poker-app-latest/verification_output"
os.makedirs(OUTPUT, exist_ok=True)

img = Image.open(PATH)
W, H = img.size
np_img = np.array(img)
print(f"截图: {W}×{H}")

def crop(x1_pct, y1_pct, x2_pct, y2_pct, name=""):
    x1, y1 = int(x1_pct * W), int(y1_pct * H)
    x2, y2 = int(x2_pct * W), int(y2_pct * H)
    region = np_img[y1:y2, x1:x2]
    if name:
        Image.fromarray(region).save(f"{OUTPUT}/{name}.png")
    return region

# ========== 第一步：定位每张牌的精确坐标 ==========
gray = np_img.mean(axis=2)
white_mask = gray > 220

print("\n=== 扫描公共牌区域 (y=40%-60%) ===")
for y_test in [0.42, 0.43, 0.44, 0.45, 0.46, 0.48, 0.50, 0.52, 0.54, 0.56]:
    y = int(y_test * H)
    row = white_mask[y, :]
    runs = []
    in_run = False
    start = 0
    for x in range(W):
        if row[x] and not in_run:
            in_run = True; start = x
        elif not row[x] and in_run:
            in_run = False
            if x - start > 30:
                runs.append((start, x, x-start))
    if runs:
        print(f"  y={y_test:.2f}({y}px): {runs[:8]}")

print("\n=== 扫描手牌区域 (y=70%-90%) ===")
for y_test in [0.72, 0.74, 0.76, 0.78, 0.80, 0.82, 0.84]:
    y = int(y_test * H)
    row = white_mask[y, :]
    runs = []
    in_run = False
    start = 0
    for x in range(W):
        if row[x] and not in_run:
            in_run = True; start = x
        elif not row[x] and in_run:
            in_run = False
            if x - start > 30:
                runs.append((start, x, x-start))
    if runs:
        print(f"  y={y_test:.2f}({y}px): {runs[:8]}")

# ========== 第二步：底池验证 ==========
print("\n=== 底池验证 (y=35%-42%) ===")
pot = crop(0.25, 0.35, 0.75, 0.42, "pot_new")
r = pot[:,:,0].astype(int); g = pot[:,:,1].astype(int); b = pot[:,:,2].astype(int)
yellow = int(((r>200)&(g>150)&(b<100)).sum())
white = int(((r>200)&(g>200)&(b>200)).sum())
print(f"黄色={yellow} 白色={white}")
print("✅ 底池命中" if white>50 else "⚠️ 底池未命中")
