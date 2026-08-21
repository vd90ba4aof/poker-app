#!/usr/bin/env python3
"""新截图完整验证：颜色分析 + y35/y40宽度决策树 vs 当前Kotlin方案"""
from PIL import Image
import numpy as np
import os

PATH = "/app/data/所有对话/主对话/用户上传/Screenshot_2026-06-13-02-20-54-87_1d58bed7e226cc412b0128cc8fb4cf50.jpg"
OUTPUT = "/app/data/所有对话/主对话/poker-app-latest/verification_output"
os.makedirs(OUTPUT, exist_ok=True)

img = Image.open(PATH)
W, H = img.size
np_img = np.array(img)
print(f"截图: {W}×{H}")

def crop(x1_pct, y1_pct, x2_pct, y2_pct, name=""):
    x1, y1 = int(x1_pct * W), int(y1_pct * H)
    x2, y2 = int(x2_pct * W), int(y2_pct * H)
    region = np_img[y1:y2, x1:x2]
    if name:
        Image.fromarray(region).save(f"{OUTPUT}/{name}.png")
    return region

def measure_widths(region, sy_pct, ey_pct, label=""):
    """测量suit区域内指定百分比行的宽度"""
    ch, cw = region.shape[:2]
    sy1 = int(ch * sy_pct)
    sy2 = int(ch * ey_pct)
    suit_area = region[sy1:sy2, :int(cw*0.65)]
    if suit_area.size == 0:
        return None
    
    r = suit_area[:,:,0].astype(int)
    g = suit_area[:,:,1].astype(int)
    b = suit_area[:,:,2].astype(int)
    
    red = int(((r>130)&(r-g>50)&(r-b>50)).sum())
    black = int(((r<90)&(g<90)&(b<90)).sum())
    
    if red > black*2:
        color = "red"
    elif black > red*2:
        color = "black"
    else:
        color = "unknown"
    
    # 二值mask
    if color == "red":
        mask = (r>130)&(r-g>50)&(r-b>50)
    elif color == "black":
        mask = (r<90)&(g<90)&(b<90)
    else:
        mask = ((r>130)&(r-g>50)&(r-b>50))|((r<90)&(g<90)&(b<90))
    
    cnt = int(mask.sum())
    if cnt < 10:
        return {"color": color, "cnt": cnt, "y35w": 0, "y40w": 0, "err": "too_few"}
    
    sh, sw = mask.shape
    row_widths = mask.sum(axis=1)
    
    # 关键测量点
    y35 = int(sh * 0.35)
    y40 = int(sh * 0.40)
    y30 = int(sh * 0.30)
    y45 = int(sh * 0.45)
    y50 = int(sh * 0.50)
    
    # 质心
    ys, xs = np.where(mask)
    comY = ys.mean() / sh if len(ys) > 0 else 0
    
    # 顶部/底部宽度
    top_edge_end = max(1, int(sh*0.2))
    bot_edge_start = int(sh*0.8)
    top_edge_w = int(row_widths[:top_edge_end].max())
    bot_edge_w = int(row_widths[bot_edge_start:].max())
    
    return {
        "color": color, "cnt": cnt, "comY": round(comY,3),
        "y30w": int(row_widths[y30]), "y35w": int(row_widths[y35]),
        "y40w": int(row_widths[y40]), "y45w": int(row_widths[y45]),
        "y50w": int(row_widths[y50]),
        "topEdgeW": top_edge_w, "botEdgeW": bot_edge_w,
        "topRatio": round(int(mask[:sh//2,:].sum())/cnt, 3) if cnt>0 else 0,
    }

def kotlin_current(data):
    """模拟当前Kotlin的comY+edge评分逻辑"""
    if data is None or data.get("err"): return "?", "?"
    comY = data["comY"]
    topRatio = data["topRatio"]
    te = data["topEdgeW"]; be = data["botEdgeW"]
    aw = 100  # 归一化
    teR = te/(aw*0.5+1); beR = be/(aw*0.5+1)
    
    c = data["color"]
    if c == "red":
        hS = (1-comY)*0.5 + topRatio*0.3 + 0.5*0.2
        if teR > beR*1.3: hS += 0.3
        dS = (1-abs(comY-0.5)*2)*0.5 + 0.5*0.4 + topRatio*0.1
        best = "h" if hS > dS else "d"
    elif c == "black":
        cS = (1-comY)*0.5 + topRatio*0.3 + 0.5*0.2
        if teR > beR*1.5: cS += 0.3
        sS = comY*0.5 + (1-topRatio)*0.3 + 0.5*0.2
        if beR > teR*1.2: sS += 0.2
        best = "c" if cS > sS else "s"
    else:
        best = "?"
    sym = {"h":"♥","d":"♦","c":"♣","s":"♠"}
    return best, sym.get(best,"?")

def decision_tree(data):
    """y35/y40宽度决策树"""
    if data is None or data.get("err"): return "?", "?"
    y35 = data["y35w"]
    y40 = data["y40w"]
    c = data["color"]
    
    sym = {"h":"♥","d":"♦","c":"♣","s":"♠"}
    
    if c == "red":
        if y35 > 25:
            return "h", "♥"
        else:
            return "d", "♦"
    elif c == "black":
        if y35 > 25:
            return "c", ""
        else:
            # y40区分♠/♣
            if y40 > 20:
                return "s", "♠"
            else:
                return "c", "♣"
    return "?", "?"

# ========== 测试用例 ==========
# 公共牌: 8♣ 8♥ A♠ 3♦ 9♠, 手牌: 7♠ 7♦
test_cards = [
    # (name, x1, y1, x2, y2, is_hand, expected_suit)
    ("8♣", 0.158, 0.445, 0.280, 0.545, False, "c"),
    ("8♥", 0.296, 0.445, 0.419, 0.545, False, "h"),
    ("A♠", 0.437, 0.445, 0.559, 0.545, False, "s"),
    ("3♦", 0.576, 0.445, 0.698, 0.545, False, "d"),
    ("9", 0.715, 0.445, 0.837, 0.545, False, "s"),
    ("7♠", 0.046, 0.746, 0.148, 0.873, True,  "s"),
    ("7♦", 0.167, 0.746, 0.279, 0.873, True,  "d"),
]

# 公共牌: suitStartY=0.35h, suitEndY=0.75h
# 手牌:   suitStartY=0.45h, suitEndY=0.75h

print("="*70)
print("新截图验证: 8♣ 8♥ A♠ 3♦ 9♠ + 7♠ 7♦")
print("="*70)

print("\n--- 测量数据 ---")
results = []
for name, x1, y1, x2, y2, is_hand, exp in test_cards:
    region = crop(x1, y1, x2, y2, f"new_{name}")
    ch, cw = region.shape[:2]
    
    sy = 0.45 if is_hand else 0.35
    data = measure_widths(region, sy, 0.75, name)
    
    if data:
        d = {k:v for k,v in data.items() if k not in ("color","cnt","err")}
        print(f"  {name} ({cw}×{ch}px) color={data['color']} cnt={data['cnt']}: comY={d.get('comY','?')} y30={d.get('y30w','?')} y35={d.get('y35w','?')} y40={d.get('y40w','?')} y45={d.get('y45w','?')} y50={d.get('y50w','?')} topEdge={d.get('topEdgeW','?')} botEdge={d.get('botEdgeW','?')}")
    
    k_key, k_sym = kotlin_current(data)
    dt_key, dt_sym = decision_tree(data)
    
    k_ok = k_key == exp
    dt_ok = dt_key == exp
    k_mark = "✅" if k_ok else ""
    dt_mark = "✅" if dt_ok else "❌"
    
    results.append((name, exp, data, k_key, k_sym, k_ok, dt_key, dt_sym, dt_ok))
    print(f"    当前Kotlin: {k_sym}({k_key}) {k_mark} | 决策树: {dt_sym}({dt_key}) {dt_mark}")

print("\n--- 汇总 ---")
k_correct = sum(1 for r in results if r[5])
dt_correct = sum(1 for r in results if r[8])
print(f"当前Kotlin方案: {k_correct}/{len(results)} ({k_correct/len(results)*100:.0f}%)")
print(f"y35/y40决策树:  {dt_correct}/{len(results)} ({dt_correct/len(results)*100:.0f}%)")

# 底池
print("\n--- 底池 (y=35%-42%) ---")
pot = crop(0.25, 0.35, 0.75, 0.42, "pot_new2")
r=pot[:,:,0].astype(int); g=pot[:,:,1].astype(int); b=pot[:,:,2].astype(int)
print(f"黄色={int(((r>200)&(g>150)&(b<100)).sum())} 白色={int(((r>200)&(g>200)&(b>200)).sum())}")
