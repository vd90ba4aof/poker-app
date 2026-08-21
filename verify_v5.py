"""
V2.9.211 花色识别 V5: 极小区域 + 形状剖面法
策略: 只取 indicator 区域的下半部分（避开 rank 字符），分析 suit symbol 形状

观察: 在 indicator 区域中, rank 在上半部, suit symbol 在下半部
取 indicator 的 y=30%~y=80% 区域 = 主要是 suit symbol

特征:
- ♥: 上半部宽（两瓣），下半部尖 → topW > botW
- ♦: 上半部尖，中间宽，下半部尖 → topW < midW
- ♣: 上半部宽（三瓣），下半部有茎 → topW > botW (茎很窄)
- ♠: 上半部尖，中间宽 → topW < midW

简化: 比较 top-half width vs max width
"""
from PIL import Image
import numpy as np

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
        
        # ★★★ 极小 indicator: 顶部 35% × 左 45% ★★★
        ind_h = int(ch * 0.35)
        ind_w = int(cw * 0.45)
        indicator = region[:ind_h, :ind_w]
        ih, iw = indicator.shape[:2]
        
        r = indicator[:,:,0].astype(int); g = indicator[:,:,1].astype(int); b = indicator[:,:,2].astype(int)
        red_m = (r>130)&(r-g>50)&(r-b>50)
        black_m = (r<90)&(g<90)&(b<90)
        red = int(red_m.sum()); black = int(black_m.sum())
        
        if red > 15 and red > black*0.5: color = "red"
        elif black > 15 and black > red*2: color = "black"
        elif red > black: color = "red"
        else: color = "black"
        
        mask = red_m if color=="red" else black_m
        total = int(mask.sum())
        if total < 10:
            print(f"  {name}: too_few({total}) → ? ❌"); continue
        
        # 逐行宽度
        row_w = np.array([int(mask[y].sum()) for y in range(ih)])
        
        # 找 rank 结束: 最大宽度的行之后，连续2行 < 20% maxW
        max_w_idx = int(np.argmax(row_w))
        max_w = int(row_w[max_w_idx])
        
        rank_end = ih
        for y in range(max_w_idx + 1, ih - 1):
            if row_w[y] < max_w * 0.20 and row_w[y+1] < max_w * 0.20:
                rank_end = y + 2
                break
        
        # suit 区域
        suit_top = min(rank_end, ih - 5)
        suit_mask = mask[suit_top:, :]
        suit_h = suit_mask.shape[0]
        if suit_h < 3:
            print(f"  {name}: no_suit → ? ❌"); continue
        
        suit_row_w = np.array([int(suit_mask[y].sum()) for y in range(suit_h)])
        
        # 去掉首尾空白
        nz = [y for y in range(suit_h) if suit_row_w[y] > 1]
        if len(nz) < 3:
            print(f"  {name}: empty → ? ❌"); continue
        
        s_top, s_bot = nz[0], nz[-1]
        s_mask = suit_mask[s_top:s_bot+1, :]
        s_h = s_bot - s_top + 1
        s_row_w = np.array([int(s_mask[y].sum()) for y in range(s_h)])
        
        max_sw = int(s_row_w.max())
        if max_sw < 3:
            print(f"  {name}: no_pattern → ? ❌"); continue
        
        # 顶部 25% 最大行宽
        top_n = max(1, s_h // 4)
        top_max = int(s_row_w[:top_n].max())
        topR = top_max / max_sw
        
        # 底部 25% 最大行宽
        bot_n = max(1, s_h // 4)
        bot_max = int(s_row_w[-bot_n:].max())
        botR = bot_max / max_sw
        
        # 质心 Y
        ys = np.arange(s_h)
        com_y = float(np.sum(ys * s_row_w)) / (np.sum(s_row_w) * s_h) if np.sum(s_row_w) > 0 else 0.5
        
        # ★★★ 判定: 先用 topR, 再用 comY 辅助 ★★★
        if color == "red":
            if topR > 0.55:
                key = "h"  # 宽顶 = ♥
            elif com_y < 0.42:
                key = "h"  # 质心偏上 = ♥
            else:
                key = "d"  # ♦
        else:
            if topR > 0.55:
                key = "c"  # 宽顶 = ♣
            elif com_y < 0.45:
                key = "c"  # 质心偏上 = ♣
            else:
                key = "s"  # ♠
        
        sym = {"h":"♥","d":"♦","c":"","s":"♠"}
        ok = key == exp
        mark = "✅" if ok else "❌"
        if ok: correct += 1
        
        print(f"  {name}: {color} indH={ih} rankEnd={rank_end} sH={s_h} maxW={max_sw} topR={topR:.2f} botR={botR:.2f} comY={com_y:.2f} → {sym[key]} {mark}")
    
    return correct

# 新截图
PATH_NEW = "/app/data/所有对话/主对话/用户上传/Screenshot_2026-06-13-02-20-54-87_1d58bed7e226cc412b0128cc8fb4cf50.jpg"
new_cards = [
    ("8♣", 0.158, 0.445, 0.280, 0.545, False, "c"),
    ("8♥", 0.296, 0.445, 0.419, 0.545, False, "h"),
    ("A♠", 0.437, 0.445, 0.559, 0.545, False, "s"),
    ("3♦", 0.576, 0.445, 0.698, 0.545, False, "d"),
    ("9", 0.715, 0.445, 0.837, 0.545, False, "s"),
    ("7♠", 0.046, 0.746, 0.148, 0.873, True,  "s"),
    ("7♦", 0.167, 0.746, 0.279, 0.873, True,  "d"),
]
print("=== 新截图 ===")
n = test_all(PATH_NEW, new_cards, "新")

# 旧截图
PATH_OLD = "/app/data/所有对话/主对话/用户上传/Screenshot_2026-06-13-02-33-09-28_1d58bed7e226cc412b0128cc8fb4cf50.jpg"
old_cards = [
    ("K♣", 0.046, 0.746, 0.171, 0.873, True, "c"),
    ("Q♦", 0.161, 0.746, 0.300, 0.873, True, "d"),
    ("8♣", 0.157, 0.459, 0.283, 0.541, False, "c"),
    ("5♠", 0.296, 0.459, 0.421, 0.541, False, "s"),
    ("7♦", 0.440, 0.459, 0.561, 0.541, False, "d"),
    ("5♣", 0.579, 0.459, 0.704, 0.541, False, "c"),
    ("7♥", 0.718, 0.459, 0.843, 0.541, False, "h"),
]
print("\n=== 旧截图 ===")
o = test_all(PATH_OLD, old_cards, "旧")

print(f"\n★★★ 最终: 新={n}/7 旧={o}/7 综合={n+o}/14 ({(n+o)/14*100:.0f}%) ★★★")
