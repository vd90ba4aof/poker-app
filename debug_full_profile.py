"""
找出大花色符号的精确位置
"""
import os
from PIL import Image

UPLOAD_DIR = "/app/data/所有对话/主对话/用户上传"
BASE_W, BASE_H = 1080, 2344

def load_jpg_pixels(path):
    img = Image.open(path)
    w, h = img.size
    return list(img.convert("RGB").getdata()), w, h

def get_pixel(pixels, w, x, y):
    h = len(pixels) // w
    if 0 <= x < w and 0 <= y < h: return pixels[y * w + x]
    return None

def full_profile(pixels, sw, sh, x1, y1, x2, y2, is_red, label):
    reg_w = x2 - x1; reg_h = y2 - y1
    row_widths = [0] * reg_h
    for y in range(reg_h):
        for x in range(reg_w):
            p = get_pixel(pixels, sw, x1 + x, y1 + y)
            if p is None: continue
            cr, cg, cb = p
            if is_red: hit = cr > 130 and cr - cg > 45 and cr - cb > 45
            else: hit = cr < 70 and cg < 70 and cb < 70 and abs(cg - cr) < 30
            if hit: row_widths[y] += 1

    max_w = max(row_widths) if row_widths else 1
    print(f"\n{label} (reg_h={reg_h}, max={max_w}):")
    for y in range(0, reg_h, 4):
        bar = "█" * (row_widths[y] // 3)
        ratio = row_widths[y] / max_w if max_w > 0 else 0
        print(f"  y={y:3d}/{reg_h}: {row_widths[y]:3d} ({ratio:.2f}) {bar}")

cases = [
    ("Screenshot_2026-06-12-22-45-58-77_1d58bed7e226cc412b0128cc8fb4cf50.jpg", 180, 325, 1030, 1290, True, "3♥"),
    ("Screenshot_2026-06-13-02-24-21-01_1d58bed7e226cc412b0128cc8fb4cf50.jpg", 180, 325, 1030, 1290, True, "9♦"),
]

def sc(v, base, screen):
    return int(v * screen / base)

for fn, bx1, bx2, by1, by2, is_red, label in cases:
    fp = os.path.join(UPLOAD_DIR, fn)
    pixels, sw, sh = load_jpg_pixels(fp)
    sx1, sx2 = sc(bx1, BASE_W, sw), sc(bx2, BASE_W, sw)
    sy1, sy2 = sc(by1, BASE_H, sh), sc(by2, BASE_H, sh)
    full_profile(pixels, sw, sh, sx1, sy1, sx2, sy2, is_red, label)
