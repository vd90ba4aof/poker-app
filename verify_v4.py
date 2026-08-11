"""V4: 尝试 middle/peak ratio 区分 ♥/♦, lower/peak ratio 区分 ♣/♠"""
from PIL import Image
import numpy as np

def test_on(PATH, test_cards, label):
    img = Image.open(PATH)
    W, H = img.size
    np_img = np.array(img)
    
    def crop(x1_pct, y1_pct, x2_pct, y2_pct):
        x1,y1 = int(x1_pct*W), int(y1_pct*H)
        x2,y2 = int(x2_pct*W), int(y2_pct*H)
        return np_img[y1:y2, x1:x2]
    
    print(f"\n{'='*70}")
    print(f"{label}")
    print(f"{'='*70}")
    
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
            print(f"  {name}: too_few → ? ❌"); continue
        
        row_w = mask.sum(axis=1)
        peak_w = int(row_w.max())
        peak_y = int(np.argmax(row_w))
        
        # rank end detection
        threshold = peak_w * 0.5
        rank_end = sh; consec = 0
        for y in range(peak_y+1, sh):
            if row_w[y] < threshold:
                consec += 1
                if consec >= 3: rank_end = y; break
            else: consec = 0
        
        offset = int(sh * 0.12)
        measure_y = min(rank_end + offset, sh-1)
        suit_w = int(row_w[measure_y])
        contaminated = suit_w > 60
        
        # Profile ratios
        mid_start = int(sh*0.30); mid_end = int(sh*0.60)
        mid_w = int(row_w[mid_start:mid_end].mean()) if mid_end > mid_start else 0
        mid_peak = mid_w / peak_w if peak_w > 0 else 0
        
        lower_start = sh//2
        min_lower = int(row_w[lower_start:].min()) if lower_start < sh else 0
        lower_peak = min_lower / peak_w if peak_w > 0 else 0
        
        # 方案A: offset测量
        if not contaminated:
            if color == "red":
                key_a = "h" if suit_w > 12 else "d"
            else:
                key_a = "c" if suit_w > 12 else "s"
        else:
            key_a = "h" if color=="red" else "s"  # fallback
        
        # 方案B: ratio-based (不受rank位置影响)
        if color == "red":
            key_b = "h" if mid_peak > 0.60 else "d"
        else:
            if not contaminated:
                key_b = "c" if lower_peak > 0.50 else "s"
            else:
                key_b = "s"  # fallback for contaminated black
        
        sym_map = {"h":"♥","d":"♦","c":"♣","s":"♠"}
        a_ok = key_a == exp; b_ok = key_b == exp
        flag = " [CONT]" if contaminated else ""
        print(f"  {name}: color={color} peak={peak_w} mid/peak={mid_peak:.2f} lower/peak={lower_peak:.2f} suitW={suit_w}{flag}")
        print(f"    A(offset): {sym_map.get(key_a,'?')}({'✅' if a_ok else '❌'}) | B(ratio): {sym_map.get(key_b,'?')}({'✅' if b_ok else '❌'}) | 期望={exp}")
        
        if a_ok: correct += 1
    
    return correct

# 新截图
new_cards = [
    ("8", 0.158, 0.445, 0.280, 0.545, False, "c"),
    ("8♥", 0.296, 0.445, 0.419, 0.545, False, "h"),
    ("A",  0.437, 0.445, 0.559, 0.545, False, "s"),
    ("3♦", 0.576, 0.445, 0.698, 0.545, False, "d"),
    ("9",  0.715, 0.445, 0.837, 0.545, False, "s"),
    ("7♠", 0.046, 0.746, 0.148, 0.873, True,  "s"),
    ("7♦", 0.167, 0.746, 0.279, 0.873, True,  "d"),
]
PATH_NEW = "/app/data/所有对话/主对话/用户上传/Screenshot_2026-06-13-02-20-54-87_1d58bed7e226cc412b0128cc8fb4cf50.jpg"
n = test_on(PATH_NEW, new_cards, "新截图: 8♣ 8♥ A♠ 3♦ 9♠ + 7♠ 7♦")

# 旧截图
old_cards = [
    ("K♣", 0.046, 0.746, 0.171, 0.873, True, "c"),
    ("Q♦", 0.161, 0.746, 0.300, 0.873, True, "d"),
    ("8♣", 0.157, 0.459, 0.283, 0.541, False, "c"),
    ("5",  0.296, 0.459, 0.421, 0.541, False, "s"),
    ("7♦", 0.440, 0.459, 0.561, 0.541, False, "d"),
    ("5♣", 0.579, 0.459, 0.704, 0.541, False, "c"),
    ("7♥", 0.718, 0.459, 0.843, 0.541, False, "h"),
]
PATH_OLD = "/app/data/所有对话/主对话/用户上传/Screenshot_2026-06-13-02-33-09-28_1d58bed7e226cc412b0128cc8fb4cf50.jpg"
o = test_on(PATH_OLD, old_cards, "旧截图: K♣ Q♦ 8♣ 5♠ 7♦ 5♣ 7♥")

print(f"\n{'='*70}")
print(f"汇总: 新={n}/7 旧={o}/7 综合={n+o}/14 ({(n+o)/14*100:.0f}%)")
