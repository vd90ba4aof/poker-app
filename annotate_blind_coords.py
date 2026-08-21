from PIL import Image, ImageDraw, ImageFont
import json

img = Image.open("/app/data/所有对话/主对话/用户上传/Screenshot_2026-08-03-19-58-42-56_1d58bed7e226cc412b0128cc8fb4cf50.jpg")
print(f"Image size: {img.size}")
draw = ImageDraw.Draw(img)

# Colors
COLORS = {
    "red": (255, 0, 0),
    "green": (0, 255, 0),
    "blue": (0, 100, 255),
    "yellow": (255, 255, 0),
    "cyan": (0, 255, 255),
    "magenta": (255, 0, 255),
    "white": (255, 255, 255),
    "orange": (255, 165, 0),
}

def draw_box(x1, y1, x2, y2, color_name, label, width=2):
    color = COLORS.get(color_name, (255, 255, 255))
    draw.rectangle([x1, y1, x2, y2], outline=color, width=width)
    # Draw label above the box
    draw.text((x1, max(0, y1-14)), label, fill=color)

# 6 players
players = {
    "P1": {"name": [440, 500, 620, 540], "chip": [460, 545, 620, 580]},
    "P2": {"name": [10, 870, 200, 910], "chip": [10, 910, 200, 950]},
    "P3": {"name": [810, 850, 1030, 890], "chip": [860, 895, 1030, 935]},
    "P4": {"name": [10, 1440, 200, 1480], "chip": [25, 1485, 200, 1520]},
    "P5": {"name": [800, 1470, 1010, 1510], "chip": [830, 1515, 1010, 1555]},
    "P6": {"name": [135, 1920, 265, 1960], "chip": [170, 1965, 295, 2005]},
}

# Timer numbers (next to avatars) - 在牌面区域左下角的小数字
timers = {
    "T_P1": [30, 800, 130, 840],     # Jens Jo 的 "100" (左上，牌面左下)
    "T_P3": [850, 780, 930, 820],    # AMF_games 的 "44"带火焰 (右上)
    "T_P4": [30, 1455, 130, 1495],   # lovevigoss 的 "100" (左下)
    "T_P5": [800, 1440, 880, 1480],  # OC-338 的 "64" (右下)
    "T_P6": [100, 1840, 170, 1880],  # zhcyc 的 "0" (Hero左侧)
}

# Table bets (small chip amounts in front of each player on table)
table_bets = {
    "B_P1": [320, 900, 430, 945],    # 400 (顶部中间偏左)
    "B_P3": [720, 900, 830, 945],    # 400 (右上)
    "B_P4": [320, 1450, 430, 1495],  # 400 (左下)
    "B_P5": [640, 1450, 750, 1495],  # 400 (右下)
    "B_P6": [320, 1740, 430, 1785],  # 100 (Hero面前)
}

# Pot area
pot = {"POT": [340, 900, 640, 1020]}

# Blind info at top
blind_info = {"BLIND": [320, 830, 700, 880]}

# Straddle (盲抓提示区域) - 覆盖"盲抓 400"黄色气泡
straddle = {"STRAD": [200, 1290, 420, 1410]}

# Bet buttons
bets = {
    "100%": [730, 1650, 1080, 1800],
    "75%": [730, 1820, 1080, 1960],
    "50%": [730, 1980, 1080, 2120],
    "33%": [730, 2140, 1080, 2280],
}

# Action buttons
actions = {"FOLD": [20, 2180, 370, 2340], "CALL": [390, 2180, 710, 2340]}

# Dealer
dealer = {"D": [920, 1255, 980, 1310]}

# Hero cards
cards = {"C1": [75, 1700, 230, 2000], "C2": [170, 1700, 340, 2000]}

# Draw
for name, c in players.items():
    draw_box(*c["name"], "green", f"NAME:{name}", width=2)
    draw_box(*c["chip"], "cyan", f"CHIP:{name}", width=2)

for name, c in timers.items():
    draw_box(*c, "yellow", f"TIMER:{name}", width=2)

for name, c in table_bets.items():
    draw_box(*c, "orange", f"BET:{name}", width=2)

for name, c in pot.items():
    draw_box(*c, "magenta", f"POT:{name}", width=3)

for name, c in blind_info.items():
    draw_box(*c, "white", f"BLIND:{name}", width=1)

for name, c in straddle.items():
    draw_box(*c, "white", f"STRAD:{name}", width=3)

for name, c in bets.items():
    draw_box(*c, "red", f"BET:{name}", width=2)

for name, c in actions.items():
    draw_box(*c, "blue", f"ACTION:{name}", width=2)

for name, c in dealer.items():
    draw_box(*c, "white", f"DEALER:{name}", width=3)

for name, c in cards.items():
    draw_box(*c, "green", f"CARD:{name}", width=2)

out_path = "/app/data/所有对话/主对话/poker-app-latest/annotated_blind.jpg"
img.save(out_path, "JPEG", quality=95)
print(f"Saved: {out_path}")

# Output coords as JSON
all_coords = {
    "players": players,
    "timers": timers,
    "table_bets": table_bets,
    "pot": pot,
    "blind_info": blind_info,
    "straddle": straddle,
    "bets": bets,
    "actions": actions,
    "dealer": dealer,
    "hero_cards": cards,
}
print(json.dumps(all_coords, indent=2, ensure_ascii=False))
