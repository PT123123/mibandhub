# 市场表盘批量生成器：4 布局 × 8 配色 = 32 张，产出直接落仓库 market/。
#
# 流程：Pillow 画素材（数字条 + 背景 + 内嵌预览）→ watchface.json →
#       `npx wfjs writeBin` 打包 → 补丁 UIHH 头第 18..21 字节为唯一表盘 ID →
#       `npx wfjs readBin` 往返校验 → 拷进 <仓库>/market/ 并写 index.json。
#
# 依赖：Python + Pillow；Node 侧要在装了 watchface-js 的目录跑（默认系统临时
#       目录下的 miband5faces，可用 NPM_DIR 环境变量覆盖，搭建步骤见 README.md）。
from __future__ import annotations

import datetime
import json
import os
import shutil
import struct
import subprocess
import sys
import tempfile
import zlib
from PIL import Image, ImageDraw, ImageFont

W, H = 126, 294  # Mi Band 5 屏幕
HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
# 装了 watchface-js 的 Node 工作目录；Windows 下别写 /tmp —— 那是 MSYS 路径，Python 不认
NPM_DIR = os.environ.get("NPM_DIR") or os.path.join(tempfile.gettempdir(), "miband5faces")
WORK = os.path.join(NPM_DIR, "market_build")

UIHH_ID_OFFSET = 18  # UIHH 头里的表盘 ID（4 字节），手环选表盘槽用的就是它


def font(name: str, size: int) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(f"C:/Windows/Fonts/{name}", size)


def render_glyph(ch: str, f: ImageFont.FreeTypeFont, box: tuple[int, int], color) -> Image.Image:
    """把单个字符画进固定尺寸画布并居中 —— 数字条要求每张图同尺寸。"""
    img = Image.new("RGBA", box, (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    l, t, r, b = d.textbbox((0, 0), ch, font=f)
    d.text(((box[0] - (r - l)) / 2 - l, (box[1] - (b - t)) / 2 - t), ch, font=f, fill=color)
    return img


def digit_strip(f, box, color, count=10) -> list[Image.Image]:
    return [render_glyph(str(i), f, box, color) for i in range(count)]


def battery_icons(box=(16, 26)) -> list[Image.Image]:
    frames = []
    for i in range(9):
        img = Image.new("RGBA", (box[0] + 2, box[1]), (0, 0, 0, 0))
        d = ImageDraw.Draw(img)
        col = (255, 90, 90, 255) if i < 2 else (255, 178, 90, 255) if i < 5 else (123, 224, 138, 255)
        d.rounded_rectangle((0, 0, box[0] - 1, box[1] - 1), radius=3, outline=col, width=2)
        d.rectangle((box[0], box[1] // 2 - 3, box[0] + 1, box[1] // 2 + 2), fill=col)
        inner_w = int((box[0] - 6) * (i + 1) / 9)
        if inner_w > 0:
            d.rounded_rectangle((3, 3, 3 + inner_w, box[1] - 4), radius=1, fill=col)
        frames.append(img)
    return frames


def weekday_strip(f, box, color) -> list[Image.Image]:
    return [render_glyph(d, f, box, color)
            for d in ["MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"]]


def number(top_left, bottom_right, image_index, alignment="CenterLeft", spacing=0):
    return {
        "TopLeftX": top_left[0], "TopLeftY": top_left[1],
        "BottomRightX": bottom_right[0], "BottomRightY": bottom_right[1],
        "Alignment": alignment, "SpacingX": spacing, "SpacingY": 0,
        "ImageIndex": image_index, "ImagesCount": 10,
    }


def place(x, y, image_index, count=1):
    return {"X": x, "Y": y, "ImageIndex": image_index, "ImagesCount": count}


def composite(bg: Image.Image, layers) -> Image.Image:
    out = bg.copy()
    for im, x, y in layers:
        out.alpha_composite(im, (x, y))
    return out


def background_base(bg_col, dim, border_alpha) -> Image.Image:
    bg = Image.new("RGBA", (W, H), bg_col + (255,))
    d = ImageDraw.Draw(bg)
    d.rounded_rectangle((2, 2, W - 3, H - 3), radius=14, outline=dim + (border_alpha,), width=1)
    return bg


def step_icon(d: ImageDraw.ImageDraw, box, col):
    d.rounded_rectangle(box, radius=4, outline=col, width=2)


# ---------------------------------------------------------------- 四种布局
# 每个布局返回：(素材图列表, 背景, 预览, watchface.json, 组件说明)
# 坐标全部照 Digital Codex（amazfitwatchfaces #5183）解包出的已验证模板。

def layout_digital(a, bg_col, big_col, small_col, dim):
    big = digit_strip(font("ariblk.ttf", 106), (54, 84), big_col)
    small = digit_strip(font("ariblk.ttf", 44), (22, 34), small_col)
    imgs = big + small + battery_icons()

    bg = background_base(bg_col, dim, 120)
    d = ImageDraw.Draw(bg)
    d.rectangle((6, 124, 120, 127), fill=a + (255,))
    d.ellipse((61, 230, 67, 236), fill=a + (255,))
    step_icon(d, (12, 260, 30, 284), dim + (200,))

    prev = composite(bg, [
        (big[1], 6, 36), (big[0], 66, 36),
        (big[3], 6, 132), (big[8], 66, 132),
        (small[0], 14, 218), (small[9], 36, 218),
        (small[1], 72, 218), (small[2], 94, 218),
        (render_glyph("4", font("ariblk.ttf", 44), (22, 34), small_col), 36, 256),
        (imgs[20 + 7], 58, 10),
        (small[8], 86, 6), (small[4], 108, 6),
    ])
    spec = {
        "Time": {
            "Hours": {"Tens": place(6, 36, 0), "Ones": place(66, 36, 0)},
            "Minutes": {"Tens": place(6, 132, 0), "Ones": place(66, 132, 0)},
            "DrawingOrder": False,
        },
        "Activity": {"Steps": {"Number": number((36, 256), (114, 290), 10)}, "UnknownV7": 0},
        "Date": {"MonthAndDayAndYear": {"Separate": {
            "Month": number((14, 218), (58, 252), 10),
            "Day": number((72, 218), (116, 252), 10),
        }, "TwoDigitsMonth": True, "TwoDigitsDay": True}},
        "Battery": {
            "BatteryText": {"Number": number((84, 6), (122, 40), 10, "CenterRight")},
            "BatteryIcon": place(58, 10, 20, 9),
        },
    }
    return imgs, bg, prev, spec, "时间 / 日期 / 步数 / 电量"


def layout_terminal(a, bg_col, big_col, small_col, dim):
    big = digit_strip(font("consolab.ttf", 88), (50, 74), big_col)
    small = digit_strip(font("consolab.ttf", 34), (20, 30), small_col)
    week = weekday_strip(font("consola.ttf", 18), (34, 16), dim)
    imgs = big + small + battery_icons() + week

    bg = background_base(bg_col, dim, 110)
    d = ImageDraw.Draw(bg)
    d.rectangle((10, 118, 116, 120), fill=a + (230,))
    d.ellipse((60, 217, 66, 223), fill=a + (255,))
    step_icon(d, (14, 264, 32, 286), dim + (200,))

    prev = composite(bg, [
        (big[2], 10, 38), (big[1], 68, 38),
        (big[0], 10, 126), (big[7], 68, 126),
        (small[0], 16, 206), (small[9], 36, 206),
        (small[1], 72, 206), (small[2], 92, 206),
        (week[5], 46, 242),
        (render_glyph("8", font("consolab.ttf", 34), (20, 30), small_col), 38, 262),
        (small[3], 58, 262), (small[1], 78, 262), (small[2], 98, 262),
        (imgs[20 + 6], 62, 8),
        (small[7], 88, 6), (small[6], 108, 6),
    ])
    spec = {
        "Time": {
            "Hours": {"Tens": place(10, 38, 0), "Ones": place(68, 38, 0)},
            "Minutes": {"Tens": place(10, 126, 0), "Ones": place(68, 126, 0)},
            "DrawingOrder": False,
        },
        "Activity": {"Steps": {"Number": number((38, 262), (118, 292), 10)}, "UnknownV7": 0},
        "Date": {
            "MonthAndDayAndYear": {"Separate": {
                "Month": number((16, 206), (56, 236), 10),
                "Day": number((72, 206), (112, 236), 10),
            }, "TwoDigitsMonth": True, "TwoDigitsDay": True},
            "ENWeekDays": place(46, 242, 29, 7),
        },
        "Battery": {
            "BatteryText": {"Number": number((86, 6), (122, 36), 10, "CenterRight")},
            "BatteryIcon": place(62, 8, 20, 9),
        },
    }
    return imgs, bg, prev, spec, "时间 / 日期 / 星期 / 步数 / 电量"


def layout_center(a, bg_col, big_col, small_col, dim):
    """极简居中：小号日期在顶，时间两行居中，底部步数。"""
    big = digit_strip(font("seguisb.ttf", 76), (44, 64), big_col)
    small = digit_strip(font("seguisb.ttf", 40), (20, 30), small_col)
    imgs = big + small + battery_icons()

    bg = background_base(bg_col, dim, 100)
    d = ImageDraw.Draw(bg)
    d.ellipse((70, 56, 76, 62), fill=a + (255,))          # 日期分隔点
    d.rectangle((30, 166, 96, 168), fill=a + (220,))       # 时/分分隔线
    step_icon(d, (12, 262, 30, 286), dim + (200,))

    prev = composite(bg, [
        (small[0], 28, 44), (small[9], 48, 44),
        (small[1], 80, 44), (small[2], 100, 44),
        (big[1], 15, 96), (big[0], 67, 96),
        (big[3], 15, 172), (big[8], 67, 172),
        (render_glyph("4", font("seguisb.ttf", 40), (20, 30), small_col), 36, 258),
        (imgs[20 + 7], 58, 8),
        (small[8], 86, 4), (small[4], 108, 4),
    ])
    spec = {
        "Time": {
            "Hours": {"Tens": place(15, 96, 0), "Ones": place(67, 96, 0)},
            "Minutes": {"Tens": place(15, 172, 0), "Ones": place(67, 172, 0)},
            "DrawingOrder": False,
        },
        "Activity": {"Steps": {"Number": number((36, 258), (114, 288), 10)}, "UnknownV7": 0},
        "Date": {"MonthAndDayAndYear": {"Separate": {
            "Month": number((28, 44), (68, 74), 10),
            "Day": number((80, 44), (120, 74), 10),
        }, "TwoDigitsMonth": True, "TwoDigitsDay": True}},
        "Battery": {
            "BatteryText": {"Number": number((84, 4), (122, 34), 10, "CenterRight")},
            "BatteryIcon": place(58, 8, 20, 9),
        },
    }
    return imgs, bg, prev, spec, "时间 / 日期 / 步数 / 电量"


def layout_huge(a, bg_col, big_col, small_col, dim):
    """全大字：日期在顶，Impact 大数字占满，底部只剩电量。没有步数。"""
    big = digit_strip(font("impact.ttf", 124), (60, 104), big_col)
    small = digit_strip(font("seguisb.ttf", 40), (20, 30), small_col)
    imgs = big + small + battery_icons()

    bg = background_base(bg_col, dim, 90)
    d = ImageDraw.Draw(bg)
    d.ellipse((70, 20, 76, 26), fill=a + (255,))           # 日期分隔点
    d.rectangle((2, 158, 124, 160), fill=a + (200,))        # 时/分分隔线

    prev = composite(bg, [
        (small[0], 20, 8), (small[9], 40, 8),
        (small[1], 72, 8), (small[2], 92, 8),
        (big[1], 2, 52), (big[0], 64, 52),
        (big[3], 2, 164), (big[8], 64, 164),
        (imgs[20 + 7], 58, 264),
        (small[8], 84, 262), (small[4], 106, 262),
    ])
    spec = {
        "Time": {
            "Hours": {"Tens": place(2, 52, 0), "Ones": place(64, 52, 0)},
            "Minutes": {"Tens": place(2, 164, 0), "Ones": place(64, 164, 0)},
            "DrawingOrder": False,
        },
        "Date": {"MonthAndDayAndYear": {"Separate": {
            "Month": number((20, 8), (60, 38), 10),
            "Day": number((72, 8), (112, 38), 10),
        }, "TwoDigitsMonth": True, "TwoDigitsDay": True}},
        "Battery": {
            "BatteryText": {"Number": number((84, 262), (122, 292), 10, "CenterRight")},
            "BatteryIcon": place(58, 264, 20, 9),
        },
    }
    return imgs, bg, prev, spec, "时间 / 日期 / 电量（无步数）"


# 配色表：key / 中文名 / 强调色 / 背景 / 大数字 / 小数字 / 弱化色
COLORS = [
    ("ice",      "冰蓝", (90, 200, 255),  (10, 13, 20), (238, 246, 252), (150, 175, 195), (70, 100, 125)),
    ("rose",     "玫红", (255, 107, 138), (18, 10, 14), (250, 235, 240), (205, 150, 168), (130, 80, 95)),
    ("sun",      "橙阳", (255, 154, 61),  (20, 16, 10), (252, 242, 228), (210, 170, 130), (140, 105, 70)),
    ("forest",   "森绿", (123, 201, 111), (10, 18, 12), (236, 248, 236), (150, 185, 158), (80, 120, 92)),
    ("celadon",  "青瓷", (143, 214, 200), (12, 18, 16), (210, 240, 232), (170, 205, 195), (95, 135, 125)),
    ("graphite", "石墨", (184, 196, 208), (16, 19, 24), (225, 230, 238), (165, 175, 188), (105, 115, 128)),
    ("sakura",   "樱粉", (245, 168, 192), (20, 13, 17), (248, 232, 238), (210, 168, 184), (140, 95, 112)),
    ("grape",    "葡紫", (177, 140, 255), (15, 12, 20), (240, 234, 250), (180, 165, 215), (115, 98, 150)),
    ("gold",     "暗金", (232, 193, 90),  (20, 17, 10), (245, 235, 210), (200, 180, 140), (135, 115, 80)),
    ("sky",      "天青", (111, 195, 255), (10, 16, 24), (232, 242, 250), (150, 185, 215), (70, 105, 140)),
    ("coral",    "珊瑚", (255, 127, 102), (23, 15, 13), (252, 238, 232), (215, 160, 145), (145, 90, 78)),
    ("lavender", "薰衣草", (195, 166, 255), (18, 15, 26), (242, 236, 252), (190, 175, 220), (120, 105, 155)),
    ("wine",     "酒红", (224, 72, 90),   (22, 10, 14), (248, 230, 235), (210, 145, 158), (140, 70, 85)),
]


def build_assets(layout: str, colors) -> tuple[list[Image.Image], Image.Image, Image.Image, dict, str]:
    a = colors["accent"]
    return LAYOUTS[layout](a, colors["bg"], colors["big"], colors["small"], colors["dim"])


def write_face_folder(folder: str, images: list[Image.Image], bg: Image.Image,
                      preview: Image.Image, spec: dict) -> None:
    os.makedirs(folder, exist_ok=True)
    images = images + [bg, preview]
    for i, im in enumerate(images):
        im.save(f"{folder}/{i}.png")
    spec = json.loads(json.dumps(spec)
                      .replace('"@BG"', str(len(images) - 2))
                      .replace('"@PREVIEW"', str(len(images) - 1)))
    with open(f"{folder}/watchface.json", "w", encoding="utf-8") as fp:
        json.dump(spec, fp, indent=2, ensure_ascii=False)


def layout_split(a, bg_col, big_col, small_col, dim):
    """分栏：左侧时间，右侧信息列（电量 / 日期 / 步数）。"""
    big = digit_strip(font("consolab.ttf", 68), (38, 56), big_col)
    small = digit_strip(font("seguisb.ttf", 40), (20, 30), small_col)
    imgs = big + small + battery_icons()

    bg = background_base(bg_col, dim, 100)
    d = ImageDraw.Draw(bg)
    d.rectangle((84, 24, 86, 282), fill=dim + (130,))       # 竖分隔线
    d.ellipse((96, 176, 102, 182), fill=a + (255,))          # 日期分隔点

    prev = composite(bg, [
        (big[2], 6, 64), (big[1], 46, 64),
        (big[0], 6, 128), (big[7], 46, 128),
        (imgs[20 + 7], 90, 52),
        (small[8], 84, 84), (small[4], 104, 84),
        (small[0], 84, 140), (small[9], 104, 140),
        (small[1], 84, 184), (small[2], 104, 184),
        (small[3], 104, 232),
    ])
    spec = {
        "Time": {
            "Hours": {"Tens": place(6, 64, 0), "Ones": place(46, 64, 0)},
            "Minutes": {"Tens": place(6, 128, 0), "Ones": place(46, 128, 0)},
            "DrawingOrder": False,
        },
        "Activity": {"Steps": {"Number": number((84, 232), (124, 262), 10, "CenterRight")}, "UnknownV7": 0},
        "Date": {"MonthAndDayAndYear": {"Separate": {
            "Month": number((84, 140), (124, 170), 10),
            "Day": number((84, 184), (124, 214), 10),
        }, "TwoDigitsMonth": True, "TwoDigitsDay": True}},
        "Battery": {
            "BatteryText": {"Number": number((84, 84), (124, 114), 10, "CenterRight")},
            "BatteryIcon": place(90, 52, 20, 9),
        },
    }
    return imgs, bg, prev, spec, "时间 / 电量 / 日期 / 步数（分栏布局）"


def layout_steps(a, bg_col, big_col, small_col, dim):
    """步数主角：步数用强调色大数字居中，时间小字在顶。"""
    hero = digit_strip(font("ariblk.ttf", 52), (24, 36), a)   # 步数条（强调色，index 0）
    small = digit_strip(font("ariblk.ttf", 44), (20, 30), big_col)  # 时间条（index 10）
    imgs = hero + small + battery_icons()

    bg = background_base(bg_col, dim, 100)
    d = ImageDraw.Draw(bg)
    d.rectangle((20, 132, 106, 134), fill=a + (220,))
    d.ellipse((61, 52, 67, 58), fill=a + (255,))             # 时:分 冒号点

    prev = composite(bg, [
        (small[2], 20, 40), (small[1], 42, 40),
        (small[0], 70, 40), (small[7], 92, 40),
        (hero[8], 20, 140), (hero[4], 44, 140), (hero[6], 68, 140), (hero[2], 92, 140),
        (small[0], 14, 246), (small[9], 36, 246),
        (small[1], 64, 246), (small[2], 86, 246),
        (imgs[20 + 6], 98, 8),
    ])
    spec = {
        "Time": {
            "Hours": {"Tens": place(20, 40, 10), "Ones": place(42, 40, 10)},
            "Minutes": {"Tens": place(70, 40, 10), "Ones": place(92, 40, 10)},
            "DrawingOrder": False,
        },
        "Activity": {"Steps": {"Number": number((2, 140), (124, 204), 0, "Center")}, "UnknownV7": 0},
        "Date": {"MonthAndDayAndYear": {"Separate": {
            "Month": number((14, 246), (54, 280), 10),
            "Day": number((64, 246), (104, 280), 10),
        }, "TwoDigitsMonth": True, "TwoDigitsDay": True}},
        "Battery": {
            "BatteryText": {"Number": number((60, 8), (98, 38), 10, "CenterRight")},
            "BatteryIcon": place(98, 8, 20, 9),
        },
    }
    return imgs, bg, prev, spec, "时间 / 步数主角 / 日期 / 电量（无星期）"


def layout_datehero(a, bg_col, big_col, small_col, dim):
    """日历主角：大日期两行居中，时间小字在顶，星期+电量在底。没有步数。"""
    big = digit_strip(font("ariblk.ttf", 106), (44, 84), big_col)
    small = digit_strip(font("seguisb.ttf", 40), (20, 30), small_col)
    week = weekday_strip(font("consola.ttf", 18), (34, 16), dim)
    imgs = big + small + battery_icons() + week

    bg = background_base(bg_col, dim, 90)
    d = ImageDraw.Draw(bg)
    d.rectangle((14, 147, 102, 149), fill=a + (220,))        # 月/日分隔线

    prev = composite(bg, [
        (small[2], 20, 20), (small[1], 42, 20),
        (small[0], 70, 20), (small[7], 92, 20),
        (big[0], 14, 60), (big[9], 58, 60),
        (big[1], 14, 150), (big[2], 58, 150),
        (week[2], 88, 240),
        (imgs[20 + 7], 58, 264),
        (small[8], 84, 262), (small[4], 106, 262),
    ])
    spec = {
        "Time": {
            "Hours": {"Tens": place(20, 20, 10), "Ones": place(42, 20, 10)},
            "Minutes": {"Tens": place(70, 20, 10), "Ones": place(92, 20, 10)},
            "DrawingOrder": False,
        },
        "Date": {
            "MonthAndDayAndYear": {"Separate": {
                "Month": number((14, 60), (102, 144), 0),
                "Day": number((14, 150), (102, 234), 0),
            }, "TwoDigitsMonth": True, "TwoDigitsDay": True},
            "ENWeekDays": place(88, 240, 29, 7),
        },
        "Battery": {
            "BatteryText": {"Number": number((84, 262), (122, 292), 10, "CenterRight")},
            "BatteryIcon": place(58, 264, 20, 9),
        },
    }
    return imgs, bg, prev, spec, "时间 / 大日期 / 星期 / 电量（无步数）"


def layout_rounded(a, bg_col, big_col, small_col, dim):
    """圆润：Bahnschrift 窄体数字 + 星期行 + 四角圆点点缀。"""
    big = digit_strip(font("bahnschrift.ttf", 100), (48, 84), big_col)
    small = digit_strip(font("bahnschrift.ttf", 44), (22, 34), small_col)
    week = weekday_strip(font("consola.ttf", 18), (34, 16), dim)
    imgs = big + small + battery_icons() + week

    bg = background_base(bg_col, dim, 100)
    d = ImageDraw.Draw(bg)
    for cx, cy in ((8, 8), (118, 8), (8, 286), (118, 286)):
        d.ellipse((cx - 2, cy - 2, cx + 2, cy + 2), fill=a + (255,))
    d.ellipse((61, 230, 67, 236), fill=a + (255,))
    step_icon(d, (12, 260, 30, 284), dim + (200,))

    prev = composite(bg, [
        (week[5], 6, 6),
        (big[1], 6, 36), (big[0], 62, 36),
        (big[3], 6, 132), (big[8], 62, 132),
        (small[0], 16, 218), (small[9], 38, 218),
        (small[1], 74, 218), (small[2], 96, 218),
        (render_glyph("4", font("bahnschrift.ttf", 44), (22, 34), small_col), 36, 256),
        (imgs[20 + 7], 58, 10),
        (small[8], 86, 6), (small[4], 108, 6),
    ])
    spec = {
        "Time": {
            "Hours": {"Tens": place(6, 36, 0), "Ones": place(62, 36, 0)},
            "Minutes": {"Tens": place(6, 132, 0), "Ones": place(62, 132, 0)},
            "DrawingOrder": False,
        },
        "Activity": {"Steps": {"Number": number((36, 256), (114, 290), 10)}, "UnknownV7": 0},
        "Date": {
            "MonthAndDayAndYear": {"Separate": {
                "Month": number((16, 218), (60, 252), 10),
                "Day": number((74, 218), (118, 252), 10),
            }, "TwoDigitsMonth": True, "TwoDigitsDay": True},
            "ENWeekDays": place(6, 6, 29, 7),
        },
        "Battery": {
            "BatteryText": {"Number": number((84, 6), (122, 40), 10, "CenterRight")},
            "BatteryIcon": place(58, 10, 20, 9),
        },
    }
    return imgs, bg, prev, spec, "时间 / 日期 / 星期 / 步数 / 电量"


LAYOUTS = {
    "digital": layout_digital,
    "terminal": layout_terminal,
    "center": layout_center,
    "huge": layout_huge,
    "split": layout_split,
    "steps": layout_steps,
    "datehero": layout_datehero,
    "rounded": layout_rounded,
}
LAYOUT_LABEL = {
    "digital": "数码", "terminal": "终端", "center": "极简", "huge": "大字",
    "split": "分栏", "steps": "步数", "datehero": "日历", "rounded": "圆润",
}


def run_wfjs(args: str) -> None:
    r = subprocess.run(f'npx wfjs {args}', shell=True, cwd=NPM_DIR,
                       capture_output=True, text=True)
    if r.returncode != 0:
        raise RuntimeError(f"wfjs {args} 失败：{r.stdout}\n{r.stderr}")


def main() -> None:
    os.makedirs(WORK, exist_ok=True)
    market = os.path.join(REPO, "market")
    shutil.rmtree(os.path.join(market, "faces"), ignore_errors=True)
    shutil.rmtree(os.path.join(market, "previews"), ignore_errors=True)
    os.makedirs(os.path.join(market, "faces"), exist_ok=True)
    os.makedirs(os.path.join(market, "previews"), exist_ok=True)

    entries = []
    seq = 0
    for layout in ["digital", "terminal", "center", "huge",
                   "split", "steps", "datehero", "rounded"]:
        for ckey, cname, accent, bg_col, big, small, dim in COLORS:
            seq += 1
            fid = f"mk{seq:02d}"
            watchface_id = 0x4D4B0000 + seq  # 'MK' 前缀 + 序号，每张唯一
            colors = dict(accent=accent, bg=bg_col, big=big, small=small, dim=dim)
            folder = os.path.join(WORK, fid)
            shutil.rmtree(folder, ignore_errors=True)

            imgs, bg, prev, spec, note = build_assets(layout, colors)
            write_face_folder(folder, imgs, bg, prev, spec)

            run_wfjs(f"writeBin -i market_build/{fid} -m miband5")
            # wfjs 的输出名取输入目录的 basename，落在 CWD（NPM_DIR）根下
            raw_bin = os.path.join(NPM_DIR, f"{fid}_packed.bin")

            # 补丁 UIHH 头的表盘 ID（offset 18..21，小端）→ 每张唯一
            data = bytearray(open(raw_bin, "rb").read())
            assert data[:4] == b"UIHH", f"{fid}: 不是 UIHH 容器"
            struct.pack_into("<I", data, UIHH_ID_OFFSET, watchface_id)
            patched = os.path.join(NPM_DIR, f"{fid}_id.bin")
            open(patched, "wb").write(data)

            # 往返校验：补丁后的包必须还能被完整解析
            run_wfjs(f"readBin -i market_build/{fid}_id.bin -m miband5")
            extracted = os.path.join(NPM_DIR, f"{fid}_id_extracted")
            if os.path.exists(extracted):
                shutil.rmtree(extracted)

            size = len(data)
            crc = zlib.crc32(data) & 0xFFFFFFFF
            shutil.copy(patched, os.path.join(market, "faces", f"{fid}.bin"))
            prev.save(os.path.join(market, "previews", f"{fid}.png"))
            entries.append({
                "id": fid,
                "name": f"{cname}{LAYOUT_LABEL[layout]}",
                "author": "shouhuan 自制",
                "source": "本仓库用 watchface-js 生成",
                "license": "CC0-1.0",
                "file": f"faces/{fid}.bin",
                "preview": f"previews/{fid}.png",
                "sizeBytes": size,
                "crc32": f"{crc:08x}",
                "note": note,
            })
            print(f"  {fid} {cname}{LAYOUT_LABEL[layout]}: id 0x{watchface_id:08x}, {size} bytes, crc {crc:08x}")

    index = {
        "version": 2,
        "updated": datetime.date.today().isoformat(),
        "faces": entries,
    }
    with open(os.path.join(market, "index.json"), "w", encoding="utf-8") as fp:
        json.dump(index, fp, indent=2, ensure_ascii=False)
    print(f"market/ 完成：{len(entries)} 张")


if __name__ == "__main__":
    sys.exit(main())
