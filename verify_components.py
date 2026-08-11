"""
V2.9.211 花色识别 V4: 连通分量法
核心: 在 indicator 区域的顶部，数连通分量数量
- 2 个分量 → ♥ (两瓣)
- 3 个分量 → ♣ (三叶草)
- 1 个分量 → ♦ 或 ♠ (尖顶，靠颜色区分)

步骤:
1. 裁剪牌面顶部 40% × 50% = indicator 区域
2. 二值化 (红/黑 mask)
3. 在顶部区域找连通分量
4. 根据分量数 + 颜色判定花色
"""
from PIL import Image
import numpy as np
from scipy import ndimage

def count_components(mask, top_pct=0.35):
    """在 mask 的顶部区域数连通分量"""
    h, w = mask.shape
    top_h = int(h * top_pct)
    if top_h < 2: top_h = 2
    top_mask = mask[:top_h, :]
    
    # 膨胀一下让同一瓣的像素连起来
    dilated = ndimage.binary_dilation(top_mask, iterations=2)
    labeled, num = ndimage.label(dilated)
    
    # 过滤掉太小的分量 (< 5 像素)
    valid = 0
    for i in range(1, num+1):
        if (labeled == i).sum() >= 5:
            valid += 1
    return valid

def test_all(PATH, test_cards, label):
    img = Image.open(PATH)
    W, H = img.size
    np_img = np.array(img)
    
    def crop(x1_pct, y1_pct, x2_pct, y2_pct):
        x1,y1 = int(x1_pct*W), int(y1_pct*H)
        x2,y2 = int(x2_pct*W), int(y2_pct*H)
        return np_img[y1:y2, x1:x2]
    
    correct = 0
    for name, x1, y1, x2, y2, is_hand, exp in test_cards:
        region = crop(x1, y1, x2, y2)
        ch, cw = region.shape[:2]
        
        # indicator 区域: 顶部 40% 高 × 50% 宽
        ind_h = int(ch * 0.40)
        ind_w = int(cw * 0.50)
        indicator = region[:ind_h, :ind_w]
        ih, iw = indicator.shape[:2]
        
        r = indicator[:,:,0].astype(int); g = indicator[:,:,1].astype(int); b = indicator[:,:,2].astype(int)
        red_m = (r>130)&(r-g>50)&(r-b>50)
        black_m = (r<90)&(g<90)&(b<90)
        red = int(red_m.sum()); black = int(black_m.sum())
        
        if red > 20 and red > black*0.5: color = "red"
        elif black > 20 and black > red*2: color = "black"
        elif red > black: color = "red"
        else: color = "black"
        
        mask = red_m if color=="red" else black_m
        total = int(mask.sum())
        if total < 15:
            print(f"  {name}: too_few({total}) → ? ❌"); continue
        
        # 数连通分量
        n_components = count_components(mask, top_pct=0.40)
        
        # ★★★ 判定逻辑 ★★★
        if n_components >= 3:
            key = "c"  # ♣ 三叶草
        elif n_components == 2:
            key = "h"  # ♥ 两瓣
        elif n_components == 1:
            # 尖顶: 红色=♦, 黑色=♠
            key = "d" if color == "red" else "s"
        else:
            key = "?"
        
        sym = {"h":"♥","d":"♦","c":"","s":"♠","?":"?"}
        ok = key == exp
        mark = "✅" if ok else "❌"
        if ok: correct += 1
        
        print(f"  {name}: {color} comps={n_components} total={total} → {sym[key]} {mark}")
    
    return correct

# 新截图
PATH_NEW = "/app/data/所有对话/主对话/用户上传/Screenshot_2026-06-13-02-20-54-87_1d58bed7e226cc412b0128cc8fb4cf50.jpg"
new_cards = [
    ("8♣", 0.158, 0.445, 0.280, 0.545, False, "c"),
    ("8♥", 0.296, 0.445, 0.419, 0.545, False, "h"),
    ("A", 0.437, 0.445, 0.559, 0.545, False, "s"),
    ("3♦", 0.576, 0.445, 0.698, 0.545, False, "d"),
    ("9♠", 0.715, 0.445, 0.837, 0.545, False, "s"),
    ("7♠", 0.046, 0.746, 0.148, 0.873, True,  "s"),
    ("7♦", 0.167, 0.746, 0.279, 0.873, True,  "d"),
]
print("=== 新截图: 8♣ 8♥ A♠ 3♦ 9♠ + 7♠ 7♦ ===")
n = test_all(PATH_NEW, new_cards, "新")

# 旧截图
PATH_OLD = "/app/data/所有对话/主对话/用户上传/Screenshot_2026-06-13-02-33-09-28_1d58bed7e226cc412b0128cc8fb4cf50.jpg"
old_cards = [
    ("K♣", 0.046, 0.746, 0.171, 0.873, True, "c"),
    ("Q♦", 0.161, 0.746, 0.300, 0.873, True, "d"),
    ("8♣", 0.157, 0.459, 0.283, 0.541, False, "c"),
    ("5♠", 0.296, 0.459, 0.421, 0.541, False, "s"),
    ("7♦", 0.440, 0.459, 0.561, 0.541, False, "d"),
    ("5", 0.579, 0.459, 0.704, 0.541, False, "c"),
    ("7♥", 0.718, 0.459, 0.843, 0.541, False, "h"),
]
print("\n=== 旧截图: K♣ Q♦ 8♣ 5♠ 7♦ 5♣ 7♥ ===")
o = test_all(PATH_OLD, old_cards, "旧")

print(f"\n★★★ 最终: 新={n}/7 旧={o}/7 综合={n+o}/14 ({(n+o)/14*100:.0f}%) ★★★")
