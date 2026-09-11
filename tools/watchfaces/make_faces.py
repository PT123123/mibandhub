# 自制 Mi Band 5 表盘生成器（两张）v2。
# 布局模板照抄 Digital Codex（amazfitwatchfaces 5183）解出来的 watchface.json，
# 只保留 Background / Time / Activity(Steps) / Date / Battery 这几个已验证的块。
# v2：修正 y 向布局 —— 分钟数字底边不得压到日期/步数行。
from __future__ import annotations

import json
import os
from PIL import Image, ImageDraw, ImageFont

W, H = 126, 294  # Mi Band 5 屏幕
ROOT = os.path.dirname(os.path.abspath(__file__))


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
    """9 帧电量图标：0-1 红、2-4 琥珀、5-8 绿，填充比例随帧号递增。"""
    frames = []
    for i in range(9):
        img = Image.new("RGBA", (box[0] + 2, box[1]), (0, 0, 0, 0))  # 右侧留 2px 给正极头
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


def build_face(name: str, images: list[Image.Image], bg: Image.Image,
               preview: Image.Image, spec: dict) -> None:
    folder = f"{ROOT}/{name}"
    os.makedirs(folder, exist_ok=True)
    for i, im in enumerate(images):
        im.save(f"{folder}/{i}.png")
    images = images + [bg, preview]
    bg_idx, prev_idx = len(images) - 2, len(images) - 1
    for i, im in enumerate(images):
        im.save(f"{folder}/{i}.png")
    spec = json.loads(json.dumps(spec).replace('"@BG"', str(bg_idx)).replace('"@PREVIEW"', str(prev_idx)))
    with open(f"{folder}/watchface.json", "w", encoding="utf-8") as fp:
        json.dump(spec, fp, indent=2, ensure_ascii=False)


def composite(bg: Image.Image, layers: list[tuple[Image.Image, int, int]]) -> Image.Image:
    out = bg.copy()
    for im, x, y in layers:
        out.alpha_composite(im, (x, y))
    return out


# ---------------------------------------------------------------- Face A：青薄荷极简
# 大数字 54×84：时 36-120 / 分 132-216；日期 218-252；步数 256-290
bigA = digit_strip(font("ariblk.ttf", 106), (54, 84), (238, 242, 248, 255))
smallA = digit_strip(font("ariblk.ttf", 44), (22, 34), (168, 178, 194, 255))
batA = battery_icons()
imgsA = bigA + smallA + batA  # 0-9 大数字，10-19 小数字，20-28 电量

bgA = Image.new("RGBA", (W, H), (11, 14, 19, 255))
d = ImageDraw.Draw(bgA)
d.rounded_rectangle((2, 2, W - 3, H - 3), radius=14, outline=(35, 41, 54, 255), width=1)
d.rectangle((6, 124, 120, 127), fill=(53, 208, 160, 255))           # 时/分之间的青色分隔线
d.ellipse((61, 230, 67, 236), fill=(53, 208, 160, 255))             # 月/日之间的圆点
d.rounded_rectangle((12, 260, 30, 284), radius=4, outline=(58, 66, 82, 255), width=3)  # 步数图标

step_digit_A = render_glyph("4", font("ariblk.ttf", 44), (22, 34), (168, 178, 194, 255))
prevA = composite(bgA, [
    (bigA[1], 6, 36), (bigA[0], 66, 36),         # 10
    (bigA[3], 6, 132), (bigA[8], 66, 132),       # 38
    (smallA[0], 14, 218), (smallA[9], 36, 218),  # 09 · 12
    (smallA[1], 72, 218), (smallA[2], 94, 218),
    (step_digit_A, 36, 256),                     # 步数示意（1 位代表即可）
    (batA[7], 58, 10),                           # 电量图标
    (smallA[8], 86, 6), (smallA[4], 108, 6),     # 84
])

specA = {
    "Background": {
        "Image": {"X": 0, "Y": 0, "ImageIndex": "@BG"},
        "PreviewEN": {"X": 0, "Y": 0, "ImageIndex": "@PREVIEW"},
        "PreviewCN": {"X": 0, "Y": 0, "ImageIndex": "@PREVIEW"},
        "PreviewCN2": {"X": 0, "Y": 0, "ImageIndex": "@PREVIEW"},
    },
    "Time": {
        "Hours": {"Tens": place(6, 36, 0), "Ones": place(66, 36, 0)},
        "Minutes": {"Tens": place(6, 132, 0), "Ones": place(66, 132, 0)},
        "DrawingOrder": False,
    },
    "Activity": {
        "Steps": {"Number": number((36, 256), (114, 290), 10)},
        "UnknownV7": 0,
    },
    "Date": {
        "MonthAndDayAndYear": {
            "Separate": {
                "Month": number((14, 218), (58, 252), 10),
                "Day": number((72, 218), (116, 252), 10),
            },
            "TwoDigitsMonth": True,
            "TwoDigitsDay": True,
        },
    },
    "Battery": {
        "BatteryText": {"Number": number((84, 6), (122, 40), 10, "CenterRight")},
        "BatteryIcon": place(58, 10, 20, 9),
    },
}

# ---------------------------------------------------------------- Face B：琥珀暗色
# 大数字 50×74：时 38-112 / 分 126-200；日期 206-236；星期 242-258；步数 262-290
bigB = digit_strip(font("consolab.ttf", 88), (50, 74), (255, 178, 90, 255))
smallB = digit_strip(font("consolab.ttf", 34), (20, 30), (232, 220, 200, 255))
batB = battery_icons()
weekB = weekday_strip(font("consola.ttf", 18), (34, 16), (150, 138, 118, 255))
imgsB = bigB + smallB + batB + weekB  # 0-9 大，10-19 小，20-28 电量，29-35 星期

bgB = Image.new("RGBA", (W, H), (20, 16, 12, 255))
d = ImageDraw.Draw(bgB)
d.rounded_rectangle((2, 2, W - 3, H - 3), radius=14, outline=(46, 38, 32, 255), width=1)
d.rectangle((10, 118, 116, 120), fill=(120, 86, 52, 255))           # 时/分之间的琥珀分隔线
d.ellipse((60, 217, 66, 223), fill=(255, 178, 90, 255))             # 月/日之间的圆点
d.rounded_rectangle((14, 264, 32, 286), radius=4, outline=(90, 74, 56, 255), width=2)  # 步数图标

step_digit_B = render_glyph("8", font("consolab.ttf", 34), (20, 30), (232, 220, 200, 255))
prevB = composite(bgB, [
    (bigB[2], 10, 38), (bigB[1], 68, 38),        # 21
    (bigB[0], 10, 126), (bigB[7], 68, 126),      # 07
    (smallB[0], 16, 206), (smallB[9], 36, 206),  # 09·12
    (smallB[1], 72, 206), (smallB[2], 92, 206),
    (weekB[5], 46, 242),                          # SAT
    (step_digit_B, 38, 262), (smallB[3], 58, 262), (smallB[1], 78, 262), (smallB[2], 98, 262),
    (batB[6], 62, 8), (smallB[7], 88, 6), (smallB[6], 108, 6),
])

specB = {
    "Background": {
        "Image": {"X": 0, "Y": 0, "ImageIndex": "@BG"},
        "PreviewEN": {"X": 0, "Y": 0, "ImageIndex": "@PREVIEW"},
        "PreviewCN": {"X": 0, "Y": 0, "ImageIndex": "@PREVIEW"},
        "PreviewCN2": {"X": 0, "Y": 0, "ImageIndex": "@PREVIEW"},
    },
    "Time": {
        "Hours": {"Tens": place(10, 38, 0), "Ones": place(68, 38, 0)},
        "Minutes": {"Tens": place(10, 126, 0), "Ones": place(68, 126, 0)},
        "DrawingOrder": False,
    },
    "Activity": {
        "Steps": {"Number": number((38, 262), (118, 292), 10)},
        "UnknownV7": 0,
    },
    "Date": {
        "MonthAndDayAndYear": {
            "Separate": {
                "Month": number((16, 206), (56, 236), 10),
                "Day": number((72, 206), (112, 236), 10),
            },
            "TwoDigitsMonth": True,
            "TwoDigitsDay": True,
        },
        "ENWeekDays": place(46, 242, 29, 7),
    },
    "Battery": {
        "BatteryText": {"Number": number((86, 6), (122, 36), 10, "CenterRight")},
        "BatteryIcon": place(62, 8, 20, 9),
    },
}

build_face("face_mint", imgsA, bgA, prevA, specA)
build_face("face_amber", imgsB, bgB, prevB, specB)
print("faces built")
