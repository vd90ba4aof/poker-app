"""
V2.9.211 花色识别 V2: 先隔离 suit symbol，再分析形状
策略: 在 rank indicator 区域内，先找到 rank 字符的下边界，
然后 suit symbol 就在 rank 下方。对 suit symbol 单独做形状分析。

关键特征:
- ♥: 顶部宽（两瓣），底部尖 → topW/maxW > 0.7
- ♦: 顶部尖，中部宽，底部尖 → topW/maxW < 0.4
- ♣: 顶部宽（三瓣），底部有茎 → topW/maxW > 0.7
- ♠: 顶部尖，中部宽 → topW/maxW < 0.4
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
        
        # rank indicator 区域: 左上角约 50% 宽 x 60% 高
        rankW = int(cw * 0.50)
        rankH = int(ch * 0.60)
        indicator = region[:rankH, :rankW]
        ih, iw = indicator.shape[:2]
        
        r = indicator[:,:,0].astype(int); g = indicator[:,:,1].astype(int); b = indicator[:,:,2].astype(int)
        red_m = (r>130)&(r-g>50)&(r-b>50)
        black_m = (r<90)&(g<90)&(b<90)
        red = int(red_m.sum()); black = int(black_m.sum())
        
        if red > 30 and red > black*0.5: color = "red"
        elif black > 30 and black > red*2: color = "black"
        elif red > black: color = "red"
        else: color = "black"
        
        mask = red_m if color=="red" else black_m
        
        # 逐行统计横向跨度
        row_spans = []
        for y in range(ih):
            row = mask[y]
            cols = np.where(row)[0]
            if len(cols) > 0:
                row_spans.append((y, cols.max() - cols.min() + 1, len(cols)))
            else:
                row_spans.append((y, 0, 0))
        
        # 找 rank 结束位置: 连续3行跨度 < 20% 最大跨度
        max_span = max(s for _,s,_ in row_spans) if row_spans else 0
        if max_span < 5:
            print(f"  {name}: no_pattern → ? ❌"); continue
        
        rank_end = ih // 2  # 默认
        for y in range(3, ih - 2):
            if (row_spans[y][1] < max_span * 0.20 and
                row_spans[y+1][1] < max_span * 0.20 and
                row_spans[y+2][1] < max_span * 0.20):
                rank_end = y + 3
                break
        
        # suit symbol 区域: 从 rank_end 到 indicator 底部
        suit_top = rank_end
        suit_bottom = ih
        suit_h = suit_bottom - suit_top
        if suit_h < 5:
            print(f"  {name}: no_suit_region → ? ❌"); continue
        
        # 提取 suit symbol 的 mask
        suit_mask = mask[suit_top:suit_bottom, :]
        suit_rows = suit_mask.sum(axis=1)
        
        # 找 suit symbol 的实际范围（去掉空白行）
        non_zero_rows = [y for y in range(suit_h) if suit_rows[y] > 2]
        if len(non_zero_rows) < 3:
            print(f"  {name}: too_few_suit → ? ❌"); continue
        
        actual_top = non_zero_rows[0]
        actual_bottom = non_zero_rows[-1]
        actual_suit_mask = suit_mask[actual_top:actual_bottom+1, :]
        actual_suit_h = actual_bottom - actual_top + 1
        
        # 计算 topWidthRatio
        actual_row_w = actual_suit_mask.sum(axis=1)
        max_w = int(actual_row_w.max())
        if max_w < 3:
            print(f"  {name}: no_suit_pattern → ? ❌"); continue
        
        # 顶部 25% 的最大行宽
        top_portion = actual_row_w[:max(2, actual_suit_h // 4)]
        top_max_w = int(top_portion.max())
        
        topWidthRatio = top_max_w / max_w if max_w > 0 else 0
        
        # 底部 25% 的最大行宽（检测是否有茎）
        bot_portion = actual_row_w[-max(2, actual_suit_h // 4):]
        bot_max_w = int(bot_portion.max())
        botWidthRatio = bot_max_w / max_w if max_w > 0 else 0
        
        # 中部最大行宽位置
        mid_y = int(np.argmax(actual_row_w))
        midWidthRatio = (mid_y + 1) / actual_suit_h  # 0~1, 越大越靠下
        
        # ★★★ 判定逻辑 ★★★
        # 红色: ♥ (宽顶) vs ♦ (尖顶)
        # 黑色: ♣ (宽顶) vs ♠ (尖顶)
        if color == "red":
            key = "h" if topWidthRatio > 0.55 else "d"
        else:
            key = "c" if topWidthRatio > 0.55 else "s"
        
        sym = {"h":"♥","d":"♦","c":"","s":"♠"}
        ok = key == exp
        mark = "✅" if ok else "❌"
        if ok: correct += 1
        
        print(f"  {name}: {color} rankEnd@{rank_end} suitH={actual_suit_h} maxW={max_w} topW={top_max_w}({topWidthRatio:.2f}) botW={bot_max_w}({botWidthRatio:.2f}) midY@{midWidthRatio:.2f} → {sym[key]} {mark}")
    
    return correct

# 新截图
PATH_NEW = "/app/data/所有对话/主对话/用户上传/Screenshot_2026-06-13-02-20-54-87_1d58bed7e226cc412b0128cc8fb4cf50.jpg"
new_cards = [
    ("8♣", 0.158, 0.445, 0.280, 0.545, False, "c"),
    ("8♥", 0.296, 0.445, 0.419, 0.545, False, "h"),
    ("A♠", 0.437, 0.445, 0.559, 0.545, False, "s"),
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
    ("8", 0.157, 0.459, 0.283, 0.541, False, "c"),
    ("5♠", 0.296, 0.459, 0.421, 0.541, False, "s"),
    ("7♦", 0.440, 0.459, 0.561, 0.541, False, "d"),
    ("5♣", 0.579, 0.459, 0.704, 0.541, False, "c"),
    ("7♥", 0.718, 0.459, 0.843, 0.541, False, "h"),
]
print("\n=== 旧截图: K♣ Q♦ 8♣ 5 7♦ 5♣ 7♥ ===")
o = test_all(PATH_OLD, old_cards, "旧")

print(f"\n★★★ 最终: 新={n}/7 旧={o}/7 综合={n+o}/14 ({(n+o)/14*100:.0f}%) ★★★")
