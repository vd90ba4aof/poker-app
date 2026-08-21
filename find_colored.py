"""
在完整图片中找 colored pixels (rank+suit) 的位置
"""
from PIL import Image
import numpy as np
import os

os.chdir(os.path.dirname(os.path.abspath(__file__)))

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
    
    if suit in ("s", "c"):
        colored = (r < 100) & (g < 100) & (b < 100)
    else:
        colored = (r > 120) & (r - g > 30) & (r - b > 30)
    
    # 统计每行/每列的有色像素数
    row_sum = colored.sum(axis=1)
    col_sum = colored.sum(axis=0)
    
    # 找有色像素密集的行列范围
    row_thresh = colored.sum() / h * 0.5
    col_thresh = colored.sum() / w * 0.5
    
    colored_rows = np.where(row_sum > row_thresh)[0]
    colored_cols = np.where(col_sum > col_thresh)[0]
    
    if len(colored_rows) > 0 and len(colored_cols) > 0:
        print(f"{suit}: colored pixels region: y={colored_rows.min()}~{colored_rows.max()}, x={colored_cols.min()}~{colored_cols.max()}")
        print(f"  As % of image: y={colored_rows.min()/h*100:.1f}%~{colored_rows.max()/h*100:.1f}%, x={colored_cols.min()/w*100:.1f}%~{colored_cols.max()/w*100:.1f}%")
    
    # 打印每100px的行列分布
    print(f"  Row distribution (every 200px):")
    for y in range(0, h, 200):
        end = min(y+200, h)
        cnt = int(row_sum[y:end].sum())
        bar = "#" * (cnt // 500) if cnt > 0 else ""
        print(f"    y={y:4d}~{end:4d}: {cnt:6d} {bar}")
