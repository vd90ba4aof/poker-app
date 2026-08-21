import cv2
import numpy as np
import os

base_path = "/app/data/所有对话/主对话/用户上传"

# 18 test cards with ground truth
# (type, filename, bbox_or_None, expected_suit, label)
test_cards = [
    # 4 template images (user cropped, no rank number, only suit symbols)
    ("template", "IMG_20260808_213049.jpg", None, "spade", "tpl_spade"),
    ("template", "IMG_20260808_212930.jpg", None, "club", "tpl_club"),
    ("template", "IMG_20260808_212825.jpg", None, "diamond", "tpl_diamond"),
    ("template", "IMG_20260808_212728.jpg", None, "heart", "tpl_heart"),
    # Community cards from screenshot (3S, AC, 10D, AH)
    ("screenshot", "Screenshot_2026-08-05-21-28-13-92_1d58bed7e226cc412b0128cc8fb4cf50.jpg",
     (0.157, 0.460, 0.283, 0.543), "spade", "3S_comm"),
    ("screenshot", "Screenshot_2026-08-05-21-28-13-92_1d58bed7e226cc412b0128cc8fb4cf50.jpg",
     (0.296, 0.460, 0.422, 0.543), "club", "AC_comm"),
    ("screenshot", "Screenshot_2026-08-05-21-28-13-92_1d58bed7e226cc412b0128cc8fb4cf50.jpg",
     (0.435, 0.460, 0.561, 0.543), "diamond", "10D_comm"),
    ("screenshot", "Screenshot_2026-08-05-21-28-13-92_1d58bed7e226cc412b0128cc8fb4cf50.jpg",
     (0.574, 0.460, 0.700, 0.543), "heart", "AH_comm"),
    # Hand cards top row (8C, 8H, AS, 3D)
    ("screenshot", "Screenshot_2026-08-05-21-28-13-92_1d58bed7e226cc412b0128cc8fb4cf50.jpg",
     (0.250, 0.660, 0.370, 0.830), "club", "8C_hand"),
    ("screenshot", "Screenshot_2026-08-05-21-28-13-92_1d58bed7e226cc412b0128cc8fb4cf50.jpg",
     (0.380, 0.660, 0.500, 0.830), "heart", "8H_hand"),
    ("screenshot", "Screenshot_2026-08-05-21-28-13-92_1d58bed7e226cc412b0128cc8fb4cf50.jpg",
     (0.510, 0.660, 0.630, 0.830), "spade", "AS_hand"),
    ("screenshot", "Screenshot_2026-08-05-21-28-13-92_1d58bed7e226cc412b0128cc8fb4cf50.jpg",
     (0.640, 0.660, 0.760, 0.830), "diamond", "3D_hand"),
    # Hand cards bottom row (9S, 7S, 7D) - previously failed on 7S and 7D
    ("screenshot", "Screenshot_2026-08-05-21-28-13-92_1d58bed7e226cc412b0128cc8fb4cf50.jpg",
     (0.250, 0.840, 0.370, 1.000), "spade", "9S_bot"),
    ("screenshot", "Screenshot_2026-08-05-21-28-13-92_1d58bed7e226cc412b0128cc8fb4cf50.jpg",
     (0.380, 0.840, 0.500, 1.000), "spade", "7S_bot"),
    ("screenshot", "Screenshot_2026-08-05-21-28-13-92_1d58bed7e226cc412b0128cc8fb4cf50.jpg",
     (0.510, 0.840, 0.630, 1.000), "diamond", "7D_bot"),
    # River cards top row (KC, QD, 8C, 5S, 7D) - previously failed on QD
    ("screenshot", "Screenshot_2026-06-13-02-33-09-28_1d58bed7e226cc412b0128cc8fb4cf50.jpg",
     (0.130, 0.500, 0.250, 0.670), "club", "KC_riv"),
    ("screenshot", "Screenshot_2026-06-13-02-33-09-28_1d58bed7e226cc412b0128cc8fb4cf50.jpg",
     (0.260, 0.500, 0.380, 0.670), "diamond", "QD_riv"),
    ("screenshot", "Screenshot_2026-06-13-02-33-09-28_1d58bed7e226cc412b0128cc8fb4cf50.jpg",
     (0.390, 0.500, 0.510, 0.670), "club", "8C_riv"),
    ("screenshot", "Screenshot_2026-06-13-02-33-09-28_1d58bed7e226cc412b0128cc8fb4cf50.jpg",
     (0.520, 0.500, 0.640, 0.670), "spade", "5S_riv"),
    ("screenshot", "Screenshot_2026-06-13-02-33-09-28_1d58bed7e226cc412b0128cc8fb4cf50.jpg",
     (0.650, 0.500, 0.770, 0.670), "diamond", "7D_riv"),
    # River cards bottom row (5C, 7H)
    ("screenshot", "Screenshot_2026-06-13-02-33-09-28_1d58bed7e226cc412b0128cc8fb4cf50.jpg",
     (0.260, 0.680, 0.380, 0.850), "club", "5C_rbot"),
    ("screenshot", "Screenshot_2026-06-13-02-33-09-28_1d58bed7e226cc412b0128cc8fb4cf50.jpg",
     (0.520, 0.680, 0.640, 0.850), "heart", "7H_rbot"),
]


def analyze_card(card_type, filename, bbox, expected_suit):
    """
    Improved suit recognition using:
    1. Color classification (red/black)
    2. Connected component analysis
    3. Position-based scoring to prefer center suit symbol
    4. Width profile for shape classification
    """
    filepath = os.path.join(base_path, filename)
    img = cv2.imread(filepath)
    if img is None:
        return None, {}, []

    h_img, w_img = img.shape[:2]

    if card_type == "template":
        card = img
    else:
        x1, y1 = int(bbox[0] * w_img), int(bbox[1] * h_img)
        x2, y2 = int(bbox[2] * w_img), int(bbox[3] * h_img)
        card = img[y1:y2, x1:x2]

    ch, cw = card.shape[:2]

    # Inner region (exclude 10% border)
    mx = int(cw * 0.10)
    my = int(ch * 0.10)
    inner = card[my:ch - my, mx:cw - mx]
    ih, iw = inner.shape[:2]

    # --- Color detection ---
    gray = cv2.cvtColor(inner, cv2.COLOR_BGR2GRAY)
    b_ch, g_ch, r_ch = cv2.split(inner)

    black_mask = ((gray < 80).astype(np.uint8)) * 255
    red_mask = ((r_ch.astype(int) - g_ch.astype(int) > 20) &
                (r_ch.astype(int) - b_ch.astype(int) > 20) &
                (r_ch > 100)).astype(np.uint8) * 255

    black_count = np.count_nonzero(black_mask)
    red_count = np.count_nonzero(red_mask)
    is_red = red_count > black_count
    mask = red_mask if is_red else black_mask

    # Morphological cleanup
    kernel = np.ones((3, 3), np.uint8)
    mask = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, kernel, iterations=1)
    mask = cv2.morphologyEx(mask, cv2.MORPH_OPEN, kernel, iterations=1)

    # --- Connected components ---
    num_labels, labels, stats, centroids = cv2.connectedComponentsWithStats(mask, connectivity=8)

    candidates = []
    for i in range(1, num_labels):
        x, y, bw, bh, area = stats[i]
        cx, cy = centroids[i]

        # Min size filter
        if area < (iw * ih * 0.01):
            continue
        if bw < 4 or bh < 4:
            continue

        aspect = max(bw, bh) / (min(bw, bh) + 1e-6)

        # Skip very elongated (rank text digits tend to be tall/narrow)
        if aspect > 2.5:
            continue

        # Skip components spanning > 70% (border remnants)
        if bw > iw * 0.70 or bh > ih * 0.70:
            continue

        # Normalized center position
        ncx = cx / iw  # 0~1
        ncy = cy / ih  # 0~1

        # Component bounding box relative to inner region
        rel_w = bw / iw
        rel_h = bh / ih

        # Fill ratio
        fill = area / (bw * bh + 1e-6)

        # Squareness
        squareness = 1.0 - abs(bw - bh) / max(bw, bh)

        # --- Position score ---
        # Suit symbol should be near center of card (0.3~0.7 range both axes)
        # Rank text is in upper-left (ncx < 0.3, ncy < 0.3)
        center_dist = np.sqrt((ncx - 0.5) ** 2 + (ncy - 0.5) ** 2)
        position_score = max(0, 1.0 - center_dist / 0.5)

        # Extra penalty for components in upper-left quadrant (rank text zone)
        upper_left_penalty = 0.2 if (ncx < 0.35 and ncy < 0.35) else 1.0

        # --- Width profile ---
        comp_mask = (labels == i).astype(np.uint8)
        comp_region = comp_mask[y:y + bh, x:x + bw]
        col_sums = comp_region.sum(axis=0).astype(float)
        max_col = col_sums.max() + 1e-6
        norm_profile = col_sums / max_col

        top5 = np.mean(np.sort(norm_profile)[-max(1, int(len(norm_profile) * 0.05)):])
        top25 = np.mean(np.sort(norm_profile)[-max(1, int(len(norm_profile) * 0.25)):])
        bot5 = np.mean(np.sort(norm_profile)[:max(1, int(len(norm_profile) * 0.05))])

        # --- Combined score ---
        # Prefer: large area, good fill, square-ish, centered position, not in upper-left
        score = area * fill * squareness * position_score * upper_left_penalty

        candidates.append({
            'label': i, 'area': area, 'bw': bw, 'bh': bh,
            'aspect': aspect, 'fill': fill, 'squareness': squareness,
            'ncx': ncx, 'ncy': ncy, 'center_dist': center_dist,
            'position_score': position_score, 'ul_penalty': upper_left_penalty,
            'rel_w': rel_w, 'rel_h': rel_h,
            'top5': top5, 'top25': top25, 'bot5': bot5,
            'score': score
        })

    if not candidates:
        return None, {'is_red': is_red, 'black_count': black_count, 'red_count': red_count}, []

    candidates.sort(key=lambda c: c['score'], reverse=True)
    best = candidates[0]

    # --- Classify using width profile ---
    if is_red:
        if best['top5'] > 0.15 or best['top25'] > 0.45:
            predicted = 'heart'
        else:
            predicted = 'diamond'
    else:
        if best['top5'] < 0.15:
            predicted = 'spade'
        else:
            predicted = 'club'

    debug = {
        'is_red': is_red, 'black_count': black_count, 'red_count': red_count,
        'best': best, 'num_candidates': len(candidates)
    }
    return predicted, debug, candidates


# ============ Run tests ============
print(f"{'#':<3} {'Label':<10} {'Expected':<8} {'Predicted':<9} {'top5':<7} {'top25':<7} {'ncy':<6} {'pos':<5} {'Result'}")
print("=" * 80)

correct = 0
total = 0
errors = []

for idx, (ctype, fname, bbox, expected, label) in enumerate(test_cards):
    predicted, debug, candidates = analyze_card(ctype, fname, bbox, expected)
    total += 1

    if predicted is None:
        print(f"{idx:<3} {label:<10} {expected:<8} {'FAIL':<9} {'---':<7} {'---':<7} {'---':<6} {'---':<5} NO_COMP")
        errors.append((label, expected, "no component"))
        continue

    is_correct = predicted == expected
    if is_correct:
        correct += 1
    else:
        errors.append((label, expected, f"got {predicted}"))

    best = debug['best']
    mark = "OK" if is_correct else "FAIL"
    print(f"{idx:<3} {label:<10} {expected:<8} {predicted:<9} {best['top5']:.3f}  {best['top25']:.3f}  {best['ncy']:.2f}  {best['position_score']:.2f}  {mark}")

print(f"\nAccuracy: {correct}/{total} = {correct / total * 100:.1f}%")

if errors:
    print(f"\n--- ERRORS ({len(errors)}) ---")
    for label, expected, reason in errors:
        print(f"  {label}: expected={expected}, {reason}")
        # Show top 3 candidates
        for ctype, fname, bbox, exp, lbl in test_cards:
            if lbl == label:
                _, _, cands = analyze_card(ctype, fname, bbox, exp)
                for j, c in enumerate(cands[:4]):
                    print(f"    cand#{j + 1}: area={c['area']}, aspect={c['aspect']:.2f}, "
                          f"fill={c['fill']:.2f}, sq={c['squareness']:.2f}, "
                          f"ncy={c['ncy']:.2f}, pos={c['position_score']:.2f}, "
                          f"ul={c['ul_penalty']:.1f}, top5={c['top5']:.3f}, "
                          f"score={c['score']:.0f}")
                break
