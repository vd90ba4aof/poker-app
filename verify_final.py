"""最终方案: 自适应rank-end检测 + 比例offset(25%) + 污染fallback"""
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
        sh = suit.shape[0]
        
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
            print(f"  {name}: too_few → ? ❌"); continue
        
        row_w = mask.sum(axis=1)
        peak_w = int(row_w.max())
        peak_y = int(np.argmax(row_w))
        
        # rank end detection: 连续3行<50%peak
        threshold = peak_w * 0.5
        rank_end = sh; consec = 0
        for y in range(peak_y+1, sh):
            if row_w[y] < threshold:
                consec += 1
                if consec >= 3: rank_end = y; break
            else: consec = 0
        
        # 比例offset: rank_end下方25%的suit区域高度
        offset = int(sh * 0.25)
        measure_y = min(rank_end + offset, sh-1)
        suit_w = int(row_w[measure_y])
        contaminated = suit_w > 60
        
        # 分类
        if not contaminated:
            if color == "red":
                key = "h" if suit_w > 12 else "d"
            else:
                key = "c" if suit_w > 12 else "s"
        else:
            # 污染fallback: 颜色兜底
            key = "h" if color=="red" else "s"
        
        sym = {"h":"♥","d":"♦","c":"♣","s":"♠"}
        ok = key == exp
        mark = "✅" if ok else "❌"
        if ok: correct += 1
        
        flag = " [CONT→fallback]" if contaminated else ""
        print(f"  {name}: {color} peak={peak_w}@y{peak_y/sh*100:.0f}% rankEnd@y{rank_end/sh*100:.0f}% measure@y{measure_y/sh*100:.0f}% suitW={suit_w}{flag} → {sym[key]} {mark}")
    
    return correct

# 新截图
PATH_NEW = "/app/data/所有对话/主对话/用户上传/Screenshot_2026-06-13-02-20-54-87_1d58bed7e226cc412b0128cc8fb4cf50.jpg"
new_cards = [
    ("8", 0.158, 0.445, 0.280, 0.545, False, "c"),
    ("8♥", 0.296, 0.445, 0.419, 0.545, False, "h"),
    ("A♠", 0.437, 0.445, 0.559, 0.545, False, "s"),
    ("3♦", 0.576, 0.445, 0.698, 0.545, False, "d"),
    ("9♠", 0.715, 0.445, 0.837, 0.545, False, "s"),
    ("7♠", 0.046, 0.746, 0.148, 0.873, True,  "s"),
    ("7♦", 0.167, 0.746, 0.279, 0.873, True,  "d"),
]
print(f"{'='*70}\n新截图验证\n{'='*70}")
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
print(f"\n{'='*70}\n旧截图交叉验证\n{'='*70}")
o = test_all(PATH_OLD, old_cards, "旧")

print(f"\n{'='*70}")
print(f"最终结果: 新截图 {n}/7 | 旧截图 {o}/7 | 综合 {n+o}/14 ({(n+o)/14*100:.0f}%)")
print(f"{'='*70}")
