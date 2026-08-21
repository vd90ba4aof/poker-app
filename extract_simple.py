"""
先找到牌面在图片中的实际位置，再定位 indicator
"""
from PIL import Image
import numpy as np
import os
from scipy import ndimage

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
    
    # 找牌面: 白色区域
    r, g, b = arr[:,:,0], arr[:,:,1], arr[:,:,2]
    white = (r > 200) & (g > 200) & (b > 200)
    
    # 最大连通白色区域 = 牌面
    lbl, n = ndimage.label(white)
    szs = ndimage.sum(white, lbl, range(1, n+1))
    largest = np.argmax(szs) + 1
    ys, xs = np.where(lbl == largest)
    
    cy0, cy1 = ys.min(), ys.max()
    cx0, cx1 = xs.min(), xs.max()
    ch = cy1 - cy0
    cw = cx1 - cx0
    print(f"{suit}: card=({cx0},{cy0})-({cx1},{cy1}), {cw}x{ch}")
    
    # indicator: 牌面内左上角
    # 距牌边 5%~12% 宽, 8%~25% 高
    ind_x0 = cx0 + int(cw * 0.06)
    ind_x1 = cx0 + int(cw * 0.16)
    ind_y0 = cy0 + int(ch * 0.06)
    ind_y1 = cy0 + int(ch * 0.22)
    
    indicator = img.crop((ind_x0, ind_y0, ind_x1, ind_y1))
    indicator.save(f"{OUT_DIR}/indicator_{suit}.png")
    iw, ih = indicator.size
    print(f"  indicator: {iw}x{ih}")
    
    # suit: indicator 的下半部分
    suit = indicator.crop((int(iw*0.05), int(ih*0.50), int(iw*0.95), int(ih*0.98)))
    suit.save(f"{OUT_DIR}/suit_lower_{suit}.png")
    sw, sh = suit.size
    print(f"  suit_lower: {sw}x{sh}")
