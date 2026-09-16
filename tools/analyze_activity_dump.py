#!/usr/bin/env python3
"""离线分析 ActivityLab 落盘的活动样本字节（找字段偏移 / 判睡眠判据用）。

不做任何假设，而是**逐列算统计、逐列看连贯段**，用数据本身回答：

  1. 哪一列像「类型」（取值集中、种类少、大量 0），哪一列像「强度/计数」（取值分散）？
  2. 哪一列的哪个取值是**连续成段**的？成段落在哪个钟点？

2026-09-16 就是靠它定的案：Mi Band 5 的睡眠不在 kind 字节上（kind==0x78 是华米新一代
Zepp OS 机型的格式，MB5 一个都不给），而是「深睡/REM 分期字段被填」（byte6/byte7 != 0x80）。
复盘见 docs/sleep-sync.md。

用法：
    python tools/analyze_activity_dump.py app/build/activity_dump.bin
    python tools/analyze_activity_dump.py <bin> --meta <activity_dump.txt>
    python tools/analyze_activity_dump.py <bin> --start 2026-09-14T20:13 --stride 8 --min-run 25

meta 文件（ActivityLab 同时落盘）里有 startAckHex，起始时刻从那里解出来；
没有 meta 时用 --start 手动指定。--stride 可换采样宽度（4 / 8）试字段错位。
"""

import argparse
import collections
import datetime as dt
import os
import re
import sys

NIGHT_START_HOUR = 21
NIGHT_END_HOUR = 9


def parse_ack_time(hex_str):
    """10 01 01 <count u32le> <年 u16le> <月> <日> <时> <分> <秒> <时区> → datetime。"""
    vals = [int(x, 16) for x in re.findall(r"[0-9a-fA-F]{2}", hex_str or "")]
    if len(vals) < 15:
        return None
    year = vals[7] | (vals[8] << 8)
    try:
        return dt.datetime(year, vals[9], vals[10], vals[11], vals[12], vals[13])
    except ValueError:
        return None


def load_meta(path):
    meta = {}
    if not path or not os.path.isfile(path):
        return meta
    with open(path, "r", encoding="utf-8", errors="replace") as fp:
        for line in fp:
            if "=" in line:
                k, v = line.strip().split("=", 1)
                meta[k] = v
    return meta


def is_night(t):
    return t.hour >= NIGHT_START_HOUR or t.hour < NIGHT_END_HOUR


def print_states(start, data, stride, n, min_run):
    """把整块数据按「粗状态」做 RLE —— 一眼看清一天被切成哪几段。

    状态 = (byte0, byte4, 步数>0, 有浅睡, 有深睡, 有REM, 有心率)。
    睡眠会表现成一整段「步数=0 + 分期有值」，比看单列分布直观得多。
    """
    def state(i):
        o = i * stride
        r = data[o:o + stride]
        return (
            r[0], r[4],
            1 if r[2] else 0,
            r[5],
            1 if r[6] != 0x80 else 0,
            1 if r[7] != 0x80 else 0,
            1 if r[3] != 0xFF else 0,
        )

    print("\n【粗状态 RLE】(byte0, byte4, 步数>0, 浅睡, 深睡!, REM!, 心率!) —— 只列 ≥%d 分钟的段" % min_run)
    i = 0
    shown = 0
    while i < n:
        s = state(i)
        j = i + 1
        while j < n and state(j) == s:
            j += 1
        if j - i >= min_run:
            t0 = start + dt.timedelta(minutes=i) if start else None
            print("  %-12s ×%-5d  %s" % (
                t0.strftime("%m-%d %H:%M") if t0 else "#%d" % i,
                j - i,
                "b0=%02x b4=%02x 步%d 浅%02x 深%s REM%s HR%s" % (
                    s[0], s[1], s[2], s[3], s[4], s[5], s[6]),
            ))
            shown += 1
        i = j
    print("  （共 %d 段，均为 ≥%d 分钟的）" % (shown, min_run))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("bin_path")
    ap.add_argument("--meta", help="ActivityLab 落盘的 activity_dump.txt")
    ap.add_argument("--start", help="起始时刻，meta 里解不出来时手动给，如 2026-09-14T20:13")
    ap.add_argument("--stride", type=int, default=None, help="每采样字节数（默认取 meta，否则 8）")
    ap.add_argument("--top", type=int, default=12, help="每列打印多少个高频取值")
    ap.add_argument("--min-run", type=int, default=20, help="粗状态 RLE 只打印这么长的段")
    args = ap.parse_args()

    meta = load_meta(args.meta)
    start = parse_ack_time(meta.get("startAckHex", ""))
    if start is None and args.start:
        start = dt.datetime.fromisoformat(args.start)
    stride = args.stride or int(meta.get("sampleSizeGuess") or 8)

    with open(args.bin_path, "rb") as fp:
        data = fp.read()

    n = len(data) // stride
    rest = len(data) % stride

    print("=" * 78)
    print("原始字节：%d（%d.2f KB）" % (len(data), len(data) / 1024.0))
    print("stride=%d → 采样 %d 个，余 %d 字节" % (stride, n, rest))
    if rest:
        print("⚠ 余数不为 0：stride 可能不是 %d" % stride)
    if start:
        print("起始时刻：%s（+%d 分钟 → %s）" % (
            start, n, start + dt.timedelta(minutes=n) if n else start))
    else:
        print("⚠ 没有起始时刻：后面的「几点」分析会跳过")
    print("=" * 78)

    cols = [[data[i * stride + off] for i in range(n)] for off in range(stride)]

    # ---- 1. 逐列取值统计 ----
    print("\n【逐列取值统计】取值种类少 + 大量 0 的列像「类型」，分散的像「强度/计数」")
    print("%-6s %8s %8s %8s %6s %6s  %s" % ("列", "种类", "零占比", "min", "max", "夜间%", "高频取值"))
    for off, col in enumerate(cols):
        hist = collections.Counter(col)
        zeros = hist.get(0, 0)
        top = " ".join("%02x×%d" % (v, c) for v, c in hist.most_common(args.top))
        night_share = 0.0
        if start:
            tot = nz = 0
            for i, v in enumerate(col):
                if v:
                    tot += 1
                    if is_night(start + dt.timedelta(minutes=i)):
                        nz += 1
            night_share = (100.0 * nz / tot) if tot else 0.0
        print("%-6s %8d %7.1f%% %8d %6d %5.1f%%  %s" % (
            "byte%d" % off, len(hist), 100.0 * zeros / max(n, 1),
            min(col) if col else -1, max(col) if col else -1, night_share, top))

    # ---- 2. 逐列按时段的均值（睡眠时段 vs 白天） ----
    if start:
        print("\n【逐列每小时均值】睡眠相关列应当在 0-6 点明显区别于 9-18 点")
        header = "%-6s" % "列" + "".join("%5d" % h for h in range(24)) + "   夜均值  日均值"
        print(header)
        for off, col in enumerate(cols):
            night_vals, day_vals = [], []
            hour_sum = [0] * 24
            hour_cnt = [0] * 24
            for i, v in enumerate(col):
                t = start + dt.timedelta(minutes=i)
                hour_sum[t.hour] += v
                hour_cnt[t.hour] += 1
                (night_vals if is_night(t) else day_vals).append(v)
            means = "".join(
                "%5.0f" % (hour_sum[h] / hour_cnt[h]) if hour_cnt[h] else "    -"
                for h in range(24)
            )
            nm = sum(night_vals) / len(night_vals) if night_vals else 0
            dm = sum(day_vals) / len(day_vals) if day_vals else 0
            print("%-6s%s  %6.1f %7.1f" % ("byte%d" % off, means, nm, dm))

    # ---- 3. 候选「类型列」：每个取值的长连贯段落在几点 ----
    if not start:
        print("\n（没有起始时刻，跳过「取值 → 时段」分析）")
        return

    print("\n【每个取值的夜间占比与最长连贯段】")
    print("判据：真正的「睡眠」取值应当只在夜里出现，且能连成若干小时的一段")
    for off, col in enumerate(cols):
        hist = collections.Counter(col)
        if len(hist) > 40:
            continue  # 取值太分散，不像类型列
        rows = []
        for val, cnt in hist.most_common(14):
            idxs = [i for i, v in enumerate(col) if v == val]
            night = sum(1 for i in idxs if is_night(start + dt.timedelta(minutes=i)))
            # 最长连贯段
            best_len = best_start = cur_len = 0
            prev = None
            for i in idxs:
                cur_len = cur_len + 1 if prev is not None and i == prev + 1 else 1
                if cur_len > best_len:
                    best_len, best_start = cur_len, i - cur_len + 1
                prev = i
            t0 = start + dt.timedelta(minutes=best_start) if best_len else None
            rows.append((night / cnt if cnt else 0, val, cnt, night, best_len, t0))
        rows.sort(reverse=True)
        print("  byte%d（%d 种取值）" % (off, len(hist)))
        for share, val, cnt, night, best_len, t0 in rows[:6]:
            print("     %02x ×%-5d 夜间占比%5.1f%%  最长连贯 %3d 分钟%s" % (
                val, cnt, 100 * share, best_len,
                ("（起 %s）" % t0.strftime("%m-%d %H:%M")) if t0 else ""))

    # ---- 4. 「标记 → 连贯段」：把各列的"有数据"定义成标记，直接看它落在一天中的哪一段 ----
    if start:
        print("\n【标记列的连贯段】睡眠观测量应当是「夜里一整段」，用这个判据挑列")
        markers = [
            ("byte2!=0 步数", 2, lambda v: v != 0),
            ("byte3!=255 心率", 3, lambda v: v != 255),
            ("byte5!=0 浅睡强度", 5, lambda v: v != 0),
            ("byte6!=0x80 深睡强度", 6, lambda v: v != 0x80),
            ("byte7!=0x80 REM强度", 7, lambda v: v != 0x80),
            ("byte6|byte7 有分期", 6, lambda v: v != 0x80),
        ]
        for name, off, pred in markers:
            col = cols[off]
            if name.startswith("byte6|byte7"):
                col = [a if a != 0x80 else b for a, b in zip(cols[6], cols[7])]
            idxs = [i for i, v in enumerate(col) if pred(v)]
            hour_hist = collections.Counter(
                (start + dt.timedelta(minutes=i)).hour for i in idxs)
            hr = " ".join("%2d:%d" % (h, hour_hist.get(h, 0)) for h in range(24) if hour_hist.get(h))
            runs = []
            prev = None
            run_start = None
            for i in idxs:
                if prev is not None and i == prev + 1:
                    pass
                else:
                    if run_start is not None:
                        runs.append((run_start, prev - run_start + 1))
                    run_start = i
                prev = i
            if run_start is not None:
                runs.append((run_start, prev - run_start + 1))
            long_runs = [(i, ln) for i, ln in runs if ln >= 30]
            print("  %-22s 标记 %5d 分钟 / %d 段（≥30 分的 %d 段）" % (
                name, len(idxs), len(runs), len(long_runs)))
            print("      分布：%s" % hr)
            for i, ln in long_runs[:6]:
                t0 = start + dt.timedelta(minutes=i)
                print("      ▸ %s → %s（%d 分钟）" % (
                    t0.strftime("%m-%d %H:%M"),
                    (t0 + dt.timedelta(minutes=ln)).strftime("%H:%M"), ln))
    # ---- 5. 粗状态 RLE ----
    if start:
        print_states(start, data, stride, n, args.min_run)


if __name__ == "__main__":
    sys.exit(main())
