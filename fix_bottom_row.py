import cv2
import numpy as np
import os

# 18 test cards with ground truth
test_cards = [
    # Community cards (from Screenshot_2026-08-05)
    ("community", "IMG_20260808_213049.jpg", None, "spade"),    # 3♠ - template
    ("community", "IMG_20260808_212930.jpg", None, "club"),     # A♣ - template  
    ("community", "IMG_20260808_212825.jpg", None, "diamond"),  # 10♦ - template
    ("community", "IMG_20260808_212728.jpg", None, "heart"),    # A♥ - template
    # Hand cards (from Screenshot_2026-08-05, bottom area)
    ("hand", "Screenshot_2026-08-05-21-28-13-92_1d58bed7e226cc412b0128cc8fb4cf50.jpg", (0.250, 0.660, 0.370, 0.830), "club"),    # 8♣
    ("hand", "Screenshot_2026-08-05-21-28-13-92_1d58bed7e226cc412b0128cc8fb4cf50.jpg", (0.380, 0.660, 0.500, 0.830), "heart"),   # 8♥
    ("hand", "Screenshot_2026-08-05-21-28-13-92_1d58bed7e226cc412b0128cc8fb4cf50.jpg", (0.510, 0.660, 0.630, 0.830), "spade"),   # A♠
    ("hand", "Screenshot_2026-08-05-21-28-13-92_1d58bed7e226cc412b0128cc8fb4cf50.jpg", (0.640, 0.660, 0.760, 0.830), "diamond"), # 3♦
    ("hand", "Screenshot_2026-08-05-21-28-13-92_1d58bed7e226cc412b0128cc8fb4cf50.jpg", (0.250, 0.840, 0.370, 1.000), "spade"),   # 9♠ (bottom row)
    ("hand", "Screenshot_2026-08-05-21-28-13-92_1d58bed7e226cc412b0128cc8fb4cf50.jpg", (0.380, 0.840, 0.500, 1.000), "spade"),   # 7♠ (bottom row) - PREVIOUSLY FAILED
    ("hand", "Screenshot_2026-08-05-21-28-13-92_1d58bed7e226cc412b0128cc8fb4cf50.jpg", (0.510, 0.840, 0.630, 1.000), "diamond"), # 7♦ (bottom row) - PREVIOUSLY FAILED
    # River cards (from old screenshot)
    ("river", "Screenshot_2026-06-13-02-33-09-28_1d58bed7e226cc412b0128cc8fb4cf50.jpg", (0.130, 0.500, 0.250, 0.670), "club"),    # K♣
    ("river", "Screenshot_2026-06-13-02-33-09-28_1d58bed7e226cc412b0128cc8fb4cf50.jpg", (0.260, 0.500, 0.380, 0.670), "diamond"), # Q♦ - PREVIOUSLY FAILED
    ("river", "Screenshot_2026-06-13-02-33-09-28_1d58bed7e226cc412b0128cc8fb4cf50.jpg", (0.390, 0.500, 0.510, 0.670), "club"),    # 8♣
    ("river", "Screenshot_2026-06-13-02-33-09-28_1d58bed7e226cc412b0128cc8fb4cf50.jpg", (0.520, 0.500, 0.640, 0.670), "spade"),   # 5♠
    ("river", "Screenshot_2026-06-13-02-33-09-28_1d58bed7e226cc412b0128cc8fb4cf50.jpg", (0.650, 0.500, 0.770, 0.670), "diamond"), # 7♦
    ("river", "Screenshot_2026-06-13-02-33-09-28_1d58bed7e226cc412b0128cc8fb4cf50.jpg", (0.260, 0.680, 0.380, 0.850), "club"),    # 5♣ (bottom row)
    ("river", "Screenshot_2026-06-13-02-33-09-28_1d58bed7e226cc412b0128cc8fb4cf50.jpg", (0.520, 0.680, 0.640, 0.850), "heart"),   # 7♥ (bottom row)
]

base_path = "/app/data/所有对话/主对话/用户上传"

def analyze_card(card_type, filename, bbox, expected_suit):
    filepath = os.path.join(base_path, filename)
    img = cv2.imread(filepath)
    if img is None:
        return None, {}, None
    
    h, w = img.shape[:2]
    
    if card_type == "community":
        # Template images - use full image
        card = img
    else:
        # Extract card region
        x1, y1, x2, y2 = int(bbox[0]*w), int(bbox[1]*h), int(bbox[2]*w), int(bbox[3]*h)
        card = img[y1:y2, x1:x2]
    
    ch, cw = card.shape[:2]
    
    # Shrink margins more aggressively (12%) to avoid border
    margin_x = int(cw * 0.12)
    margin_y = int(ch * 0.12)
    inner = card[margin_y:ch-margin_y, margin_x:cw-margin_x]
    ih, iw = inner.shape[:2]
    
    # Detect black pixels
    gray = cv2.cvtColor(inner, cv2.COLOR_BGR2GRAY)
    black_mask = (gray < 80).astype(np.uint8) * 255
    
    # Detect red pixels
    hsv = cv2.cvtColor(inner, cv2.COLOR_BGR2HSV)
    b, g, r = cv2.split(inner)
    red_mask = ((r.astype(int) - g.astype(int) > 20) & 
                (r.astype(int) - b.astype(int) > 20) & 
                (r > 100)).astype(np.uint8) * 255
    
    # Determine primary color
    black_count = np.count_nonzero(black_mask)
    red_count = np.count_nonzero(red_mask)
    is_red = red_count > black_count
    
    # Use the dominant color mask
    mask = red_mask if is_red else black_mask
    
    # Clean up mask
    kernel = np.ones((3,3), np.uint8)
    mask = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, kernel)
    mask = cv2.morphologyEx(mask, cv2.MORPH_OPEN, kernel)
    
    # Find connected components
    num_labels, labels, stats, centroids = cv2.connectedComponentsWithStats(mask, connectivity=8)
    
    candidates = []
    for i in range(1, num_labels):
        x, y, bw, bh, area = stats[i]
        cx, cy = centroids[i]
        
        if area < (iw * ih * 0.015):  # min 1.5% of inner area
            continue
        if bw < (iw * 0.05) or bh < (ih * 0.05):  # too small
            continue
        
        aspect = max(bw, bh) / (min(bw, bh) + 1e-6)
        
        # Relaxed aspect filter - rank text is usually > 2.5
        if aspect > 2.5:
            continue
        
        # Skip components that span > 70% of inner area (likely border remnants)
        if bw > iw * 0.70 or bh > ih * 0.70:
            continue
        
        # Extract component mask
        comp_mask = (labels == i).astype(np.uint8)
        comp_region = comp_mask[y:y+bh, x:x+bw]
        
        # Width profile
        col_sums = comp_region.sum(axis=0)
        max_col = col_sums.max() + 1e-6
        norm_profile = col_sums / max_col
        
        top5 = np.mean(np.sort(norm_profile)[-max(1, int(len(norm_profile)*0.05)):])
        top25 = np.mean(np.sort(norm_profile)[-max(1, int(len(norm_profile)*0.25)):])
        
        # Fill ratio
        fill = area / (bw * bh + 1e-6)
        
        # Squareness (how close to square)
        squareness = 1.0 - abs(bw - bh) / max(bw, bh)
        
        # Position score: prefer components near center of card
        # Center of inner region is (iw/2, ih/2)
        # Suit symbol should be roughly in center or lower-center
        # Rank text is in upper-left
        norm_cx = cx / iw  # 0 to 1
        norm_cy = cy / ih  # 0 to 1
        
        # Center distance penalty: suit should be near center (0.3-0.7 range)
        center_dist = np.sqrt((norm_cx - 0.5)**2 + (norm_cy - 0.5)**2)
        position_score = 1.0 - min(center_dist / 0.5, 1.0)  # 1.0 at center, 0.0 at corners
        
        # Upper region penalty: rank text is typically in top 25%
        upper_penalty = 1.0 if norm_cy > 0.25 else 0.3
        
        # Combined score
        score = area * fill * squareness * position_score * upper_penalty
        
        candidates.append({
            'label': i,
            'area': area,
            'bw': bw, 'bh': bh,
            'aspect': aspect,
            'fill': fill,
            'squareness': squareness,
            'cx': norm_cx, 'cy': norm_cy,
            'center_dist': center_dist,
            'position_score': position_score,
            'upper_penalty': upper_penalty,
            'top5': top5,
            'top25': top25,
            'score': score
        })
    
    if not candidates:
        return None, {}, None
    
    # Sort by score
    candidates.sort(key=lambda c: c['score'], reverse=True)
    
    best = candidates[0]
    
    # Classify
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
    
    debug_info = {
        'is_red': is_red,
        'black_count': black_count,
        'red_count': red_count,
        'num_candidates': len(candidates),
        'best': best,
        'top3': candidates[:3]
    }
    
    return predicted, debug_info, candidates

# Run test
print("=" * 80)
print(f"{'Card':<20} {'Expected':<10} {'Predicted':<10} {'top5':<8} {'top25':<8} {'cy':<6} {'Result'}")
print("=" * 80)

correct = 0
total = 0
errors = []

for card_type, filename, bbox, expected in test_cards:
    predicted, debug, candidates = analyze_card(card_type, filename, bbox, expected)
    total += 1
    
    if predicted is None:
        print(f"{card_type+' '+expected:<20} {expected:<10} {'FAIL':<10} {'N/A':<8} {'N/A':<8} {'N/A':<6} NO_COMPONENT")
        errors.append((card_type, expected, filename, bbox, "no component"))
        continue
    
    is_correct = predicted == expected
    if is_correct:
        correct += 1
    else:
        errors.append((card_type, expected, filename, bbox, f"got {predicted}"))
    
    best = debug['best']
    mark = "✓" if is_correct else "✗"
    print(f"{card_type+' '+expected:<20} {expected:<10} {predicted:<10} {best['top5']:.3f}   {best['top25']:.3f}   {best['cy']:.2f}   {mark}")

print(f"\nAccuracy: {correct}/{total} = {correct/total*100:.0f}%")

if errors:
    print(f"\nErrors ({len(errors)}):")
    for card_type, expected, filename, bbox, reason in errors:
        print(f"  {card_type} {expected}: {reason}")
        if bbox:
            # Show top 3 candidates for debugging
            _, debug, candidates = analyze_card(card_type, filename, bbox, expected)
            if candidates:
                print(f"    Top 3 candidates:")
                for j, c in enumerate(candidates[:3]):
                    print(f"      #{j+1}: area={c['area']}, aspect={c['aspect']:.2f}, fill={c['fill']:.2f}, sq={c['squareness']:.2f}, cy={c['cy']:.2f}, pos={c['position_score']:.2f}, top5={c['top5']:.3f}, score={c['score']:.0f}")

