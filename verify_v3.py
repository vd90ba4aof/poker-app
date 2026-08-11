"""V3: 修复两个问题 1) 8♣阈值 2) 7♦颜色unknown"""
from PIL import Image
import numpy as np

PATH = "/app/data/所有对话/主对话/用户上传/Screenshot_2026-06-13-02-20-54-87_1d58bed7e226cc412b0128cc8fb4cf50.jpg"
img = Image.open(PATH)
W, H = img.size
np_img = np.array(img)

def crop(x1_pct, y1_pct, x2_pct, y2_pct):
    x1,y1 = int(x1_pct*W), int(y1_pct*H)
    x2,y2 = int(x2_pct*W), int(y2_pct*H)
    return np_img[y1:y2, x1:x2]

def analyze(region, is_hand):
    ch, cw = region.shape[:2]
    sy1 = int(ch*0.45) if is_hand else int(ch*0.35)
    sy2 = int(ch*0.75)
    suit_area = region[sy1:sy2, :int(cw*0.65)]
    sh, sw = suit_area.shape[:2]
    
    r = suit_area[:,:,0].astype(int)
    g = suit_area[:,:,1].astype(int)
    b = suit_area[:,:,2].astype(int)
    red_mask = (r>130)&(r-g>50)&(r-b>50)
    black_mask = (r<90)&(g<90)&(b<90)
    red = int(red_mask.sum())
    black = int(black_mask.sum())
    
    # 颜色判定：放宽阈值，同时检测"是否存在显著红色"
    if red > 50 and red > black * 0.5:  # 有显著红色且不被黑色完全压制
        color = "red"
    elif black > 50 and black > red * 2:
        color = "black"
    elif red > black:
        color = "red"
    else:
        color = "black"
    
    if color == "red":
        mask = red_mask
    else:
        mask = black_mask
    
    cnt = int(mask.sum())
    if cnt < 10: return {"color": color, "err": "too_few", "red": red, "black": black}
    
    row_w = mask.sum(axis=1)
    peak_w = int(row_w.max())
    peak_y = int(np.argmax(row_w))
    
    # 检测rank结束
    threshold = peak_w * 0.5
    rank_end = sh
    consec = 0
    for y in range(peak_y + 1, sh):
        if row_w[y] < threshold:
            consec += 1
            if consec >= 3:
                rank_end = y
                break
        else:
            consec = 0
    
    offset = int(sh * 0.12)
    measure_y = min(rank_end + offset, sh - 1)
    suit_w = int(row_w[measure_y])
    contaminated = suit_w > 60
    
    lower_start = sh // 2
    min_lower = int(row_w[lower_start:].min()) if lower_start < sh else 0
    lower_peak_ratio = min_lower / peak_w if peak_w > 0 else 0
    
    return {
        "color": color, "red": red, "black": black,
        "peakW": peak_w, "peakY_pct": round(peak_y/sh*100,1),
        "rankEnd_pct": round(rank_end/sh*100,1), "measureY_pct": round(measure_y/sh*100,1),
        "suitW": suit_w, "contaminated": contaminated,
        "lowerPeakRatio": round(lower_peak_ratio, 3),
    }

def classify(data):
    if data.get("err"): return "?", "?"
    c = data["color"]
    
    if not data.get("contaminated"):
        w = data["suitW"]
        if c == "red":
            if w > 12: return "h", "♥"  # 阈值从13改为12
            else: return "d", "♦"
        else:  # black
            if w > 12: return "c", "♣"
            else: return "s", "♠"
    
    # Fallback: 颜色兜底
    if c == "red": return "h", "♥"
    return "s", "♠"

test_cards = [
    ("8", 0.158, 0.445, 0.280, 0.545, False, "c"),
    ("8♥", 0.296, 0.445, 0.419, 0.545, False, "h"),
    ("A",  0.437, 0.445, 0.559, 0.545, False, "s"),
    ("3♦", 0.576, 0.445, 0.698, 0.545, False, "d"),
    ("9",  0.715, 0.445, 0.837, 0.545, False, "s"),
    ("7♠", 0.046, 0.746, 0.148, 0.873, True,  "s"),
    ("7♦", 0.167, 0.746, 0.279, 0.873, True,  "d"),
]

print("="*80)
print("V3 自适应方案 (阈值12 + 颜色放宽)")
print("="*80)

correct = 0
for name, x1, y1, x2, y2, is_hand, exp in test_cards:
    region = crop(x1, y1, x2, y2)
    data = analyze(region, is_hand)
    key, sym = classify(data)
    ok = key == exp
    mark = "✅" if ok else "❌"
    if ok: correct += 1
    
    flag = " [CONTAMINATED]" if data.get("contaminated") else ""
    print(f"  {name}: color={data['color']} r={data['red']} b={data['black']} peak={data['peakW']}@y{data['peakY_pct']}% rankEnd@y{data['rankEnd_pct']}% suitW={data['suitW']}{flag}")
    print(f"    → {sym}({key}) {mark} (期望{exp})")

print(f"\n准确率: {correct}/{len(test_cards)} ({correct/len(test_cards)*100:.0f}%)")

# 同时用旧截图验证
print("\n" + "="*80)
print("旧截图交叉验证 (K♣ Q♦ 8♣ 5♠ 7♦ 5♣ 7♥)")
print("="*80)

PATH2 = "/app/data/所有对话/主对话/用户上传/Screenshot_2026-06-13-02-33-09-28_1d58bed7e226cc412b0128cc8fb4cf50.jpg"
img2 = Image.open(PATH2)
W2, H2 = img2.size
np_img2 = np.array(img2)

def crop2(x1_pct, y1_pct, x2_pct, y2_pct):
    x1,y1 = int(x1_pct*W2), int(y1_pct*H2)
    x2,y2 = int(x2_pct*W2), int(y2_pct*H2)
    return np_img2[y1:y2, x1:x2]

old_cards = [
    ("K♣", 0.046, 0.746, 0.171, 0.873, True, "c"),
    ("Q♦", 0.161, 0.746, 0.300, 0.873, True, "d"),
    ("8♣", 0.157, 0.459, 0.283, 0.541, False, "c"),
    ("5",  0.296, 0.459, 0.421, 0.541, False, "s"),
    ("7♦", 0.440, 0.459, 0.561, 0.541, False, "d"),
    ("5♣", 0.579, 0.459, 0.704, 0.541, False, "c"),
    ("7♥", 0.718, 0.459, 0.843, 0.541, False, "h"),
]

old_correct = 0
for name, x1, y1, x2, y2, is_hand, exp in old_cards:
    region = crop2(x1, y1, x2, y2)
    data = analyze(region, is_hand)
    key, sym = classify(data)
    ok = key == exp
    mark = "✅" if ok else "❌"
    if ok: old_correct += 1
    
    flag = " [CONTAMINATED]" if data.get("contaminated") else ""
    print(f"  {name}: color={data['color']} r={data['red']} b={data['black']} peak={data['peakW']} suitW={data['suitW']}{flag} → {sym}({key}) {mark}")

print(f"\n旧截图准确率: {old_correct}/{len(old_cards)} ({old_correct/len(old_cards)*100:.0f}%)")
print(f"\n综合: {correct + old_correct}/{len(test_cards) + len(old_cards)} ({(correct+old_correct)/(len(test_cards)+len(old_cards))*100:.0f}%)")
