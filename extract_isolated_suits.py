"""
精确定位上传牌图中 indicator 内 suit symbol 的位置
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
    print(f"\n{suit}: {w}x{h}")
    
    # 检测非白色非绿色像素（即花色符号）
    r, g, b = arr[:,:,0], arr[:,:,1], arr[:,:,2]
    
    # 黑色 suit (♠♣): R<60, G<60, B<60
    black_mask = (r < 60) & (g < 60) & (b < 60)
    # 红色 suit (♥♦): R>150, G<100, B<100
    red_mask = (r > 150) & (g < 100) & (b < 100)
    
    suit_mask = black_mask | red_mask
    
    # 只在 indicator 区域找（左上角）
    ind_region = suit_mask[:int(h*0.35), :int(w*0.40)]
    
    # 找连通分量
    from scipy import ndimage
    labeled, num = ndimage.label(ind_region)
    print(f"  Components in indicator: {num}")
    
    sizes = ndimage.sum(ind_region, labeled, range(1, num+1))
    
    # 找到 rank 字符和 suit symbol
    # rank 在上，suit 在下
    for i in range(1, num+1):
        size = int(sizes[i-1])
        if size < 100: continue
        ys, xs = np.where(labeled == i)
        cy, cx = ys.mean(), xs.mean()
        bbox = (xs.min(), ys.min(), xs.max(), ys.max())
        bw, bh = bbox[2]-bbox[0], bbox[3]-bbox[1]
        print(f"  Component {i}: size={size}, center=({cx:.0f},{cy:.0f}), bbox={bw}x{bh} @ ({bbox[0]},{bbox[1]})")
    
    # 最大的两个分量 = rank + suit
    # suit 在下方 (cy 更大)
    big_components = [(i, int(sizes[i-1])) for i in range(1, num+1) if int(sizes[i-1]) > 100]
    big_components.sort(key=lambda x: -x[1])
    
    if len(big_components) >= 2:
        # 取 cy 大的那个 = suit
        comps_with_cy = []
        for i, size in big_components[:4]:
            ys, xs = np.where(labeled == i)
            cy = ys.mean()
            comps_with_cy.append((i, size, cy))
        
        comps_with_cy.sort(key=lambda x: -x[2])  # 按 cy 降序，最大的 cy = suit（在下方）
        suit_idx = comps_with_cy[0][0]
        
        ys, xs = np.where(labeled == suit_idx)
        bbox = (xs.min()-2, ys.min()-2, xs.max()+2, ys.max()+2)
        suit_crop = img.crop(bbox)
        suit_crop.save(f"{OUT_DIR}/isolated_suit_{suit}.png")
        sw, sh = suit_crop.size
        print(f"  → Isolated suit: {sw}x{sh}, saved")
