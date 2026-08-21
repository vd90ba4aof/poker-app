"""
模板匹配 v4: 用牌面中心大 suit symbol 做模板（无 rank 干扰）
多尺度滑动窗口搜索截屏 indicator 区域
"""
from PIL import Image
import numpy as np
import os

os.chdir(os.path.dirname(os.path.abspath(__file__)))
OUT_DIR = "templates"

# ========== Step 1: 从上传牌图提取 CENTER suit symbol（纯花色）==========
src_imgs = {
    "s": "../用户上传/IMG_20260808_213049.jpg",
    "h": "../用户上传/IMG_20260808_212728.jpg",
    "c": "../用户上传/IMG_20260808_212930.jpg",
    "d": "../用户上传/IMG_20260808_212825.jpg",
}

center_suits = {}
for suit, path in src_imgs.items():
    img = Image.open(path)
    w, h = img.size
    
    # 中心大花色符号: 大约在中下区域
    # 从图片看，大符号大约在 y=45%~90%, x=25%~75%
    cx0, cx1 = int(w*0.20), int(w*0.80)
    cy0, cy1 = int(h*0.40), int(h*0.92)
    center = img.crop((cx0, cy0, cx1, cy1))
    center.save(f"{OUT_DIR}/center_suit_{suit}.png")
    cw, ch = center.size
    print(f"{suit}: center suit {cw}x{ch}")
    
    center_suits[suit] = np.array(center)

# ========== Step 2: 测试数据 ==========
PATH_NEW = "../用户上传/Screenshot_2026-06-13-02-20-54-87_1d58bed7e226cc412b0128cc8fb4cf50.jpg"
new_cards = [
    ("8♣", 0.158, 0.445, 0.280, 0.545, False, "c"),
    ("8♥", 0.296, 0.445, 0.419, 0.545, False, "h"),
    ("A♠", 0.437, 0.445, 0.559, 0.545, False, "s"),
    ("3♦", 0.576, 0.445, 0.698, 0.545, False, "d"),
    ("9♠", 0.715, 0.445, 0.837, 0.545, False, "s"),
    ("7♠", 0.046, 0.746, 0.148, 0.873, True,  "s"),
    ("7♦", 0.167, 0.746, 0.279, 0.873, True,  "d"),
]
PATH_OLD = "../用户上传/Screenshot_2026-06-13-02-33-09-28_1d58bed7e226cc412b0128cc8fb4cf50.jpg"
old_cards = [
    ("K♣", 0.046, 0.746, 0.171, 0.873, True, "c"),
    ("Q♦", 0.161, 0.746, 0.300, 0.873, True, "d"),
    ("8♣", 0.157, 0.459, 0.283, 0.541, False, "c"),
    ("5", 0.296, 0.459, 0.421, 0.541, False, "s"),
    ("7♦", 0.440, 0.459, 0.561, 0.541, False, "d"),
    ("5♣", 0.579, 0.459, 0.704, 0.541, False, "c"),
    ("7♥", 0.718, 0.459, 0.843, 0.541, False, "h"),
]
all_tests = [(PATH_NEW, new_cards), (PATH_OLD, old_cards)]

# ========== Step 3: NCC ==========
def ncc(template, region):
    t = template.astype(float) - template.mean()
    r = region.astype(float) - region.mean()
    nt = np.sqrt((t*t).sum()); nr = np.sqrt((r*r).sum())
    if nt < 1e-6 or nr < 1e-6: return -1.0
    return float((t * r).sum() / (nt * nr))

def best_ncc_search(template, region):
    """多尺度滑动窗口搜索"""
    th, tw = template.shape[:2]
    rh, rw = region.shape[:2]
    
    best = -1.0
    # 模板远大于区域，需要大幅缩小
    # 尝试多种缩放比例
    for scale_pct in range(5, 50, 3):  # 5% to 47%
        scale = scale_pct / 100.0
        new_h = int(th * scale)
        new_w = int(tw * scale)
        if new_h < 4 or new_w < 4 or new_h > rh or new_w > rw:
            continue
        
        tpl_s = np.array(Image.fromarray(template).resize((new_w, new_h), Image.LANCZOS))
        
        step_y = max(1, (rh - new_h) // 3)
        step_x = max(1, (rw - new_w) // 3)
        for dy in range(0, rh - new_h + 1, step_y):
            for dx in range(0, rw - new_w + 1, step_x):
                sub = region[dy:dy+new_h, dx:dx+new_w]
                if sub.shape[:2] == tpl_s.shape[:2]:
                    score = ncc(tpl_s, sub)
                    if score > best:
                        best = score
    
    return best

# ========== Step 4: 跑测试 ==========
total = 0; correct = 0

for path, cards in all_tests:
    img = Image.open(path)
    W, H = img.size
    np_img = np.array(img)
    
    def crop(x1, y1, x2, y2):
        return np_img[int(y1*H):int(y2*H), int(x1*W):int(x2*W)]
    
    print(f"\n{'='*50}")
    print(f"Testing: {path.split('/')[-1][:30]}")
    print(f"{'='*50}")
    
    for name, x1, y1, x2, y2, is_hand, exp in cards:
        region = crop(x1, y1, x2, y2)
        ch, cw = region.shape[:2]
        
        # 提取 indicator 区域（包含 rank + suit）
        ind_h = int(ch * 0.35)
        ind_w = int(cw * 0.50)
        indicator = region[:ind_h, :ind_w]
        
        print(f"\n{name} (indicator={indicator.shape[1]}x{indicator.shape[0]}, expected={exp}):")
        
        scores = {}
        for suit_key, tpl in center_suits.items():
            score = best_ncc_search(tpl, indicator)
            scores[suit_key] = score
        
        # 按分数排序
        sorted_scores = sorted(scores.items(), key=lambda x: -x[1])
        for k, v in sorted_scores:
            print(f"  {k}: {v:.3f}")
        
        best = sorted_scores[0][0]
        ok = best == exp
        if ok: correct += 1
        total += 1
        mark = "✅" if ok else ""
        print(f"  → {best} {mark}")

print(f"\n{'='*50}")
print(f"★★★ center-suit template matching: {correct}/{total} = {correct/total*100:.0f}% ★★★")
