"""
最终版: 正确提取4个花色 indicator suit 模板
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
    
    r, g, b = arr[:,:,0].astype(int), arr[:,:,1].astype(int), arr[:,:,2].astype(int)
    
    if suit in ("s", "c"):
        colored = (r < 60) & (g < 60) & (b < 60)
    else:
        # 放宽红色阈值
        colored = (r > 100) & (r - g > 20) & (r - b > 20) & (g < 150)
    
    # 排除绿色桌布
    green = (g > r - 10) & (g > 100)
    colored = colored & ~green
    
    # indicator 区域: 上半部分 (y < h*0.35)
    indicator_mask = np.zeros_like(colored)
    indicator_mask[:int(h*0.35), :] = colored[:int(h*0.35), :]
    
    # 形态学清理
    indicator_mask = ndimage.binary_closing(indicator_mask, iterations=3)
    indicator_mask = ndimage.binary_opening(indicator_mask, iterations=2)
    
    # 连通分量
    lbl, n = ndimage.label(indicator_mask)
    szs = ndimage.sum(indicator_mask, lbl, range(1, n+1))
    
    big = [(i+1, int(szs[i])) for i in range(n) if int(szs[i]) > 100]
    big.sort(key=lambda x: -x[1])
    
    print(f"{suit}: {len(big)} components")
    
    comp_data = []
    for idx, sz in big[:5]:
        ys, xs = np.where(lbl == idx)
        cy, cx = ys.mean(), xs.mean()
        bbox = (xs.min(), ys.min(), xs.max(), ys.max())
        bw, bh = bbox[2]-bbox[0], bbox[3]-bbox[1]
        comp_data.append((idx, sz, cy, cx, bbox, bw, bh))
        print(f"  #{idx}: sz={sz}, center=({cx:.0f},{cy:.0f}), bbox={bw}x{bh} @ {bbox}")
    
    if len(comp_data) >= 1:
        # suit = cy 最大的（在下方）
        comp_data.sort(key=lambda x: -x[2])
        suit_comp = comp_data[0]
        idx, sz, cy, cx, bbox, bw, bh = suit_comp
        
        x0, y0, x1, y1 = bbox
        pad = 10
        suit_crop = img.crop((max(0,x0-pad), max(0,y0-pad), min(w,x1+pad), min(h,y1+pad)))
        suit_crop.save(f"{OUT_DIR}/final_suit_{suit}.png")
        sw, sh = suit_crop.size
        print(f"  → final_suit_{suit}: {sw}x{sh}")
