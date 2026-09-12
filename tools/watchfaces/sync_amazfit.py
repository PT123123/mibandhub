# 同步 amazfitwatchfaces.com 的免费 Mi Band 5 表盘到 market/index.json。
#
# 为什么是「PC 爬一次做快照」而不是 App 内实时爬：
#   站点有反爬（下载要浏览器 UA + Referer，否则回一个 HTML 说明页），
#   App 每次刷新都爬几百个详情页既慢又失礼。快照推到仓库后，
#   App 走 jsDelivr/raw 拉目录，下载时才直连源站（带 UA + Referer）。
#
# 只收免费表盘：站点页面上没有可靠的付费标记（付费说明是每页都有的全局
# 弹窗文案），所以用实证判定 —— 对每张的下载地址做 Range 探测，前 4 字节
# 是 UIHH 魔数（真实 .bin）才算数；付费锁 / 反爬页探出来的不是 .bin，自然被跳过。
#
# 用法：python tools/watchfaces/sync_amazfit.py [--pages 12] [--cap 150]
#   --pages  拉多少页 fresh 列表（每页 16 张候选）
#   --cap    目录最多收多少张
#   详情页有磁盘缓存（系统临时目录），重跑增量、不重复请求。
from __future__ import annotations

import argparse
import json
import os
import re
import sys
import tempfile
import time
import urllib.request

BASE = "https://amazfitwatchfaces.com"
UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36")
FREE_COOKIE = "combyfilters=free"   # 站点的免费过滤是 Cookie 实现的
DETAIL_DELAY = 0.35                 # 详情页之间的礼貌间隔（秒）

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
CACHE = os.path.join(tempfile.gettempdir(), "amazfit-watchface-cache")


def http_get(url: str, cookie: str | None = None, referer: str | None = None,
             range_header: str | None = None, retries: int = 2) -> tuple[int, bytes, dict]:
    """返回 (状态码, body, headers)。网络抖动重试。"""
    headers = {"User-Agent": UA}
    if cookie:
        headers["Cookie"] = cookie
    if referer:
        headers["Referer"] = referer
    if range_header:
        headers["Range"] = range_header
    last_err = None
    for _ in range(retries + 1):
        try:
            req = urllib.request.Request(url, headers=headers)
            with urllib.request.urlopen(req, timeout=20) as resp:
                return resp.status, resp.read(), dict(resp.headers)
        except urllib.error.HTTPError as e:
            if e.code in (404, 410):
                return e.code, b"", {}
            last_err = e
        except Exception as e:  # 网络错误重试
            last_err = e
        time.sleep(1.2)
    raise RuntimeError(f"拉取失败 {url}: {last_err}")


def parse_listing(html: str) -> list[dict]:
    """列表页卡片：id / 名字 / 缩略图 / 作者。"""
    faces = []
    for part in re.split(r'<div class="panel wf-panel"', html)[1:]:
        idm = re.search(r'view/(\d+)', part)
        if not idm:
            continue
        namem = (re.search(r'wf-panel[^>]*title="([^"]*)"', part)
                 or re.search(r'alt="([^"]*)"', part))
        thumbm = re.search(r'src="(/storage/[^"]+)"', part)
        authorm = re.search(r'ucp/\d+"[^>]*>([^<]+)<', part)
        faces.append({
            "id": idm.group(1),
            "name": (namem.group(1) if namem else f"表盘 {idm.group(1)}").strip(),
            "thumb": thumbm.group(1) if thumbm else "",
            "author": authorm.group(1).strip() if authorm else "未知作者",
        })
    return faces


def parse_detail(html: str) -> dict:
    dl = re.search(r'href="(/dl/mi-band-5/bin/[^"]+\.bin)"', html)
    og = re.search(r'property="og:image" content="([^"]+)"', html)
    lic = re.search(r'licensed under ([^<"<]+)', html)
    return {
        "dl": dl.group(1) if dl else "",
        "og": og.group(1) if og else "",
        "license": lic.group(1).strip().rstrip(".") if lic else "",
    }


UIHH = b"UIHH"


def probe_bin(dl_url: str, page_url: str) -> tuple[bool, int]:
    """
    探测下载地址：前 4 字节必须是 UIHH 魔数（真实 .bin），顺带拿真实大小。
    站点对非浏览器请求回的是 HTML 说明页、付费面可能不给包 —— 这些都探不过，
    探不过的直接跳过。「爬到的 = 可下载且是合法 .bin」。
    注意：站点的 dl 端点可能无视 Range 头回整包 —— 只 read(4) 就断开，
    别把整个文件拉下来。
    """
    try:
        req = urllib.request.Request(
            dl_url, headers={"User-Agent": UA, "Referer": page_url, "Range": "bytes=0-3"})
        with urllib.request.urlopen(req, timeout=20) as resp:
            head = resp.read(4)
            ok = head == UIHH
            # 站点 dl 端点是 chunked（无 Content-Length），真实大小在自定义头里
            size_hdr = resp.headers.get("aw-content-length", "")
            size = int(size_hdr) if size_hdr.isdigit() else 0
            if resp.status == 206:
                m = re.search(r'/(\d+)$', resp.headers.get("Content-Range", ""))
                size = int(m.group(1)) if m else size
            return (ok and size > 0), (size if ok else 0)
    except Exception:
        pass
    return False, 0


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--pages", type=int, default=12)
    ap.add_argument("--cap", type=int, default=150)
    args = ap.parse_args()
    os.makedirs(CACHE, exist_ok=True)

    # ---- 1) 列表页：收集候选（免费 Cookie 过滤）----
    candidates: dict[str, dict] = {}
    for page in range(1, args.pages + 1):
        url = f"{BASE}/mi-band-5/fresh/p/{page}"
        _, html, _ = http_get(url, cookie=FREE_COOKIE)
        cards = parse_listing(html.decode("utf-8", errors="replace"))
        print(f"  fresh/p/{page}: {len(cards)} 张")
        if not cards:
            break
        for c in cards:
            candidates.setdefault(c["id"], c)
        time.sleep(0.3)

    print(f"候选共 {len(candidates)} 张，开始抓详情页 + 探测 .bin（探不过的跳过）…")

    # ---- 2) 详情页 + 探测：dl 地址 / 真实大小 / 授权 ----
    entries = []
    skipped = 0
    for fid, card in candidates.items():
        if len(entries) >= args.cap:
            break
        cache_file = os.path.join(CACHE, f"{fid}.html")
        if os.path.isfile(cache_file) and os.path.getsize(cache_file) > 1000:
            html = open(cache_file, encoding="utf-8", errors="replace").read()
        else:
            page_url = f"{BASE}/mi-band-5/view/{fid}"
            _, body, _ = http_get(page_url)
            html = body.decode("utf-8", errors="replace")
            open(cache_file, "w", encoding="utf-8").write(html)
            time.sleep(DETAIL_DELAY)
        d = parse_detail(html)
        if not d["dl"]:
            skipped += 1
            continue

        page_url = f"{BASE}/mi-band-5/view/{fid}"
        ok, size = probe_bin(BASE + d["dl"], page_url)
        if not ok:
            skipped += 1
            print(f"  跳过 az{fid}（下载地址探不出合法 .bin）")
            continue
        if size > 615 * 1024:
            skipped += 1
            print(f"  跳过 az{fid}（{size} 字节，超过手环 615KB 上限）")
            continue

        preview = d["og"] or card["thumb"]
        if preview.startswith("//"):
            preview = "https:" + preview
        elif preview.startswith("/"):
            preview = BASE + preview
        entries.append({
            "id": f"az{fid}",
            "name": card["name"][:40],
            "author": card["author"],
            "source": page_url,
            "license": d["license"] if d["license"] else f"© {card['author']}",
            "file": BASE + d["dl"],
            "preview": preview,
            "sizeBytes": size,
            "crc32": "",
            "note": "来自 amazfitwatchfaces.com",
        })
        print(f"  az{fid} {card['name'][:30]} by {card['author']} · {size} bytes")
        time.sleep(DETAIL_DELAY)

    index = {
        "version": 3,
        "updated": time.strftime("%Y-%m-%d"),
        "faces": entries,
    }
    out = os.path.join(REPO, "market", "index.json")
    with open(out, "w", encoding="utf-8") as fp:
        json.dump(index, fp, indent=2, ensure_ascii=False)
    print(f"market/index.json 完成：{len(entries)} 张（源：amazfitwatchfaces.com，仅免费）")
    print("记得：push 后刷 jsDelivr 缓存 → curl https://purge.jsdelivr.net/gh/PT123123/mibandhub@main/market/index.json")


if __name__ == "__main__":
    sys.exit(main())
