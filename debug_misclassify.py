"""
调试误判：打印典型误判牌的形状特征
"""
import os
from math import sqrt
from PIL import Image

UPLOAD_DIR = "/app/data/所有对话/主对话/用户上传"
BASE_W, BASE_H = 1080, 2344

SUIT_Y_START_FRAC = 0.35
SUIT_Y_END_FRAC = 0.90

def load_jpg_pixels(path):
    img = Image.open(path)
    w, h = img.size
    return list(img.convert("RGB").getdata()), w, h

def get_pixel(pixels, w, x, y):
    h = len(pixels) // w
    if 0 <= x < w and 0 <= y < h: return pixels[y * w + x]
    return None

def analyze_detail(pixels, w, h, x1, y1, x2, y2, is_red, label):
    card_h = y2 - y1
    suit_y1 = y1 + int(card_h * SUIT_Y_START_FRAC)
    suit_y2 = y1 + int(card_h * SUIT_Y_END_FRAC)
    reg_w = x2 - x1; reg_h = suit_y2 - suit_y1

    mask = []
    row_widths = [0] * reg_h
    for y in range(reg_h):
        row = []
        for x in range(reg_w):
            p = get_pixel(pixels, w, x1 + x, suit_y1 + y)
            if p is None: row.append(False); continue
            cr, cg, cb = p
            if is_red: hit = cr > 130 and cr - cg > 45 and cr - cb > 45
            else: hit = cr < 70 and cg < 70 and cb < 70 and abs(cg - cr) < 30
            row.append(hit)
            if hit: row_widths[y] += 1
        mask.append(row)

    total_px = sum(row_widths)
    widest_row = max(range(reg_h), key=lambda y: row_widths[y])
    wp = widest_row / reg_h
    sum_weighted_y = sum(y * row_widths[y] for y in range(reg_h))
    com_y = (sum_weighted_y / total_px) / reg_h if total_px > 0 else 0
    half = reg_h // 2
    ts = sum(row_widths[:half])
    bs = sum(row_widths[half:])

    # top x std
    top_end = int(reg_h * 0.25)
    xs = []
    for y in range(min(top_end, reg_h)):
        for x in range(reg_w):
            if mask[y][x]: xs.append(x)
    top_x_std = 0.0
    if len(xs) >= 3:
        mean = sum(xs) / len(xs)
        top_x_std = sqrt(sum((x - mean) ** 2 for x in xs) / len(xs))

    # components
    visited = [[False] * reg_w for _ in range(reg_h)]
    comp_count = 0
    for sy in range(reg_h):
        for sx in range(reg_w):
            if not mask[sy][sx] or visited[sy][sx]: continue
            comp_count += 1
            queue = [(sy, sx)]; visited[sy][sx] = True
            while queue:
                cy, cx = queue.pop(0)
                for dy, dx in [(-1, 0), (1, 0), (0, -1), (0, 1)]:
                    ny, nx = cy + dy, cx + dx
                    if 0 <= ny < reg_h and 0 <= nx < reg_w:
                        if mask[ny][nx] and not visited[ny][nx]:
                            visited[ny][nx] = True; queue.append((ny, nx))

    print(f"\n{label}: total={total_px}, wp={wp:.3f}, comY={com_y:.3f}, topXStd={top_x_std:.1f}, comps={comp_count}")
    print(f"  ts={ts}, bs={ts}, |ts-bs|/total={abs(ts-bs)/total_px:.3f}" if total_px > 0 else "")

    if is_red:
        ds = 0.0
        if 0.50 < wp < 0.80: ds += 4.0
        if 0.40 < com_y < 0.62: ds += 1.5
        if total_px > 0 and abs(ts - bs) / total_px < 0.35: ds += 1.0
        print(f"  DIAMOND score={ds:.1f} (>3.5=♦)")
    else:
        ss = 0.0
        if top_x_std > 14: ss += 4.0
        elif top_x_std > 10: ss += 2.0
        if wp > 0.65: ss += 2.0
        if bs > ts: ss += 1.0
        if comp_count > 6: ss += 1.5
        print(f"  SPADE score={ss:.1f} (>3.5=♠)")

    # 打印行宽度剖面
    print(f"  Row profile (每8行):")
    for y in range(0, reg_h, 8):
        bar = "█" * (row_widths[y] // 3)
        print(f"    y={y:3d}/{reg_h}: {row_widths[y]:4d} {bar}")

# 测试几个误判
cases = [
    # (filename, bx1, bx2, by1, by2, is_red, label)
    ("Screenshot_2026-06-12-22-45-58-77_1d58bed7e226cc412b0128cc8fb4cf50.jpg", 85, 180, 1760, 2000, False, "图1手牌1 J♥(误判Js)"),
    ("Screenshot_2026-06-13-02-20-54-87_1d58bed7e226cc412b0128cc8fb4cf50.jpg", 325, 460, 1030, 1290, True, "图2公共牌1 8♥(误判8d→8h实际✓, 看8♦)"),
    ("Screenshot_2026-06-12-22-45-58-77_1d58bed7e226cc412b0128cc8fb4cf50.jpg", 325, 460, 1030, 1290, True, "图1公共牌1 8♦(误判8h)"),
    ("Screenshot_2026-06-13-02-33-09-28_1d58bed7e226cc412b0128cc8fb4cf50.jpg", 180, 325, 1030, 1290, False, "图6公共牌0 8♣(正确✓)"),
    ("Screenshot_2026-06-13-02-28-21-32_1d58bed7e226cc412b0128cc8fb4cf50.jpg", 180, 325, 1030, 1290, False, "图5公共牌0 9(误判9c)"),
]

def sc(v, base, screen):
    return int(v * screen / base)

for fn, bx1, bx2, by1, by2, is_red, label in cases:
    fp = os.path.join(UPLOAD_DIR, fn)
    if not os.path.exists(fp): continue
    pixels, sw, sh = load_jpg_pixels(fp)
    sx1, sx2 = sc(bx1, BASE_W, sw), sc(bx2, BASE_W, sw)
    sy1, sy2 = sc(by1, BASE_H, sh), sc(by2, BASE_H, sh)
    analyze_detail(pixels, sw, sh, sx1, sy1, sx2, sy2, is_red, label)
