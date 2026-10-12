#!/usr/bin/env python3
# 一次性诊断脚本：解析手环管家 DataStore（band.preferences_pb），
# 把「应用名单规则」和「最近推送记录」做交叉比对，找出
#   - 白名单模式下：被放行（ok=true）却不在白名单里的包（该拦没拦）
#   - 想推却没推（ok=false，原因=不在白名单）的应用
# 用法：python analyze_datastore.py <band.preferences_pb 路径>
import sys, json


def read_varint(data, pos):
    result = 0
    shift = 0
    while True:
        b = data[pos]
        pos += 1
        result |= (b & 0x7F) << shift
        if (b & 0x80) == 0:
            break
        shift += 7
    return result, pos


def parse_message(data, pos, end):
    fields = {}
    while pos < end:
        tag, pos = read_varint(data, pos)
        field_num = tag >> 3
        wire_type = tag & 0x7
        if wire_type == 0:
            val, pos = read_varint(data, pos)
            fields.setdefault(field_num, []).append(("varint", val))
        elif wire_type == 2:
            length, pos = read_varint(data, pos)
            val = data[pos:pos + length]
            pos += length
            fields.setdefault(field_num, []).append(("bytes", val))
        elif wire_type == 5:
            val = data[pos:pos + 4]
            pos += 4
            fields.setdefault(field_num, []).append(("fixed32", val))
        elif wire_type == 1:
            val = data[pos:pos + 8]
            pos += 8
            fields.setdefault(field_num, []).append(("fixed64", val))
        else:
            raise ValueError(f"unknown wire type {wire_type}")
    return fields, pos


def decode_value(val_bytes):
    f, _ = parse_message(val_bytes, 0, len(val_bytes))
    # 本项目 datastore 实际 schema（实测）：
    #   1=boolean(varint) 3=integer(varint) 4=long(varint) 5=string(bytes)
    if 5 in f:  # string_value
        return ("string", f[5][0][1].decode("utf-8", "replace"))
    if 1 in f:  # boolean_value
        return ("bool", bool(f[1][0][1]))
    if 3 in f:  # integer_value
        return ("int", f[3][0][1])
    if 4 in f:  # long_value
        return ("long", f[4][0][1])
    return ("unknown", None)


def main():
    if len(sys.argv) < 2:
        print("usage: analyze_datastore.py <band.preferences_pb>")
        sys.exit(1)
    with open(sys.argv[1], "rb") as fh:
        data = fh.read()
    top, _ = parse_message(data, 0, len(data))
    kv = {}
    for wt, val in top.get(1, []):  # repeated Pair
        pf, _ = parse_message(val, 0, len(val))
        key = pf.get(1, [(None, b"")])[0][1].decode("utf-8", "replace")
        vfield = pf.get(2)
        if not vfield:
            kv[key] = ("unknown", None)
            continue
        kv[key] = decode_value(vfield[0][1])

    print(f"== DataStore 解析：共 {len(kv)} 个键 ==")
    for k in sorted(kv):
        t, v = kv[k]
        if t == "string" and len(v) > 80:
            v = v[:80] + f"...(len={len(kv[k][1])})"
        print(f"  [{t}] {k} = {v!r}")

    # ---- 模式 ----
    blacklist_mode = kv.get("app_filter_blacklist", ("bool", False))[1] is True
    mode = "黑名单" if blacklist_mode else "白名单"
    print(f"\n== 当前模式：{mode} (app_filter_blacklist={kv.get('app_filter_blacklist')}) ==")

    # ---- 应用名单 ----
    rules_raw = kv.get("app_rules", ("string", ""))[1]
    rules = []
    for line in rules_raw.splitlines():
        p = line.split("|")
        if len(p) < 3:
            continue
        rules.append({
            "pkg": p[0], "app": p[1],
            "enabled": p[2] == "1",
            "showDetail": (len(p) < 4 or p[3] != "0"),
        })
    print(f"\n== 应用名单：{len(rules)} 条 ({mode}) ==")
    for r in rules:
        flag = "" if r["enabled"] else " ⛔已禁用"
        sd = "" if r["showDetail"] else " (只推标题)"
        print(f"  {r['pkg']}  | {r['app']}{flag}{sd}")

    # ---- 最近推送 ----
    recent_raw = kv.get("recent_notifications", ("string", ""))[1]
    recent = []
    if recent_raw:
        try:
            arr = json.loads(recent_raw)
            for o in arr:
                recent.append({
                    "app": o.get("app", ""),
                    "title": o.get("title", ""),
                    "body": o.get("body", ""),
                    "time": o.get("time", ""),
                    "ok": bool(o.get("ok", False)),
                    "pkg": o.get("pkg", ""),
                    "reason": o.get("reason", ""),
                })
        except Exception as e:
            print(f"  [!] recent_notifications JSON 解析失败：{e}")

    print(f"\n== 最近推送：共 {len(recent)} 条 ==")
    fwd = [r for r in recent if r["ok"]]
    drop = [r for r in recent if not r["ok"]]
    print(f"  已转发 {len(fwd)} 条，未转发 {len(drop)} 条")

    def rule_for(pkg):
        return next((r for r in rules if r["pkg"] == pkg), None)

    # ---- 白名单模式：被放行却不在白名单 ----
    if not blacklist_mode:
        print("\n== 【该拦没拦】白名单模式下被转发、却不在白名单（或已禁用）的包 ==")
        leaks = {}
        for r in fwd:
            pkg = r["pkg"]
            rule = rule_for(pkg)
            if rule is None:
                leaks.setdefault(pkg, {"app": r["app"], "n": 0, "why": "不在名单"})
                leaks[pkg]["n"] += 1
            elif not rule["enabled"]:
                leaks.setdefault(pkg, {"app": r["app"], "n": 0, "why": "已禁用却仍转发(异常)"})
                leaks[pkg]["n"] += 1
        if not leaks:
            print("  （无 —— 所有被转发的包都在白名单里）")
        for pkg, info in sorted(leaks.items(), key=lambda x: -x[1]["n"]):
            print(f"  ⚠ {pkg}  | {info['app']}  →  {info['why']}，共 {info['n']} 条转发")
    else:
        print("\n== 【黑名单模式】被转发的包（名单外才转发）==")
        for r in fwd:
            rule = rule_for(r["pkg"])
            if rule is not None:
                print(f"  ⚠ {r['pkg']}  | {r['app']}  → 在名单内却仍被转发(异常)")

    # ---- 想推没推：因白名单被拦的应用 ----
    print("\n== 因「不在白名单 / 名单禁用」被拦的应用（按出现次数）==")
    blocked = {}
    for r in drop:
        if "不在转发白名单" in r["reason"] or "名单中被禁用" in r["reason"]:
            blocked.setdefault(r["pkg"] or r["app"], {"app": r["app"], "n": 0})
            blocked[r["pkg"] or r["app"]]["n"] += 1
    if not blocked:
        print("  （无）")
    for k, info in sorted(blocked.items(), key=lambda x: -x[1]["n"]):
        print(f"  {k}  | {info['app']}  → 被拦 {info['n']} 次")

    # ---- 噪音包拦截统计（USB / 系统 UI 等）----
    print("\n== 被「系统提示/噪音包」拦掉的包（含 USB/SystemUI，永远推不出）==")
    noise = {}
    for r in drop:
        if "噪音" in r["reason"] or "系统提示" in r["reason"]:
            noise.setdefault(r["pkg"] or r["app"], 0)
            noise[r["pkg"] or r["app"]] += 1
    if not noise:
        print("  （无）")
    for k, n in sorted(noise.items(), key=lambda x: -x[1]):
        print(f"  {k}  → {n} 次")

    # ---- 转发包总览 ----
    print("\n== 被转发的包汇总（去重，标注是否在白名单）==")
    seen = {}
    for r in fwd:
        pkg = r["pkg"] or "(无包名)"
        seen.setdefault(pkg, {"app": r["app"], "n": 0, "inList": rule_for(r["pkg"]) is not None})
        seen[pkg]["n"] += 1
    for pkg, info in sorted(seen.items(), key=lambda x: -x[1]["n"]):
        tag = "✓在名单" if info["inList"] else "✗不在名单"
        print(f"  {pkg}  | {info['app']}  → {info['n']} 次  [{tag}]")


if __name__ == "__main__":
    main()
