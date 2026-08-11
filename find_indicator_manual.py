"""
手动指定 indicator 坐标（基于观察），直接裁剪 suit symbol
从完整图片观察:
- 牌面大约从 y=200 开始
- indicator 在牌面左上角
- suit symbol 在 indicator 的下半部分

通过观察图片:
- 牌的左边缘约 x=230~270
- indicator 区域大约: x=250~500, y=350~900
- suit symbol 大约: x=280~450, y=600~900
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

# 先看一下每张图的 indicator 大致区域
# 用宽松的颜色检测找上半部分的有色像素位置
for suit, path in src_imgs.items():
    img = Image.open(path)
    arr = np.array(img)
    w, h = img.size
    
    r, g, b = arr[:,:,0].astype(int), arr[:,:,1].astype(int), arr[:,:,2].astype(int)
    
    if suit in ("s", "c"):
        colored = (r < 80) & (g < 80) & (b < 80)
    else:
        colored = (r > 100) & (r > g + 20) & (r > b + 20)
    
    # 只看上半部分（排除中心大符号）
    upper = colored.copy()
    upper[int(h*0.32):, :] = False
    
    # 找有色像素的边界框
    ys, xs = np.where(upper)
    if len(ys) > 0:
        print(f"{suit}: indicator colored pixels: y={ys.min()}~{ys.max()}, x={xs.min()}~{xs.max()}")
        print(f"  As %: y={ys.min()/h*100:.1f}%~{ys.max()/h*100:.1f}%, x={xs.min()/w*100:.1f}%~{xs.max()/w*100:.1f}%")
        
        # 裁剪 indicator 区域
        pad = 30
        ind = img.crop((
            max(0, xs.min()-pad), max(0, ys.min()-pad),
            min(w, xs.max()+pad), min(h, ys.max()+pad)
        ))
        ind.save(f"{OUT_DIR}/manual_indicator_{suit}.png")
        print(f"  Saved manual_indicator_{suit}: {ind.size}")
    else:
        print(f"{suit}: NO colored pixels in upper 32%")
