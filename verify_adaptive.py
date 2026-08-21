"""自适应rank-end检测 + offset测量方案验证"""
from PIL import Image
import numpy as np
import os

PATH = "/app/data/所有对话/主对话/用户上传/Screenshot_2026-06-13-02-20-54-87_1d58bed7e226cc412b0128cc8fb4cf50.jpg"
img = Image.open(PATH)
W, H = img.size
np_img = np.array(img)

def crop(x1_pct, y1_pct, x2_pct, y2_pct):
    x1,y1 = int(x1_pct*W), int(y1_pct*H)
    x2,y2 = int(x2_pct*W), int(y2_pct*H)
    return np_img[y1:y2, x1:x2]

def get_mask(region, color):
    r = region[:,:,0].astype(int)
    g = region[:,:,1].astype(int)
    b = region[:,:,2].astype(int)
    if color == "red":
        return (r>130)&(r-g>50)&(r-b>50)
    elif color == "black":
        return (r<90)&(g<90)&(b<90)
    return ((r>130)&(r-g>50)&(r-b>50))|((r<90)&(g<90)&(b<90))

def analyze(region, is_hand):
    ch, cw = region.shape[:2]
    sy1 = int(ch*0.45) if is_hand else int(ch*0.35)
    sy2 = int(ch*0.75)
    suit_area = region[sy1:sy2, :int(cw*0.65)]
    sh, sw = suit_area.shape[:2]
    
    r = suit_area[:,:,0].astype(int)
    g = suit_area[:,:,1].astype(int)
    b = suit_area[:,:,2].astype(int)
    red = int(((r>130)&(r-g>50)&(r-b>50)).sum())
    black = int(((r<90)&(g<90)&(b<90)).sum())
    
    if red > black*2: color = "red"
    elif black > red*2: color = "black"
    else: color = "unknown"
    
    mask = get_mask(suit_area, color)
    cnt = int(mask.sum())
    if cnt < 10: return {"color": color, "err": "too_few"}
    
    row_w = mask.sum(axis=1)
    peak_w = int(row_w.max())
    peak_y = int(np.argmax(row_w))
    
    # 检测rank结束位置：从peak_y向下扫描，找连续3行宽度<50%peak
    threshold = peak_w * 0.5
    rank_end = sh  # 默认到最后
    consec = 0
    for y in range(peak_y + 1, sh):
        if row_w[y] < threshold:
            consec += 1
            if consec >= 3:
                rank_end = y
                break
        else:
            consec = 0
    
    # 在rank_end下方12%处测量suit宽度
    offset = int(sh * 0.12)
    measure_y = rank_end + offset
    measure_y = min(measure_y, sh - 1)
    suit_w_at_offset = int(row_w[measure_y])
    
    # 检查污染（>60说明扫到玩家名字）
    contaminated = suit_w_at_offset > 60
    
    # 计算lower/peak比（用于fallback）
    lower_start = sh // 2
    min_lower = int(row_w[lower_start:].min()) if lower_start < sh else 0
    lower_peak_ratio = min_lower / peak_w if peak_w > 0 else 0
    
    return {
        "color": color, "cnt": cnt, "peakW": peak_w, "peakY_pct": round(peak_y/sh*100,1),
        "rankEnd_pct": round(rank_end/sh*100,1), "measureY_pct": round(measure_y/sh*100,1),
        "suitW_offset": suit_w_at_offset, "contaminated": contaminated,
        "lowerPeakRatio": round(lower_peak_ratio, 3),
    }

def classify(data):
    if data.get("err"): return "?", "?"
    c = data["color"]
    
    if not data["contaminated"]:
        # 主方案：offset测量
        w = data["suitW_offset"]
        if c == "red":
            if w > 13: return "h", "♥"
            else: return "d", "♦"
        elif c == "black":
            if w > 13: return "c", "♣"
            else: return "s", "♠"
    
    # Fallback：颜色兜底
    if c == "red": return "h", "♥"
    elif c == "black": return "s", "♠"
    return "?", "?"

# 测试
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
print("自适应rank-end检测方案验证")
print("="*80)

correct = 0
for name, x1, y1, x2, y2, is_hand, exp in test_cards:
    region = crop(x1, y1, x2, y2)
    data = analyze(region, is_hand)
    key, sym = classify(data)
    ok = key == exp
    mark = "✅" if ok else "❌"
    if ok: correct += 1
    
    flag = " [CONTAMINATED→fallback]" if data.get("contaminated") else ""
    print(f"  {name}: color={data['color']} peak={data['peakW']}@y{data['peakY_pct']}% rankEnd@y{data['rankEnd_pct']}% measure@y{data['measureY_pct']}% suitW={data['suitW_offset']}{flag}")
    print(f"    → {sym}({key}) {mark} (期望{exp})")

print(f"\n准确率: {correct}/{len(test_cards)} ({correct/len(test_cards)*100:.0f}%)")
