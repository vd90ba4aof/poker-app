"""
用绝对像素尺寸从上传牌图提取 indicator suit
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
    print(f"{suit}: {w}x{h}")
    
    r, g, b = arr[:,:,0].astype(int), arr[:,:,1].astype(int), arr[:,:,2].astype(int)
    
    if suit in ("s", "c"):
        color_mask = (r < 100) & (g < 100) & (b < 100)
    else:
        color_mask = (r > 120) & (r - g > 30) & (r - b > 30)
    
    # 只在左上角 1/3 区域找（indicator 区域）
    search = color_mask.copy()
    search[int(h/3):, :] = False
    search[:, int(w/3):] = False
    
    # 找连通分量
    lbl, n = ndimage.label(search)
    szs = ndimage.sum(search, lbl, range(1, n+1))
    
    # 取前几个大分量
    big = [(i+1, int(szs[i])) for i in range(n) if int(szs[i]) > 200]
    big.sort(key=lambda x: -x[1])
    
    print(f"  Components in top-left third: {len(big)}")
    
    comp_data = []
    for idx, sz in big[:6]:
        ys, xs = np.where(lbl == idx)
        cy, cx = ys.mean(), xs.mean()
        bbox = (xs.min(), ys.min(), xs.max(), ys.max())
        bw, bh = bbox[2]-bbox[0], bbox[3]-bbox[1]
        comp_data.append((idx, sz, cy, cx, bbox, bw, bh))
        print(f"    #{idx}: sz={sz}, center=({cx:.0f},{cy:.0f}), bbox={bw}x{bh}")
    
    if len(comp_data) >= 1:
        # suit = 位置最靠下靠右的大分量（在 indicator 内，suit 在 rank 下方）
        # 但也可能是最大的分量
        # 取最大的那个
        target = comp_data[0]
        idx, sz, cy, cx, bbox, bw, bh = target
        
        # 扩展 10px 边距
        x0, y0, x1, y1 = bbox
        pad = 15
        suit_crop = img.crop((max(0,x0-pad), max(0,y0-pad), min(w,x1+pad), min(h,y1+pad)))
        suit_crop.save(f"{OUT_DIR}/clean_suit_{suit}.png")
        sw, sh = suit_crop.size
        print(f"  → Saved clean_suit_{suit}: {sw}x{sh}")
        
        # 二值化保存
        sarr = np.array(suit_crop)
        sr, sg, sb = sarr[:,:,0].astype(int), sarr[:,:,1].astype(int), sarr[:,:,2].astype(int)
        if suit in ("s", "c"):
            bin_m = (sr < 100) & (sg < 100) & (sb < 100)
        else:
            bin_m = (sr > 120) & (sr - sg > 30) & (sr - sb > 30)
        bin_img = Image.fromarray((bin_m * 255).astype(np.uint8))
        bin_img.save(f"{OUT_DIR}/clean_suit_{suit}_binary.png")
