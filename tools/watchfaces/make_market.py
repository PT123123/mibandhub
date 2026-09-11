# 市场表盘批量生成器：8 张（2 布局 × 4 配色），产出直接落仓库 market/。
#
# 流程：Pillow 画素材（数字条 + 背景 + 内嵌预览）→ watchface.json →
#       `npx wfjs writeBin` 打包 → 补丁 UIHH 头第 18..21 字节为唯一表盘 ID →
#       `npx wfjs readBin` 往返校验 → 拷进 <仓库>/market/ 并写 index.json。
#
# 依赖：Python + Pillow；Node 侧要在装了 watchface-js 的目录跑（默认 /tmp/miband5faces，
#       可用 NPM_DIR 环境变量覆盖）。布局模板来历见 make_faces.py / README.md。
from __future__ import annotations

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
# 装了 watchface-js 的 Node 工作目录（README.md 里有搭建步骤）；
# 默认用系统临时目录，Windows 下别写 /tmp —— 那是 MSYS 的路径，Python 不认。
NPM_DIR = os.environ.get("NPM_DIR") or os.path.join(tempfile.gettempdir(), "miband5faces")
WORK = os.path.join(NPM_DIR, "market_build")

UIHH_ID_OFFSET = 18  # UIHH 头里的表盘 ID（4 字节），手环选表盘槽用的就是它


def font(name: str, size: int) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(f"C:/Windows/Fonts/{name}", size)


def render_glyph(ch: str, f: ImageFont.FreeTypeFont, box: tuple[int, int], color) -> Image.Image:
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


def write_face_folder(folder: str, images: list[Image.Image], bg: Image.Image,
                      preview: Image.Image, spec: dict) -> None:
    os.makedirs(folder, exist_ok=True)
    images = images + [bg, preview]
    for i, im in enumerate(images):
        im.save(f"{folder}/{i}.png")
    bg_idx, prev_idx = len(images) - 2, len(images) - 1
    spec = json.loads(json.dumps(spec).replace('"@BG"', str(bg_idx)).replace('"@PREVIEW"', str(prev_idx)))
    with open(f"{folder}/watchface.json", "w", encoding="utf-8") as fp:
        json.dump(spec, fp, indent=2, ensure_ascii=False)


def build_assets(spec: dict) -> tuple[list[Image.Image], Image.Image, Image.Image, dict]:
    """按一张脸的配色/布局规格产出（素材图列表, 背景, 预览, watchface.json）。"""
    a = spec["accent"]
    bg_col = spec["bg"]
    dim = spec["dim"]
    big_col = spec["big"]
    small_col = spec["small"]
    layout = spec["layout"]

    if layout == "digital":
        big = digit_strip(font("ariblk.ttf", 106), (54, 84), big_col)
        small = digit_strip(font("ariblk.ttf", 44), (22, 34), small_col)
        bat = battery_icons()
        imgs = big + small + bat

        bg = Image.new("RGBA", (W, H), bg_col + (255,))
        d = ImageDraw.Draw(bg)
        d.rounded_rectangle((2, 2, W - 3, H - 3), radius=14, outline=dim + (120,), width=1)
        d.rectangle((6, 124, 120, 127), fill=a + (255,))
        d.ellipse((61, 230, 67, 236), fill=a + (255,))
        d.rounded_rectangle((12, 260, 30, 284), radius=4, outline=dim + (200,), width=3)

        prev = composite(bg, [
            (big[1], 6, 36), (big[0], 66, 36),
            (big[3], 6, 132), (big[8], 66, 132),
            (small[0], 14, 218), (small[9], 36, 218),
            (small[1], 72, 218), (small[2], 94, 218),
            (render_glyph("4", font("ariblk.ttf", 44), (22, 34), small_col), 36, 256),
            (bat[7], 58, 10),
            (small[8], 86, 6), (small[4], 108, 6),
        ])
        json_spec = {
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
            "Activity": {"Steps": {"Number": number((36, 256), (114, 290), 10)}, "UnknownV7": 0},
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
        return imgs, bg, prev, json_spec

    # terminal：等宽字体 + 星期行
    big = digit_strip(font("consolab.ttf", 88), (50, 74), big_col)
    small = digit_strip(font("consolab.ttf", 34), (20, 30), small_col)
    bat = battery_icons()
    week = weekday_strip(font("consola.ttf", 18), (34, 16), dim)
    imgs = big + small + bat + week

    bg = Image.new("RGBA", (W, H), bg_col + (255,))
    d = ImageDraw.Draw(bg)
    d.rounded_rectangle((2, 2, W - 3, H - 3), radius=14, outline=dim + (110,), width=1)
    d.rectangle((10, 118, 116, 120), fill=a + (230,))
    d.ellipse((60, 217, 66, 223), fill=a + (255,))
    d.rounded_rectangle((14, 264, 32, 286), radius=4, outline=dim + (200,), width=2)

    prev = composite(bg, [
        (big[2], 10, 38), (big[1], 68, 38),
        (big[0], 10, 126), (big[7], 68, 126),
        (small[0], 16, 206), (small[9], 36, 206),
        (small[1], 72, 206), (small[2], 92, 206),
        (week[5], 46, 242),
        (render_glyph("8", font("consolab.ttf", 34), (20, 30), small_col), 38, 262),
        (small[3], 58, 262), (small[1], 78, 262), (small[2], 98, 262),
        (bat[6], 62, 8), (small[7], 88, 6), (small[6], 108, 6),
    ])
    json_spec = {
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
        "Activity": {"Steps": {"Number": number((38, 262), (118, 292), 10)}, "UnknownV7": 0},
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
    return imgs, bg, prev, json_spec


def run_wfjs(args: str) -> None:
    r = subprocess.run(f'npx wfjs {args}', shell=True, cwd=NPM_DIR,
                       capture_output=True, text=True)
    if r.returncode != 0:
        raise RuntimeError(f"wfjs {args} 失败：{r.stdout}\n{r.stderr}")


# ---------------------------------------------------------------- 脸谱表（id 前缀 'MK'）
FACES = [
    # layout, 中文名,      accent,          bg,           big,              small/dim
    dict(fid="mk01", name="冰蓝数码", layout="digital", accent=(90, 200, 255),  bg=(10, 13, 20),
         big=(238, 246, 252), small=(150, 175, 195), dim=(70, 100, 125),
         note="时间 / 日期 / 步数 / 电量"),
    dict(fid="mk02", name="玫红数码", layout="digital", accent=(255, 107, 138), bg=(18, 10, 14),
         big=(250, 235, 240), small=(205, 150, 168), dim=(130, 80, 95),
         note="时间 / 日期 / 步数 / 电量"),
    dict(fid="mk03", name="橙阳数码", layout="digital", accent=(255, 154, 61),  bg=(20, 16, 10),
         big=(252, 242, 228), small=(210, 170, 130), dim=(140, 105, 70),
         note="时间 / 日期 / 步数 / 电量"),
    dict(fid="mk04", name="森绿数码", layout="digital", accent=(123, 201, 111), bg=(10, 18, 12),
         big=(236, 248, 236), small=(150, 185, 158), dim=(80, 120, 92),
         note="时间 / 日期 / 步数 / 电量"),
    dict(fid="mk05", name="青瓷终端", layout="terminal", accent=(143, 214, 200), bg=(12, 18, 16),
         big=(210, 240, 232), small=(170, 205, 195), dim=(95, 135, 125),
         note="时间 / 日期 / 星期 / 步数 / 电量"),
    dict(fid="mk06", name="石墨终端", layout="terminal", accent=(184, 196, 208), bg=(16, 19, 24),
         big=(225, 230, 238), small=(165, 175, 188), dim=(105, 115, 128),
         note="时间 / 日期 / 星期 / 步数 / 电量"),
    dict(fid="mk07", name="樱粉终端", layout="terminal", accent=(245, 168, 192), bg=(20, 13, 17),
         big=(248, 232, 238), small=(210, 168, 184), dim=(140, 95, 112),
         note="时间 / 日期 / 星期 / 步数 / 电量"),
    dict(fid="mk08", name="葡紫终端", layout="terminal", accent=(177, 140, 255), bg=(15, 12, 20),
         big=(240, 234, 250), small=(180, 165, 215), dim=(115, 98, 150),
         note="时间 / 日期 / 星期 / 步数 / 电量"),
]


def main() -> None:
    os.makedirs(WORK, exist_ok=True)
    market = os.path.join(REPO, "market")
    shutil.rmtree(os.path.join(market, "faces"), ignore_errors=True)
    shutil.rmtree(os.path.join(market, "previews"), ignore_errors=True)
    os.makedirs(os.path.join(market, "faces"), exist_ok=True)
    os.makedirs(os.path.join(market, "previews"), exist_ok=True)

    entries = []
    for idx, spec in enumerate(FACES, start=1):
        fid = spec["fid"]
        watchface_id = 0x4D4B0000 + idx  # 'MK' 前缀 + 序号，每张唯一
        folder = os.path.join(WORK, fid)
        shutil.rmtree(folder, ignore_errors=True)

        imgs, bg, prev, json_spec = build_assets(spec)
        write_face_folder(folder, imgs, bg, prev, json_spec)

        bin_name = f"{fid}.bin"
        run_wfjs(f"writeBin -i market_build/{fid} -m miband5")
        # wfjs 的输出名取输入目录的 basename，落在 CWD（NPM_DIR）根下
        raw_bin = os.path.join(NPM_DIR, f"{fid}_packed.bin")

        # 补丁 UIHH 头的表盘 ID（offset 18..21，小端）→ 每张唯一
        data = bytearray(open(raw_bin, "rb").read())
        assert data[:4] == b"UIHH", f"{fid}: 不是 UIHH 容器"
        old_id = struct.unpack_from("<I", data, UIHH_ID_OFFSET)[0]
        struct.pack_into("<I", data, UIHH_ID_OFFSET, watchface_id)
        patched = os.path.join(NPM_DIR, f"market_build/{fid}_id.bin")
        open(patched, "wb").write(data)

        # 往返校验：补丁后的包必须还能被完整解析
        run_wfjs(f"readBin -i market_build/{fid}_id.bin -m miband5")
        if os.path.exists(os.path.join(NPM_DIR, f"market_build/{fid}_id_extracted")):
            shutil.rmtree(os.path.join(NPM_DIR, f"market_build/{fid}_id_extracted"))

        size = len(data)
        crc = zlib.crc32(data) & 0xFFFFFFFF
        shutil.copy(patched, os.path.join(market, "faces", bin_name))
        prev.save(os.path.join(market, "previews", f"{fid}.png"))
        entries.append({
            "id": fid,
            "name": spec["name"],
            "author": "shouhuan 自制",
            "source": "本仓库用 watchface-js 生成",
            "license": "CC0-1.0",
            "file": f"faces/{bin_name}",
            "preview": f"previews/{fid}.png",
            "sizeBytes": size,
            "crc32": f"{crc:08x}",
            "note": spec["note"],
        })
        print(f"  {fid} {spec['name']}: id 0x{old_id:08x} -> 0x{watchface_id:08x}, {size} bytes, crc {crc:08x}")

    index = {
        "version": 1,
        "updated": "2026-09-12",
        "faces": entries,
    }
    with open(os.path.join(market, "index.json"), "w", encoding="utf-8") as fp:
        json.dump(index, fp, indent=2, ensure_ascii=False)
    print(f"market/ 完成：{len(entries)} 张")


if __name__ == "__main__":
    sys.exit(main())
