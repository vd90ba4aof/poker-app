"""
V2.9.211 花色识别 V3: 只分析左上角小 indicator（rank + 小suit symbol）
关键: 只取牌面顶部 ~35% 高度，完全避开中央大花色符号

策略:
1. 裁剪牌面顶部 35% 高度 × 50% 宽度 = 纯 indicator 区域
2. 在这个区域内找 rank 字符下边界
3. 分析 rank 下方的 suit symbol 形状
4. 用 topWidthRatio 区分: 宽顶(♥/♣) vs 尖顶(♦/♠)
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
        
        # ★★★ 只取顶部 indicator 区域: 40% 高 × 50% 宽 ★★★
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
        
        # 逐行统计宽度
        row_w = np.array([int(mask[y].sum()) for y in range(ih)])
        max_w = int(row_w.max())
        if max_w < 5:
            print(f"  {name}: no_pattern(maxW={max_w}) → ? "); continue
        
        # 找 rank 结束位置: 连续3行 < 25% maxW
        rank_end = ih // 2
        for y in range(2, ih - 2):
            if row_w[y] < max_w * 0.25 and row_w[y+1] < max_w * 0.25 and row_w[y+2] < max_w * 0.25:
                rank_end = y + 3
                break
        
        # suit symbol 区域
        suit_mask = mask[rank_end:, :]
        suit_h = suit_mask.shape[0]
        if suit_h < 3:
            print(f"  {name}: no_suit → ? ❌"); continue
        
        suit_row_w = np.array([int(suit_mask[y].sum()) for y in range(suit_h)])
        
        # 去掉空白行
        non_zero = [y for y in range(suit_h) if suit_row_w[y] > 1]
        if len(non_zero) < 2:
            print(f"  {name}: empty_suit → ? ❌"); continue
        
        actual_top = non_zero[0]
        actual_bot = non_zero[-1]
        actual_mask = suit_mask[actual_top:actual_bot+1, :]
        actual_h = actual_bot - actual_top + 1
        actual_row_w = np.array([int(actual_mask[y].sum()) for y in range(actual_h)])
        
        max_sw = int(actual_row_w.max())
        if max_sw < 3:
            print(f"  {name}: no_suit_pattern → ? ❌"); continue
        
        # topWidthRatio: 顶部 30% 最大行宽 / 整体最大行宽
        top_end = max(2, actual_h // 3)
        top_max_w = int(actual_row_w[:top_end].max())
        topWidthRatio = top_max_w / max_sw
        
        # botWidthRatio: 底部 30% 最大行宽 / 整体最大行宽
        bot_start = max(0, actual_h - actual_h // 3)
        bot_max_w = int(actual_row_w[bot_start:].max())
        botWidthRatio = bot_max_w / max_sw
        
        # midY: 最大行宽所在位置 (0~1)
        mid_y_pos = int(np.argmax(actual_row_w)) / actual_h
        
        # ★★★ 判定逻辑 ★★★
        if color == "red":
            key = "h" if topWidthRatio > 0.50 else "d"
        else:
            key = "c" if topWidthRatio > 0.50 else "s"
        
        sym = {"h":"♥","d":"♦","c":"","s":"♠"}
        ok = key == exp
        mark = "✅" if ok else "❌"
        if ok: correct += 1
        
        print(f"  {name}: {color} indH={ih} rankEnd={rank_end} suitH={actual_h} maxW={max_sw} topR={topWidthRatio:.2f} botR={botWidthRatio:.2f} midY={mid_y_pos:.2f} → {sym[key]} {mark}")
    
    return correct

# 新截图
PATH_NEW = "/app/data/所有对话/主对话/用户上传/Screenshot_2026-06-13-02-20-54-87_1d58bed7e226cc412b0128cc8fb4cf50.jpg"
new_cards = [
    ("8♣", 0.158, 0.445, 0.280, 0.545, False, "c"),
    ("8♥", 0.296, 0.445, 0.419, 0.545, False, "h"),
    ("A♠", 0.437, 0.445, 0.559, 0.545, False, "s"),
    ("3♦", 0.576, 0.445, 0.698, 0.545, False, "d"),
    ("9♠", 0.715, 0.445, 0.837, 0.545, False, "s"),
    ("7", 0.046, 0.746, 0.148, 0.873, True,  "s"),
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
    ("5♣", 0.579, 0.459, 0.704, 0.541, False, "c"),
    ("7♥", 0.718, 0.459, 0.843, 0.541, False, "h"),
]
print("\n=== 旧截图: K♣ Q♦ 8♣ 5♠ 7♦ 5♣ 7♥ ===")
o = test_all(PATH_OLD, old_cards, "旧")

print(f"\n★★★ 最终: 新={n}/7 旧={o}/7 综合={n+o}/14 ({(n+o)/14*100:.0f}%) ★★★")
