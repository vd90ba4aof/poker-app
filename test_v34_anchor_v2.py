"""
V3.4 锚定法测试 V3 — 调整阈值，只看公共牌
"""
import os
from math import sqrt
from PIL import Image

UPLOAD_DIR = "/app/data/所有对话/主对话/用户上传"
BASE_W, BASE_H = 1080, 2344

COMMUNITY_X_BASE = [(180, 325), (325, 460), (460, 595), (595, 730), (730, 870)]
COMMUNITY_Y_BASE = (1030, 1290)

SUIT_Y_START_FRAC = 0.35
SUIT_Y_END_FRAC = 0.90

TEST_SCREENS = [
    ("Screenshot_2026-06-12-22-45-58-77_1d58bed7e226cc412b0128cc8fb4cf50.jpg", [
        ("3", "h"), ("8", "d"), ("K", "c"),  # 公共牌
    ], "图1"),
    ("Screenshot_2026-06-13-02-20-54-87_1d58bed7e226cc412b0128cc8fb4cf50.jpg", [
        ("8", "c"), ("8", "h"), ("A", "s"), ("3", "d"), ("9", "s"),  # 公共牌
    ], "图2"),
    ("Screenshot_2026-06-13-02-24-21-01_1d58bed7e226cc412b0128cc8fb4cf50.jpg", [
        ("9", "d"), ("6", "d"), ("9", "?"),  # 公共牌
    ], "图3"),
    ("Screenshot_2026-06-13-02-26-11-07_1d58bed7e226cc412b0128cc8fb4cf50.jpg", [
        ("10", "h"), ("A", "h"), ("9", "h"), ("8", "d"), ("7", "c"),  # 公共牌
    ], "图4"),
    ("Screenshot_2026-06-13-02-28-21-32_1d58bed7e226cc412b0128cc8fb4cf50.jpg", [
        ("9", "s"), ("4", "s"), ("10", "d"),  # 公共牌
    ], "图5"),
    ("Screenshot_2026-06-13-02-33-09-28_1d58bed7e226cc412b0128cc8fb4cf50.jpg", [
        ("8", "c"), ("5", "?"), ("7", "d"), ("5", "c"), ("7", "h"),  # 公共牌
    ], "图6"),
    ("Screenshot_2026-06-13-03-07-43-21_1d58bed7e226cc412b0128cc8fb4cf50.jpg", [
        ("5", "?"), ("K", "d"), ("J", "d"),  # 公共牌
    ], "图7"),
    ("Screenshot_2026-06-13-12-05-28-88_1d58bed7e226cc412b0128cc8fb4cf50.jpg", [
        ("2", "h"), ("J", "c"), ("2", "s"), ("10", "h"),  # 公共牌
    ], "图8"),
    ("Screenshot_2026-06-13-12-08-56-83_1d58bed7e226cc412b0128cc8fb4cf50.jpg", [
        ("8", "d"), ("10", "c"), ("6", "c"),  # 公共牌
    ], "图9"),
    ("Screenshot_2026-06-13-16-12-04-26_1d58bed7e226cc412b0128cc8fb4cf50.jpg", [
        ("3", "h"), ("8", "d"), ("K", "?"),  # 公共牌
    ], "图10"),
]

def load_jpg_pixels(path):
    img = Image.open(path)
    w, h = img.size
    return list(img.convert("RGB").getdata()), w, h

def get_pixel(pixels, w, x, y):
    h = len(pixels) // w
    if 0 <= x < w and 0 <= y < h: return pixels[y * w + x]
    return None

def classify_color(pixels, w, x1, y1, x2, y2):
    red_px = 0; black_px = 0
    for y in range(y1, y2):
        for x in range(x1, x2):
            p = get_pixel(pixels, w, x, y)
            if p is None: continue
            cr, cg, cb = p
            if cr > 130 and cr - cg > 45 and cr - cb > 45: red_px += 1
            elif cr < 70 and cg < 70 and cb < 70 and abs(cg - cr) < 30: black_px += 1
    return red_px, black_px

def build_mask(pixels, w, x1, y1, x2, y2, is_red):
    reg_w = x2 - x1; reg_h = y2 - y1
    mask = []
    row_widths = [0] * reg_h
    for y in range(reg_h):
        row = []
        for x in range(reg_w):
            p = get_pixel(pixels, w, x1 + x, y1 + y)
            if p is None: row.append(False); continue
            cr, cg, cb = p
            if is_red: hit = cr > 130 and cr - cg > 45 and cr - cb > 45
            else: hit = cr < 70 and cg < 70 and cb < 70 and abs(cg - cr) < 30
            row.append(hit)
            if hit: row_widths[y] += 1
        mask.append(row)
    return mask, row_widths, reg_w, reg_h

def compute_top_x_std(mask, reg_w, reg_h):
    top_end = int(reg_h * 0.25)
    xs = []
    for y in range(min(top_end, reg_h)):
        for x in range(reg_w):
            if mask[y][x]: xs.append(x)
    if len(xs) < 3: return 0.0
    mean = sum(xs) / len(xs)
    variance = sum((x - mean) ** 2 for x in xs) / len(xs)
    return sqrt(variance)

def count_components(mask, reg_w, reg_h):
    visited = [[False] * reg_w for _ in range(reg_h)]
    count = 0
    for sy in range(reg_h):
        for sx in range(reg_w):
            if not mask[sy][sx] or visited[sy][sx]: continue
            count += 1
            queue = [(sy, sx)]; visited[sy][sx] = True
            while queue:
                cy, cx = queue.pop(0)
                for dy, dx in [(-1, 0), (1, 0), (0, -1), (0, 1)]:
                    ny, nx = cy + dy, cx + dx
                    if 0 <= ny < reg_h and 0 <= nx < reg_w:
                        if mask[ny][nx] and not visited[ny][nx]:
                            visited[ny][nx] = True; queue.append((ny, nx))
    return count

def anchor_classify_v2(mask, row_widths, reg_w, reg_h, is_red):
    """V3.4 锚定法 V2 — 放宽阈值"""
    total_px = sum(row_widths)
    if total_px < 5: return "?"

    widest_row = max(range(reg_h), key=lambda y: row_widths[y])
    wp = widest_row / reg_h

    sum_weighted_y = sum(y * row_widths[y] for y in range(reg_h))
    com_y = (sum_weighted_y / total_px) / reg_h

    half = reg_h // 2
    ts = sum(row_widths[:half])
    bs = sum(row_widths[half:])

    top_x_std = compute_top_x_std(mask, reg_w, reg_h)
    comp_count = count_components(mask, reg_w, reg_h)

    if is_red:
        # ♦ DIAMOND: 最宽行在中下部 + 重心居中 + 对称
        diamond_score = 0.0
        if 0.40 < wp < 0.85: diamond_score += 4.0  # 放宽范围
        if 0.35 < com_y < 0.70: diamond_score += 1.5  # 放宽范围
        if total_px > 0 and abs(ts - bs) / total_px < 0.40: diamond_score += 1.0  # 放宽对称性
        return "d" if diamond_score > 3.5 else "h"
    else:
        # ♠ SPADE: 顶部分散 + 底部重 + 碎片多
        spade_score = 0.0
        if top_x_std > 14: spade_score += 4.0
        elif top_x_std > 10: spade_score += 2.0
        if wp > 0.60: spade_score += 2.0  # 放宽
        if bs > ts: spade_score += 1.0
        if comp_count > 6: spade_score += 1.5
        return "s" if spade_score > 3.5 else "c"

def scale_coord(base_val, base_dim, screen_dim):
    return int(base_val * screen_dim / base_dim)

def analyze_suit(pixels, w, h, x1, y1, x2, y2):
    card_h = y2 - y1
    suit_y1 = y1 + int(card_h * SUIT_Y_START_FRAC)
    suit_y2 = y1 + int(card_h * SUIT_Y_END_FRAC)

    red_px, black_px = classify_color(pixels, w, x1, y1, x2, y2)
    is_red = red_px > black_px * 0.4

    mask, row_widths, reg_w, reg_h = build_mask(pixels, w, x1, suit_y1, x2, suit_y2, is_red)
    suit = anchor_classify_v2(mask, row_widths, reg_w, reg_h, is_red)
    return suit, is_red, red_px, black_px

# ===== 主测试 =====
total = 0; correct = 0; details = []

for filename, cards, note in TEST_SCREENS:
    filepath = os.path.join(UPLOAD_DIR, filename)
    if not os.path.exists(filepath): continue
    pixels, sw, sh = load_jpg_pixels(filepath)

    for i, (rank, expected_suit) in enumerate(cards):
        if expected_suit == "?": continue
        total += 1
        bx1, bx2 = COMMUNITY_X_BASE[i]; by1, by2 = COMMUNITY_Y_BASE
        sx1 = scale_coord(bx1, BASE_W, sw); sx2 = scale_coord(bx2, BASE_W, sw)
        sy1 = scale_coord(by1, BASE_H, sh); sy2 = scale_coord(by2, BASE_H, sh)
        suit, is_red, rpx, bpx = analyze_suit(pixels, sw, sh, sx1, sy1, sx2, sy2)
        ok = "✓" if suit == expected_suit else "✗"
        if suit == expected_suit: correct += 1
        details.append((note, f"公共牌{i}", f"{rank}{suit}", f"{rank}{expected_suit}", ok))
        print(f"  {note} 公共牌{i}: {rank}{suit} vs {rank}{expected_suit} {ok} (red={rpx},black={bpx},isRed={is_red})")

print(f"\n===== 汇总 =====")
if total > 0:
    print(f"总测试: {total}, 正确: {correct}, 准确率: {correct/total*100:.1f}%")
errors = [(n, p, r, e, o) for n, p, r, e, o in details if o == "✗"]
if errors:
    print(f"\n误判 ({len(errors)} 张):")
    for n, p, r, e, o in errors:
        print(f"  {n} {p}: 识别={r}, 标注={e}")
