"""
用严格颜色阈值找 indicator 位置
"""
from PIL import Image
import numpy as np
import os

os.chdir(os.path.dirname(os.path.abspath(__file__)))
OUT_DIR = "templates"

src_imgs = {
    "s": "../用户上传/IMG_20260808_213049.jpg",
    "h": "../用户上传/IMG_20260808_212728.jpg",
    "c": "../用户上传/IMG_20260808_212930.jpg",
    "d": "../用户上传/IMG_20260808_212825.jpg",
}

for suit, path in src_imgs.items():
    img = Image.open(path)
    arr = np.array(img)
    w, h = img.size
    
    r, g, b = arr[:,:,0].astype(int), arr[:,:,1].astype(int), arr[:,:,2].astype(int)
    
    # 更严格的颜色检测
    if suit in ("s", "c"):
        # 纯黑: 所有通道 < 50
        colored = (r < 50) & (g < 50) & (b < 50)
    else:
        # 纯红: R很高, G和B很低
        colored = (r > 150) & (g < 80) & (b < 80)
    
    # 只在牌面内搜索 (排除绿色桌布)
    # 先找白色牌面区域
    white = (r > 200) & (g > 200) & (b > 200)
    
    # 在白色区域内找有色像素
    colored_in_white = colored & white  # 不对，有色像素不在白色区域内
    
    # 换个思路: 有色像素 AND 非绿色
    green = (g > r) & (g > b) & (g > 80)
    colored_non_green = colored & ~green
    
    row_sum = colored_non_green.sum(axis=1)
    col_sum = colored_non_green.sum(axis=0)
    
    # 找 indicator 区域: 上半部分有色像素
    # 打印上半部分每200px分布
    print(f"{suit} ({w}x{h}):")
    for y in range(0, min(h//2, 2000), 100):
        end = min(y+100, h//2)
        cnt = int(row_sum[y:end].sum())
        bar = "#" * (cnt // 100) if cnt > 0 else ""
        print(f"  y={y:4d}~{end:4d}: {cnt:6d} {bar}")
    
    # indicator 大约在有色的第一个峰值区域
    # 找上半部分有色像素的 y 范围
    upper_half = colored_non_green[:h//2, :]
    upper_rows = np.where(upper_half.sum(axis=1) > 50)[0]
    if len(upper_rows) > 0:
        print(f"  Indicator y range: {upper_rows.min()}~{upper_rows.max()} ({upper_rows.min()/h*100:.1f}%~{upper_rows.max()/h*100:.1f}%)")
        
        upper_cols = np.where(upper_half.sum(axis=0) > 10)[0]
        if len(upper_cols) > 0:
            print(f"  Indicator x range: {upper_cols.min()}~{upper_cols.max()} ({upper_cols.min()/w*100:.1f}%~{upper_cols.max()/w*100:.1f}%)")
