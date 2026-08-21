"""
对比 ♥ 和 ♦ 的实际形状差异
"""
import os
from PIL import Image, ImageDraw

UPLOAD_DIR = "/app/data/所有对话/主对话/用户上传"
BASE_W, BASE_H = 1080, 2344

# 取几张确定的 ♥ 和 ♦ 对比
cases = [
    ("Screenshot_2026-06-12-22-45-58-77_1d58bed7e226cc412b0128cc8fb4cf50.jpg", 180, 325, 1030, 1290, "3♥"),
    ("Screenshot_2026-06-13-02-20-54-87_1d58bed7e226cc412b0128cc8fb4cf50.jpg", 325, 460, 1030, 1290, "8♥"),
    ("Screenshot_2026-06-13-02-24-21-01_1d58bed7e226cc412b0128cc8fb4cf50.jpg", 180, 325, 1030, 1290, "9♦"),
    ("Screenshot_2026-06-13-02-26-11-07_1d58bed7e226cc412b0128cc8fb4cf50.jpg", 460, 595, 1030, 1290, "9♥"),
]

def sc(v, base, screen):
    return int(v * screen / base)

for fn, bx1, bx2, by1, by2, label in cases:
    fp = os.path.join(UPLOAD_DIR, fn)
    img = Image.open(fp)
    sw, sh = img.size
    sx1, sx2 = sc(bx1, BASE_W, sw), sc(bx2, BASE_W, sw)
    sy1, sy2 = sc(by1, BASE_H, sh), sc(by2, BASE_H, sh)
    crop = img.crop((sx1, sy1, sx2, sy2))
    crop.save(f"/app/data/所有对话/主对话/poker-app-latest/compare_{label}.png")
    print(f"Saved compare_{label}.png")
