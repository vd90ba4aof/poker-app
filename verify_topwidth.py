"""
V2.9.211 花色识别改进方案: 用 topWidthRatio 替代 comY
核心思路: ♥/♣ 顶部有 bumps → 顶部宽; ♦/♠ 顶部是尖角 → 顶部窄
topWidthRatio = suit区域顶部25%最大行宽 / 整体最大行宽
  > 0.55 → 宽顶 (♥/♣)
  < 0.45 → 窄顶 (♦/♠)
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
        sy1 = int(ch*0.45) if is_hand else int(ch*0.35)
        sy2 = int(ch*0.75)
        suit = region[sy1:sy2, :int(cw*0.65)]
        sh, sw = suit.shape[:2]
        
        r = suit[:,:,0].astype(int); g = suit[:,:,1].astype(int); b = suit[:,:,2].astype(int)
        red_m = (r>130)&(r-g>50)&(r-b>50)
        black_m = (r<90)&(g<90)&(b<90)
        red = int(red_m.sum()); black = int(black_m.sum())
        
        if red > 50 and red > black*0.5: color = "red"
        elif black > 50 and black > red*2: color = "black"
        elif red > black: color = "red"
        else: color = "black"
        
        mask = red_m if color=="red" else black_m
        cnt = int(mask.sum())
        if cnt < 10:
            print(f"  {name}: too_few({cnt}) → ? ❌"); continue
        
        row_w = mask.sum(axis=1)
        
        # 找最大行宽
        max_w = int(row_w.max())
        if max_w < 3:
            print(f"  {name}: no_pattern → ? ❌"); continue
        
        # ★★★ 新方法: 计算 topWidthRatio ★★★
        # 找第一个有像素的行（suit symbol 顶部）
        first_row = -1
        for y in range(sh):
            if row_w[y] > max_w * 0.2:
                first_row = y
                break
        if first_row < 0:
            print(f"  {name}: no_start → ? ❌"); continue
        
        # 顶部30%区域的最大行宽
        top_end = first_row + max(3, int(sh * 0.30))
        top_end = min(top_end, sh)
        top_max_w = int(row_w[first_row:top_end].max())
        
        topWidthRatio = top_max_w / max_w if max_w > 0 else 0
        
        # ★★★ 判定逻辑 ★★★
        if color == "red":
            key = "h" if topWidthRatio > 0.55 else "d"
        else:
            key = "c" if topWidthRatio > 0.55 else "s"
        
        sym = {"h":"♥","d":"♦","c":"♣","s":"♠"}
        ok = key == exp
        mark = "✅" if ok else "❌"
        if ok: correct += 1
        
        print(f"  {name}: {color} cnt={cnt} maxW={max_w} topW={top_max_w} ratio={topWidthRatio:.2f} → {sym[key]} {mark}")
    
    return correct

# 新截图 (turn + 换人 + 下注)
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

# 旧截图 (river, 6人)
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
